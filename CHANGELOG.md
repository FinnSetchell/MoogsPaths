# Changelog

---

## [1.0.1] - 2026-06-17

- Path generation no longer runs during chunk-gen. Each candidate origin is now recorded as a deferred job during chunk-gen and the actual A* and block placement happens off-thread after chunks load, drip-fed at 4 placements per server tick. World load on heavy packs (Tectonic + Lithostitched + structure packs) drops from around 130s to around 20s; on top of c2me it drops to around 11s. Pending jobs are persisted as per-dimension SavedData so a save-and-quit doesn't lose paths in flight.
- `/paths locate [network]` now enqueues the deferred job for the resolved path so teleporting to the reported coordinates actually shows the path; previously the path was computed into the cache but never queued for placement.
- ConfiguredFeature decorations (the vegetation pass) still run during chunk-gen so the deferred path slice gets its trees and bushes painted by neighbouring chunk feature passes; the rasteriser, structure placer, and bush placer are owned exclusively by the deferred placement flow to avoid double-painting.
- Added chunk-load, server-tick-end, and server-stopping hooks to `IPlatformHelper` (wired on Forge, NeoForge, and Fabric).
- Known trade-off: the very first chunk to encounter a freshly-discovered origin will have sparser vegetation along its path slice because `FeatureScatterer` is intentionally not replayed against live chunks. Subsequent chunks intersecting the same path paint vegetation normally.

---

## [1.0.0] - 2026-05-18

- paths generate across the overworld with biome-specific surface blocks and edge palettes
- includes dirt trails, cobblestone roads, brick roads, sandstone highways, mountain roads, mossy jungle paths, red sand trails, and a badlands canyon network
- roadside decorations: cairns, lamps, signposts, shrines, benches, and biome-matched flowers and bushes
- oceans, rivers, caves, the void, and the deep dark are excluded from generation by default
- use the `moogs_paths:has_no_paths` biome tag to exclude additional biomes
- `/paths locate [network]` finds the nearest path; `/paths debug` shows runtime info
- supports Fabric, NeoForge and Forge on Minecraft 1.21-1.21.1
