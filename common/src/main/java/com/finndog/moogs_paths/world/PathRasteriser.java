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
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PathRasteriser {
    private PathRasteriser() {}

    // Safety caps so a fallback straight-line path can't carve an absurd trench. Path surface Y
    // is shaped by the pathfinder's rigidness/carver knobs; these just gate individual tiles.
    private static final int MAX_CUT = 8;
    private static final int MAX_FILL = 8;

    //////////////////////////////

    public static void rasteriseInChunk(WorldGenLevel level, List<BlockPos> waypoints, PathType pathType, int chunkX, int chunkZ, RandomSource random) {
        if(waypoints.size() < 2) return;
        int halfWidth = pathType.width().max() / 2;
        int totalSegments = waypoints.size() - 1;

        // Snapshot water columns before any segment runs - otherwise later segments see planks
        // placed by earlier ones and misdetect them as land.
        Set<Long> waterPositions = pathType.waterSettings().isPresent()
            ? scanWaterPositions(level, chunkX, chunkZ)
            : null;

        Set<Block> pathBlocks = buildPathBlockSet(pathType);

        for(int i = 0; i < totalSegments; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);
            if(!mightIntersect(from, to, halfWidth, chunkX, chunkZ)) continue;
            float fade = fadeFactor(pathType, i, totalSegments);
            rasteriseSegmentInChunk(level, from, to, pathType, halfWidth, fade, chunkX, chunkZ, random, waterPositions, pathBlocks);
        }
    }

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

    //////////////////////////////

    private static void rasteriseSegmentInChunk(WorldGenLevel level, BlockPos from, BlockPos to, PathType pathType, int halfWidth, float fade, int chunkX, int chunkZ, RandomSource random, Set<Long> waterPositions, Set<Block> pathBlocks) {
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

        // Target Y is the pathfinder's smoothed waypoint Y, not live level height. MAX_CUT and
        // MAX_FILL gate individual tiles when terrain has drifted too far from that target.
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
                        // no water settings declared, but still skip fluid columns
                        mpos.set(bx, naturalSy - 1, bz);
                        if(!level.getFluidState(mpos).isEmpty()) continue;
                    }

                    int diff = (naturalSy - 1) - centerEffectiveY;
                    if(diff > MAX_CUT) continue;
                    if(-diff > MAX_FILL) continue;

                    boolean isEdge = halfWidth > 0 && manhattan == halfWidth && !centerPositions.contains(posKey);

                    int placeY = targetY - 1;
                    mpos.set(bx, placeY, bz);
                    boolean didPlace = false;

                    if(isWater) {
                        PathType.WaterSettings ws = pathType.waterSettings().get();
                        List<PathType.WeightedBlock> waterBlocks = isEdge && !ws.edgeBlocks().isEmpty() ? ws.edgeBlocks() : ws.surfaceBlocks();
                        BlockState picked = pick(waterBlocks, random);
                        if(!picked.isAir()) {
                            level.setBlock(mpos, picked, 3);
                            didPlace = true;
                        }
                    }
                    else if(isEdge && !pathType.edgeBlocks().isEmpty()) {
                        BlockState edgeState = pick(pathType.edgeBlocks(), random);
                        if(!edgeState.isAir()) {
                            level.setBlock(mpos, edgeState, 3);
                            if(!skipFill) fillBelow(level, mpos, bx, placeY - 1, bz, fillState, MAX_FILL, pathBlocks);
                            didPlace = true;
                        }
                    }
                    else {
                        BlockState placement = pick(pathType.surfaceBlocks(), random);
                        if(!placement.isAir()) {
                            level.setBlock(mpos, placement, 3);
                            if(!skipFill) fillBelow(level, mpos, bx, placeY - 1, bz, fillState, MAX_FILL, pathBlocks);
                            didPlace = true;
                        }
                    }

                    if(didPlace) {
                        clearVegetationAbove(level, mpos, bx, placeY, bz);
                        mpos.set(bx, placeY - 1, bz);
                        BlockState under = level.getBlockState(mpos);
                        if(under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.MYCELIUM)) {
                            level.setBlock(mpos, Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
    }

    private static void clearVegetationAbove(WorldGenLevel level, BlockPos.MutableBlockPos mpos, int bx, int placeY, int bz) {
        for(int dy = 1; dy <= 2; dy++) {
            mpos.set(bx, placeY + dy, bz);
            BlockState state = level.getBlockState(mpos);
            if(state.isAir() || state.getPistonPushReaction() != PushReaction.DESTROY) break;
            level.setBlock(mpos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void fillBelow(WorldGenLevel level, BlockPos.MutableBlockPos mpos, int x, int startY, int z, BlockState fillState, int maxFill, Set<Block> pathBlocks) {
        for(int depth = 0; depth < maxFill; depth++) {
            mpos.set(x, startY - depth, z);
            BlockState existing = level.getBlockState(mpos);
            if(existing.isAir() || pathBlocks.contains(existing.getBlock())) {
                level.setBlock(mpos, fillState, 3);
            } else {
                if(existing.is(Blocks.GRASS_BLOCK) || existing.is(Blocks.MYCELIUM)) {
                    level.setBlock(mpos, Blocks.DIRT.defaultBlockState(), 3);
                }
                break;
            }
        }
    }

    private static Set<Block> buildPathBlockSet(PathType pathType) {
        Set<Block> set = new HashSet<>();
        for(PathType.WeightedBlock e : pathType.surfaceBlocks()) {
            BuiltInRegistries.BLOCK.getOptional(e.block())
                .filter(b -> b != Blocks.STRUCTURE_VOID)
                .ifPresent(set::add);
        }
        for(PathType.WeightedBlock e : pathType.edgeBlocks()) {
            BuiltInRegistries.BLOCK.getOptional(e.block())
                .filter(b -> b != Blocks.STRUCTURE_VOID)
                .ifPresent(set::add);
        }
        return set;
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
