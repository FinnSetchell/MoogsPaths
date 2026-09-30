package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns the tick-driven side of deferred path placement.
 *
 * Responsibilities, in order of when they fire:
 *  1. About to start: start the worker pool. Level load: load the level's {@link DeferredPathState}.
 *     Started: resubmit saved jobs whose path was never computed; the rest wait for a chunk in
 *     their bounds to load.
 *  2. Job complete (called by {@link PathfindWorker}, any thread): hand the path to the server
 *     thread, which works out the chunks it writes to and queues the loaded ones.
 *  3. Chunk load: queue placement of every pending path that writes to this chunk or to one of
 *     its neighbours whose neighbourhood this load completes.
 *  4. Server tick: land finished jobs, then drain the per-level placement queue up to a budget.
 *
 * A chunk is only placed once it and its eight neighbours are loaded: roadside structures,
 * decorations and fill spill into neighbouring chunks, and writing to one that isn't loaded
 * would load it synchronously and read its height as the world floor.
 *
 * A job completes once every chunk its path writes to has been placed; until then it stays
 * pending, since the rest of the path is laid as players reach it.
 */
public final class PlacementTickPump {

    // Per-tick budget: at most N (path, chunk) placements drained per server tick.
    // Each placement does ~one rasterise pass over the path's slice in that chunk,
    // which costs a few thousand setBlock calls in the worst case. 4 placements/tick
    // is ~16k setBlocks/tick, well within a 50ms tick budget on a normal CPU.
    private static final int PLACEMENTS_PER_TICK = 4;

    // After this many ticks with no placement work, we sweep the priority queue to
    // re-rank by player proximity. Cheap because the queue is normally empty after
    // the initial spawn-load burst.
    private static final int PRIORITY_RESORT_INTERVAL_TICKS = 20;

    // A chunk players have spent five minutes in is left alone: a path laid there late would cut
    // through whatever they built.
    private static final long INHABITED_LIMIT_TICKS = 20 * 60 * 5;

    private static final Map<ServerLevel, Deque<PendingPlacement>> PENDING = new ConcurrentHashMap<>();
    private static final Queue<Landed> LANDED = new ConcurrentLinkedQueue<>();
    // Chunks each landed path writes to, by path seed. Server thread only.
    private static final Map<Long, LongOpenHashSet> TOUCHED = new HashMap<>();
    private static int tickCount = 0;
    private static volatile boolean started = false;

    private PlacementTickPump() {}

    private record PendingPlacement(DeferredPathJob job, PathDataManager.CachedPath path, int chunkX, int chunkZ) {}

    private record Landed(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath path) {}

    //////////////////////////////
    // Lifecycle

    /** Before any level exists, so jobs queued while the spawn area generates are kept. */
    public static void onServerAboutToStart(MinecraftServer server) {
        clear();
        started = true;
        PathfindWorker.start(PlacementTickPump::onJobComplete);
    }

    public static void onLevelLoad(ServerLevel level) {
        DeferredPathState.onLevelLoad(level);
    }

