package com.finndog.moogs_paths.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * A path found by {@link MoogsPathsLocator}.
 *
 * @param network  the network the path belongs to
 * @param origin   which path this is, the same wherever a search starts: the centre of its region's
 *                 origin chunk, or of the chunk its structure starts in, always at y 0. Paths leading
 *                 out of one structure share it.
 * @param landing  where to go: the waypoint nearest the search's start where the path is laid, which
 *                 is what /paths locate reports. Its y is the path's planned height, not a checked
 *                 standing spot.
 * @param distance blocks from the search's start to the landing, counted flat
 */
public record LocatedPath(ResourceLocation network, BlockPos origin, BlockPos landing, int distance) {}
