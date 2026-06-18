package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.world.BushPlacer;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRasteriser;
import com.finndog.moogs_paths.world.StructurePlacer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * Places a single (deferred path, target chunk) pair into a live {@link ServerLevel}.
 *
 * Must be called from the server thread. The placer reuses the existing
 * {@link PathRasteriser}, {@link StructurePlacer}, and {@link BushPlacer} entry points,
 * which work on any {@code WorldGenLevel}, and {@code ServerLevel implements WorldGenLevel}.
 *
 * Block-update flags: we use {@code Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE}
 * (= 2 | 16 = 18) for the rasteriser and structure placer. This avoids the neighbour-
 * update cascade that flag 3 would trigger on a live chunk while still pushing the
 * change to clients and skipping connected-block reshape (matches worldgen output).
 *
 * Bush placement uses just {@code UPDATE_CLIENTS} (2) - that's what the worldgen path
 * already does for vegetation, since leaves don't need neighbour cascades.
 *
 * Feature decorations (FeatureScatterer) are intentionally NOT replayed in deferred
 * placement. ConfiguredFeature.place requires a WorldGenLevel in worldgen-context
 * (proto-chunk semantics). Replaying it against a live ServerLevel risks unpredictable
 * fluid/block side effects from features that look up other chunks during placement.
 * The decoration gap is filled by neighbouring chunks: each chunk that runs the feature
 * pass while the path is cached will paint its own decorations along the path slice
 * within that chunk.
 */
public final class LiveChunkPlacer {

    private static final int RASTER_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE; // 18
    private static final int BUSH_FLAGS = Block.UPDATE_CLIENTS; // matches worldgen behaviour

    // Mirrors PathChunkFeature constants - the worldgen pass uses these mixers to derive
    // sub-seeds for raster/structure/feature/bush passes. Deferred placement must use the
    // same mixers so block output is identical to worldgen.
    private static final long RASTER_CHUNK_X_MULT = 1234567L;
    private static final long RASTER_CHUNK_Z_MULT = 9876543L;
    private static final long STRUCTURE_MIXER = 0x9E3779B97F4A7C15L;
    private static final long BUSH_MIXER = 0x3BFDA1C6E09D2578L;

    private LiveChunkPlacer() {}

    public static void place(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath cachedPath, int chunkX, int chunkZ) {
        if(cachedPath.waypointCount() < 2) return;

        Optional<PathNetworkType> netOpt = MoogsPathsDatapackRegistries.getPathNetwork(level.registryAccess(), job.networkId());
        if(netOpt.isEmpty()) return;
        PathNetworkType network = netOpt.get();
        Optional<PathType> ptOpt = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
        if(ptOpt.isEmpty()) return;
        PathType pathType = ptOpt.get();

        int bboxPad = pathType.width().max();
        if(!intersectsWithPad(cachedPath, chunkX, chunkZ, bboxPad)) return;

        long pathSeed = job.pathSeed();
        var waypoints = cachedPath.waypoints();

        try {
            RandomSource rasterRandom = RandomSource.create(pathSeed ^ ((long) chunkX * RASTER_CHUNK_X_MULT) ^ ((long) chunkZ * RASTER_CHUNK_Z_MULT));
            PathRasteriser.rasteriseInChunk(level, waypoints, pathType, chunkX, chunkZ, rasterRandom, RASTER_FLAGS);

            if(!network.structureSets().isEmpty()) {
                RandomSource structureRandom = RandomSource.create(pathSeed ^ STRUCTURE_MIXER);
                LongOpenHashSet placedStructurePositions = new LongOpenHashSet();
                StructurePlacer.placeInChunk(level, waypoints, network.structureSets(), network.biomes(), chunkX, chunkZ, structureRandom, placedStructurePositions, RASTER_FLAGS);
            }

            if(!network.bushDecoratorSets().isEmpty()) {
                RandomSource bushRandom = RandomSource.create(pathSeed ^ BUSH_MIXER);
                BushPlacer.placeInChunk(level, waypoints, network.bushDecoratorSets(), network.biomes(), chunkX, chunkZ, bushRandom, BUSH_FLAGS);
            }
        } catch(Throwable t) {
            Constants.LOG.error("Deferred placement failed for seed {} chunk ({}, {}): {}", pathSeed, chunkX, chunkZ, t.toString(), t);
        }
    }

    private static boolean intersectsWithPad(PathDataManager.CachedPath path, int chunkX, int chunkZ, int pad) {
        int chunkMinX = (chunkX << 4) - pad;
        int chunkMaxX = (chunkX << 4) + 15 + pad;
        int chunkMinZ = (chunkZ << 4) - pad;
        int chunkMaxZ = (chunkZ << 4) + 15 + pad;
        return path.maxX() >= chunkMinX && path.minX() <= chunkMaxX
            && path.maxZ() >= chunkMinZ && path.minZ() <= chunkMaxZ;
    }
}
