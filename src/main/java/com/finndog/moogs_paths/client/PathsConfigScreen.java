package com.finndog.moogs_paths.client;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.config.MoogsPathsConfig;
import com.finndog.moogs_paths.platform.Services;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Cloth Config screen: a 0 to 100 % slider per path network, grouped by the mod that adds it.
 * It edits {@code config/moogs_paths.json} (see {@link MoogsPathsConfig}), so it works from the main
 * menu; a dedicated server reads its own copy of the file. Only loaded when Cloth Config is present.
 */
public final class PathsConfigScreen {
    private PathsConfigScreen() {}

    public static Screen create(Screen parent) {
        Path configDir = Services.PLATFORM.getConfigDir();
        Map<ResourceLocation, Integer> fromFile = MoogsPathsConfig.readFile(configDir);
        boolean readable = fromFile != null;
        Map<ResourceLocation, Integer> current = readable ? fromFile : Map.of();

        // The networks mods ship, plus everything a world has written into the file (datapacks included).
        Set<ResourceLocation> ids = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        ids.addAll(Services.PLATFORM.bundledPathNetworks());
        ids.addAll(current.keySet());
        Map<String, List<ResourceLocation>> byNamespace = new TreeMap<>(Comparator
            .comparing((String ns) -> !ns.equals(Constants.MOD_ID))
            .thenComparing(Comparator.naturalOrder()));
        for(ResourceLocation id : ids) byNamespace.computeIfAbsent(id.getNamespace(), ns -> new ArrayList<>()).add(id);

        Map<ResourceLocation, Integer> changes = new HashMap<>();
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("moogs_paths.config.title"));
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("moogs_paths.config.category"));
        category.addEntry(entries.startTextDescription(Component.translatable("moogs_paths.config.notice").withStyle(ChatFormatting.YELLOW)).build());
        if(!readable) {
            category.addEntry(entries.startTextDescription(Component.translatable("moogs_paths.config.unreadable", MoogsPathsConfig.FILE_NAME).withStyle(ChatFormatting.RED)).build());
        }

        byNamespace.forEach((namespace, networks) -> {
            List<AbstractConfigListEntry> sliders = new ArrayList<>();
            for(ResourceLocation id : networks) {
                int value = current.getOrDefault(id, MoogsPathsConfig.MAX_CHANCE);
                sliders.add(entries.startIntSlider(networkName(id), value, 0, MoogsPathsConfig.MAX_CHANCE)
                    .setDefaultValue(MoogsPathsConfig.MAX_CHANCE)
                    .setTextGetter(v -> Component.literal(v + "%"))
                    .setTooltip(Component.literal(id.toString()))
                    .setSaveConsumer(v -> { if(v != value) changes.put(id, v); })
                    .build());
            }
            String modName = Services.PLATFORM.modName(namespace).orElse(namespace);
            category.addEntry(entries.startSubCategory(Component.literal(modName), sliders)
                .setExpanded(namespace.equals(Constants.MOD_ID))
                .build());
        });

        builder.setSavingRunnable(() -> {
            if(readable && !changes.isEmpty()) MoogsPathsConfig.save(configDir, changes);
            changes.clear();
        });
        return builder.build();
    }

    // A translation when there is one, else the id's path in title case ("village_road_plains" -> "Village Road Plains").
    private static Component networkName(ResourceLocation id) {
        String key = "moogs_paths.network." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        StringBuilder fallback = new StringBuilder();
        for(String word : id.getPath().replace('/', '_').split("_")) {
            if(word.isEmpty()) continue;
            if(!fallback.isEmpty()) fallback.append(' ');
            fallback.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return Component.translatableWithFallback(key, fallback.toString());
    }
}
