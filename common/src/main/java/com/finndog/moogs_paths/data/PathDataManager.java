package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.world.BushPlacer;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRasteriser;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class PathDataManager {

    private static final int WAYPOINT_CACHE_MAX_SIZE = 512;
    // Sized for long sessions without trim-thrashing. ~2M entries at ~8 bytes = ~16MB worst case.
    private static final int REJECTED_CACHE_MAX_SIZE = 2_097_152;

    // bbox lets per-chunk feature placement early-out cheaply for chunks outside the path
    public record CachedPath(List<BlockPos> waypoints, int minX, int maxX, int minZ, int maxZ) {
        public boolean intersectsChunk(int chunkX, int chunkZ) {
            int chunkMinX = chunkX << 4;
            int chunkMaxX = chunkMinX + 15;
            int chunkMinZ = chunkZ << 4;
            int chunkMaxZ = chunkMinZ + 15;
            return maxX >= chunkMinX && minX <= chunkMaxX && maxZ >= chunkMinZ && minZ <= chunkMaxZ;
        }
    }

    // ConcurrentHashMap so distinct pathSeeds compute in parallel on different worker threads
    private static final Map<Long, CachedPath> WAYPOINT_CACHE = new ConcurrentHashMap<>();

    // Negative cache for pathSeeds whose origin failed the biome filter. Separate from
    // WAYPOINT_CACHE so a flood of rejects can't evict real computed paths.
    private static final LongOpenHashSet REJECTED_CACHE = new LongOpenHashSet();

    private static final Map<ResourceLocation, Optional<StructureTemplate>> CACHED_TEMPLATES = new ConcurrentHashMap<>();

    private static volatile StructureTemplateManager templateManager;
    private static volatile int cacheVersion = 0;

    private PathDataManager() {}

    public static int getCacheVersion() { return cacheVersion; }

    //////////////////////////////
    private static final AtomicLong[] BIOME_CALL_COUNTS = new AtomicLong[BiomeCallSite.values().length];
    static {
        for(int i = 0; i < BIOME_CALL_COUNTS.length; i++) BIOME_CALL_COUNTS[i] = new AtomicLong();
    }

    public static void recordBiomeCall(BiomeCallSite site) {
        BIOME_CALL_COUNTS[site.ordinal()].incrementAndGet();
    }

    public static long readBiomeCallCount(BiomeCallSite site) {
        return BIOME_CALL_COUNTS[site.ordinal()].get();
    }

    //////////////////////////////
    private static final AtomicLong[] PATH_COUNTERS = new AtomicLong[PathCounter.values().length];
    static {
        for(int i = 0; i < PATH_COUNTERS.length; i++) PATH_COUNTERS[i] = new AtomicLong();
    }

    public static void addPathCounter(PathCounter counter, long n) {
        PATH_COUNTERS[counter.ordinal()].addAndGet(n);
    }

    public static long readPathCounter(PathCounter counter) {
        return PATH_COUNTERS[counter.ordinal()].get();
    }

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

    public static CachedPath getOrComputeWaypoints(long pathSeed, Supplier<List<BlockPos>> computer) {
        CachedPath cached = WAYPOINT_CACHE.get(pathSeed);
        if(cached != null) return cached;
        CachedPath computed = WAYPOINT_CACHE.computeIfAbsent(pathSeed, k -> buildCachedPath(computer.get()));
        if(WAYPOINT_CACHE.size() > WAYPOINT_CACHE_MAX_SIZE) trimCache();
        return computed;
    }

    // returns a cached path without triggering computation - a hit implies biome filter already passed
    public static CachedPath peekCachedPath(long pathSeed) {
        return WAYPOINT_CACHE.get(pathSeed);
    }

    public static boolean isRejected(long pathSeed) {
        synchronized(REJECTED_CACHE) { return REJECTED_CACHE.contains(pathSeed); }
    }

    public static void markRejected(long pathSeed) {
        synchronized(REJECTED_CACHE) {
            REJECTED_CACHE.add(pathSeed);
            if(REJECTED_CACHE.size() > REJECTED_CACHE_MAX_SIZE) trimRejectedCache();
        }
    }

    private static CachedPath buildCachedPath(List<BlockPos> waypoints) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for(BlockPos p : waypoints) {
            int x = p.getX();
            int z = p.getZ();
            if(x < minX) minX = x;
            if(x > maxX) maxX = x;
            if(z < minZ) minZ = z;
            if(z > maxZ) maxZ = z;
        }
        if(minX == Integer.MAX_VALUE) {
            minX = maxX = minZ = maxZ = 0;
        }
        return new CachedPath(waypoints, minX, maxX, minZ, maxZ);
    }

    private static void trimCache() {
        // bulk drop half - no LRU bookkeeping under contention, only fires above the cap
        int target = WAYPOINT_CACHE_MAX_SIZE / 2;
        Iterator<Map.Entry<Long, CachedPath>> it = WAYPOINT_CACHE.entrySet().iterator();
        while(it.hasNext() && WAYPOINT_CACHE.size() > target) {
            it.next();
            it.remove();
        }
    }

    private static void trimRejectedCache() {
        // must be called with REJECTED_CACHE lock held
        int target = REJECTED_CACHE_MAX_SIZE / 2;
        LongIterator it = REJECTED_CACHE.iterator();
        while(it.hasNext() && REJECTED_CACHE.size() > target) {
            it.nextLong();
            it.remove();
        }
    }

    public static Map<ResourceLocation, Optional<StructureTemplate>> getCachedTemplatesSnapshot() {
        return CACHED_TEMPLATES;
    }

    public static void clearCaches() {
        cacheVersion++;
        WAYPOINT_CACHE.clear();
        synchronized(REJECTED_CACHE) { REJECTED_CACHE.clear(); }
        CACHED_TEMPLATES.clear();
        PathRasteriser.clearBlockCache();
        BushPlacer.clearBlockCache();
        PathChunkFeature.clearOriginBiomeCache();
    }

    public static void onServerStart(StructureTemplateManager manager) {
        templateManager = manager;
        clearCaches();
    }
}
