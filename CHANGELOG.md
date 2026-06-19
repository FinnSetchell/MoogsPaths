# Changelog

---

## [1.0.3] - 2026-06-19

_Pending. Update this header date and replace this line with the actual changes before tagging._

---

## [1.0.2] - 2026-06-19

- Considerable performance improvement to world loading. Paths now generate quietly in the background instead of blocking the loading bar, so getting into your world is much faster. On our machine with a heavy pack (Tectonic, Lithostitched, structure packs, and C2ME), world load was essentially indistinguishable from running without a path mod — under 10 seconds on Fabric and NeoForge. Your numbers will vary with hardware and pack composition.
- After running `/paths locate`, teleporting to the reported location now actually shows the path. Previously the command would find a path and tell you where it was, but the path wouldn't appear when you arrived.

---

## [1.0.1] - 2026-05-20

_Pending. Update this header date and replace this line with the actual changes before tagging._

---

## [1.0.0] - 2026-05-20

- ported to Minecraft 26.1-26.1.2 (Java 25, fully unobfuscated Mojang mappings; Parchment dropped)
- server-side mod: required on the server, optional on remote clients (still loads in singleplayer)
- paths generate across the overworld with biome-specific surface blocks and edge palettes
- includes dirt trails, cobblestone roads, brick roads, sandstone highways, mountain roads, mossy jungle paths, red sand trails, and a badlands canyon network
- roadside decorations: cairns, lamps, signposts, shrines, benches, and biome-matched flowers and bushes
- oceans, rivers, caves, the void, and the deep dark are excluded from generation by default
- use the `moogs_paths:has_no_paths` biome tag to exclude additional biomes
- `/paths locate [network]` finds the nearest path; `/paths debug` shows runtime info
- supports Fabric and NeoForge on Minecraft 26.1-26.1.2
