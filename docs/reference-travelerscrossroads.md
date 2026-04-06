# MoogsPaths — Reference: TravelersCrossroads Analysis

Analysis of [TravelersCrossroads (1.21.1-Neo)](https://github.com/JMilamber/TravelersCrossroads/tree/1.21.1-Neo)
as a reference point for our architecture decisions.

---

## What It Does Well — Things to Adopt

### 1. Tick-Based Progressive Path Building

TC defers path construction to server ticks (every 3rd tick, max 2 sections per tick).
This avoids lag spikes during worldgen — paths are placed gradually after the world loads.

**We should adopt this.** Our cross-chunk seam problem largely disappears if path
construction is not tied to chunk generation at all. The spawn *decision* lives in worldgen;
the actual block placement runs on server tick.

This also means:
- No cross-chunk coordination headaches
- Paths can be paused/resumed across server restarts (via SavedData / PersistentState)
- CPU cost is predictable and configurable

### 2. 16-Way Directional System (`TravelersDirection`)

A compact enum of 16 directions (N, NNE, NE, ENE, … NNW) with a static 5×5 lookup table
for O(1) direction lookup from (x, z) deltas. Clean and efficient.

**We should use the same approach.** Our guided random walk needs direction tracking —
this enum pattern is better than raw angle maths.

### 3. Weighted Randomness for Natural Curves

TC's path algorithm uses three probability tiers per step:
- 30% straight ahead
- 50% gentle curve (±1 direction step)
- 20% broader turn (±2 direction steps)

This produces organic, naturally curving paths without complex noise functions.
**We adopt this as our curviness model** — the `curviness` field in our `PathType`
JSON could map to these three weights directly.

### 4. PathSize Auto-Calculated Spacing

```
distance = round(0.667 × (width² - width + 8))
nodesPerImportantNode = 80 / distance
```

Elegant formula that auto-scales step size with path width, ensuring wider paths
look proportionally correct. **Adopt this formula** rather than making users configure
step size separately from width.

### 5. Biome-Aware Style System

Multiple `PathStyle` entries, each gated by a biome tag. The correct style is selected
at placement time. **This is exactly our `PathType` + `BiomeFilter` design** — good
confirmation we're on the right track. TC's implementation validates this approach.

### 6. Tags for Structure Targeting

TC uses structure tags (`#path_structures`) to define which structures paths connect to.
We're not connecting paths to structures, but **we should use tags for our structure
placement biome/dimension filters** — it's the datapack-friendly pattern.

---

## Where We Differ (and Why)

### Structure Behaviour

| TravelersCrossroads | MoogsPaths |
|---|---|
| Paths connect to existing Minecraft structures (villages, mansions) | Paths are standalone; custom NBT structures decorate them |
| Structures are the *destination* of paths | Structures are *decoration* along paths |
| Uses `findNearestMapStructure` to target structure positions | Uses NBT structure pools placed at intervals |

Our approach is independent of what exists in the world — paths spawn regardless
of whether vanilla structures are nearby. This is intentional.

### Path Spawn Trigger

| TravelersCrossroads | MoogsPaths |
|---|---|
| Places a Cairn block via Feature, then builds path from Cairn | No marker block — spawn decision is seeded/regional, invisible |
| Cairn is a physical in-world object | Spawn is a pure data decision |

We do not need a physical marker block. Our region-seeded approach determines
path origin points mathematically — same result every time from the same seed.

### Scale

TC is focused on regional crossroads (connecting structures, typically 10–30 chunks).
We support four explicit scales from short trails to grand networks spanning thousands
of blocks. TC has no equivalent of our Trail or Grand scales.

### Persistence

TC uses `SavedData` (Forge) to persist in-progress path construction.
We need to abstract this via Architectury since we're targeting Fabric too.

| Loader | Equivalent |
|---|---|
| Forge | `SavedData` |
| Fabric | `PersistentState` |
| Architectury abstraction | Platform service / common interface |

We will write a thin Architectury-compatible persistence wrapper.

---

## Algorithm Reference (for our implementation)

TC's core path algorithm (simplified):

```
function buildPath(start, end, style):
    current = start
    waypoints = [start]
    
    while distance(current, end) > 2 × style.stepDistance:
        baseDir = directionFromPos(current, end)
        roll = random(0, 100)
        
        if roll < 30:   dir = baseDir           // straight
        elif roll < 80: dir = baseDir ± 1       // gentle curve
        else:           dir = baseDir ± 2       // broader turn
        
        current = current + dir × stepDistance
        waypoints.add(current)
    
    waypoints.add(end)
    return waypoints
```

For MoogsPaths, `end` is not a fixed target — it is a point drawn from a random
walk budget (max length). Replace `distance(current, end) > threshold` with
`stepsRemaining > 0`, and choose a "drift direction" at path start that acts as
the loose forward bias.

Branching: at each waypoint, roll against `branch_probability`. If triggered,
start a child walk from that point with a reduced length budget.

---

## TC Source Locations (for reference)

| Concept | TC File |
|---|---|
| Path algorithm | `world/TravelersPath.java` |
| Tick-based builder | `worldgen/TravelersWatcher.java` |
| Style base class | `worldgen/custom/pathstyle/PathStyle.java` |
| Direction enum | `util/TravelersDirection.java` |
| Persistence | `util/CrossroadsData.java` |
| PathSize formula | `util/PathSize.java` |
