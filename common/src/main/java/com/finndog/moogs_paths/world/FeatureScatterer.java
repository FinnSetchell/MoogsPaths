package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.BiomeFilter;
import com.finndog.moogs_paths.data.FeatureDecoratorSet;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import java.util.*;

public final class FeatureScatterer {
    private FeatureScatterer() {}

    private static final Set<ResourceLocation> WARNED_MISSING = Collections.synchronizedSet(new HashSet<>());

    public static void scatterInChunk(WorldGenLevel level, ChunkGenerator generator, List<BlockPos> waypoints, List<PathNetworkType.WeightedRef> decoratorSetRefs, BiomeFilter biomeFilter, int chunkX, int chunkZ, RandomSource random) {
        Registry<ConfiguredFeature<?, ?>> featureRegistry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);

        for(PathNetworkType.WeightedRef ref : decoratorSetRefs) {
            PathDataManager.getDecoratorSet(ref.id()).ifPresent(set ->
                scatterSet(level, generator, featureRegistry, waypoints, set, biomeFilter, chunkX, chunkZ, random));
        }
    }

    //////////////////////////////

    private static void scatterSet(WorldGenLevel level, ChunkGenerator generator, Registry<ConfiguredFeature<?, ?>> featureRegistry, List<BlockPos> waypoints, FeatureDecoratorSet set, BiomeFilter biomeFilter, int chunkX, int chunkZ, RandomSource random) {
        int reach = set.scatterWidth() + 1;

        WaypointScatterer.scatter(waypoints, set.density(), reach, chunkX, chunkZ, random,
            (from, d, parX, parZ, perpX, perpZ, segRandom) -> {
                ConfiguredFeature<?, ?> feature = pickWeighted(set.features(), featureRegistry, segRandom);
                if(feature == null) return;

                int side = WaypointScatterer.sideSign(set.side(), segRandom);
                int offset = side == 0 ? 0 : 1 + (set.scatterWidth() > 1 ? segRandom.nextInt(set.scatterWidth()) : 0);

                int bx = from.getX() + Math.round(parX * d + perpX * offset * side);
                int bz = from.getZ() + Math.round(parZ * d + perpZ * offset * side);

                if((bx >> 4) == chunkX && (bz >> 4) == chunkZ) {
                    tryPlace(level, generator, feature, bx, bz, biomeFilter, segRandom);
                }
            });
    }

    private static void tryPlace(WorldGenLevel level, ChunkGenerator generator, ConfiguredFeature<?, ?> feature, int bx, int bz, BiomeFilter biomeFilter, RandomSource random) {
        int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        if(sy <= level.getMinBuildHeight()) return;
        BlockPos pos = new BlockPos(bx, sy, bz);
        if(!biomeFilter.test(level.getBiome(pos))) return;
        feature.place(level, generator, random, pos);
    }

    private static ConfiguredFeature<?, ?> pickWeighted(List<FeatureDecoratorSet.FeatureEntry> entries, Registry<ConfiguredFeature<?, ?>> registry, RandomSource random) {
        int total = 0;
        for(FeatureDecoratorSet.FeatureEntry e : entries) total += e.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(FeatureDecoratorSet.FeatureEntry e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) {
                ConfiguredFeature<?, ?> feature = registry.getOptional(e.feature()).orElse(null);
                if(feature == null && WARNED_MISSING.add(e.feature())) Constants.LOG.warn("Configured feature not found: {}", e.feature());
                return feature;
            }
        }
        return null;
    }
}
