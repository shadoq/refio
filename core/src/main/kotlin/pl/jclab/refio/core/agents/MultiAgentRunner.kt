package pl.jclab.refio.core.agents

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
import pl.jclab.refio.core.agents.events.AgentMessageInbox
import pl.jclab.refio.core.services.logging.coreLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = coreLogger("MultiAgentRunner")

/**
 * Orchestrates parallel execution of multiple agents.
 *
 * Each agent is an isolated AgentTurnLoop instance with its own Task in DB.
 * Dependencies are respected (agent B waits for agent A to complete).
 * Events are emitted to AgentEventBus for GUI visualization.
 *
 * Usage:
 * ```
 * val runner = MultiAgentRunner(eventBus)
 * val results = runner.run("session-1", listOf(
 *     AgentSpec("analyst", task = "Analyze requirements"),
 *     AgentSpec("coder", task = "Implement", dependsOn = listOf("analyst"))
 * )) { spec, agentId -> executeAgent(spec, agentId) }
 * ```
 */
class MultiAgentRunner(
    private val eventBus: AgentEventBus,
    private val inboxRegistry: AgentInboxRegistry = AgentInboxRegistry()
) {
    /**
     * Detect cycles in agent dependency graph using DFS.
     *
     * @throws IllegalArgumentException if a cycle is detected or dependency references unknown agent
     */
    fun validateDependencies(specs: List<AgentSpec>) {
        val agentNames = specs.map { it.name }.toSet()

        // Check for references to unknown agents
        for (spec in specs) {
            for (dep in spec.dependsOn) {
                require(dep in agentNames) {
                    "Agent '${spec.name}' depends on unknown agent '$dep'. Known agents: $agentNames"
                }
            }
        }

        // Build adjacency list and detect cycles via DFS
        val adjacency = specs.associate { it.name to it.dependsOn }
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(node: String, path: List<String>) {
            if (node in inStack) {
                val cycleStart = path.indexOf(node)
                val cycle = path.subList(cycleStart, path.size) + node
                throw IllegalArgumentException(
                    "Circular dependency detected: ${cycle.joinToString(" → ")}"
                )
            }
            if (node in visited) return

            inStack.add(node)
            for (dep in adjacency[node] ?: emptyList()) {
                dfs(dep, path + node)
            }
            inStack.remove(node)
            visited.add(node)
        }

        for (spec in specs) {
            if (spec.name !in visited) {
                dfs(spec.name, emptyList())
            }
        }
    }

    /**
     * Run multiple agents with dependency resolution.
     *
     * @param sessionId Multi-agent session ID
     * @param specs List of agent specifications
     * @param executor Function that executes a single agent and returns result.
     *                 This is injected to decouple from CoreApiRouter.
     * @return Map of agent name to result
     * @throws IllegalArgumentException if dependency graph contains cycles or unknown references
     */
    suspend fun run(
        sessionId: String,
        specs: List<AgentSpec>,
        executor: suspend (spec: AgentSpec, agentId: String) -> AgentResult
    ): Map<String, AgentResult> {
        // Validate dependency graph before starting
        validateDependencies(specs)

        logger.info { "[MULTI_AGENT] Starting session $sessionId with ${specs.size} agents: ${specs.map { it.name }}" }

        val results = ConcurrentHashMap<String, AgentResult>()
        val completedAgents = MutableStateFlow(setOf<String>())

        supervisorScope {
            for (spec in specs) {
                launch {
                    val agentId = "${spec.name}-${UUID.randomUUID().toString().take(8)}"

                    // Wait for dependencies
                    if (spec.dependsOn.isNotEmpty()) {
                        logger.info { "[MULTI_AGENT] Agent '${spec.name}' waiting for: ${spec.dependsOn}" }
                        completedAgents.first { completed ->
                            spec.dependsOn.all { it in completed }
                        }
                        logger.info { "[MULTI_AGENT] Agent '${spec.name}' dependencies satisfied" }
                    }

                    val startTime = System.currentTimeMillis()

                    // Everything after the dependency wait runs inside this try: if setup
                    // (metrics, start event, inbox registration) throws, the finally block
                    // still marks the agent completed so dependents never wait forever.
                    var inbox: AgentMessageInbox? = null
                    try {
                        // The start event goes out before any setup that can throw, so every agent
                        // that later reports AgentFailed already has a node in the graph. Emitting
                        // it after setup let a setup failure produce a Failed event with no Started
                        // counterpart, leaving an orphan in the Agents Graph and one event short in
                        // the persisted history.
                        eventBus.emit(AgentEvent.AgentStarted(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            sourceAgentId = agentId,
                            timestamp = startTime,
                            correlationId = sessionId,
                            agentName = spec.name,
                            profile = spec.profile,
                            task = spec.task,
                            model = spec.model,
                            dependsOn = spec.dependsOn
                        ))

                        // Setup per-agent metrics
                        val agentMetrics = GlobalMetrics.forAgent(agentId)
                        agentMetrics.resetCancellation()

                        // Register this agent's inbox so peers (or this agent itself) can route
                        // messages by spec.name via AgentInboxRegistry. The inbox's coroutines die
                        // with `this` (the supervisorScope launch) when the agent completes.
                        inbox = AgentMessageInbox(
                            agentName = spec.name,
                            sessionId = sessionId,
                            eventBus = eventBus,
                            scope = this,
                        )
                        inboxRegistry.register(inbox)

                        val result = executor(spec, agentId)
                        results[spec.name] = result

                        val duration = System.currentTimeMillis() - startTime
                        agentMetrics.recordTokens(
                            result.tokensUsed.toInt().coerceAtMost(Int.MAX_VALUE),
                            0, result.costUsd
                        )

                        eventBus.emit(AgentEvent.AgentCompleted(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            sourceAgentId = agentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = sessionId,
                            summary = result.response.take(500),
                            artifacts = emptyList(),
                            tokensUsed = result.tokensUsed,
                            costUsd = result.costUsd,
                            durationMs = duration
                        ))

                        logger.info {
                            "[MULTI_AGENT] Agent '${spec.name}' completed: " +
                            "tokens=${result.tokensUsed}, cost=$${result.costUsd}, duration=${duration}ms"
                        }
                    } catch (e: CancellationException) {
                        throw e // Don't catch cancellation
                    } catch (e: Exception) {
                        val errorMsg = e.message ?: "Unknown error"
                        results[spec.name] = AgentResult(
                            agentName = spec.name,
                            success = false,
                            response = "",
                            error = errorMsg
                        )

                        eventBus.emit(AgentEvent.AgentFailed(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            sourceAgentId = agentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = sessionId,
                            error = errorMsg,
                            recoverable = true
                        ))

                        logger.error(e) { "[MULTI_AGENT] Agent '${spec.name}' failed: $errorMsg" }
                    } finally {
                        inbox?.close()
                        inboxRegistry.unregister(sessionId, spec.name)
                        GlobalMetrics.removeAgent(agentId)
                        completedAgents.update { it + spec.name }
                    }
                }
            }
        }

        logger.info {
            "[MULTI_AGENT] Session $sessionId completed. " +
            "Results: ${results.map { "${it.key}=${if (it.value.success) "OK" else "FAIL"}" }}"
        }

        return results
    }
}
