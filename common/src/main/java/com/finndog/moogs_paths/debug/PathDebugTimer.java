package com.finndog.moogs_paths.debug;

import com.finndog.moogs_paths.data.BiomeCallSite;
import com.finndog.moogs_paths.data.PathCounter;
import com.finndog.moogs_paths.data.PathDataManager;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PathDebugTimer {

    private static final Logger LOGGER = LogManager.getLogger("moogs_paths_debug");
    private static final Gson GSON = new Gson();
    private static final long FLUSH_INTERVAL_MS = 5_000L;

    private static PrintWriter writer;
    private static Thread flushThread;
    private static volatile boolean running;

    private static final long[] PREV_NS = new long[Stage.values().length];
    private static final long[] PREV_CALLS = new long[Stage.values().length];
    private static final long[] PREV_BIOME_CALLS = new long[BiomeCallSite.values().length];
    private static final long[] PREV_PATH_COUNTERS = new long[PathCounter.values().length];
    private static long prevFlushMs = 0L;

    private PathDebugTimer() {}

    public enum Stage {
        OTHER, ORIGIN_ENUM, ORIGIN_BIOME, PATHFIND, RASTER, STRUCTURES, FEATURES, BUSHES
    }

    private static final class ThreadState {
        final long[] ns = new long[Stage.values().length];
        final long[] calls = new long[Stage.values().length];
        Stage current = Stage.OTHER;
        long lastStamp = 0L;
    }

    private static final List<ThreadState> ALL_STATES = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<ThreadState> LOCAL = ThreadLocal.withInitial(() -> {
        ThreadState s = new ThreadState();
        ALL_STATES.add(s);
        return s;
    });

    // one volatile read + return when disabled; JIT branch-predicts this away on steady-state
    public static void stamp(Stage next) {
        if(!running) return;
        long now = System.nanoTime();
        ThreadState s = LOCAL.get();
        if(s.lastStamp != 0L) {
            int idx = s.current.ordinal();
            s.ns[idx] += now - s.lastStamp;
            s.calls[idx]++;
        }
        s.current = next;
        s.lastStamp = now;
    }

    public static void begin() {
        if(!running) return;
        ThreadState s = LOCAL.get();
        s.current = Stage.OTHER;
        s.lastStamp = System.nanoTime();
    }

    public static void end() {
        if(!running) return;
        stamp(Stage.OTHER);
        LOCAL.get().lastStamp = 0L;
    }

    public static synchronized void init(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("moogs_paths_debug_timings.jsonl");
            writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), false)));
            LOGGER.info("[moogs_paths_debug] Writing timing log to {}", logFile.toAbsolutePath());
            prevFlushMs = System.currentTimeMillis();

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "session_start");
            header.put("started_at", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            header.put("started_at_epoch_ms", prevFlushMs);
            writer.println(GSON.toJson(header));
            writer.flush();

            running = true;
            flushThread = new Thread(PathDebugTimer::flushLoop, "moogs_paths_debug-flush");
            flushThread.setDaemon(true);
            flushThread.start();
        } catch (IOException e) {
            LOGGER.error("[moogs_paths_debug] Failed to open timing log file", e);
        }
    }

    public static synchronized void close() {
        running = false;
        if(flushThread != null) flushThread.interrupt();
        flush();
        if(writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private static void flushLoop() {
        while(running) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            flush();
        }
    }

    private static synchronized void flush() {
        if(writer == null) return;

        int n = Stage.values().length;
        long[] nsTotals = new long[n];
        long[] callTotals = new long[n];
        for(ThreadState s : ALL_STATES) {
            for(int i = 0; i < n; i++) {
                nsTotals[i] += s.ns[i];
                callTotals[i] += s.calls[i];
            }
        }

        long[] dNs = new long[n];
        long[] dCalls = new long[n];
        boolean any = false;
        for(int i = 0; i < n; i++) {
            dNs[i] = nsTotals[i] - PREV_NS[i];
            dCalls[i] = callTotals[i] - PREV_CALLS[i];
            if(dNs[i] > 0) any = true;
        }
        System.arraycopy(nsTotals, 0, PREV_NS, 0, n);
        System.arraycopy(callTotals, 0, PREV_CALLS, 0, n);

        long nowMs = System.currentTimeMillis();
        long windowMs = nowMs - prevFlushMs;
        prevFlushMs = nowMs;

        if(!any) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "timings");
        entry.put("window_ms", windowMs);
        Map<String, Object> stages = new LinkedHashMap<>();
        Stage[] values = Stage.values();
        for(int i = 0; i < n; i++) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("ms", dNs[i] / 1_000_000L);
            bucket.put("calls", dCalls[i]);
            stages.put(values[i].name().toLowerCase(Locale.ROOT), bucket);
        }
        entry.put("stages", stages);

        writer.println(GSON.toJson(entry));

        BiomeCallSite[] sites = BiomeCallSite.values();
        Map<String, Object> biomeEntry = new LinkedHashMap<>();
        biomeEntry.put("type", "biome_calls");
        biomeEntry.put("window_ms", windowMs);
        Map<String, Long> siteCounts = new LinkedHashMap<>();
        boolean anyBiome = false;
        for(int i = 0; i < sites.length; i++) {
            long total = PathDataManager.readBiomeCallCount(sites[i]);
            long delta = total - PREV_BIOME_CALLS[i];
            PREV_BIOME_CALLS[i] = total;
            siteCounts.put(sites[i].name().toLowerCase(Locale.ROOT), delta);
            if(delta > 0) anyBiome = true;
        }
        if(anyBiome) {
            biomeEntry.put("sites", siteCounts);
            writer.println(GSON.toJson(biomeEntry));
        }

        PathCounter[] counters = PathCounter.values();
        Map<String, Object> pathCounterEntry = new LinkedHashMap<>();
        pathCounterEntry.put("type", "path_counters");
        pathCounterEntry.put("window_ms", windowMs);
        Map<String, Long> counterValues = new LinkedHashMap<>();
        boolean anyPathCounter = false;
        for(int i = 0; i < counters.length; i++) {
            long total = PathDataManager.readPathCounter(counters[i]);
            long delta = total - PREV_PATH_COUNTERS[i];
            PREV_PATH_COUNTERS[i] = total;
            counterValues.put(counters[i].name().toLowerCase(Locale.ROOT), delta);
            if(delta > 0) anyPathCounter = true;
        }
        if(anyPathCounter) {
            pathCounterEntry.put("counters", counterValues);
            writer.println(GSON.toJson(pathCounterEntry));
        }
        writer.flush();
    }
}
