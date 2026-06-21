package com.finndog.moogs_paths.commands;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.finndog.moogs_paths.world.deferred.DeferredPathJob;
import com.finndog.moogs_paths.world.deferred.PlacementTickPump;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
                .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(literal("debug")
                    .then(literal("region").executes(ctx -> debugRegion(ctx.getSource())))
                    .then(literal("networks").executes(ctx -> debugNetworks(ctx.getSource())))
                    .then(literal("structures").executes(ctx -> debugStructures(ctx.getSource())))
                    .then(literal("reload").executes(ctx -> debugReload(ctx.getSource())))
                )
                .then(literal("locate")
                    .executes(ctx -> locatePath(ctx.getSource(), null))
                    .then(argument("network", IdentifierArgument.id())
                        .suggests((ctx, builder) -> {
                            MoogsPathsDatapackRegistries.pathNetworkRegistry(ctx.getSource().registryAccess())
                                .listElementIds()
                                .forEach(rk -> builder.suggest(rk.identifier().toString()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> locatePath(ctx.getSource(), IdentifierArgument.getId(ctx, "network")))
                    )
                )
        );
    }

    //////////////////////////////

    private static final int MAX_LOCATE_VERIFY = 32;

    private static int locatePath(CommandSourceStack src, Identifier networkFilter) {
        ServerPlayer player = src.getPlayer();
        if(player == null) {
            src.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        HolderLookup.RegistryLookup<PathNetworkType> registry = MoogsPathsDatapackRegistries.pathNetworkRegistry(src.registryAccess());
        if(registry.listElementIds().findAny().isEmpty()) {
            src.sendFailure(Component.literal("[paths] No networks loaded"));
            return 0;
        }

        if(networkFilter != null && registry.get(ResourceKey.create(MoogsPathsDatapackRegistries.PATH_NETWORK, networkFilter)).isEmpty()) {
            src.sendFailure(Component.literal("[paths] Unknown network: " + networkFilter));
            return 0;
        }

        ServerLevel serverLevel = player.level();
        long worldSeed = serverLevel.getSeed();
        int playerBX = (int) player.getX();
        int playerBZ = (int) player.getZ();
        int chunkX = playerBX >> 4;
        int chunkZ = playerBZ >> 4;
        int searchRadius = 10000;

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

            long pathSeed = worldSeed
                ^ ((long) candidate.originChunkX() * PathChunkFeature.ORIGIN_X_MULT)
                ^ ((long) candidate.originChunkZ() * PathChunkFeature.ORIGIN_Z_MULT)
                ^ ((long) candidate.regionSize() * PathChunkFeature.ORIGIN_REGION_SIZE_MULT)
                ^ PathChunkFeature.PATH_SEED_MIXER;

            if(networkFilter != null) {
                Optional<PathNetworkType> selected = PathChunkFeature.selectNetworkAt(
                    generator, randomState, candidate.originChunkX(), candidate.originChunkZ(), pathSeed, candidate.networks());
                if(selected.isEmpty()) continue;
                Identifier expectedId = findId(registry, selected.get());
                if(!networkFilter.equals(expectedId)) continue;
            }

            // already-rejected origins are free to skip - biome/pathfinder already determined they
            // can't produce a path here, so they would just return empty from evaluateOrigin anyway
            if(PathDataManager.isRejected(pathSeed)) continue;

            verified++;

            Optional<PathChunkFeature.EvaluatedOrigin> result = PathChunkFeature.evaluateOrigin(
                serverLevel, generator, randomState, worldSeed,
                candidate.originChunkX(), candidate.originChunkZ(), candidate.regionSize(), candidate.networks());
            if(result.isEmpty()) continue;

            PathChunkFeature.EvaluatedOrigin ev = result.get();

            if(networkFilter != null) {
                Identifier id = findId(registry, ev.network());
                if(!networkFilter.equals(id)) continue;
            }

            // /locate computed the path synchronously into PathDataManager's cache but never
            // told the deferred placement system about it. Without this enqueue, the player can
            // teleport to the reported location and find no blocks placed: chunk-load handlers
            // check DeferredPathState.pending, see no job for this pathSeed, and skip placement.
            // Enqueueing here puts the job into pending so chunks loading at the destination
            // will trigger LiveChunkPlacer. Idempotent: if the job is already pending (from a
            // prior worldgen pass), DeferredPathState.addPending no-ops.
            Identifier networkIdForEnqueue = findId(registry, ev.network());
            if(networkIdForEnqueue != null) {
                DeferredPathJob job = new DeferredPathJob(
                    ev.pathSeed(),
                    candidate.originChunkX(),
                    candidate.originChunkZ(),
                    candidate.regionSize(),
                    networkIdForEnqueue
                );
                PlacementTickPump.enqueueFromWorldgen(serverLevel, job);
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

            Identifier foundId = findId(registry, ev.network());
            Identifier networkId = foundId != null ? foundId : Identifier.fromNamespaceAndPath("unknown", "unknown");

            MutableComponent coord = Component.literal("[" + nearestWp.getX() + ", ~, " + nearestWp.getZ() + "]")
                .withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + nearestWp.getX() + " ~ " + nearestWp.getZ()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to teleport")))
                );

            MutableComponent msg = Component.literal("[paths] Nearest " + networkId + " at ")
                .append(coord)
                .append(Component.literal(" (~" + dist + " blocks)"));

            src.sendSuccess(() -> msg, false);
            return 1;
        }

        String target = networkFilter != null ? networkFilter.toString() : "nearest";
        src.sendFailure(Component.literal("[paths] No " + target + " path found within " + searchRadius + " blocks"));
        return 0;
    }

    //////////////////////////////

    private static int debugRegion(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if(player == null) {
            src.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        long worldSeed = player.level().getSeed();
        int blockX = (int) player.getX();
        int blockZ = (int) player.getZ();

        HolderLookup.RegistryLookup<PathNetworkType> registry = MoogsPathsDatapackRegistries.pathNetworkRegistry(src.registryAccess());
        List<PathNetworkType> networks = registry.listElements().map(Holder::value).toList();
        if(networks.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Region info at your position:"), false);

        networks.stream()
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
        HolderLookup.RegistryLookup<PathNetworkType> registry = MoogsPathsDatapackRegistries.pathNetworkRegistry(src.registryAccess());
        List<PathNetworkType> networks = registry.listElements().map(Holder::value).toList();
        if(networks.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Loaded networks (" + networks.size() + "):"), false);
        for(PathNetworkType n : networks) {
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

    // Reverse lookup: find the Identifier of a network instance by reference equality.
    // PathChunkFeature returns the same instances that are stored in the registry, so == works.
    private static Identifier findId(HolderLookup.RegistryLookup<PathNetworkType> registry, PathNetworkType target) {
        return registry.listElements()
            .filter(h -> h.value() == target)
            .findFirst()
            .map(h -> h.key().identifier())
            .orElse(null);
    }

    private static int debugStructures(CommandSourceStack src) {
        Map<Identifier, Optional<StructureTemplate>> snapshot = PathDataManager.getCachedTemplatesSnapshot();
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
