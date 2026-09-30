# Moog's Paths - Datapack Guide

Paths are defined entirely in datapack JSON, so creating new ones (or replacing the built-in set) does not require any Java code.

Moog's Paths runs only on the server. Players joining a server that has it do not need it installed, and in singleplayer it runs in the game's built-in server. A datapack for it goes in the world's `datapacks` folder like any other.

## Concepts

A generated path is built from several layered configs. Understanding which file does what makes the rest of this guide easier.

- **path_type** describes the *appearance and shape* of a single path: which blocks it is built from, how wide it is, how tightly it follows terrain, how long it can be.
- **path_network** picks a `path_type` and binds it to a list of biomes. It also chooses which decorations (structures, features, bushes) get attached. Networks are what actually generate in the world.
- **structure_set** is a reusable bundle of NBT structures (lamps, signposts, cairns, shrines) that a network can place along its paths.
- **feature_decorator_set** is a reusable bundle of vanilla configured features (flowers, dead bushes) scattered alongside paths.
- **bush_decorator_set** is a reusable bundle of leaf-blob bushes scattered alongside paths.

A network references the path_type and the decorator sets by id, so the same path_type can be reused across multiple networks, and the same structure_set can be shared between networks.

The `moogs_paths:has_no_paths` biome tag is a hard blocklist. Any biome in this tag never generates paths, regardless of network bindings.

Every numeric field is bounds-checked when the datapack loads. Weights must be `>= 1`,
densities must be in `[0, 1]`, `width.min <= width.max`, `region_size >= 1`,
and so on. A value out of range, or a missing required field, stops the world from
loading, with an error in the log naming the file and what is wrong with it.

Ids that point at something else are only looked up while paths generate, so a mistyped
one does not stop the world loading. Check the log for a `not found` warning:

- an unknown block in a path (`surface_blocks`, `edge_blocks`, `fill_block`, water blocks) is placed as dirt, and an unknown bush block as oak leaves, with a warning logged once;
- an unknown configured feature, structure template (`nbt`), `structure_set` or `bush_decorator_set` is skipped, with a warning logged once;
- an unknown `feature_decorator_set` is skipped without a warning;
- a network whose `path_type` does not exist generates nothing.

