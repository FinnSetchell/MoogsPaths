package com.finndog.moogs_paths.neoforge;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.MoogsPathsCommon;
import com.finndog.moogs_paths.world.MoogsPathsRegistries;
import com.finndog.moogs_paths.world.PathChunkFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Constants.MOD_ID)
public class MoogsPathsNeoForge {

    public MoogsPathsNeoForge(IEventBus eventBus) {
        NeoForgePlatformHelper.modEventBus = eventBus;

        DeferredRegister<Feature<?>> features = DeferredRegister.create(Registries.FEATURE, Constants.MOD_ID);
        features.register(MoogsPathsRegistries.PATH_GEN_ID.getPath(), () -> new PathChunkFeature(NoneFeatureConfiguration.CODEC));
        features.register(eventBus);

        MoogsPathsCommon.init();
    }
}
