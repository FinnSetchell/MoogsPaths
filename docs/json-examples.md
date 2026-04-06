# MoogsPaths — JSON Examples

Concrete examples of how data files work together, and what each produces in-game.
Each example shows the full chain: PathType → StructureSet → FeatureDecoratorSet → PathNetworkType.

---

## Example 1: Forest Dirt Trail

A short, winding dirt trail that meanders through forests. Feels like an animal track
or an old footpath that's been slowly reclaimed by nature. Occasionally a mossy stone
marker appears. Ferns and wildflowers line the edges.

### `data/moogspaths/moogspaths/path_types/forest_dirt_trail.json`
```json
{
  "surface_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:dirt_path", "weight": 7 },
      { "block": "minecraft:coarse_dirt", "weight": 2 },
      { "block": "minecraft:rooted_dirt", "weight": 1 }
    ]
  },
  "fill_block": "minecraft:dirt",
  "edge_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:coarse_dirt", "weight": 4 },
      { "block": "minecraft:moss_block",  "weight": 2 },
      { "block": "minecraft:grass_block", "weight": 4 }
    ]
  },
  "width": {
    "min": 1,
    "max": 2,
    "taper_ends": true
  },
  "curviness": 0.55,
  "slope_handling": {
    "max_step_height": 1,
    "cut_tolerance": 2,
    "fill_tolerance": 1,
    "abandon_on_cliff": true,
    "cliff_threshold": 5
  },
  "fade": {
    "start_blocks": 6,
    "end_blocks": 10
  }
}
```

### `data/moogspaths/moogspaths/structure_sets/mossy_markers.json`
```json
{
  "spacing": 80,
  "spacing_variance": 30,
  "placement": "any",
  "flatness_tolerance": 2,
  "biome_filter_override": null,
  "structures": [
    { "nbt": "moogspaths:mossy_stone_post",  "weight": 5, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:broken_stone_pile", "weight": 3, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:small_cairn",       "weight": 2, "offset": [0, 0, 0], "rotation": "none"   }
  ]
}
```

### `data/moogspaths/moogspaths/feature_decorator_sets/forest_undergrowth.json`
```json
{
  "scatter_width": 4,
  "density": 0.12,
  "side": "both",
  "biome_filter_override": null,
  "features": [
    { "feature": "minecraft:forest_flower_vegetation", "weight": 4 },
    { "feature": "minecraft:fern_vegetation",          "weight": 4 },
    { "feature": "minecraft:patch_brown_mushroom",     "weight": 2 },
    { "feature": "minecraft:patch_red_mushroom",       "weight": 1 }
  ]
}
```

### `data/moogspaths/moogspaths/path_networks/forest_trail.json`
```json
{
  "weight": 12,
  "dimension_filter": { "type": "whitelist", "values": ["minecraft:overworld"] },
  "biome_filter": { "type": "tag", "tag": "#minecraft:is_forest" },
  "region_size": 512,
  "attempts_per_region": 2,
  "scale": {
    "type": "custom",
    "length_min": 150,
    "length_max": 450,
    "branch_count_min": 0,
    "branch_count_max": 1,
    "branch_probability": 0.08
  },
  "path_type": "moogspaths:forest_dirt_trail",
  "structure_sets": [
    { "id": "moogspaths:mossy_markers", "weight": 1 }
  ],
  "feature_decorator_sets": [
    { "id": "moogspaths:forest_undergrowth", "weight": 1 }
  ]
}
```

**In-game result:** A narrow (1–2 block wide), heavily winding path of dirt and coarse
dirt winding between trees. It fades in slowly from nowhere and disappears into the
undergrowth. Mossy stone posts appear roughly every 50–110 blocks. Ferns and mushrooms
cluster along both edges. Occasionally the path splits into a short dead-end branch.

---

## Example 2: Plains Gravel Road

A wider, more deliberate gravel road crossing open plains and savanna. Straighter than
a forest trail. Feels like a trade route or old wagon road. Stone lanterns mark it at
intervals. Wildflowers grow on the verges.

