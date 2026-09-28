import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Serialises NeoForm's (NeoForge) Minecraft setup across nodes. Without this, building several
// NeoForge nodes at once starts a full Minecraft decompile per node in parallel and will bring the
// machine to its knees.
//
// Forge (ForgeGradle 7) doesn't need it: its Minecraft setup runs during project configuration,
// one node after another, so it is finished before any task runs in parallel.
interface MinecraftSetupMutex : BuildService<BuildServiceParameters.None>

val mutex = gradle.sharedServices.registerIfAbsent("createMinecraftArtifactsMutex", MinecraftSetupMutex::class.java) {
    maxParallelUsages.set(1)
}

tasks.named { it == "createMinecraftArtifacts" }.configureEach {
    usesService(mutex)
}
