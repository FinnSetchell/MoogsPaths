package com.finndog.moogs_paths.platform;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
