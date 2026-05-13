package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.*;
import java.util.stream.Collectors;

public record StructureSet(
        List<StructureEntry> structures,
        PlacementMode placement,
        int spacing,
        int spacingVariance,
        int flatnessTolerance,
        TerrainAdjustmentSetting terrainAdjustment,
        int sideOffset
) {
    public static final Codec<StructureSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(StructureEntry.CODEC).fieldOf("structures").forGetter(StructureSet::structures),
            PlacementMode.CODEC.fieldOf("placement").forGetter(StructureSet::placement),
            Codec.INT.fieldOf("spacing").forGetter(StructureSet::spacing),
            Codec.INT.fieldOf("spacing_variance").forGetter(StructureSet::spacingVariance),
            Codec.INT.fieldOf("flatness_tolerance").forGetter(StructureSet::flatnessTolerance),
            TerrainAdjustmentSetting.CODEC.optionalFieldOf("terrain_adjustment", TerrainAdjustmentSetting.NONE).forGetter(StructureSet::terrainAdjustment),
            Codec.INT.optionalFieldOf("side_offset", 0).forGetter(StructureSet::sideOffset)
    ).apply(instance, StructureSet::new));

    public record StructureEntry(
            ResourceLocation nbt,
            RotationSetting rotation,
            int weight,
            Vec3i offset,
            float placementChance
    ) {
        public static final Codec<StructureEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("nbt").forGetter(StructureEntry::nbt),
                RotationSetting.CODEC.fieldOf("rotation").forGetter(StructureEntry::rotation),
                Codec.INT.fieldOf("weight").forGetter(StructureEntry::weight),
                Vec3i.CODEC.fieldOf("offset").forGetter(StructureEntry::offset),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("placement_chance", 1.0f).forGetter(StructureEntry::placementChance)
        ).apply(instance, StructureEntry::new));
    }

    public enum PlacementMode implements StringRepresentable {
        ENDPOINT("endpoint"),
        INTERVAL("interval");

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

    public enum TerrainAdjustmentSetting implements StringRepresentable {
        NONE("none"),
        BEARD_THIN("beard_thin");

        public static final Codec<TerrainAdjustmentSetting> CODEC = StringRepresentable.fromEnum(TerrainAdjustmentSetting::values);

        final String name;

        TerrainAdjustmentSetting(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum RotationSetting implements StringRepresentable {
        NONE("none"),
        CLOCKWISE_90("clockwise_90"),
        CLOCKWISE_180("clockwise_180"),
        COUNTERCLOCKWISE_90("counterclockwise_90"),
        RANDOM("random");

        public static final Codec<RotationSetting> CODEC = StringRepresentable.fromEnum(RotationSetting::values);

        final String name;

        RotationSetting(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
