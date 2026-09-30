package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
//? if >=1.21.1 <1.21.11 {
import net.minecraft.core.HolderLookup;
//?}
//? if <1.21.11 {
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
//?} else {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
//? if >=1.21.11 {
/*import net.minecraft.util.datafix.DataFixTypes;
*///?}
import net.minecraft.world.level.saveddata.SavedData;
//? if <1.21.11 {
import net.minecraft.world.level.storage.DimensionDataStorage;
//?} else {
/*import net.minecraft.world.level.saveddata.SavedDataType;
*///?}

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension SavedData tracking deferred paths.
 *
 * State:
 *  - {@code pendingJobs}: every {@link DeferredPathJob} enqueued by the worldgen pass (or a
 *    locate) whose path is not yet fully laid. Survives restart.
 *  - {@code bounds}: for a pending job whose path has been computed, the chunk range it writes
 *    to. After a restart the job is only recomputed once a chunk in that range loads.
 *  - {@code placedByPath}: per-pathSeed set of packed chunk positions that already received
 *    placement. Idempotency gate so a deferred path can't paint the same chunk twice.
 *  - {@code completed}: seeds whose path is fully laid (or too short to lay). Their placed
 *    chunks are dropped, and worldgen never queues them again.
 *
 * Accessed concurrently from worldgen threads (enqueue), the pathfind workers and the server
 * tick. All collections are {@link ConcurrentHashMap}-backed. One instance per level, created on
 * the server thread when the level loads: vanilla's data storage map isn't safe to fill from
 * worldgen threads.
 */
public class DeferredPathState extends SavedData {

    //? if <26.1.2 {
    public static final String NAME = Constants.MOD_ID + "_deferred_paths";
    //?} else {
    /*public static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "deferred_paths");
    *///?}

    private static final Map<ServerLevel, DeferredPathState> BY_LEVEL = new ConcurrentHashMap<>();

    private final Map<Long, DeferredPathJob> pendingJobs = new ConcurrentHashMap<>();
    private final Map<Long, int[]> bounds = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> placedByPath = new ConcurrentHashMap<>();
    private final Set<Long> completed = ConcurrentHashMap.newKeySet();

    public DeferredPathState() {}

    //////////////////////////////
    // Codec plumbing (1.21.11+)
    //
    // These declarations MUST come before TYPE because TYPE's initializer calls codec(),
    // and codec() dereferences JOB_CODEC and PLACED_CODEC at construction time. Java
    // initializes static fields in source order, so if JOB_CODEC is declared below TYPE
    // it will still be null when codec() runs, producing an NPE during class init.
    //////////////////////////////

    //? if >=1.21.11 {
    /*private record PlacedEntry(long seed, long[] chunks) {}

    private static final Codec<DeferredPathJob> JOB_CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.LONG.fieldOf("seed").forGetter(DeferredPathJob::pathSeed),
        Codec.INT.fieldOf("cx").forGetter(DeferredPathJob::originChunkX),
        Codec.INT.fieldOf("cz").forGetter(DeferredPathJob::originChunkZ),
        Codec.INT.fieldOf("rs").forGetter(DeferredPathJob::regionSize),
        ResourceLocation.CODEC.fieldOf("net").forGetter(DeferredPathJob::networkId)
    ).apply(inst, DeferredPathJob::new));

    private static final Codec<PlacedEntry> PLACED_CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.LONG.fieldOf("seed").forGetter(PlacedEntry::seed),
        Codec.LONG_STREAM.fieldOf("chunks").xmap(s -> s.toArray(), java.util.stream.LongStream::of).forGetter(PlacedEntry::chunks)
    ).apply(inst, PlacedEntry::new));

    private record BoundsEntry(long seed, int[] bb) {}

    private static final Codec<BoundsEntry> BOUNDS_CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.LONG.fieldOf("seed").forGetter(BoundsEntry::seed),
        Codec.INT_STREAM.fieldOf("bb").xmap(s -> s.toArray(), java.util.stream.IntStream::of).forGetter(BoundsEntry::bb)
    ).apply(inst, BoundsEntry::new));

    // The SavedDataType id is a String on 1.21.11 and a ResourceLocation from 26.1; NAME follows.
    // The fix type must not be null (vanilla calls it unchecked); command storage has no fixers
    // that could touch our data, where LEVEL's would run level.dat fixes on it after an upgrade.
    public static final SavedDataType<DeferredPathState> TYPE = new SavedDataType<>(
        NAME,
        DeferredPathState::new,
        codec(),
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    *///?}
    /** Loads or creates the level's state. Server thread, when the level loads. */
    public static void onLevelLoad(ServerLevel level) {
        BY_LEVEL.put(level, loadOrCreate(level));
    }

