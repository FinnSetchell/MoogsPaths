package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;

import java.util.List;
import java.util.Optional;

public record PathType(
    List<WeightedBlock> surfaceBlocks,
    List<WeightedBlock> edgeBlocks,
    Identifier fillBlock,
    WidthRange width,
    float rigidness,
    float carver,
    IntProvider length,
    FadeSettings fade,
    Optional<WaterSettings> waterSettings
) {
    public record WeightedBlock(Identifier block, int weight) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("block").forGetter(WeightedBlock::block),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(WeightedBlock::weight)
        ).apply(instance, WeightedBlock::new));
    }

    public record WidthRange(int min, int max) {
        private static final Codec<WidthRange> BASE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 32).fieldOf("min").forGetter(WidthRange::min),
            Codec.intRange(0, 32).fieldOf("max").forGetter(WidthRange::max)
        ).apply(instance, WidthRange::new));

        public static final Codec<WidthRange> CODEC = BASE.flatXmap(
            wr -> wr.min > wr.max
                ? DataResult.error(() -> "width.min (" + wr.min + ") must be <= width.max (" + wr.max + ")")
                : DataResult.success(wr),
            DataResult::success);
    }

    public record FadeSettings(int startBlocks, int endBlocks) {
        public static final Codec<FadeSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("start_blocks").forGetter(FadeSettings::startBlocks),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("end_blocks").forGetter(FadeSettings::endBlocks)
        ).apply(instance, FadeSettings::new));
    }

    public record WaterSettings(List<WeightedBlock> surfaceBlocks, List<WeightedBlock> edgeBlocks) {
        public static final Codec<WaterSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(WeightedBlock.CODEC).fieldOf("surface_blocks").forGetter(WaterSettings::surfaceBlocks),
            Codec.list(WeightedBlock.CODEC).optionalFieldOf("edge_blocks", List.of()).forGetter(WaterSettings::edgeBlocks)
        ).apply(instance, WaterSettings::new));
    }

    public static final Codec<PathType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.list(WeightedBlock.CODEC).fieldOf("surface_blocks").forGetter(PathType::surfaceBlocks),
        Codec.list(WeightedBlock.CODEC).optionalFieldOf("edge_blocks", List.of()).forGetter(PathType::edgeBlocks),
        Identifier.CODEC.fieldOf("fill_block").forGetter(PathType::fillBlock),
        WidthRange.CODEC.fieldOf("width").forGetter(PathType::width),
        Codec.floatRange(0.0f, 1.0f).fieldOf("rigidness").forGetter(PathType::rigidness),
        Codec.floatRange(0.0f, 1.0f).fieldOf("carver").forGetter(PathType::carver),
        IntProviders.codec(1, 100_000).fieldOf("length").forGetter(PathType::length),
        FadeSettings.CODEC.fieldOf("fade").forGetter(PathType::fade),
        WaterSettings.CODEC.optionalFieldOf("water_settings").forGetter(PathType::waterSettings)
    ).apply(instance, PathType::new));
}
