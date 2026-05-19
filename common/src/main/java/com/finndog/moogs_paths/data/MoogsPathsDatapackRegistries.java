package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.platform.Services;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class MoogsPathsDatapackRegistries {

    private static volatile DerivedNetworkViews cachedDerivedViews = null;

    public static final ResourceKey<Registry<PathType>> PATH_TYPE =
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "path_type"));

    public static final ResourceKey<Registry<PathNetworkType>> PATH_NETWORK =
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "path_network"));

    public static final ResourceKey<Registry<StructureSet>> STRUCTURE_SET =
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "structure_set"));

    public static final ResourceKey<Registry<FeatureDecoratorSet>> FEATURE_DECORATOR_SET =
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "feature_decorator_set"));

    public static final ResourceKey<Registry<BushDecoratorSet>> BUSH_DECORATOR_SET =
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "bush_decorator_set"));

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

    public static Map<Integer, List<PathNetworkType>> networksByRegionSize(RegistryAccess access) {
        return derivedViews(access).byRegionSize;
    }

    public static int networksMaxRadiusForRegionSize(RegistryAccess access, int regionSize) {
        return derivedViews(access).maxRadiusByRegionSize.getOrDefault(regionSize, 1000);
    }

    public static void invalidateDerivedViews() {
        cachedDerivedViews = null;
    }

    // search radius = max path length * 1.5 - covers A* ellipse (1.35x) + path width + margin
    private static final double RADIUS_LENGTH_MULTIPLIER = 1.5;

    private static DerivedNetworkViews derivedViews(RegistryAccess access) {
        DerivedNetworkViews views = cachedDerivedViews;
        if(views == null) {
            Registry<PathNetworkType> networks = access.registryOrThrow(PATH_NETWORK);
            Registry<PathType> pathTypes = access.registryOrThrow(PATH_TYPE);
            Map<Integer, List<PathNetworkType>> byRegion = Collections.unmodifiableMap(
                networks.stream().collect(Collectors.groupingBy(PathNetworkType::regionSize)));
            Map<Integer, Integer> maxByRegion = byRegion.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    e -> e.getValue().stream()
                        .mapToInt(n -> Optional.ofNullable(pathTypes.get(n.pathType())).map(pt -> (int) Math.ceil(pt.length().getMaxValue() * RADIUS_LENGTH_MULTIPLIER)).orElse(1000))
                        .max().orElse(1000)
                ));
            views = new DerivedNetworkViews(byRegion, maxByRegion);
            cachedDerivedViews = views;
        }
        return views;
    }

    private record DerivedNetworkViews(
        Map<Integer, List<PathNetworkType>> byRegionSize,
        Map<Integer, Integer> maxRadiusByRegionSize
    ) {}

    private MoogsPathsDatapackRegistries() {}
}
