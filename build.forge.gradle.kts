plugins {
    id("net.minecraftforge.gradle") version "[7.0.29,8.0)"
    id("minecraft-mutex")
}

fun prop(key: String): String = sc.properties.get<String>(key)

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

minecraft {
    mappings("official", mcBuild)

    runs {
        // Per-node game directory, so worlds are never opened by a different Minecraft version.
        configureEach {
            workingDir.set(rootProject.file("run/${project.name}"))
            // Dev runs keep the path timings; released jars leave them off.
            systemProperty("moogs_paths.debug_timer", "true")
        }
        register("client")
        register("server") { args("--nogui") }
    }
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$mcBuild-${prop("deps.forge")}"))
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

    // Forge's Minecraft setup must not run before Stonecutter has written the processed sources.
    withType<JavaCompile>().configureEach { dependsOn("stonecutterGenerate") }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/{mod version}/"
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$version"))
    }
}
