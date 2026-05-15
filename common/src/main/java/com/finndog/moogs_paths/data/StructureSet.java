package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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

    public static final class StructureEntry {
        public static final Codec<StructureEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("nbt").forGetter(StructureEntry::nbt),
                RotationSetting.CODEC.fieldOf("rotation").forGetter(StructureEntry::rotation),
                Codec.INT.fieldOf("weight").forGetter(StructureEntry::weight),
                Vec3i.CODEC.fieldOf("offset").forGetter(StructureEntry::offset),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("placement_chance", 1.0f).forGetter(StructureEntry::placementChance)
        ).apply(instance, StructureEntry::new));

        private final ResourceLocation nbt;
        private final RotationSetting rotation;
        private final int weight;
        private final Vec3i offset;
        private final float placementChance;

        // volatile pair: version guards the template so we don't serve a stale optional
        private volatile Optional<StructureTemplate> cachedTemplate;
        private volatile int cachedVersion = -1;

        public StructureEntry(ResourceLocation nbt, RotationSetting rotation, int weight, Vec3i offset, float placementChance) {
            this.nbt = nbt;
            this.rotation = rotation;
            this.weight = weight;
            this.offset = offset;
            this.placementChance = placementChance;
        }

        public ResourceLocation nbt() { return nbt; }
        public RotationSetting rotation() { return rotation; }
        public int weight() { return weight; }
        public Vec3i offset() { return offset; }
        public float placementChance() { return placementChance; }

        public Optional<StructureTemplate> getTemplate() {
            int cv = PathDataManager.getCacheVersion();
            if(cachedVersion != cv) {
                cachedTemplate = PathDataManager.getCachedTemplate(nbt);
                cachedVersion = cv;
            }
            return cachedTemplate;
        }
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
