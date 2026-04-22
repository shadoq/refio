package pl.jclab.refio.core.services.turn

/**
 * Override text for {{tool_descriptions}} when native function-calling is active.
 *
 * The model receives actual tool schemas via the API `tools` parameter instead of
 * the verbose text list. This replaces only the <available_tools> section in the
 * system-plan.md / system-agent.md templates; all other guidance stays intact.
 */
fun nativeToolsDescriptionOverride(): String =
    "Tools are provided via the native function-calling API — their schemas are attached " +
        "to this request. Invoke them via the standard tool_use mechanism (native tool_calls). " +
        "Do NOT wrap tool calls inside a JSON envelope in your text response; " +
        "use ONLY the native tool_calls channel."

internal fun buildIterationInfoString(current: Int, max: Int, @Suppress("UNUSED_PARAMETER") writeToolsExecutedInTurn: Int = 0): String {
    val remaining = max - current

    val warning = when {
        remaining <= 3 -> "⚠️ CRITICAL: Only $remaining iterations left! Prioritize essential actions and prepare to conclude."
        remaining <= 7 -> "⚠️ WARNING: $remaining iterations remaining. Plan efficiently and focus on core objectives."
        remaining <= 12 -> "Note: $remaining iterations remaining. Consider pacing your tool usage."
        else -> ""
    }

    return if (warning.isNotEmpty()) {
        """
<iteration_status>
Current iteration: $current / $max
${warning}
</iteration_status>
        """.trimIndent()
    } else {
        ""
    }
}
