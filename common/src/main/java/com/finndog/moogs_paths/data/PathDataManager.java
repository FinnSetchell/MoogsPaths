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

    private static final int WAYPOINT_CACHE_MAX_SIZE = 512;

    // keyed by pathSeed, computed once per path origin and shared across all chunks that touch it.
    // ConcurrentHashMap so different pathSeeds don't serialize - worldgen worker threads can
    // compute distinct paths in parallel. Size-bounded via a coarse trim on overflow (no strict LRU).
    private static final Map<Long, List<List<BlockPos>>> WAYPOINT_CACHE = new ConcurrentHashMap<>();

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
        List<List<BlockPos>> cached = WAYPOINT_CACHE.get(pathSeed);
        if(cached != null) return cached;
        List<List<BlockPos>> computed = WAYPOINT_CACHE.computeIfAbsent(pathSeed, k -> computer.get());
        if(WAYPOINT_CACHE.size() > WAYPOINT_CACHE_MAX_SIZE) trimCache();
        return computed;
    }

    private static void trimCache() {
        // Coarse bulk trim: keep half, drop half. Cheap and avoids needing LRU bookkeeping
        // under contention. Called only when cap is exceeded, so amortised cost is near zero.
        int target = WAYPOINT_CACHE_MAX_SIZE / 2;
        Iterator<Map.Entry<Long, List<List<BlockPos>>>> it = WAYPOINT_CACHE.entrySet().iterator();
        while(it.hasNext() && WAYPOINT_CACHE.size() > target) {
            it.next();
            it.remove();
        }
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
