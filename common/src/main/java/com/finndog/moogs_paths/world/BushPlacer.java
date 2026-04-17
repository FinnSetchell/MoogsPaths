package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.BushDecoratorSet;
import com.finndog.moogs_paths.data.FeatureDecoratorSet;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

public final class BushPlacer {

    private BushPlacer() {}

    //////////////////////////////

    public static void placeInChunk(WorldGenLevel level, List<BlockPos> waypoints, List<PathNetworkType.WeightedRef> bushSetRefs, int chunkX, int chunkZ, RandomSource random) {
        for(PathNetworkType.WeightedRef ref : bushSetRefs) {
            PathDataManager.getBushDecoratorSet(ref.id()).ifPresent(set ->
                placeSet(level, waypoints, set, chunkX, chunkZ, random));
        }
    }

    private static void placeSet(WorldGenLevel level, List<BlockPos> waypoints, BushDecoratorSet set, int chunkX, int chunkZ, RandomSource random) {
        if(waypoints.size() < 2 || set.blocks().isEmpty()) return;

        int totalWeight = set.blocks().stream().mapToInt(BushDecoratorSet.WeightedBlock::weight).sum();
        int maxReach = set.maxOffset() + set.maxSize();
        int minSpacingSq = set.minSpacing() * set.minSpacing();

        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ * 16;
        int chunkMaxZ = chunkMinZ + 15;

        int[] lastX = {Integer.MIN_VALUE / 2};
        int[] lastZ = {Integer.MIN_VALUE / 2};

        for(int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);

            // Per-segment seed to allow skipping while maintaining consistency
            long segmentSeed = random.nextLong();
            if(!mightIntersect(from, to, maxReach, chunkX, chunkZ)) continue;

            RandomSource segRandom = RandomSource.create(segmentSeed);
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if(length == 0) continue;

            float parX = dx / length;
            float parZ = dz / length;
            float perpX = -parZ;
            float perpZ = parX;

            for(int d = 0; d < (int) length; d++) {
                if(segRandom.nextFloat() >= set.density()) continue;

                int offsetRange = set.maxOffset() - set.minOffset();
                int offset = set.minOffset() + (offsetRange > 0 ? segRandom.nextInt(offsetRange + 1) : 0);
                int side = sideSign(set.side(), segRandom);

                int cx = from.getX() + Math.round(parX * d + perpX * offset * side);
                int cz = from.getZ() + Math.round(parZ * d + perpZ * offset * side);

                if(minSpacingSq > 0) {
                    int ddx = cx - lastX[0];
                    int ddz = cz - lastZ[0];
                    if(ddx * ddx + ddz * ddz < minSpacingSq) continue;
                }

                lastX[0] = cx;
                lastZ[0] = cz;

                int sizeRange = set.maxSize() - set.minSize();
                int size = set.minSize() + (sizeRange > 0 ? segRandom.nextInt(sizeRange + 1) : 0);

                // Call placement if any part of the bush might be in this chunk
                if(cx + size >= chunkMinX && cx - size <= chunkMaxX && cz + size >= chunkMinZ && cz - size <= chunkMaxZ) {
                    BlockState block = pick(set.blocks(), totalWeight, segRandom);
                    placeBush(level, cx, cz, size, parX, parZ, block, chunkX, chunkZ, segRandom, set.minHeight(), set.maxHeight());
                }
            }
        }
    }

    private static void placeBush(WorldGenLevel level, int cx, int cz, int size, float parX, float parZ, BlockState block, int chunkX, int chunkZ, RandomSource random, int minHeight, int maxHeight) {
        float perpX = -parZ;
        float perpZ = parX;
        float longR = size;
        float shortR = Math.max(1, size / 2.0f);

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for(int dx = -size; dx <= size; dx++) {
            for(int dz = -size; dz <= size; dz++) {
                int px = cx + dx;
                int pz = cz + dz;
                if((px >> 4) != chunkX || (pz >> 4) != chunkZ) continue;

                float along = dx * parX + dz * parZ;
                float across = dx * perpX + dz * perpZ;

                // Ellipse check for directional bushes
                float ellipse = (along / longR) * (along / longR) + (across / shortR) * (across / shortR);
                if(ellipse > 1.0f || (ellipse > 0.7f && random.nextFloat() < 0.35f)) continue;

                int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                if(sy <= level.getMinBuildHeight()) continue;

                mpos.set(px, sy - 1, pz);
                if(!level.getFluidState(mpos).isEmpty()) continue;

                int heightRange = maxHeight - minHeight;
                int height = minHeight + (heightRange > 0 ? random.nextInt(heightRange + 1) : 0);

                for(int dy = 0; dy < height; dy++) {
                    mpos.set(px, sy + dy, pz);
                    if(level.getBlockState(mpos).isAir()) {
                        BlockState toPlace = block;
                        for(Direction dir : Direction.Plane.HORIZONTAL) {
                            toPlace = toPlace.updateShape(dir, level.getBlockState(mpos.relative(dir)), level, mpos, mpos.relative(dir));
                        }
                        level.setBlock(mpos, toPlace, Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static boolean mightIntersect(BlockPos from, BlockPos to, int reach, int chunkX, int chunkZ) {
        int minX = Math.min(from.getX(), to.getX()) - reach;
        int maxX = Math.max(from.getX(), to.getX()) + reach;
        int minZ = Math.min(from.getZ(), to.getZ()) - reach;
        int maxZ = Math.max(from.getZ(), to.getZ()) + reach;
        return maxX >= chunkX * 16 && minX <= chunkX * 16 + 15 && maxZ >= chunkZ * 16 && minZ <= chunkZ * 16 + 15;
    }

    private static int sideSign(FeatureDecoratorSet.Side side, RandomSource random) {
        return switch(side) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case BOTH -> random.nextBoolean() ? -1 : 1;
            case CENTER -> 0;
        };
    }

    private static BlockState pick(List<BushDecoratorSet.WeightedBlock> entries, int total, RandomSource random) {
        int roll = random.nextInt(total);
        int sum = 0;
        for(BushDecoratorSet.WeightedBlock entry : entries) {
            sum += entry.weight();
            if(roll < sum) {
                BlockState state = BuiltInRegistries.BLOCK.getOptional(entry.block())
                    .orElse(Blocks.OAK_LEAVES).defaultBlockState();
                if(state.hasProperty(LeavesBlock.PERSISTENT)) {
                    state = state.setValue(LeavesBlock.PERSISTENT, true);
                }
                return state;
            }
        }
        return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }
}
