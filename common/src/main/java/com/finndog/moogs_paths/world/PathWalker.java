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
        List<List<BlockPos>> result = new ArrayList<>();

        List<BlockPos> mainPath = walkSingle(origin, network, pathType, random, 1.0f, heightAt);
        result.add(mainPath);

        var branches = network.branches();
        int branchCount = random.nextInt(Math.max(1, branches.maxBranches() - branches.minBranches() + 1)) + branches.minBranches();

        for(int i = 0; i < branchCount; i++) {
            BlockPos branchStart = mainPath.get(random.nextInt(mainPath.size()));
            result.add(walkSingle(branchStart, network, pathType, random, branches.lengthFraction(), heightAt));
        }

        return result;
    }

    private static List<BlockPos> walkSingle(BlockPos origin, PathNetworkType network, PathType pathType, RandomSource random, float lengthFraction, BiFunction<Integer, Integer, Integer> heightAt) {
        ScaleSettings scale = network.scale();
        int targetLength = Math.round((random.nextInt(Math.max(1, scale.lengthMax - scale.lengthMin + 1)) + scale.lengthMin) * lengthFraction);

        PathDirection dir = PathDirection.VALUES[random.nextInt(16)];
        int width = random.nextInt(pathType.width().max() - pathType.width().min() + 1) + pathType.width().min();
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
            if(heightAt != null && pathType.slopeAvoidance() > 0f)
                dir = slopeAwareSteer(current, dir, pathType, random, heightAt, stepDist);
            else
                dir = steer(dir, pathType.curviness(), random);
        }

        return waypoints;
    }

    private static PathDirection slopeAwareSteer(BlockPos current, PathDirection dir, PathType pathType, RandomSource random, BiFunction<Integer, Integer, Integer> heightAt, int stepDist) {
        int currentY = heightAt.apply(current.getX(), current.getZ());
        if (currentY < 0) return steer(dir, pathType.curviness(), random);

        PathDirection best = steer(dir, pathType.curviness(), random);
        int bestSlope = slopeOf(best, current, currentY, heightAt, stepDist);

        for(int i = 0; i < 2; i++) {
            PathDirection alt = steer(dir, pathType.curviness(), random);
            int altSlope = slopeOf(alt, current, currentY, heightAt, stepDist);
            if(altSlope < bestSlope && random.nextFloat() < pathType.slopeAvoidance()) {
                best = alt;
                bestSlope = altSlope;
            }
        }

        return best;
    }

    private static int slopeOf(PathDirection dir, BlockPos current, int currentY, BiFunction<Integer, Integer, Integer> heightAt, int stepDist) {
        int nextY = heightAt.apply(current.getX() + dir.dx * stepDist, current.getZ() + dir.dz * stepDist);
        if (nextY < 0) return 0;
        return Math.abs(nextY - currentY);
    }

    private static PathDirection steer(PathDirection current, float curviness, RandomSource random) {
        float roll = random.nextFloat();
        if(roll > curviness) return current;

        int steps;
        float turnRoll = random.nextFloat();
        if(turnRoll < 0.5f) steps = 1;
        else if(turnRoll < 0.85f) steps = 2;
        else steps = 3;

        if(random.nextBoolean()) steps = -steps;
        return current.rotate(steps);
    }

    public static int stepDistance(int width) {
        return Math.round(0.667f * (width * width - width + 8));
    }
}
