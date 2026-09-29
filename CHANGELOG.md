# Changelog

---

## [1.1.0] - 2026-09-29

### Added
- Roads now lead out of every village, two per village, in the style of its biome
- Trails lead out of witch huts and jungle temples
- Datapacks can start paths at any vanilla or modded structure
- Support for Minecraft 26.3 on Fabric and NeoForge

### Changed
- On 1.20, paths now generate in the background like every other version, so servers start and worlds load faster
- `/paths locate` also finds roads out of structures, and works from command blocks and the console

### Fixed
- Flowers now grow along plains paths on 1.20 and 26.1
- On 1.20 Fabric, older Fabric Loader versions now get a clear message instead of silently generating no paths

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
