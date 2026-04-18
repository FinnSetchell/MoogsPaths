package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.platform.Services;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

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

    public static void register() {
        Services.PLATFORM.registerDatapackRegistry(PATH_TYPE, PathType.CODEC);
        Services.PLATFORM.registerDatapackRegistry(PATH_NETWORK, PathNetworkType.CODEC);
        Services.PLATFORM.registerDatapackRegistry(STRUCTURE_SET, StructureSet.CODEC);
        Services.PLATFORM.registerDatapackRegistry(FEATURE_DECORATOR_SET, FeatureDecoratorSet.CODEC);
        Services.PLATFORM.registerDatapackRegistry(BUSH_DECORATOR_SET, BushDecoratorSet.CODEC);
    }

    public static Optional<PathType> getPathType(RegistryAccess access, ResourceLocation id) {
        return access.registryOrThrow(PATH_TYPE).getOptional(id);
    }

    public static Optional<PathNetworkType> getPathNetwork(RegistryAccess access, ResourceLocation id) {
        return access.registryOrThrow(PATH_NETWORK).getOptional(id);
    }

    public static Optional<StructureSet> getStructureSet(RegistryAccess access, ResourceLocation id) {
        return access.registryOrThrow(STRUCTURE_SET).getOptional(id);
    }

    public static Optional<FeatureDecoratorSet> getFeatureDecoratorSet(RegistryAccess access, ResourceLocation id) {
        return access.registryOrThrow(FEATURE_DECORATOR_SET).getOptional(id);
    }

    public static Optional<BushDecoratorSet> getBushDecoratorSet(RegistryAccess access, ResourceLocation id) {
        return access.registryOrThrow(BUSH_DECORATOR_SET).getOptional(id);
    }

    public static Registry<PathNetworkType> pathNetworkRegistry(RegistryAccess access) {
        return access.registryOrThrow(PATH_NETWORK);
    }

    private MoogsPathsDatapackRegistries() {}
}
