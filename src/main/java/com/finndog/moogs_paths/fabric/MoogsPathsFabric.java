package com.finndog.moogs_paths.fabric;

import com.finndog.moogs_paths.MoogsPathsCommon;
import com.finndog.moogs_paths.world.MoogsPathsRegistries;
import com.finndog.moogs_paths.world.PathChunkFeature;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.GenerationStep;
//? if <26.3 {
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
//?}

public class MoogsPathsFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // 26.3 registers a feature type as its codec; the feature itself is data (worldgen/feature).
        //? if >=26.3 {
        /*Registry.register(BuiltInRegistries.FEATURE_TYPE, MoogsPathsRegistries.PATH_GEN_ID, PathChunkFeature.CODEC);
        *///?} else {
        Registry.register(BuiltInRegistries.FEATURE, MoogsPathsRegistries.PATH_GEN_ID, new PathChunkFeature(NoneFeatureConfiguration.CODEC));
        //?}

        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Decoration.TOP_LAYER_MODIFICATION,
            ResourceKey.create(Registries.PLACED_FEATURE, MoogsPathsRegistries.PATH_GEN_ID)
        );

        MoogsPathsCommon.init();
    }
}