    public static void onServerStarted(MinecraftServer server) {
        for(ServerLevel level : server.getAllLevels()) {
            DeferredPathState state = DeferredPathState.get(level);
            for(DeferredPathJob job : state.pendingJobs()) {
                if(state.bounds(job.pathSeed()) == null) PathfindWorker.submit(level, job);
            }
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        started = false;
        PathfindWorker.stop();
        clear();
    }

    private static void clear() {
        PENDING.clear();
        LANDED.clear();
        TOUCHED.clear();
        DeferredPathState.clearLoaded();
    }

    public static int landedPathCount() { return TOUCHED.size(); }

    public static int queuedPlacementCount(ServerLevel level) {
        Deque<PendingPlacement> queue = PENDING.get(level);
        return queue == null ? 0 : queue.size();
    }

    //////////////////////////////
    // Queueing

    /**
     * Records a job for a path that was needed but isn't cached, and submits it to the worker
     * pool. Called by worldgen and by locate searches; safe from any thread.
     */
    public static void enqueueFromWorldgen(ServerLevel level, DeferredPathJob job) {
        if(!started) return;
        DeferredPathState state = DeferredPathState.get(level);
        if(state.isCompleted(job.pathSeed())) return;
        state.addPending(job);
        PathfindWorker.submit(level, job);
    }

    /** Called by the worker when an A* job finishes, on its own thread. */
    private static void onJobComplete(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath path) {
        LANDED.add(new Landed(level, job, path));
    }

    // Server thread: work out the chunks a finished path writes to and queue the ones already loaded.
    private static void land(Landed landed) {
        ServerLevel level = landed.level();
        if(level.getServer().getLevel(level.dimension()) != level) return;
        DeferredPathState state = DeferredPathState.get(level);
        long seed = landed.job().pathSeed();
        if(!state.isPending(seed)) return;

        PathDataManager.CachedPath path = landed.path();
        if(path == null) {
            state.retire(seed);
            TOUCHED.remove(seed);
            return;
        }
        if(path.waypointCount() < 2) {
            state.complete(seed);
            TOUCHED.remove(seed);
            return;
        }
        LongOpenHashSet touched = touchedChunks(level, landed.job(), path);
        if(touched == null) {
            state.retire(seed);
            TOUCHED.remove(seed);
            return;
        }
        TOUCHED.put(seed, touched);
        state.setBounds(seed, bounds(touched));

        Deque<PendingPlacement> queue = queue(level);
        LongIterator it = touched.iterator();
        while(it.hasNext()) {
            long packed = it.nextLong();
            int cx = unpackX(packed), cz = unpackZ(packed);
            if(state.wasPlaced(seed, cx, cz)) continue;
            if(neighbourhoodLoaded(level, cx, cz, Long.MIN_VALUE)) queue.add(new PendingPlacement(landed.job(), path, cx, cz));
        }
        if(allPlaced(state, seed, touched)) {
            state.complete(seed);
            TOUCHED.remove(seed);
        }
    }

    /** Called on the server thread when a chunk loads in a given level. */
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        if(!started) return;
        DeferredPathState state = DeferredPathState.get(level);
        if(!state.hasPending()) return;
        ChunkPos cp = chunk.getPos();
        //? if <26.1.2 {
        int loadedX = cp.x, loadedZ = cp.z;
        //?} else {
        /*int loadedX = cp.x(), loadedZ = cp.z();
        *///?}
        // The loading chunk may not count as loaded yet during its own load event.
        long loading = pack(loadedX, loadedZ);
        for(int dx = -1; dx <= 1; dx++) {
            for(int dz = -1; dz <= 1; dz++) {
                int cx = loadedX + dx, cz = loadedZ + dz;
                if((dx != 0 || dz != 0) && !level.getChunkSource().hasChunk(cx, cz)) continue;
                if(!neighbourhoodLoaded(level, cx, cz, loading)) continue;
                queueChunk(level, state, cx, cz);
            }
        }
    }

    private static void queueChunk(ServerLevel level, DeferredPathState state, int cx, int cz) {
        Deque<PendingPlacement> queue = null;
        long chunkKey = pack(cx, cz);
        for(DeferredPathJob job : state.pendingJobs()) {
            long seed = job.pathSeed();
            int[] b = state.bounds(seed);
            if(b != null && (cx < b[0] || cx > b[1] || cz < b[2] || cz > b[3])) continue;
            if(state.wasPlaced(seed, cx, cz)) continue;
            LongOpenHashSet touched = TOUCHED.get(seed);
            PathDataManager.CachedPath cached = PathDataManager.peekCachedPath(seed);
            if(touched == null || cached == null) {
                // Computed in an earlier session, or dropped from the cache: recompute it now a chunk
                // it reaches has loaded. It lands and queues every loaded chunk it writes to. A job with
                // no bounds yet is still running, or was resubmitted at start.
                if(b != null) PathfindWorker.submit(level, job);
                continue;
            }
            if(!touched.contains(chunkKey)) continue;
            if(queue == null) queue = queue(level);
            queue.add(new PendingPlacement(job, cached, cx, cz));
        }
    }

    private static Deque<PendingPlacement> queue(ServerLevel level) {
        return PENDING.computeIfAbsent(level, k -> new ConcurrentLinkedDeque<>());
    }

    //////////////////////////////
    // Placing

    /** Called on the server thread at the end of every tick. */
    public static void onServerTickEnd(MinecraftServer server) {
        if(!started) return;
        tickCount++;
        Landed landed;
        while((landed = LANDED.poll()) != null) land(landed);

        for(ServerLevel level : server.getAllLevels()) {
            Deque<PendingPlacement> queue = PENDING.get(level);
            if(queue == null || queue.isEmpty()) continue;

            DeferredPathState state = DeferredPathState.get(level);

            // Optional re-rank by player proximity - moves jobs near players to the head.
            if(tickCount % PRIORITY_RESORT_INTERVAL_TICKS == 0 && queue.size() > PLACEMENTS_PER_TICK) {
                priorityRerank(level, queue);
            }

            int budget = PLACEMENTS_PER_TICK;
            while(budget > 0) {
                PendingPlacement pp = queue.pollFirst();
                if(pp == null) break;
                long seed = pp.job.pathSeed();
                if(!state.isPending(seed) || state.wasPlaced(seed, pp.chunkX, pp.chunkZ)) continue;
                // A neighbour unloaded meanwhile: dropped, and queued again when the neighbourhood is back.
                if(!neighbourhoodLoaded(level, pp.chunkX, pp.chunkZ, Long.MIN_VALUE)) continue;
                budget--;
                LevelChunk chunk = level.getChunk(pp.chunkX, pp.chunkZ);
                if(chunk.getInhabitedTime() < INHABITED_LIMIT_TICKS) {
                    LiveChunkPlacer.place(level, pp.job, pp.path, pp.chunkX, pp.chunkZ);
                }
                state.markPlaced(seed, pp.chunkX, pp.chunkZ);
                LongOpenHashSet touched = TOUCHED.get(seed);
                if(touched != null && allPlaced(state, seed, touched)) {
                    state.complete(seed);
                    TOUCHED.remove(seed);
                }
            }
        }
    }

