plugins {
    // Applies the Loom variant matching this node's Minecraft version.
    id("dev.kikugie.loom-back-compat")
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
base.archivesName = "${property("archives_base_name")}-fabric-$mcBuild"

sourceSets.main {
    // Loader code sits in fabric/forge/neoforge packages; each loader compiles only its own.
    java.exclude("**/forge/**", "**/neoforge/**")
    resources.srcDir(rootProject.file("src/fabric/resources"))
}

repositories {
    maven("https://maven.shedaniel.me") { name = "Shedaniel" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcBuild")
    // No-op on the unobfuscated versions; applies Mojang mappings on the obfuscated ones.
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")

    // The optional config screen: compiled against, never bundled or required at runtime.
    modCompileOnly("me.shedaniel.cloth:cloth-config-fabric:${prop("deps.cloth_config")}") { isTransitive = false }
    modCompileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}") { isTransitive = false }
}

loom {
    runs {
        // Per-node game directory, so worlds are never opened by a different Minecraft version.
        // Dev runs keep the path timings; released jars leave them off.
        named("client") {
            client()
            configName = "Fabric Client"
            vmArg("-Dmoogs_paths.debug_timer=true")
            ideConfigGenerated(true)
            runDir("../../run/${project.name}")
        }
        named("server") {
            server()
            configName = "Fabric Server"
            vmArg("-Dmoogs_paths.debug_timer=true")
            ideConfigGenerated(true)
            runDir("../../run/${project.name}")
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
            "fabric_loader_dep" to prop("mod.fabric_loader_dep"),
            "java_version" to requiredJava.majorVersion,
            "pack_formats" to prop("mod.pack_formats"),
        )
        props.forEach { (k, v) -> inputs.property(k, v) }
        filesMatching(listOf("fabric.mod.json", "pack.mcmeta", "assets/*/lang/en_us.json")) { expand(props) }
        // Only 1.20 has a mixin (its registry route); everywhere else the config stays out of the jar.
        val mixins = prop("mod.fabric_mixins").toBoolean()
        inputs.property("fabric_mixins", mixins)
        if (mixins) {
            filesMatching("fabric.mod.json") {
                filter { line -> line.replace("\"environment\": \"*\",", "\"environment\": \"*\",\n    \"mixins\": [\"$modId.fabric.mixins.json\"],") }
            }
        } else {
            exclude("$modId.fabric.mixins.json")
        }
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
                "Implementation-Title" to "fabric",
                "Implementation-Version" to version,
                "Implementation-Vendor" to modAuthor,
                "Built-On-Minecraft" to mcBuild,
            )
        }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/{mod version}/"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$version"))
    }
}

// Re-saves the structure templates at this node's data version with this node's Minecraft, into
// src/upgraded-structures/<version>, which every loader's jar for the version ships (see UpgradedStructures.kt).
// Only the Fabric nodes run it: the data version depends on the Minecraft version, not the loader.
if (!prop("mod.legacy_data").toBoolean()) {
    // Minecraft and its libraries, without the mod itself (whose resources hold the templates this makes).
    val structureTools = sourceSets.create("structureTools") {
        java.srcDir(rootProject.file("src/tools/java"))
        compileClasspath += configurations.compileClasspath.get()
        runtimeClasspath += output + configurations.runtimeClasspath.get()
    }
    tasks.register<JavaExec>("upgradeStructures") {
        group = "moogs"
        description = "Re-saves the structure templates at Minecraft $mcBuild's data version into src/upgraded-structures/$mcBuild"
        val source = rootProject.file(STRUCTURE_SOURCE)
        val target = upgradedStructuresDir(mcBuild)
        classpath = structureTools.runtimeClasspath
        mainClass = "com.finndog.moogs_paths.tools.StructureUpgrader"
        args(source.absolutePath, target.absolutePath)
        maxHeapSize = "1G"
        workingDir = layout.buildDirectory.dir("upgradeStructures").get().asFile
        doFirst {
            target.deleteRecursively()
            workingDir.mkdirs()
        }
        doLast { writeStructureManifest(source, target) }
    }
}

// IntelliJ 2026.2 turns the dots in a node's name into underscores when it names modules
// (Root.1_21_1-fabric.main), but Loom writes its run configurations for Root.1.21.1-fabric.main,
// which then don't run. Once the IDE has imported the project, point them at the name it actually uses.
tasks.named("ideaSyncTask") {
    val ideaDir = rootProject.file(".idea")
    val dotted = "${rootProject.name}.${project.name}."
    val underscored = "${rootProject.name}.${project.name.replace('.', '_')}."
    doLast {
        val modules = File(ideaDir, "modules.xml")
        if (dotted == underscored || !modules.exists() || underscored !in modules.readText()) return@doLast
        File(ideaDir, "runConfigurations").listFiles { f -> f.extension == "xml" }?.forEach { f ->
            val text = f.readText()
            val fixed = text.replace("<module name=\"$dotted", "<module name=\"$underscored")
            if (fixed != text) f.writeText(fixed)
        }
    }
}
