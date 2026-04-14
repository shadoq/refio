package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.services.ProcessManager
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

class MonitorProcessTool(
    private val processManager: ProcessManager
) : Tool {
    override val name = "monitor_process"
    override val description = "Read output from a background process started with run_process_background. " +
        "Call repeatedly to get new output lines. Returns output so far and whether process is still running. " +
        "If process has finished, returns all remaining output."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint = "Read new output from a background process (pairs with run_process_background)."

    override fun validateParams(params: Map<String, Any>) {
        val id = params["process_id"] as? String
        if (id.isNullOrBlank()) throw IllegalArgumentException("Parameter 'process_id' is required")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val processId = params["process_id"] as? String
            ?: return ToolResult.error("Missing 'process_id'")
        val maxLines = ((params["max_lines"] as? Number)?.toInt() ?: 200).coerceIn(1, 1000)

        val (lines, isRunning) = processManager.readOutput(processId, maxLines)

        if (processManager.get(processId) == null && lines.isEmpty()) {
            return ToolResult.error("Process not found: $processId (may have already finished)")
        }

        val statusLine = if (isRunning) "[Process $processId: RUNNING]" else "[Process $processId: FINISHED]"
        val output = if (lines.isEmpty()) {
            "$statusLine\n(no new output)"
        } else {
            "$statusLine\n${lines.joinToString("\n")}"
        }

        return ToolResult(
            success = true,
            output = output,
            metadata = mapOf(
                "process_id" to processId,
                "is_running" to isRunning,
                "lines_read" to lines.size
            )
        )
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "process_id" to mapOf("type" to "string", "description" to "Process ID from run_process_background"),
            "max_lines" to mapOf("type" to "integer", "description" to "Max output lines to return (default: 200)")
        ),
        "required" to listOf("process_id")
    )
}
