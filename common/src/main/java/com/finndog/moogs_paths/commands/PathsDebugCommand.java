package com.finndog.moogs_paths.commands;

import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Collection;
import java.util.Optional;

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
                    .then(literal("clear").executes(ctx -> debugClear(ctx.getSource())))
                )
        );
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

        Collection<PathNetworkType> allNetworks = PathDataManager.getAllNetworks();
        if(allNetworks.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Region info at your position:"), false);

        allNetworks.stream()
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
        Collection<PathNetworkType> networks = PathDataManager.getAllNetworks();
        if(networks.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Loaded networks (" + networks.size() + "):"), false);
        for(PathNetworkType n : networks) {
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
        int count = PathDataManager.getCachedTemplateCount();
        src.sendSuccess(() -> Component.literal("[paths] Cached structure templates: " + count), false);

        PathDataManager.getAllStructureIds().forEach(id -> {
            Optional<StructureTemplate> tmpl = PathDataManager.getCachedTemplate(id);
            String state = tmpl.isPresent() ? "ok" : "MISSING";
            src.sendSuccess(() -> Component.literal("  " + id + " [" + state + "]"), false);
        });
        return 1;
    }

    private static int debugClear(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("[paths] Reloading datapacks..."), false);
        var packIds = src.getServer().getResourceManager().listPacks()
            .map(pack -> pack.packId())
            .toList();
        src.getServer().reloadResources(packIds)
            .thenRun(() -> src.sendSuccess(() -> Component.literal("[paths] Reload complete"), false));
        return 1;
    }
}
