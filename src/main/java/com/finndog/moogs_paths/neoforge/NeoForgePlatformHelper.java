package com.finndog.moogs_paths.neoforge;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
// NeoForge 26.3.0.31 replaced DataPackRegistryEvent.NewRegistry with NewDatapackRegistryEvent.
//? if >=26.3 {
/*import net.neoforged.neoforge.registries.NewDatapackRegistryEvent;
*///?} else {
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NeoForgePlatformHelper implements IPlatformHelper {

    public static IEventBus modEventBus;

    private final List<PendingDatapackRegistry<?>> pendingDatapackRegistries = new ArrayList<>();
    private boolean datapackRegistryListenerSubscribed = false;

    @Override
    public <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        pendingDatapackRegistries.add(new PendingDatapackRegistry<>(key, codec));
        if (!datapackRegistryListenerSubscribed) {
            datapackRegistryListenerSubscribed = true;
            modEventBus.addListener(this::onNewDatapackRegistry);
        }
    }

    //? if >=26.3 {
    /*private void onNewDatapackRegistry(NewDatapackRegistryEvent event) {
    *///?} else {
    private void onNewDatapackRegistry(DataPackRegistryEvent.NewRegistry event) {
    //?}
        for (PendingDatapackRegistry<?> pending : pendingDatapackRegistries) {
            pending.register(event);
        }
    }

    private record PendingDatapackRegistry<T>(ResourceKey<Registry<T>> key, Codec<T> codec) {
        // No network codec: the registries stay on the server, clients never need them.
        //? if >=26.3 {
        /*void register(NewDatapackRegistryEvent event) {
            event.worldRegistry(key, codec);
        }
        *///?} else {
        void register(DataPackRegistryEvent.NewRegistry event) {
            event.dataPackRegistry(key, codec, null);
        }
        //?}
    }

    @Override
    public void registerServerAboutToStartListener(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public void registerServerStartedListener(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public void registerLevelLoadListener(Consumer<ServerLevel> listener) {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if(event.getLevel() instanceof ServerLevel level) listener.accept(level);
        });
    }

    @Override
    public void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> listener.accept(event.getDispatcher()));
    }

    @Override
    public void registerChunkLoadListener(BiConsumer<ServerLevel, LevelChunk> listener) {
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if(!event.getLevel().isClientSide() && event.getLevel() instanceof ServerLevel sl && event.getChunk() instanceof LevelChunk lc) {
                listener.accept(sl, lc);
            }
        });
    }

    @Override
    public void registerServerTickEndListener(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> listener.accept(event.getServer()));
    }

    @Override
    public void registerServerStoppingListener(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> listener.accept(event.getServer()));
    }
}
