package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class PathDataManager {

    private static final Gson GSON = new GsonBuilder().create();

    private static final int WAYPOINT_CACHE_MAX_SIZE = 256;

    // keyed by pathSeed, computed once per path origin and shared across all chunks that touch it
    // bounded LRU so far-away paths can be evicted during long exploration sessions
    private static final Map<Long, List<List<BlockPos>>> WAYPOINT_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, List<List<BlockPos>>> eldest) {
                return size() > WAYPOINT_CACHE_MAX_SIZE;
            }
        });

    // volatile: apply() runs on the main thread; worldgen threads read these concurrently
    private static volatile Map<ResourceLocation, PathType> PATH_TYPES = Map.of();
    private static volatile Map<ResourceLocation, PathNetworkType> PATH_NETWORKS = Map.of();
    private static volatile Map<ResourceLocation, StructureSet> STRUCTURE_SETS = Map.of();
    private static volatile Map<ResourceLocation, FeatureDecoratorSet> DECORATOR_SETS = Map.of();
    private static volatile Map<ResourceLocation, BushDecoratorSet> BUSH_DECORATOR_SETS = Map.of();
    // lazy cache: templates are fetched on first request and cleared on every reload
    private static final Map<ResourceLocation, Optional<StructureTemplate>> CACHED_TEMPLATES = new ConcurrentHashMap<>();
    private static volatile Map<Integer, List<PathNetworkType>> NETWORKS_BY_REGION_SIZE = Map.of();
    private static volatile int NETWORKS_MAX_RADIUS = 1000;

    private static volatile StructureTemplateManager templateManager;

    private PathDataManager() {}

    //////////////////////////////

    public static Map<ResourceLocation, SimpleJsonResourceReloadListener> createListeners() {
        Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners = new LinkedHashMap<>();

        listeners.put(new ResourceLocation(Constants.MOD_ID, "path_types_listener"),
            new SimpleJsonResourceReloadListener(GSON, "path_types") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, PathType> fresh = new HashMap<>();
                    map.forEach((id, json) -> {
                        if(json.isJsonObject() && json.getAsJsonObject().has("slope_avoidance"))
                            Constants.LOG.warn("Path type {} has removed field 'slope_avoidance' - use 'max_slope_per_step' and 'slope_cost_weight' instead", id);
                        PathType.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load path type {}: {}", id, e))
                            .ifPresent(pt -> fresh.put(id, pt));
                    });
                    PATH_TYPES = Collections.unmodifiableMap(fresh);
                    WAYPOINT_CACHE.clear();
                    Constants.LOG.info("Loaded {} path types", PATH_TYPES.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "path_networks_listener"),
            new SimpleJsonResourceReloadListener(GSON, "path_networks") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, PathNetworkType> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        PathNetworkType.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load path network {}: {}", id, e))
                            .ifPresent(pn -> fresh.put(id, pn))
                    );
                    PATH_NETWORKS = Collections.unmodifiableMap(fresh);
                    WAYPOINT_CACHE.clear();
                    NETWORKS_BY_REGION_SIZE = Collections.unmodifiableMap(
                        fresh.values().stream().collect(Collectors.groupingBy(PathNetworkType::regionSize)));
                    NETWORKS_MAX_RADIUS = fresh.values().stream()
                        .mapToInt(n -> n.scale().lengthMax)
                        .max().orElse(1000);
                    Constants.LOG.info("Loaded {} path networks", PATH_NETWORKS.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "structure_sets_listener"),
            new SimpleJsonResourceReloadListener(GSON, "structure_sets") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, StructureSet> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        StructureSet.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load structure set {}: {}", id, e))
                            .ifPresent(ss -> fresh.put(id, ss))
                    );
                    STRUCTURE_SETS = Collections.unmodifiableMap(fresh);
                    CACHED_TEMPLATES.clear();
                    Constants.LOG.info("Loaded {} structure sets", STRUCTURE_SETS.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "decorator_sets_listener"),
            new SimpleJsonResourceReloadListener(GSON, "feature_decorator_sets") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, FeatureDecoratorSet> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        FeatureDecoratorSet.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load decorator set {}: {}", id, e))
                            .ifPresent(ds -> fresh.put(id, ds))
                    );
                    DECORATOR_SETS = Collections.unmodifiableMap(fresh);
                    Constants.LOG.info("Loaded {} feature decorator sets", DECORATOR_SETS.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "bush_decorator_sets_listener"),
            new SimpleJsonResourceReloadListener(GSON, "bush_decorator_sets") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, BushDecoratorSet> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        BushDecoratorSet.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load bush decorator set {}: {}", id, e))
                            .ifPresent(bs -> fresh.put(id, bs))
                    );
                    BUSH_DECORATOR_SETS = Collections.unmodifiableMap(fresh);
                    Constants.LOG.info("Loaded {} bush decorator sets", BUSH_DECORATOR_SETS.size());
                    validateReferences();
                }
            }
        );

        return listeners;
    }

    // LinkedHashMap preserves registration order so the bush listener runs last
    // and sees every other map fully populated when validating cross-references
    private static void validateReferences() {
        int warnings = 0;
        for(Map.Entry<ResourceLocation, PathNetworkType> entry : PATH_NETWORKS.entrySet()) {
            ResourceLocation netId = entry.getKey();
            PathNetworkType net = entry.getValue();

            if(!PATH_TYPES.containsKey(net.pathType())) {
                Constants.LOG.warn("Path network {} references missing path_type {}", netId, net.pathType());
                warnings++;
            }
            for(PathNetworkType.WeightedRef ref : net.structureSets()) {
                if(!STRUCTURE_SETS.containsKey(ref.id())) {
                    Constants.LOG.warn("Path network {} references missing structure_set {}", netId, ref.id());
                    warnings++;
                }
            }
            for(PathNetworkType.WeightedRef ref : net.featureDecoratorSets()) {
                if(!DECORATOR_SETS.containsKey(ref.id())) {
                    Constants.LOG.warn("Path network {} references missing feature_decorator_set {}", netId, ref.id());
                    warnings++;
                }
            }
            for(PathNetworkType.WeightedRef ref : net.bushDecoratorSets()) {
                if(!BUSH_DECORATOR_SETS.containsKey(ref.id())) {
                    Constants.LOG.warn("Path network {} references missing bush_decorator_set {}", netId, ref.id());
                    warnings++;
                }
            }
        }
        if(warnings > 0) Constants.LOG.warn("moogs_paths: {} cross-reference warning(s) during reload", warnings);
    }

    //////////////////////////////

    public static Optional<PathType> getPathType(ResourceLocation id) {
        return Optional.ofNullable(PATH_TYPES.get(id));
    }

    public static Optional<PathNetworkType> getPathNetwork(ResourceLocation id) {
        return Optional.ofNullable(PATH_NETWORKS.get(id));
    }

    public static Optional<StructureSet> getStructureSet(ResourceLocation id) {
        return Optional.ofNullable(STRUCTURE_SETS.get(id));
    }

    public static Optional<FeatureDecoratorSet> getDecoratorSet(ResourceLocation id) {
        return Optional.ofNullable(DECORATOR_SETS.get(id));
    }

    public static Optional<BushDecoratorSet> getBushDecoratorSet(ResourceLocation id) {
        return Optional.ofNullable(BUSH_DECORATOR_SETS.get(id));
    }

    public static Map<ResourceLocation, PathType> getPathTypesSnapshot() {
        return PATH_TYPES;
    }

    public static Map<ResourceLocation, PathNetworkType> getPathNetworksSnapshot() {
        return PATH_NETWORKS;
    }

    public static Map<ResourceLocation, StructureSet> getStructureSetsSnapshot() {
        return STRUCTURE_SETS;
    }

    public static Map<ResourceLocation, FeatureDecoratorSet> getDecoratorSetsSnapshot() {
        return DECORATOR_SETS;
    }

    public static Map<ResourceLocation, BushDecoratorSet> getBushDecoratorSetsSnapshot() {
        return BUSH_DECORATOR_SETS;
    }

    public static Map<ResourceLocation, Optional<StructureTemplate>> getCachedTemplatesSnapshot() {
        return CACHED_TEMPLATES;
    }

    public static Map<Integer, List<PathNetworkType>> getNetworksByRegionSize() {
        return NETWORKS_BY_REGION_SIZE;
    }

    public static int getNetworksMaxRadius() {
        return NETWORKS_MAX_RADIUS;
    }

    public static Optional<StructureTemplate> getCachedTemplate(ResourceLocation id) {
        StructureTemplateManager mgr = templateManager;
        if(mgr == null) return Optional.empty();
        return CACHED_TEMPLATES.computeIfAbsent(id, key -> {
            Optional<StructureTemplate> tmpl = mgr.get(key);
            if(tmpl.isEmpty()) Constants.LOG.warn("Structure template not found: {}", key);
            return tmpl;
        });
    }

    public static List<List<BlockPos>> getOrComputeWaypoints(long pathSeed, Supplier<List<List<BlockPos>>> computer) {
        return WAYPOINT_CACHE.computeIfAbsent(pathSeed, k -> computer.get());
    }

    public static void onServerStart(StructureTemplateManager manager) {
        templateManager = manager;
        WAYPOINT_CACHE.clear();
        CACHED_TEMPLATES.clear();
    }
}
