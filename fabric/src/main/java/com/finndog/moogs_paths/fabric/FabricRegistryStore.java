package com.finndog.moogs_paths.fabric;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FabricRegistryStore {

    private static final List<RegistryDataLoader.RegistryData<?>> ENTRIES = new ArrayList<>();

    public static <T> void add(ResourceKey<Registry<T>> key, Codec<T> codec) {
        ENTRIES.add(new RegistryDataLoader.RegistryData<>(key, codec));
    }

    public static List<RegistryDataLoader.RegistryData<?>> getEntries() {
        return Collections.unmodifiableList(ENTRIES);
    }
}
