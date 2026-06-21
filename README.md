![header](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/d5e8c0f59add420ea9e76f95544c256a.png)

[![Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/D1D8LKA5N)

[![Discord](https://img.shields.io/discord/869218732650688543?color=F46B10&label=DISCORD&style=for-the-badge)](https://discord.com/invite/S5nffJbuvA)

[![My projects](https://img.shields.io/badge/CurseForge-projects-F46B10?style=for-the-badge&logo=curseforge)](https://www.curseforge.com/members/finndog_123/projects)

[![My projects](https://img.shields.io/badge/Modrinth-projects-F46B10?style=for-the-badge&logo=modrinth)](https://modrinth.com/user/FinnSetchell)

A data-driven path and trail network for Minecraft. Biome-specific paths and roadside decorations bring the overworld to life. Fully configurable through datapacks.

This mod works on Fabric and NeoForge for Minecraft 26.2.

![overview](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/46b98148bc4b4e24b53d6b0d780a7c4a.png)

Moog's Paths generates a network of paths, trails and roads across the overworld. Each biome gets its own style of path built from blocks that belong there, lined with decorations that fit the landscape. Paths carve through hills, follow terrain, bridge over water, and fade naturally into the ground at their endpoints.

Everything is defined in JSON. No new items, no crafting recipes. The mod ships a full set of built-in paths, but every path type, network, structure, and decorator can be overridden or extended through datapacks without writing a single line of code.

![paths](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/a3ebaaa8a9f74f1684efcfc8a8b118d4.png)

**15 path networks** across every major overworld biome:

*   **Plains Trail** - dirt path through plains and meadows with grass and wildflower scatter, oak and azalea bush clusters alongside
*   **Dirt Road** - wide coarse dirt, podzol and rooted dirt road through taiga and dark forest with cairns along the route
*   **Overworld Trail** - cobblestone and mossy cobblestone road that can appear across any overworld biome with oak posts and wildflowers
*   **Brick Road** - bricks and polished granite across the general overworld with brick lanterns and oak signposts at intervals
*   **Mountain Road** - cobblestone, andesite and stone brick path through all mountain biomes with andesite lamps
*   **Desert Highway** - wide smooth and cut sandstone road across deserts with lamps and birch posts, dead bushes scattered alongside
*   **Jungle Path** - mossy stone bricks winding under the canopy with branch shrines and leaf overgrowth
*   **Badlands Canyon Trail** - red sand and terracotta through mesas and eroded badlands with weathered markers
*   **Snowy Trail** - packed snow, ice and gravel through snowy biomes with cairns and spruce bush clusters
*   **Swamp Path** - mud bricks and packed mud through swamps and mangrove swamps, switching to oak and spruce planks over water, with wooden posts and swamp grass
*   **Cherry Grove Path** - winding dirt path through cherry groves with benches and cherry leaf litter
*   **Mushroom Trail** - mycelium and podzol through mushroom fields with shrines, mushroom decorations, candles and scattered mushrooms
*   **Savanna Path** - dirt trail through savanna biomes with wooden posts
*   **Windswept Trail** - rugged gravel and cobblestone across exposed windswept terrain with andesite lamps
*   **Wildlands Trail** - a rare fallback dirt trail with andesite lamps that can appear in any overworld biome

Each path is decorated with a combination of:

*   **15 NBT structures** - cairns, lamps, signposts, shrines, benches, markers, posts, and candles
*   **Scattered features** - flowers, dead bushes, mushrooms, tall grass
*   **Bush clusters** - oak, azalea, spruce and cherry leaf blobs alongside the path

![features](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/9c90489705864e9b8b5ed564dc536d7d.png)

*   **Biome-aware generation** - paths pick surfaces and decorations that match their biome
*   **Terrain-following pathfinder** - A* pathfinding on a 4x4 block grid with slope awareness, cliff rejection and configurable rigidness
*   **Smooth curves** - Chaikin corner-cutting and carver smoothing produce natural-looking paths
*   **Fade in / fade out** - paths blend into the terrain at their start and end
*   **Water bridging** - paths can use alternate blocks over water (boardwalk planks, etc.)
*   **NBT structure placement** - place structures at endpoints or at intervals along the path, with flatness checks and terrain adjustment
*   **Fully data-driven** - every path type, network, structure set and decorator set is a JSON file that datapacks can override or extend

![datapacks](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/833ffe14834e4daea69c0fea20ce1f91.png)

Moog's Paths is built around five custom datapack registries:

*   **path_type** - the visual appearance and shape of a path (blocks, width, curvature, length)
*   **path_network** - binds a path_type to biomes and attaches decorators
*   **structure_set** - reusable bundles of NBT structures placed along paths
*   **feature_decorator_set** - vanilla configured features scattered alongside paths
*   **bush_decorator_set** - leaf-blob bushes generated beside paths

Add your own paths by dropping JSON files into `data/<namespace>/moogs_paths/`. Override built-in paths by using the `moogs_paths` namespace. Block biomes from generating paths with the `moogs_paths:has_no_paths` biome tag.

Full datapack documentation: [DATAPACK_GUIDE.md](DATAPACK_GUIDE.md)

![modpacks](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/e811525bcdb6423f8886ae3f43456c88.png)

Feel free to include this mod in modpacks. No special permission needed.

![support](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/53b2ec08d1ad4aafba148a28571cd3e7.png)

The best way to get a reply is to join the Discord server.

*   [Discord](https://discord.gg/S5nffJbuvA)
*   [GitHub / Issue Tracker](https://github.com/FinnSetchell/MoogsPaths)
*   [Ko-fi](https://ko-fi.com/finndog)

![BH promo banner](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/0b15a121fb4947bcad4f6fc542f3b9bf.png)
