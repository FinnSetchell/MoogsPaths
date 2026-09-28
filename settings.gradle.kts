pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    // Picks the Loom variant per node: remapping Loom for obfuscated Minecraft, plain Loom from 26.1.
    id("dev.kikugie.loom-back-compat") version "0.4"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        /**
         * One node per loader for a Minecraft version, as `versions/{mc}-{loader}`, built by
         * `build.{loader}.gradle.kts`. Pass the version separately: as a single argument SemVer would
         * read `fabric` as a pre-release tag and order `1.21.1-fabric` below `1.21.1`.
         */
        fun match(mc: String, vararg loaders: String) {
            for (loader in loaders) {
                version("$mc-$loader", mc).buildscript("build.$loader.gradle.kts")
            }
        }

        // Forge before 1.20.5 needs a reobfuscated jar, which build.forge-legacy.gradle.kts makes.
        fun legacyForge(mc: String) {
            version("$mc-forge", mc).buildscript("build.forge-legacy.gradle.kts")
        }

        match("1.20", "fabric")
        legacyForge("1.20")
        match("1.20.1", "fabric")
        legacyForge("1.20.1")
        match("1.21.1", "fabric", "forge", "neoforge")
        match("1.21.11", "fabric", "neoforge")
        match("26.1.2", "fabric", "neoforge")
        match("26.2", "fabric", "neoforge")
        match("26.3", "fabric", "neoforge")

        vcsVersion = "1.21.1-fabric"
    }
}

rootProject.name = "MoogsPaths"
