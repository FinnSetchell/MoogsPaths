package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.StructureSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;
import java.util.Optional;

public final class StructurePlacer {
    private StructurePlacer() {}

    // Cross-set minimum distance between any two placed structures in a chunk. Distinct from
    // StructureSet.spacing which is the interval inside a single INTERVAL-mode set; this stops
    // overlapping path networks from doubling up on the same waypoint.
    private static final int MIN_STRUCTURE_SPACING_SQ = 5 * 5;

    public static void placeInChunk(WorldGenLevel level, List<BlockPos> waypoints, List<ResourceLocation> structureSetIds, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, LongOpenHashSet placedPositions) {
        placeInChunk(level, waypoints, structureSetIds, biomes, chunkX, chunkZ, random, placedPositions, 3);
    }

    public static void placeInChunk(WorldGenLevel level, List<BlockPos> waypoints, List<ResourceLocation> structureSetIds, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, LongOpenHashSet placedPositions, int flags) {
        for(ResourceLocation id : structureSetIds) {
            Optional<StructureSet> set = MoogsPathsDatapackRegistries.getStructureSet(level.registryAccess(), id);
            if(set.isEmpty()) {
                PathDataManager.warnMissingOnce("Structure set", id);
                continue;
            }
            placeSet(level, waypoints, set.get(), biomes, chunkX, chunkZ, random, placedPositions, flags);
        }
    }

    //////////////////////////////

    private static void placeSet(WorldGenLevel level, List<BlockPos> waypoints, StructureSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, LongOpenHashSet placedPositions, int flags) {
        if(waypoints.isEmpty()) return;

        switch(set.placement()) {
            case ENDPOINT -> {
                tryPlace(level, sideOffsetWaypoint(waypoints, 0, set.sideOffset(), random), set, biomes, chunkX, chunkZ, random, placedPositions, flags);
                if(waypoints.size() > 1) {
                    int last = waypoints.size() - 1;
                    tryPlace(level, sideOffsetWaypoint(waypoints, last, set.sideOffset(), random), set, biomes, chunkX, chunkZ, random, placedPositions, flags);
                }
            }
            case INTERVAL -> placeInterval(level, waypoints, set, biomes, chunkX, chunkZ, random, placedPositions, flags);
        }
    }

    private static void placeInterval(WorldGenLevel level, List<BlockPos> waypoints, StructureSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, LongOpenHashSet placedPositions, int flags) {
        // Waypoints are block-dense after the pathfinder + chaikin pass, so step distance is
        // approximately 1 block. Measure spacing in blocks directly by counting waypoints.
        int distanceSinceLast = 0;
        int nextThreshold = nextSpacing(set, random);

        for(int i = 0; i < waypoints.size(); i++) {
            distanceSinceLast++;
            if(distanceSinceLast >= nextThreshold) {
                tryPlace(level, sideOffsetWaypoint(waypoints, i, set.sideOffset(), random), set, biomes, chunkX, chunkZ, random, placedPositions, flags);
                distanceSinceLast = 0;
                nextThreshold = nextSpacing(set, random);
            }
        }
    }

    // Shifts a waypoint perpendicular to the local path direction by sideOffset blocks.
    // Direction is estimated from adjacent waypoints; side (left/right) is chosen randomly.
    private static BlockPos sideOffsetWaypoint(List<BlockPos> waypoints, int index, int sideOffset, RandomSource random) {
        if(sideOffset == 0 || waypoints.size() < 2) return waypoints.get(index);
        BlockPos a = waypoints.get(Math.max(0, index - 1));
        BlockPos b = waypoints.get(Math.min(waypoints.size() - 1, index + 1));
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        if(dx == 0 && dz == 0) return waypoints.get(index);
        double len = Math.sqrt((double)(dx * dx + dz * dz));
        int perpX = (int) Math.round(-dz / len * sideOffset);
        int perpZ = (int) Math.round(dx / len * sideOffset);
        if(random.nextBoolean()) { perpX = -perpX; perpZ = -perpZ; }
        return waypoints.get(index).offset(perpX, 0, perpZ);
    }

