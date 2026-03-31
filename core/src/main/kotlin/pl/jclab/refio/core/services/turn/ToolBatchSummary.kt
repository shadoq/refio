package pl.jclab.refio.core.services.turn

/**
 * Generates short human-readable labels for batches of tool calls.
 * Used in chat UI to replace verbose tool results with concise summaries.
 */
object ToolBatchSummary {

    data class BatchSummary(
        val label: String,
        val toolCount: Int,
        val tools: List<ToolCallSummary>
    )

    data class ToolCallSummary(
        val toolName: String,
        val shortDescription: String,
        val success: Boolean
    )

    /**
     * Generates a batch summary from tool calls and their results.
     *
     * Example output: "Read 2 files, searched for 'auth', edited LoginService.kt"
     */
    fun summarize(toolCalls: List<ToolCallWithResult>): BatchSummary {
        val summaries = toolCalls.map { call ->
            ToolCallSummary(
                toolName = call.toolName,
                shortDescription = describeToolCall(call),
                success = call.success
            )
        }
        return BatchSummary(
            label = buildLabel(summaries),
            toolCount = summaries.size,
            tools = summaries
        )
    }

    private fun describeToolCall(call: ToolCallWithResult): String {
        return when (call.toolName) {
            "read_file" -> "Read ${extractFileName(call.params["file_path"])}"
            "grep_search" -> "Searched for '${call.params["query"]?.toString()?.take(30)}'"
            "file_search" -> "Found files matching '${call.params["query"]?.toString()?.take(30)}'"
            "code_editing" -> "Edited ${extractFileName(call.params["file_path"])}"
            "create_new_file" -> "Created ${extractFileName(call.params["file_path"])}"
            "run_terminal_command" -> "Ran: ${call.params["command"]?.toString()?.take(40)}"
            "read_directory" -> "Listed ${extractFileName(call.params["directory_path"])}"
            else -> call.toolName
        }
    }

    private fun buildLabel(summaries: List<ToolCallSummary>): String {
        if (summaries.isEmpty()) return "No tools executed"
        if (summaries.size == 1) return summaries[0].shortDescription

        var reads = 0; var searches = 0; var edits = 0; var commands = 0
        for (s in summaries) {
            when (s.toolName) {
                "read_file", "read_directory" -> reads++
                "grep_search", "file_search" -> searches++
                "code_editing", "create_new_file" -> edits++
                "run_terminal_command" -> commands++
            }
        }

        val parts = mutableListOf<String>()
        if (reads > 0) parts.add("Read $reads file${if (reads > 1) "s" else ""}")
        if (searches > 0) parts.add("$searches search${if (searches > 1) "es" else ""}")
        if (edits > 0) parts.add("Edited $edits file${if (edits > 1) "s" else ""}")
        if (commands > 0) parts.add("Ran $commands command${if (commands > 1) "s" else ""}")

        if (parts.isEmpty()) {
            parts.add("${summaries.size} tool calls")
        }

        return parts.joinToString(", ")
    }

    private fun extractFileName(path: Any?): String {
        val str = path?.toString() ?: return "file"
        return str.substringAfterLast("/").substringAfterLast("\\")
    }
}

/**
 * Input for batch summary generation.
 */
data class ToolCallWithResult(
    val toolName: String,
    val params: Map<String, Any>,
    val success: Boolean,
    val resultPreview: String? = null
)