### `data/moogspaths/moogspaths/path_types/plains_gravel_road.json`
```json
{
  "surface_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:gravel",      "weight": 6 },
      { "block": "minecraft:stone",       "weight": 2 },
      { "block": "minecraft:cobblestone", "weight": 2 }
    ]
  },
  "fill_block": "minecraft:gravel",
  "edge_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:gravel",      "weight": 3 },
      { "block": "minecraft:coarse_dirt", "weight": 5 },
      { "block": "minecraft:grass_block", "weight": 2 }
    ]
  },
  "width": {
    "min": 3,
    "max": 4,
    "taper_ends": true
  },
  "curviness": 0.20,
  "slope_handling": {
    "max_step_height": 1,
    "cut_tolerance": 3,
    "fill_tolerance": 3,
    "abandon_on_cliff": false,
    "cliff_threshold": 8
  },
  "fade": {
    "start_blocks": 5,
    "end_blocks": 8
  }
}
```

### `data/moogspaths/moogspaths/structure_sets/stone_lanterns.json`
```json
{
  "spacing": 55,
  "spacing_variance": 15,
  "placement": "any",
  "flatness_tolerance": 3,
  "biome_filter_override": null,
  "structures": [
    { "nbt": "moogspaths:stone_lantern_post", "weight": 7, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:double_lantern_post","weight": 3, "offset": [0, 0, 0], "rotation": "random" }
  ]
}
```

### `data/moogspaths/moogspaths/feature_decorator_sets/plains_verge.json`
```json
{
  "scatter_width": 6,
  "density": 0.07,
  "side": "both",
  "biome_filter_override": null,
  "features": [
    { "feature": "minecraft:patch_sunflower",          "weight": 3 },
    { "feature": "minecraft:patch_tall_grass",         "weight": 5 },
    { "feature": "minecraft:plain_vegetation",         "weight": 4 },
    { "feature": "minecraft:flower_plain",             "weight": 3 }
  ]
}
```

### `data/moogspaths/moogspaths/path_networks/plains_road.json`
```json
{
  "weight": 8,
  "dimension_filter": { "type": "whitelist", "values": ["minecraft:overworld"] },
  "biome_filter": {
    "type": "and",
    "filters": [
      { "type": "tag", "tag": "#minecraft:is_overworld" },
      {
        "type": "whitelist",
        "values": [
          "minecraft:plains", "minecraft:sunflower_plains",
          "minecraft:savanna", "minecraft:savanna_plateau"
        ]
      }
    ]
  },
  "region_size": 512,
  "attempts_per_region": 1,
  "scale": {
    "type": "regional",
    "length_min": 1000,
    "length_max": 2500,
    "branch_count_min": 2,
    "branch_count_max": 5,
    "branch_probability": 0.12
  },
  "path_type": "moogspaths:plains_gravel_road",
  "structure_sets": [
    { "id": "moogspaths:stone_lanterns", "weight": 1 }
  ],
  "feature_decorator_sets": [
    { "id": "moogspaths:plains_verge", "weight": 1 }
  ]
}
```

**In-game result:** A broad (3–4 block wide) gravel road sweeping across open plains
in a fairly straight line with gentle curves. It branches 2–5 times, each branch
running several hundred blocks before fading. Stone lantern posts appear every 40–70
blocks. Sunflowers and tall grass line the edges. Because `abandon_on_cliff` is false,
the road attempts to continue down slopes rather than stopping.

---

## Example 3: Jungle Overgrown Path

A barely-visible ancient path threading through jungle. Very narrow, heavily
overgrown, mostly reclaimed by moss and vines. Crumbling stone ruins appear
along it. Tropical vegetation chokes the edges.

