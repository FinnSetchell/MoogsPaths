package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record PathNetworkType(
    ResourceLocation pathType,
    ScaleSettings scale,
    BiomeFilter biomeFilter,
    int weight,
    int regionSize,
    BranchSettings branches,
    List<WeightedRef> structureSets,
    List<WeightedRef> featureDecoratorSets,
    List<WeightedRef> bushDecoratorSets
) {

    public static final Codec<PathNetworkType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("path_type").forGetter(PathNetworkType::pathType),
        ScaleSettings.CODEC.fieldOf("scale").forGetter(PathNetworkType::scale),
        BiomeFilter.DISPATCH_CODEC.fieldOf("biome_filter").forGetter(PathNetworkType::biomeFilter),
        Codec.INT.fieldOf("weight").forGetter(PathNetworkType::weight),
        Codec.INT.fieldOf("region_size").forGetter(PathNetworkType::regionSize),
        BranchSettings.CODEC.fieldOf("branches").forGetter(PathNetworkType::branches),
        WeightedRef.CODEC.listOf().optionalFieldOf("structure_sets", List.of()).forGetter(PathNetworkType::structureSets),
        WeightedRef.CODEC.listOf().optionalFieldOf("feature_decorator_sets", List.of()).forGetter(PathNetworkType::featureDecoratorSets),
        WeightedRef.CODEC.listOf().optionalFieldOf("bush_decorator_sets", List.of()).forGetter(PathNetworkType::bushDecoratorSets)
    ).apply(instance, PathNetworkType::new));

    public record BranchSettings(int minBranches, int maxBranches, float lengthFraction) {
        public static final Codec<BranchSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("min_branches").forGetter(BranchSettings::minBranches),
            Codec.INT.fieldOf("max_branches").forGetter(BranchSettings::maxBranches),
            Codec.FLOAT.fieldOf("length_fraction").forGetter(BranchSettings::lengthFraction)
        ).apply(instance, BranchSettings::new));
    }

    public record WeightedRef(ResourceLocation id, int weight) {
        public static final Codec<WeightedRef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(WeightedRef::id),
            Codec.INT.fieldOf("weight").forGetter(WeightedRef::weight)
        ).apply(instance, WeightedRef::new));
    }
}
