# MoogsPaths — Gameplay Design

## What the Player Experiences

While exploring, the player stumbles across paths cutting through the landscape.
These paths have no obvious origin or destination — they feel like remnants of
something, or perhaps just the natural result of terrain that has been travelled.
Along and beside them, structures appear: ruins, shrines, stone markers, rest spots.
The world feels less empty.

## Path Networks

A **path network** is the top-level generation unit. It defines:
- How often it tries to spawn per region (chunk grid)
- The scale of the resulting network (small trail vs. large branching route)
- Which biomes/dimensions it can appear in
- Which path type it uses for its segments
- Which structure sets and feature decorators are attached

### Scale Examples (for reference, configurable in JSON)

| Scale       | Approximate Length | Branches | Description                          |
|-------------|-------------------|----------|--------------------------------------|
| Trail       | 100–400 blocks    | 0–1      | A short winding path, often dead-ends |
| Local       | 400–1000 blocks   | 1–3      | Connects a small area, light branching |
| Regional    | 1000–4000 blocks  | 3–8      | Major route with several branches    |
| Grand       | 4000+ blocks      | 8+       | Rare, sweeping network               |

These are starting defaults. All values live in JSON.

## Path Generation Behaviour

- Paths use a **guided random walk** algorithm — each step has a preferred forward
  direction with controlled variance (curviness parameter).
- Paths **hug the terrain heightmap** — they do not float or tunnel. On steep slopes
  they may cut slightly or rise as steps (configurable).
- Paths can **branch** at a configurable rate. Branch segments inherit the parent
  path type but may have reduced width.
- Paths have a **fade** system — at the start and end of a path, block density
  decreases so paths appear to trail off rather than hard-stop.

## Structures Along Paths

**Structure sets** define a pool of NBT structures that can spawn at intervals
along a path. Examples:

- Stone lanterns or markers every N blocks
- A ruined archway at a branch point
- A collapsed shelter near the end of a trail

Placement respects:
- Minimum spacing between structures
- Whether the ground is flat enough (configurable tolerance)
- Biome inclusion/exclusion filters inherited from or overriding the network

## Feature Decorators (Path-side Terrain Features)

**Feature decorator sets** work similarly to Minecraft's `PlacedFeature` system.
They scatter terrain features in a corridor alongside the path:

- Flower patches, tall grass, specific tree types
- Custom configured features (ore veins, moss patches, custom trees)
- Supports vanilla Minecraft configured features as well as mod-added ones

Feature decorators give paths an ecological identity — a path through a forest
feels different from one through plains.

## Design Inspiration

- **Dwarf Fortress** road generation: routes that adapt to terrain, branch organically
- **Wildfire Worlds / Terra**: feature scattering along geographic lines
- **Kingdom Come: Deliverance**: paths that look worn and natural, not engineered
- **Minecraft's own structure system**: pools, processors, placement rules — familiar
  to modpack makers
