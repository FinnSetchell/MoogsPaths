package com.finndog.moogs_paths.data;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.world.BushPlacer;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRasteriser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class PathDataManager {

    // sized to cover a 24-chunk view distance worth of accepted origins so adjacent chunks
    // don't evict each other's paths and re-trigger A*. ~4096 entries at ~30KB each worst
    // case is ~120MB - paths typically much smaller, real footprint is single-digit MB.
    private static final int WAYPOINT_CACHE_MAX_SIZE = 4096;
    private static final int REJECTED_CACHE_MAX_SIZE = 2_097_152;

    // bbox lets per-chunk feature placement early-out cheaply for chunks outside the path.
    // waypoints stored as parallel long[]+int[] primitives to reduce per-entry object overhead;
    // materialized to List<BlockPos> on demand via waypoints().
    public record CachedPath(long[] xzPacked, int[] ys, int minX, int maxX, int minZ, int maxZ) {
        public boolean intersectsChunk(int chunkX, int chunkZ) {
            int chunkMinX = chunkX << 4;
            int chunkMaxX = chunkMinX + 15;
            int chunkMinZ = chunkZ << 4;
            int chunkMaxZ = chunkMinZ + 15;
            return maxX >= chunkMinX && minX <= chunkMaxX && maxZ >= chunkMinZ && minZ <= chunkMaxZ;
        }

        public int waypointCount() { return xzPacked.length; }

        public List<BlockPos> waypoints() {
            List<BlockPos> result = new ArrayList<>(xzPacked.length);
            for(int i = 0; i < xzPacked.length; i++) {
                result.add(new BlockPos((int)(xzPacked[i] >> 32), ys[i], (int)(xzPacked[i])));
            }
            return result;
        }
    }

    // ConcurrentHashMap so distinct pathSeeds compute in parallel on different worker threads
    private static final Map<Long, CachedPath> WAYPOINT_CACHE = new ConcurrentHashMap<>();

    // Negative cache for pathSeeds whose origin failed the biome filter. Separate from
    // WAYPOINT_CACHE so a flood of rejects can't evict real computed paths. ConcurrentHashMap
    // keyset because every worldgen-thread call to isRejected hits this and a synchronized
    // LongOpenHashSet serialised all those reads.
    private static final Set<Long> REJECTED_CACHE = ConcurrentHashMap.newKeySet();

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
        return REJECTED_CACHE.contains(pathSeed);
    }

    public static void markRejected(long pathSeed) {
        REJECTED_CACHE.add(pathSeed);
        if(REJECTED_CACHE.size() > REJECTED_CACHE_MAX_SIZE) trimRejectedCache();
    }

    private static CachedPath buildCachedPath(List<BlockPos> waypoints) {
        int n = waypoints.size();
        long[] xzPacked = new long[n];
        int[] ys = new int[n];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for(int i = 0; i < n; i++) {
            BlockPos p = waypoints.get(i);
            int x = p.getX(), z = p.getZ();
            xzPacked[i] = ((long) x << 32) | (z & 0xFFFFFFFFL);
            ys[i] = p.getY();
            if(x < minX) minX = x;
            if(x > maxX) maxX = x;
            if(z < minZ) minZ = z;
            if(z > maxZ) maxZ = z;
        }
        if(n == 0) { minX = maxX = minZ = maxZ = 0; }
        return new CachedPath(xzPacked, ys, minX, maxX, minZ, maxZ);
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
        int target = REJECTED_CACHE_MAX_SIZE / 2;
        Iterator<Long> it = REJECTED_CACHE.iterator();
        while(it.hasNext() && REJECTED_CACHE.size() > target) {
            it.next();
            it.remove();
        }
    }

    public static Map<ResourceLocation, Optional<StructureTemplate>> getCachedTemplatesSnapshot() {
        return CACHED_TEMPLATES;
    }

    public static void clearCaches() {
        cacheVersion++;
        WAYPOINT_CACHE.clear();
        REJECTED_CACHE.clear();
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
