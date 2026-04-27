package pl.jclab.refio.core.llm

import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("NativeToolsResolver")

enum class NativeToolsMode { AUTO, ALWAYS, NEVER }

fun parseNativeToolsMode(raw: String?): NativeToolsMode = when (raw?.trim()?.lowercase()) {
    "always" -> NativeToolsMode.ALWAYS
    "never" -> NativeToolsMode.NEVER
    else -> NativeToolsMode.AUTO
}

/**
 * Decide whether a given request should use native function-calling API.
 *
 * Precedence:
 * 1. modelId in fallbackFlags → false (session-scoped failure cache)
 * 2. mode == NEVER → false
 * 3. mode == ALWAYS → true
 * 4. mode == AUTO → ModelDefinition.supportsFunctionCalling (false if no definition)
 */
fun shouldUseNativeTools(
    mode: NativeToolsMode,
    definition: ModelDefinition?,
    modelId: String,
    fallbackFlags: Set<String> = emptySet(),
): Boolean {
    if (modelId in fallbackFlags) {
        logger.debug { "[NATIVE_TOOLS] $modelId in session fallback set, forcing JSON path" }
        return false
    }
    return when (mode) {
        NativeToolsMode.NEVER -> false
        NativeToolsMode.ALWAYS -> true
        NativeToolsMode.AUTO -> definition?.supportsFunctionCalling == true
    }
}
