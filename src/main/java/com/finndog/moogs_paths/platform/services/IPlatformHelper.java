package com.finndog.moogs_paths.platform.services;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface IPlatformHelper {

    <T> void registerDatapackRegistry(ResourceKey<Registry<T>> key, Codec<T> codec);

    // The instance's config folder.
    Path getConfigDir();

    // Path networks the installed mods ship (not world datapacks), for the config screen.
    Set<ResourceLocation> bundledPathNetworks();

    // A mod's display name, for the config screen's groups.
    Optional<String> modName(String namespace);

    // Before the server creates its levels, so before the spawn area generates, on every loader.
    void registerServerAboutToStartListener(Consumer<MinecraftServer> listener);

    // Once the spawn area has generated.
    void registerServerStartedListener(Consumer<MinecraftServer> listener);

    void registerCommandListener(Consumer<CommandDispatcher<CommandSourceStack>> listener);

    // Deferred-path-gen hooks.
    void registerLevelLoadListener(Consumer<ServerLevel> listener);

    void registerChunkLoadListener(BiConsumer<ServerLevel, LevelChunk> listener);

    void registerServerTickEndListener(Consumer<MinecraftServer> listener);

    void registerServerStoppingListener(Consumer<MinecraftServer> listener);
}
