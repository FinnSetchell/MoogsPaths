package com.finndog.moogs_paths.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public sealed interface BiomeFilter permits BiomeFilter.Any, BiomeFilter.Whitelist, BiomeFilter.Blacklist, BiomeFilter.TagFilter, BiomeFilter.And {

    boolean test(ResourceLocation biomeId);

    Codec<BiomeFilter> DISPATCH_CODEC = Codec.STRING.dispatch(
        "type",
        filter -> {
            if(filter instanceof Any) return "any";
            if(filter instanceof Whitelist) return "whitelist";
            if(filter instanceof Blacklist) return "blacklist";
            if(filter instanceof TagFilter) return "tag";
            if(filter instanceof And) return "and";
            throw new IllegalArgumentException("Unknown BiomeFilter type: " + filter);
        },
        type -> switch(type) {
            case "any" -> Any.CODEC;
            case "whitelist" -> Whitelist.CODEC;
            case "blacklist" -> Blacklist.CODEC;
            case "tag" -> TagFilter.CODEC;
            case "and" -> And.CODEC;
            default -> throw new IllegalArgumentException("Unknown BiomeFilter type: " + type);
        }
    );

    record Any() implements BiomeFilter {
        static final MapCodec<Any> CODEC = MapCodec.unit(new Any());

        @Override
        public boolean test(ResourceLocation biomeId) {
            return true;
        }
    }

    record Whitelist(List<ResourceLocation> biomes) implements BiomeFilter {
        static final MapCodec<Whitelist> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.listOf().fieldOf("biomes").forGetter(Whitelist::biomes)
        ).apply(instance, Whitelist::new));

        @Override
        public boolean test(ResourceLocation biomeId) {
            return biomes.contains(biomeId);
        }
    }

    record Blacklist(List<ResourceLocation> biomes) implements BiomeFilter {
        static final MapCodec<Blacklist> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.listOf().fieldOf("biomes").forGetter(Blacklist::biomes)
        ).apply(instance, Blacklist::new));

        @Override
        public boolean test(ResourceLocation biomeId) {
            return !biomes.contains(biomeId);
        }
    }

    record TagFilter(ResourceLocation tag) implements BiomeFilter {
        static final MapCodec<TagFilter> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("tag").forGetter(TagFilter::tag)
        ).apply(instance, TagFilter::new));

        @Override
        public boolean test(ResourceLocation biomeId) {
            return false; // tag membership requires registry access; callers must resolve at runtime
        }
    }

    record And(List<BiomeFilter> filters) implements BiomeFilter {
        static final MapCodec<And> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.list(BiomeFilter.DISPATCH_CODEC).fieldOf("filters").forGetter(And::filters)
        ).apply(instance, And::new));

        @Override
        public boolean test(ResourceLocation biomeId) {
            return filters.stream().allMatch(f -> f.test(biomeId));
        }
    }
}
