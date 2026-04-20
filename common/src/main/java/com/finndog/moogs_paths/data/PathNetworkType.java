package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record PathNetworkType(
    ResourceLocation pathType,
    BiomeFilter biomeFilter,
    int weight,
    int regionSize,
    List<WeightedRef> structureSets,
    List<WeightedRef> featureDecoratorSets,
    List<WeightedRef> bushDecoratorSets
) {

    public static final Codec<PathNetworkType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("path_type").forGetter(PathNetworkType::pathType),
        BiomeFilter.DISPATCH_CODEC.fieldOf("biome_filter").forGetter(PathNetworkType::biomeFilter),
        Codec.INT.fieldOf("weight").forGetter(PathNetworkType::weight),
        Codec.INT.fieldOf("region_size").forGetter(PathNetworkType::regionSize),
        WeightedRef.CODEC.listOf().optionalFieldOf("structure_sets", List.of()).forGetter(PathNetworkType::structureSets),
        WeightedRef.CODEC.listOf().optionalFieldOf("feature_decorator_sets", List.of()).forGetter(PathNetworkType::featureDecoratorSets),
        WeightedRef.CODEC.listOf().optionalFieldOf("bush_decorator_sets", List.of()).forGetter(PathNetworkType::bushDecoratorSets)
    ).apply(instance, PathNetworkType::new));

    public record WeightedRef(ResourceLocation id, int weight) {
        public static final Codec<WeightedRef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(WeightedRef::id),
            Codec.INT.fieldOf("weight").forGetter(WeightedRef::weight)
        ).apply(instance, WeightedRef::new));
    }
}