`/paths debug structures` lists every structure template the mod has tried to load and whether it was found.

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
  "width": { "min": 1, "max": 3 },
  "rigidness": 0.6,
  "carver": 0.7,
  "length": {
    "type": "minecraft:uniform",
    "min_inclusive": 200,
    "max_inclusive": 500
  },
  "fade": { "start_blocks": 8, "end_blocks": 8 }
}
```

This produces a 3 wide cobblestone path, narrowing to 1 at its ends, with gravel along the edges and a dirt foundation underneath.

On Minecraft 1.20 and 1.20.1 a `uniform` length keeps its bounds in a `value` object instead:
`"length": { "type": "minecraft:uniform", "value": { "min_inclusive": 200, "max_inclusive": 500 } }`.
Each form only loads on its own versions.

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

You will also need to put `stone_signpost.nbt` at `data/mypack/structure/stone_signpost.nbt` (`data/mypack/structures/stone_signpost.nbt` on 1.20 and 1.20.1).

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
    "mypack:stone_signposts"
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
| `width` | `{ "min": int, "max": int }` | Path width in blocks. The width tapers along the fade region from `min` near the endpoints to `max` along the middle of the path. Set `min == max` for a constant-width path. Both must be in `[0, 32]`, with `min <= max`. See the note on widths below. |
| `rigidness` | float `0.0`-`1.0` | How little the route cares about slopes. The pathfinder charges extra for every step that climbs or drops, growing with the square of the height change and scaled by `1 - rigidness`. Low values make the path wind around hills and along valleys; `1.0` ignores slopes and heads straight for its goal over whatever is in the way. |
| `carver` | float `0.0`-`1.0` | How level the path is kept once the route is chosen. It limits how much the height may change from one point of the path to the next: `0.0` follows the ground (up to 64 blocks per step), `1.0` allows 1 block per step. It never changes where the path goes; that is `rigidness`. |
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

#### Widths

Each tile of a path is a small diamond around the centre line, so a path is `2 * floor(width / 2) + 1` blocks across: `0` and `1` both give 1 block, `2` and `3` give 3, `4` and `5` give 5. Only odd widths change anything, and a taper moves in those steps too, so `{ "min": 2, "max": 3 }` stays 3 wide from end to end.

#### Practical tuning notes

- `rigidness` picks the route, `carver` then evens out its height. A low-rigidness, high-carver path winds around hills and stays nearly level; a high-rigidness, low-carver path runs straight and rides up and down over the terrain. A balanced setting is `0.6 / 0.7`.
- A levelled path does not dig. Where the ground rises above it, its tiles are laid under the surface and don't show; where the ground drops away it is built up on `fill_block`; more than 8 blocks off the ground either way, tiles are left out. So a high `carver` on steep ground leaves gaps.
- For wide paved roads, set `width.max` to 5. For ordinary paths, 3. For thin trails, 1.
- `fade` of `8` on each end blends the path edges into the terrain so they do not visually start/stop in midair.

### path_network

Binds a path_type to biomes and decorations. This is what the worldgen actually iterates.

| field | type | description |
|---|---|---|
| `path_type` | resource location | The path_type to use. |
| `biomes` | biome holder set | A single biome id, a list of biome ids, or a `#tag:like_this`. Standard vanilla holderset syntax. Tags from any namespace work, including modded ones (`#c:is_swamp`, `#forge:is_swamp`, mod-specific biome tags), so networks can extend cleanly to modded biomes. The built-in networks use their own tags, listed under [Built-in biome tags](#built-in-biome-tags). |
| `weight` | int (optional, default `1`) | Relative weight when multiple networks compete for the same biome. Higher = more likely to win. |
| `region_size` | int | Size of the worldgen region (in chunks) within which the network plans a path. Larger = longer, less frequent paths. Typical range 32-64. Networks with the same `region_size` share one starting point per region, so a network for a small or rare biome (mushroom fields use 12) needs its own smaller size, or its starting points rarely land in the biome. Not used, and not needed, with `origin`. |
| `structure_sets` | list of resource locations (optional) | Structure sets to scatter along the path. Each entry runs independently - there is no weighted pick here. |
| `feature_decorator_sets` | list of resource locations (optional) | Feature decorator sets used for scattered features. |
| `bush_decorator_sets` | list of resource locations (optional) | Bush decorator sets used for leaf blobs along the path. |
| `origin` | object (optional) | Start every path at a structure instead of a region. See below. |

Decorator set lists are plain arrays of ids:

```json
"structure_sets": [
  "mypack:stone_signposts",
  "mypack:rare_milestones"
]
```

#### Paths from structures (`origin`)

With an `origin` block a network stops scattering its paths across regions. Instead every path starts
at a structure that really generated there and leads away from it, so villages, huts and temples get
roads out of them. It works with vanilla and modded structures alike.

```json
{
  "path_type": "moogs_paths:dirt_trail",
  "biomes": "#minecraft:is_overworld",
  "structure_sets": ["moogs_paths:oak_posts"],
  "origin": {
    "structure_set": "minecraft:villages",
    "structure": "minecraft:village_plains",
    "path_count": 2
  }
}
```

| field | type | description |
|---|---|---|
| `structure_set` | resource location, or a list | A structure set (`worldgen/structure_set`), e.g. `minecraft:villages`, `minecraft:swamp_huts` or a modded set, or several. The set, not the structure, because the set holds the placement that says where its structures go. A set that isn't loaded (its mod isn't installed) is skipped, so a list can name optional modded sets; a warning is only logged when none of them is loaded. |
| `structure` | resource location, `#tag`, or a list of either (optional) | Only anchor to these structures of the sets (`worldgen/structure`), e.g. `minecraft:village_desert` out of `minecraft:villages`, or `#minecraft:village`. Without it, any structure of the sets counts. Ids that don't exist simply never match. |
| `path_count` | int (optional, default `1`, up to `8`) | How many paths lead out of each structure. They head off in different directions. |
| `anchor` | object (optional) | Start the paths at a particular spot inside the structure. See below. |

