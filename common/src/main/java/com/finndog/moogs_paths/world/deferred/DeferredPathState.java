package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension SavedData tracking deferred paths.
 *
 * Two pieces of state:
 *  - {@code pendingJobs}: every {@link DeferredPathJob} that has been enqueued by the
 *    worldgen feature pass but has not yet completed. Survives restart. On server start
 *    we re-enqueue everything in here so a save-and-quit mid-pathing doesn't lose work.
 *  - {@code placedByPath}: per-pathSeed set of packed chunk positions that already
 *    received placement. Idempotency gate so a deferred path can't paint the same chunk
 *    twice (e.g. unload + reload while jobs are in flight).
 *
 * Accessed concurrently from the server tick (placement drain) and the pathfind worker
 * (job completion). All maps are {@link ConcurrentHashMap}-backed; the SavedData itself
 * is only saved on the IO thread via MC's normal flush cadence.
 *
 * Persistence uses the Codec-based {@link SavedDataType} pipeline introduced in 1.21.5.
 */
public class DeferredPathState extends SavedData {

    public static final Identifier NAME = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "deferred_paths");

    private final Map<Long, DeferredPathJob> pendingJobs = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> placedByPath = new ConcurrentHashMap<>();

    public DeferredPathState() {}

    //////////////////////////////
    // Codec plumbing
    //
    // These declarations MUST come before TYPE because TYPE's initializer calls codec(),
    // and codec() dereferences JOB_CODEC and PLACED_CODEC at construction time. Java
    // initializes static fields in source order, so if JOB_CODEC is declared below TYPE
    // it will still be null when codec() runs, producing an NPE during class init.
    //////////////////////////////

    private record PlacedEntry(long seed, long[] chunks) {}

    private static final Codec<DeferredPathJob> JOB_CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.LONG.fieldOf("seed").forGetter(DeferredPathJob::pathSeed),
        Codec.INT.fieldOf("cx").forGetter(DeferredPathJob::originChunkX),
        Codec.INT.fieldOf("cz").forGetter(DeferredPathJob::originChunkZ),
        Codec.INT.fieldOf("rs").forGetter(DeferredPathJob::regionSize),
        Identifier.CODEC.fieldOf("net").forGetter(DeferredPathJob::networkId)
    ).apply(inst, DeferredPathJob::new));

    private static final Codec<PlacedEntry> PLACED_CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.LONG.fieldOf("seed").forGetter(PlacedEntry::seed),
        Codec.LONG_STREAM.fieldOf("chunks").xmap(s -> s.toArray(), java.util.stream.LongStream::of).forGetter(PlacedEntry::chunks)
    ).apply(inst, PlacedEntry::new));

    // 1.21.11 SavedDataType ctor takes a String id (not Identifier), Supplier<T>, Codec<T>,
    // and a @Nullable DataFixTypes.
    public static final SavedDataType<DeferredPathState> TYPE = new SavedDataType<>(
        NAME,
        DeferredPathState::new,
        codec(),
        DataFixTypes.LEVEL
    );

    public static DeferredPathState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Map<Long, DeferredPathJob> snapshotPending() {
        return new HashMap<>(pendingJobs);
    }

    public boolean addPending(DeferredPathJob job) {
        if(pendingJobs.putIfAbsent(job.pathSeed(), job) == null) {
            setDirty();
            return true;
        }
        return false;
    }

    public void markCompleted(long pathSeed) {
        if(pendingJobs.remove(pathSeed) != null) setDirty();
    }

    public boolean wasPlaced(long pathSeed, int chunkX, int chunkZ) {
        Set<Long> s = placedByPath.get(pathSeed);
        if(s == null) return false;
        return s.contains(packChunk(chunkX, chunkZ));
    }

    public boolean markPlaced(long pathSeed, int chunkX, int chunkZ) {
        Set<Long> s = placedByPath.computeIfAbsent(pathSeed, k -> ConcurrentHashMap.newKeySet());
        if(s.add(packChunk(chunkX, chunkZ))) {
            setDirty();
            return true;
        }
        return false;
    }

    public void forgetPlaced(long pathSeed) {
        if(placedByPath.remove(pathSeed) != null) setDirty();
    }

    private static long packChunk(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    private static Codec<DeferredPathState> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
            JOB_CODEC.listOf().optionalFieldOf("pending", List.of()).forGetter(s -> new ArrayList<>(s.pendingJobs.values())),
            PLACED_CODEC.listOf().optionalFieldOf("placed", List.of()).forGetter(s -> {
                List<PlacedEntry> out = new ArrayList<>();
                for(Map.Entry<Long, Set<Long>> e : s.placedByPath.entrySet()) {
                    long[] arr = new long[e.getValue().size()];
                    int i = 0;
                    for(long v : e.getValue()) arr[i++] = v;
                    out.add(new PlacedEntry(e.getKey(), arr));
                }
                return out;
            })
        ).apply(inst, (pending, placed) -> {
            DeferredPathState state = new DeferredPathState();
            for(DeferredPathJob job : pending) {
                try {
                    state.pendingJobs.put(job.pathSeed(), job);
                } catch(Exception ex) {
                    Constants.LOG.warn("Skipping malformed deferred-path entry: {}", ex.toString());
                }
            }
            for(PlacedEntry pe : placed) {
                Set<Long> s = ConcurrentHashMap.newKeySet();
                for(long v : pe.chunks()) s.add(v);
                state.placedByPath.put(pe.seed(), s);
            }
            return state;
        }));
    }
}
