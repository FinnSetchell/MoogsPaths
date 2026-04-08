package com.finndog.moogs_paths.platform.services;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;

import java.util.Map;
import java.util.function.Consumer;

public interface IPlatformHelper {

    void registerReloadListeners(Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners);

    void registerServerStartingListener(Consumer<MinecraftServer> listener);

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
