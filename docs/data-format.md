# MoogsPaths — JSON Data Format Reference

All files live inside a datapack or mod's `data/` directory.
Namespacing follows standard Minecraft conventions.

---

## PathNetworkType

**Location:** `data/<namespace>/moogspaths/path_networks/<id>.json`

```json
{
  "weight": 10,
  "dimension_filter": {
    "type": "whitelist",
    "values": ["minecraft:overworld"]
  },
  "biome_filter": {
    "type": "tag",
    "tag": "#minecraft:is_forest"
  },
  "region_size": 512,
  "attempts_per_region": 1,
  "scale": {
    "type": "local",
    "length_min": 400,
    "length_max": 1000,
    "branch_count_min": 1,
    "branch_count_max": 3,
    "branch_probability": 0.15
  },
  "path_type": "moogspaths:dirt_trail",
  "structure_sets": [
    { "id": "moogspaths:stone_markers", "weight": 1 }
  ],
  "feature_decorator_sets": [
    { "id": "moogspaths:forest_edge_flora", "weight": 1 }
  ]
}
```

### scale.type values

| Value      | Description                  |
|------------|------------------------------|
| `trail`    | 100–400 blocks, 0–1 branches |
| `local`    | 400–1000 blocks, 1–3 branches|
| `regional` | 1000–4000 blocks, 3–8 branches|
| `grand`    | 4000+ blocks, 8+ branches    |
| `custom`   | Use explicit min/max fields  |

---

## PathType

**Location:** `data/<namespace>/moogspaths/path_types/<id>.json`

```json
{
  "surface_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:dirt_path", "weight": 8 },
      { "block": "minecraft:gravel",    "weight": 2 }
    ]
  },
  "fill_block": "minecraft:dirt",
  "edge_blocks": {
    "type": "weighted_list",
    "entries": [
      { "block": "minecraft:coarse_dirt", "weight": 5 },
      { "block": "minecraft:grass_block", "weight": 3 }
    ]
  },
  "width": {
    "min": 2,
    "max": 4,
    "taper_ends": true
  },
  "curviness": 0.35,
  "slope_handling": {
    "max_step_height": 1,
    "cut_tolerance": 3,
    "fill_tolerance": 2,
    "abandon_on_cliff": true,
    "cliff_threshold": 6
  },
  "fade": {
    "start_blocks": 8,
    "end_blocks": 12
  }
}
```

### curviness

`0.0` = perfectly straight. `1.0` = fully random direction each step.
Recommended range: `0.1`–`0.5`.

### slope_handling

- `cut_tolerance`: how many blocks deep the path will cut into a hillside
- `fill_tolerance`: how many blocks of air below the path surface will be filled
- `cliff_threshold`: if terrain drops more than this in one step, the path
  either ends (if `abandon_on_cliff: true`) or skips to the lower level

---

## StructureSet

**Location:** `data/<namespace>/moogspaths/structure_sets/<id>.json`

```json
{
  "spacing": 60,
  "spacing_variance": 20,
  "placement": "any",
  "flatness_tolerance": 2,
  "biome_filter_override": null,
  "structures": [
    {
      "nbt": "moogspaths:stone_lantern",
      "weight": 6,
      "offset": [0, 0, 0],
      "rotation": "random"
    },
    {
      "nbt": "moogspaths:broken_post",
      "weight": 2,
      "offset": [0, 0, 0],
      "rotation": "random"
    }
  ]
}
```

### placement values

| Value          | Description                                |
|----------------|--------------------------------------------|
| `any`          | Any point along the path                   |
| `branch_point` | Only at branching junctions                |
| `endpoint`     | Only at path start/end                     |
| `interval`     | Strictly at `spacing` intervals            |

---

## FeatureDecoratorSet

**Location:** `data/<namespace>/moogspaths/feature_decorator_sets/<id>.json`

```json
{
  "scatter_width": 6,
  "density": 0.08,
  "side": "both",
  "biome_filter_override": null,
  "features": [
    {
      "feature": "minecraft:forest_flower_vegetation",
      "weight": 4
    },
    {
      "feature": "moogspaths:mossy_stone_cluster",
      "weight": 2
    }
  ]
}
```

### density

Probability (0.0–1.0) of a feature attempt per path block column.
`0.08` = roughly 8 attempts per 100 blocks.

### side values

`"left"`, `"right"`, `"both"`, `"center"` (on the path surface itself).

---

## Biome Filter

Reusable sub-object used in several types above.

```json
{ "type": "any" }

{ "type": "whitelist", "values": ["minecraft:forest", "minecraft:birch_forest"] }

{ "type": "blacklist", "values": ["minecraft:desert"] }

{ "type": "tag", "tag": "#minecraft:is_forest" }

{
  "type": "and",
  "filters": [
    { "type": "tag", "tag": "#minecraft:is_overworld" },
    { "type": "blacklist", "values": ["minecraft:ocean"] }
  ]
}
```
