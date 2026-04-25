package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathCounter;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.debug.PathDebugTimer;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PathChunkFeature extends Feature<NoneFeatureConfiguration> {

    public static final TagKey<Biome> HAS_NO_PATHS = TagKey.create(Registries.BIOME, new ResourceLocation(Constants.MOD_ID, "has_no_paths"));

    // mixer constants - distinct random streams derived from pathSeed by xor with these
    public static final long PATH_SEED_MIXER = 0xABCDEF1234567890L;
    public static final long WALK_MIXER = 0x1L;
    public static final long ORIGIN_X_MULT = 341873128712L;
    public static final long ORIGIN_Z_MULT = 132897987541L;
    public static final long ORIGIN_REGION_SIZE_MULT = 27182818284L;
    public static final int BIOME_FILTER_Y = 64;
    private static final long RASTER_CHUNK_X_MULT = 1234567L;
    private static final long RASTER_CHUNK_Z_MULT = 9876543L;
    private static final long STRUCTURE_MIXER = 0x9E3779B97F4A7C15L;
    private static final long FEATURE_MIXER = 0x6C62272E07BB0142L;
    private static final long BUSH_MIXER = 0x3BFDA1C6E09D2578L;

    // Per-dimension cache so each origin's biome is sampled once instead of by every neighbouring
    // chunk that visits it. Without this the same origin gets re-sampled maxRadius-many times.
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, Holder<Biome>>> ORIGIN_BIOME_CACHE = new ConcurrentHashMap<>();
    private static final int ORIGIN_BIOME_CACHE_SOFT_CAP = 131072;
    public static final int ORIGIN_BIOME_CELL_SHIFT = 0;

    public PathChunkFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        PathDebugTimer.begin();
        try {
        WorldGenLevel level = ctx.level();
        ChunkGenerator generator = ctx.chunkGenerator();
        long worldSeed = level.getSeed();
        int chunkX = ctx.origin().getX() >> 4;
        int chunkZ = ctx.origin().getZ() >> 4;

        Map<Integer, List<PathNetworkType>> byRegionSize = MoogsPathsDatapackRegistries.networksByRegionSize(level.registryAccess());
        if(byRegionSize.isEmpty()) return false;

        RandomState randomState = level.getLevel().getChunkSource().randomState();

        // Shared across every network/origin in this chunk so overlapping networks can't both
        // place a structure on near-identical (x,z) spots.
        Set<Long> placedStructurePositions = new HashSet<>();

        boolean placed = false;

        for(Map.Entry<Integer, List<PathNetworkType>> entry : byRegionSize.entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();
            int maxRadius = MoogsPathsDatapackRegistries.networksMaxRadiusForRegionSize(level.registryAccess(), regionSize);
            PathDebugTimer.stamp(PathDebugTimer.Stage.ORIGIN_ENUM);
            List<int[]> origins = PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, maxRadius, regionSize).toList();

            for(int[] origin : origins) {
                int originChunkX = origin[0];
                int originChunkZ = origin[1];
                int originBlockX = originChunkX * 16 + 8;
                int originBlockZ = originChunkZ * 16 + 8;

                long pathSeed = worldSeed
                    ^ ((long) originChunkX * ORIGIN_X_MULT)
                    ^ ((long) originChunkZ * ORIGIN_Z_MULT)
                    ^ ((long) regionSize * ORIGIN_REGION_SIZE_MULT)
                    ^ PATH_SEED_MIXER;

                // negative-result fast-path: a prior chunk already failed this origin's biome filter
                if(PathDataManager.isRejected(pathSeed)) {
                    PathDataManager.addPathCounter(PathCounter.ORIGIN_REJECTED_BY_CACHE, 1);
                    continue;
                }

                RandomSource pickRandom = RandomSource.create(pathSeed);
                PathNetworkType network = pickWeighted(networks, pickRandom);

                // positive-result fast-path: cached path implies the biome filter already passed
                PathDataManager.CachedPath fastCached = PathDataManager.peekCachedPath(pathSeed);

                if(fastCached == null) {
                    PathDebugTimer.stamp(PathDebugTimer.Stage.ORIGIN_BIOME);
                    Holder<Biome> originBiome = getOriginBiome(level, originChunkX, originChunkZ);
                    if(originBiome.is(HAS_NO_PATHS) || !network.biomes().contains(originBiome)) {
                        PathDataManager.markRejected(pathSeed);
                        PathDataManager.addPathCounter(PathCounter.ORIGIN_REJECTED_BY_BIOME, 1);
                        Holder<Biome> exactBiome = level.getBiome(new BlockPos(originBlockX, BIOME_FILTER_Y, originBlockZ));
                        if(!exactBiome.is(HAS_NO_PATHS) && network.biomes().contains(exactBiome)) {
                            PathDataManager.addPathCounter(PathCounter.ORIGIN_COARSE_FALSE_NEG, 1);
                        }
                        continue;
                    }
                    PathDataManager.addPathCounter(PathCounter.ORIGIN_ACCEPTED, 1);
                    Holder<Biome> exactBiome = level.getBiome(new BlockPos(originBlockX, BIOME_FILTER_Y, originBlockZ));
                    if(exactBiome.is(HAS_NO_PATHS) || !network.biomes().contains(exactBiome)) {
                        PathDataManager.addPathCounter(PathCounter.ORIGIN_COARSE_FALSE_POS, 1);
                    }
                }

                Optional<PathType> pathTypeOpt = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
                if(pathTypeOpt.isEmpty()) {
                    Constants.LOG.error("Missing path type: {}", network.pathType());
                    continue;
                }
                PathDebugTimer.stamp(PathDebugTimer.Stage.PATHFIND);
                PathType pathType = pathTypeOpt.get();

                PathDataManager.CachedPath cachedPath;
                if(fastCached != null) {
                    cachedPath = fastCached;
                } else {
                    int originSurfaceY = generator.getBaseHeight(originBlockX, originBlockZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                    BlockPos originPos = new BlockPos(originBlockX, originSurfaceY, originBlockZ);
                    PathNetworkType finalNetwork = network;
                    cachedPath = PathDataManager.getOrComputeWaypoints(pathSeed, () -> {
                        PathDataManager.addPathCounter(PathCounter.PATH_ACTUALLY_COMPUTED, 1);
                        RandomSource walkRandom = RandomSource.create(pathSeed ^ WALK_MIXER);
                        return PathFinder.findPath(originPos, pathType, walkRandom,
                            (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState),
                            (gx, gz) -> {
                                PathDataManager.recordBiomeCall(com.finndog.moogs_paths.data.BiomeCallSite.PATHFINDER_GOAL_CHECK);
                                return finalNetwork.biomes().contains(level.getBiome(new BlockPos(gx, BIOME_FILTER_Y, gz)));
                            });
                    });
                }

                List<BlockPos> waypoints = cachedPath.waypoints();
                if(waypoints.size() < 2) {
                    if(fastCached == null) {
                        PathDataManager.markRejected(pathSeed);
                        PathDataManager.addPathCounter(PathCounter.PATH_DROPPED_TOO_SHORT, 1);
                    }
                    continue;
                }

                int bboxPad = pathType.width().max();
                if(!intersectsWithPad(cachedPath, chunkX, chunkZ, bboxPad)) continue;

                RandomSource rasterRandom = RandomSource.create(pathSeed ^ ((long) chunkX * RASTER_CHUNK_X_MULT) ^ ((long) chunkZ * RASTER_CHUNK_Z_MULT));
                PathDebugTimer.stamp(PathDebugTimer.Stage.RASTER);
                PathRasteriser.rasteriseInChunk(level, waypoints, pathType, chunkX, chunkZ, rasterRandom);

                if(!network.structureSets().isEmpty()) {
                    RandomSource structureRandom = RandomSource.create(pathSeed ^ STRUCTURE_MIXER);
                    PathDebugTimer.stamp(PathDebugTimer.Stage.STRUCTURES);
                    StructurePlacer.placeInChunk(level, waypoints, network.structureSets(), network.biomes(), chunkX, chunkZ, structureRandom, placedStructurePositions);
                }

                if(!network.featureDecoratorSets().isEmpty()) {
                    RandomSource featureRandom = RandomSource.create(pathSeed ^ FEATURE_MIXER);
                    PathDebugTimer.stamp(PathDebugTimer.Stage.FEATURES);
                    FeatureScatterer.scatterInChunk(level, generator, waypoints, network.featureDecoratorSets(), network.biomes(), chunkX, chunkZ, featureRandom);
                }

                if(!network.bushDecoratorSets().isEmpty()) {
                    RandomSource bushRandom = RandomSource.create(pathSeed ^ BUSH_MIXER);
                    PathDebugTimer.stamp(PathDebugTimer.Stage.BUSHES);
                    BushPlacer.placeInChunk(level, waypoints, network.bushDecoratorSets(), network.biomes(), chunkX, chunkZ, bushRandom);
                }

                placed = true;
            }
        }

        return placed;
        } finally {
            PathDebugTimer.end();
        }
    }

    private static Holder<Biome> getOriginBiome(WorldGenLevel level, int originChunkX, int originChunkZ) {
        ResourceKey<Level> dimKey = level.getLevel().dimension();
        ConcurrentHashMap<Long, Holder<Biome>> cache = ORIGIN_BIOME_CACHE.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());
        int cellX = originChunkX >> ORIGIN_BIOME_CELL_SHIFT;
        int cellZ = originChunkZ >> ORIGIN_BIOME_CELL_SHIFT;
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        Holder<Biome> cached = cache.get(key);
        if(cached != null) return cached;
        // sample at a fixed location per cell so concurrent worker threads agree on the result
        int sampleChunkX = cellX << ORIGIN_BIOME_CELL_SHIFT;
        int sampleChunkZ = cellZ << ORIGIN_BIOME_CELL_SHIFT;
        int sampleBlockX = (sampleChunkX << 4) + 8;
        int sampleBlockZ = (sampleChunkZ << 4) + 8;
        PathDataManager.recordBiomeCall(com.finndog.moogs_paths.data.BiomeCallSite.ORIGIN_FILTER_MISS);
        Holder<Biome> fresh = level.getBiome(new BlockPos(sampleBlockX, BIOME_FILTER_Y, sampleBlockZ));
        cache.put(key, fresh);
        if(cache.size() > ORIGIN_BIOME_CACHE_SOFT_CAP) trimOriginBiomeCache(cache);
        return fresh;
    }

    private static void trimOriginBiomeCache(ConcurrentHashMap<Long, Holder<Biome>> cache) {
        // bulk drop half - no LRU bookkeeping under contention, only fires above the soft cap
        int target = ORIGIN_BIOME_CACHE_SOFT_CAP / 2;
        Iterator<Map.Entry<Long, Holder<Biome>>> it = cache.entrySet().iterator();
        while(it.hasNext() && cache.size() > target) {
            it.next();
            it.remove();
        }
    }

    public static void clearOriginBiomeCache() {
        ORIGIN_BIOME_CACHE.clear();
    }

    private static boolean intersectsWithPad(PathDataManager.CachedPath path, int chunkX, int chunkZ, int pad) {
        int chunkMinX = (chunkX << 4) - pad;
        int chunkMaxX = (chunkX << 4) + 15 + pad;
        int chunkMinZ = (chunkZ << 4) - pad;
        int chunkMaxZ = (chunkZ << 4) + 15 + pad;
        return path.maxX() >= chunkMinX && path.minX() <= chunkMaxX
            && path.maxZ() >= chunkMinZ && path.minZ() <= chunkMaxZ;
    }

    private static PathNetworkType pickWeighted(List<PathNetworkType> eligible, RandomSource random) {
        int total = 0;
        for(PathNetworkType n : eligible) total += n.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(PathNetworkType n : eligible) {
            cumulative += n.weight();
            if(roll < cumulative) return n;
        }
        return eligible.get(0);
    }
}
