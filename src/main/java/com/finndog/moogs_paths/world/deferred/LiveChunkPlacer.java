package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.world.BushPlacer;
import com.finndog.moogs_paths.world.FeatureScatterer;
import com.finndog.moogs_paths.world.PathRasteriser;
import com.finndog.moogs_paths.world.PlacementGuard;
import com.finndog.moogs_paths.world.StructurePlacer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * Places a single (deferred path, target chunk) pair into a live {@link ServerLevel}: the path
 * surface, roadside structures, bushes and decorations, then the dirt-path settle pass.
 *
 * Must be called from the server thread, once the chunk and its eight neighbours are loaded
 * (structures, fill and decorations spill up to a chunk across). The placers work on any
 * {@code WorldGenLevel}, and {@code ServerLevel implements WorldGenLevel}.
 *
 * Block-update flags: {@code Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE} (= 18) for the
 * rasteriser and structure placer avoids the neighbour-update cascade flag 3 would trigger on a
 * live chunk while still pushing the change to clients. Bushes use just {@code UPDATE_CLIENTS}.
 * Decorations are vanilla features placed as bonemeal places them, with their own flags.
 *
 * Every chunk the path reaches is placed here exactly once, so decorations are too: placing them
 * during world generation missed every chunk generated before its path was computed.
 */
public final class LiveChunkPlacer {

    private static final int RASTER_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE; // 18
    private static final int BUSH_FLAGS = Block.UPDATE_CLIENTS;

    // Sub-seed mixers for the raster/structure/feature/bush passes. Changing them changes every path.
    private static final long RASTER_CHUNK_X_MULT = 1234567L;
    private static final long RASTER_CHUNK_Z_MULT = 9876543L;
    private static final long STRUCTURE_MIXER = 0x9E3779B97F4A7C15L;
    private static final long FEATURE_MIXER = 0x6C62272E07BB0142L;
    private static final long BUSH_MIXER = 0x3BFDA1C6E09D2578L;

    private LiveChunkPlacer() {}

    public static void place(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath cachedPath, int chunkX, int chunkZ) {
        if(cachedPath.waypointCount() < 2) return;

        Optional<PathNetworkType> netOpt = MoogsPathsDatapackRegistries.getPathNetwork(level.registryAccess(), job.networkId());
        if(netOpt.isEmpty()) return;
        PathNetworkType network = netOpt.get();
        Optional<PathType> ptOpt = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
        if(ptOpt.isEmpty()) return;
        // A path leading out of a structure meets it at full width instead of fading in.
        PathType pathType = job.isAnchored() ? ptOpt.get().withoutStartFade() : ptOpt.get();

        long pathSeed = job.pathSeed();
        var waypoints = cachedPath.waypoints();

        try {
            // Region paths keep out of structures' pieces; a structure's own paths are meant to meet it.
            PlacementGuard guard = PlacementGuard.forChunk(level, chunkX, chunkZ, !job.isAnchored());

            RandomSource rasterRandom = RandomSource.create(pathSeed ^ ((long) chunkX * RASTER_CHUNK_X_MULT) ^ ((long) chunkZ * RASTER_CHUNK_Z_MULT));
            LongOpenHashSet placedSurface = PathRasteriser.rasteriseInChunk(level, waypoints, pathType, chunkX, chunkZ, rasterRandom, guard, RASTER_FLAGS);

            if(!network.structureSets().isEmpty()) {
                RandomSource structureRandom = RandomSource.create(pathSeed ^ STRUCTURE_MIXER);
                LongOpenHashSet placedStructurePositions = new LongOpenHashSet();
                StructurePlacer.placeInChunk(level, waypoints, network.structureSets(), network.biomes(), chunkX, chunkZ, structureRandom, placedStructurePositions, guard, placedSurface, RASTER_FLAGS);
            }

            if(!network.bushDecoratorSets().isEmpty()) {
                LongOpenHashSet pathColumns = new LongOpenHashSet();
                LongIterator it = placedSurface.iterator();
                while(it.hasNext()) {
                    long packed = it.nextLong();
                    pathColumns.add(((long) BlockPos.getX(packed) << 32) | (BlockPos.getZ(packed) & 0xFFFFFFFFL));
                }
                RandomSource bushRandom = RandomSource.create(pathSeed ^ BUSH_MIXER);
                BushPlacer.placeInChunk(level, waypoints, network.bushDecoratorSets(), network.biomes(), chunkX, chunkZ, bushRandom, guard, pathColumns, BUSH_FLAGS);
            }

            if(!network.featureDecoratorSets().isEmpty()) {
                RandomSource featureRandom = RandomSource.create(pathSeed ^ FEATURE_MIXER);
                FeatureScatterer.scatterInChunk(level, level.getChunkSource().getGenerator(), waypoints, network.featureDecoratorSets(), network.biomes(), chunkX, chunkZ, featureRandom, guard);
            }

            // Last, so posts and bushes standing on the path count as blocks on top of it. The
            // structure placer has added the blocks its structures stand on.
            PathRasteriser.settleDirtPaths(level, placedSurface, RASTER_FLAGS);
        } catch(Throwable t) {
            Constants.LOG.error("Deferred placement failed for seed {} chunk ({}, {}): {}", pathSeed, chunkX, chunkZ, t.toString(), t);
        }
    }
}
