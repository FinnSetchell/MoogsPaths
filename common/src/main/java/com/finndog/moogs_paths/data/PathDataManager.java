package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

public final class PathDataManager {

    private static final Gson GSON = new GsonBuilder().create();

    private static final Map<ResourceLocation, PathType> PATH_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, PathNetworkType> PATH_NETWORKS = new HashMap<>();
    private static final Map<ResourceLocation, StructureSet> STRUCTURE_SETS = new HashMap<>();
    private static final Map<ResourceLocation, FeatureDecoratorSet> DECORATOR_SETS = new HashMap<>();

    private PathDataManager() {}

    //////////////////////////////

    public static Map<ResourceLocation, SimpleJsonResourceReloadListener> createListeners() {
        Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners = new LinkedHashMap<>();

        listeners.put(new ResourceLocation(Constants.MOD_ID, "path_types_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/path_types") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    PATH_TYPES.clear();
                    map.forEach((id, json) ->
                        PathType.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load path type {}: {}", id, e))
                            .ifPresent(pt -> PATH_TYPES.put(id, pt))
                    );
                    Constants.LOG.info("Loaded {} path types", PATH_TYPES.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "path_networks_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/path_networks") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    PATH_NETWORKS.clear();
                    map.forEach((id, json) ->
                        PathNetworkType.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load path network {}: {}", id, e))
                            .ifPresent(pn -> PATH_NETWORKS.put(id, pn))
                    );
                    Constants.LOG.info("Loaded {} path networks", PATH_NETWORKS.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "structure_sets_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/structure_sets") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    STRUCTURE_SETS.clear();
                    map.forEach((id, json) ->
                        StructureSet.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load structure set {}: {}", id, e))
                            .ifPresent(ss -> STRUCTURE_SETS.put(id, ss))
                    );
                    Constants.LOG.info("Loaded {} structure sets", STRUCTURE_SETS.size());
                }
            }
        );

        listeners.put(new ResourceLocation(Constants.MOD_ID, "decorator_sets_listener"),
            new SimpleJsonResourceReloadListener(GSON, "moogspaths/feature_decorator_sets") {
                @Override
                protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
                    DECORATOR_SETS.clear();
                    map.forEach((id, json) ->
                        FeatureDecoratorSet.CODEC.parse(JsonOps.INSTANCE, json)
                            .resultOrPartial(e -> Constants.LOG.error("Failed to load decorator set {}: {}", id, e))
                            .ifPresent(ds -> DECORATOR_SETS.put(id, ds))
                    );
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
        return Collections.unmodifiableCollection(PATH_NETWORKS.values());
    }
}