    public static void clearLoaded() {
        BY_LEVEL.clear();
    }

    public static DeferredPathState get(ServerLevel level) {
        DeferredPathState state = BY_LEVEL.get(level);
        if(state != null) return state;
        // A level another mod created without a load event.
        synchronized(BY_LEVEL) {
            return BY_LEVEL.computeIfAbsent(level, DeferredPathState::loadOrCreate);
        }
    }

    private static DeferredPathState loadOrCreate(ServerLevel level) {
        //? if >=1.21.11 {
        /*return level.getDataStorage().computeIfAbsent(TYPE);
        *///?} else {
        DimensionDataStorage storage = level.getDataStorage();
        //? if >=1.21.1 {
        return storage.computeIfAbsent(
            new SavedData.Factory<>(DeferredPathState::new, DeferredPathState::load, null),
            NAME);
        //?} else {
        /*return storage.computeIfAbsent(DeferredPathState::load, DeferredPathState::new, NAME);
        *///?}
        //?}
    }

    public Map<Long, DeferredPathJob> snapshotPending() {
        return new HashMap<>(pendingJobs);
    }

    /** A live view: iteration sees jobs added or removed meanwhile, or not. */
    public Collection<DeferredPathJob> pendingJobs() {
        return pendingJobs.values();
    }

    public int pendingCount() { return pendingJobs.size(); }

    public int boundsCount() { return bounds.size(); }

    public int completedCount() { return completed.size(); }

    public int placedChunkCount() {
        int n = 0;
        for(Set<Long> s : placedByPath.values()) n += s.size();
        return n;
    }

    public boolean hasPending() {
        return !pendingJobs.isEmpty();
    }

    public boolean isPending(long pathSeed) {
        return pendingJobs.containsKey(pathSeed);
    }

    public boolean isCompleted(long pathSeed) {
        return completed.contains(pathSeed);
    }

    public boolean addPending(DeferredPathJob job) {
        if(completed.contains(job.pathSeed())) return false;
        if(pendingJobs.putIfAbsent(job.pathSeed(), job) == null) {
            setDirty();
            return true;
        }
        return false;
    }

    /** The chunk range {minX, maxX, minZ, maxZ} a computed path writes to, or null before it is computed. */
    public int[] bounds(long pathSeed) {
        return bounds.get(pathSeed);
    }

    public void setBounds(long pathSeed, int[] chunkBounds) {
        int[] old = bounds.put(pathSeed, chunkBounds);
        if(old == null || !java.util.Arrays.equals(old, chunkBounds)) setDirty();
    }

    /** The path is fully laid, or too short to lay: forget its chunks and never queue it again. */
    public void complete(long pathSeed) {
        completed.add(pathSeed);
        forget(pathSeed);
        setDirty();
    }

    /** The job can't run (its network or path type is gone, or it keeps failing). Worldgen may queue it again. */
    public void retire(long pathSeed) {
        forget(pathSeed);
        setDirty();
    }

