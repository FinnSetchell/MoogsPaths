package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.*;

public record FeatureDecoratorSet(
        List<FeatureEntry> features,
        float density,
        Side side,
        int scatterWidth
) {
    public static final Codec<FeatureDecoratorSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(FeatureEntry.CODEC).fieldOf("features").forGetter(FeatureDecoratorSet::features),
            Codec.floatRange(0.0f, 1.0f).fieldOf("density").forGetter(FeatureDecoratorSet::density),
            Side.CODEC.fieldOf("side").forGetter(FeatureDecoratorSet::side),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("scatter_width").forGetter(FeatureDecoratorSet::scatterWidth)
    ).apply(instance, FeatureDecoratorSet::new));

    public record FeatureEntry(
            ResourceLocation feature,
            int weight
    ) {
        public static final Codec<FeatureEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("feature").forGetter(FeatureEntry::feature),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(FeatureEntry::weight)
        ).apply(instance, FeatureEntry::new));
    }

    public enum Side implements StringRepresentable {
        LEFT("left"),
        RIGHT("right"),
        BOTH("both"),
        CENTER("center");

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
