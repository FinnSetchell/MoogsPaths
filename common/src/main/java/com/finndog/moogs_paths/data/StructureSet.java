package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.*;
import java.util.stream.Collectors;

public record StructureSet(
        List<StructureEntry> structures,
        PlacementMode placement,
        int spacing,
        int spacingVariance,
        int flatnessTolerance
) {
    public static final Codec<StructureSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(StructureEntry.CODEC).fieldOf("structures").forGetter(StructureSet::structures),
            PlacementMode.CODEC.fieldOf("placement").forGetter(StructureSet::placement),
            Codec.INT.fieldOf("spacing").forGetter(StructureSet::spacing),
            Codec.INT.fieldOf("spacing_variance").forGetter(StructureSet::spacingVariance),
            Codec.INT.fieldOf("flatness_tolerance").forGetter(StructureSet::flatnessTolerance)
    ).apply(instance, StructureSet::new));

    public record StructureEntry(
            ResourceLocation nbt,
            String rotation,
            int weight,
            int[] offset
    ) {
        public static final Codec<StructureEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("nbt").forGetter(StructureEntry::nbt),
                Codec.STRING.fieldOf("rotation").forGetter(StructureEntry::rotation),
                Codec.INT.fieldOf("weight").forGetter(StructureEntry::weight),
                Codec.list(Codec.INT).xmap(
                        list -> new int[]{list.get(0), list.get(1), list.get(2)},
                        arr -> List.of(arr[0], arr[1], arr[2])
                ).fieldOf("offset").forGetter(StructureEntry::offset)
        ).apply(instance, StructureEntry::new));
    }

    public enum PlacementMode implements StringRepresentable {
        ENDPOINT("endpoint"),
        INTERVAL("interval"),
        BRANCH_POINT("branch_point");

        public static final Map<String, PlacementMode> BY_NAME = Arrays.stream(values())
                .collect(Collectors.toMap(PlacementMode::getSerializedName, v -> v));

        public static final Codec<PlacementMode> CODEC = StringRepresentable.fromEnum(PlacementMode::values);

        final String name;

        PlacementMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
