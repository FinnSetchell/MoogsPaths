import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * Per-node resource differences, for data that has to differ on some versions (a feature Minecraft
 * removed, a registry it renamed).
 *
 * Each named overlay under src/overlays/ is added to the jar; an overlay file replaces the shared
 * resource at the same path. Overlays are named after the first version that needs them.
 * `excludes` are comma-separated patterns for shared resources the node must not ship at all.
 */
fun ProcessResources.applyNodeResources(project: Project, overlays: String, excludes: String) {
    for (name in overlays.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val dir = project.rootProject.file("src/overlays/$name")
        if (!dir.isDirectory) throw GradleException("No overlay directory $dir")
        val replaced = project.fileTree(dir).files.map { it.relativeTo(dir).invariantSeparatorsPath }
        inputs.dir(dir).withPropertyName("overlay-$name")
        from(dir)
        filesMatching(replaced) { if (!file.startsWith(dir)) exclude() }
    }
    val patterns = excludes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    inputs.property("resource-excludes", patterns)
    exclude(patterns)
}
