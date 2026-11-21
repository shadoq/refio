package pl.jclab.refio.core.agents

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
import pl.jclab.refio.core.agents.events.AgentMessageInbox
import pl.jclab.refio.core.db.AgentInstanceStatus
import pl.jclab.refio.core.services.logging.coreLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = coreLogger("MultiAgentRunner")

/**
 * Orchestrates parallel execution of multiple agents.
 *
 * Each agent is an isolated AgentTurnLoop instance with its own Task in DB.
 * Dependencies are respected (agent B waits for agent A to complete), and a dependent gets the
 * reports of its dependencies appended to its task. What happens after an agent does not complete,
 * and the run's shared time, cost and parallelism limits, come from [WorkflowLimits].
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
    private val inboxRegistry: AgentInboxRegistry = AgentInboxRegistry(),
    private val nowMs: () -> Long = System::currentTimeMillis,
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

    /** Run with no shared limits; dependents of an agent that did not complete are skipped. */
    suspend fun run(
        sessionId: String,
        specs: List<AgentSpec>,
        executor: suspend (spec: AgentSpec, agentId: String) -> AgentResult
    ): Map<String, AgentResult> = run(sessionId, specs, WorkflowLimits(), executor)

    /**
     * Run multiple agents with dependency resolution.
     *
     * @param sessionId Multi-agent session ID
     * @param specs List of agent specifications
     * @param limits The run's shared limits and what happens after an agent does not complete
     * @param executor Function that executes a single agent and returns result. It gets the spec
     *                 with the dependency reports appended to its task and its limits capped by
     *                 what is left of the run's time and budget. Injected to decouple from
     *                 CoreApiRouter.
     * @return Map of agent name to result; a skipped agent has one too
     * @throws IllegalArgumentException if dependency graph contains cycles or unknown references
     */
    suspend fun run(
        sessionId: String,
        specs: List<AgentSpec>,
        limits: WorkflowLimits,
        executor: suspend (spec: AgentSpec, agentId: String) -> AgentResult
    ): Map<String, AgentResult> {
        // Validate dependency graph before starting
        validateDependencies(specs)

        logger.info {
            "[MULTI_AGENT] Starting session $sessionId with ${specs.size} agents: ${specs.map { it.name }}, limits=$limits"
        }

        val results = ConcurrentHashMap<String, AgentResult>()
        val completedAgents = MutableStateFlow(setOf<String>())
        val slots = limits.maxParallel?.let { Semaphore(it) }
        val deadline = limits.maxMinutes?.let { nowMs() + it * MINUTE_MS }
        val stopped = AtomicBoolean(false)

        supervisorScope {
            for (spec in specs) {
                launch {
                    val agentScope = this
                    val agentId = "${spec.name}-${UUID.randomUUID().toString().take(8)}"
                    // The finally marks the agent completed whatever happens, so dependents never
                    // wait forever.
                    try {
                        if (spec.dependsOn.isNotEmpty()) {
                            logger.info { "[MULTI_AGENT] Agent '${spec.name}' waiting for: ${spec.dependsOn}" }
                            completedAgents.first { completed ->
                                spec.dependsOn.all { it in completed }
                            }
                            logger.info { "[MULTI_AGENT] Agent '${spec.name}' dependencies satisfied" }
                        }

                        // Checked before waiting for a slot, so a doomed agent does not queue, and
                        // again once it has one, since the run may have stopped or run out meanwhile.
                        val result = skipReason(spec, limits, results, deadline, stopped.get())
                            ?.let { skip(sessionId, agentId, spec, it) }
                            ?: withSlot(slots) {
                                skipReason(spec, limits, results, deadline, stopped.get())
                                    ?.let { skip(sessionId, agentId, spec, it) }
                                    ?: runAgent(sessionId, agentId, prepare(spec, limits, results, deadline), executor, agentScope)
                            }
                        results[spec.name] = result
                        if (limits.onFailure == OnFailure.STOP && result.status != AgentInstanceStatus.COMPLETED) {
                            stopped.set(true)
                        }
                    } finally {
                        completedAgents.update { it + spec.name }
                    }
                }
            }
        }

        logger.info {
            "[MULTI_AGENT] Session $sessionId completed. " +
            "Results: ${results.map { "${it.key}=${it.value.status}" }}"
        }

        return results
    }

    /**
     * One agent's turn with its events, metrics and inbox. Never throws except on cancellation: a
     * failure becomes a FAILED result.
     */
    private suspend fun runAgent(
        sessionId: String,
        agentId: String,
        spec: AgentSpec,
        executor: suspend (spec: AgentSpec, agentId: String) -> AgentResult,
        scope: CoroutineScope,
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        var inbox: AgentMessageInbox? = null
        return try {
            // The start event goes out before any setup that can throw, so every agent
            // that later reports AgentFailed already has a node in the graph. Emitting
            // it after setup let a setup failure produce a Failed event with no Started
            // counterpart, leaving an orphan in the Agents Graph and one event short in
            // the persisted history.
            eventBus.emit(startedEvent(sessionId, agentId, spec, startTime))

            // Setup per-agent metrics
            val agentMetrics = GlobalMetrics.forAgent(agentId)
            agentMetrics.resetCancellation()

            // Register this agent's inbox so peers (or this agent itself) can route
            // messages by spec.name via AgentInboxRegistry. The inbox's coroutines die
            // with the agent's own launch when the agent completes.
            inbox = AgentMessageInbox(
                agentName = spec.name,
                sessionId = sessionId,
                eventBus = eventBus,
                scope = scope,
            )
            inboxRegistry.register(inbox)

            val result = executor(spec, agentId)

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
                "[MULTI_AGENT] Agent '${spec.name}' finished (${result.status}): " +
                "tokens=${result.tokensUsed}, cost=$${result.costUsd}, duration=${duration}ms"
            }
            result
        } catch (e: CancellationException) {
            throw e // Don't catch cancellation
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            logger.error(e) { "[MULTI_AGENT] Agent '${spec.name}' failed: $errorMsg" }
            emitFailed(sessionId, agentId, spec, errorMsg, recoverable = true)
            AgentResult(
                agentName = spec.name,
                success = false,
                response = "",
                error = errorMsg
            )
        } finally {
            inbox?.close()
            inboxRegistry.unregister(sessionId, spec.name)
            GlobalMetrics.removeAgent(agentId)
        }
    }

    /** Settles an agent that never runs. The graph still gets its node, failed with the reason. */
    private suspend fun skip(sessionId: String, agentId: String, spec: AgentSpec, reason: String): AgentResult {
        val error = "skipped: $reason"
        logger.info { "[MULTI_AGENT] Agent '${spec.name}' $error" }
        try {
            eventBus.emit(startedEvent(sessionId, agentId, spec, System.currentTimeMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[MULTI_AGENT] Could not report skipped agent '${spec.name}'" }
        }
        emitFailed(sessionId, agentId, spec, error, recoverable = false)
        return AgentResult(
            agentName = spec.name,
            success = false,
            response = "",
            error = error,
            status = AgentInstanceStatus.SKIPPED,
        )
    }

    /** Why [spec] must not start now, or null when it may. */
    private fun skipReason(
        spec: AgentSpec,
        limits: WorkflowLimits,
        results: Map<String, AgentResult>,
        deadline: Long?,
        stopped: Boolean,
    ): String? {
        if (stopped) {
            return "the run stopped after an agent did not complete"
        }
        if (limits.onFailure != OnFailure.CONTINUE) {
            val unfinished = spec.dependsOn.firstOrNull { results[it]?.status != AgentInstanceStatus.COMPLETED }
            if (unfinished != null) {
                val status = results[unfinished]?.status?.name?.lowercase() ?: "no result"
                return "dependency '$unfinished' did not complete ($status)"
            }
        }
        if (deadline != null && nowMs() >= deadline) {
            return "the run's time limit was reached"
        }
        val budget = limits.maxCostUsd
        if (budget != null && spent(results) >= budget) {
            return "the run's cost limit was reached"
        }
        return null
    }

    /** The spec the agent runs with: its dependencies' reports added, its limits capped by the run's. */
    private fun prepare(
        spec: AgentSpec,
        limits: WorkflowLimits,
        results: Map<String, AgentResult>,
        deadline: Long?,
    ): AgentSpec = spec.copy(
        task = DependencyReports.appendTo(spec.task, spec.dependsOn.mapNotNull { results[it] }),
        maxMinutes = cappedMinutes(spec.maxMinutes, deadline),
        maxCostUsd = limits.maxCostUsd?.let { it - spent(results) },
    )

    /** The agent's own limit, but never past the run's end; a started agent gets at least a minute. */
    private fun cappedMinutes(own: Int?, deadline: Long?): Int? {
        if (deadline == null) {
            return own
        }
        val left = ((deadline - nowMs() + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)
        return if (own == null) left else minOf(own, left)
    }

    private fun spent(results: Map<String, AgentResult>): Double = results.values.sumOf { it.costUsd }

    private suspend fun <T> withSlot(slots: Semaphore?, block: suspend () -> T): T =
        if (slots == null) block() else slots.withPermit { block() }

    private fun startedEvent(sessionId: String, agentId: String, spec: AgentSpec, timestamp: Long) =
        AgentEvent.AgentStarted(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sourceAgentId = agentId,
            timestamp = timestamp,
            correlationId = sessionId,
            agentName = spec.name,
            profile = spec.profile,
            task = spec.task,
            model = spec.model,
            dependsOn = spec.dependsOn
        )

    /** A failure the event stream could not take must not lose the agent's result. */
    private suspend fun emitFailed(sessionId: String, agentId: String, spec: AgentSpec, error: String, recoverable: Boolean) {
        try {
            eventBus.emit(AgentEvent.AgentFailed(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                sourceAgentId = agentId,
                timestamp = System.currentTimeMillis(),
                correlationId = sessionId,
                error = error,
                recoverable = recoverable
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[MULTI_AGENT] Could not report the failure of agent '${spec.name}'" }
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
