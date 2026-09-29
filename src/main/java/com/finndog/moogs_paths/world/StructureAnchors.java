package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.data.StructureOrigin;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
//? if >=26.3 {
/*import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
*///?} else {
import net.minecraft.world.level.biome.Climate;
//?}
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a {@link StructureOrigin} into paths that lead away from real structures.
 *
 * <p>Any chunk has to arrive at the same paths without reading generated chunks, so a structure is
 * found the way vanilla places it:
 * <ol>
 *   <li>{@link #candidatesInRange} repeats vanilla's random-spread grid maths and its own
 *       {@link StructurePlacement#isStructureChunk} check (frequency and exclusion zones included).
 *       Cheap, and safe on world-gen threads.</li>
 *   <li>{@link #resolve} repeats vanilla's pick between the set's structures for that chunk and runs
 *       the structure's own seeded generation, which checks biomes and terrain. Expensive, so it only
 *       runs on the pathfinding workers and in commands, and is cached per structure.</li>
 * </ol>
 */
public final class StructureAnchors {
    private StructureAnchors() {}

    /**
     * Blocks a path can start away from its structure's chunk: the start sits just outside the
     * structure's pieces, and a large jigsaw structure reaches this far from its start chunk.
     */
    public static final int STRUCTURE_REACH = 128;

    // Seeds live in their own space so an anchored path never shares one with a region path.
    private static final long ANCHOR_MIXER = 0x53747275637400L; // "Struct\0"
    private static final long CHUNK_X_MULT = 0x9E3779B97F4A7C15L;
    private static final long CHUNK_Z_MULT = 0xC2B2AE3D27D4EB4FL;
    private static final long NETWORK_MULT = 0x165667B19E3779F9L;
    private static final long INDEX_MULT = 0x27D4EB2F165667C5L;
    private static final long WALK_MIXER = 0x1L;

    private static final int GOAL_ATTEMPTS = 6;
    private static final int FALLBACK_HEADINGS = 7;
    private static final int FIRST_STEP = 12;
    private static final int MAX_START_STEPS = 256;
    private static final int BIOME_Y = 64;

    /** A chunk where the structure set places a structure (before biomes are checked). */
    public record StructureChunk(int x, int z) {}

    /** A placed piece: its box, and for jigsaw pieces the pool element's NBT id and placement. */
    private record PieceInfo(BoundingBox box, ResourceLocation element, BlockPos position, Rotation rotation) {}

    /** A structure that really generates at a candidate chunk. */
    public record ResolvedStructure(ResourceLocation structure, BoundingBox bounds, List<PieceInfo> pieces) {}

    private record SetCell(long worldSeed, ResourceLocation set, int cellX, int cellZ) {}
    private record SetChunk(long worldSeed, ResourceLocation set, int chunkX, int chunkZ) {}

    private static final long NO_CANDIDATE = Long.MIN_VALUE;
    private static final ConcurrentHashMap<SetCell, Long> CANDIDATE_CACHE = new ConcurrentHashMap<>();
    private static final int CANDIDATE_CACHE_SOFT_CAP = 65536;
    // Optional.empty() is a real answer: nothing of this set generates at that chunk.
    private static final ConcurrentHashMap<SetChunk, Optional<ResolvedStructure>> RESOLVE_CACHE = new ConcurrentHashMap<>();
    private static final int RESOLVE_CACHE_SOFT_CAP = 2048;

    public static void clearCache() {
        CANDIDATE_CACHE.clear();
        RESOLVE_CACHE.clear();
    }

    //////////////////////////////
    // Seeds

    /** One path's seed: stable for a structure chunk, network and path index, distinct across all three. */
    public static long pathSeed(long worldSeed, int structureChunkX, int structureChunkZ, ResourceLocation network, int pathIndex) {
        long z = worldSeed
            + (long) structureChunkX * CHUNK_X_MULT
            + (long) structureChunkZ * CHUNK_Z_MULT
            + idHash(network) * NETWORK_MULT
            + (pathIndex + 1L) * INDEX_MULT
            + ANCHOR_MIXER;
        // SplitMix64 finalizer: structure chunks are small, dense coordinates, and plain
        // multiply-and-XOR mixing collides on them.
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Stable hash of an id, so distinct networks get distinct seeds. */
    static long idHash(ResourceLocation id) {
        long h = 1125899906842597L;
        String s = id.toString();
        for(int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    //////////////////////////////
    // Candidates

    /**
     * Chunks within {@code blockRadius} of the given chunk where the origin's structure set places a
     * structure, per vanilla's placement rules. Only random-spread sets can be anchored to.
     */
    public static List<StructureChunk> candidatesInRange(ServerLevel level, StructureOrigin origin, int chunkX, int chunkZ, int blockRadius) {
        Optional<StructureSet> set = MoogsPathsDatapackRegistries.vanillaStructureSet(level.registryAccess(), origin.structureSet());
        if(set.isEmpty()) {
            PathDataManager.warnMissingOnce("Structure set", origin.structureSet());
            return List.of();
        }
        StructurePlacement placement = set.get().placement();
        if(!(placement instanceof RandomSpreadStructurePlacement spread)) {
            // Concentric rings (strongholds) have no per-chunk grid to walk.
            PathDataManager.warnMissingOnce("Structure set without random_spread placement (cannot anchor paths)", origin.structureSet());
            return List.of();
        }

        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        long worldSeed = level.getSeed();
        int spacing = spread.spacing();
        int chunkRadius = (blockRadius + 15) >> 4;
        // One cell of margin: a cell's structure chunk can sit anywhere inside the cell.
        int minCellX = Math.floorDiv(chunkX - chunkRadius, spacing) - 1;
        int maxCellX = Math.floorDiv(chunkX + chunkRadius, spacing) + 1;
        int minCellZ = Math.floorDiv(chunkZ - chunkRadius, spacing) - 1;
        int maxCellZ = Math.floorDiv(chunkZ + chunkRadius, spacing) + 1;
        long radiusSq = (long) blockRadius * blockRadius;
        long centerX = ((long) chunkX << 4) + 8;
        long centerZ = ((long) chunkZ << 4) + 8;

        List<StructureChunk> result = new ArrayList<>();
        for(int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for(int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long packed = placedChunkInCell(state, spread, worldSeed, origin.structureSet(), cellX, cellZ);
                if(packed == NO_CANDIDATE) continue;
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                long dx = ((long) cx << 4) + 8 - centerX;
                long dz = ((long) cz << 4) + 8 - centerZ;
                if(dx * dx + dz * dz <= radiusSq) result.add(new StructureChunk(cx, cz));
            }
        }
        return result;
    }

    // The chunk a grid cell places its structure in, or NO_CANDIDATE when the placement's frequency
    // or exclusion zone rules it out.
    private static long placedChunkInCell(ChunkGeneratorStructureState state, RandomSpreadStructurePlacement spread, long worldSeed, ResourceLocation setId, int cellX, int cellZ) {
        SetCell key = new SetCell(worldSeed, setId, cellX, cellZ);
        Long cached = CANDIDATE_CACHE.get(key);
        if(cached != null) return cached;
        // getPotentialStructureChunk takes any chunk inside the cell, not the cell index.
        ChunkPos chunk = spread.getPotentialStructureChunk(worldSeed, cellX * spread.spacing(), cellZ * spread.spacing());
        int x = chunk.getMinBlockX() >> 4;
        int z = chunk.getMinBlockZ() >> 4;
        long result = spread.isStructureChunk(state, x, z) ? ((long) x << 32) | (z & 0xFFFFFFFFL) : NO_CANDIDATE;
        CANDIDATE_CACHE.put(key, result);
        if(CANDIDATE_CACHE.size() > CANDIDATE_CACHE_SOFT_CAP) CANDIDATE_CACHE.clear();
        return result;
    }

    //////////////////////////////
    // Resolution

    /** The structure vanilla generates for the set at this chunk, if one does. */
    public static Optional<ResolvedStructure> resolve(ServerLevel level, ResourceLocation setId, int chunkX, int chunkZ) {
        SetChunk key = new SetChunk(level.getSeed(), setId, chunkX, chunkZ);
        Optional<ResolvedStructure> cached = RESOLVE_CACHE.get(key);
        if(cached != null) return cached;
        Optional<ResolvedStructure> computed = generateGuarded(level, setId, chunkX, chunkZ);
        RESOLVE_CACHE.put(key, computed);
        if(RESOLVE_CACHE.size() > RESOLVE_CACHE_SOFT_CAP) RESOLVE_CACHE.clear();
        return computed;
    }

    private static final Object GENERATE_LOCK = new Object();
    private static final int GENERATE_ATTEMPTS = 3;

    // Structure generation fills lazy caches in vanilla's templates (StructureTemplate.Palette keeps
    // them in a plain HashMap), so two of our threads must never generate at once. A vanilla
    // world-gen thread filling the same cache can still race us; that throws, and a retry finds the
    // cache filled.
    private static Optional<ResolvedStructure> generateGuarded(ServerLevel level, ResourceLocation setId, int chunkX, int chunkZ) {
        synchronized(GENERATE_LOCK) {
            for(int attempt = 1; ; attempt++) {
                try {
                    return generate(level, setId, chunkX, chunkZ);
                } catch(java.util.ConcurrentModificationException ex) {
                    if(attempt >= GENERATE_ATTEMPTS) throw ex;
                }
            }
        }
    }

    // Mirrors ChunkGenerator#createStructures: one structure tried directly, several in a weighted
    // order drawn from the chunk's large-feature seed, the first that generates wins.
    private static Optional<ResolvedStructure> generate(ServerLevel level, ResourceLocation setId, int chunkX, int chunkZ) {
        Optional<StructureSet> set = MoogsPathsDatapackRegistries.vanillaStructureSet(level.registryAccess(), setId);
        if(set.isEmpty()) return Optional.empty();
        List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.get().structures());
        if(entries.isEmpty()) return Optional.empty();
        if(entries.size() == 1) return tryGenerate(level, entries.get(0), chunkX, chunkZ);

        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(level.getSeed(), chunkX, chunkZ);
        int totalWeight = 0;
        for(StructureSet.StructureSelectionEntry entry : entries) totalWeight += entry.weight();
        while(!entries.isEmpty()) {
            int roll = random.nextInt(totalWeight);
            int index = 0;
            for(StructureSet.StructureSelectionEntry entry : entries) {
                roll -= entry.weight();
                if(roll < 0) break;
                index++;
            }
            StructureSet.StructureSelectionEntry picked = entries.get(index);
            Optional<ResolvedStructure> generated = tryGenerate(level, picked, chunkX, chunkZ);
            if(generated.isPresent()) return generated;
            entries.remove(index);
            totalWeight -= picked.weight();
        }
        return Optional.empty();
    }

    private static Optional<ResolvedStructure> tryGenerate(ServerLevel level, StructureSet.StructureSelectionEntry entry, int chunkX, int chunkZ) {
        Holder<Structure> holder = entry.structure();
        Structure structure = holder.value();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        //? if <1.21.11 {
        StructureStart start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), randomState,
            level.getServer().getStructureManager(), level.getSeed(), chunk, 0, level, structure.biomes()::contains);
        //?} elif <26.3 {
        /*StructureStart start = structure.generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(), randomState,
            level.getServer().getStructureManager(), level.getSeed(), chunk, 0, level, structure.biomes()::contains);
        *///?} else {
        /*StructureStart start = structure.generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
            randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED), randomState,
            level.getServer().getStructureTemplateManager(), level.getSeed(), chunk, 0, level, structure.biomes()::contains);
        *///?}
        if(!start.isValid()) return Optional.empty();

        ResourceLocation id = holder.unwrapKey().map(MoogsPathsDatapackRegistries::keyId).orElse(null);
        if(id == null) return Optional.empty();
        List<PieceInfo> pieces = new ArrayList<>();
        for(StructurePiece piece : start.getPieces()) {
            if(piece instanceof PoolElementStructurePiece pool) {
                pieces.add(new PieceInfo(piece.getBoundingBox(), poolElementId(pool), pool.getPosition(), pool.getRotation()));
            } else {
                pieces.add(new PieceInfo(piece.getBoundingBox(), null, null, null));
            }
        }
        return Optional.of(new ResolvedStructure(id, start.getBoundingBox(), Collections.unmodifiableList(pieces)));
    }

    //////////////////////////////
    // Paths

    /**
     * Computes one path leading out of the structure at a structure chunk, or an empty list when no
     * structure of the right kind generates there. Runs the structure's generation, so keep it off
     * world-gen threads.
     */
    public static List<BlockPos> computePath(ServerLevel level, PathNetworkType network, ResourceLocation networkId, PathType pathType, int chunkX, int chunkZ, int pathIndex) {
        StructureOrigin origin = network.origin().orElse(null);
        if(origin == null || pathIndex >= origin.pathCount()) return List.of();
        Optional<ResolvedStructure> resolved = resolve(level, origin.structureSet(), chunkX, chunkZ);
        if(resolved.isEmpty()) return List.of();
        ResolvedStructure structure = resolved.get();
        if(origin.structure().isPresent() && !origin.structure().get().equals(structure.structure())) return List.of();

        long worldSeed = level.getSeed();
        long pathSeed = pathSeed(worldSeed, chunkX, chunkZ, networkId, pathIndex);
        RandomSource walkRandom = RandomSource.create(pathSeed ^ WALK_MIXER);
        int length = pathType.length().sample(walkRandom);

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        PathFinder.HeightSampler heights = (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        PathFinder.BiomeAccept biomes = networkBiomes(generator.getBiomeSource(), randomState, network);

        // Paths of one structure share a base heading and split the circle between them; the base
        // comes from a seed shared by all of them (index -1).
        double baseAngle = RandomSource.create(pathSeed(worldSeed, chunkX, chunkZ, networkId, -1)).nextDouble() * Math.PI * 2.0;
        double sector = Math.PI * 2.0 / origin.pathCount();
        double heading = baseAngle + sector * pathIndex;

        Optional<BlockPos> anchor = origin.anchor().map(a -> resolveAnchor(structure, a));
        // Without an anchor the path must not run back through the structure it leaves.
        int margin = pathType.width().max() + 4;
        PathFinder.BiomeAccept accept = anchor.isPresent() ? biomes : (x, z) -> !insidePieces(structure, x, z, margin) && biomes.test(x, z);

        BlockPos start = null;
        int goalX = 0, goalZ = 0;
        for(int attempt = 0; attempt < GOAL_ATTEMPTS + FALLBACK_HEADINGS; attempt++) {
            // First the planned heading, then wobbles within this path's sector, then (a structure
            // on a coast or a river bank) the rest of the circle before giving up.
            double angle = attempt == 0 ? heading
                : attempt < GOAL_ATTEMPTS ? heading + (walkRandom.nextDouble() - 0.5) * sector * 0.8
                : heading + Math.PI * 2.0 * (attempt - GOAL_ATTEMPTS + 1) / (FALLBACK_HEADINGS + 1);
            double dx = Math.cos(angle), dz = Math.sin(angle);
            BlockPos from = anchor.orElseGet(() -> edgeStart(structure, dx, dz, margin));
            int gx = from.getX() + (int) Math.round(dx * length);
            int gz = from.getZ() + (int) Math.round(dz * length);
            // A heading is good when its goal is in the network's biomes and its first steps out of
            // the structure are passable, so a path never starts facing into a lake or river.
            int stepX = from.getX() + (int) Math.round(dx * FIRST_STEP);
            int stepZ = from.getZ() + (int) Math.round(dz * FIRST_STEP);
            boolean good = biomes.test(gx, gz) && accept.test(stepX, stepZ);
            if(start == null || good) {
                start = from;
                goalX = gx;
                goalZ = gz;
            }
            if(good) break;
        }
        BlockPos startPos = new BlockPos(start.getX(), heights.sampleAt(start.getX(), start.getZ()), start.getZ());
        return PathFinder.findPathTo(startPos, goalX, goalZ, length, pathType, heights, accept);
    }

    // The network's biomes, less moogs_paths:has_no_paths, as the path finder's gate.
    private static PathFinder.BiomeAccept networkBiomes(BiomeSource biomeSource, RandomState randomState, PathNetworkType network) {
        int quartY = QuartPos.fromBlock(BIOME_Y);
        //? if >=26.3 {
        /*BiomeResolver resolver = biomeSource.createUncachedResolver(randomState);
        return (x, z) -> {
            Holder<Biome> b = resolver.getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z));
            return network.biomes().contains(b) && !b.is(PathChunkFeature.HAS_NO_PATHS);
        };
        *///?} else {
        Climate.Sampler sampler = randomState.sampler();
        return (x, z) -> {
            Holder<Biome> b = biomeSource.getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z), sampler);
            return network.biomes().contains(b) && !b.is(PathChunkFeature.HAS_NO_PATHS);
        };
        //?}
    }

    // Starts from the piece reaching furthest along the heading, then walks out along it until clear
    // of every piece, so the path begins at the structure's edge on the side it leaves from.
    private static BlockPos edgeStart(ResolvedStructure structure, double dx, double dz, int margin) {
        BoundingBox bounds = structure.bounds();
        double cx = (bounds.minX() + bounds.maxX()) / 2.0;
        double cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
        double fromX = cx, fromZ = cz, best = Double.NEGATIVE_INFINITY;
        for(PieceInfo piece : structure.pieces()) {
            double px = (piece.box().minX() + piece.box().maxX()) / 2.0;
            double pz = (piece.box().minZ() + piece.box().maxZ()) / 2.0;
            double along = (px - cx) * dx + (pz - cz) * dz;
            if(along > best) {
                best = along;
                fromX = px;
                fromZ = pz;
            }
        }
        for(int step = 0; step < MAX_START_STEPS; step++) {
            int x = (int) Math.round(fromX + dx * step);
            int z = (int) Math.round(fromZ + dz * step);
            if(!insidePieces(structure, x, z, margin)) return new BlockPos(x, 0, z);
        }
        return new BlockPos((int) Math.round(fromX + dx * MAX_START_STEPS), 0, (int) Math.round(fromZ + dz * MAX_START_STEPS));
    }

    private static boolean insidePieces(ResolvedStructure structure, int x, int z, int margin) {
        BoundingBox b = structure.bounds();
        if(x < b.minX() - margin || x > b.maxX() + margin || z < b.minZ() - margin || z > b.maxZ() + margin) return false;
        for(PieceInfo piece : structure.pieces()) {
            BoundingBox p = piece.box();
            if(x >= p.minX() - margin && x <= p.maxX() + margin && z >= p.minZ() - margin && z <= p.maxZ() + margin) return true;
        }
        return false;
    }

    //////////////////////////////
    // Anchors

    /**
     * An explicit anchor's world position: the matched piece (or the start piece) and, with
     * {@code local_pos}, that position inside the piece's NBT turned by the piece's rotation. Falls
     * back to the piece's or the structure's centre, warning once, when the piece or its NBT is missing.
     */
    private static BlockPos resolveAnchor(ResolvedStructure structure, StructureOrigin.Anchor anchor) {
        PieceInfo target = null;
        if(anchor.piece().isPresent()) {
            int seen = 0;
            for(PieceInfo piece : structure.pieces()) {
                if(anchor.piece().get().equals(piece.element()) && seen++ == anchor.pieceIndex()) {
                    target = piece;
                    break;
                }
            }
            if(target == null) {
                PathDataManager.warnMissingOnce("Structure piece for a path anchor (using the structure's centre)", anchor.piece().get());
                return structure.bounds().getCenter();
            }
        } else if(!structure.pieces().isEmpty()) {
            target = structure.pieces().get(0);
        }
        if(target == null) return structure.bounds().getCenter();
        if(anchor.localPos().isPresent()) {
            if(target.position() != null) {
                BlockPos local = BlockPos.ZERO.offset(anchor.localPos().get());
                return target.position().offset(StructureTemplate.transform(local, Mirror.NONE, target.rotation(), BlockPos.ZERO));
            }
            PathDataManager.warnMissingOnce("NBT for a path anchor's local_pos (using the piece's centre)", structure.structure());
        }
        return target.box().getCenter();
    }

    // SinglePoolElement keeps its NBT id in a private Either<ResourceLocation, StructureTemplate> with
    // no getter. Found by type, which no mapping renames, so no access widener or transformer is needed.
    private static volatile Field templateField;
    private static volatile boolean templateFieldLooked;

    private static ResourceLocation poolElementId(PoolElementStructurePiece piece) {
        if(!(piece.getElement() instanceof SinglePoolElement single)) return null;
        Field field = templateField();
        if(field == null) return null;
        try {
            if(field.get(single) instanceof Either<?, ?> either && either.left().orElse(null) instanceof ResourceLocation id) return id;
        } catch(ReflectiveOperationException ignored) {
            // no match
        }
        return null;
    }

    private static Field templateField() {
        if(templateFieldLooked) return templateField;
        synchronized(StructureAnchors.class) {
            if(!templateFieldLooked) {
                for(Field f : SinglePoolElement.class.getDeclaredFields()) {
                    if(Either.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        templateField = f;
                        break;
                    }
                }
                if(templateField == null) Constants.LOG.warn("Moog's Paths could not find SinglePoolElement's template; path anchors by piece fall back to the structure's centre");
                templateFieldLooked = true;
            }
        }
        return templateField;
    }
}
