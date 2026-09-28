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
 */
public class DeferredPathState extends SavedData {

    //? if <26.1.2 {
    public static final String NAME = Constants.MOD_ID + "_deferred_paths";
    //?} else {
    /*public static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "deferred_paths");
    *///?}

    private final Map<Long, DeferredPathJob> pendingJobs = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> placedByPath = new ConcurrentHashMap<>();

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

    // The SavedDataType id is a String on 1.21.11 and a ResourceLocation from 26.1; NAME follows.
    public static final SavedDataType<DeferredPathState> TYPE = new SavedDataType<>(
        NAME,
        DeferredPathState::new,
        codec(),
        DataFixTypes.LEVEL
    );

    *///?}
    public static DeferredPathState get(ServerLevel level) {
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
        // Used at server-start to re-enqueue jobs. Snapshot so caller can iterate without
        // worrying about concurrent removals when jobs complete.
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
    *///?}
}
