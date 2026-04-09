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

import java.util.*;
import java.util.stream.Collectors;

public final class PathDataManager {

    private static final Gson GSON = new GsonBuilder().create();

    // volatile: apply() runs on the main thread; worldgen threads read these concurrently
    private static volatile Map<ResourceLocation, PathType> PATH_TYPES = Map.of();
    private static volatile Map<ResourceLocation, PathNetworkType> PATH_NETWORKS = Map.of();
    private static volatile Map<ResourceLocation, StructureSet> STRUCTURE_SETS = Map.of();
    private static volatile Map<ResourceLocation, FeatureDecoratorSet> DECORATOR_SETS = Map.of();
    private static volatile Map<ResourceLocation, Optional<StructureTemplate>> CACHED_TEMPLATES = Map.of();
    private static volatile Map<Integer, List<PathNetworkType>> NETWORKS_BY_REGION_SIZE = Map.of();
    private static volatile int NETWORKS_MAX_RADIUS = 1000;

    private static StructureTemplateManager templateManager;

    private PathDataManager() {}

    //////////////////////////////

    public static Map<ResourceLocation, SimpleJsonResourceReloadListener> createListeners() {
        Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners = new LinkedHashMap<>();

        listeners.put(new ResourceLocation(Constants.MOD_ID, "path_types_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/path_types") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, PathType> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        PathType.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load path type {}: {}", id, e))
                            .ifPresent(pt -> fresh.put(id, pt))
                    );
                    PATH_TYPES = Collections.unmodifiableMap(fresh);
                    Constants.LOG.info("Loaded {} path types", PATH_TYPES.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "path_networks_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/path_networks") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, PathNetworkType> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        PathNetworkType.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load path network {}: {}", id, e))
                            .ifPresent(pn -> fresh.put(id, pn))
                    );
                    PATH_NETWORKS = Collections.unmodifiableMap(fresh);
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
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/structure_sets") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    Map<ResourceLocation, StructureSet> fresh = new HashMap<>();
                    map.forEach((id, json) ->
                        StructureSet.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load structure set {}: {}", id, e))
                            .ifPresent(ss -> fresh.put(id, ss))
                    );
                    STRUCTURE_SETS = Collections.unmodifiableMap(fresh);
                    Constants.LOG.info("Loaded {} structure sets", STRUCTURE_SETS.size());
                    reloadTemplates();
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "decorator_sets_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/feature_decorator_sets") {
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

        return listeners;
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

    public static Collection<PathNetworkType> getAllNetworks() {
        return PATH_NETWORKS.values();
    }

    public static Set<ResourceLocation> getAllNetworkIds() {
        return PATH_NETWORKS.keySet();
    }

    public static Map<Integer, List<PathNetworkType>> getNetworksByRegionSize() {
        return NETWORKS_BY_REGION_SIZE;
    }

    public static int getNetworksMaxRadius() {
        return NETWORKS_MAX_RADIUS;
    }

    public static Optional<StructureTemplate> getCachedTemplate(ResourceLocation id) {
        return CACHED_TEMPLATES.getOrDefault(id, Optional.empty());
    }

    public static int getCachedTemplateCount() {
        return CACHED_TEMPLATES.size();
    }

    public static Collection<ResourceLocation> getAllStructureIds() {
        return CACHED_TEMPLATES.keySet();
    }

    public static void onServerStart(StructureTemplateManager manager) {
        templateManager = manager;
        reloadTemplates();
    }

    private static void reloadTemplates() {
        if(templateManager == null) return;
        Map<ResourceLocation, Optional<StructureTemplate>> fresh = new HashMap<>();
        STRUCTURE_SETS.values().forEach(set ->
            set.structures().forEach(entry -> {
                ResourceLocation id = entry.nbt();
                if(!fresh.containsKey(id)) {
                    Optional<StructureTemplate> tmpl = templateManager.get(id);
                    if(tmpl.isEmpty()) Constants.LOG.warn("Structure template not found: {}", id);
                    fresh.put(id, tmpl);
                }
            })
        );
        CACHED_TEMPLATES = Collections.unmodifiableMap(fresh);
        Constants.LOG.info("Cached {} structure templates", CACHED_TEMPLATES.size());
    }
}
