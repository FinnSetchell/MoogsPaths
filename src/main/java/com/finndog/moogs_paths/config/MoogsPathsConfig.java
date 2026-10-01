package com.finndog.moogs_paths.config;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The instance's {@code config/moogs_paths.json}: how often each path network lays a path, from 0
 * (never) to 100 (every time, the default).
 *
 * <p>The chance is a roll seeded by the path itself (a region path's seed, or a structure and network
 * for structure-anchored paths), so it never moves a path: turning a network down only leaves some of
 * its paths out, and the paths kept at 50 are among those kept at 75. Region networks still pick the
 * same network at every origin; a roll that misses leaves that origin without a path.
 *
 * <p>Read when the server is about to start, before any chunk generates, and by {@code /paths debug
 * reload}. Every loaded network is then written into the file, so it lists what exists, networks from
 * other mods and datapacks included. Entries for networks that aren't loaded are kept, so a mod or
 * datapack missing for one session doesn't lose its settings.
 */
public final class MoogsPathsConfig {
    private MoogsPathsConfig() {}

    public static final String FILE_NAME = "moogs_paths.json";
    public static final int MAX_CHANCE = 100;

    private static final String CHANCE_KEY = "path_chance";
    private static final String INFO_KEY = "_info";
    private static final String INFO = "How often each path network lays a path, from 0 (never) to 100 (every time). "
        + "Read when a world starts, or by /paths debug reload; applies to chunks that haven't had their paths laid yet.";
    private static final long RARITY_MIXER = 0xC2B2AE3D27D4EB4FL;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Comparator<ResourceLocation> BY_ID = Comparator.comparing(ResourceLocation::toString);

    // Read by worldgen threads; replaced whole, never changed in place.
    private record Snapshot(Map<ResourceLocation, Integer> byId, Map<PathNetworkType, Integer> byNetwork) {}

    private static volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /** Reads the file, adds every loaded network to it and writes it back. */
    public static void load(Path configDir, RegistryAccess access) {
        Path file = configDir.resolve(FILE_NAME);
        Map<ResourceLocation, Integer> read = readFile(configDir);
        boolean readable = read != null;
        Map<ResourceLocation, Integer> fromFile = readable ? read : new TreeMap<>(BY_ID);

        Map<ResourceLocation, PathNetworkType> networks = MoogsPathsDatapackRegistries.networksById(access);
        Map<PathNetworkType, Integer> byNetwork = new IdentityHashMap<>();
        Map<ResourceLocation, Integer> all = new TreeMap<>(BY_ID);
        all.putAll(fromFile);
        networks.forEach((id, network) -> {
            int chance = fromFile.getOrDefault(id, MAX_CHANCE);
            all.put(id, chance);
            byNetwork.put(network, chance);
        });
        snapshot = new Snapshot(Collections.unmodifiableMap(all), Collections.unmodifiableMap(byNetwork));

        var unloaded = fromFile.keySet().stream().filter(id -> !networks.containsKey(id)).map(ResourceLocation::toString).toList();
        if(!unloaded.isEmpty()) Constants.LOG.info("{} keeps settings for path networks that aren't loaded: {}", FILE_NAME, String.join(", ", unloaded));
        if(readable) write(file, all);
    }

    /** The chance for a loaded network, 0 to 100. */
    public static int chance(PathNetworkType network) {
        Integer chance = snapshot.byNetwork().get(network);
        return chance == null ? MAX_CHANCE : chance;
    }

    /** The chance for a network by id, loaded or not, 0 to 100. */
    public static int chance(ResourceLocation id) {
        Integer chance = snapshot.byId().get(id);
        return chance == null ? MAX_CHANCE : chance;
    }

    /** Every network the file knows, loaded or not, in id order. */
    public static Map<ResourceLocation, Integer> chances() {
        return snapshot.byId();
    }

    /** Whether a path rolled from this seed is laid at this chance. The same seed always rolls the same. */
    public static boolean keeps(long seed, int chance) {
        if(chance >= MAX_CHANCE) return true;
        if(chance <= 0) return false;
        return Math.floorMod(mix(seed ^ RARITY_MIXER), MAX_CHANCE) < chance;
    }

    /**
     * The chances in the file, in id order: empty when there is no file yet, null when it can't be read
     * (it is then left alone, so a typo doesn't cost the rest of it).
     */
    public static Map<ResourceLocation, Integer> readFile(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        Map<ResourceLocation, Integer> chances = new TreeMap<>(BY_ID);
        if(!Files.exists(file)) return chances;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject list = root.has(CHANCE_KEY) ? root.getAsJsonObject(CHANCE_KEY) : new JsonObject();
            for(Map.Entry<String, JsonElement> e : list.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                if(id == null || !e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) {
                    Constants.LOG.warn("Ignoring {} entry {}: {}", FILE_NAME, e.getKey(), e.getValue());
                    continue;
                }
                chances.put(id, clamp((int) Math.round(e.getValue().getAsDouble())));
            }
            return chances;
        } catch(Exception ex) {
            Constants.LOG.error("Could not read {} ({}); every path network runs at {} until it is fixed", file, ex.toString(), MAX_CHANCE);
            return null;
        }
    }

    /**
     * Saves chances from the config screen into the file, keeping every other entry. The running game
     * picks them up on the next world start or /paths debug reload, like edits to the file. Returns
     * false when the file can't be read, so it isn't overwritten.
     */
    public static boolean save(Path configDir, Map<ResourceLocation, Integer> changes) {
        Map<ResourceLocation, Integer> all = readFile(configDir);
        if(all == null) return false;
        changes.forEach((id, chance) -> all.put(id, clamp(chance)));
        write(configDir.resolve(FILE_NAME), all);
        return true;
    }

    private static void write(Path file, Map<ResourceLocation, Integer> chances) {
        JsonObject root = new JsonObject();
        root.addProperty(INFO_KEY, INFO);
        JsonObject list = new JsonObject();
        chances.forEach((id, chance) -> list.addProperty(id.toString(), chance));
        root.add(CHANCE_KEY, list);
        String text = GSON.toJson(root) + "\n";
        try {
            if(Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return;
            Files.createDirectories(file.getParent());
            // Written beside the file, then moved over it, so a crash mid-write never leaves half a file.
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch(AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException ex) {
            Constants.LOG.error("Could not write {}: {}", file, ex.toString());
        }
    }

    private static int clamp(int chance) {
        return Math.max(0, Math.min(MAX_CHANCE, chance));
    }

    // SplitMix64's finaliser: spreads nearby seeds over the whole range before the roll.
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
