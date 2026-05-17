package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.PathCounter;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathType;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

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
    private static final int COARSE_HEIGHT_STRIDE = 16; // A* snaps height samples to this stride so adjacent cells share noise lookups
    private static final long NO_PREV = Long.MIN_VALUE; // sentinel in cameFrom meaning "no predecessor cell"

    // Curl noise pushes paths off the straight line so flat-terrain routes don't render as
    // long perfect lines. Per-cell simplex evaluation is cheap and the wavelength is set so
    // a typical 300-1000 block path crosses 4-10 noise zero-crossings (i.e. visible bends).
    // CURL_STRENGTH is the noise weight added to step cost; STEP_COST cardinals are 1.0 so
    // values 0.0-0.5 are sane. CURL_SCALE in 1/cells; 0.05 ≈ a 20-cell (80-block) wavelength.
    private static final double CURL_STRENGTH = 0.35;
    private static final double CURL_SCALE = 0.05;
    private static final SimplexNoise CURL_NOISE = new SimplexNoise(RandomSource.create(0x6D75D5A06A7C9F1FL));

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

    public static List<BlockPos> findPath(BlockPos origin, PathType pathType, RandomSource random, HeightSampler rawHeightAt, BiomeAccept biomeAccept) {
        // A* only needs heights for the slope filter and step cost, both tolerant of coarse
        // approximations. Snapping to a 16-block grid shares one noise eval across 16 cells.
        HeightSampler coarseHeightAt = memoiseCoarse(rawHeightAt);
        // interpolateToBlocks wants per-block heights so the rasteriser sees a smooth Y curve.
        HeightSampler preciseHeightAt = memoise(rawHeightAt);
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

        int startCellX = Math.floorDiv(origin.getX(), CELL_SIZE);
        int startCellZ = Math.floorDiv(origin.getZ(), CELL_SIZE);
        int goalCellX = Math.floorDiv(goalBlockX, CELL_SIZE);
        int goalCellZ = Math.floorDiv(goalBlockZ, CELL_SIZE);

        List<int[]> cellPath = astarCells(
            startCellX, startCellZ, goalCellX, goalCellZ,
            pathType.rigidness(), length, coarseHeightAt, biome);

        List<BlockPos> blockWaypoints = interpolateToBlocks(cellPath, preciseHeightAt, origin.getY());
        carverSmooth(blockWaypoints, pathType.carver());
        return chaikinOnce(blockWaypoints);
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
                    PathDataManager.addPathCounter(PathCounter.PATH_BAILED_ON_PROXIMITY, 1);
                    result = reconstruct(cameFrom, startKey, bestReachedKey);
                    break;
                }
            } else if(improvedOnce) {
                popsSinceImprovement++;
                if(popsSinceImprovement > STAGNATION_LIMIT) {
                    PathDataManager.addPathCounter(PathCounter.PATH_BAILED_ON_STAGNATION, 1);
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

                // curl noise injects a smooth scalar field so flat-terrain routes can still
                // weave. Math.abs maps [-1,1] to [0,1] so the noise is purely a cost addition.
                double curl = Math.abs(CURL_NOISE.getValue(nx * CURL_SCALE, nz * CURL_SCALE)) * CURL_STRENGTH;
                PathDataManager.addPathCounter(PathCounter.CURL_NOISE_SAMPLES, 1);
                double stepCost = STEP_COST[d] + (double) slopeDelta * slopeDelta * (1.0 - rigidness) * SLOPE_COST_SCALE + curl;
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
        PathDataManager.addPathCounter(PathCounter.A_STAR_ITERATIONS, iters);
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

    // Expands cell waypoints to block-dense ones. Drops consecutive duplicate (x,z).
    private static List<BlockPos> interpolateToBlocks(List<int[]> cells, HeightSampler heightAt, int originY) {
        List<BlockPos> result = new ArrayList<>();
        long[] lastKey = {Long.MIN_VALUE};
        for(int i = 0; i < cells.size() - 1; i++) {
            int[] a = cells.get(i);
            int[] b = cells.get(i + 1);
            int ax = a[0] * CELL_SIZE;
            int az = a[1] * CELL_SIZE;
            int bx = b[0] * CELL_SIZE;
            int bz = b[1] * CELL_SIZE;
            PathGeometryUtils.bresenham(ax, az, bx, bz, (px, pz) -> {
                long pk = ((long) px << 32) | (pz & 0xFFFFFFFFL);
                if(pk == lastKey[0]) return;
                lastKey[0] = pk;
                int y = heightAt.sampleAt(px, pz);
                result.add(new BlockPos(px, y < 0 ? originY : y, pz));
            });
        }
        if(result.isEmpty() && !cells.isEmpty()) {
            int[] a = cells.get(0);
            int y = heightAt.sampleAt(a[0] * CELL_SIZE, a[1] * CELL_SIZE);
            if(y < 0) y = originY;
            result.add(new BlockPos(a[0] * CELL_SIZE, y, a[1] * CELL_SIZE));
        }
        return result;
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

    // One pass of Chaikin corner-cutting on XZ+Y. Endpoints preserved so structure placement at
    // path ends stays where A* decided.
    private static List<BlockPos> chaikinOnce(List<BlockPos> raw) {
        if(raw.size() < 3) return raw;
        List<BlockPos> next = new ArrayList<>(raw.size() * 2);
        next.add(raw.get(0));
        for(int i = 0; i < raw.size() - 1; i++) {
            BlockPos a = raw.get(i);
            BlockPos b = raw.get(i + 1);
            int qx = Math.round(0.75f * a.getX() + 0.25f * b.getX());
            int qy = Math.round(0.75f * a.getY() + 0.25f * b.getY());
            int qz = Math.round(0.75f * a.getZ() + 0.25f * b.getZ());
            int rx = Math.round(0.25f * a.getX() + 0.75f * b.getX());
            int ry = Math.round(0.25f * a.getY() + 0.75f * b.getY());
            int rz = Math.round(0.25f * a.getZ() + 0.75f * b.getZ());
            next.add(new BlockPos(qx, qy, qz));
            next.add(new BlockPos(rx, ry, rz));
        }
        next.add(raw.get(raw.size() - 1));
        return next;
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
    private static HeightSampler memoiseCoarse(HeightSampler source) {
        Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
        cache.defaultReturnValue(HEIGHT_UNSET);
        return (x, z) -> {
            int sx = Math.floorDiv(x, COARSE_HEIGHT_STRIDE) * COARSE_HEIGHT_STRIDE;
            int sz = Math.floorDiv(z, COARSE_HEIGHT_STRIDE) * COARSE_HEIGHT_STRIDE;
            long key = ((long) sx << 32) | (sz & 0xFFFFFFFFL);
            int v = cache.get(key);
            if(v != HEIGHT_UNSET) return v;
            int computed = source.sampleAt(sx, sz);
            cache.put(key, computed);
            return computed;
        };
    }

    private static BiomeAccept memoiseBiome(BiomeAccept source) {
        if(source == null) return null;
        Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
        cache.defaultReturnValue(0);
        return (x, z) -> {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            int v = cache.get(key);
            if(v != 0) {
                PathDataManager.addPathCounter(PathCounter.PATHFINDER_BIOME_MEMO_HIT, 1);
                return v == 1;
            }
            boolean result = source.test(x, z);
            cache.put(key, result ? 1 : 2);
            return result;
        };
    }
}
