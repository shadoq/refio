package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.ToolResultData

/** Thrown when user rejects a tool execution in ASK mode. */
class ToolRejectedException(
    val toolName: String,
    val toolCallId: String,
    val reason: String?,
    val partialResults: List<ToolResultData> = emptyList()
) : Exception("User rejected tool: $toolName. Reason: ${reason ?: "none"}")
