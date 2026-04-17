package com.finndog.moogs_paths.world;

public enum PathDirection {
    N(0, -1), NNE(1, -2), NE(1, -1), ENE(2, -1),
    E(1, 0),  ESE(2, 1),  SE(1, 1),  SSE(1, 2),
    S(0, 1),  SSW(-1, 2), SW(-1, 1), WSW(-2, 1),
    W(-1, 0), WNW(-2, -1),NW(-1, -1),NNW(-1, -2);

    public final int dx;
    public final int dz;

    public static final PathDirection[] VALUES = values();

    PathDirection(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public PathDirection rotate(int steps) {
        return VALUES[((this.ordinal() + steps) % 16 + 16) % 16];
    }
}
