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
    override val description = "Read output from (or stop) a background process started with run_process_background. " +
        "Call repeatedly with action=\"read\" to get new output lines; returns output so far and whether the " +
        "process is still running. Use action=\"stop\" to kill it once you are done with it (a dev server, a " +
        "watcher) - a process left running keeps holding its port until the app exits."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint =
        "Read output from, or stop, a background process (pairs with run_process_background)."

    override fun validateParams(params: Map<String, Any>) {
        val id = params["process_id"] as? String
        if (id.isNullOrBlank()) throw IllegalArgumentException("Parameter 'process_id' is required")
        val action = params["action"] as? String
        if (action != null && action.lowercase() !in SUPPORTED_ACTIONS) {
            throw IllegalArgumentException(
                "Unknown action '$action' - expected one of ${SUPPORTED_ACTIONS.joinToString("/")}"
            )
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val processId = params["process_id"] as? String
            ?: return ToolResult.error("Missing 'process_id'")
        val action = (params["action"] as? String)?.lowercase() ?: ACTION_READ
        if (action !in SUPPORTED_ACTIONS) {
            return ToolResult.error(
                "Unknown action '$action' - expected one of ${SUPPORTED_ACTIONS.joinToString("/")}"
            )
        }
        if (action == ACTION_STOP) {
            return stop(processId)
        }
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

    /**
     * Kills the process and everything it spawned. Reports the id as unknown rather than as a
     * failure when there is nothing left to stop - a finished process is the outcome the caller
     * wanted anyway.
     */
    private fun stop(processId: String): ToolResult {
        val managed = processManager.get(processId)
            ?: return ToolResult.error("Process not found: $processId (may have already finished)")

        processManager.stop(processId)

        return ToolResult(
            success = true,
            output = "[Process $processId: STOPPED] ${managed.command}",
            metadata = mapOf(
                "process_id" to processId,
                "is_running" to false,
                "action" to ACTION_STOP
            )
        )
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "process_id" to mapOf("type" to "string", "description" to "Process ID from run_process_background"),
            "action" to mapOf(
                "type" to "string",
                "enum" to SUPPORTED_ACTIONS.toList(),
                "description" to "\"$ACTION_READ\" (default) returns new output, \"$ACTION_STOP\" kills the process"
            ),
            "max_lines" to mapOf("type" to "integer", "description" to "Max output lines to return (default: 200)")
        ),
        "required" to listOf("process_id")
    )

    companion object {
        const val ACTION_READ = "read"
        const val ACTION_STOP = "stop"
        val SUPPORTED_ACTIONS = setOf(ACTION_READ, ACTION_STOP)
    }
}