    private void forget(long pathSeed) {
        pendingJobs.remove(pathSeed);
        bounds.remove(pathSeed);
        placedByPath.remove(pathSeed);
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

    private static long packChunk(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    //? if <1.21.11 {
    @Override
    //? if >=1.21.1 {
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*public CompoundTag save(CompoundTag tag) {
    *///?}
        ListTag pending = new ListTag();
        for(DeferredPathJob job : pendingJobs.values()) {
            CompoundTag j = new CompoundTag();
            j.putLong("seed", job.pathSeed());
            j.putInt("cx", job.originChunkX());
            j.putInt("cz", job.originChunkZ());
            j.putInt("rs", job.regionSize());
            j.putString("net", job.networkId().toString());
            pending.add(j);
        }
        tag.put("pending", pending);

        ListTag placed = new ListTag();
        for(Map.Entry<Long, Set<Long>> e : placedByPath.entrySet()) {
            CompoundTag p = new CompoundTag();
            p.putLong("seed", e.getKey());
            long[] arr = new long[e.getValue().size()];
            int i = 0;
            for(long v : e.getValue()) arr[i++] = v;
            p.put("chunks", new LongArrayTag(arr));
            placed.add(p);
        }
        tag.put("placed", placed);

        ListTag boundsList = new ListTag();
        for(Map.Entry<Long, int[]> e : bounds.entrySet()) {
            CompoundTag b = new CompoundTag();
            b.putLong("seed", e.getKey());
            b.putIntArray("bb", e.getValue());
            boundsList.add(b);
        }
        tag.put("bounds", boundsList);

        long[] done = new long[completed.size()];
        int d = 0;
        for(long seed : completed) done[d++] = seed;
        tag.put("done", new LongArrayTag(done));
        return tag;
    }

    //? if >=1.21.1 {
    public static DeferredPathState load(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*public static DeferredPathState load(CompoundTag tag) {
    *///?}
        DeferredPathState state = new DeferredPathState();
        ListTag pending = tag.getList("pending", Tag.TAG_COMPOUND);
        for(int i = 0; i < pending.size(); i++) {
            CompoundTag j = pending.getCompound(i);
            try {
                //? if >=1.21.1 {
                ResourceLocation rl = ResourceLocation.parse(j.getString("net"));
                //?} else {
                /*ResourceLocation rl = new ResourceLocation(j.getString("net"));
                *///?}
                DeferredPathJob job = new DeferredPathJob(j.getLong("seed"), j.getInt("cx"), j.getInt("cz"), j.getInt("rs"), rl);
                state.pendingJobs.put(job.pathSeed(), job);
            } catch(Exception ex) {
                Constants.LOG.warn("Skipping malformed deferred-path entry: {}", ex.toString());
            }
        }
        ListTag placed = tag.getList("placed", Tag.TAG_COMPOUND);
        for(int i = 0; i < placed.size(); i++) {
            CompoundTag p = placed.getCompound(i);
            long seed = p.getLong("seed");
            long[] arr = p.getLongArray("chunks");
            Set<Long> s = ConcurrentHashMap.newKeySet();
            for(long v : arr) s.add(v);
            state.placedByPath.put(seed, s);
        }
        ListTag boundsList = tag.getList("bounds", Tag.TAG_COMPOUND);
        for(int i = 0; i < boundsList.size(); i++) {
            CompoundTag b = boundsList.getCompound(i);
            int[] bb = b.getIntArray("bb");
            if(bb.length == 4) state.bounds.put(b.getLong("seed"), bb);
        }
        for(long seed : tag.getLongArray("done")) state.completed.add(seed);
        return state;
    }
    //?} else {
    /*private static Codec<DeferredPathState> codec() {
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
            }),
            BOUNDS_CODEC.listOf().optionalFieldOf("bounds", List.of()).forGetter(s -> {
                List<BoundsEntry> out = new ArrayList<>();
                for(Map.Entry<Long, int[]> e : s.bounds.entrySet()) out.add(new BoundsEntry(e.getKey(), e.getValue()));
                return out;
            }),
            Codec.LONG_STREAM.optionalFieldOf("done", java.util.stream.LongStream.empty())
                .forGetter(s -> s.completed.stream().mapToLong(Long::longValue))
        ).apply(inst, (pending, placed, boundsList, done) -> {
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
            for(BoundsEntry be : boundsList) {
                if(be.bb().length == 4) state.bounds.put(be.seed(), be.bb());
            }
            done.forEach(state.completed::add);
            return state;
        }));
    }
    *///?}
}
