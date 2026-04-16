package pl.jclab.refio.core.agents.orchestration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.MultiAgentStrategy
import pl.jclab.refio.core.agents.AgentResult
import pl.jclab.refio.core.agents.AgentSpec
import pl.jclab.refio.core.agents.MultiAgentRunner
import pl.jclab.refio.core.api.CreateTaskRequest
import pl.jclab.refio.core.api.LEGACY_PROJECT_ID
import pl.jclab.refio.core.api.LEGACY_PROJECT_PATH
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.models.SubagentDefinition

private val logger = dualLogger("OrchestrationDispatcher")

/**
 * Routes a user turn to a multi-agent flow selected by [MultiAgentStrategy].
 *
 * - [MultiAgentStrategy.ORCHESTRATOR] runs a single turn with the `multi-agent-coordinator`
 *   subagent's system prompt + tool whitelist. The LLM decides dynamically which subagents
 *   to spawn via `invoke_subagent`.
 * - [MultiAgentStrategy.PARALLEL] asks [TaskDecomposer] for independent specs, runs them
 *   concurrently via [MultiAgentRunner], then [ResultMerger] collapses N outputs to one.
 * - [MultiAgentStrategy.PIPELINE] decomposes into a linear chain where each stage consumes
 *   the previous stage's output via the `{{prev.output}}` placeholder. Returns the last
 *   stage's response as-is.
 *
 * Each sub-agent run emits an assistant message to the parent chat with `agentName` populated,
 * so [pl.jclab.refio.ui.components.chat.bubble.ChatMessageBubbleRouter] renders a per-agent
 * bubble header. The sub-agent's internal turn-loop messages land in an isolated child task
 * so they don't pollute the main conversation.
 */
