package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.StructureAnchors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the {@link ExecutorService} that runs deferred A* off the main thread, plus the
 * "what's currently queued" bookkeeping.
 *
 * Lifecycle is tied to MC server lifetime: {@link #start} before the levels load,
 * {@link #stop} from server-stopping.
 *
 * Concurrency: jobs are submitted from the worldgen worker pool (during chunk-gen) and
 * from the server thread (chunk-load handler). The {@code IN_FLIGHT} set dedups so the
 * same pathSeed doesn't run twice in parallel.
 */
public final class PathfindWorker {
    private PathfindWorker() {}

    // A job that throws this many times in one session is retired rather than retried on every chunk load.
    private static final int MAX_FAILURES = 3;

    private static volatile ExecutorService executor;
    private static final Set<Long> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Long, Integer> FAILURES = new ConcurrentHashMap<>();
    private static volatile JobCompletionHook completionHook;
    // Bumped on start and stop, so a job still running when its server stops can't report into the next one.
    private static final AtomicInteger SESSION = new AtomicInteger();

    @FunctionalInterface
    public interface JobCompletionHook {
        /** {@code path} is null when the job can't run and should be retired. */
        void onComplete(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath path);
    }

    public static void start(JobCompletionHook hook) {
        if(executor != null) return;
        SESSION.incrementAndGet();
        IN_FLIGHT.clear();
        FAILURES.clear();
        completionHook = hook;
        int cores = Math.max(2, Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "moogs_paths-pathfind-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        };
        executor = Executors.newFixedThreadPool(cores, tf);
        Constants.LOG.info("PathfindWorker started with {} threads", cores);
    }

    public static void stop() {
        SESSION.incrementAndGet();
        completionHook = null;
        ExecutorService ex = executor;
        executor = null;
        IN_FLIGHT.clear();
        if(ex != null) {
            ex.shutdownNow();
            try { ex.awaitTermination(2, TimeUnit.SECONDS); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    public static int inFlightCount() { return IN_FLIGHT.size(); }

    /** Submit a job to compute waypoints. No-op if already in flight; reports at once if already cached. */
    public static void submit(ServerLevel level, DeferredPathJob job) {
        ExecutorService ex = executor;
        if(ex == null) return;
        PathDataManager.CachedPath cached = PathDataManager.peekCachedPath(job.pathSeed());
        if(cached != null) {
            JobCompletionHook h = completionHook;
            if(h != null) h.onComplete(level, job, cached);
            return;
        }
        // Reserved before submitting: checking then adding let two threads both submit, and a job that
        // finished before the add left a stale entry blocking its seed for the rest of the session.
        if(!IN_FLIGHT.add(job.pathSeed())) return;
        int session = SESSION.get();
        try {
            ex.submit(() -> runJob(level, job, session));
        } catch(RejectedExecutionException rejected) {
            IN_FLIGHT.remove(job.pathSeed());
        }
    }

    private static void runJob(ServerLevel level, DeferredPathJob job, int session) {
        PathDataManager.CachedPath path;
        try {
            path = computeWaypoints(level, job);
        } catch(Throwable t) {
            int failures = FAILURES.merge(job.pathSeed(), 1, Integer::sum);
            if(failures == 1) Constants.LOG.error("PathfindWorker job failed for seed {}: {}", job.pathSeed(), t.toString(), t);
            IN_FLIGHT.remove(job.pathSeed());
            if(failures < MAX_FAILURES) return;
            Constants.LOG.warn("Giving up on the path for seed {} after {} failures", job.pathSeed(), failures);
            path = null;
        }
        IN_FLIGHT.remove(job.pathSeed());
        JobCompletionHook h = completionHook;
        if(h != null && session == SESSION.get()) h.onComplete(level, job, path);
    }

    private static PathDataManager.CachedPath computeWaypoints(ServerLevel level, DeferredPathJob job) {
        // The network and path type come from the live registry, so a datapack swap between sessions
        // can't break a saved job. Done off-thread.
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        // Null retires the job: a network or path type removed by a datapack, or a network that changed kind.
        Optional<PathNetworkType> netOpt = MoogsPathsDatapackRegistries.getPathNetwork(level.registryAccess(), job.networkId());
        if(netOpt.isEmpty()) {
            PathDataManager.warnMissingOnce("Path network for a queued path", job.networkId());
            return null;
        }
        PathNetworkType network = netOpt.get();
        Optional<PathType> ptOpt = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
        if(ptOpt.isEmpty()) {
            PathDataManager.warnMissingOnce("Path type", network.pathType());
            return null;
        }
        PathType pathType = ptOpt.get();
        if(job.isAnchored() != network.isStructureAnchored()) return null;
        if(job.isAnchored()) {
            return PathDataManager.getOrComputeWaypoints(job.pathSeed(), () -> StructureAnchors.computePath(
                level, network, job.networkId(), pathType, job.originChunkX(), job.originChunkZ(), job.anchorPathIndex()));
        }

        return PathDataManager.getOrComputeWaypoints(job.pathSeed(), () -> PathChunkFeature.findRegionPath(
            level, generator, randomState, job.originChunkX(), job.originChunkZ(), job.pathSeed(), network, pathType));
    }
}
