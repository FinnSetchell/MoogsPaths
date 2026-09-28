import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * Adds each named overlay under src/overlays/ to the jar. An overlay file replaces the shared
 * resource at the same path, for data that has to differ on some versions (a feature Minecraft
 * removed, say). Overlays are named after the first version that needs them.
 */
fun ProcessResources.applyOverlays(project: Project, names: String) {
    for (name in names.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val dir = project.rootProject.file("src/overlays/$name")
        if (!dir.isDirectory) throw org.gradle.api.GradleException("No overlay directory $dir")
        val replaced = project.fileTree(dir).files.map { it.relativeTo(dir).invariantSeparatorsPath }
        inputs.dir(dir).withPropertyName("overlay-$name")
        from(dir)
        filesMatching(replaced) { if (!file.startsWith(dir)) exclude() }
    }
}
