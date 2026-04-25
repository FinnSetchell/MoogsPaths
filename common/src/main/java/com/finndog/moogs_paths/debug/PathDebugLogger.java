package com.finndog.moogs_paths.debug;

import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PathDebugLogger {

    private static final Logger LOGGER = LogManager.getLogger("moogs_paths_debug");
    private static final Gson GSON = new Gson();
    private static PrintWriter writer;

    private PathDebugLogger() {}

    public record BranchResult(List<BlockPos> waypoints, boolean terminatedEarly) {}

    public static synchronized void init(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("moogs_paths_debug.jsonl");
            writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), false)));
            LOGGER.info("[moogs_paths_debug] Writing path generation log to {}", logFile.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("[moogs_paths_debug] Failed to open log file", e);
        }
    }

    public static synchronized void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    public static synchronized void logWalk(
        BlockPos origin,
        String pathTypeName,
        int regionSize,
        int widthMin,
        int widthMax,
        float curviness,
        int maxSlope,
        float slopeWeight,
        List<BranchResult> branches
    ) {
        if (writer == null) return;

        List<Map<String, Object>> pathEntries = new ArrayList<>();
        for (BranchResult branch : branches) {
            List<int[]> points = new ArrayList<>(branch.waypoints().size());
            for (BlockPos p : branch.waypoints()) points.add(new int[]{p.getX(), p.getZ()});
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("waypoints", points);
            entry.put("terminated_early", branch.terminatedEarly());
            pathEntries.add(entry);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("width_min", widthMin);
        params.put("width_max", widthMax);
        params.put("curviness", curviness);
        params.put("max_slope", maxSlope);
        params.put("slope_weight", slopeWeight);

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("type", "walk");
        log.put("origin", new int[]{origin.getX(), origin.getZ()});
        log.put("path_type", pathTypeName);
        log.put("region_size", regionSize);
        log.put("params", params);
        log.put("paths", pathEntries);

        writer.println(GSON.toJson(log));
        writer.flush();
    }
}
