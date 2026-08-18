package pl.jclab.refio.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No model may be declared twice in one registry map.
 *
 * `mapOf` keeps the last entry for a repeated key and drops the earlier one without a word, so a
 * duplicate leaves a definition that looks live in the source and does nothing. Two had crept in:
 * `o3-pro` (identical twins) and `nemotron-cascade-2:30b`, where the shadowed copy declared a 128K
 * window against the 256K the server actually reports - anyone correcting that number in the wrong
 * copy would have seen no effect at all.
 *
 * A duplicate cannot be caught by reading the maps at runtime (the collision already happened), so
 * the check reads the source file instead.
 */
class ModelRegistryUniquenessTest {

    @Test
    fun `every model id appears once per registry map`() {
        val source = ModelDefinitionsSource.read()
        val mapStart = Regex("""val ([A-Z_]+_MODELS) = mapOf\(""")
        val entry = Regex("""^ {8}"([^"]+)" to ModelDefinition\(""")

        var currentMap: String? = null
        val seen = mutableMapOf<String, MutableList<String>>()
        val duplicates = mutableListOf<String>()

        for (line in source.lines()) {
            mapStart.find(line)?.let { currentMap = it.groupValues[1] }
            val id = entry.find(line)?.groupValues?.get(1) ?: continue
            val map = currentMap ?: continue
            val ids = seen.getOrPut(map) { mutableListOf() }
            if (id in ids) {
                duplicates += "$map declares '$id' more than once"
            }
            ids += id
        }

        assertEquals(emptyList(), duplicates, "a repeated key silently shadows the earlier definition")
    }
}

/** Reads the registry source, whose text is the only place a duplicate key is still visible. */
private object ModelDefinitionsSource {
    fun read(): String {
        val candidates = listOf(
            "core/src/main/kotlin/pl/jclab/refio/core/llm/ModelDefinitions.kt",
            "src/main/kotlin/pl/jclab/refio/core/llm/ModelDefinitions.kt",
        )
        val file = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: error("ModelDefinitions.kt not found from " + java.io.File(".").absolutePath)
        return file.readText()
    }
}
