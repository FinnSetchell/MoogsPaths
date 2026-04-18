package com.finndog.moogs_paths.platform.services;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;

import java.util.Map;
import java.util.function.Consumer;

public interface IPlatformHelper {

    void registerReloadListeners(Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners);

    <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec);

    void registerServerStartingListener(Consumer<MinecraftServer> listener);

    void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener);

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
