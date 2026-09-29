package com.finndog.moogs_paths.world.deferred;

import net.minecraft.resources.ResourceLocation;

/**
 * Key for a deferred-path computation. All worldgen-derived state we need to
 * re-run {@code PathChunkFeature.evaluateOrigin} is captured by (pathSeed, originChunkX,
 * originChunkZ, regionSize, networkGroupKey) so a job can be persisted and replayed
 * across restarts without holding references to live MC objects.
 *
 * The job description is intentionally tiny - we re-look-up the network/pathType from
 * the live registry at execution time so a datapack swap between sessions doesn't blow
 * up persisted jobs.
 */
public record DeferredPathJob(long pathSeed, int originChunkX, int originChunkZ, int regionSize, ResourceLocation networkId) {
    public long packedOriginChunk() {
        return ((long) originChunkX << 32) | (originChunkZ & 0xFFFFFFFFL);
    }

    /**
     * A path leading out of the structure at a structure chunk. Region sizes are always positive, so
     * the path's index around its structure is kept as {@code -index} in regionSize, which leaves the
     * saved form of a job unchanged.
     */
    public static DeferredPathJob anchored(long pathSeed, int structureChunkX, int structureChunkZ, int pathIndex, ResourceLocation networkId) {
        return new DeferredPathJob(pathSeed, structureChunkX, structureChunkZ, -pathIndex, networkId);
    }

    public boolean isAnchored() {
        return regionSize <= 0;
    }

    public int anchorPathIndex() {
        return -regionSize;
    }
}
