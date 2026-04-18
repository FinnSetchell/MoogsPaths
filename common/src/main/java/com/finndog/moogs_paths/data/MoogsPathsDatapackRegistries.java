package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

public final class MoogsPathsDatapackRegistries {

    public static final ResourceKey<Registry<PathType>> PATH_TYPE =
        ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "path_type"));

    public static final ResourceKey<Registry<PathNetworkType>> PATH_NETWORK =
        ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "path_network"));

    public static final ResourceKey<Registry<StructureSet>> STRUCTURE_SET =
        ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "structure_set"));

    public static final ResourceKey<Registry<FeatureDecoratorSet>> FEATURE_DECORATOR_SET =
        ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "feature_decorator_set"));

    public static final ResourceKey<Registry<BushDecoratorSet>> BUSH_DECORATOR_SET =
        ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "bush_decorator_set"));

    private MoogsPathsDatapackRegistries() {}
}
