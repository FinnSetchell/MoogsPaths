package com.finndog.moogs_paths.api;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.data.StructureOrigin;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.finndog.moogs_paths.world.StructureAnchors;
import com.finndog.moogs_paths.world.deferred.DeferredPathJob;
import com.finndog.moogs_paths.world.deferred.PlacementTickPump;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds paths for other mods: the search /paths locate runs, which uses it too.
 *
 * <p>Other mods reach this by reflection, by class name, method name and argument count, so those
 * stay the same on every version and loader, and the signatures only take Minecraft and Java types.
 *
 * <p>Call it on the server thread. Paths only generate in the overworld. A search computes the path
 * it finds and queues it for placement, so the path is laid when its chunks load. A far search can
 * take a while, seconds for a structure-anchored network, since checking a spot generates its structure.
 */
public final class MoogsPathsLocator {
    private MoogsPathsLocator() {}

    /** How far a search for a region network, or for any network, looks. */
    public static final int LOCATE_RADIUS = 10000;
    /** How far a search for a structure-anchored network looks: checking a spot generates its structure. */
    public static final int ANCHORED_LOCATE_RADIUS = 6000;

    private static final int MAX_LOCATE_VERIFY = 32;
    // Structures of the network's kind whose paths are tried before giving up. Spots holding no
    // structure, or another kind from the same set, don't count.
    private static final int MAX_ANCHORED_VERIFY = 16;

    /** Every loaded network, region and structure-anchored, in id order. */
    public static List<ResourceLocation> networks(ServerLevel level) {
        return List.copyOf(MoogsPathsDatapackRegistries.networksById(level.registryAccess()).keySet());
    }

    /**
     * The path of this network from the origin nearest {@code from} that produces one, as /paths locate
     * finds it, passing over paths whose {@link LocatedPath#origin()} is in {@code excludeOrigins} (null
     * for none). Passing the origins found so far walks outward to the next path each time. Empty for
     * an unknown network, or when nothing turns up within {@link #LOCATE_RADIUS}
     * ({@link #ANCHORED_LOCATE_RADIUS} for a structure-anchored network).
     */
    public static Optional<LocatedPath> locate(ServerLevel level, ResourceLocation network, BlockPos from, Set<BlockPos> excludeOrigins) {
        Optional<PathNetworkType> type = MoogsPathsDatapackRegistries.getPathNetwork(level.registryAccess(), network);
        if(type.isEmpty()) return Optional.empty();
        Set<BlockPos> exclude = excludeOrigins != null ? excludeOrigins : Set.of();
        return Optional.ofNullable(type.get().isStructureAnchored()
            ? locateAnchoredPath(level, network, from, exclude)
            : locateRegionPath(level, network, from, exclude));
    }

    /**
     * The nearest path of any network, as /paths locate without a network finds it, passing over the
     * origins in {@code excludeOrigins} like the search for one network above.
     */
    public static Optional<LocatedPath> locate(ServerLevel level, BlockPos from, Set<BlockPos> excludeOrigins) {
        Set<BlockPos> exclude = excludeOrigins != null ? excludeOrigins : Set.of();
        LocatedPath best = locateRegionPath(level, null, from, exclude);
        LocatedPath anchored = locateAnchoredPath(level, null, from, exclude);
        if(anchored != null && (best == null || distSq(anchored.landing(), from) < distSq(best.landing(), from))) best = anchored;
        return Optional.ofNullable(best);
    }

    //////////////////////////////

    // The path from the nearest region origin that produces one, per worldgen's own evaluation.
    private static LocatedPath locateRegionPath(ServerLevel level, ResourceLocation networkFilter, BlockPos from, Set<BlockPos> exclude) {
        RegistryAccess access = level.registryAccess();
        long worldSeed = level.getSeed();
        int fromX = from.getX();
        int fromZ = from.getZ();
        int chunkX = fromX >> 4;
        int chunkZ = fromZ >> 4;
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        record OriginCandidate(int originChunkX, int originChunkZ, int regionSize, List<PathNetworkType> networks, long distSq) {}
        List<OriginCandidate> candidates = new ArrayList<>();
        for(Map.Entry<Integer, List<PathNetworkType>> entry : MoogsPathsDatapackRegistries.networksByRegionSize(access).entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();
            PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, LOCATE_RADIUS, regionSize)
                .forEach(origin -> {
                    long dx = origin[0] * 16L + 8 - fromX;
                    long dz = origin[1] * 16L + 8 - fromZ;
                    candidates.add(new OriginCandidate(origin[0], origin[1], regionSize, networks, dx * dx + dz * dz));
                });
        }
        candidates.sort(Comparator.comparingLong(OriginCandidate::distSq));

