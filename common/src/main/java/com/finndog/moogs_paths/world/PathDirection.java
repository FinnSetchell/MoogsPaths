package com.finndog.moogs_paths.world;

public enum PathDirection {
    N(0, -1), NE(1, -1), E(1, 0), SE(1, 1),
    S(0, 1),  SW(-1, 1), W(-1, 0),NW(-1, -1);

    public final int dx;
    public final int dz;

    public static final PathDirection[] VALUES = values();

    PathDirection(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public PathDirection rotate(int steps) {
        return VALUES[((this.ordinal() + steps) % 8 + 8) % 8];
    }
}
