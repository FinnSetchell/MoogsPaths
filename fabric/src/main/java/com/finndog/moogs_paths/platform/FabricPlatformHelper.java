package com.finndog.moogs_paths.platform;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        DynamicRegistries.register(key, codec);
    }

    @Override
    public void registerServerStartingListener(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STARTING.register(listener::accept);
    }

    @Override
    public void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> listener.accept(dispatcher));
    }

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
