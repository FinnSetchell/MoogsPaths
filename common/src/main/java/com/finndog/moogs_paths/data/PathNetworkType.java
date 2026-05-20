package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

public record PathNetworkType(
    Identifier pathType,
    HolderSet<Biome> biomes,
    int weight,
    int regionSize,
    List<Identifier> structureSets,
    List<Identifier> featureDecoratorSets,
    List<Identifier> bushDecoratorSets
) {

    public static final Codec<PathNetworkType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Identifier.CODEC.fieldOf("path_type").forGetter(PathNetworkType::pathType),
        RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biomes").forGetter(PathNetworkType::biomes),
        Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(PathNetworkType::weight),
        Codec.intRange(1, Integer.MAX_VALUE).fieldOf("region_size").forGetter(PathNetworkType::regionSize),
        Identifier.CODEC.listOf().optionalFieldOf("structure_sets", List.of()).forGetter(PathNetworkType::structureSets),
        Identifier.CODEC.listOf().optionalFieldOf("feature_decorator_sets", List.of()).forGetter(PathNetworkType::featureDecoratorSets),
        Identifier.CODEC.listOf().optionalFieldOf("bush_decorator_sets", List.of()).forGetter(PathNetworkType::bushDecoratorSets)
    ).apply(instance, PathNetworkType::new));

    // /locate has to reproduce the exact same weighted pick that worldgen does so the locate
    // result matches what the world actually generates. Centralising means the two callers
    // can't drift.
    public static PathNetworkType pickWeighted(List<PathNetworkType> eligible, RandomSource random) {
        int total = 0;
        for(PathNetworkType n : eligible) total += n.weight();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(PathNetworkType n : eligible) {
            cumulative += n.weight();
            if(roll < cumulative) return n;
        }
        return eligible.get(0);
    }
}
