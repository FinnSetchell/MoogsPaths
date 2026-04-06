# MoogsPaths — Development Roadmap

## Phase 1 — Foundation (1.20/1.20.1, Forge)

### Milestone 1: Project Skeleton
- [ ] Architectury + Forge Gradle setup
- [ ] Basic mod metadata (mods.toml, fabric.mod.json placeholder)
- [ ] Codec infrastructure for custom registries
- [ ] Data loading pipeline (reads JSON from datapacks)

### Milestone 2: Path Generation Core
- [ ] `PathType` codec + registry
- [ ] Guided random walk algorithm (single segment, no branches)
- [ ] Terrain heightmap sampling + path rasteriser
- [ ] Slope handling (cut/fill/abandon)
- [ ] Width and fade system
- [ ] Worldgen hook (PlacedFeature-based, region-aware cache)

### Milestone 3: Path Networks
- [ ] `PathNetworkType` codec + registry
- [ ] Branching logic
- [ ] Spawn frequency + seeded region decision
- [ ] Biome + dimension filtering
- [ ] Built-in default path types (dirt trail, gravel road)

### Milestone 4: Structures Along Paths
- [ ] `StructureSet` codec + registry
- [ ] NBT structure loading + placement
- [ ] Flatness check + placement validation
- [ ] Spacing and placement mode (any / branch / endpoint)
- [ ] A small set of built-in example structures (stone marker, lantern post)

### Milestone 5: Feature Decorators
- [ ] `FeatureDecoratorSet` codec + registry
- [ ] Corridor scatter logic
- [ ] Integration with vanilla PlacedFeature references
- [ ] Side / density / width controls

### Milestone 6: Polish + Release Prep
- [ ] Debug overlay (show path skeletons, F3 region info)
- [ ] Complete built-in data (a few ready-to-use network types)
- [ ] Documentation pass
- [ ] 1.20.1 Forge + Fabric release

---

## Phase 2 — 1.21/1.21.1 Port

- [ ] Update Architectury + Loom to 1.21 targets
- [ ] Add NeoForge loader module
- [ ] Audit API changes (registry, worldgen hooks) between 1.20 and 1.21
- [ ] Release Forge, NeoForge, and Fabric JARs for 1.21.1

---

## Out of Scope (for now)

- GUI / in-game editor for paths
- Dynamic paths (player-created at runtime)
- Minimap integration
- Multiplayer-specific path ownership
