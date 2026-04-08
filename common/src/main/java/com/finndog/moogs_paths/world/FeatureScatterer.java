package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.FeatureDecoratorSet;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.*;

public final class FeatureScatterer {
    private FeatureScatterer() {}

    private static final Set<ResourceLocation> WARNED_MISSING = Collections.synchronizedSet(new HashSet<>());

    public static void scatterInChunk(WorldGenLevel level, ChunkGenerator generator, List<BlockPos> waypoints, List<PathNetworkType.WeightedRef> decoratorSetRefs, int chunkX, int chunkZ, RandomSource random) {
        Registry<PlacedFeature> featureRegistry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);

        for(PathNetworkType.WeightedRef ref : decoratorSetRefs) {
            PathDataManager.getDecoratorSet(ref.id()).ifPresent(set ->
                scatterSet(level, generator, featureRegistry, waypoints, set, chunkX, chunkZ, random));
        }
    }

    //////////////////////////////

    private static void scatterSet(WorldGenLevel level, ChunkGenerator generator, Registry<PlacedFeature> featureRegistry, List<BlockPos> waypoints, FeatureDecoratorSet set, int chunkX, int chunkZ, RandomSource random) {
        if(waypoints.size() < 2) return;

        for(int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos from = waypoints.get(i);
            BlockPos to = waypoints.get(i + 1);

            int segDx = to.getX() - from.getX();
            int segDz = to.getZ() - from.getZ();
            int perpX = -segDz;
            int perpZ = segDx;
            float perpLen = (float) Math.sqrt(perpX * perpX + perpZ * perpZ);
            if(perpLen == 0) continue;

            bresenham(from.getX(), from.getZ(), to.getX(), to.getZ(), (cx, cz) -> {
                if(random.nextFloat() >= set.density()) return;

                PlacedFeature feature = pickWeighted(set.features(), featureRegistry, random);
                if(feature == null) return;

                int side = sideSign(set.side(), random);
                if(side == 0) {
                    tryPlace(level, generator, feature, cx, cz, chunkX, chunkZ, random);
                    return;
                }

                int offset = 1 + (set.scatterWidth() > 1 ? random.nextInt(set.scatterWidth()) : 0);
                int bx = cx + Math.round(perpX / perpLen * offset * side);
                int bz = cz + Math.round(perpZ / perpLen * offset * side);
                tryPlace(level, generator, feature, bx, bz, chunkX, chunkZ, random);
            });
        }
    }

    private static void tryPlace(WorldGenLevel level, ChunkGenerator generator, PlacedFeature feature, int bx, int bz, int chunkX, int chunkZ, RandomSource random) {
        if((bx >> 4) != chunkX || (bz >> 4) != chunkZ) return;
        int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        if(sy <= level.getMinBuildHeight()) return;
        feature.feature().value().place(level, generator, random, new BlockPos(bx, sy, bz));
    }

    private static int sideSign(FeatureDecoratorSet.Side side, RandomSource random) {
        return switch(side) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case BOTH -> random.nextBoolean() ? 1 : -1;
            case CENTER -> 0;
        };
    }

    private static PlacedFeature pickWeighted(List<FeatureDecoratorSet.FeatureEntry> entries, Registry<PlacedFeature> registry, RandomSource random) {
        int total = entries.stream().mapToInt(FeatureDecoratorSet.FeatureEntry::weight).sum();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(FeatureDecoratorSet.FeatureEntry e : entries) {
            cumulative += e.weight();
            if(roll < cumulative) {
                PlacedFeature feature = registry.getOptional(e.feature()).orElse(null);
                if(feature == null && WARNED_MISSING.add(e.feature())) Constants.LOG.warn("Placed feature not found: {}", e.feature());
                return feature;
            }
        }
        return null;
    }

    private static void bresenham(int x1, int z1, int x2, int z2, XZConsumer fn) {
        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;
        int x = x1, z = z1;
        while(true) {
            fn.accept(x, z);
            if(x == x2 && z == z2) break;
            int e2 = 2 * err;
            if(e2 > -dz) { err -= dz; x += sx; }
            if(e2 < dx) { err += dx; z += sz; }
        }
    }

    @FunctionalInterface
    private interface XZConsumer {
        void accept(int x, int z);
    }
}
