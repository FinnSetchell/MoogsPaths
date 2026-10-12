package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.PathCounter;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathType;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PathFinder {
    private PathFinder() {}

    private static final int CELL_SIZE = 4; // A* grid resolution in blocks - one cell covers a 4x4 block area
    private static final float SLOPE_COST_SCALE = 0.5f; // multiplier on slopeDelta^2 added to step cost when rigidness < 1
    private static final int MAX_NATURAL_STEP = 64; // height delta above which a step is forbidden (cliffs, walls)
    private static final int BASE_ITER_CAP = 2_000; // minimum A* iteration budget regardless of path length
    private static final double HEURISTIC_WEIGHT = 3.0; // weighted A* multiplier - >1 makes the search greedy toward the goal
    private static final float DIAG = 1.41421356f; // sqrt(2) - cost of a diagonal step
    private static final int[] DX = {1, 0, -1, 0, 1, -1, -1, 1}; // x offsets for 8-way neighbour expansion (cardinals first, then diagonals)
    private static final int[] DZ = {0, 1, 0, -1, 1, 1, -1, -1}; // z offsets matching DX in the same neighbour order
    private static final float[] STEP_COST = {1f, 1f, 1f, 1f, DIAG, DIAG, DIAG, DIAG}; // base step cost per direction (cardinal=1, diagonal=sqrt(2))
    private static final int GOAL_REROLL_ATTEMPTS = 6; // tries to land the goal angle on a cell that passes the biome predicate
    private static final double ELLIPSE_SLACK = 1.35; // start-goal ellipse inflation - 1.35 leaves ~35% room to detour around obstacles
    private static final double PROXIMITY_FRACTION = 0.10; // bail when bestReachedH drops to this fraction of startToGoal (0.10 = 90% covered)
    private static final int STAGNATION_LIMIT = 200; // bail after this many real pops with no improvement to bestReachedH
    private static final int HEIGHT_UNSET = Integer.MIN_VALUE; // sentinel in the height memo cache meaning "not yet sampled"
    private static final int COARSE_HEIGHT_STRIDE = 8; // A* snaps height samples to this stride so adjacent cells share noise lookups
    private static final long NO_PREV = Long.MIN_VALUE; // sentinel in cameFrom meaning "no predecessor cell"
    private static final double MAX_SHORTCUT_DRIFT = 8.0; // blocks a straightened stretch may stray from the searched route
    private static final int MAX_SHORTCUT_HEIGHT_DIFF = 4; // ground height a shortcut may differ by from the route beside it
    private static final double CORNER_RADIUS = 12.0; // blocks either side of a corner that its curve replaces

    // Primitive variant of BiFunction<Integer,Integer,Integer> - avoids autoboxing per call.
    @FunctionalInterface
    public interface HeightSampler {
        int sampleAt(int x, int z);
    }

    // Primitive variant of BiPredicate<Integer,Integer> - avoids autoboxing per call.
    @FunctionalInterface
    public interface BiomeAccept {
        boolean test(int x, int z);
    }

    // smooth is false only for a path begun before routes were smoothed, which keeps the route it was laid on.
    public static List<BlockPos> findPath(BlockPos origin, PathType pathType, RandomSource random, HeightSampler rawHeightAt, BiomeAccept biomeAccept, boolean smooth) {
        BiomeAccept biome = memoiseBiome(biomeAccept);

        int length = pathType.length().sample(random);

        // Reroll the goal angle a few times to land somewhere the biome gate accepts. Origin
        // enumeration upstream guarantees the start cell passes; the goal does not. If every
        // attempt misses, A* still runs with the last attempt and falls back to bestReachedKey.
        int goalBlockX = origin.getX();
        int goalBlockZ = origin.getZ();
        for(int attempt = 0; attempt < GOAL_REROLL_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            goalBlockX = origin.getX() + (int) Math.round(Math.cos(angle) * length);
            goalBlockZ = origin.getZ() + (int) Math.round(Math.sin(angle) * length);
            if(biome == null || biome.test(goalBlockX, goalBlockZ)) break;
        }

        return solve(origin, goalBlockX, goalBlockZ, length, pathType, rawHeightAt, biome, smooth);
    }

    /**
     * A path from origin towards a goal the caller already chose, e.g. one leading away from a
     * structure. Cells failing biomeAccept are gated out as in {@link #findPath}; the origin's cell
     * never is.
     */
    public static List<BlockPos> findPathTo(BlockPos origin, int goalBlockX, int goalBlockZ, int length, PathType pathType, HeightSampler rawHeightAt, BiomeAccept biomeAccept, boolean smooth) {
        return solve(origin, goalBlockX, goalBlockZ, length, pathType, rawHeightAt, memoiseBiome(biomeAccept), smooth);
    }

    private static List<BlockPos> solve(BlockPos origin, int goalBlockX, int goalBlockZ, int length, PathType pathType, HeightSampler rawHeightAt, BiomeAccept biome, boolean smooth) {
        // A* only needs heights for the slope filter and step cost, both tolerant of coarse
        // approximations. Snapping to an 8-block grid shares one noise eval across 4 cells.
        CoarseHeights coarseHeightAt = new CoarseHeights(rawHeightAt);
        // interpolateToBlocks wants per-block heights so the rasteriser sees a smooth Y curve.
        HeightSampler preciseHeightAt = memoise(rawHeightAt);

        int startCellX = Math.floorDiv(origin.getX(), CELL_SIZE);
        int startCellZ = Math.floorDiv(origin.getZ(), CELL_SIZE);
        int goalCellX = Math.floorDiv(goalBlockX, CELL_SIZE);
        int goalCellZ = Math.floorDiv(goalBlockZ, CELL_SIZE);

        List<int[]> cellPath = astarCells(
            startCellX, startCellZ, goalCellX, goalCellZ,
            pathType.rigidness(), length, coarseHeightAt, biome);

        // The search moves in 8 directions on its grid. Straightening and rounding the route it found
        // takes out its kinks and turns its corners into curves, without another height sample.
        List<int[]> route = new ArrayList<>(cellPath.size());
        for(int[] cell : cellPath) route.add(new int[]{cell[0] * CELL_SIZE, cell[1] * CELL_SIZE});
        if(smooth) route = roundCorners(straighten(route, coarseHeightAt, biome), biome);

        List<BlockPos> blockWaypoints = interpolateToBlocks(route, preciseHeightAt, origin.getY());
        carverSmooth(blockWaypoints, pathType.carver());
        smoothHeights(blockWaypoints);
        return blockWaypoints;
    }

    //////////////////////////////

    // Step cost = distance (cardinal=1, diagonal=sqrt(2)) + slope_delta^2 scaled by (1-rigidness).
    // Cells failing biomeAccept are gated out of expansion so the path stays inside the source biome.
    // On any non-success exit (biome boundary, slope walls, iter cap, bail) returns a path to the
    // closest-to-goal in-biome cell reached - never null.
    private static List<int[]> astarCells(int startX, int startZ, int goalX, int goalZ, float rigidness, int length, HeightSampler heightAt, BiomeAccept biomeAccept) {
        long startKey = packCell(startX, startZ);
        long goalKey = packCell(goalX, goalZ);

        Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        cameFrom.defaultReturnValue(NO_PREV);
        Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
        gScore.defaultReturnValue(Double.POSITIVE_INFINITY);
        OpenHeap open = new OpenHeap(64);

        int startYRaw = heightAt.sampleAt(startX * CELL_SIZE, startZ * CELL_SIZE);
        gScore.put(startKey, 0.0);
        open.push(startKey, HEURISTIC_WEIGHT * octile(startX, startZ, goalX, goalZ), startYRaw);

        // length is straight-line block distance; CELL_SIZE=4 makes it 4x the cell-space
        // distance to the goal, which is roughly the slack a weighted-A* search needs to
        // detour around obstacles before giving up.
        int iterCap = Math.max(BASE_ITER_CAP, length);
        int iters = 0;

        // Ellipse with foci at start and goal. A cell stays in-bounds while
        // octile(cell,start) + octile(cell,goal) <= ellipseSum. The slack lets A* detour
        // around obstacles; the +4 is a floor so short paths still have a few cells of room.
        double startToGoal = octile(startX, startZ, goalX, goalZ);
        double ellipseSum = startToGoal * ELLIPSE_SLACK + 4.0;

        long bestReachedKey = startKey;
        double bestReachedH = startToGoal;
        double proximityH = startToGoal * PROXIMITY_FRACTION;
        // stagnation only starts counting after the first improvement so paths through
        // monotonously bad-h initial regions don't get punished before they get going
        boolean improvedOnce = false;
        int popsSinceImprovement = 0;

        List<int[]> result = null;
        while(!open.isEmpty() && iters++ < iterCap) {
            open.poll();
            long curKey = open.topKey;
            double curHeapF = open.topF;
            int curYRaw = open.topY;

            int cx = unpackX(curKey);
            int cz = unpackZ(curKey);
            double curG = gScore.get(curKey);
            double curF = curG + HEURISTIC_WEIGHT * octile(cx, cz, goalX, goalZ);
            // skip stale queue entries whose gScore has since improved
            if(curHeapF > curF + 1e-6) continue;

            // Biome gate: don't branch through cells outside the source biome. Start cell is
            // exempt because the caller already verified origin biome before calling findPath.
            if(curKey != startKey && biomeAccept != null && !biomeAccept.test(cx * CELL_SIZE, cz * CELL_SIZE)) continue;

            if(curKey == goalKey) {
                result = reconstruct(cameFrom, startKey, goalKey);
                break;
            }

            double h = octile(cx, cz, goalX, goalZ);
            if(h < bestReachedH) {
                bestReachedH = h;
                bestReachedKey = curKey;
                improvedOnce = true;
                popsSinceImprovement = 0;
                if(bestReachedH <= proximityH) {
                    if(Constants.ENABLE_DEBUG_TIMER) PathDataManager.addPathCounter(PathCounter.PATH_BAILED_ON_PROXIMITY, 1);
                    result = reconstruct(cameFrom, startKey, bestReachedKey);
                    break;
                }
            } else if(improvedOnce) {
                popsSinceImprovement++;
                if(popsSinceImprovement > STAGNATION_LIMIT) {
                    if(Constants.ENABLE_DEBUG_TIMER) PathDataManager.addPathCounter(PathCounter.PATH_BAILED_ON_STAGNATION, 1);
                    result = reconstruct(cameFrom, startKey, bestReachedKey);
                    break;
                }
            }

            // negative sampler returns are treated as y=0 for slope maths
            int curY = curYRaw < 0 ? 0 : curYRaw;

            for(int d = 0; d < 8; d++) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if(octile(nx, nz, startX, startZ) + octile(nx, nz, goalX, goalZ) > ellipseSum) continue;
                int nyRaw = heightAt.sampleAt(nx * CELL_SIZE, nz * CELL_SIZE);
                int ny = nyRaw < 0 ? curY : nyRaw;
                int slopeDelta = Math.abs(ny - curY);
                if(slopeDelta > MAX_NATURAL_STEP) continue;

                double stepCost = STEP_COST[d] + (double) slopeDelta * slopeDelta * (1.0 - rigidness) * SLOPE_COST_SCALE;
                double tentativeG = curG + stepCost;
                long neighKey = packCell(nx, nz);
                double prev = gScore.get(neighKey);
                if(tentativeG < prev) {
                    cameFrom.put(neighKey, curKey);
                    gScore.put(neighKey, tentativeG);
                    open.push(neighKey, tentativeG + HEURISTIC_WEIGHT * octile(nx, nz, goalX, goalZ), nyRaw);
                }
            }
        }
        if(Constants.ENABLE_DEBUG_TIMER) PathDataManager.addPathCounter(PathCounter.A_STAR_ITERATIONS, iters);
        if(result == null) result = reconstruct(cameFrom, startKey, bestReachedKey);
        return result;
    }

    private static List<int[]> reconstruct(Long2LongOpenHashMap cameFrom, long startKey, long goalKey) {
        List<int[]> path = new ArrayList<>();
        long k = goalKey;
        path.add(new int[]{unpackX(k), unpackZ(k)});
        while(k != startKey) {
            long prev = cameFrom.get(k);
            if(prev == NO_PREV) break;
            k = prev;
            path.add(new int[]{unpackX(k), unpackZ(k)});
        }
        Collections.reverse(path);
        return path;
    }

    // Octile distance - admissible heuristic for 8-way movement with cardinal=1 / diagonal=sqrt(2).
    private static double octile(int x1, int z1, int x2, int z2) {
        int dx = Math.abs(x1 - x2);
        int dz = Math.abs(z1 - z2);
        return (dx + dz) + (DIAG - 2.0) * Math.min(dx, dz);
    }

    private static long packCell(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private static int unpackX(long k) { return (int) (k >> 32); }
    private static int unpackZ(long k) { return (int) (k & 0xFFFFFFFFL); }

    //////////////////////////////

    // Primitive min-heap on (key, f, y). After poll() the popped values live in topKey/topF/topY.
    private static final class OpenHeap {
        private long[] keys;
        private double[] fs;
        private int[] ys;
        private int size;

        long topKey;
        double topF;
        int topY;

        OpenHeap(int initialCapacity) {
            this.keys = new long[initialCapacity];
            this.fs = new double[initialCapacity];
            this.ys = new int[initialCapacity];
        }

        boolean isEmpty() { return size == 0; }

        void push(long key, double f, int y) {
            if(size == keys.length) grow();
            int i = size++;
            keys[i] = key;
            fs[i] = f;
            ys[i] = y;
            while(i > 0) {
                int parent = (i - 1) >>> 1;
                if(fs[i] < fs[parent]) {
                    swap(i, parent);
                    i = parent;
                } else break;
            }
        }

        void poll() {
            topKey = keys[0];
            topF = fs[0];
            topY = ys[0];
            int last = --size;
            if(last > 0) {
                keys[0] = keys[last];
                fs[0] = fs[last];
                ys[0] = ys[last];
                int i = 0;
                int half = size >>> 1;
                while(i < half) {
                    int left = (i << 1) + 1;
                    int right = left + 1;
                    int smallest = (right < size && fs[right] < fs[left]) ? right : left;
                    if(fs[smallest] < fs[i]) {
                        swap(i, smallest);
                        i = smallest;
                    } else break;
                }
            }
        }

        private void swap(int a, int b) {
            long tk = keys[a]; keys[a] = keys[b]; keys[b] = tk;
            double tf = fs[a]; fs[a] = fs[b]; fs[b] = tf;
            int ty = ys[a]; ys[a] = ys[b]; ys[b] = ty;
        }

        private void grow() {
            int newCap = keys.length + (keys.length >> 1);
            keys = Arrays.copyOf(keys, newCap);
            fs = Arrays.copyOf(fs, newCap);
            ys = Arrays.copyOf(ys, newCap);
        }
    }

    //////////////////////////////

    //////////////////////////////
    // Route smoothing

    // Replaces stretches of the route with straight lines: from each kept point, on to the farthest
    // later point the route can be cut straight to.
    private static List<int[]> straighten(List<int[]> route, CoarseHeights heights, BiomeAccept biome) {
        int n = route.size();
        if(n < 3) return route;
        List<int[]> out = new ArrayList<>();
        out.add(route.get(0));
        int i = 0;
        while(i < n - 1) {
            int j = i + 1;
            for(int k = i + 2; k < n; k++) {
                if(!shortcutOk(route, i, k, heights, biome)) break;
                j = k;
            }
            out.add(route.get(j));
            i = j;
        }
        return out;
    }

    // A straight line from route point i to k is fine when every point it skips stays close to it,
    // in a biome the path may cross, over ground about as high as the route had there. Heights are
    // only the ones the search already sampled: where it has none the line is taken on trust, as it
    // is never more than MAX_SHORTCUT_DRIFT from ground the search did accept.
    private static boolean shortcutOk(List<int[]> route, int i, int k, CoarseHeights heights, BiomeAccept biome) {
        int[] a = route.get(i), b = route.get(k);
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double lengthSq = dx * dx + dz * dz;
        for(int m = i + 1; m < k; m++) {
            int[] p = route.get(m);
            double t = lengthSq == 0 ? 0 : Math.max(0.0, Math.min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / lengthSq));
            double qx = a[0] + t * dx, qz = a[1] + t * dz;
            double drift = Math.hypot(p[0] - qx, p[1] - qz);
            if(drift > MAX_SHORTCUT_DRIFT) return false;
            if(drift < 0.5) continue;
            int cellX = Math.floorDiv((int) Math.round(qx), CELL_SIZE) * CELL_SIZE;
            int cellZ = Math.floorDiv((int) Math.round(qz), CELL_SIZE) * CELL_SIZE;
            if(biome != null && !biome.test(cellX, cellZ)) return false;
            int routeY = heights.known(p[0], p[1]);
            int lineY = heights.known(cellX, cellZ);
            if(routeY >= 0 && lineY >= 0 && Math.abs(lineY - routeY) > MAX_SHORTCUT_HEIGHT_DIFF) return false;
        }
        return true;
    }

    // Replaces each corner with a curve that leaves the route up to CORNER_RADIUS before the corner
    // and rejoins it as far after. A corner whose curve would leave the path's biomes stays sharp.
    private static List<int[]> roundCorners(List<int[]> route, BiomeAccept biome) {
        int n = route.size();
        if(n < 3) return route;
        List<int[]> out = new ArrayList<>();
        out.add(route.get(0));
        for(int i = 1; i < n - 1; i++) {
            int[] before = route.get(i - 1), corner = route.get(i), after = route.get(i + 1);
            double lenIn = Math.hypot(corner[0] - before[0], corner[1] - before[1]);
            double lenOut = Math.hypot(after[0] - corner[0], after[1] - corner[1]);
            double radius = Math.min(CORNER_RADIUS, Math.min(lenIn, lenOut) / 2.0);
            List<int[]> curve = radius < 1.5 ? null : cornerCurve(before, corner, after, radius / lenIn, radius / lenOut, biome);
            if(curve == null) addPoint(out, corner);
            else for(int[] point : curve) addPoint(out, point);
        }
        addPoint(out, route.get(n - 1));
        return out;
    }

    // Five points of the quadratic curve from a point on the way in to one on the way out, pulled
    // towards the corner. Null when one of them is outside the path's biomes.
    private static List<int[]> cornerCurve(int[] before, int[] corner, int[] after, double shareIn, double shareOut, BiomeAccept biome) {
        double startX = corner[0] + (before[0] - corner[0]) * shareIn, startZ = corner[1] + (before[1] - corner[1]) * shareIn;
        double endX = corner[0] + (after[0] - corner[0]) * shareOut, endZ = corner[1] + (after[1] - corner[1]) * shareOut;
        List<int[]> curve = new ArrayList<>(5);
        for(int step = 0; step <= 4; step++) {
            double t = step / 4.0, u = 1.0 - t;
            int x = (int) Math.round(u * u * startX + 2 * t * u * corner[0] + t * t * endX);
            int z = (int) Math.round(u * u * startZ + 2 * t * u * corner[1] + t * t * endZ);
            if(biome != null && !biome.test(Math.floorDiv(x, CELL_SIZE) * CELL_SIZE, Math.floorDiv(z, CELL_SIZE) * CELL_SIZE)) return null;
            curve.add(new int[]{x, z});
        }
        return curve;
    }

    private static void addPoint(List<int[]> points, int[] point) {
        int[] last = points.get(points.size() - 1);
        if(last[0] != point[0] || last[1] != point[1]) points.add(point);
    }

    //////////////////////////////

    // Expands the route's points to block-dense waypoints. Drops consecutive duplicate (x,z).
    //
    // Heights are the bulk of a path's cost (a noise column per sample), so every other block is
    // sampled, and a block between two samples of the same height takes that height; any other
    // block is sampled too. On real paths that skips about a third of the samples and leaves one
    // height in a thousand off, nearly always by a single block. The route itself never changes.
    private static List<BlockPos> interpolateToBlocks(List<int[]> route, HeightSampler heightAt, int originY) {
        LongArrayList points = new LongArrayList();
        long[] lastKey = {Long.MIN_VALUE};
        for(int i = 0; i < route.size() - 1; i++) {
            int[] a = route.get(i);
            int[] b = route.get(i + 1);
            PathGeometryUtils.bresenham(a[0], a[1], b[0], b[1], (px, pz) -> {
                long pk = ((long) px << 32) | (pz & 0xFFFFFFFFL);
                if(pk == lastKey[0]) return;
                lastKey[0] = pk;
                points.add(pk);
            });
        }
        List<BlockPos> result = new ArrayList<>(points.size());
        if(points.isEmpty()) {
            if(!route.isEmpty()) {
                int[] a = route.get(0);
                int y = heightAt.sampleAt(a[0], a[1]);
                result.add(new BlockPos(a[0], y < 0 ? originY : y, a[1]));
            }
            return result;
        }

        int n = points.size();
        int[] ys = new int[n];
        for(int m = 0; m < n; m += 2) ys[m] = sample(heightAt, points.getLong(m), originY);
        if(n % 2 == 0) ys[n - 1] = sample(heightAt, points.getLong(n - 1), originY);
        for(int m = 1; m < n - 1; m += 2) {
            ys[m] = ys[m - 1] == ys[m + 1] ? ys[m - 1] : sample(heightAt, points.getLong(m), originY);
        }
        for(int m = 0; m < n; m++) {
            long pk = points.getLong(m);
            result.add(new BlockPos((int) (pk >> 32), ys[m], (int) pk));
        }
        return result;
    }

    private static int sample(HeightSampler heightAt, long packed, int fallbackY) {
        int y = heightAt.sampleAt((int) (packed >> 32), (int) packed);
        return y < 0 ? fallbackY : y;
    }

    //////////////////////////////

    // Two-pass Y clamp: after both passes, no consecutive Y difference exceeds maxStep.
    // carver=0 -> maxStep=MAX_NATURAL_STEP (no smoothing). carver=1 -> maxStep=1 (fully flat).
    private static void carverSmooth(List<BlockPos> waypoints, float carver) {
        if(waypoints.size() < 2) return;
        int maxStep = Math.max(1, Math.round((MAX_NATURAL_STEP - 1) * (1f - carver) + 1f));
        BlockPos prev = waypoints.get(0);
        for(int i = 1; i < waypoints.size(); i++) {
            BlockPos cur = waypoints.get(i);
            int clamped = clampStep(cur.getY(), prev.getY(), maxStep);
            if(clamped != cur.getY()) {
                cur = new BlockPos(cur.getX(), clamped, cur.getZ());
                waypoints.set(i, cur);
            }
            prev = cur;
        }
        BlockPos next = waypoints.get(waypoints.size() - 1);
        for(int i = waypoints.size() - 2; i >= 0; i--) {
            BlockPos cur = waypoints.get(i);
            int clamped = clampStep(cur.getY(), next.getY(), maxStep);
            if(clamped != cur.getY()) {
                cur = new BlockPos(cur.getX(), clamped, cur.getZ());
                waypoints.set(i, cur);
            }
            next = cur;
        }
    }

    private static int clampStep(int value, int anchor, int maxStep) {
        if(value > anchor + maxStep) return anchor + maxStep;
        if(value < anchor - maxStep) return anchor - maxStep;
        return value;
    }

    // Softens steep steps between neighbouring waypoints: each takes 3/4 of its own height and 1/8
    // of each neighbour's. Waypoints stay one per block, so spacing and fade count blocks. (This was
    // a Chaikin pass, which on block-dense waypoints only duplicated them, apart from this smoothing.)
    private static void smoothHeights(List<BlockPos> waypoints) {
        if(waypoints.size() < 3) return;
        int[] ys = new int[waypoints.size()];
        for(int i = 0; i < ys.length; i++) ys[i] = waypoints.get(i).getY();
        for(int i = 1; i < ys.length - 1; i++) {
            int y = Math.round(0.125f * ys[i - 1] + 0.75f * ys[i] + 0.125f * ys[i + 1]);
            if(y != ys[i]) {
                BlockPos p = waypoints.get(i);
                waypoints.set(i, new BlockPos(p.getX(), y, p.getZ()));
            }
        }
    }

    private static HeightSampler memoise(HeightSampler source) {
        Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
        cache.defaultReturnValue(HEIGHT_UNSET);
        return (x, z) -> {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            int v = cache.get(key);
            if(v != HEIGHT_UNSET) return v;
            int computed = source.sampleAt(x, z);
            cache.put(key, computed);
            return computed;
        };
    }

    // Snaps (x, z) down to a COARSE_HEIGHT_STRIDE-aligned anchor before sampling. Any A* cell
    // inside the same stride tile shares one noise evaluation. The slope filter and slope cost
    // both tolerate this approximation; the worst case is a couple of adjacent cells claiming
    // the same Y when terrain is moving fast, which the post-A* carver/chaikin pass smooths out.
    private static final class CoarseHeights implements HeightSampler {
        private final HeightSampler source;
        private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();

        CoarseHeights(HeightSampler source) {
            this.source = source;
            cache.defaultReturnValue(HEIGHT_UNSET);
        }

        @Override
        public int sampleAt(int x, int z) {
            int sx = Math.floorDiv(x, COARSE_HEIGHT_STRIDE) * COARSE_HEIGHT_STRIDE;
            int sz = Math.floorDiv(z, COARSE_HEIGHT_STRIDE) * COARSE_HEIGHT_STRIDE;
            long key = ((long) sx << 32) | (sz & 0xFFFFFFFFL);
            int v = cache.get(key);
            if(v != HEIGHT_UNSET) return v;
            int computed = source.sampleAt(sx, sz);
            cache.put(key, computed);
            return computed;
        }

        // The height already sampled for this spot's tile, or HEIGHT_UNSET. Never samples.
        int known(int x, int z) {
            int sx = Math.floorDiv(x, COARSE_HEIGHT_STRIDE) * COARSE_HEIGHT_STRIDE;
            int sz = Math.floorDiv(z, COARSE_HEIGHT_STRIDE) * COARSE_HEIGHT_STRIDE;
            return cache.get(((long) sx << 32) | (sz & 0xFFFFFFFFL));
        }
    }

    private static BiomeAccept memoiseBiome(BiomeAccept source) {
        if(source == null) return null;
        Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
        cache.defaultReturnValue(0);
        return (x, z) -> {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            int v = cache.get(key);
            if(v != 0) {
                if(Constants.ENABLE_DEBUG_TIMER) PathDataManager.addPathCounter(PathCounter.PATHFINDER_BIOME_MEMO_HIT, 1);
                return v == 1;
            }
            boolean result = source.test(x, z);
            cache.put(key, result ? 1 : 2);
            return result;
        };
    }
}
