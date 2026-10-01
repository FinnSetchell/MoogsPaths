package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.config.MoogsPathsConfig;
import com.finndog.moogs_paths.platform.Services;
import net.minecraft.core.Holder;
//? if >=1.21.11 {
/*import net.minecraft.core.HolderLookup;
*///?}
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class MoogsPathsDatapackRegistries {

    private static volatile DerivedNetworkViews cachedDerivedViews = null;

    public static final ResourceKey<Registry<PathType>> PATH_TYPE =
        //? if >=1.21.1 {
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "path_type"));
        //?} else {
        /*ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "path_type"));
        *///?}

    public static final ResourceKey<Registry<PathNetworkType>> PATH_NETWORK =
        //? if >=1.21.1 {
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "path_network"));
        //?} else {
        /*ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "path_network"));
        *///?}

    public static final ResourceKey<Registry<StructureSet>> STRUCTURE_SET =
        //? if >=1.21.1 {
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "structure_set"));
        //?} else {
        /*ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "structure_set"));
        *///?}

    public static final ResourceKey<Registry<FeatureDecoratorSet>> FEATURE_DECORATOR_SET =
        //? if >=1.21.1 {
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "feature_decorator_set"));
        //?} else {
        /*ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "feature_decorator_set"));
        *///?}

    public static final ResourceKey<Registry<BushDecoratorSet>> BUSH_DECORATOR_SET =
        //? if >=1.21.1 {
        ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "bush_decorator_set"));
        //?} else {
        /*ResourceKey.createRegistryKey(new ResourceLocation(Constants.MOD_ID, "bush_decorator_set"));
        *///?}

    public static void register() {
        Services.PLATFORM.registerDatapackRegistry(PATH_TYPE, PathType.CODEC);
        Services.PLATFORM.registerDatapackRegistry(PATH_NETWORK, PathNetworkType.CODEC);
        Services.PLATFORM.registerDatapackRegistry(STRUCTURE_SET, StructureSet.CODEC);
        Services.PLATFORM.registerDatapackRegistry(FEATURE_DECORATOR_SET, FeatureDecoratorSet.CODEC);
        Services.PLATFORM.registerDatapackRegistry(BUSH_DECORATOR_SET, BushDecoratorSet.CODEC);
    }

    // 26.1: RegistryAccess#registryOrThrow was renamed to lookupOrThrow, and entries are looked up
    // by ResourceKey via HolderLookup.RegistryLookup#get(ResourceKey).
    //? if >=1.21.11 {
    /*private static <T> Optional<T> getByLocation(RegistryAccess access, ResourceKey<Registry<T>> registry, ResourceLocation id) {
        HolderLookup.RegistryLookup<T> lookup = access.lookupOrThrow(registry);
        return lookup.get(ResourceKey.create(registry, id)).map(Holder::value);
    }

    *///?}
    public static Optional<PathType> getPathType(RegistryAccess access, ResourceLocation id) {
        //? if <1.21.11 {
        return access.registryOrThrow(PATH_TYPE).getOptional(id);
        //?} else {
        /*return getByLocation(access, PATH_TYPE, id);
        *///?}
    }

    public static Optional<PathNetworkType> getPathNetwork(RegistryAccess access, ResourceLocation id) {
        //? if <1.21.11 {
        return access.registryOrThrow(PATH_NETWORK).getOptional(id);
        //?} else {
        /*return getByLocation(access, PATH_NETWORK, id);
        *///?}
    }

    public static Optional<StructureSet> getStructureSet(RegistryAccess access, ResourceLocation id) {
        //? if <1.21.11 {
        return access.registryOrThrow(STRUCTURE_SET).getOptional(id);
        //?} else {
        /*return getByLocation(access, STRUCTURE_SET, id);
        *///?}
    }

    public static Optional<FeatureDecoratorSet> getFeatureDecoratorSet(RegistryAccess access, ResourceLocation id) {
        //? if <1.21.11 {
        return access.registryOrThrow(FEATURE_DECORATOR_SET).getOptional(id);
        //?} else {
        /*return getByLocation(access, FEATURE_DECORATOR_SET, id);
        *///?}
    }

    public static Optional<BushDecoratorSet> getBushDecoratorSet(RegistryAccess access, ResourceLocation id) {
        //? if <1.21.11 {
        return access.registryOrThrow(BUSH_DECORATOR_SET).getOptional(id);
        //?} else {
        /*return getByLocation(access, BUSH_DECORATOR_SET, id);
        *///?}
    }

    //? if <1.21.11 {
    public static Registry<PathNetworkType> pathNetworkRegistry(RegistryAccess access) {
        return access.registryOrThrow(PATH_NETWORK);
    //?} else {
    /*public static HolderLookup.RegistryLookup<PathNetworkType> pathNetworkRegistry(RegistryAccess access) {
        return access.lookupOrThrow(PATH_NETWORK);
    *///?}
    }

    public static Map<Integer, List<PathNetworkType>> networksByRegionSize(RegistryAccess access) {
        return derivedViews(access).byRegionSize;
    }

    public static int networksMaxRadiusForRegionSize(RegistryAccess access, int regionSize) {
        return derivedViews(access).maxRadiusByRegionSize.getOrDefault(regionSize, 1000);
    }

    /** Networks whose paths start at structures. They never take part in region origins. */
    public static List<AnchoredNetwork> anchoredNetworks(RegistryAccess access) {
        return derivedViews(access).anchored;
    }

    /** A structure-anchored network, its id, and how far (in blocks) its paths can reach. */
    public record AnchoredNetwork(PathNetworkType network, ResourceLocation id, int maxRadius) {}

    /** Every loaded network by id, in id order. */
    public static Map<ResourceLocation, PathNetworkType> networksById(RegistryAccess access) {
        Map<ResourceLocation, PathNetworkType> byId = new java.util.TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        networkHolders(access).forEach(h -> byId.put(keyId(h.key()), h.value()));
        return byId;
    }

    /** The id a loaded network instance is registered under. */
    public static Optional<ResourceLocation> networkId(RegistryAccess access, PathNetworkType network) {
        return networkHolders(access).filter(h -> h.value() == network).findFirst().map(h -> keyId(h.key()));
    }

    /** A vanilla or modded structure set (worldgen/structure_set), which carries a structure's placement. */
    public static Optional<net.minecraft.world.level.levelgen.structure.StructureSet> vanillaStructureSet(RegistryAccess access, ResourceLocation id) {
        //? if <1.21.11 {
        return access.registryOrThrow(Registries.STRUCTURE_SET).getOptional(id);
        //?} else {
        /*return getByLocation(access, Registries.STRUCTURE_SET, id);
        *///?}
    }

    // 1.21.11 renamed ResourceKey#location to identifier.
    public static ResourceLocation keyId(ResourceKey<?> key) {
        //? if <1.21.11 {
        return key.location();
        //?} else {
        /*return key.identifier();
        *///?}
    }

    private static Stream<Holder.Reference<PathNetworkType>> networkHolders(RegistryAccess access) {
        //? if <1.21.11 {
        return access.registryOrThrow(PATH_NETWORK).holders();
        //?} else {
        /*return access.lookupOrThrow(PATH_NETWORK).listElements();
        *///?}
    }

    public static void invalidateDerivedViews() {
        cachedDerivedViews = null;
    }

    // search radius = max path length * 1.5 - covers A* ellipse (1.35x) + path width + margin
    private static final double RADIUS_LENGTH_MULTIPLIER = 1.5;

    private static int maxRadius(RegistryAccess access, PathNetworkType network) {
        return getPathType(access, network.pathType())
            .map(pt -> (int) Math.ceil(pt.maxLength() * RADIUS_LENGTH_MULTIPLIER))
            .orElse(1000);
    }

    private static DerivedNetworkViews derivedViews(RegistryAccess access) {
        DerivedNetworkViews views = cachedDerivedViews;
        if(views == null) {
            Map<Integer, List<PathNetworkType>> byRegion = Collections.unmodifiableMap(networkHolders(access)
                .map(Holder::value)
                .filter(n -> !n.isStructureAnchored())
                .collect(Collectors.groupingBy(PathNetworkType::regionSize)));
            Map<Integer, Integer> maxByRegion = byRegion.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    e -> e.getValue().stream().mapToInt(n -> maxRadius(access, n)).max().orElse(1000)
                ));
            // Sorted by id so every chunk walks the anchored networks in the same order. A network none
            // of whose structure sets is loaded (they all belong to mods that aren't installed) is left out,
            // as is one the config turns off.
            Set<String> setNamespaces = structureSetNamespaces(access);
            List<AnchoredNetwork> anchored = networkHolders(access)
                .filter(h -> h.value().isStructureAnchored() && MoogsPathsConfig.chance(h.value()) > 0
                    && anyStructureSetLoaded(access, h.value().origin().orElseThrow(), setNamespaces))
                .map(h -> new AnchoredNetwork(h.value(), keyId(h.key()), maxRadius(access, h.value())))
                .sorted(Comparator.comparing(a -> a.id().toString()))
                .toList();
            views = new DerivedNetworkViews(byRegion, maxByRegion, anchored);
            cachedDerivedViews = views;
        }
        return views;
    }

    // A list of sets may name optional modded ones, so only a network with none loaded warns, and
    // only about sets of a mod that is installed: a network for another mod's structures (the MVS
    // houses) stays quiet without that mod, but a misspelt or renamed set still shows up.
    private static boolean anyStructureSetLoaded(RegistryAccess access, StructureOrigin origin, Set<String> setNamespaces) {
        for(ResourceLocation id : origin.structureSets()) {
            if(vanillaStructureSet(access, id).isPresent()) return true;
        }
        origin.structureSets().stream()
            .filter(id -> setNamespaces.contains(id.getNamespace()))
            .forEach(id -> PathDataManager.warnMissingOnce("Structure set", id));
        return false;
    }

    // The namespaces with any structure set loaded: roughly, which structure mods are installed.
    private static Set<String> structureSetNamespaces(RegistryAccess access) {
        //? if <1.21.11 {
        Stream<Holder.Reference<net.minecraft.world.level.levelgen.structure.StructureSet>> holders = access.registryOrThrow(Registries.STRUCTURE_SET).holders();
        //?} else {
        /*Stream<Holder.Reference<net.minecraft.world.level.levelgen.structure.StructureSet>> holders = access.lookupOrThrow(Registries.STRUCTURE_SET).listElements();
        *///?}
        return holders.map(h -> keyId(h.key()).getNamespace()).collect(Collectors.toSet());
    }

    private record DerivedNetworkViews(
        Map<Integer, List<PathNetworkType>> byRegionSize,
        Map<Integer, Integer> maxRadiusByRegionSize,
        List<AnchoredNetwork> anchored
    ) {}

    private MoogsPathsDatapackRegistries() {}
}
