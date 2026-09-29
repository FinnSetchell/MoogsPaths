package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Anchors a {@link PathNetworkType}'s paths to a structure. A network carrying one of these no longer
 * scatters paths from region origins: every path starts at a structure that really generated there,
 * and leads away from it.
 *
 * <p>{@code structure_set} is a vanilla or modded structure set ({@code minecraft:worldgen/structure_set}),
 * e.g. {@code minecraft:villages}. The set rather than the structure, because the set carries the
 * placement (spacing, separation, salt, frequency, exclusion zone) that lets every chunk work out the
 * same candidate positions without reading generated chunks.
 *
 * <p>{@code structure} optionally narrows it to one structure of that set: a candidate only counts when
 * vanilla's own pick for that spot is this structure.
 *
 * <p>{@code path_count} is how many paths lead out of each structure, spread around it.
 *
 * <p>{@code anchor} optionally pins where paths start. Without it a path starts just outside the
 * structure's pieces, on the side it heads off towards, and never crosses them:
 * <ul>
 *   <li>{@code piece} picks a template pool element by its NBT id (a particular village house, a named
 *       element of a modded pool), {@code piece_index} which occurrence of it;</li>
 *   <li>{@code local_pos} is a position inside that piece's NBT, or the start piece's without
 *       {@code piece}.</li>
 * </ul>
 * With an anchor the path starts exactly there and may cross the structure on its way out.
 */
public record StructureOrigin(
    ResourceLocation structureSet,
    Optional<ResourceLocation> structure,
    int pathCount,
    Optional<Anchor> anchor
) {
    public static final int MAX_PATH_COUNT = 8;

    public static final Codec<StructureOrigin> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("structure_set").forGetter(StructureOrigin::structureSet),
        ResourceLocation.CODEC.optionalFieldOf("structure").forGetter(StructureOrigin::structure),
        Codec.intRange(1, MAX_PATH_COUNT).optionalFieldOf("path_count", 1).forGetter(StructureOrigin::pathCount),
        Anchor.CODEC.optionalFieldOf("anchor").forGetter(StructureOrigin::anchor)
    ).apply(instance, StructureOrigin::new));

    /**
     * Where inside the structure a path starts. {@code localPos} is in the coordinate space of the
     * piece's {@code .nbt} (relative to the piece's own origin, before rotation), the numbers a
     * structure block shows. Only X/Z steer the start; it is snapped to the surface.
     */
    public record Anchor(Optional<ResourceLocation> piece, int pieceIndex, Optional<Vec3i> localPos) {
        public static final Codec<Anchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.optionalFieldOf("piece").forGetter(Anchor::piece),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("piece_index", 0).forGetter(Anchor::pieceIndex),
            Vec3i.CODEC.optionalFieldOf("local_pos").forGetter(Anchor::localPos)
        ).apply(instance, Anchor::new));
    }
}
