## [1.0.0]
- paths now generate across the overworld, with biome-specific surface blocks and edge palettes
- adds dirt trails, cobblestone roads, brick roads, sandstone highways, mountain roads, mossy jungle paths, red sand trails and a badlands canyon network
- scatters cairns, lamps, signposts, shrines and benches alongside paths
- adds matching flower and dead-bush decorators for forests, plains, deserts and jungles
- adds the `moogs_paths:has_no_paths` biome tag for excluding biomes from path generation
- oceans, rivers, the void, deep dark, lush caves and dripstone caves are excluded by default
- biome selectors accept vanilla ids, arrays and tag references (`#minecraft:is_overworld`, `#c:is_overworld`, modded tags)
- adds `/paths locate [network]` to find the nearest path and `/paths debug` for runtime instrumentation
- supports fabric and forge on minecraft 1.20.1
