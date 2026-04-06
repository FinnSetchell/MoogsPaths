# MoogsPaths — Technical Architecture

## Stack

| Concern           | Choice                                      |
|-------------------|---------------------------------------------|
| Minecraft version | 1.20 / 1.20.1                               |
| Abstraction layer | Architectury API                            |
| Phase 1 loaders   | Forge + Fabric                              |
| Phase 2 loaders   | Forge, NeoForge, Fabric                     |
| Data format       | JSON (datapack-style, loaded via codec)     |
| Build system      | Gradle (Architectury Loom)                  |

## Module Structure (Architectury)

```
moogspaths/
  common/          # Shared logic, data types, codecs, worldgen
  forge/           # Forge entrypoint, loader-specific hooks
  fabric/          # Fabric entrypoint
```

All worldgen logic lives in `common`. Loader modules only contain entrypoints
and any loader-specific registration boilerplate.

## Data Pipeline

```
JSON files (datapack / mod resources)
        ↓
  Codec deserialisation (Mojang DataFixerUpper / Gson)
        ↓
  Registry population (PathNetworkType, PathType,
                       StructureSet, FeatureDecoratorSet)
        ↓
  Worldgen hook (ChunkGenerator / PlacedFeature layer)
        ↓
  Path network spawning decision (per region)
        ↓
  Path segment generation (guided random walk)
        ↓
  Structure placement pass
        ↓
  Feature decorator scatter pass
```

## Key Systems

### 1. PathNetworkType

Registered via JSON at `data/<namespace>/moogspaths/path_networks/<id>.json`.

Responsibilities:
- Spawn frequency and region budget
- Scale parameters (min/max length, branch count, branch probability)
- Biome/dimension filter
- Reference to a `PathType`
- Reference to zero or more `StructureSet`s
- Reference to zero or more `FeatureDecoratorSet`s

### 2. PathType

Registered via JSON at `data/<namespace>/moogspaths/path_types/<id>.json`.

Responsibilities:
- Block palette (surface layer, fill blocks, edge blocks)
- Width (min, max, taper behaviour)
- Curviness (directional variance per step)
- Slope handling (cut/fill tolerance, step generation)
- Fade behaviour (start/end density falloff)

### 3. StructureSet

Registered via JSON at `data/<namespace>/moogspaths/structure_sets/<id>.json`.

Responsibilities:
- Pool of weighted NBT structure references
- Minimum spacing between placements
- Placement rules (ground flatness tolerance, biome filter override)
- Placement position along path (any, branch points only, endpoints only)

### 4. FeatureDecoratorSet

Registered via JSON at `data/<namespace>/moogspaths/feature_decorator_sets/<id>.json`.

Responsibilities:
- Pool of weighted feature references (vanilla `PlacedFeature` IDs or mod features)
- Scatter width (how far from path edge features can place)
- Density (attempts per N blocks of path)
- Biome filter override

### 5. Path Generation Algorithm

Phase 1 — Spawn decision:
- Divide the world into generation regions (e.g. 512×512 block cells)
- Per region, use seeded RNG to decide which `PathNetworkType`s attempt to spawn
  and at what origin point

Phase 2 — Network layout:
- From origin, run guided random walk to produce a polyline
- At each branch probability trigger, spawn a child walk from the current point
- All walks have a step size, curviness, max length drawn from the `PathNetworkType`

Phase 3 — Segment rasterisation:
- Convert polyline segments to block positions using a corridor rasteriser
  (Bresenham-based, with width applied perpendicular to travel direction)
- Sample heightmap at each column; place path blocks at surface level
- Apply slope handling and fade at path ends

Phase 4 — Structure pass:
- Walk the path polyline, attempt structure placements at configured intervals
- Load NBT, check placement validity (flatness, space), place or skip

Phase 5 — Feature pass:
- Walk the path polyline, scatter feature attempts in the corridor
- Delegate to Minecraft's own feature placement machinery

### 6. Worldgen Integration (1.20)

Options considered:
- **`PlacedFeature` + `FeaturePlacement`** — simplest hook point, fires per chunk.
  Risk: paths crossing chunk boundaries need cross-chunk coordination.
- **Custom `ChunkGenerator` decorator** — more control, more complex, harder to
  play nicely with other mods.
- **`StructureFeature` (structure system)** — good for the start point; paths are
  not structures but the spawn decision logic maps well here.

**Chosen approach:** Use a `PlacedFeature` as the worldgen hook for the spawn
decision, but do path generation from a seeded, region-aware cache so that all
chunks in a region agree on path layout before any of them generate. This avoids
the cross-chunk seam problem without needing a custom chunk generator.

### 7. Persistence / Caching

Path networks are fully deterministic from world seed + region coords.
No save data is required — regenerating is always identical.
A lightweight in-memory LRU cache holds recently computed region layouts
to avoid recomputation during normal play.

## Codec / JSON Loading

Use Minecraft's existing `Codec` infrastructure (via DFU) for all JSON types.
This gives free datapack reload support and integrates with the resource
pack / datapack pipeline without custom loading code.

Register all types via Architectury's registry abstraction so loader-specific
registration details stay in the loader modules.
