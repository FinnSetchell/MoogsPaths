package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.PathDataManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Owns the tick-driven side of deferred path placement.
 *
 * Responsibilities, in order of when they fire:
 *  1. Server-starting: register the worker's completion hook so finished jobs land
 *     here, and re-enqueue every {@link DeferredPathState} pending job for every
 *     loaded level. (See {@link #onServerStarting}.)
 *  2. Chunk-load: for any deferred path whose bbox intersects the loading chunk and
 *     where the path is already cached, schedule placement. For ones that aren't
 *     cached yet, do nothing - the path's eventual completion will handle it.
 *  3. Job complete (called by {@link PathfindWorker}): scan currently-loaded chunks
 *     in this level; schedule placement for every chunk the path intersects.
 *  4. Server tick: drain the per-level placement queue up to a per-tick budget.
 *
 * Single-server-thread invariants: chunk-load, tick, and completion-hook callbacks
 * all hand off to a per-level queue that's drained only on the server tick. The
 * completion hook itself may be called on a worker thread; it just enqueues.
 *
 * Concurrency: {@code pendingPlacements} is a {@link ConcurrentLinkedDeque} so the
 * worker thread can append while the server-tick thread polls. We do NOT block on
 * it; we just poll up to budget and re-check on next tick.
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

    private static final Map<ResourceKey<Level>, Deque<PendingPlacement>> PENDING = new HashMap<>();
    private static int tickCount = 0;
    private static volatile boolean started = false;

    private PlacementTickPump() {}

    private record PendingPlacement(DeferredPathJob job, PathDataManager.CachedPath path, int chunkX, int chunkZ) {}

    public static void onServerStarting(MinecraftServer server) {
        started = true;
        PathfindWorker.start(PlacementTickPump::onJobComplete);

        // Re-enqueue every pending job from every level's persisted state so the worker
        // pool picks up where it left off after the last shutdown.
        for(ServerLevel level : server.getAllLevels()) {
            DeferredPathState state = DeferredPathState.get(level);
            for(DeferredPathJob job : state.snapshotPending().values()) {
                PathfindWorker.submit(level, job);
            }
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        started = false;
        PathfindWorker.stop();
        synchronized(PENDING) { PENDING.clear(); }
    }

    /**
     * Entry point called by PathChunkFeature when a worldgen-phase origin candidate
     * passes the biome filter but doesn't yet have a cached path. Enqueues the job
     * and submits it to the worker pool.
     *
     * Safe to call from worldgen worker threads.
     */
    public static void enqueueFromWorldgen(ServerLevel level, DeferredPathJob job) {
        if(!started) return;
        DeferredPathState state = DeferredPathState.get(level);
        if(state.addPending(job)) {
            PathfindWorker.submit(level, job);
        } else {
            // already pending - the worker (if running) will eventually call onJobComplete.
            // For safety re-submit in case a previous submit was rejected.
            PathfindWorker.submit(level, job);
        }
    }

    /** Called by the worker thread when an A* job finishes. */
    private static void onJobComplete(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath path) {
        if(path.waypointCount() < 2) {
            // Rejected by length - clear from pending so we don't retry forever.
            DeferredPathState.get(level).markCompleted(job.pathSeed());
            return;
        }

        // For every currently-loaded chunk in this level that intersects the path bbox,
        // enqueue a placement. Chunks loaded LATER will pick up via the chunk-load hook.
        ResourceKey<Level> key = level.dimension();
        int chunkMinX = path.minX() >> 4;
        int chunkMaxX = path.maxX() >> 4;
        int chunkMinZ = path.minZ() >> 4;
        int chunkMaxZ = path.maxZ() >> 4;
        DeferredPathState state = DeferredPathState.get(level);
        synchronized(PENDING) {
            Deque<PendingPlacement> queue = PENDING.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
            for(int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                for(int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                    if(state.wasPlaced(job.pathSeed(), cx, cz)) continue;
                    if(level.getChunkSource().hasChunk(cx, cz)) {
                        queue.add(new PendingPlacement(job, path, cx, cz));
                    }
                }
            }
        }
    }

    /** Called on the server thread when a chunk loads in a given level. */
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        if(!started) return;
        ChunkPos cp = chunk.getPos();
        DeferredPathState state = DeferredPathState.get(level);

        // For every known pending job whose bbox intersects this chunk and where the
        // cached path is already computed, enqueue placement. For jobs whose path is
        // not yet computed, do nothing - the completion hook will pick this chunk up
        // once the path lands.
        Map<Long, DeferredPathJob> pending = state.snapshotPending();
        if(pending.isEmpty()) return;
        Deque<PendingPlacement> queue;
        synchronized(PENDING) {
            queue = PENDING.computeIfAbsent(level.dimension(), k -> new ConcurrentLinkedDeque<>());
        }
        for(DeferredPathJob job : pending.values()) {
            if(state.wasPlaced(job.pathSeed(), cp.x(), cp.z())) continue;
            PathDataManager.CachedPath cached = PathDataManager.peekCachedPath(job.pathSeed());
            if(cached == null) {
                // Path still computing or hasn't been picked up. Resubmit just in case
                // (no-op if already in flight or already cached).
                PathfindWorker.submit(level, job);
                continue;
            }
            int minCx = cached.minX() >> 4, maxCx = cached.maxX() >> 4;
            int minCz = cached.minZ() >> 4, maxCz = cached.maxZ() >> 4;
            if(cp.x() < minCx || cp.x() > maxCx || cp.z() < minCz || cp.z() > maxCz) continue;
            queue.add(new PendingPlacement(job, cached, cp.x(), cp.z()));
        }
    }

    /** Called on the server thread at the end of every tick. */
    public static void onServerTickEnd(MinecraftServer server) {
        if(!started) return;
        tickCount++;
        for(ServerLevel level : server.getAllLevels()) {
            Deque<PendingPlacement> queue;
            synchronized(PENDING) { queue = PENDING.get(level.dimension()); }
            if(queue == null || queue.isEmpty()) continue;

            DeferredPathState state = DeferredPathState.get(level);

            // Optional re-rank by player proximity - moves jobs near players to the head.
            if(tickCount % PRIORITY_RESORT_INTERVAL_TICKS == 0 && queue.size() > PLACEMENTS_PER_TICK) {
                priorityRerank(level, queue);
            }

            int budget = PLACEMENTS_PER_TICK;
            while(budget-- > 0) {
                PendingPlacement pp = queue.pollFirst();
                if(pp == null) break;
                if(!level.getChunkSource().hasChunk(pp.chunkX, pp.chunkZ)) {
                    // chunk unloaded - drop the placement; chunk-load will re-enqueue when needed.
                    continue;
                }
                if(state.wasPlaced(pp.job.pathSeed(), pp.chunkX, pp.chunkZ)) continue;
                try {
                    LiveChunkPlacer.place(level, pp.job, pp.path, pp.chunkX, pp.chunkZ);
                    state.markPlaced(pp.job.pathSeed(), pp.chunkX, pp.chunkZ);
                } catch(Throwable t) {
                    Constants.LOG.error("Deferred placement tick failed: {}", t.toString(), t);
                }
            }

            // If the path is fully placed across its bbox, drop from pending so we
            // don't pay the SavedData snapshot cost on future chunk loads.
            // (Compaction is intentionally lazy - we check only when the queue empties
            // for this level.)
            if(queue.isEmpty()) compactPending(level, state);
        }
    }

    private static void compactPending(ServerLevel level, DeferredPathState state) {
        // For each pending job whose cached path is computed AND every chunk in its
        // bbox is now in placedByPath, remove from pending. Marker for "fully done".
        Map<Long, DeferredPathJob> pending = state.snapshotPending();
        for(DeferredPathJob job : pending.values()) {
            PathDataManager.CachedPath cached = PathDataManager.peekCachedPath(job.pathSeed());
            if(cached == null) continue;
            int minCx = cached.minX() >> 4, maxCx = cached.maxX() >> 4;
            int minCz = cached.minZ() >> 4, maxCz = cached.maxZ() >> 4;
            boolean allPlaced = true;
            outer:
            for(int cx = minCx; cx <= maxCx; cx++) {
                for(int cz = minCz; cz <= maxCz; cz++) {
                    if(!state.wasPlaced(job.pathSeed(), cx, cz)) { allPlaced = false; break outer; }
                }
            }
            if(allPlaced) state.markCompleted(job.pathSeed());
        }
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
