package com.finndog.moogs_paths.config;

import com.finndog.moogs_paths.Constants;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Path networks shipped inside mod jars, found by file name: the config screen opens before any world
 * has loaded the datapack registries, so it lists what the jars hold plus what the config file knows.
 */
public final class BundledPathNetworks {
    private BundledPathNetworks() {}

    private static final String NETWORK_FOLDER = "/moogs_paths/path_network/";

    /** Adds every network under a mod's {@code data} folder, looking only in each namespace's network folder. */
    public static void scanDataFolder(Path data, Set<ResourceLocation> out) {
        if(data == null || !Files.isDirectory(data)) return;
        try(Stream<Path> namespaces = Files.list(data)) {
            for(Path namespace : namespaces.toList()) {
                Path folder = namespace.resolve("moogs_paths").resolve("path_network");
                if(!Files.isDirectory(folder)) continue;
                try(Stream<Path> walk = Files.walk(folder)) {
                    walk.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> addIfNetwork("data/" + data.relativize(p).toString().replace('\\', '/'), out));
                }
            }
        } catch(Exception ex) {
            Constants.LOG.debug("Could not scan {} for path networks: {}", data, ex.toString());
        }
    }

    /** Adds the network a mod file stands for, if it is one: {@code data/<namespace>/moogs_paths/path_network/<path>.json}. */
    public static void addIfNetwork(String file, Set<ResourceLocation> out) {
        String path = file.replace('\\', '/');
        int at = path.indexOf(NETWORK_FOLDER);
        if(at < 0 || !path.endsWith(".json")) return;
        String namespace = path.substring(path.lastIndexOf('/', at - 1) + 1, at);
        String name = path.substring(at + NETWORK_FOLDER.length(), path.length() - ".json".length());
        ResourceLocation id = ResourceLocation.tryParse(namespace + ":" + name);
        if(id != null) out.add(id);
    }
}
