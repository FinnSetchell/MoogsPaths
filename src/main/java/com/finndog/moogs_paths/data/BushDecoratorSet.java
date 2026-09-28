package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
    int minSpacing,
    int minHeight,
    int maxHeight
) {
    public record WeightedBlock(ResourceLocation block, int weight) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("block").forGetter(WeightedBlock::block),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(WeightedBlock::weight)
        ).apply(instance, WeightedBlock::new));
    }

    private static final Codec<BushDecoratorSet> BASE = RecordCodecBuilder.create(instance -> instance.group(
        Codec.list(WeightedBlock.CODEC).fieldOf("blocks").forGetter(BushDecoratorSet::blocks),
        Codec.floatRange(0.0f, 1.0f).fieldOf("density").forGetter(BushDecoratorSet::density),
        FeatureDecoratorSet.Side.CODEC.fieldOf("side").forGetter(BushDecoratorSet::side),
        Codec.intRange(0, Integer.MAX_VALUE).fieldOf("min_offset").forGetter(BushDecoratorSet::minOffset),
        Codec.intRange(0, Integer.MAX_VALUE).fieldOf("max_offset").forGetter(BushDecoratorSet::maxOffset),
        Codec.intRange(1, Integer.MAX_VALUE).fieldOf("min_size").forGetter(BushDecoratorSet::minSize),
        Codec.intRange(1, Integer.MAX_VALUE).fieldOf("max_size").forGetter(BushDecoratorSet::maxSize),
        Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("min_spacing", 0).forGetter(BushDecoratorSet::minSpacing),
        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("min_height", 1).forGetter(BushDecoratorSet::minHeight),
        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_height", 2).forGetter(BushDecoratorSet::maxHeight)
    ).apply(instance, BushDecoratorSet::new));

    public static final Codec<BushDecoratorSet> CODEC = BASE.flatXmap(BushDecoratorSet::validate, DataResult::success);

    private static DataResult<BushDecoratorSet> validate(BushDecoratorSet b) {
        if(b.minOffset > b.maxOffset)
            return DataResult.error(() -> "min_offset (" + b.minOffset + ") must be <= max_offset (" + b.maxOffset + ")");
        if(b.minSize > b.maxSize)
            return DataResult.error(() -> "min_size (" + b.minSize + ") must be <= max_size (" + b.maxSize + ")");
        if(b.minHeight > b.maxHeight)
            return DataResult.error(() -> "min_height (" + b.minHeight + ") must be <= max_height (" + b.maxHeight + ")");
        return DataResult.success(b);
    }
}
