package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.world.PathChunkFeature;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the {@link ExecutorService} that runs deferred A* off the main thread, plus the
 * "what's currently queued" bookkeeping.
 *
 * Lifecycle is tied to MC server lifetime: {@link #start} from server-starting,
 * {@link #stop} from server-stopping.
 *
 * Concurrency: jobs are submitted from the worldgen worker pool (during chunk-gen) and
 * from the server thread (chunk-load handler). The {@code inFlight} set dedups so the
 * same pathSeed doesn't run twice in parallel.
 */
public final class PathfindWorker {
    private PathfindWorker() {}

    private static volatile ExecutorService executor;
    // pathSeed -> Future, so we can tell what's currently running vs already cached.
    private static final ConcurrentHashMap<Long, Future<?>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static volatile JobCompletionHook completionHook;

    @FunctionalInterface
    public interface JobCompletionHook {
        void onComplete(ServerLevel level, DeferredPathJob job, PathDataManager.CachedPath path);
    }

    public static void start(JobCompletionHook hook) {
        if(executor != null) return;
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
        ExecutorService ex = executor;
        executor = null;
        IN_FLIGHT.clear();
        if(ex != null) {
            ex.shutdownNow();
            try { ex.awaitTermination(2, TimeUnit.SECONDS); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    public static boolean isRunning() { return executor != null; }

    /** Submit a job to compute waypoints. No-op if already in flight or already cached. */
    public static void submit(ServerLevel level, DeferredPathJob job) {
        if(executor == null) return;
        if(PathDataManager.peekCachedPath(job.pathSeed()) != null) {
            // Already computed; treat as immediate completion.
            PathDataManager.CachedPath cached = PathDataManager.peekCachedPath(job.pathSeed());
            JobCompletionHook h = completionHook;
            if(h != null) h.onComplete(level, job, cached);
            return;
        }
        if(IN_FLIGHT.containsKey(job.pathSeed())) return;
        try {
            Future<?> f = executor.submit(() -> runJob(level, job));
            IN_FLIGHT.put(job.pathSeed(), f);
        } catch(RejectedExecutionException ex) {
            // shutting down; drop silently
        }
    }

    private static void runJob(ServerLevel level, DeferredPathJob job) {
        try {
            PathDataManager.CachedPath path = computeWaypoints(level, job);
            if(path == null) return;
            JobCompletionHook h = completionHook;
            if(h != null) h.onComplete(level, job, path);
        } catch(Throwable t) {
            Constants.LOG.error("PathfindWorker job failed for seed {}: {}", job.pathSeed(), t.toString(), t);
        } finally {
            IN_FLIGHT.remove(job.pathSeed());
        }
    }

    private static PathDataManager.CachedPath computeWaypoints(ServerLevel level, DeferredPathJob job) {
        // Cheap re-derivation of the network/pathType from the live registry. Mirrors what
        // PathChunkFeature.evaluateOrigin did during the feature pass. Done off-thread.
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();

        Optional<PathNetworkType> netOpt = MoogsPathsDatapackRegistries.getPathNetwork(level.registryAccess(), job.networkId());
        if(netOpt.isEmpty()) {
            Constants.LOG.warn("Deferred job references missing network {}", job.networkId());
            return null;
        }
        PathNetworkType network = netOpt.get();
        Optional<PathType> ptOpt = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
        if(ptOpt.isEmpty()) return null;
        PathType pathType = ptOpt.get();

        int originBlockX = job.originChunkX() * 16 + 8;
        int originBlockZ = job.originChunkZ() * 16 + 8;

        return PathDataManager.getOrComputeWaypoints(job.pathSeed(), () -> {
            int originSurfaceY = generator.getBaseHeight(originBlockX, originBlockZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
            BlockPos originPos = new BlockPos(originBlockX, originSurfaceY, originBlockZ);
            int biomeQuartY = QuartPos.fromBlock(64);
            RandomSource walkRandom = RandomSource.create(job.pathSeed() ^ 0x1L); // WALK_MIXER
            return com.finndog.moogs_paths.world.PathFinder.findPath(originPos, pathType, walkRandom,
                (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState),
                (gx, gz) -> {
                    Holder<Biome> b = biomeSource.getNoiseBiome(QuartPos.fromBlock(gx), biomeQuartY, QuartPos.fromBlock(gz), sampler);
                    return network.biomes().contains(b) && !b.is(PathChunkFeature.HAS_NO_PATHS);
                });
        });
    }
}