Several sets and structures let one network cover modded structures too, e.g. plains roads that also
leave Towns and Towers' meadow villages:

```json
"origin": {
  "structure_set": ["minecraft:villages", "towns_and_towers:towns"],
  "structure": ["minecraft:village_plains", "towns_and_towers:village_meadow"],
  "path_count": 2
}
```

By default a path starts just outside the structure, on the side it heads off towards, and never cuts
back through the structure's pieces. The path still only runs through the network's `biomes` (and never
through `moogs_paths:has_no_paths` biomes such as rivers), so keep the list broad: structures often sit
at the edge of their biome, and a narrow list like `#minecraft:has_structure/village_plains` would stop a
road at the first forest next to the village.

Structures are found the way vanilla places them, including each set's frequency and exclusion zones,
so a path only starts where the structure really is. A structure another mod turns off (YUNG's Better
Jungle Temples replaces the vanilla jungle temple, for one) gets no paths. Only `random_spread` placements can be used, which
covers villages, huts, temples, outposts and most modded structures. Strongholds (`concentric_rings`)
are not supported and log a warning once.

#### `anchor` - start from a spot on the structure

A path can lead up to a door, or carry on from the end of a village street:

```json
"origin": {
  "structure_set": "minecraft:jungle_temples",
  "structure": "minecraft:jungle_pyramid",
  "anchor": {
    "local_pos": [5, 0, -1],
    "facing": "north"
  }
}
```

```json
"origin": {
  "structure_set": "minecraft:villages",
  "structure": "minecraft:village_plains",
  "path_count": 2,
  "anchor": {
    "piece": "minecraft:village/*/terminators/*"
  }
}
```

| field | type | description |
|---|---|---|
| `piece` | string, or a list (optional) | Which pieces to start from, by NBT id: the `location` of a `single_pool_element` (a village house, a named piece of a modded jigsaw structure) or the NBT of a template piece (an igloo). `*` stands for any part of one name between slashes and `**` for any run of folders, so `minecraft:village/*/terminators/*` matches every vanilla village's street ends. A list takes several ids or patterns, e.g. one per structure a network covers. Without it, the structure's first piece is used. |
| `piece_index` | int (optional) | Pins one occurrence when `piece` matches several. Without it, each path starts from the matching piece lying furthest the way it heads, so the roads of a village each carry on from a different street end. When even that piece lies more than 32 blocks back from the structure's edge on that side, the path starts at the edge instead. |
| `local_pos` | `[x, y, z]` (optional) | A position inside the piece: for an NBT piece the numbers a structure block shows; for a piece built in code (jungle temple, witch hut, desert pyramid) the piece's own coordinates, the ones its code places blocks at. It turns with the piece, so it tracks the structure however it generated, and may lie outside the piece (`-1` is just in front of its `z = 0` side). Only `x` and `z` matter; the path starts on the surface. Without it, the piece's centre is used. |
| `facing` | `north`, `south`, `east` or `west` (optional) | The side the path leaves from, in the same frame as `local_pos` (`north` is towards `z = 0`), turned with the piece. The path runs straight out that way until it is clear of the structure, then turns towards where it is going, and a structure's first path heads that way. Without it, a path leaves by the shortest way clear of the structure, preferring the way it heads. |

With or without an anchor, a path never cuts back through the structure's pieces: from an anchor it only
crosses them on its straight way out. When no piece matches, the path starts as it would without an
anchor, so a list of pieces can cover structures that lack some of them.

