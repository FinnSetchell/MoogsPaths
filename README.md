# Moog's Paths

A data-driven path network generator for Minecraft. Paths are defined entirely in datapack JSON, so creating new ones (or replacing the built-in set) does not require any Java code.

This README documents how to configure paths. For project setup and the underlying MultiLoader template, see the bottom of this file.

## Concepts

A generated path is built from several layered configs. Understanding which file does what makes the rest of this guide easier.

- **path_type** describes the *appearance and shape* of a single path: which blocks it is built from, how wide it is, how tightly it follows terrain, how long it can be.
- **path_network** picks a `path_type` and binds it to a list of biomes. It also chooses which decorations (structures, features, bushes) get attached. Networks are what actually generate in the world.
- **structure_set** is a reusable bundle of NBT structures (lamps, signposts, cairns, shrines) that a network can place along its paths.
- **feature_decorator_set** is a reusable bundle of vanilla configured features (flowers, dead bushes) scattered alongside paths.
- **bush_decorator_set** is a reusable bundle of leaf-blob bushes scattered alongside paths.

A network references the path_type and the decorator sets by id, so the same path_type can be reused across multiple networks, and the same structure_set can be shared between networks.

The `moogs_paths:has_no_paths` biome tag is a hard blocklist. Any biome in this tag never generates paths, regardless of network bindings.

## Tutorial: adding a new path

This walks through adding a new "stone trail" path that generates in plains biomes with cobblestone signposts on the side.

All files live under `data/<your_namespace>/moogs_paths/`. For this tutorial assume the namespace is `mypack`.

### 1. Define the path_type

`data/mypack/moogs_paths/path_type/stone_trail.json`

```json
{
  "surface_blocks": [
    { "block": "minecraft:cobblestone", "weight": 4 },
    { "block": "minecraft:mossy_cobblestone", "weight": 1 }
  ],
  "edge_blocks": [
    { "block": "minecraft:gravel", "weight": 1 }
  ],
  "fill_block": "minecraft:dirt",
  "width": { "min": 2, "max": 3 },
  "rigidness": 0.6,
  "carver": 0.7,
  "length": {
    "type": "minecraft:uniform",
    "value": { "min_inclusive": 200, "max_inclusive": 500 }
  },
  "fade": { "start_blocks": 8, "end_blocks": 8 }
}
```

This produces a 2-3 wide cobblestone path with gravel along the edges and a dirt foundation underneath.

### 2. (Optional) Define a structure_set

`data/mypack/moogs_paths/structure_set/stone_signposts.json`

```json
{
  "structures": [
    { "nbt": "mypack:stone_signpost", "rotation": "random", "weight": 1, "offset": [0, 0, 0] }
  ],
  "placement": "interval",
  "spacing": 20,
  "spacing_variance": 6,
  "flatness_tolerance": 3
}
```

You will also need to put `stone_signpost.nbt` at `data/mypack/structures/stone_signpost.nbt`.

### 3. Define the path_network

`data/mypack/moogs_paths/path_network/plains_stone_trail.json`

```json
{
  "path_type": "mypack:stone_trail",
  "biomes": [
    "minecraft:plains",
    "minecraft:sunflower_plains"
  ],
  "weight": 3,
  "region_size": 48,
  "structure_sets": [
    { "id": "mypack:stone_signposts", "weight": 1 }
  ],
  "feature_decorator_sets": [],
  "bush_decorator_sets": []
}
```

That is it. Load the datapack and paths will start generating in plains in any newly generated chunks.

## Field reference

### path_type

The visual and structural definition of a path.

