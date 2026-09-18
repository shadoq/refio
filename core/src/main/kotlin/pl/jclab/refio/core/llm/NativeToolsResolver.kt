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
 * 4. mode == AUTO → ModelDefinition.supportsFunctionCalling; with no definition, what the provider
 *    said about the model; with neither, false.
 *
 * A hand-written definition outranks the provider's own answer on purpose: it is usually written
 * precisely to overrule a model that advertises more than it can deliver.
 *
 * @param providerSupportsTools what the serving provider reported, or null when it was not asked
 *        or could not answer. Only consulted when there is no definition.
 */
fun shouldUseNativeTools(
    mode: NativeToolsMode,
    definition: ModelDefinition?,
    modelId: String,
    fallbackFlags: Set<String> = emptySet(),
    providerSupportsTools: Boolean? = null,
): Boolean {
    if (modelId in fallbackFlags) {
        logger.debug { "[NATIVE_TOOLS] $modelId in session fallback set, forcing JSON path" }
        return false
    }
    return when (mode) {
        NativeToolsMode.NEVER -> false
        NativeToolsMode.ALWAYS -> true
        NativeToolsMode.AUTO ->
            definition?.supportsFunctionCalling ?: (providerSupportsTools == true)
    }
}

/**
 * Human-readable reason for the [shouldUseNativeTools] verdict, so a run's native-vs-JSON path is
 * explainable from the log alone — no need to re-derive the model flag or config by hand. Mirrors
 * the exact precedence of [shouldUseNativeTools]; the leading token is the resulting path
 * (`NATIVE` / `JSON`).
 */
fun nativeToolsDecisionReason(
    mode: NativeToolsMode,
    definition: ModelDefinition?,
    modelId: String,
    fallbackFlags: Set<String> = emptySet(),
    providerSupportsTools: Boolean? = null,
): String {
    if (modelId in fallbackFlags) {
        return "JSON: '$modelId' is in the session native-tools fallback set (a prior native call failed)"
    }
    return when (mode) {
        NativeToolsMode.NEVER -> "JSON: tools.native_tools=never"
        NativeToolsMode.ALWAYS -> "NATIVE: tools.native_tools=always (forced regardless of model flag)"
        NativeToolsMode.AUTO -> when {
            // The three no-definition outcomes have to read differently, or the log cannot say
            // whether the provider confirmed tools, denied them, or was never reachable.
            definition == null && providerSupportsTools == true ->
                "NATIVE: tools.native_tools=auto, no ModelDefinition for '$modelId', " +
                    "provider reports tool support"
            definition == null && providerSupportsTools == false ->
                "JSON: tools.native_tools=auto, no ModelDefinition for '$modelId', " +
                    "provider reports no tool support"
            definition == null ->
                "JSON: tools.native_tools=auto and no ModelDefinition for '$modelId' " +
                    "(provider could not be asked about tool support)"
            definition.supportsFunctionCalling ->
                "NATIVE: tools.native_tools=auto and ModelDefinition.supportsFunctionCalling=true"
            else ->
                "JSON: tools.native_tools=auto and ModelDefinition.supportsFunctionCalling=false for '$modelId'"
        }
    }
}