### `data/moogspaths/moogspaths/path_types/jungle_overgrown.json`
```json
{
  "surface_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:mossy_cobblestone", "weight": 3 },
      { "block": "minecraft:moss_block",        "weight": 4 },
      { "block": "minecraft:cobblestone",       "weight": 2 },
      { "block": "minecraft:coarse_dirt",       "weight": 1 }
    ]
  },
  "fill_block": "minecraft:stone",
  "edge_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:moss_block",        "weight": 5 },
      { "block": "minecraft:mossy_cobblestone", "weight": 3 },
      { "block": "minecraft:mud",               "weight": 2 }
    ]
  },
  "width": {
    "min": 1,
    "max": 3,
    "taper_ends": true
  },
  "curviness": 0.65,
  "slope_handling": {
    "max_step_height": 2,
    "cut_tolerance": 2,
    "fill_tolerance": 1,
    "abandon_on_cliff": true,
    "cliff_threshold": 6
  },
  "fade": {
    "start_blocks": 10,
    "end_blocks": 15
  }
}
```

### `data/moogspaths/moogspaths/structure_sets/jungle_ruins.json`
```json
{
  "spacing": 120,
  "spacing_variance": 50,
  "placement": "any",
  "flatness_tolerance": 4,
  "biome_filter_override": null,
  "structures": [
    { "nbt": "moogspaths:crumbled_arch",    "weight": 4, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:ruined_platform",  "weight": 3, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:stone_idol",       "weight": 2, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:overgrown_pillar", "weight": 3, "offset": [0, 0, 0], "rotation": "random" }
  ]
}
```

### `data/moogspaths/moogspaths/feature_decorator_sets/jungle_undergrowth.json`
```json
{
  "scatter_width": 3,
  "density": 0.18,
  "side": "both",
  "biome_filter_override": null,
  "features": [
    { "feature": "minecraft:jungle_bush",          "weight": 5 },
    { "feature": "minecraft:patch_melon",          "weight": 2 },
    { "feature": "minecraft:bamboo_light",         "weight": 3 },
    { "feature": "minecraft:patch_sugar_cane",     "weight": 2 },
    { "feature": "minecraft:flower_jungle",        "weight": 3 }
  ]
}
```

### `data/moogspaths/moogspaths/path_networks/jungle_ruins_path.json`
```json
{
  "weight": 6,
  "dimension_filter": { "type": "whitelist", "values": ["minecraft:overworld"] },
  "biome_filter": {
    "type": "whitelist",
    "values": [
      "minecraft:jungle", "minecraft:sparse_jungle", "minecraft:bamboo_jungle"
    ]
  },
  "region_size": 512,
  "attempts_per_region": 1,
  "scale": {
    "type": "local",
    "length_min": 300,
    "length_max": 800,
    "branch_count_min": 1,
    "branch_count_max": 3,
    "branch_probability": 0.18
  },
  "path_type": "moogspaths:jungle_overgrown",
  "structure_sets": [
    { "id": "moogspaths:jungle_ruins", "weight": 1 }
  ],
  "feature_decorator_sets": [
    { "id": "moogspaths:jungle_undergrowth", "weight": 1 }
  ]
}
```

**In-game result:** A barely-recognisable path of moss and crumbling cobblestone
winding sharply through the jungle canopy. It appears and disappears over a long fade.
Crumbled arches and stone idols emerge from the undergrowth every 70–170 blocks.
Bamboo, jungle bush, and sugar cane cluster on both sides. This one feels ancient
and discovered, not built.

---

## Example 4: Snowy Mountain Pass

A wide, rugged stone pass crossing high mountain terrain. Relatively straight —
mountain passes don't meander. Cuts into cliff faces rather than going around.
Occasional stone shelters. Sparse vegetation (only hardy alpine plants).

