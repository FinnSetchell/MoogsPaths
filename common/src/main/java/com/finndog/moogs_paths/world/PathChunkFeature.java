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

        for(Map.Entry<Integer, List<PathNetworkType>> entry : byRegionSize.entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();

            PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, maxRadius, regionSize)
                .forEach(origin -> {
                    int originChunkX = origin[0];
                    int originChunkZ = origin[1];
                    BlockPos originPos = new BlockPos(originChunkX * 16 + 8, 64, originChunkZ * 16 + 8);

                    Holder<Biome> biomeHolder = level.getBiome(originPos);

                    List<PathNetworkType> eligible = networks.stream()
                        .filter(n -> n.biomeFilter().test(biomeHolder))
                        .collect(Collectors.toList());

                    if(eligible.isEmpty()) return;

                    long pathSeed = worldSeed
                        ^ ((long) originChunkX * 341873128712L)
                        ^ ((long) originChunkZ * 132897987541L)
                        ^ 0xABCDEF1234567890L;
                    RandomSource pathRandom = RandomSource.create(pathSeed);

                    PathNetworkType network = pickWeighted(eligible, pathRandom);

                    Optional<PathType> pathTypeOpt = PathDataManager.getPathType(network.pathType());
                    if(pathTypeOpt.isEmpty()) {
                        Constants.LOG.error("Missing path type: {}", network.pathType());
                        return;
                    }
                    PathType pathType = pathTypeOpt.get();

                    List<List<BlockPos>> allPaths = PathWalker.walkWithBranches(originPos, network, pathType, pathRandom);

                    for(List<BlockPos> waypoints : allPaths) {
                        PathRasteriser.rasteriseInChunk(level, waypoints, pathType, chunkX, chunkZ, pathRandom);
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
