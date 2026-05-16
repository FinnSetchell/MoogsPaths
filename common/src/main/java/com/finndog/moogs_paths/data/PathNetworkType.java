package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

public record PathNetworkType(
    ResourceLocation pathType,
    HolderSet<Biome> biomes,
    int weight,
    int regionSize,
    List<ResourceLocation> structureSets,
    List<ResourceLocation> featureDecoratorSets,
    List<ResourceLocation> bushDecoratorSets
) {

    public static final Codec<PathNetworkType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("path_type").forGetter(PathNetworkType::pathType),
        RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biomes").forGetter(PathNetworkType::biomes),
        Codec.INT.fieldOf("weight").forGetter(PathNetworkType::weight),
        Codec.INT.fieldOf("region_size").forGetter(PathNetworkType::regionSize),
        ResourceLocation.CODEC.listOf().optionalFieldOf("structure_sets", List.of()).forGetter(PathNetworkType::structureSets),
        ResourceLocation.CODEC.listOf().optionalFieldOf("feature_decorator_sets", List.of()).forGetter(PathNetworkType::featureDecoratorSets),
        ResourceLocation.CODEC.listOf().optionalFieldOf("bush_decorator_sets", List.of()).forGetter(PathNetworkType::bushDecoratorSets)
    ).apply(instance, PathNetworkType::new));
}