    private static void tryPlace(WorldGenLevel level, BlockPos waypoint, StructureSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, LongOpenHashSet placedPositions, int flags) {
        StructureSet.StructureEntry entry = pickWeighted(set.structures(), random);

        // placement_chance gates the slot rather than rerolling, so a rare entry winning
        // the weight roll does NOT pass the slot on to the small entries. That keeps the
        // overall placement count for a set roughly constant while making large or special
        // structures appear only once or twice per path.
        if(entry.placementChance() < 1.0f && random.nextFloat() >= entry.placementChance()) return;

        Rotation rotation = parseRotation(entry.rotation(), random);

        if((waypoint.getX() >> 4) != chunkX || (waypoint.getZ() >> 4) != chunkZ) return;

        LongIterator pit = placedPositions.iterator();
        while(pit.hasNext()) {
            long encoded = pit.nextLong();
            int px = (int) (encoded >> 32);
            int pz = (int) encoded;
            int ddx = waypoint.getX() - px;
            int ddz = waypoint.getZ() - pz;
            if(ddx * ddx + ddz * ddz < MIN_STRUCTURE_SPACING_SQ) return;
        }

        // template fetch is a field read after first lookup - do it before the 25-sample flatness check
        Optional<StructureTemplate> templateOpt = entry.getTemplate();
        if(templateOpt.isEmpty()) return;
        StructureTemplate template = templateOpt.get();

        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, waypoint.getX(), waypoint.getZ());
        if(!level.getFluidState(new BlockPos(waypoint.getX(), surfaceY - 1, waypoint.getZ())).isEmpty()) return;
        BlockPos pos = new BlockPos(waypoint.getX(), surfaceY - 1, waypoint.getZ());

        if(Constants.ENABLE_DEBUG_TIMER) PathDataManager.recordBiomeCall(com.finndog.moogs_paths.data.BiomeCallSite.STRUCTURE_PLACE_CHECK);
        var biome = level.getBiome(pos);
        if(!biomes.contains(biome) || biome.is(PathChunkFeature.HAS_NO_PATHS)) return;
        if(!isFlatEnough(level, pos, set.flatnessTolerance())) return;

        if(footprintOverWater(level, template, pos, entry.offset(), rotation)) return;

        if(set.terrainAdjustment() == StructureSet.TerrainAdjustmentSetting.BEARD_THIN) {
            applyBeardThin(level, template, pos, entry.offset(), rotation, random, flags);
        }