| field | type | description |
|---|---|---|
| `surface_blocks` | weighted block list | The top layer of the path. One entry is rolled per tile. |
| `edge_blocks` | weighted block list (optional, default `[]`) | Used for the outermost ring of tiles when defined. If empty, surface_blocks is used everywhere. |
| `fill_block` | block id | Block placed beneath the surface to fill any gaps below the path tile (so paths sit cleanly on uneven terrain). |
| `width` | `{ "min": int, "max": int }` | Total path width in blocks. `max` is what actually drives generation today. `min` is parsed but not yet used by the rasteriser. |
| `rigidness` | float `0.0`–`1.0` | How strictly the path holds its target Y. Higher values make straighter, flatter paths that cut/fill terrain more aggressively. |
| `carver` | float `0.0`–`1.0` | How willing the path is to dig through obstacles vs route around them. Higher values cut through hills; lower values snake around them. |
| `length` | IntProvider | Total path length in blocks. Use vanilla IntProvider syntax (`uniform`, `constant`, etc). Bounded `[1, 100000]`. |
| `fade` | `{ "start_blocks": int, "end_blocks": int }` | Number of blocks at the start/end of the path where placement probability ramps from 0 to full. `0` disables fade on that end. |
| `water_settings` | object (optional) | If present, paths will bridge water instead of skipping it. See below. |

#### `surface_blocks` / `edge_blocks` entry shape

```json
{ "block": "minecraft:cobblestone", "weight": 4 }
```

`minecraft:structure_void` is special: it rolls as "place nothing here", which produces gaps in the path. Mixing structure_void into a surface_blocks list is how the built-in dirt trail produces patchy worn-in paths.

#### `water_settings` shape

```json
"water_settings": {
  "surface_blocks": [
    { "block": "minecraft:oak_planks", "weight": 1 }
  ],
  "edge_blocks": [
    { "block": "minecraft:oak_log", "weight": 1 }
  ]
}
```

Identical schema to the top-level surface/edge blocks but only used over water tiles. `edge_blocks` here is optional too.

#### Practical tuning notes

- `rigidness` and `carver` interact. A high-rigidness, low-carver path will look unnatural in mountainous terrain because it wants to be flat but refuses to dig. A balanced setting is `0.6 / 0.7`.
- For dense paved roads, set `width.max` to 3 or 4. For thin trails, 1 or 2.
- `fade` of `8` on each end blends the path edges into the terrain so they do not visually start/stop in midair.

### path_network

Binds a path_type to biomes and decorations. This is what the worldgen actually iterates.

| field | type | description |
|---|---|---|
| `path_type` | resource location | The path_type to use. |
| `biomes` | biome holder set | A single biome id, a list of biome ids, or a `#tag:like_this`. Standard vanilla holderset syntax. Tags from any namespace work, including modded ones (`#c:is_overworld`, `#forge:is_overworld`, mod-specific biome tags), so networks can extend cleanly to modded biomes. |
| `weight` | int | Relative weight when multiple networks compete for the same biome. Higher = more likely to win. |
| `region_size` | int | Size of the worldgen region (in chunks) within which the network plans a path. Larger = longer, less frequent paths. Typical range 32–64. |
| `structure_sets` | weighted ref list (optional) | Structure sets to pull from when decorating. One is picked per placement opportunity. |
| `feature_decorator_sets` | weighted ref list (optional) | Feature decorator sets used for scattered features. |
| `bush_decorator_sets` | weighted ref list (optional) | Bush decorator sets used for leaf blobs along the path. |

A weighted ref looks like:

```json
{ "id": "mypack:stone_signposts", "weight": 1 }
```

### structure_set

A reusable list of NBT structures with placement rules.

| field | type | description |
|---|---|---|
| `structures` | list of structure entries | The actual structures and their per-entry settings. |
| `placement` | `"endpoint"` or `"interval"` | `endpoint` places one structure at each end of the path. `interval` spreads structures along the entire path at regular distances. |
| `spacing` | int | For `interval` mode: average distance in blocks between placements. For `endpoint` mode: typically `1`. |
| `spacing_variance` | int | Random jitter added to `spacing` so placements do not look mechanical. `0` for perfectly regular. |
| `flatness_tolerance` | int | Maximum Y variance (in blocks) across the structure's footprint that the placement check will accept. Lower = stricter, fewer placements but flatter ground. |
| `terrain_adjustment` | `"none"` or `"beard_thin"` (optional, default `none`) | If `beard_thin`, the placement carves a small pad under the structure so it sits flush on uneven ground. Use this for structures that need a level base. |
| `side_offset` | int (optional, default `0`) | Perpendicular distance from the path centerline at which to place the structure. `0` is on the path, positive values push it to the side. |

