package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.PathType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PathRasteriser {
    private PathRasteriser() {}

    @FunctionalInterface
    private interface XZConsumer {
        void accept(int x, int z);
    }

    //////////////////////////////

    public static void rasteriseInChunk(WorldGenLevel level, List<BlockPos> waypoints, PathType pathType, int chunkX, int chunkZ, RandomSource random) {
        if(waypoints.size() < 2) return;
        int halfWidth = pathType.width().max() / 2;
        int stepDist = PathWalker.stepDistance(pathType.width().max());
        int totalSegments = waypoints.size() - 1;

        for(int i = 0; i < totalSegments; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);
            if(!mightIntersect(from, to, halfWidth, chunkX, chunkZ)) continue;
            float fade = fadeFactor(pathType, i, totalSegments, stepDist);
            rasteriseSegmentInChunk(level, from, to, pathType, halfWidth, fade, chunkX, chunkZ, random);
        }
    }

    private static void rasteriseSegmentInChunk(WorldGenLevel level, BlockPos from, BlockPos to, PathType pathType, int halfWidth, float fade, int chunkX, int chunkZ, RandomSource random) {
        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ * 16;
        int chunkMaxZ = chunkMinZ + 15;

        BlockState fillState = BuiltInRegistries.BLOCK.getOptional(pathType.fillBlock())
            .orElse(Blocks.DIRT).defaultBlockState();
        int fillTolerance = pathType.slopeHandling().fillTolerance();

        Set<Long> centerPositions = new HashSet<>();
        List<Long> processPoints = new ArrayList<>();
        bresenham(from.getX(), from.getZ(), to.getX(), to.getZ(), (cx, cz) -> {
            long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
            if(cx >= chunkMinX && cx <= chunkMaxX && cz >= chunkMinZ && cz <= chunkMaxZ)
                centerPositions.add(key);
            if(cx + halfWidth >= chunkMinX && cx - halfWidth <= chunkMaxX &&
               cz + halfWidth >= chunkMinZ && cz - halfWidth <= chunkMaxZ)
                processPoints.add(key);
        });

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for(long encoded : processPoints) {
            int cx = (int)(encoded >> 32);
            int cz = (int)(encoded & 0xFFFFFFFFL);
            if(random.nextFloat() >= fade) continue;

            int centerY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
            if(centerY <= level.getMinBuildHeight()) continue;

            for(int ox = -halfWidth; ox <= halfWidth; ox++) {
                for(int oz = -halfWidth; oz <= halfWidth; oz++) {
                    int manhattan = Math.abs(ox) + Math.abs(oz);
                    if(manhattan > halfWidth) continue;

                    int bx = cx + ox;
                    int bz = cz + oz;
                    if((bx >> 4) != chunkX || (bz >> 4) != chunkZ) continue;

                    int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    if(sy <= level.getMinBuildHeight()) continue;
                    mpos.set(bx, sy - 1, bz);
                    if(!level.getFluidState(mpos).isEmpty()) continue;

                    int diff = sy - centerY;
                    if(diff > pathType.slopeHandling().cutTolerance()) continue;
                    if(-diff > fillTolerance) continue;

                    long posKey = (long) bx << 32 | (bz & 0xFFFFFFFFL);
                    if(halfWidth > 0 && manhattan == halfWidth && !pathType.edgeBlocks().isEmpty()
                            && !centerPositions.contains(posKey)) {
                        level.setBlock(mpos, pick(pathType.edgeBlocks(), random), Block.UPDATE_CLIENTS);
                    }
                    else {
                        level.setBlock(mpos, pick(pathType.surfaceBlocks(), random), Block.UPDATE_CLIENTS);
                        fillBelow(level, mpos, bx, sy - 2, bz, fillState, fillTolerance);
                    }
                }
            }
        }
    }

    private static void fillBelow(WorldGenLevel level, BlockPos.MutableBlockPos mpos, int x, int startY, int z, BlockState fillState, int maxFill) {
        for(int depth = 0; depth < maxFill; depth++) {
            mpos.set(x, startY - depth, z);
            if(level.getBlockState(mpos).isAir()) {
                level.setBlock(mpos, fillState, Block.UPDATE_CLIENTS);
            }
            else break;
        }
    }

    private static float fadeFactor(PathType pathType, int segIdx, int totalSegments, int stepDist) {
        float distFromStart = segIdx * (float) stepDist;
        float distFromEnd = (totalSegments - segIdx) * (float) stepDist;
        float startFade = pathType.fade().startBlocks() <= 0 ? 1.0f
            : Math.min(1.0f, distFromStart / pathType.fade().startBlocks());
        float endFade = pathType.fade().endBlocks() <= 0 ? 1.0f
            : Math.min(1.0f, distFromEnd / pathType.fade().endBlocks());
        return Math.min(startFade, endFade);
    }

    private static BlockState pick(List<PathType.WeightedBlock> entries, RandomSource random) {
        int total = 0;
        for(PathType.WeightedBlock e : entries) total += e.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(PathType.WeightedBlock e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) {
                return BuiltInRegistries.BLOCK.getOptional(e.block())
                    .orElse(Blocks.DIRT)
                    .defaultBlockState();
            }
        }
        return BuiltInRegistries.BLOCK.getOptional(entries.get(0).block())
            .orElse(Blocks.DIRT)
            .defaultBlockState();
    }

    private static boolean mightIntersect(BlockPos from, BlockPos to, int halfWidth, int chunkX, int chunkZ) {
        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ * 16;
        int chunkMaxZ = chunkMinZ + 15;
        int minX = Math.min(from.getX(), to.getX()) - halfWidth;
        int maxX = Math.max(from.getX(), to.getX()) + halfWidth;
        int minZ = Math.min(from.getZ(), to.getZ()) - halfWidth;
        int maxZ = Math.max(from.getZ(), to.getZ()) + halfWidth;
        return maxX >= chunkMinX && minX <= chunkMaxX && maxZ >= chunkMinZ && minZ <= chunkMaxZ;
    }

    private static void bresenham(int x1, int z1, int x2, int z2, XZConsumer fn) {
        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;
        int x = x1, z = z1;
        while(true) {
            fn.accept(x, z);
            if(x == x2 && z == z2) break;
            int e2 = 2 * err;
            if(e2 > -dz) { err -= dz; x += sx; }
            if(e2 < dx) { err += dx; z += sz; }
        }
    }
}
