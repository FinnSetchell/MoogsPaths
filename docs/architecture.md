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
  ── WORLDGEN THREAD ──────────────────────────────────────
  PlacedFeature fires per region (RarityFilter + DistanceFilter)
        ↓
  Spawn decision → origin point queued to PathWatcher
  ── SERVER TICK THREAD ───────────────────────────────────
  PathWatcher processes queue (N sections/tick, configurable)
        ↓
  Guided random walk → waypoint list
        ↓
  Section-by-section rasterisation (terrain-hugging block placement)
        ↓
  Structure placement pass (NBT pool, interval-based)
        ↓
  Feature decorator scatter pass
        ↓
  SavedData / PersistentState (persists in-progress paths)
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

**Direction system:** 16-way enum (N, NNE, NE, ENE, E … NNW) with a static
5×5 lookup table for O(1) direction from (x,z) delta. No float angle maths
at runtime. (Pattern validated by TravelersCrossroads.)

**Curviness model** (per step, weights configurable via `curviness` field):

```
roll = random(0, 100)
if roll < straightWeight:   dir = forwardDir
elif roll < curveWeight:    dir = forwardDir ± 1   // gentle
else:                       dir = forwardDir ± 2   // broader turn
```

Default weights (medium curviness): 30 / 50 / 20.
`curviness: 0.0` = 100/0/0. `curviness: 1.0` = 0/0/100.

**Step distance** auto-calculated from width (validated formula from TC):
```
stepDistance = round(0.667 × (width² - width + 8))
```

**Branching:** At each waypoint, roll against `branch_probability`.
If triggered, start a child walk from that point with a reduced length budget
(e.g. 60% of remaining parent budget). Branches inherit the parent `PathType`.

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

**Two-stage approach** (validated by TravelersCrossroads reference mod):

**Stage 1 — Spawn decision (worldgen time):**
Use a `PlacedFeature` with a `RarityFilter` + custom `DistanceFilter`
(prevents paths spawning within N chunks of each other) to record path
origin points. No blocks are placed at this stage — only the origin
coordinate is queued.

**Stage 2 — Path construction (server tick):**
A server-tick listener (`PathWatcher`) processes queued origins
progressively — N sections per tick (configurable). This completely
sidesteps cross-chunk seam problems and spreads CPU cost across frames
rather than causing chunk-load spikes.

This pattern decouples path layout from chunk generation entirely.
Paths appear in the world over a short period after a region loads,
rather than all at once.

### 7. Persistence

In-progress path construction must survive server restarts.

| Loader   | API               |
|----------|-------------------|
| Forge    | `SavedData`       |
| Fabric   | `PersistentState` |

A thin platform-service abstraction in `common` will expose
`load()` / `save()` / `markDirty()` so `PathWatcher` stays loader-agnostic.

Completed paths are fully deterministic from world seed + region coords,
so only *in-progress* state needs persistence. An in-memory LRU cache
holds recently computed region layouts to avoid re-seeding cost.

## Codec / JSON Loading

Use Minecraft's existing `Codec` infrastructure (via DFU) for all JSON types.
This gives free datapack reload support and integrates with the resource
pack / datapack pipeline without custom loading code.

Register all types via Architectury's registry abstraction so loader-specific
registration details stay in the loader modules.
