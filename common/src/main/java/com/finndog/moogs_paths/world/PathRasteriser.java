package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.PathType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PathRasteriser {
    private PathRasteriser() {}

    //////////////////////////////

    public static void rasteriseInChunk(WorldGenLevel level, List<BlockPos> waypoints, PathType pathType, int chunkX, int chunkZ, RandomSource random) {
        if(waypoints.size() < 2) return;
        int halfWidth = pathType.width().max() / 2;
        int stepDist = PathWalker.stepDistance(pathType.width().max());
        int totalSegments = waypoints.size() - 1;

        // Pre-scan water positions before any blocks are placed so later segments
        // don't see planks placed by earlier segments and misdetect them as land.
        Set<Long> waterPositions = pathType.waterSettings().isPresent()
            ? scanWaterPositions(level, chunkX, chunkZ)
            : null;

        for(int i = 0; i < totalSegments; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);
            if(!mightIntersect(from, to, halfWidth, chunkX, chunkZ)) continue;
            float fade = fadeFactor(pathType, i, totalSegments, stepDist);
            rasteriseSegmentInChunk(level, from, to, pathType, halfWidth, fade, chunkX, chunkZ, random, waterPositions);
        }
    }

    // Scans every surface position in the chunk and records which ones have fluid at sy-1.
    private static Set<Long> scanWaterPositions(WorldGenLevel level, int chunkX, int chunkZ) {
        Set<Long> result = new HashSet<>();
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for(int x = minX; x < minX + 16; x++) {
            for(int z = minZ; z < minZ + 16; z++) {
                int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                mpos.set(x, sy - 1, z);
                if(!level.getFluidState(mpos).isEmpty()) {
                    result.add((long) x << 32 | (z & 0xFFFFFFFFL));
                }
            }
        }
        return result;
    }

    private static void rasteriseSegmentInChunk(WorldGenLevel level, BlockPos from, BlockPos to, PathType pathType, int halfWidth, float fade, int chunkX, int chunkZ, RandomSource random, Set<Long> waterPositions) {
        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ * 16;
        int chunkMaxZ = chunkMinZ + 15;

        BlockState fillState = BuiltInRegistries.BLOCK.getOptional(pathType.fillBlock())
            .orElse(Blocks.DIRT).defaultBlockState();
        int fillTolerance = pathType.slopeHandling().fillTolerance();

        Set<Long> centerPositions = new HashSet<>();
        List<Long> processPoints = new ArrayList<>();
        PathGeometryUtils.bresenham(from.getX(), from.getZ(), to.getX(), to.getZ(), (cx, cz) -> {
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
            int centerEffectiveY = centerY - 1;

            for(int ox = -halfWidth; ox <= halfWidth; ox++) {
                for(int oz = -halfWidth; oz <= halfWidth; oz++) {
                    int manhattan = Math.abs(ox) + Math.abs(oz);
                    if(manhattan > halfWidth) continue;

                    int bx = cx + ox;
                    int bz = cz + oz;
                    if((bx >> 4) != chunkX || (bz >> 4) != chunkZ) continue;

                    int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    if(sy <= level.getMinBuildHeight()) continue;

                    long posKey = (long) bx << 32 | (bz & 0xFFFFFFFFL);
                    boolean isWater = waterPositions != null && waterPositions.contains(posKey);
                    if(!isWater && waterPositions != null) {
                        // allowWater path but this tile is land — fall through to normal logic
                    }
                    else if(waterPositions == null) {
                        // no water settings — check live for the skip-if-fluid guard
                        mpos.set(bx, sy - 1, bz);
                        if(!level.getFluidState(mpos).isEmpty()) continue;
                    }

                    int diff = (sy - 1) - centerEffectiveY;
                    if(diff > pathType.slopeHandling().cutTolerance()) continue;
                    if(-diff > fillTolerance) continue;

                    mpos.set(bx, sy - 1, bz);
                    boolean isEdge = halfWidth > 0 && manhattan == halfWidth && !centerPositions.contains(posKey);

                    if(isWater) {
                        PathType.WaterSettings ws = pathType.waterSettings().get();
                        List<PathType.WeightedBlock> waterBlocks = isEdge && !ws.edgeBlocks().isEmpty() ? ws.edgeBlocks() : ws.surfaceBlocks();
                        BlockState picked = pick(waterBlocks, random);
                        if(!picked.isAir()) {
                            level.setBlock(mpos, picked, 3);
                        }
                    }
                    else if(isEdge && !pathType.edgeBlocks().isEmpty()) {
                        BlockState edgeState = pick(pathType.edgeBlocks(), random);
                        if(!edgeState.isAir()) {
                            level.setBlock(mpos, edgeState, 3);
                        }
                    }
                    else {
                        BlockState surfaceState = pick(pathType.surfaceBlocks(), random);
                        if(!surfaceState.isAir()) {
                            level.setBlock(mpos, surfaceState, 3);
                            fillBelow(level, mpos, bx, sy - 2, bz, fillState, fillTolerance);
                        }
                    }
                }
            }
        }
    }

    private static void fillBelow(WorldGenLevel level, BlockPos.MutableBlockPos mpos, int x, int startY, int z, BlockState fillState, int maxFill) {
        for(int depth = 0; depth < maxFill; depth++) {
            mpos.set(x, startY - depth, z);
            if(level.getBlockState(mpos).isAir()) {
                level.setBlock(mpos, fillState, 3);
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
        if(entries.isEmpty()) return Blocks.AIR.defaultBlockState();
        int total = 0;
        for(PathType.WeightedBlock e : entries) total += e.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(PathType.WeightedBlock e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) {
                Block block = BuiltInRegistries.BLOCK.getOptional(e.block()).orElse(Blocks.DIRT);
                BlockState state = block.defaultBlockState();
                for(Map.Entry<String, String> prop : e.properties().entrySet()) {
                    state = applyProperty(state, prop.getKey(), prop.getValue());
                }
                return state;
            }
        }
        return BuiltInRegistries.BLOCK.getOptional(entries.get(0).block())
            .orElse(Blocks.DIRT)
            .defaultBlockState();
    }

    private static BlockState applyProperty(BlockState state, String key, String value) {
        for(Property<?> prop : state.getBlock().getStateDefinition().getProperties()) {
            if(prop.getName().equals(key)) {
                return tryApplyValue(state, prop, value);
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState tryApplyValue(BlockState state, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
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
}
