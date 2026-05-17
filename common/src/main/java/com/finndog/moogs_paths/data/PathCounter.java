package com.finndog.moogs_paths.data;

public enum PathCounter {
    A_STAR_ITERATIONS,
    ORIGIN_REJECTED_BY_CACHE,
    ORIGIN_REJECTED_BY_BIOME,
    ORIGIN_ACCEPTED,
    PATH_DROPPED_TOO_SHORT,
    // how many times memoiseBiome returned a cached boolean (no getBiome call)
    PATHFINDER_BIOME_MEMO_HIT,
    // how many times getOrComputeWaypoints actually ran A* (vs returning a pre-cached result)
    PATH_ACTUALLY_COMPUTED,
    // coarse cell biome said reject but exact origin coord would have passed
    ORIGIN_COARSE_FALSE_NEG,
    // coarse cell biome said pass but exact origin coord would have rejected
    ORIGIN_COARSE_FALSE_POS,
    // A* exited because bestReachedH dropped below PROXIMITY_FRACTION * startToGoal
    PATH_BAILED_ON_PROXIMITY,
    // A* exited because no progress on bestReachedH for STAGNATION_LIMIT pops
    PATH_BAILED_ON_STAGNATION
}
