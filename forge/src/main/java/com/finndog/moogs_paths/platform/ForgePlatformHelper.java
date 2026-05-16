package com.finndog.moogs_paths.platform;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DataPackRegistryEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ForgePlatformHelper implements IPlatformHelper {

    private final List<PendingDatapackRegistry<?>> pendingDatapackRegistries = new ArrayList<>();
    private boolean datapackRegistryListenerSubscribed = false;

    @Override
    public <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        pendingDatapackRegistries.add(new PendingDatapackRegistry<>(key, codec));
        if(!datapackRegistryListenerSubscribed) {
            datapackRegistryListenerSubscribed = true;
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onNewDatapackRegistry);
        }
    }

    private void onNewDatapackRegistry(DataPackRegistryEvent.NewRegistry event) {
        for(PendingDatapackRegistry<?> pending : pendingDatapackRegistries) {
            pending.register(event);
        }
    }

    private record PendingDatapackRegistry<T>(ResourceKey<Registry<T>> key, Codec<T> codec) {
        void register(DataPackRegistryEvent.NewRegistry event) {
            event.dataPackRegistry(key, codec, null);
        }
    }

    @Override
    public void registerServerStartingListener(Consumer<MinecraftServer> listener) {
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> listener.accept(event.getDispatcher()));
    }
}
