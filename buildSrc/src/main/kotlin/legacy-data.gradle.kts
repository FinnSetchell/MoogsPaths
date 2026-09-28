import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// The datapack is written for 1.21. This rewrites it into the shape Minecraft before 1.21 reads:
// - structure templates live in structures/, which 1.21 renamed to structure/;
// - a uniform int provider keeps its bounds in a "value" object, which 1.20.5 inlined. Path types'
//   `length` is the only int provider the data uses.

plugins {
    java
}

tasks.named<ProcessResources>("processResources") {
    eachFile {
        path = path.replace(Regex("^data/([^/]+)/structure/"), "data/\$1/structures/")
    }
    doLast {
        destinationDir.resolve("data").listFiles()?.forEach { namespace ->
            namespace.resolve("moogs_paths/path_type").listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
                @Suppress("UNCHECKED_CAST")
                val pathType = JsonSlurper().parse(file) as MutableMap<String, Any?>
                pathType["length"] = boxIntProvider(pathType["length"], "${namespace.name}:${file.nameWithoutExtension}")
                file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(pathType)))
            }
        }
    }
}

fun boxIntProvider(provider: Any?, pathType: String): Any? {
    // A bare number, or a constant, reads the same on every version.
    if (provider !is Map<*, *>) return provider
    return when (provider["type"].toString().removePrefix("minecraft:")) {
        "constant" -> provider
        "uniform" -> linkedMapOf("type" to provider["type"], "value" to provider.filterKeys { it != "type" })
        else -> throw GradleException("Path type $pathType: no pre-1.20.5 form known for a ${provider["type"]} length")
    }
}
