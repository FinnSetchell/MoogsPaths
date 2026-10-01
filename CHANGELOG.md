# Changelog

---

## [1.1.0] - 2026-09-29

### Added
- Roads lead out from the ends of village streets, styled to their biome
- Trails lead from the doors of witch huts and jungle temples
- Towns and Towers villages get roads too
- Four Moog's Voyager Structures houses get a path up to their door
- A rarity slider for each path, in the config or in game with Cloth Config
- Datapacks can start paths at any structure, door or street end
- Minecraft 26.3 support (Fabric and NeoForge)
- Players without the mod can join Forge servers that have it

### Changed
- Paths appear in modded biomes from Terralith, Biomes O' Plenty and Regions Unexplored
- Dirt roads also run through snowy taigas
- Paths on 1.20 generate in the background, so worlds load faster
- `/paths locate` finds structure roads, works from the console and puts you on the path
- Uses less memory on 1.21 and newer
- Datapack `spacing` and `fade` now count blocks, as the guide says

### Fixed
- Paths no longer cut through houses, farms, doors or chests
- Bushes no longer grow on paths
- Flowers line paths near spawn too
- No roads to temples or huts that YUNG's mods replace
- Long-running servers no longer slow down as paths pile up
- Paths from one world no longer turn up in the next
- Paths stay in the overworld on Forge and NeoForge
- Flowers grow along plains paths on 1.20 and 26.1
- 1.20 Fabric asks for a newer Fabric Loader instead of generating no paths
- Dirt paths no longer stack or sit under blocks
- Mushroom paths now generate
- `/paths locate` no longer points into water

---

## [1.0.2] - 2026-06-22

- Fixed path decorations (signposts, cairns, shrines, lamps, benches) being silently skipped on 1.21+. They now actually place along generated paths.

---

## [1.0.1] - 2026-06-18

- Considerable performance improvement to world loading. Paths now generate quietly in the background instead of blocking the loading bar, so getting into your world is much faster. On our machine with a heavy pack (Tectonic, Lithostitched, structure packs, and C2ME), world load was essentially indistinguishable from running without a path mod — under 7 seconds on Fabric, NeoForge, and Forge. Your numbers will vary with hardware and pack composition.
- After running `/paths locate`, teleporting to the reported location now actually shows the path. Previously the command would find a path and tell you where it was, but the path wouldn't appear when you arrived.

---

## [1.0.0] - 2026-05-18

- paths generate across the overworld with biome-specific surface blocks and edge palettes
- includes dirt trails, cobblestone roads, brick roads, sandstone highways, mountain roads, mossy jungle paths, red sand trails, and a badlands canyon network
- roadside decorations: cairns, lamps, signposts, shrines, benches, and biome-matched flowers and bushes
- oceans, rivers, caves, the void, and the deep dark are excluded from generation by default
- use the `moogs_paths:has_no_paths` biome tag to exclude additional biomes
- `/paths locate [network]` finds the nearest path; `/paths debug` shows runtime info
- supports Fabric, NeoForge and Forge on Minecraft 1.21-1.21.1
