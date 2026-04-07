package com.finndog.moogs_paths;

import com.finndog.moogs_paths.world.MoogsPathsRegistries;
import com.finndog.moogs_paths.world.PathChunkFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(Constants.MOD_ID)
public class MoogsPaths {

    public MoogsPaths() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        DeferredRegister<Feature<?>> features = DeferredRegister.create(ForgeRegistries.FEATURES, Constants.MOD_ID);
        features.register(MoogsPathsRegistries.PATH_GEN_ID.getPath(), () -> new PathChunkFeature(NoneFeatureConfiguration.CODEC));
        features.register(bus);

        MoogsPathsCommon.init();
    }
}
