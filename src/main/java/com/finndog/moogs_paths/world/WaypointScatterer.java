package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.FeatureDecoratorSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.util.List;

public final class WaypointScatterer {
    private WaypointScatterer() {}

    @FunctionalInterface
    public interface PointVisitor {
        void visit(BlockPos from, int d, float parX, float parZ, float perpX, float perpZ, RandomSource segRandom);
    }

    public static void scatter(List<BlockPos> waypoints, float density, int reach, int chunkX, int chunkZ, RandomSource random, PointVisitor visitor) {
        if(waypoints.size() < 2) return;

        for(int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);

            long segmentSeed = random.nextLong();
            if(!mightIntersect(from, to, reach, chunkX, chunkZ)) continue;

            RandomSource segRandom = RandomSource.create(segmentSeed);
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if(length == 0) continue;

            float parX = dx / length;
            float parZ = dz / length;
            float perpX = -parZ;
            float perpZ = parX;

            int steps = (int) length;
            for(int d = 0; d < steps; d++) {
                if(segRandom.nextFloat() >= density) continue;
                visitor.visit(from, d, parX, parZ, perpX, perpZ, segRandom);
            }
        }
    }

    public static boolean mightIntersect(BlockPos from, BlockPos to, int reach, int chunkX, int chunkZ) {
        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ * 16;
        int chunkMaxZ = chunkMinZ + 15;
        int minX = Math.min(from.getX(), to.getX()) - reach;
        int maxX = Math.max(from.getX(), to.getX()) + reach;
        int minZ = Math.min(from.getZ(), to.getZ()) - reach;
        int maxZ = Math.max(from.getZ(), to.getZ()) + reach;
        return maxX >= chunkMinX && minX <= chunkMaxX && maxZ >= chunkMinZ && minZ <= chunkMaxZ;
    }

    public static int sideSign(FeatureDecoratorSet.Side side, RandomSource random) {
        return switch(side) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case BOTH -> random.nextBoolean() ? -1 : 1;
            case CENTER -> 0;
        };
    }
}
