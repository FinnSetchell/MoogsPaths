package com.finndog.moogs_paths.world;

import net.minecraft.util.RandomSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class PathRegionSelector {
    private PathRegionSelector() {}

    private static final Map<Long, int[]> ORIGIN_CACHE = new ConcurrentHashMap<>();

    public static int regionX(int chunkX, int regionSize) {
        return Math.floorDiv(chunkX, regionSize);
    }

    public static int regionZ(int chunkZ, int regionSize) {
        return Math.floorDiv(chunkZ, regionSize);
    }

    public static int[] originChunk(long worldSeed, int regionX, int regionZ, int regionSize) {
        long key = worldSeed ^ ((long) regionX << 34) ^ ((long) regionZ << 2) ^ (long) regionSize;
        return ORIGIN_CACHE.computeIfAbsent(key, k -> {
            long hash = worldSeed ^ ((long) regionX * 341873128712L) ^ ((long) regionZ * 132897987541L);
            RandomSource r = RandomSource.create(hash);
            int offsetX = r.nextInt(regionSize);
            int offsetZ = r.nextInt(regionSize);
            return new int[]{ regionX * regionSize + offsetX, regionZ * regionSize + offsetZ };
        });
    }

    public static Stream<int[]> originsInRange(long worldSeed, int chunkX, int chunkZ, int maxBlockRadius, int regionSize) {
        int regionRadius = (int) Math.ceil((double) maxBlockRadius / (regionSize * 16.0));
        int myRegionX = regionX(chunkX, regionSize);
        int myRegionZ = regionZ(chunkZ, regionSize);

        int centerBlockX = chunkX * 16 + 8;
        int centerBlockZ = chunkZ * 16 + 8;
        long radiusSq = (long) maxBlockRadius * maxBlockRadius;

        return IntStream.rangeClosed(myRegionX - regionRadius, myRegionX + regionRadius)
            .boxed()
            .flatMap(rx -> IntStream.rangeClosed(myRegionZ - regionRadius, myRegionZ + regionRadius)
                .mapToObj(rz -> originChunk(worldSeed, rx, rz, regionSize)))
            .filter(origin -> {
                long dx = (long) (origin[0] * 16 + 8) - centerBlockX;
                long dz = (long) (origin[1] * 16 + 8) - centerBlockZ;
                return dx * dx + dz * dz <= radiusSq;
            });
    }

    public static String describe(long worldSeed, int blockX, int blockZ, int regionSize) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        int rx = regionX(chunkX, regionSize);
        int rz = regionZ(chunkZ, regionSize);
        int[] origin = originChunk(worldSeed, rx, rz, regionSize);
        return String.format("region=(%d,%d) origin_chunk=(%d,%d)", rx, rz, origin[0], origin[1]);
    }
}
