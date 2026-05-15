![header](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/d5e8c0f59add420ea9e76f95544c256a.png)

[![Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/D1D8LKA5N)

[![Discord](https://img.shields.io/discord/869218732650688543?color=1A6E8A&label=DISCORD&style=for-the-badge)](https://discord.com/invite/S5nffJbuvA)

[![My projects](https://img.shields.io/badge/CurseForge-projects-1A6E8A?style=for-the-badge&logo=curseforge)](https://www.curseforge.com/members/finndog_123/projects)

[![My projects](https://img.shields.io/badge/Modrinth-projects-1A6E8A?style=for-the-badge&logo=modrinth)](https://modrinth.com/user/FinnSetchell)

A data-driven path and trail network for Minecraft worldgen. Biome-specific surfaces, scattered landmarks, and roadside decorations bring the overworld to life. Fully configurable through datapacks.

This mod works on Fabric and Forge for Minecraft 1.20.1.

![overview](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/46b98148bc4b4e24b53d6b0d780a7c4a.png)

Moog's Paths generates a network of paths, trails and roads across the overworld. Each biome gets its own style of path built from blocks that belong there, lined with decorations that fit the landscape. Paths carve through hills, follow terrain, bridge over water, and fade naturally into the ground at their endpoints.

Everything is defined in JSON. No new items, no crafting recipes. The mod ships a full set of built-in paths, but every path type, network, structure, and decorator can be overridden or extended through datapacks without writing a single line of code.

![paths](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/a3ebaaa8a9f74f1684efcfc8a8b118d4.png)

**14 path networks** across every major overworld biome:

*   **Plains Trail** - dirt path with grass, wildflowers and oak bushes alongside
*   **Dirt Road** - wide coarse dirt road through taiga and dark forest, lined with ferns and spruce bushes
*   **Cobblestone Road** - mossy cobblestone through forests and savannas with flower borders and signposts
*   **Brick Road** - polished granite and brick across the general overworld with roadside wildflowers
*   **Mountain Road** - narrow stone and andesite switchbacks through peaks and slopes
*   **Sandstone Highway** - wide, long cut sandstone road across deserts with lamps at each end
*   **Jungle Path** - mossy stone bricks winding under the canopy with shrines and leaf overgrowth
*   **Badlands Canyon Trail** - red sand and terracotta through mesas with weathered markers
*   **Snowy Trail** - packed snow and ice with gravel underfoot, cairns and spruce bushes along the route
*   **Swamp Boardwalk** - dark oak and spruce planks over mud and water with wooden posts
*   **Cherry Grove Path** - polished diorite and quartz lined with cherry planks, flowers and leaf litter
*   **Mushroom Trail** - mycelium and podzol through mushroom fields with mushroom scatter
*   **Windswept Trail** - rugged gravel and cobblestone across exposed windswept terrain
*   **Wildlands Trail** - a rare fallback dirt trail that can appear in any overworld biome

Each path is decorated with a combination of:

*   **15 NBT structures** - cairns, lamps, signposts, shrines, benches, and markers
*   **Scattered features** - flowers, ferns, dead bushes, mushrooms, tall grass
*   **Bush clusters** - spruce, oak, cherry, jungle and azalea leaf blobs alongside the path

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

---

**CREDITS**

*   FinnDog - author
*   Phantax - author

![BH promo banner](https://pub-24a4e0e7ea8544a5b6f73c3a23512589.r2.dev/images/0b15a121fb4947bcad4f6fc542f3b9bf.png)