class OrchestrationDispatcher(
    private val configService: ConfigService,
    private val subagentRouter: SubagentRouter,
    private val multiAgentRunner: MultiAgentRunner,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskDecomposer: TaskDecomposer,
    private val resultMerger: ResultMerger,
    private val createTaskFn: (CreateTaskRequest) -> TaskResponse,
    private val runTurnFn: suspend (TurnRequest, StreamCallback?) -> TurnResult,
    private val projectId: String?,
    private val projectPath: String?,
) {

    data class OrchestrationResult(
        val response: String,
        val agents: Map<String, AgentResult>,
        val totalTokensIn: Int,
        val totalTokensOut: Int,
        val totalCost: Double,
    )

    suspend fun dispatch(
        parentTaskId: String,
        input: String,
        contextRefs: List<ContextReference>,
        parentModel: String?,
        parentProvider: String?,
        stream: Boolean,
        streamCallback: StreamCallback?,
        strategy: MultiAgentStrategy,
    ): OrchestrationResult {
        logger.info { "[ORCHESTRATE] strategy=$strategy taskId=$parentTaskId" }

        return when (strategy) {
            MultiAgentStrategy.ORCHESTRATOR -> runOrchestrator(
                parentTaskId, input, contextRefs, parentModel, parentProvider, stream, streamCallback
            )
            MultiAgentStrategy.PARALLEL -> runParallel(
                parentTaskId, input, parentModel, parentProvider, stream, streamCallback
            )
            MultiAgentStrategy.PIPELINE -> runPipeline(
                parentTaskId, input, parentModel, parentProvider
            )
            MultiAgentStrategy.SINGLE -> throw IllegalArgumentException(
                "OrchestrationDispatcher must not be called for SINGLE strategy"
            )
        }
    }

    // ========== ORCHESTRATOR ==========

    private suspend fun runOrchestrator(
        parentTaskId: String,
        input: String,
        contextRefs: List<ContextReference>,
        parentModel: String?,
        parentProvider: String?,
        stream: Boolean,
        streamCallback: StreamCallback?,
    ): OrchestrationResult {
        val coordinator = subagentRouter.getSubagent(COORDINATOR_NAME)
            ?: throw IllegalStateException("Subagent '$COORDINATOR_NAME' not found — install built-in subagents")
        if (!coordinator.enabled) {
            throw IllegalStateException("Subagent '$COORDINATOR_NAME' is disabled — enable it in Settings")
        }

        val (resolvedModel, resolvedProvider) = coordinator.resolveModel(
            configService, joinModel(parentModel, parentProvider)
        )

        val request = buildSubagentTurnRequest(
            taskId = parentTaskId,
            userInput = input,
            definition = coordinator,
            resolvedModel = resolvedModel,
            resolvedProvider = resolvedProvider,
            contextRefs = contextRefs,
            emitSessionId = parentTaskId,
            emitSourceAgentId = "orchestrator",
        )

        val result = runTurnFn(request, streamCallback.takeIf { stream })
        return OrchestrationResult(
            response = result.response,
            agents = mapOf(
                COORDINATOR_NAME to AgentResult(
                    agentName = COORDINATOR_NAME,
                    success = result.success,
                    response = result.response,
                    tokensUsed = (result.tokensIn + result.tokensOut).toLong(),
                    costUsd = result.cost,
                )
            ),
            totalTokensIn = result.tokensIn,
            totalTokensOut = result.tokensOut,
            totalCost = result.cost,
        )
    }

    // ========== PARALLEL ==========

    private suspend fun runParallel(
        parentTaskId: String,
        input: String,
        parentModel: String?,
        parentProvider: String?,
        stream: Boolean,
        streamCallback: StreamCallback?,
    ): OrchestrationResult {
        val specs = taskDecomposer.decompose(input, TaskDecomposer.Mode.PARALLEL)
        if (specs.isEmpty()) {
            throw DecompositionFailedException(
                "Decomposer produced no plan for PARALLEL — user should re-run with SINGLE strategy"
            )
        }

        logger.info { "[ORCHESTRATE/PARALLEL] ${specs.size} agents: ${specs.map { it.name }}" }

        val results = multiAgentRunner.run(parentTaskId, specs) { spec, agentId ->
            executeSpec(parentTaskId, spec, agentId, parentModel, parentProvider, originalInput = input)
        }

        val merged = resultMerger.merge(input, results, stream = stream, onChunk = streamCallback.takeIf { stream })
        return buildResult(merged, results)
    }

    // ========== PIPELINE ==========

    private suspend fun runPipeline(
        parentTaskId: String,
        input: String,
        parentModel: String?,
        parentProvider: String?,
    ): OrchestrationResult {
        val specs = taskDecomposer.decompose(input, TaskDecomposer.Mode.PIPELINE)
        if (specs.isEmpty()) {
            throw DecompositionFailedException(
                "Decomposer produced no plan for PIPELINE — user should re-run with SINGLE strategy"
            )
        }

        logger.info { "[ORCHESTRATE/PIPELINE] ${specs.size} stages: ${specs.map { it.name }}" }

        val outputs = mutableMapOf<String, String>()
        val outputsMutex = Mutex()

        val results = multiAgentRunner.run(parentTaskId, specs) { spec, agentId ->
            val resolvedTask = resolvePipelinePlaceholders(spec, outputs, outputsMutex)
            val resolvedSpec = spec.copy(task = resolvedTask)
            val result = executeSpec(parentTaskId, resolvedSpec, agentId, parentModel, parentProvider, originalInput = input)
            outputsMutex.withLock { outputs[spec.name] = result.response }
            result
        }

        // For PIPELINE, the last stage's response is the final answer — no LLM merge needed.
        val finalResponse = specs.lastOrNull()?.let { results[it.name]?.response }.orEmpty()
        return buildResult(finalResponse, results)
    }

    private suspend fun resolvePipelinePlaceholders(
        spec: AgentSpec,
        outputs: Map<String, String>,
        outputsMutex: Mutex,
    ): String {
        val prevName = spec.dependsOn.firstOrNull() ?: return spec.task
        val prevOutput = outputsMutex.withLock { outputs[prevName].orEmpty() }
        return spec.task.replace("{{prev.output}}", prevOutput)
    }

    // ========== Per-spec execution ==========

    private suspend fun executeSpec(
        parentTaskId: String,
        spec: AgentSpec,
        agentId: String,
        parentModel: String?,
        parentProvider: String?,
        originalInput: String,
    ): AgentResult {
        val subagentName = spec.profile ?: spec.name
        val definition = subagentRouter.getSubagent(subagentName)
            ?: throw IllegalStateException("Subagent '$subagentName' not found during orchestration")

        val (resolvedModel, resolvedProvider) = definition.resolveModel(
            configService, joinModel(parentModel, parentProvider)
        )

        val childTask = createTaskFn(
            CreateTaskRequest(
                name = "orchestrate: ${spec.name}",
                mode = TaskMode.AGENT,
                projectId = projectId ?: LEGACY_PROJECT_ID,
                projectPath = projectPath ?: LEGACY_PROJECT_PATH,
            )
        )

        val request = buildSubagentTurnRequest(
            taskId = childTask.id,
            userInput = spec.task,
            definition = definition,
            resolvedModel = resolvedModel,
            resolvedProvider = resolvedProvider,
            contextRefs = emptyList(),
            emitSessionId = parentTaskId,
            emitSourceAgentId = agentId,
        )

        val turnResult = runTurnFn(request, null)

        // Publish per-agent bubble in the parent conversation.
        chatMessageRepository.create(
            taskId = parentTaskId,
            role = MessageRole.ASSISTANT,
            content = turnResult.response,
            agentName = spec.name,
            agentDepth = 1,
            tokensIn = turnResult.tokensIn,
            tokensOut = turnResult.tokensOut,
            cost = turnResult.cost,
        )

        return AgentResult(
            agentName = spec.name,
            success = turnResult.success,
            response = turnResult.response,
            tokensUsed = (turnResult.tokensIn + turnResult.tokensOut).toLong(),
            costUsd = turnResult.cost,
        )
    }

    private fun buildSubagentTurnRequest(
        taskId: String,
        userInput: String,
        definition: SubagentDefinition,
        resolvedModel: String,
        resolvedProvider: String,
        contextRefs: List<ContextReference>,
        emitSessionId: String,
        emitSourceAgentId: String,
    ): TurnRequest {
        return TurnRequest(
            taskId = taskId,
            userInput = userInput,
            mode = TaskMode.AGENT,
            executionMode = ExecutionMode.AUTO,
            model = resolvedModel,
            provider = resolvedProvider,
            userContextRefs = contextRefs,
            runProfile = TurnRunProfile.SUBAGENT,
            profileOverrides = TurnProfileOverrides(
                subagentName = definition.name,
                systemPromptOverride = definition.systemPrompt,
                allowedTools = definition.allowedTools,
                disallowedTools = definition.disallowedTools,
                modelOverride = resolvedModel,
                providerOverride = resolvedProvider,
                maxIterationsOverride = definition.maxSteps,
                depth = 0,
                subagentChain = emptyList(),
                contextProfile = definition.contextProfile,
                reasoningEffort = definition.reasoningEffort,
            ),
            emitSessionId = emitSessionId,
            emitSourceAgentId = emitSourceAgentId,
        )
    }

    private fun buildResult(
        response: String,
        agents: Map<String, AgentResult>,
    ): OrchestrationResult {
        val totalTokens = agents.values.sumOf { it.tokensUsed }
        // AgentResult.tokensUsed bundles in+out; preserve that split cheaply by halving — callers
        // use the totals for session accounting, the exact in/out split isn't load-bearing here.
        return OrchestrationResult(
            response = response,
            agents = agents,
            totalTokensIn = (totalTokens / 2).toInt(),
            totalTokensOut = (totalTokens - totalTokens / 2).toInt(),
            totalCost = agents.values.sumOf { it.costUsd },
        )
    }

    private fun joinModel(model: String?, provider: String?): String? {
        return when {
            model != null && provider != null -> "$provider/$model"
            model != null -> model
            else -> null
        }
    }

    companion object {
        private const val COORDINATOR_NAME = "multi-agent-coordinator"
    }
}

class DecompositionFailedException(message: String) : Exception(message)