The built-in networks use these: jungle temple roads leave the temple's front steps (`[5, 0, -1]`,
facing `north`), witch hut trails the hut's porch (`[3, 0, -1]`, facing `north`), and village roads
carry on from street ends.

`/paths debug anchors` lists each structure-anchored network's structures near you, how many of their
paths were built, and what the anchor picked in each.

The mod ships these as built-in networks, each findable with `/paths locate <network>`:

- `moogs_paths:village_road_plains`, `village_road_desert`, `village_road_savanna`, `village_road_snowy`, `village_road_taiga` - two roads out of every village, in the style of its biome, Towns and Towers villages of those styles included
- `moogs_paths:witch_hut_trail` - a swamp trail out of every witch hut
- `moogs_paths:jungle_temple_road` - a jungle path out of every jungle temple

### structure_set

A reusable list of NBT structures with placement rules.

| field | type | description |
|---|---|---|
| `structures` | list of structure entries | The actual structures and their per-entry settings. |
| `placement` | `"endpoint"` or `"interval"` | `endpoint` places one structure at each end of the path. `interval` spreads structures along the entire path at regular distances. |
| `spacing` | int | For `interval` mode: distance in blocks along the path between placements, before `spacing_variance` is added. For `endpoint` mode: unused, typically `1`. |
| `spacing_variance` | int | Adds a random 0 to `spacing_variance - 1` blocks to each gap so placements do not look mechanical. `0` for perfectly regular. |
| `flatness_tolerance` | int `0`-`255` | How far (in blocks) the ground may rise or drop around the placement spot. The check looks at a plus shape, 1 and 2 blocks out from the centre in each of the four directions, not the structure's whole footprint, and skips the placement if any of those 8 spots differs from the centre by more than this. Lower = stricter, fewer placements but flatter ground. A structure wider than 5 blocks can still hang over a drop at its corners; `beard_thin` below fills under it. |
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
| `nbt` | resource location | NBT structure id, resolved as `data/<namespace>/structure/<path>.nbt` (`data/<namespace>/structures/<path>.nbt` on 1.20 and 1.20.1), the same place vanilla keeps structure templates. |
| `rotation` | enum | `none`, `clockwise_90`, `clockwise_180`, `counterclockwise_90`, or `random`. |
| `weight` | int | Relative weight when picking from this set. |
| `offset` | `[x, y, z]` | Offset applied to the placement origin. Useful for nudging a structure off-center or sinking it into the ground. |
| `placement_chance` | float `0.0`-`1.0` (optional, default `1.0`) | Probability of actually placing this entry when the weight roll picks it. If the roll fails, the placement slot is skipped entirely instead of falling back to another entry, which keeps a rare entry from being silently replaced by common ones. Use this to make large landmark structures show up only once or twice along a path. |

### feature_decorator_set

A bundle of vanilla configured features scattered along the path. Used for things like flowers, ferns, or dead bushes.

| field | type | description |
|---|---|---|
| `features` | list of feature entries | The features to roll between. |
| `density` | float | Per-tile probability of placing a feature. Typical range `0.02`-`0.10`. |
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
| `density` | float | Per-tile probability of starting a bush. Typical range `0.05`-`0.20`. |
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

### Built-in biome tags

Eleven of the built-in networks take their biomes from a tag named after the network,
`#moogs_paths:has_path/<network>`, in `data/moogs_paths/tags/worldgen/biome/has_path/`. Each lists the
vanilla biomes the path is made for, plus the loaders' common biome tags for the same kind of biome as
optional entries, so biomes from Terralith, Biomes O' Plenty, Regions Unexplored and other biome mods
get the path too. Optional entries are skipped when a tag doesn't exist, so one file works on every
version and loader.