#### Structure entry shape

```json
{
  "nbt": "mypack:my_structure",
  "rotation": "random",
  "weight": 2,
  "offset": [0, 0, 0]
}
```

| field | type | description |
|---|---|---|
| `nbt` | resource location | NBT structure id, resolved as `data/<namespace>/structures/<path>.nbt`. |
| `rotation` | enum | `none`, `clockwise_90`, `clockwise_180`, `counterclockwise_90`, or `random`. |
| `weight` | int | Relative weight when picking from this set. |
| `offset` | `[x, y, z]` | Offset applied to the placement origin. Useful for nudging a structure off-center or sinking it into the ground. |

### feature_decorator_set

A bundle of vanilla configured features scattered along the path. Used for things like flowers, ferns, or dead bushes.

| field | type | description |
|---|---|---|
| `features` | list of feature entries | The features to roll between. |
| `density` | float | Per-tile probability of placing a feature. Typical range `0.02`–`0.10`. |
| `side` | enum | `left`, `right`, `both`, or `center` relative to the path direction. |
| `scatter_width` | int | How far perpendicular to the path (in blocks) features can scatter. |

#### Feature entry shape

```json
{ "feature": "minecraft:forest_flowers", "weight": 3 }
```

`feature` is a configured feature id, not a placed feature.

### bush_decorator_set

Generates blobs of leaf blocks along the path. Used for shrubs and bushes.

| field | type | description |
|---|---|---|
| `blocks` | weighted block list | Blocks that make up the bush. Typically leaf blocks. |
| `density` | float | Per-tile probability of starting a bush. Typical range `0.05`–`0.20`. |
| `side` | enum | `left`, `right`, `both`, or `center`. |
| `min_offset` / `max_offset` | int | Perpendicular distance range from the path edge. |
| `min_size` / `max_size` | int | Size of the bush blob in blocks. |
| `min_spacing` | int (optional, default `0`) | Minimum spacing between consecutive bushes along the path. |
| `min_height` / `max_height` | int (optional, default `1` / `2`) | Vertical extent of the bush. |

#### Block entry shape

```json
{ "block": "minecraft:oak_leaves", "weight": 8 }
```

### has_no_paths biome tag

`data/moogs_paths/tags/worldgen/biome/has_no_paths.json` is a hard exclusion list. Any biome listed (or a biome from a referenced tag) will never generate paths even if a network claims it.

The mod ships a default `has_no_paths` that excludes oceans, rivers, the void, the deep dark and the lush / dripstone caves. Datapacks can extend it (with `"replace": false`) or replace it outright.

```json
{
  "replace": false,
  "values": [
    "minecraft:the_void",
    "#minecraft:is_ocean",
    "#minecraft:is_river",
    "minecraft:deep_dark",
    "minecraft:lush_caves",
    "minecraft:dripstone_caves"
  ]
}
```

## Commands

Requires permission level 2 (op).

`/paths locate [network]` — teleport-suggest the nearest path origin. With no argument, finds the nearest path of any network. With a network id, finds the nearest path that rolled that network. Click the chat coord to fill `/tp`.

`/paths debug region` — show which path region your position falls inside, for each loaded `region_size`.

`/paths debug networks` — list every loaded `path_network` with its `path_type`, length range, region size, weight, and decorator-set counts.

`/paths debug structures` — list every cached structure NBT and whether it resolved (`ok`) or is missing.

`/paths debug reload` — force a datapack reload and clear runtime caches. Note: `path_type`, `path_network`, `structure_set`, `feature_decorator_set` and `bush_decorator_set` are datapack registries, which on 1.20.1 require a world restart to fully refresh.

---