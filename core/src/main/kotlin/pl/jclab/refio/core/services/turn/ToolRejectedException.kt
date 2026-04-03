package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.ToolResultData

/**
 * Thrown when the user rejects a tool execution in ASK permission mode.
 * AgentTurnLoop catches this to break the loop and return to user prompt.
 */
class ToolRejectedException(
    val toolName: String,
    val reason: String?,
    val partialResults: List<ToolResultData> = emptyList()
) : Exception("User rejected tool: $toolName. Reason: ${reason ?: "none"}")
