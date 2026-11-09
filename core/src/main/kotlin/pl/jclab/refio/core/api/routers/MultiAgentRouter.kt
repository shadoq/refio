package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.agents.AgentResult
import pl.jclab.refio.core.agents.AgentSpec
import pl.jclab.refio.core.agents.MultiAgentTaskParser
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.AgentInstance
import pl.jclab.refio.core.db.AgentInstanceStatus
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.subagents.models.SubagentDefinition

private val logger = dualLogger("MultiAgentRouter")

/**
 * Router for multi-agent session operations.
 * Handles launching, querying, and listing multi-agent sessions.
 */
class MultiAgentRouter(
    private val defaultProjectId: String?,
    private val defaultProjectPath: String?,
    private val agentSessionRepository: pl.jclab.refio.core.db.repositories.AgentSessionRepository,
    private val agentInstanceRepository: pl.jclab.refio.core.db.repositories.AgentInstanceRepository,
    private val multiAgentRunner: pl.jclab.refio.core.agents.MultiAgentRunner,
    private val createTaskFn: (CreateTaskRequest) -> TaskResponse,
    private val runTurnFn: suspend (TurnRequest, StreamCallback?) -> TurnResult,
    /** Writes an agent's own turn limits into its task's config. */
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    /** Looks up the subagent definition an agent's `profile` names. */
    private val subagentDefinitionFn: (String) -> pl.jclab.refio.core.subagents.models.SubagentDefinition? = { null },
    private val taskCostFn: (taskId: String) -> Double? = { null },
) : Router {

    override suspend fun initialize() {
        logger.info { "[MultiAgentRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[MultiAgentRouter] Shutting down" }
    }

    /**
     * Launch a multi-agent session from YAML definition.
     */
    suspend fun launchMultiAgentSession(
        request: MultiAgentSessionRequest,
        streamCallback: StreamCallback? = null
    ): MultiAgentSessionResponse {
        val projectId = defaultProjectId ?: LEGACY_PROJECT_ID

        val definition = MultiAgentTaskParser.parse(request.yamlDefinition)
        val specs = MultiAgentTaskParser.toAgentSpecs(definition)
        val limits = MultiAgentTaskParser.toLimits(definition)

        multiAgentRunner.validateDependencies(specs)
        // Before the session exists: a misspelled profile must stop the run, not quietly run the
        // agent with the default instructions.
        val profiles = resolveProfiles(specs)

        val session = agentSessionRepository.create(
            projectId = projectId,
            name = request.name,
            definitionYaml = request.yamlDefinition
        )
        agentSessionRepository.updateStatus(session.id, "RUNNING")

        val instanceMap = mutableMapOf<String, pl.jclab.refio.core.db.AgentInstance>()
        for (spec in specs) {
            val instance = agentInstanceRepository.create(
                sessionId = session.id,
                name = spec.name,
                taskDescription = spec.task,
                profile = spec.profile,
                model = spec.model ?: request.model,
                dependsOn = if (spec.dependsOn.isNotEmpty()) spec.dependsOn.joinToString(",") else null
            )
            instanceMap[spec.name] = instance
        }

        val startTime = System.currentTimeMillis()

        try {
            val results = multiAgentRunner.run(session.id, specs, limits) { spec, agentId ->
                val instance = instanceMap[spec.name]!!
                // Capture the real start time locally: the `instance` object is the pre-execution row
                // (startedAt = null) and is NOT refreshed by updateStatus, so reading instance.startedAt
                // back would be null and the agent order could not be reconstructed.
                val agentStartedAt = System.currentTimeMillis()
                agentInstanceRepository.updateStatus(
                    instance.id, pl.jclab.refio.core.db.AgentInstanceStatus.RUNNING,
                    startedAt = agentStartedAt
                )

                val agentTask = createTaskFn(CreateTaskRequest(
                    name = "${request.name} — ${spec.name}",
                    mode = spec.mode,
                    projectId = projectId,
                    projectPath = defaultProjectPath ?: LEGACY_PROJECT_PATH
                ))

                applyTurnLimits(agentTask.id, spec)
                val profile = profiles[spec.name]

                // Attribute per-turn events to the parent multi-agent session so the
                // user sees one unified trace for the whole run. sourceAgentId is the
                // spawned agentId already used by MultiAgentRunner.AgentStarted/Completed,
                // so Graph and Trace views correlate cleanly.
                val turnResult = try {
                    runTurnFn(
                        TurnRequest(
                            taskId = agentTask.id,
                            userInput = spec.task,
                            mode = spec.mode,
                            executionMode = pl.jclab.refio.core.db.ExecutionMode.AUTO,
                            model = spec.model ?: request.model,
                            provider = request.provider,
                            emitSessionId = session.id,
                            emitSourceAgentId = agentId,
                            agentName = spec.name,
                            runProfile = if (profile != null) TurnRunProfile.SUBAGENT else TurnRunProfile.DEFAULT,
                            profileOverrides = turnProfile(spec, profile),
                        ),
                        streamCallback
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@run failedTurn(spec.name, instance.id, agentTask.id, agentStartedAt, e)
                }

                // The turn reports only its own model calls; its subagents and delegated calls are
                // billed to the same task, and the run's budget is split on what was really spent.
                val costUsd = maxOf(turnResult.cost, taskCostFn(agentTask.id) ?: 0.0)
                val status = agentStatusOf(turnResult)
                val completedAt = System.currentTimeMillis()
                agentInstanceRepository.updateStatus(instance.id, status, completedAt = completedAt)
                agentInstanceRepository.updateResult(
                    instance.id,
                    result = turnResult.response.take(10000),
                    tokensIn = turnResult.tokensIn,
                    tokensOut = turnResult.tokensOut,
                    costUsd = costUsd
                )

                AgentResult(
                    agentName = spec.name,
                    success = turnResult.success,
                    response = turnResult.response,
                    error = if (turnResult.success) null else describeTurnFailure(turnResult),
                    tokensUsed = (turnResult.tokensIn + turnResult.tokensOut).toLong(),
                    tokensIn = turnResult.tokensIn,
                    tokensOut = turnResult.tokensOut,
                    costUsd = costUsd,
                    durationMs = completedAt - agentStartedAt,
                    startedAt = agentStartedAt,
                    completedAt = completedAt,
                    status = status,
                    filesChanged = turnResult.filesChanged,
                    taskId = agentTask.id,
                )
            }

            val completedAt = System.currentTimeMillis()
            recordAgentsWithoutTurn(results, instanceMap, completedAt)
            val sessionStatus = sessionStatusOf(results.values)
            agentSessionRepository.updateStatus(session.id, sessionStatus, completedAt)

            return buildSessionResponse(session, results, startTime, completedAt, request.name, sessionStatus)
        } catch (e: Exception) {
            agentSessionRepository.updateStatus(session.id, "FAILED", System.currentTimeMillis())
            throw e
        }
    }

    /**
     * Get status of a multi-agent session.
     */
    fun getMultiAgentSession(sessionId: String): MultiAgentSessionResponse? {
        val session = agentSessionRepository.findById(sessionId) ?: return null
        val instances = agentInstanceRepository.findBySessionId(sessionId)

        return MultiAgentSessionResponse(
            sessionId = session.id,
            name = session.name,
            status = session.status,
            agents = instances.map { inst ->
                MultiAgentInstanceResponse(
                    agentName = inst.name,
                    status = inst.status,
                    success = inst.status == "COMPLETED",
                    response = inst.result?.take(2000),
                    tokensUsed = (inst.tokensIn + inst.tokensOut).toLong(),
                    tokensIn = inst.tokensIn,
                    tokensOut = inst.tokensOut,
                    costUsd = inst.costUsd,
                    durationMs = if (inst.startedAt != null && inst.completedAt != null)
                        inst.completedAt - inst.startedAt else 0,
                    error = if (inst.status == AgentInstanceStatus.FAILED.name || inst.status == AgentInstanceStatus.SKIPPED.name) {
                        inst.result
                    } else {
                        null
                    },
                    startedAt = inst.startedAt,
                    completedAt = inst.completedAt
                )
            },
            totalTokens = instances.sumOf { (it.tokensIn + it.tokensOut).toLong() },
            totalTokensIn = instances.sumOf { it.tokensIn.toLong() },
            totalTokensOut = instances.sumOf { it.tokensOut.toLong() },
            totalCostUsd = instances.sumOf { it.costUsd },
            durationMs = if (session.completedAt != null) session.completedAt - session.createdAt else 0,
            createdAt = session.createdAt,
            completedAt = session.completedAt
        )
    }

    /**
     * List all multi-agent sessions for the current project.
     */
    fun listMultiAgentSessions(): List<MultiAgentSessionResponse> {
        val projectId = defaultProjectId ?: return emptyList()
        return agentSessionRepository.findByProjectId(projectId).map { session ->
            getMultiAgentSession(session.id)!!
        }
    }

    /**
     * Fails the run before anything is created when a profile names no usable subagent definition.
     * A profile is the definition's instructions, tool limits and step limit.
     */
    private fun resolveProfiles(specs: List<AgentSpec>): Map<String, SubagentDefinition> =
        specs.mapNotNull { spec ->
            val name = spec.profile?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val definition = subagentDefinitionFn(name)
            require(definition != null) { "Agent '${spec.name}' uses profile '$name', but no subagent definition has that name" }
            require(definition.enabled) { "Agent '${spec.name}' uses profile '$name', which is disabled" }
            spec.name to definition
        }.toMap()

    /**
     * The shape of the agent's turn: its profile's instructions and tool limits, the same way a
     * subagent invoked by name runs, and its own iteration limit over the profile's.
     */
    private fun turnProfile(spec: AgentSpec, profile: SubagentDefinition?): TurnProfileOverrides? = when {
        profile != null -> TurnProfileOverrides(
            subagentName = spec.name,
            systemPromptOverride = profile.systemPrompt,
            allowedTools = profile.allowedTools,
            disallowedTools = profile.disallowedTools,
            maxIterationsOverride = spec.maxIterations ?: profile.maxSteps,
            depth = 0,
            contextProfile = profile.contextProfile,
            reasoningEffort = profile.reasoningEffort,
        )
        spec.maxIterations != null -> TurnProfileOverrides(maxIterationsOverride = spec.maxIterations)
        else -> null
    }

    /**
     * An agent whose turn threw before it could report. It still has its own task, which holds its
     * conversation, and the cost it ran up there, which the run's budget must count.
     */
    private fun failedTurn(name: String, instanceId: String, taskId: String, startedAt: Long, e: Exception): AgentResult {
        logger.warn { "[MultiAgentRouter] agent '$name' stopped on an error: ${e.message}" }
        val completedAt = System.currentTimeMillis()
        val costUsd = taskCostFn(taskId) ?: 0.0
        val error = "turn stopped on an error: ${e.message ?: e::class.simpleName}"
        agentInstanceRepository.updateStatus(instanceId, AgentInstanceStatus.FAILED, completedAt = completedAt)
        agentInstanceRepository.updateResult(instanceId, result = error, tokensIn = 0, tokensOut = 0, costUsd = costUsd)
        return AgentResult(
            agentName = name,
            success = false,
            response = "",
            error = error,
            costUsd = costUsd,
            durationMs = completedAt - startedAt,
            startedAt = startedAt,
            completedAt = completedAt,
            status = AgentInstanceStatus.FAILED,
            taskId = taskId,
        )
    }

    /** The agent's own time limit and what is left of the run's budget become its task's limits. */
    private fun applyTurnLimits(taskId: String, spec: AgentSpec) {
        val config = configService ?: return
        spec.maxMinutes?.let { config.setTyped(ConfigKeys.AGENT_MAX_TURN_MINUTES, it.toLong(), ConfigScope.TASK, taskId) }
        spec.maxCostUsd?.let { config.setTyped(ConfigKeys.AGENT_MAX_COST_USD, it, ConfigScope.TASK, taskId) }
    }

    /**
     * Agents the runner settled without a finished turn (skipped, or stopped by an exception) still
     * have their row at PENDING or RUNNING; give them their final status and the reason.
     */
    private fun recordAgentsWithoutTurn(
        results: Map<String, AgentResult>,
        instances: Map<String, AgentInstance>,
        completedAt: Long,
    ) {
        results.values.filter { it.taskId == null }.forEach { result ->
            val instance = instances[result.agentName] ?: return@forEach
            agentInstanceRepository.updateStatus(instance.id, result.status, completedAt = completedAt)
            agentInstanceRepository.updateResult(
                instance.id,
                result = result.error ?: result.response.take(10000),
                tokensIn = result.tokensIn,
                tokensOut = result.tokensOut,
                costUsd = result.costUsd
            )
        }
    }

    private fun buildSessionResponse(
        session: pl.jclab.refio.core.db.AgentSession,
        results: Map<String, AgentResult>,
        startTime: Long,
        completedAt: Long,
        name: String,
        status: String,
    ): MultiAgentSessionResponse {
        return MultiAgentSessionResponse(
            sessionId = session.id,
            name = name,
            status = status,
            agents = results.map { (agentName, result) ->
                MultiAgentInstanceResponse(
                    agentName = agentName,
                    status = result.status.name,
                    success = result.status == AgentInstanceStatus.COMPLETED,
                    response = result.response.take(2000),
                    tokensUsed = result.tokensUsed,
                    tokensIn = result.tokensIn,
                    tokensOut = result.tokensOut,
                    costUsd = result.costUsd,
                    durationMs = result.durationMs,
                    error = result.error,
                    startedAt = result.startedAt,
                    completedAt = result.completedAt,
                    taskId = result.taskId,
                    filesChanged = result.filesChanged,
                )
            },
            totalTokens = results.values.sumOf { it.tokensUsed },
            totalTokensIn = results.values.sumOf { it.tokensIn.toLong() },
            totalTokensOut = results.values.sumOf { it.tokensOut.toLong() },
            totalCostUsd = results.values.sumOf { it.costUsd },
            durationMs = completedAt - startTime,
            createdAt = session.createdAt,
            completedAt = completedAt
        )
    }
}


/**
 * COMPLETED when the turn delivered, INCOMPLETE when it stopped on a limit with work worth building
 * on, FAILED otherwise. Shown per agent and stored on its row.
 */
internal fun agentStatusOf(turnResult: TurnResult): AgentInstanceStatus = when {
    turnResult.success -> AgentInstanceStatus.COMPLETED
    turnResult.stopReason.isPartial -> AgentInstanceStatus.INCOMPLETE
    else -> AgentInstanceStatus.FAILED
}

/** FAILED when any agent failed, INCOMPLETE when any other did not complete, COMPLETED otherwise. */
internal fun sessionStatusOf(results: Collection<AgentResult>): String = when {
    results.any { it.status == AgentInstanceStatus.FAILED } -> AgentInstanceStatus.FAILED.name
    results.any { it.status != AgentInstanceStatus.COMPLETED } -> AgentInstanceStatus.INCOMPLETE.name
    else -> AgentInstanceStatus.COMPLETED.name
}

/**
 * Explains why an agent's turn did not succeed.
 *
 * The turn reports its outcome through several disjoint signals and none of them is an error
 * string, so copying only `success` left a failed agent with nothing to diagnose: the run's error
 * list came out empty and the only trace of the failure was the text of the final answer.
 */
internal fun describeTurnFailure(turnResult: pl.jclab.refio.core.services.TurnResult): String {
    val iterations = "${turnResult.iterations} iteration(s)"
    return when {
        turnResult.rejectedByUser ->
            "tool '${turnResult.rejectedToolName ?: "unknown"}' was rejected after $iterations: " +
                (turnResult.rejectionReason ?: "no reason given")
        turnResult.verification?.result == pl.jclab.refio.core.debug.VerificationSummary.RESULT_FAILED &&
            turnResult.verification?.attributionUncertain != true ->
            "post-turn verification failed after ${turnResult.verification?.attempts ?: 0} attempt(s) and $iterations"
        turnResult.incomplete ->
            "turn ended without delivering the request after $iterations" + describeTools(turnResult)
        else ->
            "turn failed after $iterations" + describeTools(turnResult)
    }
}

private fun describeTools(turnResult: pl.jclab.refio.core.services.TurnResult): String =
    if (turnResult.toolsUsed.isEmpty()) {
        ", no tools were used"
    } else {
        ", tools used: ${turnResult.toolsUsed.joinToString(", ")}"
    }
