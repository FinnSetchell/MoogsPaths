package com.finndog.moogs_paths.commands;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.*;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class PathsDebugCommand {
    private PathsDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            literal("paths")
                .requires(src -> src.hasPermission(2))
                .then(literal("debug")
                    .then(literal("region").executes(ctx -> debugRegion(ctx.getSource())))
                    .then(literal("networks").executes(ctx -> debugNetworks(ctx.getSource())))
                    .then(literal("structures").executes(ctx -> debugStructures(ctx.getSource())))
                    .then(literal("reload").executes(ctx -> debugReload(ctx.getSource())))
                )
                .then(literal("locate")
                    .executes(ctx -> locatePath(ctx.getSource(), null))
                    .then(argument("network", ResourceLocationArgument.id())
                        .suggests((ctx, builder) -> {
                            MoogsPathsDatapackRegistries.pathNetworkRegistry(ctx.getSource().registryAccess())
                                .keySet().forEach(id -> builder.suggest(id.toString()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> locatePath(ctx.getSource(), ResourceLocationArgument.getId(ctx, "network")))
                    )
                )
        );
    }

    //////////////////////////////

    private static final int MAX_LOCATE_VERIFY = 32;

    private static int locatePath(CommandSourceStack src, ResourceLocation networkFilter) {
        ServerPlayer player = src.getPlayer();
        if(player == null) {
            src.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        Registry<PathNetworkType> registry = MoogsPathsDatapackRegistries.pathNetworkRegistry(src.registryAccess());
        if(registry.size() == 0) {
            src.sendFailure(Component.literal("[paths] No networks loaded"));
            return 0;
        }

        if(networkFilter != null && !registry.containsKey(networkFilter)) {
            src.sendFailure(Component.literal("[paths] Unknown network: " + networkFilter));
            return 0;
        }

        ServerLevel serverLevel = player.serverLevel();
        long worldSeed = serverLevel.getSeed();
        int playerBX = (int) player.getX();
        int playerBZ = (int) player.getZ();
        int chunkX = playerBX >> 4;
        int chunkZ = playerBZ >> 4;
        int searchRadius = 100000;

        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        RandomState randomState = serverLevel.getChunkSource().randomState();

        Map<Integer, List<PathNetworkType>> byRegionSize = MoogsPathsDatapackRegistries.networksByRegionSize(src.registryAccess());

        record OriginCandidate(int originChunkX, int originChunkZ, int regionSize, List<PathNetworkType> networks, long distSq) {}
        List<OriginCandidate> candidates = new ArrayList<>();

        for(Map.Entry<Integer, List<PathNetworkType>> entry : byRegionSize.entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();
            PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, searchRadius, regionSize)
                .forEach(origin -> {
                    long bx = origin[0] * 16L + 8;
                    long bz = origin[1] * 16L + 8;
                    long dx = bx - playerBX;
                    long dz = bz - playerBZ;
                    candidates.add(new OriginCandidate(origin[0], origin[1], regionSize, networks, dx * dx + dz * dz));
                });
        }

        candidates.sort(Comparator.comparingLong(OriginCandidate::distSq));

        int verified = 0;
        for(OriginCandidate candidate : candidates) {
            if(verified >= MAX_LOCATE_VERIFY) break;
            verified++;

            Optional<PathChunkFeature.EvaluatedOrigin> result = PathChunkFeature.evaluateOrigin(
                serverLevel, generator, randomState, worldSeed,
                candidate.originChunkX(), candidate.originChunkZ(), candidate.regionSize(), candidate.networks());
            if(result.isEmpty()) continue;

            PathChunkFeature.EvaluatedOrigin ev = result.get();

            if(networkFilter != null) {
                ResourceLocation id = registry.getResourceKey(ev.network()).map(ResourceKey::location).orElse(null);
                if(!networkFilter.equals(id)) continue;
            }

            List<BlockPos> waypoints = ev.cachedPath().waypoints();
            BlockPos nearestWp = waypoints.stream()
                .min(Comparator.comparingLong(wp -> {
                    long dx = wp.getX() - playerBX;
                    long dz = wp.getZ() - playerBZ;
                    return dx * dx + dz * dz;
                }))
                .orElse(waypoints.get(0));

            long wpDx = nearestWp.getX() - playerBX;
            long wpDz = nearestWp.getZ() - playerBZ;
            int dist = (int) Math.sqrt(wpDx * wpDx + wpDz * wpDz);

            ResourceLocation networkId = registry.getResourceKey(ev.network())
                .map(ResourceKey::location).orElse(new ResourceLocation("unknown", "unknown"));

            MutableComponent coord = Component.literal("[" + nearestWp.getX() + ", ~, " + nearestWp.getZ() + "]")
                .withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + nearestWp.getX() + " ~ " + nearestWp.getZ()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to teleport")))
                );

            MutableComponent msg = Component.literal("[paths] Nearest " + networkId + " at ")
                .append(coord)
                .append(Component.literal(" (~" + dist + " blocks)"));

            src.sendSuccess(() -> msg, false);
            return 1;
        }

        src.sendFailure(Component.literal("[paths] No path found within " + searchRadius + " blocks"));
        return 0;
    }

    //////////////////////////////

    private static int debugRegion(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if(player == null) {
            src.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        long worldSeed = player.serverLevel().getSeed();
        int blockX = (int) player.getX();
        int blockZ = (int) player.getZ();

        Registry<PathNetworkType> registry = MoogsPathsDatapackRegistries.pathNetworkRegistry(src.registryAccess());
        if(registry.size() == 0) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Region info at your position:"), false);

        registry.stream()
            .map(PathNetworkType::regionSize)
            .distinct()
            .sorted()
            .forEach(regionSize -> {
                String desc = PathRegionSelector.describe(worldSeed, blockX, blockZ, regionSize);
                src.sendSuccess(() -> Component.literal("  regionSize=" + regionSize + " -> " + desc), false);
            });

        return 1;
    }

    private static int debugNetworks(CommandSourceStack src) {
        Registry<PathNetworkType> registry = MoogsPathsDatapackRegistries.pathNetworkRegistry(src.registryAccess());
        if(registry.size() == 0) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Loaded networks (" + registry.size() + "):"), false);
        for(PathNetworkType n : registry) {
            String lengthStr = MoogsPathsDatapackRegistries.getPathType(src.registryAccess(), n.pathType())
                .map(pt -> pt.length().getMinValue() + "-" + pt.length().getMaxValue())
                .orElse("?");
            String line = "  pathType=" + n.pathType()
                + " length=" + lengthStr
                + " regionSize=" + n.regionSize()
                + " weight=" + n.weight()
                + " structureSets=" + n.structureSets().size()
                + " decoratorSets=" + n.featureDecoratorSets().size();
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int debugStructures(CommandSourceStack src) {
        Map<ResourceLocation, Optional<StructureTemplate>> snapshot = PathDataManager.getCachedTemplatesSnapshot();
        src.sendSuccess(() -> Component.literal("[paths] Cached structure templates: " + snapshot.size()), false);

        snapshot.forEach((id, tmpl) -> {
            String state = tmpl.isPresent() ? "ok" : "MISSING";
            src.sendSuccess(() -> Component.literal("  " + id + " [" + state + "]"), false);
        });
        return 1;
    }

    private static int debugReload(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("[paths] Reloading datapacks..."), false);
        var packIds = src.getServer().getResourceManager().listPacks()
            .map(pack -> pack.packId())
            .toList();
        src.getServer().reloadResources(packIds)
            .thenRun(() -> {
                PathDataManager.clearCaches();
                MoogsPathsDatapackRegistries.invalidateDerivedViews();
                src.sendSuccess(() -> Component.literal("[paths] Reload complete (note: path_type/path_network/structure_set/feature_decorator_set/bush_decorator_set are datapack registries and require a world restart on 1.20.1)"), false);
            });
        return 1;
    }
}
