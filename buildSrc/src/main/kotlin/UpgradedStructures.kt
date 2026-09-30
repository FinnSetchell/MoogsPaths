import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File
import java.security.MessageDigest

/**
 * Structure templates are written once, for 1.20.1, in src/main/resources/data/moogs_paths/structure.
 * Newer Minecraft versions would upgrade each one through the data fixer when it first loads, and the
 * data fixer keeps what it built for that in memory for the rest of the session. So every node from
 * 1.21 on ships copies already saved at its own data version, in src/upgraded-structures/<version>.
 *
 * The copies are made by `./gradlew upgradeStructures` (the StructureUpgrader tool, run with each
 * version's Minecraft by its Fabric node) and committed. `sources.sha256` next to them records the
 * source templates they were made from; a build fails if a source template changed since.
 */
const val STRUCTURE_SOURCE = "src/main/resources/data/moogs_paths/structure"
private const val STRUCTURE_PATH = "data/moogs_paths/structure"
private const val MANIFEST = "sources.sha256"

fun Project.upgradedStructuresDir(mcBuild: String): File = rootProject.file("src/upgraded-structures/$mcBuild")

/** Lists every source template with its hash, so a build can tell whether the copies are stale. */
fun writeStructureManifest(source: File, target: File) {
    File(target, MANIFEST).writeText(sourceHashes(source).entries.joinToString("") { (path, hash) -> "$hash  $path\n" })
}

/** Swaps the node's structure templates for its upgraded copies. Nodes before 1.21 ship the sources. */
fun ProcessResources.applyUpgradedStructures(project: Project, mcBuild: String, legacyData: Boolean) {
    if (legacyData) return
    val source = project.rootProject.file(STRUCTURE_SOURCE)
    val upgraded = project.upgradedStructuresDir(mcBuild)
    inputs.files(project.fileTree(upgraded)).withPropertyName("upgraded-structures")
    doFirst { checkUpgradedStructures(source, upgraded, mcBuild) }
    from(upgraded) {
        include("**/*.nbt")
        into(STRUCTURE_PATH)
    }
    filesMatching("$STRUCTURE_PATH/**") { if (!file.startsWith(upgraded)) exclude() }
}

private fun checkUpgradedStructures(source: File, upgraded: File, mcBuild: String) {
    val fix = "Run ./gradlew :$mcBuild-fabric:upgradeStructures (or ./gradlew upgradeStructures for every version) and commit the result; see CONTRIBUTING.md."
    val manifest = File(upgraded, MANIFEST)
    if (!manifest.isFile) throw GradleException("No upgraded structure templates for Minecraft $mcBuild in $upgraded. $fix")
    val recorded = manifest.readLines().filter { it.isNotBlank() }.associate { line ->
        val (hash, path) = line.split("  ", limit = 2)
        path to hash
    }
    val current = sourceHashes(source)
    val stale = (current.keys + recorded.keys).filter { current[it] != recorded[it] || !File(upgraded, it).isFile }
    if (stale.isNotEmpty()) {
        throw GradleException("The upgraded structure templates for Minecraft $mcBuild are out of date (${stale.sorted().joinToString()}). $fix")
    }
}

private fun sourceHashes(source: File): Map<String, String> =
    source.walkTopDown().filter { it.isFile && it.extension == "nbt" }
        .associate { it.relativeTo(source).invariantSeparatorsPath to sha256(it) }
        .toSortedMap()

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
