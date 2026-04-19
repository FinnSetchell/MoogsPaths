package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.data.ScaleSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.util.*;
import java.util.function.BiFunction;

public final class PathWalker {
    private PathWalker() {}

    public static List<List<BlockPos>> walkWithBranches(BlockPos origin, PathNetworkType network, PathType pathType, RandomSource random, BiFunction<Integer, Integer, Integer> heightAt) {
        // Terrain-unaware path types (maxSlopePerStep == 0 && slopeCostWeight == 0) don't use
        // heightmap samples for steering, so skip passing heightAt through and avoid ~thousands
        // of getBaseHeight calls per walk.
        BiFunction<Integer, Integer, Integer> effectiveHeightAt =
            (pathType.maxSlopePerStep() == 0 && pathType.slopeCostWeight() == 0.0f) ? null : memoise(heightAt);

        List<List<BlockPos>> result = new ArrayList<>();

        List<BlockPos> mainPath = walkSingle(origin, network, pathType, random, 1.0f, effectiveHeightAt);
        result.add(mainPath);

        var branches = network.branches();
        int branchCount = random.nextInt(Math.max(1, branches.maxBranches() - branches.minBranches() + 1)) + branches.minBranches();

        if(mainPath.size() >= 2) {
            for(int i = 0; i < branchCount; i++) {
                BlockPos branchStart = mainPath.get(random.nextInt(mainPath.size()));
                result.add(walkSingle(branchStart, network, pathType, random, branches.lengthFraction(), effectiveHeightAt));
            }
        }

        return result;
    }

    // Wraps the heightAt sampler in a HashMap cache keyed by (x, z). Adjacent candidate
    // directions in terrainAwareSteer sample overlapping columns, so memoising eliminates
    // redundant getBaseHeight noise evaluations across steps AND across branches.
    private static BiFunction<Integer, Integer, Integer> memoise(BiFunction<Integer, Integer, Integer> source) {
        if(source == null) return null;
        HashMap<Long, Integer> cache = new HashMap<>();
        return (x, z) -> {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            Integer cached = cache.get(key);
            if(cached != null) return cached;
            int value = source.apply(x, z);
            cache.put(key, value);
            return value;
        };
    }

    private static List<BlockPos> walkSingle(BlockPos origin, PathNetworkType network, PathType pathType, RandomSource random, float lengthFraction, BiFunction<Integer, Integer, Integer> heightAt) {
        ScaleSettings scale = network.scale();
        int targetLength = Math.round((random.nextInt(Math.max(1, scale.lengthMax - scale.lengthMin + 1)) + scale.lengthMin) * lengthFraction);

        PathDirection dir = PathDirection.VALUES[random.nextInt(8)];
        int width = random.nextInt(Math.max(1, pathType.width().max() - pathType.width().min() + 1)) + pathType.width().min();
        int stepDist = stepDistance(width);

        List<BlockPos> waypoints = new ArrayList<>();
        BlockPos current = origin;
        waypoints.add(current);

        int distanceWalked = 0;
        while(distanceWalked < targetLength) {
            BlockPos next = new BlockPos(current.getX() + dir.dx * stepDist, 0, current.getZ() + dir.dz * stepDist);
            waypoints.add(next);
            current = next;
            distanceWalked += stepDist;
            if(heightAt != null) {
                PathDirection steered = terrainAwareSteer(current, dir, pathType, random, heightAt, stepDist);
                if(steered == null) break;
                dir = steered;
            }
            else {
                dir = steer(dir, pathType.curviness(), random);
            }
        }

        return waypoints;
    }

    // returns null if all directions are impassable (dead end)
    private static PathDirection terrainAwareSteer(BlockPos current, PathDirection dir, PathType pathType, RandomSource random, BiFunction<Integer, Integer, Integer> heightAt, int stepDist) {
        int currentY = heightAt.apply(current.getX(), current.getZ());
        if(currentY < 0) return steer(dir, pathType.curviness(), random);

        int maxSlope = pathType.maxSlopePerStep();
        float slopeWeight = pathType.slopeCostWeight();
        float curviness = pathType.curviness();

        PathDirection best = null;
        float bestScore = Float.MAX_VALUE;

        int currentOrdinal = dir.ordinal();

        for(PathDirection candidate : PathDirection.VALUES) {
            int maxSlopeSeen = 0;
            boolean impassable = false;
            boolean anyLoaded = false;
            int prevY = currentY;

            for(int t = 1; t <= stepDist; t++) {
                int sy = heightAt.apply(
                    current.getX() + candidate.dx * t,
                    current.getZ() + candidate.dz * t
                );
                if(sy < 0) continue;
                anyLoaded = true;
                int s = Math.abs(sy - prevY);
                if(maxSlope > 0 && s > maxSlope) { impassable = true; break; }
                if(s > maxSlopeSeen) maxSlopeSeen = s;
                prevY = sy;
            }

            if(impassable) continue;

            float slopeScore;
            if(!anyLoaded) {
                slopeScore = 0.5f; // fully unloaded, penalise but don't block
            }
            else {
                slopeScore = maxSlope > 0 ? (float) maxSlopeSeen / maxSlope : maxSlopeSeen / 16f;
            }

            int ordinalDist = Math.abs(candidate.ordinal() - currentOrdinal);
            int angularDist = Math.min(ordinalDist, 8 - ordinalDist);
            float dirScore = (angularDist / 4f) * (1f - curviness);

            float noise = random.nextFloat() * 0.15f;
            float totalScore = slopeScore * slopeWeight + dirScore * (1f - slopeWeight) + noise;

            if(totalScore < bestScore) {
                bestScore = totalScore;
                best = candidate;
            }
        }

        return best;
    }

    // always consumes exactly 3 randoms, early return happens after all 3 are consumed
    private static PathDirection steer(PathDirection current, float curviness, RandomSource random) {
        float roll = random.nextFloat();
        float turnRoll = random.nextFloat();
        boolean flip = random.nextBoolean();

        if(roll > curviness) return current;

        int steps = turnRoll < 0.7f ? 1 : 2;
        if(flip) steps = -steps;
        return current.rotate(steps);
    }

    public static int stepDistance(int width) {
        return Math.round(0.667f * (width * width - width + 8));
    }
}
