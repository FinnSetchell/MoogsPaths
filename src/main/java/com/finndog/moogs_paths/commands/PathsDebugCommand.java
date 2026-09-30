package com.finndog.moogs_paths.commands;

import com.finndog.moogs_paths.api.LocatedPath;
import com.finndog.moogs_paths.api.MoogsPathsLocator;
import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.StructureOrigin;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.finndog.moogs_paths.world.StructureAnchors;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.*;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

// Every command searches around the source's position, so they work from a command block or the
// console (`execute positioned ... run paths locate`) as well as for a player.
public final class PathsDebugCommand {
    private PathsDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            literal("paths")
                //? if <1.21.11 {
                .requires(src -> src.hasPermission(2))
                //?} else {
                /*.requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                *///?}
                .then(literal("debug")
                    .then(literal("region").executes(ctx -> debugRegion(ctx.getSource())))
                    .then(literal("networks").executes(ctx -> debugNetworks(ctx.getSource())))
                    .then(literal("anchors").executes(ctx -> debugAnchors(ctx.getSource())))
                    .then(literal("structures").executes(ctx -> debugStructures(ctx.getSource())))
                    .then(literal("reload").executes(ctx -> debugReload(ctx.getSource())))
                )
                .then(literal("locate")
                    .executes(ctx -> locatePath(ctx.getSource(), null))
                    .then(argument("network", ResourceLocationArgument.id())
                        .suggests((ctx, builder) -> {
                            MoogsPathsDatapackRegistries.networksById(ctx.getSource().registryAccess())
                                .keySet().forEach(id -> builder.suggest(id.toString()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> locatePath(ctx.getSource(), ResourceLocationArgument.getId(ctx, "network")))
                    )
                )
        );
    }

    //////////////////////////////

    private static final int ANCHOR_LIST_RADIUS = 1500;
    private static final int ANCHOR_LIST_MAX = 8;

    // The search itself is MoogsPathsLocator's, so the command and other mods can't drift apart.
    private static int locatePath(CommandSourceStack src, ResourceLocation networkFilter) {
        RegistryAccess access = src.registryAccess();
        if(MoogsPathsDatapackRegistries.networksById(access).isEmpty()) {
            src.sendFailure(Component.literal("[paths] No networks loaded"));
            return 0;
        }
        Optional<PathNetworkType> filtered = networkFilter == null ? Optional.empty() : MoogsPathsDatapackRegistries.getPathNetwork(access, networkFilter);
        if(networkFilter != null && filtered.isEmpty()) {
            src.sendFailure(Component.literal("[paths] Unknown network: " + networkFilter));
            return 0;
        }

        ServerLevel level = src.getLevel();
        BlockPos from = BlockPos.containing(src.getPosition());
        Optional<LocatedPath> found = networkFilter == null
            ? MoogsPathsLocator.locate(level, from, Set.of())
            : MoogsPathsLocator.locate(level, networkFilter, from, Set.of());
        if(found.isEmpty()) {
            String target = networkFilter != null ? networkFilter.toString() : "nearest";
            boolean anchoredOnly = filtered.map(PathNetworkType::isStructureAnchored).orElse(false);
            int radius = anchoredOnly ? MoogsPathsLocator.ANCHORED_LOCATE_RADIUS : MoogsPathsLocator.LOCATE_RADIUS;
            src.sendFailure(Component.literal("[paths] No " + target + " path found within " + radius + " blocks"));
            return 0;
        }

        LocatedPath best = found.get();
        MutableComponent msg = Component.literal("[paths] Nearest " + best.network() + " at ")
            .append(teleportLink(best.landing()))
            .append(Component.literal(" (~" + best.distance() + " blocks)"));
        src.sendSuccess(() -> msg, false);
        return 1;
    }

    private static MutableComponent teleportLink(BlockPos pos) {
        String tp = "/tp @s " + pos.getX() + " ~ " + pos.getZ();
        return Component.literal("[" + pos.getX() + ", ~, " + pos.getZ() + "]")
            .withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                //? if <1.21.11 {
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to teleport")))
                //?} else {
                /*.withClickEvent(new ClickEvent.SuggestCommand(tp))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to teleport")))
                *///?}
            );
    }

    //////////////////////////////