### `data/moogspaths/moogspaths/path_types/mountain_pass.json`
```json
{
  "surface_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:stone",           "weight": 5 },
      { "block": "minecraft:cobblestone",     "weight": 3 },
      { "block": "minecraft:gravel",          "weight": 2 }
    ]
  },
  "fill_block": "minecraft:stone",
  "edge_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:stone",         "weight": 4 },
      { "block": "minecraft:gravel",        "weight": 3 },
      { "block": "minecraft:powder_snow",   "weight": 1 }
    ]
  },
  "width": {
    "min": 3,
    "max": 5,
    "taper_ends": false
  },
  "curviness": 0.15,
  "slope_handling": {
    "max_step_height": 2,
    "cut_tolerance": 6,
    "fill_tolerance": 4,
    "abandon_on_cliff": false,
    "cliff_threshold": 12
  },
  "fade": {
    "start_blocks": 4,
    "end_blocks": 6
  }
}
```

### `data/moogspaths/moogspaths/structure_sets/mountain_shelters.json`
```json
{
  "spacing": 200,
  "spacing_variance": 60,
  "placement": "any",
  "flatness_tolerance": 5,
  "biome_filter_override": null,
  "structures": [
    { "nbt": "moogspaths:stone_shelter",    "weight": 5, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:boundary_marker", "weight": 4, "offset": [0, 0, 0], "rotation": "random" },
    { "nbt": "moogspaths:collapsed_wall",  "weight": 3, "offset": [0, 0, 0], "rotation": "random" }
  ]
}
```

### `data/moogspaths/moogspaths/feature_decorator_sets/alpine_sparse.json`
```json
{
  "scatter_width": 4,
  "density": 0.03,
  "side": "both",
  "biome_filter_override": null,
  "features": [
    { "feature": "minecraft:patch_grass",          "weight": 3 },
    { "feature": "minecraft:freeze_top_layer",     "weight": 2 },
    { "feature": "minecraft:flower_meadow",        "weight": 1 }
  ]
}
```

### `data/moogspaths/moogspaths/path_networks/mountain_pass_network.json`
```json
{
  "weight": 4,
  "dimension_filter": { "type": "whitelist", "values": ["minecraft:overworld"] },
  "biome_filter": {
    "type": "tag",
    "tag": "#minecraft:is_mountain"
  },
  "region_size": 768,
  "attempts_per_region": 1,
  "scale": {
    "type": "custom",
    "length_min": 600,
    "length_max": 1500,
    "branch_count_min": 0,
    "branch_count_max": 2,
    "branch_probability": 0.06
  },
  "path_type": "moogspaths:mountain_pass",
  "structure_sets": [
    { "id": "moogspaths:mountain_shelters", "weight": 1 }
  ],
  "feature_decorator_sets": [
    { "id": "moogspaths:alpine_sparse", "weight": 1 }
  ]
}
```

**In-game result:** A wide (3–5 block), nearly straight stone road cutting through
mountain peaks. It doesn't fade — it appears solidly and ends abruptly, like
infrastructure rather than a trail. High `cut_tolerance` means it carves into
hillsides rather than routing around them. Stone shelters appear roughly every
140–260 blocks. Vegetation is almost absent — just sparse grass and the occasional
alpine flower. Rarely branches. Rarer to find than forest trails.

---

## Mixing: What Happens When Biomes Blend

The `biome_filter` on a `PathNetworkType` controls where the *network spawns*.
The `PathType` is constant for the whole path. If a path starts in forest and
crosses into plains, the surface blocks remain `forest_dirt_trail` blocks
throughout — **the PathType does not change mid-path**.

If you want biome-adaptive block palettes, define a `PathType` with a broad block
palette that works across biomes, or rely on multiple networks with overlapping
regions covering different biomes.

---

## A Note on NBT Structures

The `nbt` field in a `StructureSet` references a structure file at:

```
data/<namespace>/structures/<path>.nbt
```

So `"nbt": "moogspaths:stone_lantern_post"` loads from:
```
data/moogspaths/structures/stone_lantern_post.nbt
```

These are standard Minecraft `.nbt` structure files, the same format used by
structure blocks in-game. Any structure saved with a structure block can be
dropped into this folder and referenced immediately — no extra code needed.
