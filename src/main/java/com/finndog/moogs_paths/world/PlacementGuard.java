package com.finndog.moogs_paths.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
//? if <26.3 {
import net.minecraft.world.level.ChunkPos;
//?}
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;

/**
 * What live placement leaves alone in a chunk. Paths are laid into chunks that already exist, so
 * they would otherwise cut through whatever is there: blocks with block entities (chests,
 * campfires), doors, beds, rails and farmland, and, for region paths, the pieces of structures
 * such as village houses. Structure-anchored paths are meant to meet their structure, so they
 * only keep off the protected blocks.
 */
public final class PlacementGuard {
    public static final PlacementGuard NONE = new PlacementGuard(List.of());

    private final List<BoundingBox> pieces;

    private PlacementGuard(List<BoundingBox> pieces) {
        this.pieces = pieces;
    }

    public static PlacementGuard forChunk(ServerLevel level, int chunkX, int chunkZ, boolean avoidStructures) {
        if(!avoidStructures) return NONE;
        int minX = chunkX << 4, minZ = chunkZ << 4;
        List<BoundingBox> boxes = new ArrayList<>();
        //? if >=26.3 {
        /*List<StructureStart> starts = level.structureManager().startsForStructure(chunkX, chunkZ, s -> true);
        *///?} else {
        List<StructureStart> starts = level.structureManager().startsForStructure(new ChunkPos(chunkX, chunkZ), s -> true);
        //?}
        for(StructureStart start : starts) {
            for(StructurePiece piece : start.getPieces()) {
                BoundingBox b = piece.getBoundingBox();
                if(b.maxX() >= minX && b.minX() <= minX + 15 && b.maxZ() >= minZ && b.minZ() <= minZ + 15) boxes.add(b);
            }
        }
        return boxes.isEmpty() ? NONE : new PlacementGuard(boxes);
    }

    /** Whether any structure piece covers this column between minY and maxY. */
    public boolean insidePiece(int x, int minY, int maxY, int z) {
        for(BoundingBox b : pieces) {
            if(x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ() && maxY >= b.minY() && minY <= b.maxY()) return true;
        }
        return false;
    }

    /**
     * Whether a path surface at {@code y} must skip this column: a structure piece around it, or a
     * protected block at it or in the two blocks above (which the path would clear or leave floating).
     */
    public boolean blocksPath(WorldGenLevel level, int x, int y, int z) {
        if(insidePiece(x, y - 1, y + 2, z)) return true;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for(int dy = 0; dy <= 2; dy++) {
            if(isProtected(level.getBlockState(mpos.set(x, y + dy, z)))) return true;
        }
        return false;
    }

    public static boolean isProtected(BlockState state) {
        return state.hasBlockEntity()
            || state.is(BlockTags.DOORS)
            || state.is(BlockTags.BEDS)
            || state.is(BlockTags.RAILS)
            || state.is(Blocks.FARMLAND);
    }
}
