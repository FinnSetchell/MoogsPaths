package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PathDataManager {

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

    // lazy cache: templates are fetched on first request and cleared on reload/server start
    private static final Map<ResourceLocation, Optional<StructureTemplate>> CACHED_TEMPLATES = new ConcurrentHashMap<>();

    private static volatile StructureTemplateManager templateManager;

    private PathDataManager() {}

    //////////////////////////////

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

    public static Map<ResourceLocation, Optional<StructureTemplate>> getCachedTemplatesSnapshot() {
        return CACHED_TEMPLATES;
    }

    public static void clearCaches() {
        WAYPOINT_CACHE.clear();
        CACHED_TEMPLATES.clear();
    }

    public static void onServerStart(StructureTemplateManager manager) {
        templateManager = manager;
        clearCaches();
    }
}
