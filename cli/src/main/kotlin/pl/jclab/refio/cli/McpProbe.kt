package pl.jclab.refio.cli

import kotlinx.coroutines.delay
import pl.jclab.refio.core.context.mcp.MCPConnectionInfo
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.mcp.MCPServerStatus

/**
 * Connects the declared MCP servers, reports what they expose, and makes no LLM call.
 *
 * This exists to separate two failures that look identical from a turn's output: a server that
 * never connected, and a model that chose not to call its tool. Without it, diagnosing an MCP e2e
 * failure means reading the agent log and guessing.
 */
object McpProbe {

    /** Rendered report plus the verdict, so the caller owns printing and the exit code. */
    data class Report(val text: String, val allConnected: Boolean)

    private const val POLL_INTERVAL_MS = 200L

    /**
     * Waits until every declared server leaves a transitional state, or [timeoutMs] elapses.
     * A server stuck in CONNECTING is reported as such rather than silently treated as failed.
     */
    suspend fun awaitSettled(projectId: String, serverIds: List<String>, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val settled = serverIds.all {
                MCPManager.getServerStatus(projectId, it) !in TRANSITIONAL
            }
            if (settled) return
            delay(POLL_INTERVAL_MS)
        }
    }

    private val TRANSITIONAL = setOf(MCPServerStatus.DISCONNECTED, MCPServerStatus.CONNECTING)

    fun render(infos: List<MCPConnectionInfo>, toolNames: List<String>): Report {
        if (infos.isEmpty()) {
            return Report("No MCP servers declared or stored for this project.", allConnected = false)
        }

        val lines = mutableListOf<String>()
        infos.sortedBy { it.serverId }.forEach { info ->
            lines += "${statusMark(info.status)} ${info.serverId} (${info.displayName})"
            lines += "    status:    ${info.status}"
            lines += "    tools:     ${info.toolCount}"
            lines += "    resources: ${info.resourceCount}"
            info.lastError?.let { lines += "    error:     $it" }

            val registered = toolNames.filter { it.startsWith("mcp_${info.serverId}_") }.sorted()
            if (registered.isEmpty()) {
                // The usual cause is CONTEXT exposure, where tools are deliberately not registered.
                lines += "    registered: none - the agent cannot call this server's tools"
            } else {
                registered.forEach { lines += "    registered: $it" }
            }
        }

        val allConnected = infos.all { it.status == MCPServerStatus.CONNECTED }
        return Report(lines.joinToString("\n"), allConnected)
    }

    private fun statusMark(status: MCPServerStatus): String =
        if (status == MCPServerStatus.CONNECTED) "[OK]" else "[!!]"
}
