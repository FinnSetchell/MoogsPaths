package com.finndog.moogs_paths.world.deferred;

import net.minecraft.resources.Identifier;

/**
 * Identifier for a deferred-path computation. All worldgen-derived state we need to
 * re-run {@code PathChunkFeature.evaluateOrigin} is captured by (pathSeed, originChunkX,
 * originChunkZ, regionSize, networkGroupKey) so a job can be persisted and replayed
 * across restarts without holding references to live MC objects.
 *
 * The job description is intentionally tiny - we re-look-up the network/pathType from
 * the live registry at execution time so a datapack swap between sessions doesn't blow
 * up persisted jobs.
 */
public record DeferredPathJob(long pathSeed, int originChunkX, int originChunkZ, int regionSize, Identifier networkId) {
    public long packedOriginChunk() {
        return ((long) originChunkX << 32) | (originChunkZ & 0xFFFFFFFFL);
    }
}
