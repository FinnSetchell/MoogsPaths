package com.finndog.moogs_paths.world.deferred;

import com.finndog.moogs_paths.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    public static final String NAME = Constants.MOD_ID + "_deferred_paths";

    private final Map<Long, DeferredPathJob> pendingJobs = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> placedByPath = new ConcurrentHashMap<>();

    public DeferredPathState() {}

    public static DeferredPathState get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(
            new SavedData.Factory<>(DeferredPathState::new, DeferredPathState::load, null),
            NAME);
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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
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

    public static DeferredPathState load(CompoundTag tag, HolderLookup.Provider registries) {
        DeferredPathState state = new DeferredPathState();
        ListTag pending = tag.getList("pending", Tag.TAG_COMPOUND);
        for(int i = 0; i < pending.size(); i++) {
            CompoundTag j = pending.getCompound(i);
            try {
                ResourceLocation rl = ResourceLocation.parse(j.getString("net"));
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
}
