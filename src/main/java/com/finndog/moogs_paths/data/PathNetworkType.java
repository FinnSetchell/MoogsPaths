package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
//? if >=26.3 {
/*import net.minecraft.core.registries.codec.RegistryCodecs;
*///?} else {
import net.minecraft.core.RegistryCodecs;
//?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Optional;

public record PathNetworkType(
    ResourceLocation pathType,
    HolderSet<Biome> biomes,
    int weight,
    int regionSize,
    List<ResourceLocation> structureSets,
    List<ResourceLocation> featureDecoratorSets,
    List<ResourceLocation> bushDecoratorSets,
    Optional<StructureOrigin> origin
) {

    public static final Codec<PathNetworkType> CODEC = RecordCodecBuilder.<PathNetworkType>create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("path_type").forGetter(PathNetworkType::pathType),
        //? if >=26.3 {
        /*RegistryCodecs.holderSet(Registries.BIOME).fieldOf("biomes").forGetter(PathNetworkType::biomes),
        *///?} else {
        RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biomes").forGetter(PathNetworkType::biomes),
        //?}
        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("weight", 1).forGetter(PathNetworkType::weight),
        // 0 stands for "not given": a network anchored to structures has no regions.
        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("region_size", 0).forGetter(PathNetworkType::regionSize),
        ResourceLocation.CODEC.listOf().optionalFieldOf("structure_sets", List.of()).forGetter(PathNetworkType::structureSets),
        ResourceLocation.CODEC.listOf().optionalFieldOf("feature_decorator_sets", List.of()).forGetter(PathNetworkType::featureDecoratorSets),
        ResourceLocation.CODEC.listOf().optionalFieldOf("bush_decorator_sets", List.of()).forGetter(PathNetworkType::bushDecoratorSets),
        StructureOrigin.CODEC.optionalFieldOf("origin").forGetter(PathNetworkType::origin)
    ).apply(instance, PathNetworkType::new)).flatXmap(PathNetworkType::requireRegionSize, DataResult::success);

    private static DataResult<PathNetworkType> requireRegionSize(PathNetworkType network) {
        if(network.origin.isEmpty() && network.regionSize == 0) {
            return DataResult.error(() -> "region_size is required for a path network without an origin");
        }
        return DataResult.success(network);
    }

    /** True when this network's paths start at structures instead of region origins. */
    public boolean isStructureAnchored() {
        return origin.isPresent();
    }

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
