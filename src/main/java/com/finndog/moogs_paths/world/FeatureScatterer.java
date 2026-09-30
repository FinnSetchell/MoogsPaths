package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.FeatureDecoratorSet;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import net.minecraft.core.BlockPos;
//? if >=1.21.11 {
/*import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
*///?}
import net.minecraft.core.HolderSet;
//? if <1.21.11 {
import net.minecraft.core.Registry;
//?} else {
/*import net.minecraft.resources.ResourceKey;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
//? if >=26.3 {
/*import net.minecraft.world.level.levelgen.feature.Feature;
*///?} else {
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
//?}

import java.util.*;

// Decorator sets name vanilla configured features. 26.3 folded those into Feature itself, held in
// the worldgen/feature registry.
public final class FeatureScatterer {
    private FeatureScatterer() {}

    private static final Set<ResourceLocation> WARNED_MISSING = Collections.synchronizedSet(new HashSet<>());

    public static void scatterInChunk(WorldGenLevel level, ChunkGenerator generator, List<BlockPos> waypoints, List<ResourceLocation> decoratorSetIds, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, PlacementGuard guard) {
        // 1.21.11: RegistryAccess#registryOrThrow -> #lookupOrThrow returning HolderLookup.RegistryLookup
        //? if <1.21.11 {
        Registry<ConfiguredFeature<?, ?>> featureRegistry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        //?} elif <26.3 {
        /*HolderLookup.RegistryLookup<ConfiguredFeature<?, ?>> featureRegistry =
            level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        *///?} else {
        /*HolderLookup.RegistryLookup<Feature> featureRegistry = level.registryAccess().lookupOrThrow(Registries.FEATURE);
        *///?}

        for(ResourceLocation id : decoratorSetIds) {
            var set = MoogsPathsDatapackRegistries.getFeatureDecoratorSet(level.registryAccess(), id);
            if(set.isEmpty()) {
                PathDataManager.warnMissingOnce("Feature decorator set", id);
                continue;
            }
            scatterSet(level, generator, featureRegistry, waypoints, set.get(), biomes, chunkX, chunkZ, random, guard);
        }
    }

    //////////////////////////////

    //? if <1.21.11 {
    private static void scatterSet(WorldGenLevel level, ChunkGenerator generator, Registry<ConfiguredFeature<?, ?>> featureRegistry, List<BlockPos> waypoints, FeatureDecoratorSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, PlacementGuard guard) {
    //?} elif <26.3 {
    /*private static void scatterSet(WorldGenLevel level, ChunkGenerator generator, HolderLookup.RegistryLookup<ConfiguredFeature<?, ?>> featureRegistry, List<BlockPos> waypoints, FeatureDecoratorSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, PlacementGuard guard) {
    *///?} else {
    /*private static void scatterSet(WorldGenLevel level, ChunkGenerator generator, HolderLookup.RegistryLookup<Feature> featureRegistry, List<BlockPos> waypoints, FeatureDecoratorSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, PlacementGuard guard) {
    *///?}
        int reach = set.scatterWidth() + 1;

        WaypointScatterer.scatter(waypoints, set.density(), reach, chunkX, chunkZ, random,
            (from, d, parX, parZ, perpX, perpZ, segRandom) -> {
                var feature = pickWeighted(set.features(), featureRegistry, segRandom);
                if(feature == null) return;

                int side = WaypointScatterer.sideSign(set.side(), segRandom);
                int offset = side == 0 ? 0 : 1 + (set.scatterWidth() > 1 ? segRandom.nextInt(set.scatterWidth()) : 0);

                int bx = from.getX() + Math.round(parX * d + perpX * offset * side);
                int bz = from.getZ() + Math.round(parZ * d + perpZ * offset * side);

                if((bx >> 4) == chunkX && (bz >> 4) == chunkZ) {
                    tryPlace(level, generator, feature, bx, bz, biomes, segRandom, guard);
                }
            });
    }

    //? if >=26.3 {
    /*private static void tryPlace(WorldGenLevel level, ChunkGenerator generator, Feature feature, int bx, int bz, HolderSet<Biome> biomes, RandomSource random, PlacementGuard guard) {
    *///?} else {
    private static void tryPlace(WorldGenLevel level, ChunkGenerator generator, ConfiguredFeature<?, ?> feature, int bx, int bz, HolderSet<Biome> biomes, RandomSource random, PlacementGuard guard) {
    //?}
        int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        //? if <1.21.11 {
        if(sy <= level.getMinBuildHeight()) return;
        //?} else {
        /*if(sy <= level.getMinY()) return;
        *///?}
        if(guard.insidePiece(bx, sy - 1, sy + 2, bz)) return;
        BlockPos pos = new BlockPos(bx, sy, bz);
        if(Constants.ENABLE_DEBUG_TIMER) PathDataManager.recordBiomeCall(com.finndog.moogs_paths.data.BiomeCallSite.FEATURE_PLACE_CHECK);
        var biome = level.getBiome(pos);
        if(!biomes.contains(biome) || biome.is(PathChunkFeature.HAS_NO_PATHS)) return;
        feature.place(level, generator, random, pos);
    }

    //? if <1.21.11 {
    private static ConfiguredFeature<?, ?> pickWeighted(List<FeatureDecoratorSet.FeatureEntry> entries, Registry<ConfiguredFeature<?, ?>> registry, RandomSource random) {
    //?} elif <26.3 {
    /*private static ConfiguredFeature<?, ?> pickWeighted(List<FeatureDecoratorSet.FeatureEntry> entries, HolderLookup.RegistryLookup<ConfiguredFeature<?, ?>> registry, RandomSource random) {
    *///?} else {
    /*private static Feature pickWeighted(List<FeatureDecoratorSet.FeatureEntry> entries, HolderLookup.RegistryLookup<Feature> registry, RandomSource random) {
    *///?}
        int total = 0;
        for(FeatureDecoratorSet.FeatureEntry e : entries) total += e.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(FeatureDecoratorSet.FeatureEntry e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) {
                //? if <1.21.11 {
                ConfiguredFeature<?, ?> feature = registry.getOptional(e.feature()).orElse(null);
                //?} elif <26.3 {
                /*ConfiguredFeature<?, ?> feature = registry.get(ResourceKey.create(Registries.CONFIGURED_FEATURE, e.feature()))
                    .map(Holder::value).orElse(null);
                *///?} else {
                /*Feature feature = registry.get(ResourceKey.create(Registries.FEATURE, e.feature()))
                    .map(Holder::value).orElse(null);
                *///?}
                if(feature == null && WARNED_MISSING.add(e.feature())) Constants.LOG.warn("Configured feature not found: {}", e.feature());
                return feature;
            }
        }
        return null;
    }
}
