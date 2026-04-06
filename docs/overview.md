# MoogsPaths — Project Overview

## Vision

MoogsPaths is a data-driven world generation mod that procedurally spawns path networks
across Minecraft terrain. Paths are purely decorative/atmospheric — they exist to make the
world feel lived-in, traversed, and organic. They are not required to connect anything
specific; they wander, branch, and fade naturally.

Everything is defined via JSON data files. Nothing is hardcoded. Other mods can add their
own path types, structure sets, and feature decorators by dropping files into the correct
data directory — no dependency on MoogsPaths' internals required.

## Core Pillars

1. **Data-driven first** — all path types, structure sets, and decorators are JSON.
   The mod itself is an engine. Content is data.
2. **Composable** — path networks, structure sets, and feature decorators are defined
   separately and linked by reference. Mix and match freely.
3. **Scalable** — supports both small local paths (a few hundred blocks) and large
   regional route networks (thousands of blocks, branching).
4. **Multiloader** — built on Architectury (1.20/1.20.1). Port to 1.21/1.21.1 comes later.
5. **Non-destructive** — paths follow terrain; they do not terraform aggressively.
   Structures place only where they fit.

## Target Versions

| Phase | Version      | Loaders                              |
|-------|-------------|--------------------------------------|
| 1     | 1.20/1.20.1 | Forge + Fabric (via Architectury)    |
| 2     | 1.21/1.21.1 | Forge, NeoForge, Fabric (via Architectury) |
