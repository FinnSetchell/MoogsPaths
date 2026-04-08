package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public class PathChunkFeature extends Feature<NoneFeatureConfiguration> {

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

        Collection<PathNetworkType> allNetworks = PathDataManager.getAllNetworks();
        if(allNetworks.isEmpty()) return false;

        int maxRadius = allNetworks.stream()
            .mapToInt(n -> n.scale().lengthMax)
            .max()
            .orElse(1000);

        Map<Integer, List<PathNetworkType>> byRegionSize = allNetworks.stream()
            .collect(Collectors.groupingBy(PathNetworkType::regionSize));

        boolean[] placed = {false};

       BlockPos currentCenter = new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
        Holder<Biome> currentBiome = level.getBiome(currentCenter);

        for(Map.Entry<Integer, List<PathNetworkType>> entry : byRegionSize.entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();

            PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, maxRadius, regionSize)
                .forEach(origin -> {
                    int originChunkX = origin[0];
                    int originChunkZ = origin[1];
                    BlockPos originPos = new BlockPos(originChunkX * 16 + 8, 64, originChunkZ * 16 + 8);

                    long pathSeed = worldSeed
                        ^ ((long) originChunkX * 341873128712L)
                        ^ ((long) originChunkZ * 132897987541L)
                        ^ 0xABCDEF1234567890L;

                    RandomSource pickRandom = RandomSource.create(pathSeed);
                    PathNetworkType network = pickWeighted(networks, pickRandom);

                    if(!network.biomeFilter().test(currentBiome)) return;

                    Optional<PathType> pathTypeOpt = PathDataManager.getPathType(network.pathType());
                    if(pathTypeOpt.isEmpty()) {
                        Constants.LOG.error("Missing path type: {}", network.pathType());
                        return;
                    }
                    PathType pathType = pathTypeOpt.get();

                    // Walk uses its own seeded random — deterministic, same result every chunk
                    RandomSource walkRandom = RandomSource.create(pathSeed ^ 0x1L);
                    List<List<BlockPos>> allPaths = PathWalker.walkWithBranches(originPos, network, pathType, walkRandom);

                    for(int branchIdx = 0; branchIdx < allPaths.size(); branchIdx++) {
                        List<BlockPos> waypoints = allPaths.get(branchIdx);
                        long branchSeed = pathSeed ^ ((long) branchIdx * 9999991L);

                        // Rasteriser gets a per-chunk seed — cross-chunk consistency not needed here
                        RandomSource rasterRandom = RandomSource.create(branchSeed ^ ((long) chunkX * 1234567L) ^ ((long) chunkZ * 9876543L));
                        PathRasteriser.rasteriseInChunk(level, waypoints, pathType, chunkX, chunkZ, rasterRandom);

                        // Structure placer needs the same random sequence in every chunk
                        if(!network.structureSets().isEmpty()) {
                            RandomSource structureRandom = RandomSource.create(branchSeed ^ 0x9E3779B97F4A7C15L);
                            StructurePlacer.placeInChunk(level, waypoints, network.structureSets(), chunkX, chunkZ, structureRandom);
                        }

                        // Feature scatterer needs the same random sequence in every chunk
                        if(!network.featureDecoratorSets().isEmpty()) {
                            RandomSource featureRandom = RandomSource.create(branchSeed ^ 0x6C62272E07BB0142L);
                            FeatureScatterer.scatterInChunk(level, generator, waypoints, network.featureDecoratorSets(), chunkX, chunkZ, featureRandom);
                        }
                    }

                    placed[0] = true;
                });
        }

        return placed[0];
    }

    private static PathNetworkType pickWeighted(List<PathNetworkType> eligible, RandomSource random) {
        int total = eligible.stream().mapToInt(PathNetworkType::weight).sum();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(PathNetworkType n : eligible) {
            cumulative += n.weight();
            if(roll < cumulative) return n;
        }
        return eligible.get(0);
    }
}
