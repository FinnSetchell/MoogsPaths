package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.platform.Services;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class MoogsPathsDatapackRegistries {

    private static volatile DerivedNetworkViews cachedDerivedViews = null;

    public static final ResourceKey<Registry<PathType>> PATH_TYPE =
        ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "path_type"));

    public static final ResourceKey<Registry<PathNetworkType>> PATH_NETWORK =
        ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "path_network"));

    public static final ResourceKey<Registry<StructureSet>> STRUCTURE_SET =
        ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structure_set"));

    public static final ResourceKey<Registry<FeatureDecoratorSet>> FEATURE_DECORATOR_SET =
        ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "feature_decorator_set"));

    public static final ResourceKey<Registry<BushDecoratorSet>> BUSH_DECORATOR_SET =
        ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "bush_decorator_set"));

    public static void register() {
        Services.PLATFORM.registerDatapackRegistry(PATH_TYPE, PathType.CODEC);
        Services.PLATFORM.registerDatapackRegistry(PATH_NETWORK, PathNetworkType.CODEC);
        Services.PLATFORM.registerDatapackRegistry(STRUCTURE_SET, StructureSet.CODEC);
        Services.PLATFORM.registerDatapackRegistry(FEATURE_DECORATOR_SET, FeatureDecoratorSet.CODEC);
        Services.PLATFORM.registerDatapackRegistry(BUSH_DECORATOR_SET, BushDecoratorSet.CODEC);
    }

    // 26.1: RegistryAccess#registryOrThrow was renamed to lookupOrThrow, and entries are looked up
    // by ResourceKey via HolderLookup.RegistryLookup#get(ResourceKey).
    private static <T> Optional<T> getByLocation(RegistryAccess access, ResourceKey<Registry<T>> registry, Identifier id) {
        HolderLookup.RegistryLookup<T> lookup = access.lookupOrThrow(registry);
        return lookup.get(ResourceKey.create(registry, id)).map(Holder::value);
    }

    public static Optional<PathType> getPathType(RegistryAccess access, Identifier id) {
        return getByLocation(access, PATH_TYPE, id);
    }

    public static Optional<PathNetworkType> getPathNetwork(RegistryAccess access, Identifier id) {
        return getByLocation(access, PATH_NETWORK, id);
    }

    public static Optional<StructureSet> getStructureSet(RegistryAccess access, Identifier id) {
        return getByLocation(access, STRUCTURE_SET, id);
    }

    public static Optional<FeatureDecoratorSet> getFeatureDecoratorSet(RegistryAccess access, Identifier id) {
        return getByLocation(access, FEATURE_DECORATOR_SET, id);
    }

    public static Optional<BushDecoratorSet> getBushDecoratorSet(RegistryAccess access, Identifier id) {
        return getByLocation(access, BUSH_DECORATOR_SET, id);
    }

    public static HolderLookup.RegistryLookup<PathNetworkType> pathNetworkRegistry(RegistryAccess access) {
        return access.lookupOrThrow(PATH_NETWORK);
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
            HolderLookup.RegistryLookup<PathNetworkType> networks = access.lookupOrThrow(PATH_NETWORK);
            HolderLookup.RegistryLookup<PathType> pathTypes = access.lookupOrThrow(PATH_TYPE);
            Map<Integer, List<PathNetworkType>> byRegion = Collections.unmodifiableMap(
                networks.listElements()
                    .map(Holder::value)
                    .collect(Collectors.groupingBy(PathNetworkType::regionSize)));
            Map<Integer, Integer> maxByRegion = byRegion.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    e -> e.getValue().stream()
                        .mapToInt(n -> pathTypes.get(ResourceKey.create(PATH_TYPE, n.pathType()))
                            .map(Holder::value)
                            .map(pt -> (int) Math.ceil(pt.length().getMaxValue() * RADIUS_LENGTH_MULTIPLIER))
                            .orElse(1000))
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
