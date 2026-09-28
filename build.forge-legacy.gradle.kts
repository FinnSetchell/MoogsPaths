plugins {
    // Forge before 1.20.5 runs on SRG-named Minecraft, so the jar has to be reobfuscated out of the
    // official names the source is written in. ForgeGradle 7 cannot reobfuscate; ModDevGradle's legacy
    // Forge plugin can, and wires it into the jar. Everything else matches build.forge.gradle.kts.
    id("net.neoforged.moddev.legacyforge") version "2.0.147"
    id("minecraft-mutex")
}

fun prop(key: String): String = sc.properties.get<String>(key)

if (prop("mod.legacy_data").toBoolean()) apply(plugin = "legacy-data")

val modId = property("mod_id").toString()
val modName = property("mod_name").toString()
val modAuthor = property("mod_author").toString()
val requiredJava: JavaVersion = JavaVersion.toVersion(prop("mod.java"))
// The Minecraft version this node compiles against.
val mcBuild: String = prop("mod.mc_build")

version = property("mod_version").toString()
base.archivesName = "${property("archives_base_name")}-forge-$mcBuild"

sourceSets.main {
    java.exclude("**/fabric/**", "**/neoforge/**")
    resources.srcDir(rootProject.file("src/forge/resources"))
}

legacyForge {
    version = "$mcBuild-${prop("deps.forge")}"

    mods {
        register(modId) { sourceSet(sourceSets.main.get()) }
    }

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
            "forge_loader_range" to prop("deps.forge_loader_range"),
            "forge_range" to prop("deps.forge_range"),
            "pack_formats" to prop("mod.pack_formats"),
        )
        props.forEach { (k, v) -> inputs.property(k, v) }
        filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta", "assets/*/lang/en_us.json")) { expand(props) }
        applyNodeResources(project, prop("mod.overlays"), prop("mod.resource_excludes"))
    }

    jar {
        from(rootProject.file("LICENSE")) { rename { "${it}_${modName}" } }
        manifest {
            attributes(
                "Specification-Title" to modName,
                "Specification-Vendor" to modAuthor,
                "Specification-Version" to version,
                "Implementation-Title" to "forge",
                "Implementation-Version" to version,
                "Implementation-Vendor" to modAuthor,
                "Built-On-Minecraft" to mcBuild,
            )
        }
    }

    named("createMinecraftArtifacts") { dependsOn("stonecutterGenerate") }
    withType<JavaCompile>().configureEach { dependsOn("stonecutterGenerate") }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/{mod version}/"
        // The reobfuscated jar, not `jar`: that one keeps official names and dies on a real server.
        val reobf = named("reobfJar")
        dependsOn(reobf)
        from(reobf.map { it.outputs.files })
        into(rootProject.layout.buildDirectory.dir("libs/$version"))
    }
}
