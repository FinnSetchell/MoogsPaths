plugins {
    id("net.neoforged.moddev") version "2.0.147"
    id("minecraft-mutex")
}

fun prop(key: String): String = sc.properties.get<String>(key)

val modId = property("mod_id").toString()
val modName = property("mod_name").toString()
val modAuthor = property("mod_author").toString()
val requiredJava: JavaVersion = JavaVersion.toVersion(prop("mod.java"))
// The Minecraft version this node compiles against.
val mcBuild: String = prop("mod.mc_build")

version = property("mod_version").toString()
base.archivesName = "${property("archives_base_name")}-neoforge-$mcBuild"

sourceSets.main {
    java.exclude("**/fabric/**", "**/forge/**")
    resources.srcDir(rootProject.file("src/neoforge/resources"))
}

neoForge {
    version = prop("deps.neoforge")

    runs {
        // Per-node game directory, so worlds are never opened by a different Minecraft version.
        // Dev runs keep the path timings; released jars leave them off.
        register("client") {
            client()
            gameDirectory = rootProject.file("run/${project.name}")
            systemProperty("moogs_paths.debug_timer", "true")
        }
        register("server") {
            server()
            gameDirectory = rootProject.file("run/${project.name}")
            systemProperty("moogs_paths.debug_timer", "true")
        }
    }

    mods {
        register(modId) { sourceSet(sourceSets.main.get()) }
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain { languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion) }
}

tasks {
    processResources {
        val props = sharedMetadata() + mapOf(
            "mc_compat" to prop("mod.mc_compat"),
            "neoforge_loader_range" to prop("deps.neoforge_loader_range"),
            "neoforge_range" to prop("deps.neoforge_range"),
            "neo_icon_key" to prop("mod.neo_icon_key"),
            "pack_formats" to prop("mod.pack_formats"),
        )
        props.forEach { (k, v) -> inputs.property(k, v) }
        filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta", "assets/*/lang/en_us.json")) { expand(props) }
        applyNodeResources(project, prop("mod.overlays"), prop("mod.resource_excludes"))
        applyUpgradedStructures(project, mcBuild, prop("mod.legacy_data").toBoolean())
    }

    jar {
        from(rootProject.file("LICENSE")) { rename { "${it}_${modName}" } }
        manifest {
            attributes(
                "Specification-Title" to modName,
                "Specification-Vendor" to modAuthor,
                "Specification-Version" to version,
                "Implementation-Title" to "neoforge",
                "Implementation-Version" to version,
                "Implementation-Vendor" to modAuthor,
                "Built-On-Minecraft" to mcBuild,
            )
        }
    }

    // NeoForge's Minecraft artifacts must not be created before Stonecutter has written the sources.
    named("createMinecraftArtifacts") { dependsOn("stonecutterGenerate") }
    withType<JavaCompile>().configureEach { dependsOn("stonecutterGenerate") }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/{mod version}/"
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$version"))
    }
}
