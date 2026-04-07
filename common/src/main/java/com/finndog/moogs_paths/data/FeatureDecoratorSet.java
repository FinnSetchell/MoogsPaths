package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.*;
import java.util.stream.Collectors;

public record FeatureDecoratorSet(
        List<FeatureEntry> features,
        float density,
        Side side,
        int scatterWidth
) {
    public static final Codec<FeatureDecoratorSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(FeatureEntry.CODEC).fieldOf("features").forGetter(FeatureDecoratorSet::features),
            Codec.FLOAT.fieldOf("density").forGetter(FeatureDecoratorSet::density),
            Side.CODEC.fieldOf("side").forGetter(FeatureDecoratorSet::side),
            Codec.INT.fieldOf("scatter_width").forGetter(FeatureDecoratorSet::scatterWidth)
    ).apply(instance, FeatureDecoratorSet::new));

    public record FeatureEntry(
            ResourceLocation feature,
            int weight
    ) {
        public static final Codec<FeatureEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("feature").forGetter(FeatureEntry::feature),
                Codec.INT.fieldOf("weight").forGetter(FeatureEntry::weight)
        ).apply(instance, FeatureEntry::new));
    }

    public enum Side implements StringRepresentable {
        LEFT("left"),
        RIGHT("right"),
        BOTH("both"),
        CENTER("center");

        public static final Map<String, Side> BY_NAME = Arrays.stream(values())
                .collect(Collectors.toMap(Side::getSerializedName, v -> v));

        public static final Codec<Side> CODEC = StringRepresentable.fromEnum(Side::values);

        final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
