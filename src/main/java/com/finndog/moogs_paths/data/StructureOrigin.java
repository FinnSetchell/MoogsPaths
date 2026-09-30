package com.finndog.moogs_paths.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Anchors a {@link PathNetworkType}'s paths to a structure. A network carrying one of these no longer
 * scatters paths from region origins: every path starts at a structure that really generated there,
 * and leads away from it.
 *
 * <p>{@code structure_set} is a vanilla or modded structure set ({@code minecraft:worldgen/structure_set}),
 * e.g. {@code minecraft:villages}, or a list of them. The set rather than the structure, because the set
 * carries the placement (spacing, separation, salt, frequency, exclusion zone) that lets every chunk work
 * out the same candidate positions without reading generated chunks. A set that isn't loaded (its mod
 * isn't installed) is skipped, so a list can name optional modded sets; a warning is only logged when
 * none of them is.
 *
 * <p>{@code structure} optionally narrows it to some structures of those sets: one id, a {@code #tag},
 * or a list of either. A candidate only counts when vanilla's own pick for that spot is one of them.
 *
 * <p>{@code path_count} is how many paths lead out of each structure, spread around it.
 *
 * <p>{@code anchor} optionally pins where paths start. Without it a path starts just outside the
 * structure's pieces, on the side it heads off towards. Either way a path never runs back through the
 * structure's pieces.
 * <ul>
 *   <li>{@code piece} picks pieces by their NBT id: a template pool element's {@code location} (a
 *       particular village house, a named element of a modded pool) or a template piece's NBT (an
 *       igloo). {@code *} stands for any part of one name between slashes and {@code **} for any run
 *       of folders, and a list takes several ids or patterns. When several pieces match, each path starts from the one lying furthest the way
 *       it heads, so the roads of a village each continue a different street outward (one more than
 *       32 blocks back from the structure's edge on that side is passed over); {@code piece_index}
 *       instead pins one occurrence. With no piece matching, the path starts as it would without an
 *       anchor.</li>
 *   <li>{@code local_pos} is a position inside that piece, or inside the start piece without
 *       {@code piece}: the piece's NBT coordinates, or for a piece built in code (a jungle temple, a
 *       swamp hut) its own coordinates, the ones its code places blocks at.</li>
 *   <li>{@code facing} is the side of the piece the path leaves from, in the same frame as
 *       {@code local_pos}. The path runs straight out that way until clear of the structure before
 *       turning towards its goal, and a structure's first path heads that way.</li>
 * </ul>
 */
public record StructureOrigin(
    List<ResourceLocation> structureSets,
    List<ExtraCodecs.TagOrElementLocation> structures,
    int pathCount,
    Optional<Anchor> anchor
) {
    public static final int MAX_PATH_COUNT = 8;

    public static final Codec<StructureOrigin> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        nonEmpty(oneOrMany(ResourceLocation.CODEC)).fieldOf("structure_set").forGetter(StructureOrigin::structureSets),
        oneOrMany(ExtraCodecs.TAG_OR_ELEMENT_ID).optionalFieldOf("structure", List.of()).forGetter(StructureOrigin::structures),
        Codec.intRange(1, MAX_PATH_COUNT).optionalFieldOf("path_count", 1).forGetter(StructureOrigin::pathCount),
        Anchor.CODEC.optionalFieldOf("anchor").forGetter(StructureOrigin::anchor)
    ).apply(instance, StructureOrigin::new));

    /** Whether a generated structure is one this origin anchors to. */
    public boolean matches(Holder<Structure> holder, ResourceLocation id) {
        if(structures.isEmpty()) return true;
        for(ExtraCodecs.TagOrElementLocation entry : structures) {
            if(entry.tag() ? holder.is(TagKey.create(Registries.STRUCTURE, entry.id())) : entry.id().equals(id)) return true;
        }
        return false;
    }

    /** The sets and structures, for commands: {@code minecraft:villages/minecraft:village_plains}. */
    public String describe() {
        String sets = structureSets.stream().map(ResourceLocation::toString).collect(Collectors.joining(", "));
        if(structures.isEmpty()) return sets;
        return sets + "/" + structures.stream().map(ExtraCodecs.TagOrElementLocation::toString).collect(Collectors.joining(", "));
    }

    /**
     * Where inside the structure a path starts. {@code localPos} is in the coordinate space of the
     * piece's {@code .nbt} (relative to the piece's own origin, before rotation), the numbers a
     * structure block shows, or for a piece built in code its own coordinates. Only X/Z steer the
     * start; it is snapped to the surface. {@code facing} is in the same frame and turns with the piece.
     */
    public record Anchor(List<String> piece, Optional<Integer> pieceIndex, Optional<Vec3i> localPos, Optional<Direction> facing) {
        public static final Codec<Anchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            oneOrMany(Codec.STRING).optionalFieldOf("piece", List.of()).forGetter(Anchor::piece),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("piece_index").forGetter(Anchor::pieceIndex),
            Vec3i.CODEC.optionalFieldOf("local_pos").forGetter(Anchor::localPos),
            Direction.CODEC.flatXmap(StructureOrigin::horizontal, StructureOrigin::horizontal).optionalFieldOf("facing").forGetter(Anchor::facing)
        ).apply(instance, Anchor::new));

        /** Whether a piece's NBT id matches {@code piece}: an id, or a pattern with {@code *} and {@code **}. */
        public boolean matchesPiece(ResourceLocation element) {
            if(element == null) return false;
            String id = element.toString();
            for(String pattern : piece) {
                if(glob(pattern, 0, id, 0)) return true;
            }
            return false;
        }
    }

    // '*' matches any run of characters within one path segment, so it never crosses a '/'; '**'
    // matches any run at all, folders included.
    private static boolean glob(String pattern, int p, String text, int t) {
        while(p < pattern.length()) {
            char c = pattern.charAt(p);
            if(c == '*') {
                boolean anyFolder = p + 1 < pattern.length() && pattern.charAt(p + 1) == '*';
                int next = anyFolder ? p + 2 : p + 1;
                for(int end = t; ; end++) {
                    if(glob(pattern, next, text, end)) return true;
                    if(end >= text.length() || (!anyFolder && text.charAt(end) == '/')) return false;
                }
            }
            if(t >= text.length() || text.charAt(t) != c) return false;
            p++;
            t++;
        }
        return t == text.length();
    }

    private static DataResult<Direction> horizontal(Direction direction) {
        return direction.getAxis().isHorizontal()
            ? DataResult.success(direction)
            : DataResult.error(() -> "facing must be north, south, east or west, not " + direction.getSerializedName());
    }

    // A single value or a list, so `"structure_set": "minecraft:villages"` still reads as before.
    private static <T> Codec<List<T>> oneOrMany(Codec<T> codec) {
        return Codec.either(codec.listOf(), codec).xmap(
            either -> either.map(list -> list, List::of),
            list -> list.size() == 1 ? Either.right(list.get(0)) : Either.left(list));
    }

    private static <T> Codec<List<T>> nonEmpty(Codec<List<T>> codec) {
        return codec.flatXmap(StructureOrigin::requireEntries, StructureOrigin::requireEntries);
    }

    private static <T> DataResult<List<T>> requireEntries(List<T> list) {
        return list.isEmpty() ? DataResult.error(() -> "structure_set needs at least one structure set") : DataResult.success(list);
    }
}
