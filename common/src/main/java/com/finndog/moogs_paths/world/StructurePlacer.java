package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.StructureSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Optional;

public final class StructurePlacer {
    private StructurePlacer() {}

    public static void placeInChunk(WorldGenLevel level, List<BlockPos> waypoints, List<PathNetworkType.WeightedRef> structureSetRefs, int chunkX, int chunkZ, RandomSource random) {
        Level underlying = level.getLevel();
        if(!(underlying instanceof ServerLevel serverLevel)) return;
        StructureTemplateManager manager = serverLevel.getStructureManager();

        for(PathNetworkType.WeightedRef ref : structureSetRefs) {
            PathDataManager.getStructureSet(ref.id()).ifPresent(set ->
                placeSet(level, serverLevel, manager, waypoints, set, chunkX, chunkZ, random));
        }
    }

    //////////////////////////////

    private static void placeSet(WorldGenLevel level, ServerLevel serverLevel, StructureTemplateManager manager, List<BlockPos> waypoints, StructureSet set, int chunkX, int chunkZ, RandomSource random) {
        if(set.placement() == StructureSet.PlacementMode.ENDPOINT) {
            tryPlace(level, serverLevel, manager, waypoints.get(0), set, chunkX, chunkZ, random);
            if(waypoints.size() > 1) {
                tryPlace(level, serverLevel, manager, waypoints.get(waypoints.size() - 1), set, chunkX, chunkZ, random);
            }
            return;
        }

        int distanceSinceLast = 0;
        int nextThreshold = nextSpacing(set, random);

        for(BlockPos waypoint : waypoints) {
            distanceSinceLast++;
            if(distanceSinceLast >= nextThreshold) {
                tryPlace(level, serverLevel, manager, waypoint, set, chunkX, chunkZ, random);
                distanceSinceLast = 0;
                nextThreshold = nextSpacing(set, random);
            }
        }
    }

    private static void tryPlace(WorldGenLevel level, ServerLevel serverLevel, StructureTemplateManager manager, BlockPos waypoint, StructureSet set, int chunkX, int chunkZ, RandomSource random) {
        // Always pick entry and rotation first — advances random consistently across all chunks
        StructureSet.StructureEntry entry = pickWeighted(set.structures(), random);
        Rotation rotation = parseRotation(entry.rotation(), random);

        if((waypoint.getX() >> 4) != chunkX || (waypoint.getZ() >> 4) != chunkZ) return;

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, waypoint.getX(), waypoint.getZ());
        BlockPos pos = new BlockPos(waypoint.getX(), surfaceY, waypoint.getZ());

        if(!isFlatEnough(level, pos, set.flatnessTolerance())) return;

        Optional<StructureTemplate> templateOpt = manager.get(entry.nbt());
        if(templateOpt.isEmpty()) {
            Constants.LOG.warn("Structure template not found: {}", entry.nbt());
            return;
        }

        placeEntry(serverLevel, templateOpt.get(), pos, entry, rotation);
    }

    private static void placeEntry(ServerLevel level, StructureTemplate template, BlockPos pos, StructureSet.StructureEntry entry, Rotation rotation) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .setMirror(Mirror.NONE)
            .setIgnoreEntities(false);

        Vec3i rawSize = template.getSize();
        boolean rotated90 = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int sizeX = rotated90 ? rawSize.getZ() : rawSize.getX();
        int sizeZ = rotated90 ? rawSize.getX() : rawSize.getZ();
        BlockPos placementPos = new BlockPos(
            pos.getX() - sizeX / 2 + entry.offset()[0],
            pos.getY() + entry.offset()[1],
            pos.getZ() - sizeZ / 2 + entry.offset()[2]
        );

        template.placeInWorld(level, placementPos, placementPos, settings, level.getRandom(), 2);
    }

    private static boolean isFlatEnough(WorldGenLevel level, BlockPos center, int tolerance) {
        int centerY = level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX(), center.getZ());
        for(int ox = -2; ox <= 2; ox++) {
            for(int oz = -2; oz <= 2; oz++) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX() + ox, center.getZ() + oz);
                if(Math.abs(y - centerY) > tolerance) return false;
            }
        }
        return true;
    }

    private static int nextSpacing(StructureSet set, RandomSource random) {
        return set.spacing() + (set.spacingVariance() > 0 ? random.nextInt(set.spacingVariance()) : 0);
    }

    private static StructureSet.StructureEntry pickWeighted(List<StructureSet.StructureEntry> entries, RandomSource random) {
        int total = entries.stream().mapToInt(StructureSet.StructureEntry::weight).sum();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(StructureSet.StructureEntry e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) return e;
        }
        return entries.get(0);
    }

    private static Rotation parseRotation(String rotation, RandomSource random) {
        return switch(rotation) {
            case "none" -> Rotation.NONE;
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            case "180" -> Rotation.CLOCKWISE_180;
            case "random" -> Rotation.values()[random.nextInt(4)];
            default -> Rotation.NONE;
        };
    }
}
