package com.finndog.moogs_paths.platform;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

import java.util.Map;
import java.util.function.Consumer;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public void registerReloadListeners(Map<ResourceLocation, SimpleJsonResourceReloadListener> listeners) {
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent event) ->
            listeners.values().forEach(event::addListener));
    }

    @Override
    public void registerServerStartingListener(Consumer<MinecraftServer> listener) {
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}
