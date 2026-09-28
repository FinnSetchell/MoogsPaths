# Building Moog's Paths

One source tree builds every Minecraft version and loader Moog's Paths supports, through
[Stonecutter](https://stonecutter.kikugie.dev/). Each version × loader is a Gradle subproject
("node") under `versions/`, declared in `settings.gradle.kts`. Gradle runs on Java 25; each node
compiles on its own toolchain (17 for 1.20-1.20.1, 21 for 1.21.x, 25 for 26.x).

| Range | Node | Fabric | Forge | NeoForge |
| --- | --- | --- | --- | --- |
| 1.20 | `1.20` | yes | yes (legacy plugin, reobfuscated) | - |
| 1.20.1 | `1.20.1` | yes | yes (legacy plugin, reobfuscated) | - |
| 1.21-1.21.1 | `1.21.1` | yes | yes | yes |
| 1.21.11 | `1.21.11` | yes | - | yes |
| 26.1-26.1.2 | `26.1.2` | yes | - | yes |
| 26.2 | `26.2` | yes | - | yes |
| 26.3 | `26.3` | yes | - | yes |

## Building

```bash
./gradlew :1.21.1-fabric:buildAndCollect
```

```bash
./gradlew buildAndCollect
```

The first builds one node, the second every node. Jars land in `build/libs/<mod version>/`.

## Where things live

- `src/main/java` - all Java. Loader code sits in the `fabric`, `forge` and `neoforge` packages; each
  loader's build excludes the other two.
- `src/main/resources` - the shared resources, written for 1.21. `src/<loader>/resources` - loader
  metadata, service files and the Fabric mixin config.
- `src/overlays/<version>` - resources that replace a shared file from that version on (26.2 dropped
  `patch_tall_grass`; 26.3 moved configured features to `worldgen/feature`). A node lists the overlays
  it takes in `mod.overlays`.
- `buildSrc/src/main/kotlin/legacy-data.gradle.kts` - rewrites the datapack into its pre-1.21 shape for
  the 1.20 nodes (`structures/` folder, boxed int providers), so the data only exists once.
- `stonecutter.properties.toml` - per-node versions, ranges, pack formats and the switches above.
- `build.<loader>.gradle.kts` - one build script per loader; `build.forge-legacy.gradle.kts` builds
  Forge before 1.20.5.

## Version-specific code

The committed source is the `1.21.1-fabric` node. Other versions differ through Stonecutter
comments:

```java
//? if <1.21.11 {
if(sy <= level.getMinBuildHeight()) continue;
//?} else {
/*if(sy <= level.getMinY()) continue;
*///?}
```

- Switch the active version with the Stonecutter IntelliJ plugin, or run
  `./gradlew "Set active project to 26.3-fabric"`. Switch back with `./gradlew "Reset active project"`
  before committing.
- `ResourceLocation` is written everywhere; Stonecutter rewrites it to `Identifier` for 1.21.11 and
  later. So no other name, and no comment, may contain either word.
- A block must not open with a comment line: Stonecutter reads it as already commented out. Put the
  comment above the `//? if`.
- `//?} elif <cond> {` chains a third branch, as in `FeatureScatterer`.