    private static boolean neighbourhoodLoaded(ServerLevel level, int cx, int cz, long assumeLoaded) {
        for(int dx = -1; dx <= 1; dx++) {
            for(int dz = -1; dz <= 1; dz++) {
                if(pack(cx + dx, cz + dz) == assumeLoaded) continue;
                if(!level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }

    private static boolean allPlaced(DeferredPathState state, long seed, LongOpenHashSet touched) {
        LongIterator it = touched.iterator();
        while(it.hasNext()) {
            long packed = it.nextLong();
            if(!state.wasPlaced(seed, unpackX(packed), unpackZ(packed))) return false;
        }
        return true;
    }

    //////////////////////////////
    // Reach

    // Every chunk the path's blocks, roadside structures, bushes or decorations can land in: the
    // chunks within reach of a waypoint. Null when the network or path type has gone.
    private static LongOpenHashSet touchedChunks(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath path) {
        var access = level.registryAccess();
        PathNetworkType network = MoogsPathsDatapackRegistries.getPathNetwork(access, job.networkId()).orElse(null);
        if(network == null) return null;
        PathType pathType = MoogsPathsDatapackRegistries.getPathType(access, network.pathType()).orElse(null);
        if(pathType == null) return null;

        int reach = pathType.width().max() / 2;
        for(ResourceLocation id : network.structureSets()) {
            var set = MoogsPathsDatapackRegistries.getStructureSet(access, id);
            if(set.isPresent()) reach = Math.max(reach, Math.abs(set.get().sideOffset()));
        }
        for(ResourceLocation id : network.bushDecoratorSets()) {
            var set = MoogsPathsDatapackRegistries.getBushDecoratorSet(access, id);
            if(set.isPresent()) reach = Math.max(reach, set.get().maxOffset() + set.get().maxSize());
        }
        for(ResourceLocation id : network.featureDecoratorSets()) {
            var set = MoogsPathsDatapackRegistries.getFeatureDecoratorSet(access, id);
            if(set.isPresent()) reach = Math.max(reach, set.get().scatterWidth() + 1);
        }
        reach += 1;

        LongOpenHashSet chunks = new LongOpenHashSet();
        long[] xz = path.xzPacked();
        for(long packed : xz) {
            int x = (int) (packed >> 32);
            int z = (int) packed;
            for(int cx = (x - reach) >> 4; cx <= (x + reach) >> 4; cx++) {
                for(int cz = (z - reach) >> 4; cz <= (z + reach) >> 4; cz++) {
                    chunks.add(pack(cx, cz));
                }
            }
        }
        return chunks;
    }

    private static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    private static int unpackX(long packed) { return (int) (packed >> 32); }

    private static int unpackZ(long packed) { return (int) packed; }

    private static int[] bounds(LongOpenHashSet chunks) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        LongIterator it = chunks.iterator();
        while(it.hasNext()) {
            long packed = it.nextLong();
            int x = unpackX(packed), z = unpackZ(packed);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX, maxX, minZ, maxZ};
    }

    private static void priorityRerank(ServerLevel level, Deque<PendingPlacement> queue) {
        // O(n) sweep moving placements within 8 chunks of any player to the head.
        // Acceptable because the queue size is normally small (<200) outside the initial
        // spawn burst, and during the burst the cost is dwarfed by placement itself.
        Set<Long> nearChunks = new HashSet<>();
        for(Player p : level.players()) {
            int pcx = p.blockPosition().getX() >> 4;
            int pcz = p.blockPosition().getZ() >> 4;
            for(int dx = -8; dx <= 8; dx++) {
                for(int dz = -8; dz <= 8; dz++) {
                    nearChunks.add(((long)(pcx + dx) << 32) | ((pcz + dz) & 0xFFFFFFFFL));
                }
            }
        }
        if(nearChunks.isEmpty()) return;
        ArrayDeque<PendingPlacement> near = new ArrayDeque<>();
        ArrayDeque<PendingPlacement> far = new ArrayDeque<>();
        PendingPlacement pp;
        while((pp = queue.pollFirst()) != null) {
            long k = ((long) pp.chunkX << 32) | (pp.chunkZ & 0xFFFFFFFFL);
            if(nearChunks.contains(k)) near.add(pp);
            else far.add(pp);
        }
        for(PendingPlacement p : far) queue.addLast(p);
        // near first
        ArrayDeque<PendingPlacement> reversed = new ArrayDeque<>();
        for(PendingPlacement p : near) reversed.addFirst(p);
        for(PendingPlacement p : reversed) queue.addFirst(p);
    }
}
