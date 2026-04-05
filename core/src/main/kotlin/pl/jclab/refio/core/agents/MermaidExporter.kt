package pl.jclab.refio.core.agents

import pl.jclab.refio.core.agents.events.AgentEvent

/**
 * Exports multi-agent execution events as a Mermaid sequence diagram.
 */
object MermaidExporter {

    fun exportSequenceDiagram(events: List<AgentEvent>): String {
        // Build agentId → name map once (O(N) instead of O(N²))
        val nameById = events.filterIsInstance<AgentEvent.AgentStarted>()
            .associate { it.sourceAgentId to it.agentName }

        val sb = StringBuilder()
        sb.appendLine("sequenceDiagram")
        sb.appendLine("    participant U as User")
        sb.appendLine("    participant O as Orchestrator")

        for (agent in nameById.values.toSet()) {
            sb.appendLine("    participant ${safeName(agent)} as $agent")
        }
        sb.appendLine()

        for (event in events.sortedBy { it.timestamp }) {
            when (event) {
                is AgentEvent.AgentStarted -> {
                    val safe = safeName(event.agentName)
                    val task = sanitize(event.task, 40)
                    sb.appendLine("    O->>$safe: $task")
                    sb.appendLine("    activate $safe")
                }
                is AgentEvent.AgentCompleted -> {
                    val agent = resolveParticipant(nameById, event.sourceAgentId, "Agent")
                    val summary = sanitize(event.summary, 30)
                    sb.appendLine("    $agent-->>O: $summary (${event.durationMs}ms)")
                    sb.appendLine("    deactivate $agent")
                }
                is AgentEvent.AgentFailed -> {
                    val agent = resolveParticipant(nameById, event.sourceAgentId, "Agent")
                    val err = sanitize(event.error, 30)
                    sb.appendLine("    $agent--xO: FAILED: $err")
                    sb.appendLine("    deactivate $agent")
                }
                is AgentEvent.DataRequest -> {
                    val from = resolveParticipant(nameById, event.sourceAgentId, "Agent")
                    val to = if (event.targetAgentId == null) "O"
                        else resolveParticipant(nameById, event.targetAgentId, "O")
                    val msg = sanitize(event.query, 40)
                    sb.appendLine("    $from->>$to: $msg")
                }
                is AgentEvent.DataResponse -> {
                    val from = resolveParticipant(nameById, event.sourceAgentId, "O")
                    val to = resolveParticipant(nameById, event.targetAgentId, "Agent")
                    val resp = sanitize(event.response, 30)
                    sb.appendLine("    $from-->>$to: $resp")
                }
                else -> {}
            }
        }

        return sb.toString()
    }

    private fun resolveParticipant(nameById: Map<String, String>, agentId: String?, fallback: String): String =
        nameById[agentId]?.let { safeName(it) } ?: fallback

    private fun safeName(name: String): String = name.replace("-", "_")

    private fun sanitize(text: String, maxLen: Int): String = text.take(maxLen).replace("\"", "'")
}
