package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.logging.dualLogger

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
    private val runTurnFn: suspend (TurnRequest, StreamCallback?) -> TurnResult
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

        val definition = pl.jclab.refio.core.agents.MultiAgentTaskParser.parse(request.yamlDefinition)
        val specs = pl.jclab.refio.core.agents.MultiAgentTaskParser.toAgentSpecs(definition)

        multiAgentRunner.validateDependencies(specs)

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
            val results = multiAgentRunner.run(session.id, specs) { spec, agentId ->
                val instance = instanceMap[spec.name]!!
                agentInstanceRepository.updateStatus(
                    instance.id, pl.jclab.refio.core.db.AgentInstanceStatus.RUNNING,
                    startedAt = System.currentTimeMillis()
                )

                val agentTask = createTaskFn(CreateTaskRequest(
                    name = "${request.name} — ${spec.name}",
                    mode = spec.mode,
                    projectId = projectId,
                    projectPath = defaultProjectPath ?: LEGACY_PROJECT_PATH
                ))

                // Attribute per-turn events to the parent multi-agent session so the
                // user sees one unified trace for the whole run. sourceAgentId is the
                // spawned agentId already used by MultiAgentRunner.AgentStarted/Completed,
                // so Graph and Trace views correlate cleanly.
                val turnResult = runTurnFn(
                    TurnRequest(
                        taskId = agentTask.id,
                        userInput = spec.task,
                        mode = spec.mode,
                        executionMode = pl.jclab.refio.core.db.ExecutionMode.AUTO,
                        model = spec.model ?: request.model,
                        provider = request.provider,
                        emitSessionId = session.id,
                        emitSourceAgentId = agentId
                    ),
                    streamCallback
                )

                val completedAt = System.currentTimeMillis()
                agentInstanceRepository.updateStatus(
                    instance.id, pl.jclab.refio.core.db.AgentInstanceStatus.COMPLETED,
                    completedAt = completedAt
                )
                agentInstanceRepository.updateResult(
                    instance.id,
                    result = turnResult.response.take(10000),
                    tokensIn = turnResult.tokensIn,
                    tokensOut = turnResult.tokensOut,
                    costUsd = turnResult.cost
                )

                pl.jclab.refio.core.agents.AgentResult(
                    agentName = spec.name,
                    success = turnResult.success,
                    response = turnResult.response,
                    tokensUsed = (turnResult.tokensIn + turnResult.tokensOut).toLong(),
                    costUsd = turnResult.cost,
                    durationMs = completedAt - (instance.startedAt ?: completedAt)
                )
            }

            val completedAt = System.currentTimeMillis()
            agentSessionRepository.updateStatus(session.id, "COMPLETED", completedAt)

            return buildSessionResponse(session, results, startTime, completedAt, request.name)
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
                    costUsd = inst.costUsd,
                    durationMs = if (inst.startedAt != null && inst.completedAt != null)
                        inst.completedAt - inst.startedAt else 0,
                    error = if (inst.status == "FAILED") inst.result else null
                )
            },
            totalTokens = instances.sumOf { (it.tokensIn + it.tokensOut).toLong() },
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

    private fun buildSessionResponse(
        session: pl.jclab.refio.core.db.AgentSession,
        results: Map<String, pl.jclab.refio.core.agents.AgentResult>,
        startTime: Long,
        completedAt: Long,
        name: String
    ): MultiAgentSessionResponse {
        return MultiAgentSessionResponse(
            sessionId = session.id,
            name = name,
            status = "COMPLETED",
            agents = results.map { (agentName, result) ->
                MultiAgentInstanceResponse(
                    agentName = agentName,
                    status = if (result.success) "COMPLETED" else "FAILED",
                    success = result.success,
                    response = result.response.take(2000),
                    tokensUsed = result.tokensUsed,
                    costUsd = result.costUsd,
                    durationMs = result.durationMs,
                    error = result.error
                )
            },
            totalTokens = results.values.sumOf { it.tokensUsed },
            totalCostUsd = results.values.sumOf { it.costUsd },
            durationMs = completedAt - startTime,
            createdAt = session.createdAt,
            completedAt = completedAt
        )
    }
}
