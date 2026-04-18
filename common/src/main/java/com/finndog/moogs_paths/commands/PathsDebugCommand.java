package com.finndog.moogs_paths.commands;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathDirection;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.*;
import java.util.stream.Collectors;

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

        long worldSeed = player.serverLevel().getSeed();
        int playerBX = (int) player.getX();
        int playerBZ = (int) player.getZ();
        int chunkX = playerBX >> 4;
        int chunkZ = playerBZ >> 4;
        int searchRadius = 10000;

        List<Map.Entry<ResourceLocation, PathNetworkType>> allEntries = registry.entrySet().stream()
            .<Map.Entry<ResourceLocation, PathNetworkType>>map(e -> Map.entry(e.getKey().location(), e.getValue()))
            .collect(Collectors.toList());

        Map<Integer, List<Map.Entry<ResourceLocation, PathNetworkType>>> byRegionSize = allEntries.stream()
            .collect(Collectors.groupingBy(e -> e.getValue().regionSize()));

        record Candidate(int bx, int bz, ResourceLocation id, long distSq, long pathSeed, PathNetworkType network) {}
        List<Candidate> candidates = new ArrayList<>();

        for(Map.Entry<Integer, List<Map.Entry<ResourceLocation, PathNetworkType>>> entry : byRegionSize.entrySet()) {
            int regionSize = entry.getKey();
            List<Map.Entry<ResourceLocation, PathNetworkType>> networksInGroup = entry.getValue();

            PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, searchRadius, regionSize)
                .forEach(origin -> {
                    long pathSeed = worldSeed
                        ^ ((long) origin[0] * PathChunkFeature.ORIGIN_X_MULT)
                        ^ ((long) origin[1] * PathChunkFeature.ORIGIN_Z_MULT)
                        ^ ((long) regionSize * PathChunkFeature.ORIGIN_REGION_SIZE_MULT)
                        ^ PathChunkFeature.PATH_SEED_MIXER;
                    RandomSource pickRandom = RandomSource.create(pathSeed);
                    Map.Entry<ResourceLocation, PathNetworkType> picked = pickWeightedEntry(networksInGroup, pickRandom);

                    if(networkFilter != null && !picked.getKey().equals(networkFilter)) return;

                    int bx = origin[0] * 16 + 8;
                    int bz = origin[1] * 16 + 8;

                    Holder<Biome> biome = player.serverLevel().getBiome(new BlockPos(bx, 64, bz));
                    if(!picked.getValue().biomeFilter().test(biome)) return;

                    long dx = bx - playerBX;
                    long dz = bz - playerBZ;
                    candidates.add(new Candidate(bx, bz, picked.getKey(), dx * dx + dz * dz, pathSeed, picked.getValue()));
                });
        }

        if(candidates.isEmpty()) {
            src.sendFailure(Component.literal("[paths] No path found within " + searchRadius + " blocks"));
            return 0;
        }

        Candidate nearest = candidates.stream().min(Comparator.comparingLong(Candidate::distSq)).get();
        int dist = (int) Math.sqrt(nearest.distSq());

        // Offset the TP point past the start fade zone so path blocks are actually visible.
        PathNetworkType nearestNetwork = nearest.network();
        RandomSource walkRandom = RandomSource.create(nearest.pathSeed() ^ PathChunkFeature.WALK_MIXER);
        walkRandom.nextInt(Math.max(1, nearestNetwork.scale().lengthMax - nearestNetwork.scale().lengthMin + 1));
        PathDirection initialDir = PathDirection.VALUES[walkRandom.nextInt(8)];
        int fadeOffset = MoogsPathsDatapackRegistries.getPathType(src.registryAccess(), nearestNetwork.pathType())
            .map(pt -> pt.fade().startBlocks() + 10)
            .orElse(30);
        int reportBx = nearest.bx() + initialDir.dx * fadeOffset;
        int reportBz = nearest.bz() + initialDir.dz * fadeOffset;

        MutableComponent coord = Component.literal("[" + reportBx + ", ~, " + reportBz + "]")
            .withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + reportBx + " ~ " + reportBz))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to teleport")))
            );

        MutableComponent msg = Component.literal("[paths] Nearest " + nearest.id() + " at ")
            .append(coord)
            .append(Component.literal(" (~" + dist + " blocks)"));

        src.sendSuccess(() -> msg, false);
        return 1;
    }

    private static Map.Entry<ResourceLocation, PathNetworkType> pickWeightedEntry(
        List<Map.Entry<ResourceLocation, PathNetworkType>> entries, RandomSource random
    ) {
        int total = entries.stream().mapToInt(e -> e.getValue().weight()).sum();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for(Map.Entry<ResourceLocation, PathNetworkType> e : entries) {
            cumulative += e.getValue().weight();
            if(roll < cumulative) return e;
        }
        return entries.get(0);
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
            String line = "  pathType=" + n.pathType()
                + " scale=" + n.scale().lengthMin + "-" + n.scale().lengthMax
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
                src.sendSuccess(() -> Component.literal("[paths] Reload complete"), false);
            });
        return 1;
    }
}
