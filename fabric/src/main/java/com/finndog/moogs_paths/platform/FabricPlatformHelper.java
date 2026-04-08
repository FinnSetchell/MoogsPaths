package com.finndog.moogs_paths.platform;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public void registerReloadListeners(Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners) {
        listeners.forEach((id, listener) ->
            ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                new IdentifiableResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return id;
                    }

                    @Override
                    public CompletableFuture<Void> reload(
                        PreparationBarrier barrier, ResourceManager resourceManager,
                        ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
                        Executor prepareExecutor, Executor applyExecutor
                    ) {
                        return listener.reload(barrier, resourceManager, prepareProfiler, applyProfiler, prepareExecutor, applyExecutor);
                    }
                }
            )
        );
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
