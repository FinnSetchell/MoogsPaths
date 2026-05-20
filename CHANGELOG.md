# Changelog

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