        int verified = 0;
        for(OriginCandidate candidate : candidates) {
            if(verified >= MAX_LOCATE_VERIFY) break;
            BlockPos origin = chunkCentre(candidate.originChunkX(), candidate.originChunkZ());
            if(exclude.contains(origin)) continue;

            long pathSeed = worldSeed
                ^ ((long) candidate.originChunkX() * PathChunkFeature.ORIGIN_X_MULT)
                ^ ((long) candidate.originChunkZ() * PathChunkFeature.ORIGIN_Z_MULT)
                ^ ((long) candidate.regionSize() * PathChunkFeature.ORIGIN_REGION_SIZE_MULT)
                ^ PathChunkFeature.PATH_SEED_MIXER;

            if(networkFilter != null) {
                Optional<PathNetworkType> selected = PathChunkFeature.selectNetworkAt(
                    generator, randomState, candidate.originChunkX(), candidate.originChunkZ(), pathSeed, candidate.networks());
                if(selected.isEmpty()) continue;
                if(!MoogsPathsDatapackRegistries.networkId(access, selected.get()).map(networkFilter::equals).orElse(false)) continue;
            }

            // already-rejected origins are free to skip - biome/pathfinder already determined they
            // can't produce a path here, so they would just return empty from evaluateOrigin anyway
            if(PathDataManager.isRejected(pathSeed)) continue;

            verified++;

            Optional<PathChunkFeature.EvaluatedOrigin> result = PathChunkFeature.evaluateOrigin(
                level, generator, randomState, worldSeed,
                candidate.originChunkX(), candidate.originChunkZ(), candidate.regionSize(), candidate.networks());
            if(result.isEmpty()) continue;

            PathChunkFeature.EvaluatedOrigin ev = result.get();
            Optional<ResourceLocation> networkId = MoogsPathsDatapackRegistries.networkId(access, ev.network());
            if(networkFilter != null && !networkId.map(networkFilter::equals).orElse(false)) continue;

            // A search computes the path synchronously into PathDataManager's cache, but the deferred
            // placement system only places paths it has a job for: chunk-load handlers check
            // DeferredPathState.pending and skip a pathSeed with no job, so the player would teleport
            // to the reported spot and find nothing. Enqueueing puts the job into pending, so chunks
            // loading at the destination trigger LiveChunkPlacer. Idempotent: if the job is already
            // pending (from a prior worldgen pass), DeferredPathState.addPending no-ops.
            networkId.ifPresent(id -> PlacementTickPump.enqueueFromWorldgen(level, new DeferredPathJob(
                ev.pathSeed(), candidate.originChunkX(), candidate.originChunkZ(), candidate.regionSize(), id)));

            BlockPos landing = nearestWaypoint(level, ev.cachedPath(), ev.pathType(), fromX, fromZ);
            return located(networkId.orElse(unknownId()), origin, landing, from);
        }
        return null;
    }

    // The path leading out of the nearest structure that produces one. Computing it here caches it
    // for worldgen and queues its placement, like a region path above.
    private static LocatedPath locateAnchoredPath(ServerLevel level, ResourceLocation networkFilter, BlockPos from, Set<BlockPos> exclude) {
        RegistryAccess access = level.registryAccess();
        int fromX = from.getX();
        int fromZ = from.getZ();

        record Candidate(MoogsPathsDatapackRegistries.AnchoredNetwork network, StructureAnchors.StructureChunk chunk, long distSq) {}
        List<Candidate> candidates = new ArrayList<>();
        for(MoogsPathsDatapackRegistries.AnchoredNetwork anchored : MoogsPathsDatapackRegistries.anchoredNetworks(access)) {
            if(networkFilter != null && !networkFilter.equals(anchored.id())) continue;
            StructureOrigin origin = anchored.network().origin().orElseThrow();
            for(StructureAnchors.StructureChunk chunk : StructureAnchors.candidatesInRange(level, origin, fromX >> 4, fromZ >> 4, ANCHORED_LOCATE_RADIUS)) {
                long dx = ((long) chunk.x() << 4) + 8 - fromX;
                long dz = ((long) chunk.z() << 4) + 8 - fromZ;
                candidates.add(new Candidate(anchored, chunk, dx * dx + dz * dz));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::distSq));

        int verified = 0;
        for(Candidate candidate : candidates) {
            BlockPos pathOrigin = chunkCentre(candidate.chunk().x(), candidate.chunk().z());
            if(exclude.contains(pathOrigin)) continue;
            // Most spots in range hold no structure (wrong biome) or another kind from the set, e.g.
            // a plains village for a desert road. Counting those used to end the search ~2400 blocks out.
            StructureOrigin origin = candidate.network().network().origin().orElseThrow();
            if(!StructureAnchors.keepsPaths(level.getSeed(), candidate.chunk().x(), candidate.chunk().z(), candidate.network().id(), candidate.network().network())) continue;
            // A biome check first: generating every village in range to learn its kind took a minute.
            if(!StructureAnchors.mayHold(level, origin, candidate.chunk().x(), candidate.chunk().z())) continue;
            Optional<StructureAnchors.ResolvedStructure> structure = StructureAnchors.resolve(level, origin, candidate.chunk().x(), candidate.chunk().z());
            if(structure.isEmpty() || !structure.get().matches(origin)) continue;
            if(verified++ >= MAX_ANCHORED_VERIFY) break;
            BlockPos best = null;
            PathType pathType = MoogsPathsDatapackRegistries.getPathType(access, candidate.network().network().pathType()).orElse(null);
            for(PathDataManager.CachedPath path : StructureAnchors.pathsAt(level, candidate.network(), candidate.chunk())) {
                BlockPos here = nearestWaypoint(level, path, pathType, fromX, fromZ);
                if(best == null || distSq(here, fromX, fromZ) < distSq(best, fromX, fromZ)) best = here;
            }
            if(best != null) return located(candidate.network().id(), pathOrigin, best, from);
        }
        return null;
    }

    private static final int LAND_CHECKS = 64;
    private static final int LAND_CHECK_STRIDE = 4;

    // The nearest waypoint where the path is actually laid. A path type without water settings
    // leaves nothing over water, so the nearest waypoint on a lake would send the player to an
    // empty spot. Checks every few waypoints outward from the nearest, using the generator's height
    // maps (no chunks generated); falls back to the plain nearest if none is on land.
    private static BlockPos nearestWaypoint(ServerLevel level, PathDataManager.CachedPath path, PathType pathType, int fromX, int fromZ) {
        List<BlockPos> byDistance = new ArrayList<>(path.waypoints());
        byDistance.sort(Comparator.comparingLong(wp -> distSq(wp, fromX, fromZ)));
        if(pathType != null && pathType.waterSettings().isEmpty()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState randomState = level.getChunkSource().randomState();
            for(int i = 0, checks = 0; i < byDistance.size() && checks < LAND_CHECKS; i += LAND_CHECK_STRIDE, checks++) {
                BlockPos wp = byDistance.get(i);
                int floor = generator.getBaseHeight(wp.getX(), wp.getZ(), Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                int surface = generator.getBaseHeight(wp.getX(), wp.getZ(), Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                if(floor >= surface) return wp;
            }
        }
        return byDistance.get(0);
    }

    private static LocatedPath located(ResourceLocation network, BlockPos origin, BlockPos landing, BlockPos from) {
        return new LocatedPath(network, origin, landing, (int) Math.sqrt(distSq(landing, from)));
    }

    // A path's identity: the centre of its origin or structure chunk, at y 0 so it compares equal.
    private static BlockPos chunkCentre(int chunkX, int chunkZ) {
        return new BlockPos((chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
    }

    private static long distSq(BlockPos wp, BlockPos from) {
        return distSq(wp, from.getX(), from.getZ());
    }

    private static long distSq(BlockPos wp, int fromX, int fromZ) {
        long dx = wp.getX() - fromX;
        long dz = wp.getZ() - fromZ;
        return dx * dx + dz * dz;
    }

    private static ResourceLocation unknownId() {
        //? if >=1.21.1 {
        return ResourceLocation.fromNamespaceAndPath("unknown", "unknown");
        //?} else {
        /*return new ResourceLocation("unknown", "unknown");
        *///?}
    }
}
