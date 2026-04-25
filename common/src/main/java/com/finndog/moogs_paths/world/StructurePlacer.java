package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.StructureSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class StructurePlacer {
    private StructurePlacer() {}

    // Min centre-to-centre distance between two placed structures. Squared for cheap compares.
    private static final int MIN_STRUCTURE_SPACING_SQ = 5 * 5;

    public static void placeInChunk(WorldGenLevel level, List<BlockPos> waypoints, List<PathNetworkType.WeightedRef> structureSetRefs, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, Set<Long> placedPositions) {
        for(PathNetworkType.WeightedRef ref : structureSetRefs) {
            MoogsPathsDatapackRegistries.getStructureSet(level.registryAccess(), ref.id()).ifPresent(set ->
                placeSet(level, waypoints, set, biomes, chunkX, chunkZ, random, placedPositions));
        }
    }

    //////////////////////////////

    private static void placeSet(WorldGenLevel level, List<BlockPos> waypoints, StructureSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, Set<Long> placedPositions) {
        if(waypoints.isEmpty()) return;

        switch(set.placement()) {
            case ENDPOINT -> {
                tryPlace(level, waypoints.get(0), set, biomes, chunkX, chunkZ, random, placedPositions);
                if(waypoints.size() > 1) {
                    tryPlace(level, waypoints.get(waypoints.size() - 1), set, biomes, chunkX, chunkZ, random, placedPositions);
                }
            }
            case INTERVAL -> placeInterval(level, waypoints, set, biomes, chunkX, chunkZ, random, placedPositions);
        }
    }

    private static void placeInterval(WorldGenLevel level, List<BlockPos> waypoints, StructureSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, Set<Long> placedPositions) {
        // Waypoints are block-dense after the pathfinder + chaikin pass, so step distance is
        // approximately 1 block. Measure spacing in blocks directly by counting waypoints.
        int distanceSinceLast = 0;
        int nextThreshold = nextSpacing(set, random);

        for(BlockPos waypoint : waypoints) {
            distanceSinceLast++;
            if(distanceSinceLast >= nextThreshold) {
                tryPlace(level, waypoint, set, biomes, chunkX, chunkZ, random, placedPositions);
                distanceSinceLast = 0;
                nextThreshold = nextSpacing(set, random);
            }
        }
    }

    private static void tryPlace(WorldGenLevel level, BlockPos waypoint, StructureSet set, HolderSet<Biome> biomes, int chunkX, int chunkZ, RandomSource random, Set<Long> placedPositions) {
        StructureSet.StructureEntry entry = pickWeighted(set.structures(), random);
        Rotation rotation = parseRotation(entry.rotation(), random);

        if((waypoint.getX() >> 4) != chunkX || (waypoint.getZ() >> 4) != chunkZ) return;

        for(long encoded : placedPositions) {
            int px = (int) (encoded >> 32);
            int pz = (int) encoded;
            int ddx = waypoint.getX() - px;
            int ddz = waypoint.getZ() - pz;
            if(ddx * ddx + ddz * ddz < MIN_STRUCTURE_SPACING_SQ) return;
        }

        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, waypoint.getX(), waypoint.getZ());
        if(!level.getFluidState(new BlockPos(waypoint.getX(), surfaceY - 1, waypoint.getZ())).isEmpty()) return;
        BlockPos pos = new BlockPos(waypoint.getX(), surfaceY - 1, waypoint.getZ());

        PathDataManager.recordBiomeCall(com.finndog.moogs_paths.data.BiomeCallSite.STRUCTURE_PLACE_CHECK);
        if(!biomes.contains(level.getBiome(pos))) return;
        if(!isFlatEnough(level, pos, set.flatnessTolerance())) return;

        Optional<StructureTemplate> templateOpt = PathDataManager.getCachedTemplate(entry.nbt());
        if(templateOpt.isEmpty()) return;
        StructureTemplate template = templateOpt.get();

        if(footprintOverWater(level, template, pos, entry.offset(), rotation)) return;

        placeEntry(level, template, pos, entry, rotation);
        placedPositions.add(((long) waypoint.getX() << 32) | (waypoint.getZ() & 0xFFFFFFFFL));
    }

    private static boolean footprintOverWater(WorldGenLevel level, StructureTemplate template, BlockPos pos, Vec3i offset, Rotation rotation) {
        Vec3i rawSize = template.getSize();
        boolean rotated90 = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int sizeX = rotated90 ? rawSize.getZ() : rawSize.getX();
        int sizeZ = rotated90 ? rawSize.getX() : rawSize.getZ();
        int minX = pos.getX() - sizeX / 2 + offset.getX();
        int minZ = pos.getZ() - sizeZ / 2 + offset.getZ();

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        int stride = 2;
        for(int dx = 0; dx <= sizeX; dx += stride) {
            for(int dz = 0; dz <= sizeZ; dz += stride) {
                int x = minX + dx;
                int z = minZ + dz;
                if(isColumnOverWater(level, x, z, mpos)) return true;
            }
        }
        return false;
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

    private static void placeEntry(WorldGenLevel level, StructureTemplate template, BlockPos pos, StructureSet.StructureEntry entry, Rotation rotation) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .setMirror(Mirror.NONE)
            .setIgnoreEntities(false);

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

        template.placeInWorld(level, placementPos, placementPos, settings, level.getRandom(), 3);
    }

    private static boolean isFlatEnough(WorldGenLevel level, BlockPos center, int tolerance) {
        int centerY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        for(int ox = -2; ox <= 2; ox++) {
            for(int oz = -2; oz <= 2; oz++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX() + ox, center.getZ() + oz);
                if(Math.abs(y - centerY) > tolerance) return false;
            }
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
