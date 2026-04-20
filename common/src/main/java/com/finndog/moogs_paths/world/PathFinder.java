package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.PathType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public final class PathFinder {
    private PathFinder() {}

    private static final int CELL_SIZE = 4;
    private static final float SLOPE_COST_SCALE = 0.5f;
    private static final int MAX_NATURAL_STEP = 64;
    private static final int BASE_ITER_CAP = 200_000;
    private static final int MAX_GOAL_REROLLS = 8;
    private static final float DIAG = 1.41421356f;
    private static final int[] DX = {1, 0, -1, 0, 1, -1, -1, 1};
    private static final int[] DZ = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final float[] STEP_COST = {1f, 1f, 1f, 1f, DIAG, DIAG, DIAG, DIAG};

    private record OpenNode(long key, double f) {}

    public static List<BlockPos> findPath(BlockPos origin, PathType pathType, RandomSource random, BiFunction<Integer, Integer, Integer> rawHeightAt, BiPredicate<Integer, Integer> goalAccept) {
        BiFunction<Integer, Integer, Integer> heightAt = memoise(rawHeightAt);

        int length = pathType.length().sample(random);

        // Reroll the goal angle until it lands in a position the caller accepts (typically a
        // biome-filter check), up to MAX_GOAL_REROLLS attempts. If every attempt fails, give up
        // so the path isn't forced to terminate in a disallowed biome.
        int goalBlockX = 0, goalBlockZ = 0;
        boolean found = false;
        for(int attempt = 0; attempt <= MAX_GOAL_REROLLS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int gx = origin.getX() + (int) Math.round(Math.cos(angle) * length);
            int gz = origin.getZ() + (int) Math.round(Math.sin(angle) * length);
            if(goalAccept == null || goalAccept.test(gx, gz)) {
                goalBlockX = gx;
                goalBlockZ = gz;
                found = true;
                break;
            }
        }
        if(!found) {
            Constants.LOG.info("[moogs_paths] no goal in allowed biome after {} tries origin=({},{}) - skipping path",
                MAX_GOAL_REROLLS + 1, origin.getX(), origin.getZ());
            return Collections.emptyList();
        }

        int startCellX = Math.floorDiv(origin.getX(), CELL_SIZE);
        int startCellZ = Math.floorDiv(origin.getZ(), CELL_SIZE);
        int goalCellX = Math.floorDiv(goalBlockX, CELL_SIZE);
        int goalCellZ = Math.floorDiv(goalBlockZ, CELL_SIZE);

        List<int[]> cellPath = astarCells(
            startCellX, startCellZ, goalCellX, goalCellZ,
            pathType.rigidness(), length, heightAt);

        if(cellPath == null) {
            Constants.LOG.warn("[moogs_paths] A* failed origin=({},{}) goal=({},{}) length={} - falling back to straight line",
                origin.getX(), origin.getZ(), goalBlockX, goalBlockZ, length);
            cellPath = new ArrayList<>();
            cellPath.add(new int[]{startCellX, startCellZ});
            cellPath.add(new int[]{goalCellX, goalCellZ});
        }

        List<BlockPos> blockWaypoints = interpolateToBlocks(cellPath, heightAt, origin.getY());
        carverSmooth(blockWaypoints, pathType.carver());
        return chaikinOnce(blockWaypoints);
    }

    //////////////////////////////

    // A* over a coarse cell grid. Step cost blends distance (1 cardinal / sqrt(2) diagonal) with
    // a slope penalty scaled by (1 - rigidness). rigidness=1 ignores slope; rigidness=0 routes
    // aggressively around hills.
    private static List<int[]> astarCells(int startX, int startZ, int goalX, int goalZ, float rigidness, int length, BiFunction<Integer, Integer, Integer> heightAt) {
        long startKey = packCell(startX, startZ);
        long goalKey = packCell(goalX, goalZ);

        HashMap<Long, Long> cameFrom = new HashMap<>();
        HashMap<Long, Double> gScore = new HashMap<>();
        PriorityQueue<OpenNode> open = new PriorityQueue<>(Comparator.comparingDouble(OpenNode::f));

        gScore.put(startKey, 0.0);
        open.add(new OpenNode(startKey, octile(startX, startZ, goalX, goalZ)));

        int iterCap = Math.max(BASE_ITER_CAP, length * 100);
        int iters = 0;

        int cellRadius = Math.max(4, (int) Math.ceil((length * 1.5) / CELL_SIZE));
        int midX = (startX + goalX) / 2;
        int midZ = (startZ + goalZ) / 2;
        int minX = midX - cellRadius;
        int maxX = midX + cellRadius;
        int minZ = midZ - cellRadius;
        int maxZ = midZ + cellRadius;

        while(!open.isEmpty() && iters++ < iterCap) {
            OpenNode cur = open.poll();
            long curKey = cur.key();
            if(curKey == goalKey) return reconstruct(cameFrom, startKey, goalKey);

            int cx = unpackX(curKey);
            int cz = unpackZ(curKey);
            double curG = gScore.getOrDefault(curKey, Double.POSITIVE_INFINITY);
            double curF = curG + octile(cx, cz, goalX, goalZ);
            // skip stale queue entries whose gScore has since improved
            if(cur.f() > curF + 1e-6) continue;

            int curY = heightAt.apply(cx * CELL_SIZE, cz * CELL_SIZE);
            if(curY < 0) curY = 0;

            for(int d = 0; d < 8; d++) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if(nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                int ny = heightAt.apply(nx * CELL_SIZE, nz * CELL_SIZE);
                if(ny < 0) ny = curY;
                int slopeDelta = Math.abs(ny - curY);
                if(slopeDelta > MAX_NATURAL_STEP) continue;

                double stepCost = STEP_COST[d] + (double) slopeDelta * slopeDelta * (1.0 - rigidness) * SLOPE_COST_SCALE;
                double tentativeG = curG + stepCost;
                long neighKey = packCell(nx, nz);
                Double prev = gScore.get(neighKey);
                if(prev == null || tentativeG < prev) {
                    cameFrom.put(neighKey, curKey);
                    gScore.put(neighKey, tentativeG);
                    open.add(new OpenNode(neighKey, tentativeG + octile(nx, nz, goalX, goalZ)));
                }
            }
        }
        return null;
    }

    private static List<int[]> reconstruct(Map<Long, Long> cameFrom, long startKey, long goalKey) {
        List<int[]> path = new ArrayList<>();
        long k = goalKey;
        path.add(new int[]{unpackX(k), unpackZ(k)});
        while(k != startKey) {
            Long prev = cameFrom.get(k);
            if(prev == null) break;
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

    // Expands cell-level waypoints into block-dense waypoints with target Y sampled from heightAt
    // at each step. Consecutive duplicates (same x,z) are dropped.
    private static List<BlockPos> interpolateToBlocks(List<int[]> cells, BiFunction<Integer, Integer, Integer> heightAt, int originY) {
        List<BlockPos> result = new ArrayList<>();
        long lastKey = Long.MIN_VALUE;
        for(int i = 0; i < cells.size() - 1; i++) {
            int[] a = cells.get(i);
            int[] b = cells.get(i + 1);
            int ax = a[0] * CELL_SIZE;
            int az = a[1] * CELL_SIZE;
            int bx = b[0] * CELL_SIZE;
            int bz = b[1] * CELL_SIZE;
            List<int[]> line = bresenhamXZ(ax, az, bx, bz);
            for(int j = 0; j < line.size(); j++) {
                int[] p = line.get(j);
                long pk = ((long) p[0] << 32) | (p[1] & 0xFFFFFFFFL);
                if(pk == lastKey) continue;
                int y = heightAt.apply(p[0], p[1]);
                if(y < 0) y = originY;
                result.add(new BlockPos(p[0], y, p[1]));
                lastKey = pk;
            }
        }
        if(result.isEmpty() && !cells.isEmpty()) {
            int[] a = cells.get(0);
            int y = heightAt.apply(a[0] * CELL_SIZE, a[1] * CELL_SIZE);
            if(y < 0) y = originY;
            result.add(new BlockPos(a[0] * CELL_SIZE, y, a[1] * CELL_SIZE));
        }
        return result;
    }

    private static List<int[]> bresenhamXZ(int x0, int z0, int x1, int z1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0, z = z0;
        while(true) {
            out.add(new int[]{x, z});
            if(x == x1 && z == z1) break;
            int e2 = err * 2;
            if(e2 > -dz) { err -= dz; x += sx; }
            if(e2 < dx)  { err += dx; z += sz; }
        }
        return out;
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

    private static BiFunction<Integer, Integer, Integer> memoise(BiFunction<Integer, Integer, Integer> source) {
        HashMap<Long, Integer> cache = new HashMap<>();
        return (x, z) -> {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            Integer v = cache.get(key);
            if(v != null) return v;
            int computed = source.apply(x, z);
            cache.put(key, computed);
            return computed;
        };
    }
}
