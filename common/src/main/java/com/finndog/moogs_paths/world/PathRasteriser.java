package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.PathType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
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

    // Hard caps on how far path surface may cut into / fill over natural terrain. The pathfinder's
    // rigidness/carver knobs are what shape the target Y; these constants are just safety valves
    // so a fallback straight-line path can't carve an absurd trench.
    private static final int MAX_CUT = 8;
    private static final int MAX_FILL = 8;

    //////////////////////////////

    public static void rasteriseInChunk(WorldGenLevel level, List<BlockPos> waypoints, PathType pathType, int chunkX, int chunkZ, RandomSource random) {
        if(waypoints.size() < 2) return;
        int halfWidth = pathType.width().max() / 2;
        int totalSegments = waypoints.size() - 1;

        // Pre-scan water positions before any blocks are placed so later segments don't see
        // planks placed by earlier segments and misdetect them as land.
        Set<Long> waterPositions = pathType.waterSettings().isPresent()
            ? scanWaterPositions(level, chunkX, chunkZ)
            : null;

        for(int i = 0; i < totalSegments; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);
            if(!mightIntersect(from, to, halfWidth, chunkX, chunkZ)) continue;
            float fade = fadeFactor(pathType, i, totalSegments);
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

        Block fillBlockResolved = BuiltInRegistries.BLOCK.getOptional(pathType.fillBlock()).orElse(Blocks.DIRT);
        BlockState fillState = fillBlockResolved.defaultBlockState();
        boolean skipFill = fillBlockResolved == Blocks.STRUCTURE_VOID;

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

        // Target Y comes from the pathfinder's smoothed waypoint Y, not live level height.
        // The rasteriser drives the path surface to that Y and only the MAX_CUT/MAX_FILL
        // safety net gates individual tiles.
        int targetY = from.getY();
        int centerEffectiveY = targetY - 1;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for(long encoded : processPoints) {
            int cx = (int)(encoded >> 32);
            int cz = (int)(encoded & 0xFFFFFFFFL);
            if(random.nextFloat() >= fade) continue;

            for(int ox = -halfWidth; ox <= halfWidth; ox++) {
                for(int oz = -halfWidth; oz <= halfWidth; oz++) {
                    int manhattan = Math.abs(ox) + Math.abs(oz);
                    if(manhattan > halfWidth) continue;

                    int bx = cx + ox;
                    int bz = cz + oz;
                    if((bx >> 4) != chunkX || (bz >> 4) != chunkZ) continue;

                    int naturalSy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    if(naturalSy <= level.getMinBuildHeight()) continue;

                    long posKey = (long) bx << 32 | (bz & 0xFFFFFFFFL);
                    boolean isWater = waterPositions != null && waterPositions.contains(posKey);
                    if(!isWater && waterPositions == null) {
                        // no water settings declared - still don't place over fluid
                        mpos.set(bx, naturalSy - 1, bz);
                        if(!level.getFluidState(mpos).isEmpty()) continue;
                    }

                    int diff = (naturalSy - 1) - centerEffectiveY;
                    if(diff > MAX_CUT) continue;
                    if(-diff > MAX_FILL) continue;

                    boolean isEdge = halfWidth > 0 && manhattan == halfWidth && !centerPositions.contains(posKey);

                    int placeY = targetY - 1;
                    int clearUpTo = Math.max(placeY + 6, naturalSy + 2);
                    mpos.set(bx, placeY, bz);

                    if(isWater) {
                        PathType.WaterSettings ws = pathType.waterSettings().get();
                        List<PathType.WeightedBlock> waterBlocks = isEdge && !ws.edgeBlocks().isEmpty() ? ws.edgeBlocks() : ws.surfaceBlocks();
                        BlockState picked = pick(waterBlocks, random);
                        if(!picked.isAir()) {
                            level.setBlock(mpos, picked, 3);
                            clearAbove(level, mpos, bx, placeY + 1, bz, clearUpTo);
                        }
                    }
                    else if(isEdge && !pathType.edgeBlocks().isEmpty()) {
                        BlockState edgeState = pick(pathType.edgeBlocks(), random);
                        if(!edgeState.isAir()) {
                            level.setBlock(mpos, edgeState, 3);
                            clearAbove(level, mpos, bx, placeY + 1, bz, clearUpTo);
                        }
                    }
                    else {
                        BlockState surfaceState = pick(pathType.surfaceBlocks(), random);
                        if(!surfaceState.isAir()) {
                            level.setBlock(mpos, surfaceState, 3);
                            if(!skipFill) fillBelow(level, mpos, bx, placeY - 1, bz, fillState, MAX_FILL);
                            clearAbove(level, mpos, bx, placeY + 1, bz, clearUpTo);
                        }
                    }
                }
            }
        }
    }

    private static void clearAbove(WorldGenLevel level, BlockPos.MutableBlockPos mpos, int x, int startY, int z, int endY) {
        for(int y = startY; y <= endY; y++) {
            mpos.set(x, y, z);
            BlockState s = level.getBlockState(mpos);
            if(s.isAir()) continue;
            if(s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)) {
                level.setBlock(mpos, Blocks.AIR.defaultBlockState(), 3);
            }
            else return;
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

    private static float fadeFactor(PathType pathType, int segIdx, int totalSegments) {
        int distFromStart = segIdx;
        int distFromEnd = totalSegments - segIdx;
        float startFade = pathType.fade().startBlocks() <= 0 ? 1.0f
            : Math.min(1.0f, (float) distFromStart / pathType.fade().startBlocks());
        float endFade = pathType.fade().endBlocks() <= 0 ? 1.0f
            : Math.min(1.0f, (float) distFromEnd / pathType.fade().endBlocks());
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
                if(block == Blocks.STRUCTURE_VOID) return Blocks.AIR.defaultBlockState();
                return block.defaultBlockState();
            }
        }
        Block fallback = BuiltInRegistries.BLOCK.getOptional(entries.get(0).block()).orElse(Blocks.DIRT);
        if(fallback == Blocks.STRUCTURE_VOID) return Blocks.AIR.defaultBlockState();
        return fallback.defaultBlockState();
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
