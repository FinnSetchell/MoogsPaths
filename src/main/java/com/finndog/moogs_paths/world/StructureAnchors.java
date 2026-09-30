package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.data.StructureOrigin;
import com.finndog.moogs_paths.world.deferred.DeferredPathJob;
import com.finndog.moogs_paths.world.deferred.PlacementTickPump;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
//? if <1.21.11 {
import net.minecraft.core.registries.Registries;
//?}
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
//? if >=26.3 {
/*import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
*///?}
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
//? if >=1.21.11 {
/*import net.minecraft.world.level.chunk.PalettedContainerFactory;
*///?}
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
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
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Turns a {@link StructureOrigin} into paths that lead away from real structures.
 *
 * <p>Any chunk has to arrive at the same paths without reading generated chunks, so a structure is
 * found the way vanilla places it:
 * <ol>
 *   <li>{@link #candidatesInRange} repeats vanilla's random-spread grid maths and its own
 *       {@link StructurePlacement#isStructureChunk} check (frequency and exclusion zones included).
 *       Cheap, and safe on world-gen threads.</li>
 *   <li>{@link #resolve} repeats vanilla's pick between the set's structures for that chunk and starts
 *       each through the chunk generator as vanilla does, which checks biomes and terrain and lets other
 *       mods cancel it. Expensive, so it only runs on the pathfinding workers and in commands, and is
 *       cached per structure.</li>
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
    // A path out of an anchor runs straight for at least this many blocks before turning.
    private static final int MIN_LEAD = 4;
    // An anchor without a facing tries this many ways out; turning away from the path's heading
    // costs up to EXIT_TURN_COST blocks of the way out.
    private static final int EXIT_DIRECTIONS = 16;
    private static final double EXIT_TURN_COST = 8.0;
    // A door path's goal wobbles at most this far either side of the way the door faces.
    private static final double FACING_SPREAD = Math.PI / 2;
    // A piece picked by heading lies at most this many blocks back from the structure's edge that way.
    private static final double ANCHOR_DEPTH = 32.0;

    /** A chunk where the structure set places a structure (before biomes are checked). */
    public record StructureChunk(int x, int z) {}

    /**
     * A placed piece: its box, its NBT id when it has one, and how a position inside it maps to the
     * world. Pieces placed from an NBT (jigsaw and template pieces) carry the template's origin and
     * placement; pieces built in code carry their orientation, as {@link StructurePiece} uses it.
     */
    private record PieceInfo(BoundingBox box, ResourceLocation element, BlockPos origin, Rotation rotation, Mirror mirror, BlockPos pivot, Direction orientation) {}

    /** A structure that really generates at a candidate chunk. */
    public record ResolvedStructure(ResourceLocation structure, Holder<Structure> holder, BoundingBox bounds, List<PieceInfo> pieces) {
        /** Whether this is a structure the origin anchors to. */
        public boolean matches(StructureOrigin origin) {
            return origin.matches(holder, structure);
        }
    }

    /** A loaded random-spread structure set an origin names. */
    private record AnchorSet(ResourceLocation id, RandomSpreadStructurePlacement spread) {}

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

    // The origin's sets that are loaded and can be anchored to. A set that isn't loaded is skipped
    // quietly (MoogsPathsDatapackRegistries warns when none of a network's sets is).
    private static List<AnchorSet> anchorSets(RegistryAccess access, StructureOrigin origin) {
        List<AnchorSet> sets = new ArrayList<>(origin.structureSets().size());
        for(ResourceLocation id : origin.structureSets()) {
            Optional<StructureSet> set = MoogsPathsDatapackRegistries.vanillaStructureSet(access, id);
            if(set.isEmpty()) continue;
            if(!(set.get().placement() instanceof RandomSpreadStructurePlacement spread)) {
                // Concentric rings (strongholds) have no per-chunk grid to walk.
                PathDataManager.warnMissingOnce("Structure set without random_spread placement (cannot anchor paths)", id);
                continue;
            }
            sets.add(new AnchorSet(id, spread));
        }
        return sets;
    }

    /**
     * Chunks within {@code blockRadius} of the given chunk where the origin's structure sets place a
     * structure, per vanilla's placement rules. Only random-spread sets can be anchored to.
     */
    public static List<StructureChunk> candidatesInRange(ServerLevel level, StructureOrigin origin, int chunkX, int chunkZ, int blockRadius) {
        List<AnchorSet> sets = anchorSets(level.registryAccess(), origin);
        if(sets.isEmpty()) return List.of();

        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        long worldSeed = level.getSeed();
        int chunkRadius = (blockRadius + 15) >> 4;
        long radiusSq = (long) blockRadius * blockRadius;
        long centerX = ((long) chunkX << 4) + 8;
        long centerZ = ((long) chunkZ << 4) + 8;

        // Two sets can place in one chunk; it still holds one set of paths.
        Set<StructureChunk> result = new LinkedHashSet<>();
        for(AnchorSet set : sets) {
            int spacing = set.spread().spacing();
            // One cell of margin: a cell's structure chunk can sit anywhere inside the cell.
            int minCellX = Math.floorDiv(chunkX - chunkRadius, spacing) - 1;
            int maxCellX = Math.floorDiv(chunkX + chunkRadius, spacing) + 1;
            int minCellZ = Math.floorDiv(chunkZ - chunkRadius, spacing) - 1;
            int maxCellZ = Math.floorDiv(chunkZ + chunkRadius, spacing) + 1;
            for(int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for(int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    long packed = placedChunkInCell(state, set, worldSeed, cellX, cellZ);
                    if(packed == NO_CANDIDATE) continue;
                    int cx = (int) (packed >> 32);
                    int cz = (int) packed;
                    long dx = ((long) cx << 4) + 8 - centerX;
                    long dz = ((long) cz << 4) + 8 - centerZ;
                    if(dx * dx + dz * dz <= radiusSq) result.add(new StructureChunk(cx, cz));
                }
            }
        }
        return new ArrayList<>(result);
    }

    // The chunk a grid cell places its structure in, or NO_CANDIDATE when the placement's frequency
    // or exclusion zone rules it out.
    private static long placedChunkInCell(ChunkGeneratorStructureState state, AnchorSet set, long worldSeed, int cellX, int cellZ) {
        SetCell key = new SetCell(worldSeed, set.id(), cellX, cellZ);
        Long cached = CANDIDATE_CACHE.get(key);
        if(cached != null) return cached;
        RandomSpreadStructurePlacement spread = set.spread();
        // getPotentialStructureChunk takes any chunk inside the cell, not the cell index.
        ChunkPos chunk = spread.getPotentialStructureChunk(worldSeed, cellX * spread.spacing(), cellZ * spread.spacing());
        int x = chunk.getMinBlockX() >> 4;
        int z = chunk.getMinBlockZ() >> 4;
        long result = spread.isStructureChunk(state, x, z) ? ((long) x << 32) | (z & 0xFFFFFFFFL) : NO_CANDIDATE;
        CANDIDATE_CACHE.put(key, result);
        if(CANDIDATE_CACHE.size() > CANDIDATE_CACHE_SOFT_CAP) CANDIDATE_CACHE.clear();
        return result;
    }

    // Whether the set places its structure in this chunk. A cell's structure chunk lies inside the cell.
    private static boolean placesAt(ChunkGeneratorStructureState state, AnchorSet set, long worldSeed, int chunkX, int chunkZ) {
        int spacing = set.spread().spacing();
        long packed = placedChunkInCell(state, set, worldSeed, Math.floorDiv(chunkX, spacing), Math.floorDiv(chunkZ, spacing));
        return packed == (((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    //////////////////////////////
    // Resolution

    private static final int[][] HOLD_SAMPLES = {{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}};
    // Fixed heights rather than the surface: a height lookup builds a whole noise column, which cost
    // more than it saved. Above the ground a sample gives the surface biome, so the high ones cover
    // tall terrain.
    private static final int[] HOLD_SAMPLE_Y = {64, 96, 128, 192};

    /**
     * Whether one of the origin's structures can start in this chunk's biome: a quick check before
     * {@link #resolve}, which generates the whole structure. Vanilla only starts a structure where
     * its biome allows, so a chunk none of whose samples (centre and corners, several heights) is
     * in those biomes can't hold one. Sampling can in principle miss a biome boundary, so this is for
     * searches like /paths locate, not for world generation.
     */
    public static boolean mayHold(ServerLevel level, StructureOrigin origin, int chunkX, int chunkZ) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        List<Structure> allowed = new ArrayList<>();
        for(AnchorSet set : anchorSets(level.registryAccess(), origin)) {
            if(!placesAt(state, set, level.getSeed(), chunkX, chunkZ)) continue;
            Optional<StructureSet> structureSet = MoogsPathsDatapackRegistries.vanillaStructureSet(level.registryAccess(), set.id());
            if(structureSet.isEmpty()) continue;
            for(StructureSet.StructureSelectionEntry entry : structureSet.get().structures()) {
                ResourceLocation id = entry.structure().unwrapKey().map(MoogsPathsDatapackRegistries::keyId).orElse(null);
                if(origin.matches(entry.structure(), id)) allowed.add(entry.structure().value());
            }
        }
        if(allowed.isEmpty()) return false;

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        //? if >=26.3 {
        /*BiomeResolver resolver = generator.getBiomeSource().createUncachedResolver(randomState);
        *///?} else {
        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();
        //?}
        for(int[] sample : HOLD_SAMPLES) {
            for(int y : HOLD_SAMPLE_Y) {
                int qx = QuartPos.fromBlock(minX + sample[0]);
                int qy = QuartPos.fromBlock(y);
                int qz = QuartPos.fromBlock(minZ + sample[1]);
                //? if >=26.3 {
                /*Holder<Biome> biome = resolver.getNoiseBiome(qx, qy, qz);
                *///?} else {
                Holder<Biome> biome = biomeSource.getNoiseBiome(qx, qy, qz, sampler);
                //?}
                for(Structure structure : allowed) {
                    if(structure.biomes().contains(biome)) return true;
                }
            }
        }
        return false;
    }

    /**
     * The structure the origin's sets generate at this chunk, if any does: one the origin anchors to
     * when there is one (check with {@link ResolvedStructure#matches}), else whatever generated there.
     */
    public static Optional<ResolvedStructure> resolve(ServerLevel level, StructureOrigin origin, int chunkX, int chunkZ) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        Optional<ResolvedStructure> other = Optional.empty();
        for(AnchorSet set : anchorSets(level.registryAccess(), origin)) {
            if(!placesAt(state, set, level.getSeed(), chunkX, chunkZ)) continue;
            Optional<ResolvedStructure> resolved = resolve(level, set.id(), chunkX, chunkZ);
            if(resolved.isEmpty()) continue;
            if(resolved.get().matches(origin)) return resolved;
            if(other.isEmpty()) other = resolved;
        }
        return other;
    }

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

    private static final ReentrantLock GENERATE_LOCK = new ReentrantLock();
    // Server-thread callers (/paths locate) waiting for the lock. Path workers step aside while there
    // are any: the server thread may need the lock dozens of times in one command, and queueing
    // behind every busy worker each time froze the server for minutes.
    private static final AtomicInteger PRIORITY_WAITERS = new AtomicInteger();
    private static final int GENERATE_ATTEMPTS = 3;

    private static void lockGenerate(ServerLevel level) {
        if(level.getServer().isSameThread()) {
            PRIORITY_WAITERS.incrementAndGet();
            try {
                GENERATE_LOCK.lock();
            } finally {
                PRIORITY_WAITERS.decrementAndGet();
            }
            return;
        }
        while(true) {
            GENERATE_LOCK.lock();
            if(PRIORITY_WAITERS.get() == 0) return;
            GENERATE_LOCK.unlock();
            LockSupport.parkNanos(1_000_000L);
        }
    }

    // Structure generation fills lazy caches in vanilla's templates (StructureTemplate.Palette keeps
    // them in a plain HashMap), so two of our threads must never generate at once. A vanilla
    // world-gen thread filling the same cache can still race us; that throws, and a retry finds the
    // cache filled.
    private static Optional<ResolvedStructure> generateGuarded(ServerLevel level, ResourceLocation setId, int chunkX, int chunkZ) {
        lockGenerate(level);
        try {
            for(int attempt = 1; ; attempt++) {
                try {
                    return generate(level, setId, chunkX, chunkZ);
                } catch(java.util.ConcurrentModificationException ex) {
                    if(attempt >= GENERATE_ATTEMPTS) throw ex;
                }
            }
        } finally {
            GENERATE_LOCK.unlock();
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
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        Method method = tryGenerateMethod();
        StructureStart start = method != null ? startThroughGenerator(level, method, entry, chunk) : startDirectly(level, holder, chunk);
        if(start == null || !start.isValid()) return Optional.empty();

        ResourceLocation id = holder.unwrapKey().map(MoogsPathsDatapackRegistries::keyId).orElse(null);
        if(id == null) return Optional.empty();
        List<PieceInfo> pieces = new ArrayList<>();
        for(StructurePiece piece : start.getPieces()) pieces.add(pieceInfo(piece));
        return Optional.of(new ResolvedStructure(id, holder, start.getBoundingBox(), Collections.unmodifiableList(pieces)));
    }

    private static PieceInfo pieceInfo(StructurePiece piece) {
        BoundingBox box = piece.getBoundingBox();
        if(piece instanceof PoolElementStructurePiece pool) {
            return new PieceInfo(box, poolElementId(pool), pool.getPosition(), pool.getRotation(), Mirror.NONE, BlockPos.ZERO, null);
        }
        if(piece instanceof TemplateStructurePiece template) {
            StructurePlaceSettings settings = template.placeSettings();
            return new PieceInfo(box, templateId(template), template.templatePosition(), settings.getRotation(), settings.getMirror(), settings.getRotationPivot(), null);
        }
        return new PieceInfo(box, null, null, Rotation.NONE, Mirror.NONE, BlockPos.ZERO, piece.getOrientation());
    }

    //////////////////////////////
    // Starting a structure through the chunk generator

    // Vanilla starts every structure through ChunkGenerator's private tryGenerateStructure, and other
    // mods hook it to turn one off: YUNG's Better Jungle Temples and Better Witch Huts cancel the
    // vanilla temple and hut there. Predicting a structure through the same method, on a scratch
    // chunk, sees every such cancel. The method is found by its parameter types, which no mapping
    // renames, and handed its arguments by type too, since later versions added some.
    private static final Set<Class<?>> TRY_GENERATE_PARAMETERS = Set.of(
        StructureSet.StructureSelectionEntry.class, StructureManager.class, RegistryAccess.class, RandomState.class,
        StructureTemplateManager.class, long.class, ChunkAccess.class, ChunkPos.class, SectionPos.class, ResourceKey.class,
        Climate.Sampler.class);
    private static volatile Method tryGenerateMethod;
    private static volatile boolean tryGenerateLooked;

    private static Method tryGenerateMethod() {
        if(tryGenerateLooked) return tryGenerateMethod;
        synchronized(StructureAnchors.class) {
            if(!tryGenerateLooked) {
                for(Method m : ChunkGenerator.class.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if(m.getReturnType() != boolean.class || Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) continue;
                    if(params.length == 0 || params[0] != StructureSet.StructureSelectionEntry.class) continue;
                    boolean known = true;
                    for(Class<?> param : params) known &= TRY_GENERATE_PARAMETERS.contains(param);
                    if(!known) continue;
                    try {
                        m.setAccessible(true);
                        tryGenerateMethod = m;
                    } catch(RuntimeException ex) {
                        // A module that won't open it; the warning below covers it.
                    }
                    break;
                }
                if(tryGenerateMethod == null) Constants.LOG.warn("Moog's Paths could not find ChunkGenerator's tryGenerateStructure; paths may start at structures another mod turned off");
                tryGenerateLooked = true;
            }
        }
        return tryGenerateMethod;
    }

    private static StructureStart startThroughGenerator(ServerLevel level, Method method, StructureSet.StructureSelectionEntry entry, ChunkPos chunk) {
        ProtoChunk scratch = scratchChunk(level, chunk);
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        for(int i = 0; i < params.length; i++) args[i] = tryGenerateArgument(params[i], level, entry, scratch, chunk);
        try {
            method.invoke(level.getChunkSource().getGenerator(), args);
        } catch(InvocationTargetException ex) {
            // Rethrown as is, so a ConcurrentModificationException still gets its retry.
            if(ex.getCause() instanceof RuntimeException runtime) throw runtime;
            if(ex.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(ex.getCause());
        } catch(IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
        // A cancelled or failed start leaves nothing in the chunk.
        return scratch.getStartForStructure(entry.structure().value());
    }

    private static Object tryGenerateArgument(Class<?> type, ServerLevel level, StructureSet.StructureSelectionEntry entry, ProtoChunk scratch, ChunkPos chunk) {
        if(type == StructureSet.StructureSelectionEntry.class) return entry;
        if(type == StructureManager.class) return level.structureManager();
        if(type == RegistryAccess.class) return level.registryAccess();
        if(type == RandomState.class) return level.getChunkSource().randomState();
        if(type == StructureTemplateManager.class) return templateManager(level);
        if(type == long.class) return level.getSeed();
        if(type == ChunkAccess.class) return scratch;
        if(type == ChunkPos.class) return chunk;
        if(type == SectionPos.class) return SectionPos.bottomOf(scratch);
        if(type == ResourceKey.class) return level.dimension();
        if(type == Climate.Sampler.class) return climateSampler(level.getChunkSource().randomState());
        throw new IllegalArgumentException("Unexpected tryGenerateStructure parameter " + type.getName());
    }

    // A chunk that only ever holds the structure start: nothing reads or saves it.
    private static ProtoChunk scratchChunk(ServerLevel level, ChunkPos chunk) {
        //? if <1.21.11 {
        return new ProtoChunk(chunk, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        //?} else {
        /*return new ProtoChunk(chunk, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        *///?}
    }

    // Without tryGenerateStructure (never seen, but a mod could in principle strip it), the structure
    // is generated directly, which misses other mods' cancels.
    private static StructureStart startDirectly(ServerLevel level, Holder<Structure> holder, ChunkPos chunk) {
        Structure structure = holder.value();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        //? if <1.21.11 {
        return structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), randomState,
            templateManager(level), level.getSeed(), chunk, 0, level, structure.biomes()::contains);
        //?} elif <26.3 {
        /*return structure.generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(), randomState,
            templateManager(level), level.getSeed(), chunk, 0, level, structure.biomes()::contains);
        *///?} else {
        /*return structure.generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
            climateSampler(randomState), randomState, templateManager(level), level.getSeed(), chunk, 0, level, structure.biomes()::contains);
        *///?}
    }

    private static StructureTemplateManager templateManager(ServerLevel level) {
        //? if <26.3 {
        return level.getServer().getStructureManager();
        //?} else {
        /*return level.getServer().getStructureTemplateManager();
        *///?}
    }

    // 26.3 dropped RandomState#sampler; a sampler is built per use.
    private static Climate.Sampler climateSampler(RandomState randomState) {
        //? if >=26.3 {
        /*return randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        *///?} else {
        return randomState.sampler();
        //?}
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
        Optional<ResolvedStructure> resolved = resolve(level, origin, chunkX, chunkZ);
        if(resolved.isEmpty() || !resolved.get().matches(origin)) return List.of();
        ResolvedStructure structure = resolved.get();

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
        StructureOrigin.Anchor anchor = origin.anchor().orElse(null);
        boolean facing = anchor != null && anchor.facing().isPresent();
        if(facing) {
            // A door's first path heads the way the door faces.
            Spot door = anchorSpot(structure, anchor, Math.cos(baseAngle), Math.sin(baseAngle));
            if(door != null) baseAngle = Math.atan2(door.exitZ(), door.exitX());
        }
        double heading = baseAngle + sector * pathIndex;
        double spread = facing ? Math.min(sector, FACING_SPREAD) : sector;

        // A path never runs back through the structure it leaves: one from an anchor only crosses it
        // on its straight way out.
        int margin = pathType.width().max() + 4;
        PathFinder.BiomeAccept accept = (x, z) -> !insidePieces(structure, x, z, margin) && biomes.test(x, z);

        BlockPos start = null;
        List<BlockPos> lead = List.of();
        int goalX = 0, goalZ = 0;
        for(int attempt = 0; attempt < GOAL_ATTEMPTS + FALLBACK_HEADINGS; attempt++) {
            // First the planned heading, then wobbles within this path's sector, then (a structure
            // on a coast or a river bank) the rest of the circle before giving up.
            double angle = attempt == 0 ? heading
                : attempt < GOAL_ATTEMPTS ? heading + (walkRandom.nextDouble() - 0.5) * spread * 0.8
                : heading + Math.PI * 2.0 * (attempt - GOAL_ATTEMPTS + 1) / (FALLBACK_HEADINGS + 1);
            double dx = Math.cos(angle), dz = Math.sin(angle);
            Spot spot = anchor != null ? anchorSpot(structure, anchor, dx, dz) : null;
            List<BlockPos> way = spot != null ? leadOut(structure, spot, dx, dz, margin) : List.of();
            BlockPos from = spot != null ? way.get(way.size() - 1) : edgeStart(structure, dx, dz, margin);
            int gx = from.getX() + (int) Math.round(dx * length);
            int gz = from.getZ() + (int) Math.round(dz * length);
            // A heading is good when its goal is in the network's biomes and its first steps out of
            // the structure are passable, so a path never starts facing into a lake or river.
            int stepX = from.getX() + (int) Math.round(dx * FIRST_STEP);
            int stepZ = from.getZ() + (int) Math.round(dz * FIRST_STEP);
            boolean good = biomes.test(gx, gz) && accept.test(stepX, stepZ);
            if(start == null || good) {
                start = from;
                lead = way;
                goalX = gx;
                goalZ = gz;
            }
            if(good) break;
        }
        BlockPos startPos = new BlockPos(start.getX(), heights.sampleAt(start.getX(), start.getZ()), start.getZ());
        List<BlockPos> path = PathFinder.findPathTo(startPos, goalX, goalZ, length, pathType, heights, accept);
        // A lead with nowhere to go is not a path.
        if(lead.isEmpty() || path.size() < 2) return path;
        return withLead(lead, path, heights);
    }

    /**
     * Every path leading out of the structure at this chunk, computed now if need be and queued for
     * placement. Empty when no structure of the network's kind generates there. For searches on the
     * server thread (/paths locate and the locate API), not for world generation.
     */
    public static List<PathDataManager.CachedPath> pathsAt(ServerLevel level, MoogsPathsDatapackRegistries.AnchoredNetwork anchored, StructureChunk chunk) {
        PathNetworkType network = anchored.network();
        Optional<PathType> pathType = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
        if(pathType.isEmpty()) return List.of();
        List<PathDataManager.CachedPath> paths = new ArrayList<>();
        int pathCount = network.origin().orElseThrow().pathCount();
        for(int i = 0; i < pathCount; i++) {
            int pathIndex = i;
            long pathSeed = pathSeed(level.getSeed(), chunk.x(), chunk.z(), anchored.id(), pathIndex);
            if(PathDataManager.isRejected(pathSeed)) continue;
            PathDataManager.CachedPath path = PathDataManager.getOrComputeWaypoints(pathSeed, () -> computePath(
                level, network, anchored.id(), pathType.get(), chunk.x(), chunk.z(), pathIndex));
            if(path.waypointCount() < 2) {
                PathDataManager.markRejected(pathSeed);
                continue;
            }
            PlacementTickPump.enqueueFromWorldgen(level, DeferredPathJob.anchored(pathSeed, chunk.x(), chunk.z(), pathIndex, anchored.id()));
            paths.add(path);
        }
        return paths;
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

    /** Where an anchor starts a path, and the way it leaves (a unit step, or 0, 0 for no facing). */
    private record Spot(PieceInfo piece, BlockPos pos, int exitX, int exitZ) {}

    /**
     * The spot an anchor starts a path heading (dx, dz) from: the matched piece (or the start piece)
     * and, with {@code local_pos}, that position inside it. Null when no piece matches, and the path
     * then starts as it would without an anchor.
     */
    private static Spot anchorSpot(ResolvedStructure structure, StructureOrigin.Anchor anchor, double dx, double dz) {
        PieceInfo piece = anchorPiece(structure, anchor, dx, dz);
        if(piece == null) return null;
        BlockPos pos = anchor.localPos().map(local -> localToWorld(piece, local)).orElseGet(() -> piece.box().getCenter());
        int[] exit = anchor.facing().map(side -> facingToWorld(piece, side)).orElse(new int[]{0, 0});
        return new Spot(piece, pos, exit[0], exit[1]);
    }

    // The piece an anchor names: with several matches the one furthest along the heading, unless
    // piece_index pins one. The furthest match can still lie deep inside the structure (a village whose
    // only street ends are inner ones); the path then starts at the structure's edge instead.
    private static PieceInfo anchorPiece(ResolvedStructure structure, StructureOrigin.Anchor anchor, double dx, double dz) {
        if(anchor.piece().isEmpty()) return structure.pieces().isEmpty() ? null : structure.pieces().get(0);
        BoundingBox bounds = structure.bounds();
        double cx = (bounds.minX() + bounds.maxX()) / 2.0;
        double cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
        int index = anchor.pieceIndex().orElse(-1);
        int seen = 0;
        PieceInfo best = null;
        double bestAlong = Double.NEGATIVE_INFINITY;
        double edge = Double.NEGATIVE_INFINITY;
        for(PieceInfo piece : structure.pieces()) {
            BoundingBox box = piece.box();
            double along = ((box.minX() + box.maxX()) / 2.0 - cx) * dx + ((box.minZ() + box.maxZ()) / 2.0 - cz) * dz;
            edge = Math.max(edge, along + (box.maxX() - box.minX()) / 2.0 * Math.abs(dx) + (box.maxZ() - box.minZ()) / 2.0 * Math.abs(dz));
            if(!anchor.matchesPiece(piece.element())) continue;
            if(index >= 0) {
                if(seen++ == index) return piece;
                continue;
            }
            if(along > bestAlong) {
                bestAlong = along;
                best = piece;
            }
        }
        return best != null && bestAlong >= edge - ANCHOR_DEPTH ? best : null;
    }

    // A position inside a piece, the way the piece itself places blocks: through its template's
    // placement for an NBT piece, and as StructurePiece#getWorldX/getWorldZ do for one built in code.
    private static BlockPos localToWorld(PieceInfo piece, Vec3i local) {
        if(piece.origin() != null) {
            BlockPos pos = new BlockPos(local.getX(), local.getY(), local.getZ());
            return piece.origin().offset(StructureTemplate.transform(pos, piece.mirror(), piece.rotation(), piece.pivot()));
        }
        BoundingBox box = piece.box();
        int x = local.getX(), z = local.getZ();
        Direction orientation = piece.orientation();
        if(orientation == null) return new BlockPos(box.minX() + x, box.minY() + local.getY(), box.minZ() + z);
        return switch(orientation) {
            case NORTH -> new BlockPos(box.minX() + x, box.minY() + local.getY(), box.maxZ() - z);
            case SOUTH -> new BlockPos(box.minX() + x, box.minY() + local.getY(), box.minZ() + z);
            case WEST -> new BlockPos(box.maxX() - z, box.minY() + local.getY(), box.minZ() + x);
            default -> new BlockPos(box.minX() + z, box.minY() + local.getY(), box.minZ() + x);
        };
    }

    // A side of a piece, in the frame local_pos uses, as a world step.
    private static int[] facingToWorld(PieceInfo piece, Direction side) {
        if(piece.origin() != null) {
            Direction world = piece.rotation().rotate(piece.mirror().mirror(side));
            return new int[]{world.getStepX(), world.getStepZ()};
        }
        int x = side.getStepX(), z = side.getStepZ();
        Direction orientation = piece.orientation();
        if(orientation == null) return new int[]{x, z};
        return switch(orientation) {
            case NORTH -> new int[]{x, -z};
            case SOUTH -> new int[]{x, z};
            case WEST -> new int[]{-z, x};
            default -> new int[]{z, x};
        };
    }

    /**
     * The straight way out from an anchor: out of its facing side when it has one, else the shortest
     * way clear of the structure, preferring the path's heading, so a street end's road carries on
     * outward. Runs until clear of every piece (and at least {@link #MIN_LEAD} blocks), so the path
     * that follows never needs to cross the structure.
     */
    private static List<BlockPos> leadOut(ResolvedStructure structure, Spot spot, double dx, double dz, int margin) {
        double ex = spot.exitX(), ez = spot.exitZ();
        if(ex == 0 && ez == 0) {
            double headingAngle = Math.atan2(dz, dx);
            double bestScore = Double.POSITIVE_INFINITY;
            for(int i = 0; i <= EXIT_DIRECTIONS; i++) {
                // i == EXIT_DIRECTIONS is the heading itself.
                double turn = i == EXIT_DIRECTIONS ? 0 : Math.PI * 2.0 * i / EXIT_DIRECTIONS - Math.PI;
                double cx = Math.cos(headingAngle + turn), cz = Math.sin(headingAngle + turn);
                double score = leadLength(structure, spot.pos(), cx, cz, margin) + EXIT_TURN_COST * Math.abs(turn) / Math.PI;
                if(score < bestScore) {
                    bestScore = score;
                    ex = cx;
                    ez = cz;
                }
            }
        }
        int steps = leadLength(structure, spot.pos(), ex, ez, margin);
        List<BlockPos> way = new ArrayList<>(steps + 1);
        for(int step = 0; step <= steps; step++) {
            BlockPos pos = new BlockPos((int) Math.round(spot.pos().getX() + ex * step), 0, (int) Math.round(spot.pos().getZ() + ez * step));
            if(way.isEmpty() || !way.get(way.size() - 1).equals(pos)) way.add(pos);
        }
        return way;
    }

    private static int leadLength(ResolvedStructure structure, BlockPos from, double ex, double ez, int margin) {
        for(int step = MIN_LEAD; step < MAX_START_STEPS; step++) {
            if(!insidePieces(structure, (int) Math.round(from.getX() + ex * step), (int) Math.round(from.getZ() + ez * step), margin)) return step;
        }
        return MAX_START_STEPS;
    }

    // The lead, then the path finder's path. The path finder starts on its 4-block grid, a little
    // off the lead's end, so the gap is filled block by block.
    private static List<BlockPos> withLead(List<BlockPos> lead, List<BlockPos> path, PathFinder.HeightSampler heights) {
        List<BlockPos> joined = new ArrayList<>(lead.size() + path.size() + 4);
        for(BlockPos pos : lead) joined.add(new BlockPos(pos.getX(), heights.sampleAt(pos.getX(), pos.getZ()), pos.getZ()));
        BlockPos last = lead.get(lead.size() - 1);
        BlockPos first = path.get(0);
        PathGeometryUtils.bresenham(last.getX(), last.getZ(), first.getX(), first.getZ(), (x, z) -> {
            if((x == last.getX() && z == last.getZ()) || (x == first.getX() && z == first.getZ())) return;
            joined.add(new BlockPos(x, heights.sampleAt(x, z), z));
        });
        joined.addAll(path);
        return joined;
    }

    /** What an origin's anchor picks in a structure, for /paths debug anchors. */
    public static String describeAnchor(ResolvedStructure structure, StructureOrigin origin) {
        StructureOrigin.Anchor anchor = origin.anchor().orElse(null);
        if(anchor == null) return "starts at its edge";
        if(anchor.piece().isEmpty()) return "anchored to its start piece";
        int matches = 0;
        for(PieceInfo piece : structure.pieces()) {
            if(anchor.matchesPiece(piece.element())) matches++;
        }
        if(matches == 0) return "no piece matches the anchor, starts at its edge";
        return "anchored to " + (anchor.pieceIndex().isPresent() ? "piece " + anchor.pieceIndex().get() + " of " : "") + matches + " matching piece(s)";
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

    // TemplateStructurePiece keeps its NBT id in a protected String, its only one. Found by type too.
    private static volatile Field templateNameField;
    private static volatile boolean templateNameLooked;

    private static ResourceLocation templateId(TemplateStructurePiece piece) {
        if(!templateNameLooked) {
            synchronized(StructureAnchors.class) {
                if(!templateNameLooked) {
                    for(Field f : TemplateStructurePiece.class.getDeclaredFields()) {
                        if(f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                            f.setAccessible(true);
                            templateNameField = f;
                            break;
                        }
                    }
                    templateNameLooked = true;
                }
            }
        }
        Field field = templateNameField;
        if(field == null) return null;
        try {
            return field.get(piece) instanceof String name ? ResourceLocation.tryParse(name) : null;
        } catch(ReflectiveOperationException ignored) {
            return null;
        }
    }
}
