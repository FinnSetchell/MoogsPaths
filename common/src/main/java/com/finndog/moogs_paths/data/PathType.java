package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public record PathType(
    List<WeightedBlock> surfaceBlocks,
    List<WeightedBlock> edgeBlocks,
    ResourceLocation fillBlock,
    WidthRange width,
    float curviness,
    int smoothingIterations,
    int maxSlopePerStep,
    float slopeCostWeight,
    SlopeHandling slopeHandling,
    FadeSettings fade,
    Optional<WaterSettings> waterSettings,
    List<WeightedBlock> slabBlocks
) {
    public record WeightedBlock(ResourceLocation block, int weight) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("block").forGetter(WeightedBlock::block),
            Codec.INT.fieldOf("weight").forGetter(WeightedBlock::weight)
        ).apply(instance, WeightedBlock::new));
    }

    public record WidthRange(int min, int max) {
        public static final Codec<WidthRange> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("min").forGetter(WidthRange::min),
            Codec.INT.fieldOf("max").forGetter(WidthRange::max)
        ).apply(instance, WidthRange::new));
    }

    public record SlopeHandling(int cutTolerance, int fillTolerance) {
        public static final Codec<SlopeHandling> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("cut_tolerance").forGetter(SlopeHandling::cutTolerance),
            Codec.INT.fieldOf("fill_tolerance").forGetter(SlopeHandling::fillTolerance)
        ).apply(instance, SlopeHandling::new));
    }

    public record FadeSettings(int startBlocks, int endBlocks) {
        public static final Codec<FadeSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("start_blocks").forGetter(FadeSettings::startBlocks),
            Codec.INT.fieldOf("end_blocks").forGetter(FadeSettings::endBlocks)
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
        ResourceLocation.CODEC.fieldOf("fill_block").forGetter(PathType::fillBlock),
        WidthRange.CODEC.fieldOf("width").forGetter(PathType::width),
        Codec.FLOAT.fieldOf("curviness").forGetter(PathType::curviness),
        Codec.INT.optionalFieldOf("smoothing_iterations", 2).forGetter(PathType::smoothingIterations),
        Codec.INT.optionalFieldOf("max_slope_per_step", 0).forGetter(PathType::maxSlopePerStep),
        Codec.FLOAT.optionalFieldOf("slope_cost_weight", 0.0f).forGetter(PathType::slopeCostWeight),
        SlopeHandling.CODEC.fieldOf("slope_handling").forGetter(PathType::slopeHandling),
        FadeSettings.CODEC.fieldOf("fade").forGetter(PathType::fade),
        WaterSettings.CODEC.optionalFieldOf("water_settings").forGetter(PathType::waterSettings),
        Codec.list(WeightedBlock.CODEC).optionalFieldOf("slab_blocks", List.of()).forGetter(PathType::slabBlocks)
    ).apply(instance, PathType::new));
}
