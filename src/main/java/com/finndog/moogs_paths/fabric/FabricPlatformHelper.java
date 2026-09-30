package com.finndog.moogs_paths.fabric;

import com.finndog.moogs_paths.platform.services.IPlatformHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
//? if <26.1.2 {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
//?} else {
/*import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
*///?}
//? if >=1.20.1 {
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
//?}
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        //? if >=1.20.1 {
        DynamicRegistries.register(key, codec);
        //?} else {
        /*FabricRegistryStore.add(key, codec);
        *///?}
    }

    // Fabric's SERVER_STARTING fires before any level is created.
    @Override
    public void registerServerAboutToStartListener(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STARTING.register(listener::accept);
    }

    @Override
    public void registerServerStartedListener(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STARTED.register(listener::accept);
    }

    @Override
    public void registerLevelLoadListener(Consumer<ServerLevel> listener) {
        //? if <26.1.2 {
        ServerWorldEvents.LOAD.register((server, level) -> listener.accept(level));
        //?} else {
        /*ServerLevelEvents.LOAD.register((server, level) -> listener.accept(level));
        *///?}
    }

    @Override
    public void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> listener.accept(dispatcher));
    }

    @Override
    public void registerChunkLoadListener(BiConsumer<ServerLevel, LevelChunk> listener) {
        // Fabric 26.1 added a third boolean (newChunk?) arg; we don't care which.
        //? if <26.1.2 {
        ServerChunkEvents.CHUNK_LOAD.register(listener::accept);
        //?} else {
        /*ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) -> listener.accept(level, chunk));
        *///?}
    }

    @Override
    public void registerServerTickEndListener(Consumer<MinecraftServer> listener) {
        ServerTickEvents.END_SERVER_TICK.register(listener::accept);
    }

    @Override
    public void registerServerStoppingListener(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STOPPING.register(listener::accept);
    }
}
