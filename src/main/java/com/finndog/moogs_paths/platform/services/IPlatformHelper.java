package com.finndog.moogs_paths.platform.services;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface IPlatformHelper {

    <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec);

    void registerServerStartingListener(Consumer<MinecraftServer> listener);

    void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener);

    // Deferred-path-gen hooks.
    void registerChunkLoadListener(BiConsumer<ServerLevel, LevelChunk> listener);

    void registerServerTickEndListener(Consumer<MinecraftServer> listener);

    void registerServerStoppingListener(Consumer<MinecraftServer> listener);
}
