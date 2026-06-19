# Changelog

---

## [1.0.5] - 2026-06-19

_Pending. Update this header date and replace this line with the actual changes before tagging._

---

## [1.0.4] - 2026-06-18

- Considerable performance improvement to world loading. Paths now generate quietly in the background instead of blocking the loading bar, so getting into your world is much faster. The bigger your modpack, the bigger the win. On our machine with Tectonic and Lithostitched, world load dropped from around 130 seconds to around 30. Adding C2ME dropped it further to around 10. Your numbers will vary with hardware and pack composition.
- After running `/paths locate`, teleporting to the reported location now actually shows the path. Previously the command would find a path and tell you where it was, but the path wouldn't appear when you arrived.

---

## [1.0.3] - 2026-05-29

- Fixed forge release jar not being remapped correctly, causing a crash on startup

---

## [1.0.2] - 2026-05-29

- Fixed forge release jar not being remapped correctly, causing a crash on startup

---

## [1.0.1] - 2026-05-18

- Reformatted all json code
- Fixed a crash on startup affecting some users

---

## [1.0.0] - 2026-05-18

- paths generate across the overworld with biome-specific surface blocks and edge palettes
- includes dirt trails, cobblestone roads, brick roads, sandstone highways, mountain roads, mossy jungle paths, red sand trails, and a badlands canyon network
- roadside decorations: cairns, lamps, signposts, shrines, benches, and biome-matched flowers and bushes
- oceans, rivers, caves, the void, and the deep dark are excluded from generation by default
- use the `moogs_paths:has_no_paths` biome tag to exclude additional biomes
- `/paths locate [network]` finds the nearest path; `/paths debug` shows runtime info
- supports Fabric and Forge on Minecraft 1.20.1