    private static int debugRegion(CommandSourceStack src) {
        Set<Integer> regionSizes = new TreeSet<>(MoogsPathsDatapackRegistries.networksByRegionSize(src.registryAccess()).keySet());
        if(regionSizes.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No region networks loaded"), false);
            return 1;
        }

        long worldSeed = src.getLevel().getSeed();
        BlockPos from = BlockPos.containing(src.getPosition());
        src.sendSuccess(() -> Component.literal("[paths] Region info at " + from.getX() + ", " + from.getZ() + ":"), false);
        for(int regionSize : regionSizes) {
            String desc = PathRegionSelector.describe(worldSeed, from.getX(), from.getZ(), regionSize);
            src.sendSuccess(() -> Component.literal("  regionSize=" + regionSize + " -> " + desc), false);
        }
        return 1;
    }

    private static int debugNetworks(CommandSourceStack src) {
        Map<ResourceLocation, PathNetworkType> networks = MoogsPathsDatapackRegistries.networksById(src.registryAccess());
        if(networks.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No networks loaded"), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("[paths] Loaded networks (" + networks.size() + "):"), false);
        networks.forEach((id, n) -> {
            String lengthStr = MoogsPathsDatapackRegistries.getPathType(src.registryAccess(), n.pathType())
                .map(pt -> pt.minLength() + "-" + pt.maxLength())
                .orElse("?");
            String originStr = n.origin()
                .map(o -> " origin=" + o.structureSet() + o.structure().map(s -> "/" + s).orElse("") + " x" + o.pathCount())
                .orElse(" regionSize=" + n.regionSize() + " weight=" + n.weight());
            String line = "  " + id + ": pathType=" + n.pathType()
                + " length=" + lengthStr
                + originStr
                + " structureSets=" + n.structureSets().size()
                + " decoratorSets=" + n.featureDecoratorSets().size();
            src.sendSuccess(() -> Component.literal(line), false);
        });
        return 1;
    }

    // For each structure-anchored network, the structure spots near the source and whether each one
    // produced its paths, so a datapack author can check a network finds the structure they expect.
    private static int debugAnchors(CommandSourceStack src) {
        List<MoogsPathsDatapackRegistries.AnchoredNetwork> networks = MoogsPathsDatapackRegistries.anchoredNetworks(src.registryAccess());
        if(networks.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[paths] No structure-anchored networks loaded"), false);
            return 1;
        }

        ServerLevel level = src.getLevel();
        BlockPos from = BlockPos.containing(src.getPosition());
        src.sendSuccess(() -> Component.literal("[paths] Structure-anchored networks within " + ANCHOR_LIST_RADIUS + " blocks:"), false);
        for(MoogsPathsDatapackRegistries.AnchoredNetwork anchored : networks) {
            StructureOrigin origin = anchored.network().origin().orElseThrow();
            List<StructureAnchors.StructureChunk> chunks = new ArrayList<>(StructureAnchors.candidatesInRange(level, origin, from.getX() >> 4, from.getZ() >> 4, ANCHOR_LIST_RADIUS));
            chunks.sort(Comparator.comparingLong(c -> {
                long dx = ((long) c.x() << 4) + 8 - from.getX();
                long dz = ((long) c.z() << 4) + 8 - from.getZ();
                return dx * dx + dz * dz;
            }));
            String target = origin.structureSet() + origin.structure().map(s -> "/" + s).orElse("");
            src.sendSuccess(() -> Component.literal("  " + anchored.id() + " <- " + target + ": " + chunks.size() + " placement spot(s)"), false);

            for(StructureAnchors.StructureChunk chunk : chunks.subList(0, Math.min(ANCHOR_LIST_MAX, chunks.size()))) {
                Optional<StructureAnchors.ResolvedStructure> structure = StructureAnchors.resolve(level, origin.structureSet(), chunk.x(), chunk.z());
                boolean ours = structure.isPresent() && origin.structure().map(structure.get().structure()::equals).orElse(true);
                int paths = ours ? StructureAnchors.pathsAt(level, anchored, chunk).size() : 0;
                BlockPos at = structure.map(s -> s.bounds().getCenter()).orElse(new BlockPos((chunk.x() << 4) + 8, 0, (chunk.z() << 4) + 8));
                String what = structure.map(s -> ours
                        ? s.structure() + ": " + paths + "/" + origin.pathCount() + " path(s)"
                        : s.structure() + " (not " + origin.structure().orElseThrow() + ")")
                    .orElse("no structure generates here");
                MutableComponent line = Component.literal("    - ")
                    .append(teleportLink(at))
                    .append(Component.literal(" " + what)
                        .withStyle(paths > 0 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
                src.sendSuccess(() -> line, false);
            }
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
