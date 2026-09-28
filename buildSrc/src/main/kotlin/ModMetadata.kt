import org.gradle.api.Project

// gradle.properties values every loader's metadata templates (fabric.mod.json, mods.toml,
// neoforge.mods.toml, pack.mcmeta, the lang file) expand. Node-specific values are added per loader.
private val SHARED_KEYS = listOf(
    "mod_id", "mod_name", "mod_author", "license", "credits", "description",
    "mod_homepage", "mod_issues", "mod_discord", "mod_kofi", "mod_curseforge", "mod_modrinth", "mod_releases",
)

fun Project.sharedMetadata(): Map<String, String> =
    SHARED_KEYS.associateWith { property(it).toString() } + ("version" to version.toString())
