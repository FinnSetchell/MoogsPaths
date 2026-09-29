package com.finndog.moogs_paths.commands;

import com.finndog.moogs_paths.data.MoogsPathsDatapackRegistries;
import com.finndog.moogs_paths.data.PathDataManager;
import com.finndog.moogs_paths.data.PathNetworkType;
import com.finndog.moogs_paths.data.PathType;
import com.finndog.moogs_paths.data.StructureOrigin;
import com.finndog.moogs_paths.world.PathChunkFeature;
import com.finndog.moogs_paths.world.PathRegionSelector;
import com.finndog.moogs_paths.world.StructureAnchors;
import com.finndog.moogs_paths.world.deferred.DeferredPathJob;
import com.finndog.moogs_paths.world.deferred.PlacementTickPump;
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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
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

    private static final int MAX_LOCATE_VERIFY = 32;
    private static final int LOCATE_RADIUS = 10000;
    // Checking an anchored spot generates its structure, so that search is tighter.
    private static final int ANCHORED_LOCATE_RADIUS = 6000;
    private static final int MAX_ANCHORED_VERIFY = 64;
    private static final int ANCHOR_LIST_RADIUS = 1500;
    private static final int ANCHOR_LIST_MAX = 8;

    private record Located(ResourceLocation network, BlockPos waypoint, long distSq) {}

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
        boolean anchoredOnly = filtered.map(PathNetworkType::isStructureAnchored).orElse(false);

        Located best = anchoredOnly ? null : locateRegionPath(level, networkFilter, from.getX(), from.getZ());
        if(networkFilter == null || anchoredOnly) {
            Located anchored = locateAnchoredPath(level, networkFilter, from.getX(), from.getZ());
            if(anchored != null && (best == null || anchored.distSq() < best.distSq())) best = anchored;
        }
        if(best == null) {
            String target = networkFilter != null ? networkFilter.toString() : "nearest";
            int radius = anchoredOnly ? ANCHORED_LOCATE_RADIUS : LOCATE_RADIUS;
            src.sendFailure(Component.literal("[paths] No " + target + " path found within " + radius + " blocks"));
            return 0;
        }

        MutableComponent msg = Component.literal("[paths] Nearest " + best.network() + " at ")
            .append(teleportLink(best.waypoint()))
            .append(Component.literal(" (~" + (int) Math.sqrt(best.distSq()) + " blocks)"));
        src.sendSuccess(() -> msg, false);
        return 1;
    }

    // The path from the nearest region origin that produces one, per worldgen's own evaluation.
    private static Located locateRegionPath(ServerLevel level, ResourceLocation networkFilter, int fromX, int fromZ) {
        RegistryAccess access = level.registryAccess();
        long worldSeed = level.getSeed();
        int chunkX = fromX >> 4;
        int chunkZ = fromZ >> 4;
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        record OriginCandidate(int originChunkX, int originChunkZ, int regionSize, List<PathNetworkType> networks, long distSq) {}
        List<OriginCandidate> candidates = new ArrayList<>();
        for(Map.Entry<Integer, List<PathNetworkType>> entry : MoogsPathsDatapackRegistries.networksByRegionSize(access).entrySet()) {
            int regionSize = entry.getKey();
            List<PathNetworkType> networks = entry.getValue();
            PathRegionSelector.originsInRange(worldSeed, chunkX, chunkZ, LOCATE_RADIUS, regionSize)
                .forEach(origin -> {
                    long dx = origin[0] * 16L + 8 - fromX;
                    long dz = origin[1] * 16L + 8 - fromZ;
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
                if(!MoogsPathsDatapackRegistries.networkId(access, selected.get()).map(networkFilter::equals).orElse(false)) continue;
            }

            // already-rejected origins are free to skip - biome/pathfinder already determined they
            // can't produce a path here, so they would just return empty from evaluateOrigin anyway
            if(PathDataManager.isRejected(pathSeed)) continue;

            verified++;

            Optional<PathChunkFeature.EvaluatedOrigin> result = PathChunkFeature.evaluateOrigin(
                level, generator, randomState, worldSeed,
                candidate.originChunkX(), candidate.originChunkZ(), candidate.regionSize(), candidate.networks());
            if(result.isEmpty()) continue;

            PathChunkFeature.EvaluatedOrigin ev = result.get();
            Optional<ResourceLocation> networkId = MoogsPathsDatapackRegistries.networkId(access, ev.network());
            if(networkFilter != null && !networkId.map(networkFilter::equals).orElse(false)) continue;

            // /locate computed the path synchronously into PathDataManager's cache but never
            // told the deferred placement system about it. Without this enqueue, the player can
            // teleport to the reported location and find no blocks placed: chunk-load handlers
            // check DeferredPathState.pending, see no job for this pathSeed, and skip placement.
            // Enqueueing here puts the job into pending so chunks loading at the destination
            // will trigger LiveChunkPlacer. Idempotent: if the job is already pending (from a
            // prior worldgen pass), DeferredPathState.addPending no-ops.
            networkId.ifPresent(id -> PlacementTickPump.enqueueFromWorldgen(level, new DeferredPathJob(
                ev.pathSeed(), candidate.originChunkX(), candidate.originChunkZ(), candidate.regionSize(), id)));

            return nearestWaypoint(networkId.orElse(unknownId()), ev.cachedPath(), fromX, fromZ);
        }
        return null;
    }

    // The path leading out of the nearest structure that produces one. Computing it here caches it
    // for worldgen and queues its placement, like a region path above.
    private static Located locateAnchoredPath(ServerLevel level, ResourceLocation networkFilter, int fromX, int fromZ) {
        RegistryAccess access = level.registryAccess();
        long worldSeed = level.getSeed();

        record Candidate(MoogsPathsDatapackRegistries.AnchoredNetwork network, StructureAnchors.StructureChunk chunk, long distSq) {}
        List<Candidate> candidates = new ArrayList<>();
        for(MoogsPathsDatapackRegistries.AnchoredNetwork anchored : MoogsPathsDatapackRegistries.anchoredNetworks(access)) {
            if(networkFilter != null && !networkFilter.equals(anchored.id())) continue;
            StructureOrigin origin = anchored.network().origin().orElseThrow();
            for(StructureAnchors.StructureChunk chunk : StructureAnchors.candidatesInRange(level, origin, fromX >> 4, fromZ >> 4, ANCHORED_LOCATE_RADIUS)) {
                long dx = ((long) chunk.x() << 4) + 8 - fromX;
                long dz = ((long) chunk.z() << 4) + 8 - fromZ;
                candidates.add(new Candidate(anchored, chunk, dx * dx + dz * dz));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::distSq));

        int verified = 0;
        for(Candidate candidate : candidates) {
            if(verified++ >= MAX_ANCHORED_VERIFY) break;
            Located best = null;
            for(PathDataManager.CachedPath path : anchoredPaths(level, candidate.network(), candidate.chunk())) {
                Located here = nearestWaypoint(candidate.network().id(), path, fromX, fromZ);
                if(best == null || here.distSq() < best.distSq()) best = here;
            }
            if(best != null) return best;
        }
        return null;
    }

    // Every path leading out of the structure at this chunk, computed now if need be and queued for
    // placement. Empty when no structure of the network's kind generates there.
    private static List<PathDataManager.CachedPath> anchoredPaths(ServerLevel level, MoogsPathsDatapackRegistries.AnchoredNetwork anchored, StructureAnchors.StructureChunk chunk) {
        PathNetworkType network = anchored.network();
        Optional<PathType> pathType = MoogsPathsDatapackRegistries.getPathType(level.registryAccess(), network.pathType());
        if(pathType.isEmpty()) return List.of();
        List<PathDataManager.CachedPath> paths = new ArrayList<>();
        int pathCount = network.origin().orElseThrow().pathCount();
        for(int i = 0; i < pathCount; i++) {
            int pathIndex = i;
            long pathSeed = StructureAnchors.pathSeed(level.getSeed(), chunk.x(), chunk.z(), anchored.id(), pathIndex);
            if(PathDataManager.isRejected(pathSeed)) continue;
            PathDataManager.CachedPath path = PathDataManager.getOrComputeWaypoints(pathSeed, () -> StructureAnchors.computePath(
                level, network, anchored.id(), pathType.get(), chunk.x(), chunk.z(), pathIndex));
            if(path.waypointCount() < 2) {
                PathDataManager.markRejected(pathSeed);
                continue;
            }
            PlacementTickPump.enqueueFromWorldgen(level, DeferredPathJob.anchored(pathSeed, chunk.x(), chunk.z(), pathIndex, anchored.id()));
            paths.add(path);
        }
        return paths;
    }

    private static Located nearestWaypoint(ResourceLocation network, PathDataManager.CachedPath path, int fromX, int fromZ) {
        BlockPos nearest = null;
        long nearestSq = Long.MAX_VALUE;
        for(BlockPos wp : path.waypoints()) {
            long dx = wp.getX() - fromX;
            long dz = wp.getZ() - fromZ;
            if(dx * dx + dz * dz < nearestSq) {
                nearestSq = dx * dx + dz * dz;
                nearest = wp;
            }
        }
        return new Located(network, nearest, nearestSq);
    }

    private static ResourceLocation unknownId() {
        //? if >=1.21.1 {
        return ResourceLocation.fromNamespaceAndPath("unknown", "unknown");
        //?} else {
        /*return new ResourceLocation("unknown", "unknown");
        *///?}
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
                int paths = ours ? anchoredPaths(level, anchored, chunk).size() : 0;
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
