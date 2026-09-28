package com.finndog.moogs_paths.world;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class PathRegionSelector {
    private PathRegionSelector() {}

    private record OriginKey(long worldSeed, int regionX, int regionZ, int regionSize) {}

    private static final ConcurrentHashMap<OriginKey, int[]> ORIGIN_CACHE = new ConcurrentHashMap<>();
    private static final int ORIGIN_CACHE_CAP = 1024;

    public static int regionX(int chunkX, int regionSize) {
        return Math.floorDiv(chunkX, regionSize);
    }

    public static int regionZ(int chunkZ, int regionSize) {
        return Math.floorDiv(chunkZ, regionSize);
    }

    public static int[] originChunk(long worldSeed, int regionX, int regionZ, int regionSize) {
        OriginKey key = new OriginKey(worldSeed, regionX, regionZ, regionSize);
        int[] cached = ORIGIN_CACHE.get(key);
        if (cached != null) return cached;
        long hash = worldSeed ^ ((long) regionX * 341873128712L) ^ ((long) regionZ * 132897987541L);
        RandomSource r = RandomSource.create(hash);
        int offsetX = r.nextInt(regionSize);
        int offsetZ = r.nextInt(regionSize);
        int[] result = new int[]{ regionX * regionSize + offsetX, regionZ * regionSize + offsetZ };
        int[] existing = ORIGIN_CACHE.putIfAbsent(key, result);
        if (existing != null) return existing;
        if (ORIGIN_CACHE.size() > ORIGIN_CACHE_CAP) ORIGIN_CACHE.clear();
        return result;
    }

    public static List<int[]> originsInRange(long worldSeed, int chunkX, int chunkZ, int maxBlockRadius, int regionSize) {
        int regionRadius = (int) Math.ceil((double) maxBlockRadius / (regionSize * 16.0));
        int myRegionX = regionX(chunkX, regionSize);
        int myRegionZ = regionZ(chunkZ, regionSize);

        int centerBlockX = chunkX * 16 + 8;
        int centerBlockZ = chunkZ * 16 + 8;
        long radiusSq = (long) maxBlockRadius * maxBlockRadius;

        List<int[]> result = new ArrayList<>();
        for (int rx = myRegionX - regionRadius; rx <= myRegionX + regionRadius; rx++) {
            for (int rz = myRegionZ - regionRadius; rz <= myRegionZ + regionRadius; rz++) {
                int[] origin = originChunk(worldSeed, rx, rz, regionSize);
                long dx = (long) (origin[0] * 16 + 8) - centerBlockX;
                long dz = (long) (origin[1] * 16 + 8) - centerBlockZ;
                if (dx * dx + dz * dz <= radiusSq) {
                    result.add(origin);
                }
            }
        }
        return result;
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
