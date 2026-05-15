package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.BushDecoratorSet;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class BushPlacer {

    private BushPlacer() {}

    private static final ConcurrentHashMap<ResourceLocation, BlockState> RESOLVED_STATES = new ConcurrentHashMap<>();

    private static BlockState resolveState(ResourceLocation id) {
        return RESOLVED_STATES.computeIfAbsent(id, key -> {
            BlockState state = BuiltInRegistries.BLOCK.getOptional(key)
                .orElse(Blocks.OAK_LEAVES).defaultBlockState();
            if(state.hasProperty(LeavesBlock.PERSISTENT)) {
                state = state.setValue(LeavesBlock.PERSISTENT, true);
            }
            return state;
        });
    }

    public static void clearBlockCache() {
        RESOLVED_STATES.clear();
    }

    //////////////////////////////

    public static void placeInChunk(WorldGenLevel level, List<BlockPos> waypoints, List<PathNetworkType.WeightedRef> bushSetRefs, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random) {
        for(PathNetworkType.WeightedRef ref : bushSetRefs) {
            MoogsPathsDatapackRegistries.getBushDecoratorSet(level.registryAccess(), ref.id()).ifPresent(set ->
                placeSet(level, waypoints, set, biomes, chunkX, chunkZ, random));
        }
    }

    private static void placeSet(WorldGenLevel level, List<BlockPos> waypoints, BushDecoratorSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random) {
        if(set.blocks().isEmpty()) return;

        int totalWeight = set.blocks().stream().mapToInt(BushDecoratorSet.WeightedBlock::weight).sum();
        int maxReach = set.maxOffset() + set.maxSize();
        int minSpacingSq = set.minSpacing() * set.minSpacing();

        int chunkMinX = chunkX * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ * 16;
        int chunkMaxZ = chunkMinZ + 15;

        int[] lastX = {Integer.MIN_VALUE / 2};
        int[] lastZ = {Integer.MIN_VALUE / 2};

        // snapshot before scatter so all height lookups in placeBush see pre-decoration terrain
        int[] chunkHeights = snapshotHeights(level, chunkX, chunkZ);

        WaypointScatterer.scatter(waypoints, set.density(), maxReach, chunkX, chunkZ, random,
            (from, d, parX, parZ, perpX, perpZ, segRandom) -> {
                int offsetRange = set.maxOffset() - set.minOffset();
                int offset = set.minOffset() + (offsetRange > 0 ? segRandom.nextInt(offsetRange + 1) : 0);
                int side = WaypointScatterer.sideSign(set.side(), segRandom);

                int cx = from.getX() + Math.round(parX * d + perpX * offset * side);
                int cz = from.getZ() + Math.round(parZ * d + perpZ * offset * side);

                if(minSpacingSq > 0) {
                    int ddx = cx - lastX[0];
                    int ddz = cz - lastZ[0];
                    if(ddx * ddx + ddz * ddz < minSpacingSq) return;
                }

                lastX[0] = cx;
                lastZ[0] = cz;

                int sizeRange = set.maxSize() - set.minSize();
                int size = set.minSize() + (sizeRange > 0 ? segRandom.nextInt(sizeRange + 1) : 0);

                if(cx + size >= chunkMinX && cx - size <= chunkMaxX && cz + size >= chunkMinZ && cz - size <= chunkMaxZ) {
                    int centerY = level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
                    PathDataManager.recordBiomeCall(com.finndog.moogs_paths.data.BiomeCallSite.BUSH_PLACE_CHECK);
                    if(!biomes.contains(level.getBiome(new BlockPos(cx, centerY, cz)))) return;
                    BlockState block = pick(set.blocks(), totalWeight, segRandom);
                    placeBush(level, cx, cz, size, parX, parZ, block, chunkX, chunkZ, segRandom, set.minHeight(), set.maxHeight(), chunkHeights);
                }
            });
    }

    private static int[] snapshotHeights(WorldGenLevel level, int chunkX, int chunkZ) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int[] heights = new int[256];
        for(int lx = 0; lx < 16; lx++) {
            for(int lz = 0; lz < 16; lz++) {
                heights[lx * 16 + lz] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, minX + lx, minZ + lz);
            }
        }
        return heights;
    }

    private static void placeBush(WorldGenLevel level, int cx, int cz, int size, float parX, float parZ, BlockState block, int chunkX, int chunkZ, RandomSource random, int minHeight, int maxHeight, int[] chunkHeights) {
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

                float ellipse = (along / longR) * (along / longR) + (across / shortR) * (across / shortR);
                if(ellipse > 1.0f || (ellipse > 0.7f && random.nextFloat() < 0.35f)) continue;

                int sy = chunkHeights[(px - chunkX * 16) * 16 + (pz - chunkZ * 16)];
                if(sy <= level.getMinBuildHeight()) continue;

                // 3-deep, not 1: water-settings paths rasterise a solid layer on top of water columns
                if(isColumnOverWater(level, px, pz, sy, mpos)) continue;

                int heightRange = maxHeight - minHeight;
                int height = minHeight + (heightRange > 0 ? random.nextInt(heightRange + 1) : 0);

                for(int dy = 0; dy < height; dy++) {
                    mpos.set(px, sy + dy, pz);
                    if(level.getBlockState(mpos).isAir()) {
                        level.setBlock(mpos, block, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static boolean isColumnOverWater(WorldGenLevel level, int x, int z, int sy, BlockPos.MutableBlockPos mpos) {
        for(int depth = 1; depth <= 3; depth++) {
            mpos.set(x, sy - depth, z);
            if(!level.getFluidState(mpos).isEmpty()) return true;
        }
        return false;
    }

    private static BlockState pick(List<BushDecoratorSet.WeightedBlock> entries, int total, RandomSource random) {
        int roll = random.nextInt(total);
        int sum = 0;
        for(BushDecoratorSet.WeightedBlock entry : entries) {
            sum += entry.weight();
            if(roll < sum) {
                return resolveState(entry.block());
            }
        }
        return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }
}
