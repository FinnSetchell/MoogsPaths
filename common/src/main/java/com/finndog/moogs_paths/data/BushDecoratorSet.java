package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record BushDecoratorSet(
    List<WeightedBlock> blocks,
    float density,
    FeatureDecoratorSet.Side side,
    int minOffset,
    int maxOffset,
    int minSize,
    int maxSize,
    int minSpacing
) {
    public record WeightedBlock(ResourceLocation block, int weight) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("block").forGetter(WeightedBlock::block),
            Codec.INT.fieldOf("weight").forGetter(WeightedBlock::weight)
        ).apply(instance, WeightedBlock::new));
    }

    public static final Codec<BushDecoratorSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.list(WeightedBlock.CODEC).fieldOf("blocks").forGetter(BushDecoratorSet::blocks),
        Codec.FLOAT.fieldOf("density").forGetter(BushDecoratorSet::density),
        FeatureDecoratorSet.Side.CODEC.fieldOf("side").forGetter(BushDecoratorSet::side),
        Codec.INT.fieldOf("min_offset").forGetter(BushDecoratorSet::minOffset),
        Codec.INT.fieldOf("max_offset").forGetter(BushDecoratorSet::maxOffset),
        Codec.INT.fieldOf("min_size").forGetter(BushDecoratorSet::minSize),
        Codec.INT.fieldOf("max_size").forGetter(BushDecoratorSet::maxSize),
        Codec.INT.optionalFieldOf("min_spacing", 0).forGetter(BushDecoratorSet::minSpacing)
    ).apply(instance, BushDecoratorSet::new));
}
