package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.*;

public class PathChunkFeature extends Feature<NoneFeatureConfiguration> {

    public static final TagKey<Biome> HAS_NO_PATHS = TagKey.create(Registries.BIOME, new ResourceLocation(Constants.MOD_ID, "has_no_paths"));

    // arbitrary large primes / well-known constants used to derive distinct random streams from pathSeed
    private static final long PATH_SEED_MIXER = 0xABCDEF1234567890L;
    private static final long WALK_MIXER = 0x1L;
    private static final long BRANCH_SEED_MULT = 9999991L;
    private static final long RASTER_CHUNK_X_MULT = 1234567L;
    private static final long RASTER_CHUNK_Z_MULT = 9876543L;
    private static final long STRUCTURE_MIXER = 0x9E3779B97F4A7C15L;
    private static final long FEATURE_MIXER = 0x6C62272E07BB0142L;
    private static final long BUSH_MIXER = 0x3BFDA1C6E09D2578L;
    private static final long ORIGIN_X_MULT = 341873128712L;
    private static final long ORIGIN_Z_MULT = 132897987541L;
    private static final long ORIGIN_REGION_SIZE_MULT = 27182818284L;

    public PathChunkFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkGenerator generator = ctx.chunkGenerator();
        long worldSeed = level.getSeed();
        int chunkX = ctx.origin().getX() >> 4;
        int chunkZ = ctx.origin().getZ() >> 4;

        Map<Integer, List<PathNetworkType>> byRegionSize = PathDataManager.getNetworksByRegionSize();
        if(byRegionSize.isEmpty()) return false;

        int maxRadius = PathDataManager.getNetworksMaxRadius();
        RandomState randomState = level.getLevel().getChunkSource().randomState();

        boolean placed = false;

        for(Map.Entry<Integer, List<PathNetworkType>> entry : byRegionSize.entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();
            List<int[]> origins = PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, maxRadius, regionSize).toList();

            for(int[] origin : origins) {
                int originChunkX = origin[0];
                int originChunkZ = origin[1];
                int originBlockX = originChunkX * 16 + 8;
                int originBlockZ = originChunkZ * 16 + 8;
                int originSurfaceY = generator.getBaseHeight(originBlockX, originBlockZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                BlockPos originPos = new BlockPos(originBlockX, originSurfaceY, originBlockZ);

                long pathSeed = worldSeed
                    ^ ((long) originChunkX * ORIGIN_X_MULT)
                    ^ ((long) originChunkZ * ORIGIN_Z_MULT)
                    ^ ((long) regionSize * ORIGIN_REGION_SIZE_MULT)
                    ^ PATH_SEED_MIXER;

                RandomSource pickRandom = RandomSource.create(pathSeed);
                PathNetworkType network = pickWeighted(networks, pickRandom);

                Holder<Biome> originBiome = level.getBiome(originPos);
                if(originBiome.is(HAS_NO_PATHS)) continue;
                if(!network.biomeFilter().test(originBiome)) continue;

                Optional<PathType> pathTypeOpt = PathDataManager.getPathType(network.pathType());
                if(pathTypeOpt.isEmpty()) {
                    Constants.LOG.error("Missing path type: {}", network.pathType());
                    continue;
                }
                PathType pathType = pathTypeOpt.get();

                List<List<BlockPos>> allPaths = PathDataManager.getOrComputeWaypoints(pathSeed, () -> {
                    RandomSource walkRandom = RandomSource.create(pathSeed ^ WALK_MIXER);
                    return PathWalker.walkWithBranches(originPos, network, pathType, walkRandom,
                        (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState));
                });

                for(int branchIdx = 0; branchIdx < allPaths.size(); branchIdx++) {
                    List<BlockPos> waypoints = allPaths.get(branchIdx);
                    long branchSeed = pathSeed ^ ((long) branchIdx * BRANCH_SEED_MULT);

                    // Rasteriser gets a per-chunk seed — cross-chunk consistency not needed here
                    RandomSource rasterRandom = RandomSource.create(branchSeed ^ ((long) chunkX * RASTER_CHUNK_X_MULT) ^ ((long) chunkZ * RASTER_CHUNK_Z_MULT));
                    PathRasteriser.rasteriseInChunk(level, waypoints, pathType, chunkX, chunkZ, rasterRandom);

                    // Structure placer needs the same random sequence in every chunk
                    if(!network.structureSets().isEmpty()) {
                        RandomSource structureRandom = RandomSource.create(branchSeed ^ STRUCTURE_MIXER);
                        StructurePlacer.placeInChunk(level, waypoints, network.structureSets(), network.biomeFilter(), chunkX, chunkZ, structureRandom);
                    }

                    // Feature scatterer needs the same random sequence in every chunk
                    if(!network.featureDecoratorSets().isEmpty()) {
                        RandomSource featureRandom = RandomSource.create(branchSeed ^ FEATURE_MIXER);
                        FeatureScatterer.scatterInChunk(level, generator, waypoints, network.featureDecoratorSets(), network.biomeFilter(), chunkX, chunkZ, featureRandom);
                    }

                    if(!network.bushDecoratorSets().isEmpty()) {
                        RandomSource bushRandom = RandomSource.create(branchSeed ^ BUSH_MIXER);
                        BushPlacer.placeInChunk(level, waypoints, network.bushDecoratorSets(), network.biomeFilter(), chunkX, chunkZ, bushRandom);
                    }
                }

                placed = true;
            }
        }

        return placed;
    }

    private static PathNetworkType pickWeighted(List<PathNetworkType> eligible, RandomSource random) {
        int total = 0;
        for(PathNetworkType n : eligible) total += n.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(PathNetworkType n : eligible) {
            cumulative += n.weight();
            if(roll < cumulative) return n;
        }
        return eligible.get(0);
    }
}