        placeEntry(level, template, pos, entry, rotation, flags);
        placedPositions.add(((long) waypoint.getX() << 32) | (waypoint.getZ() & 0xFFFFFFFFL));
    }

    private static int fillPad(int sizeX, int sizeZ) {
        return Math.max(1, Math.min(2, (Math.max(sizeX, sizeZ) + 1) / 2));
    }

    private static boolean footprintOverWater(WorldGenLevel level, StructureTemplate template, BlockPos pos, Vec3i offset, Rotation rotation) {
        Vec3i rawSize = template.getSize();
        boolean rotated90 = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int sizeX = rotated90 ? rawSize.getZ() : rawSize.getX();
        int sizeZ = rotated90 ? rawSize.getX() : rawSize.getZ();
        int pad = fillPad(sizeX, sizeZ);
        int minX = pos.getX() - sizeX / 2 + offset.getX() - pad;
        int minZ = pos.getZ() - sizeZ / 2 + offset.getZ() - pad;
        int totalX = sizeX + 2 * pad;
        int totalZ = sizeZ + 2 * pad;

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        if(isColumnOverWater(level, minX + totalX / 2, minZ + totalZ / 2, mpos)) return true;
        if(isColumnOverWater(level, minX, minZ, mpos)) return true;
        if(isColumnOverWater(level, minX + totalX, minZ, mpos)) return true;
        if(isColumnOverWater(level, minX, minZ + totalZ, mpos)) return true;
        if(isColumnOverWater(level, minX + totalX, minZ + totalZ, mpos)) return true;

        int stride = 2;
        for(int dx = 0; dx <= totalX; dx += stride) {
            for(int dz = 0; dz <= totalZ; dz += stride) {
                int x = minX + dx;
                int z = minZ + dz;
                if(isColumnOverWater(level, x, z, mpos)) return true;
            }
        }
        return false;
    }

    private static void applyBeardThin(WorldGenLevel level, StructureTemplate template, BlockPos pos, Vec3i offset, Rotation rotation, RandomSource random, int flags) {
        Vec3i rawSize = template.getSize();
        boolean rotated90 = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int sizeX = rotated90 ? rawSize.getZ() : rawSize.getX();
        int sizeZ = rotated90 ? rawSize.getX() : rawSize.getZ();
        int pad = fillPad(sizeX, sizeZ);
        int fillSizeX = sizeX + 2 * pad;
        int fillSizeZ = sizeZ + 2 * pad;
        int fillMinX = pos.getX() - sizeX / 2 + offset.getX() - pad;
        int fillMinZ = pos.getZ() - sizeZ / 2 + offset.getZ() - pad;
        int baseY = pos.getY() + offset.getY();

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for(int dx = 0; dx < fillSizeX; dx++) {
            for(int dz = 0; dz < fillSizeZ; dz++) {
                int wx = fillMinX + dx;
                int wz = fillMinZ + dz;
                int edgeDist = Math.min(Math.min(dx, fillSizeX - 1 - dx), Math.min(dz, fillSizeZ - 1 - dz));
                int naturalY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;

                if(naturalY < baseY) {
                    BlockState topFill = naturalY >= level.getMinBuildHeight()
                            ? level.getBlockState(mpos.set(wx, naturalY, wz))
                            : Blocks.GRASS_BLOCK.defaultBlockState();
                    BlockState subFill = naturalY - 1 >= level.getMinBuildHeight()
                            ? level.getBlockState(mpos.set(wx, naturalY - 1, wz))
                            : Blocks.DIRT.defaultBlockState();
                    for(int y = naturalY + 1; y < baseY; y++) {
                        if(edgeDist == 0 && random.nextFloat() > 0.5f) continue;
                        mpos.set(wx, y, wz);
                        level.setBlock(mpos, (y == baseY - 1) ? topFill : subFill, flags);
                    }
                }
            }
        }
    }

    private static boolean isColumnOverWater(WorldGenLevel level, int x, int z, BlockPos.MutableBlockPos mpos) {
        int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if(sy <= level.getMinBuildHeight()) return false;
        for(int depth = 1; depth <= 3; depth++) {
            mpos.set(x, sy - depth, z);
            if(!level.getFluidState(mpos).isEmpty()) return true;
        }
        return false;
    }

    private static void placeEntry(WorldGenLevel level, StructureTemplate template, BlockPos pos, StructureSet.StructureEntry entry, Rotation rotation, int flags) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .setMirror(Mirror.NONE)
            .setIgnoreEntities(false)
            .addProcessor(new BlockIgnoreProcessor(List.of(Blocks.STRUCTURE_VOID)));

        Vec3i rawSize = template.getSize();
        boolean rotated90 = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int sizeX = rotated90 ? rawSize.getZ() : rawSize.getX();
        int sizeZ = rotated90 ? rawSize.getX() : rawSize.getZ();
        Vec3i offset = entry.offset();
        BlockPos placementPos = new BlockPos(
            pos.getX() - sizeX / 2 + offset.getX(),
            pos.getY() + offset.getY(),
            pos.getZ() - sizeZ / 2 + offset.getZ()
        );

        template.placeInWorld(level, placementPos, placementPos, settings, level.getRandom(), flags);
    }

    // 9-sample cross instead of 5x5 grid: catches slopes in all 4 cardinal directions
    private static boolean isFlatEnough(WorldGenLevel level, BlockPos center, int tolerance) {
        int cx = center.getX(), cz = center.getZ();
        int centerY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        for(int r = 1; r <= 2; r++) {
            if(Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + r, cz) - centerY) > tolerance) return false;
            if(Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx - r, cz) - centerY) > tolerance) return false;
            if(Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz + r) - centerY) > tolerance) return false;
            if(Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz - r) - centerY) > tolerance) return false;
        }
        return true;
    }

    private static int nextSpacing(StructureSet set, RandomSource random) {
        return set.spacing() + (set.spacingVariance() > 0 ? random.nextInt(set.spacingVariance()) : 0);
    }

    private static StructureSet.StructureEntry pickWeighted(List<StructureSet.StructureEntry> entries, RandomSource random) {
        int total = 0;
        for(StructureSet.StructureEntry e : entries) total += e.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(StructureSet.StructureEntry e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) return e;
        }
        return entries.get(0);
    }

    private static Rotation parseRotation(StructureSet.RotationSetting rotation, RandomSource random) {
        return switch(rotation) {
            case NONE -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case RANDOM -> Rotation.values()[random.nextInt(4)];
        };
    }
}
