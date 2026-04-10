package com.finndog.moogs_paths.world;

public final class PathGeometryUtils {
    private PathGeometryUtils() {}

    @FunctionalInterface
    public interface XZConsumer {
        void accept(int x, int z);
    }

    public static void bresenham(int x1, int z1, int x2, int z2, XZConsumer fn) {
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
}