| tag | vanilla biomes | common tags it adds |
|---|---|---|
| `has_path/badlands_canyon` | `#minecraft:is_badlands` | `#c:is_badlands`, `#c:badlands`, `#c:mesa` |
| `has_path/cherry_grove` | cherry grove | `#c:primary_wood_type/cherry`, and Terralith's sakura grove and sakura valley |
| `has_path/desert_highway` | desert | `#c:is_desert`, `#c:desert`, `#forge:is_desert` |
| `has_path/dirt_road` | taiga, old growth pine and spruce taiga, dark forest, `#minecraft:is_taiga` (which adds snowy taiga) | `#c:is_taiga`, `#c:taiga` |
| `has_path/mushroom_path` | mushroom fields | `#c:is_mushroom`, `#c:mushroom`, `#forge:is_mushroom` |
| `has_path/plains_trail` | plains, sunflower plains, meadow | `#c:is_plains`, `#c:plains` |
| `has_path/savanna_path` | `#minecraft:is_savanna` | `#c:is_savanna`, `#c:savanna` |
| `has_path/snowy_trail` | snowy plains, snowy taiga, ice spikes, snowy beach | `#c:is_snowy_plains`, `#c:snowy_plains` |
| `has_path/swamp_path`, `has_path/witch_hut_trail` | swamp, mangrove swamp | `#c:is_swamp`, `#c:swamp`, `#forge:is_swamp` |
| `has_path/windswept_trail` | windswept hills, gravelly hills, forest and savanna, `#minecraft:is_hill` | `#c:is_windswept`, `#c:windswept` |

The other networks use vanilla tags directly (`#minecraft:is_overworld`, `#minecraft:is_jungle`,
`#minecraft:is_mountain`), which biome mods fill in themselves.

To give a built-in path to more biomes, add them to its tag with `"replace": false`:

```json
{
  "replace": false,
  "values": [
    "mypack:my_swamp",
    { "id": "othermod:peat_bog", "required": false }
  ]
}
```

at `data/moogs_paths/tags/worldgen/biome/has_path/swamp_path.json`. To take a biome away, replace the
tag outright with `"replace": true` and list everything it should keep.

### Turning off a built-in network

Override the network's file in your datapack, at the same path
(`data/moogs_paths/moogs_paths/path_network/<network>.json`), and give it no biomes. Copy the rest of the
original file across, since `path_type` and either `region_size` or `origin` are still required:

```json
{
  "path_type": "moogs_paths:dirt_road",
  "biomes": [],
  "weight": 3,
  "region_size": 36
}
```

A network with no biomes never generates a path, including the ones that lead out of structures.

## Commands

Requires permission level 2 (op). They search around wherever they run, so they also work from a command
block or the console, e.g. `/execute positioned 1000 64 -500 run paths locate`.

`/paths locate [network]` -- teleport-suggest the nearest path origin. With no argument, finds the nearest path of any network. With a network id, finds the nearest path that rolled that network, including structure-anchored ones. Click the chat coord to fill `/tp` with a spot on the path.

`/paths debug region` -- show which path region your position falls inside, for each loaded `region_size`.

`/paths debug networks` -- list every loaded `path_network` with its id, `path_type`, length range, region size and weight (or `origin`), and decorator-set counts.

`/paths debug anchors` -- for each structure-anchored network, list the nearest spots its structure set places a structure, which structure generated at each (click to teleport), and how many paths lead out of it.

`/paths debug structures` -- list every cached structure NBT and whether it resolved (`ok`) or is missing.

`/paths debug jobs` -- count the paths waiting to be laid in this dimension (and how many chunks of them are down), the paths already finished, and what is queued, computing and cached right now. A path is laid chunk by chunk as those chunks load, so it stays waiting until players have been everywhere it goes.

`/paths debug reload` -- reload datapacks as `/reload` does, then clear the mod's caches: the structure templates it has loaded, and every path it has worked out, so paths in chunks that haven't generated yet are planned again. A plain `/reload` keeps the cached templates, so use this after changing a `.nbt`. `path_type`, `path_network`, `structure_set`, `feature_decorator_set` and `bush_decorator_set` are datapack registries, which Minecraft only loads with the world on every version: changes to those files need the world (or server) restarted.
