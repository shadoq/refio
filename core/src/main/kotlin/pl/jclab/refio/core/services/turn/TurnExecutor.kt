package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.debug.IterationSummary
import pl.jclab.refio.core.debug.TurnIterationTracker
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.debug.TurnStopReasonTracker
import pl.jclab.refio.core.errors.NativeToolTemplateError
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.NativeToolsFallbackTracker
import pl.jclab.refio.core.llm.ProviderToolSupport
import pl.jclab.refio.core.llm.nativeToolsDecisionReason
import pl.jclab.refio.core.llm.parseNativeToolsMode
import pl.jclab.refio.core.llm.shouldUseNativeTools
import pl.jclab.refio.core.llm.streaming.StreamAbortedException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.LLMRetryHandler
import pl.jclab.refio.core.services.NoopTaskVerifier
import pl.jclab.refio.core.services.PendingUserMessageQueue
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.services.TaskVerifier
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.TurnLoopConfigs
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.WorkingMemoryIntegration
import pl.jclab.refio.core.services.hooks.HookService
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.subagents.SubagentToolFilter
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolSchema
import java.util.UUID
import java.util.concurrent.CancellationException

private val logger = dualLogger("AgentTurnLoop")

/**
 * TurnExecutor - thin coordinator for the turn-based agent execution loop.
 *
 * Delegates per-iteration execution to [TurnIterationRunner] and completion/verification
 * handling to [TurnCompletionPipeline], keeping [executeLoop] compact and well under
 * the JVM per-method bytecode ceiling.
 */
internal class TurnExecutor(
    private val llmClient: LLMClient,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    @Suppress("UNUSED_PARAMETER") private val subtaskRepository: SubtaskRepository,
    private val configService: ConfigService,
    private val toolRegistry: ToolRegistry,
    private val taskVerifier: TaskVerifier = NoopTaskVerifier(),
    private val turnPromptBuilder: TurnPromptBuilder,
    private val toolCallParser: ToolCallParser,
    private val turnToolExecutor: TurnToolExecutor,
    private val turnLLMCaller: TurnLLMCaller,
    private val turnResponseProcessor: TurnResponseProcessor,
    private val turnFinalizer: TurnFinalizer,
    private val completionGuardians: GuardianRegistry = GuardianRegistry(),
    private val tokenEstimator: PromptTokenEstimator = PromptTokenEstimator(),
    @Suppress("UNUSED_PARAMETER") private val llmRetryHandler: LLMRetryHandler? = null,
    @Suppress("UNUSED_PARAMETER") private val workingMemoryIntegration: WorkingMemoryIntegration? = null,
    private val pendingUserMessageQueue: PendingUserMessageQueue? = null,
    private val agentEventBus: AgentEventBus? = null,
    private val hookService: HookService? = null,
    private val toolPermissionsService: ToolPermissionsService? = null,
    private val turnVerifier: TurnVerifier? = null,
) {
    private val toolCallExtractor = ToolCallExtractor(toolCallParser, toolRegistry)
    private val handoffNoteWriter = HandoffNoteWriter(
        llmClient, chatMessageRepository, configService, toolCallParser::extractTextResponse,
    )
    private val llmResponseRecovery = LLMResponseRecovery(toolCallParser)

    private val _turnState = MutableStateFlow(TurnStateSnapshot())
    val turnState: StateFlow<TurnStateSnapshot> = _turnState.asStateFlow()

    private val _lastPromptSnapshot = MutableStateFlow<PromptSnapshot?>(null)
    val lastPromptSnapshot: StateFlow<PromptSnapshot?> = _lastPromptSnapshot.asStateFlow()

    private fun updateTurnState(update: TurnStateSnapshot.() -> TurnStateSnapshot) {
        _turnState.value = _turnState.value.update()
    }

    private val completionPipeline = TurnCompletionPipeline(
        chatMessageRepository = chatMessageRepository,
        taskRepository = taskRepository,
        configService = configService,
        taskVerifier = taskVerifier,
        toolCallParser = toolCallParser,
        turnToolExecutor = turnToolExecutor,
        turnResponseProcessor = turnResponseProcessor,
        completionGuardians = completionGuardians,
        pendingUserMessageQueue = pendingUserMessageQueue,
        hookService = hookService,
        turnVerifier = turnVerifier,
        updateTurnState = ::updateTurnState,
    )

    private val iterationRunner = TurnIterationRunner(
        chatMessageRepository = chatMessageRepository,
        configService = configService,
        toolRegistry = toolRegistry,
        turnPromptBuilder = turnPromptBuilder,
        toolCallParser = toolCallParser,
        toolCallExtractor = toolCallExtractor,
        turnToolExecutor = turnToolExecutor,
        turnLLMCaller = turnLLMCaller,
        turnResponseProcessor = turnResponseProcessor,
        llmResponseRecovery = llmResponseRecovery,
        completionPipeline = completionPipeline,
        tokenEstimator = tokenEstimator,
        pendingUserMessageQueue = pendingUserMessageQueue,
        agentEventBus = agentEventBus,
        turnVerifier = turnVerifier,
        updateTurnState = ::updateTurnState,
        onPromptSnapshot = { _lastPromptSnapshot.value = it },
        emitTurnEvent = ::emitTurnEvent,
    )

    internal fun interface UserMessageStrategy {
        suspend fun getUserMessage(taskId: String): String?
    }

    internal enum class TurnSource {
        RUN,
        CONTINUE,
    }

    private suspend fun emitTurnEvent(
        @Suppress("UNUSED_PARAMETER") taskId: String,
        build: () -> AgentEvent,
    ) {
        val bus = agentEventBus ?: return
        try {
            bus.emit(build())
        } catch (e: Exception) {
            logger.debug { "Failed to emit turn event: ${e.message}" }
        }
    }

    internal suspend fun execute(
        taskId: String,
        mode: TaskMode,
        executionMode: ExecutionMode,
        listener: TurnEventListener?,
        streamCallback: StreamCallback?,
        model: String?,
        provider: String?,
        userContextRefs: List<ContextReference>,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        source: TurnSource,
        userMessageStrategy: UserMessageStrategy,
        emitSessionId: String? = null,
        emitSourceAgentId: String? = null,
        agentName: String? = null,
    ): TurnResult {
        probeProviderToolSupport(mode, taskId, model, provider, profileOverrides)
        val lastRequest = LastRequestHolder()
        val changedFiles = ChangedFiles()
        val loopResult = executeLoop(
            taskId, mode, executionMode, listener, streamCallback, model, provider,
            userContextRefs, runProfile, profileOverrides, runId, parentRunId, depth,
            source, userMessageStrategy, emitSessionId, emitSourceAgentId, agentName, lastRequest, changedFiles,
        )
        val result = leaveHandoffNote(taskId, loopResult.copy(filesChanged = changedFiles.toList()), lastRequest, depth)
        emitTurnFinal(
            taskId, emitSessionId, emitSourceAgentId, runId, parentRunId, depth,
            iterations = result.iterations, success = result.success,
        )
        return result
    }

    private suspend fun leaveHandoffNote(
        taskId: String,
        result: TurnResult,
        lastRequest: LastRequestHolder,
        depth: Int,
    ): TurnResult {
        if (result.success) return result
        val note = handoffNoteWriter.write(taskId, result.stopReason, lastRequest.request, lastRequest.agent)
            ?: return result
        return if (depth > 0) result.copy(response = result.response + "\n\n" + note) else result
    }

    private suspend fun probeProviderToolSupport(
        mode: TaskMode,
        taskId: String,
        model: String?,
        provider: String?,
        profileOverrides: TurnProfileOverrides?,
    ) {
        val (effectiveModel, effectiveProvider) = turnLLMCaller.resolveModelSelection(
            mode = mode,
            taskId = taskId,
            model = model,
            provider = provider,
            profileOverrides = profileOverrides,
        )
        if (ModelDefinitions.getDefinition(effectiveProvider, effectiveModel) == null) {
            ProviderToolSupport.probe(effectiveProvider, effectiveModel, configService)
        }
    }

    private suspend fun emitTurnFinal(
        taskId: String,
        emitSessionId: String?,
        emitSourceAgentId: String?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        iterations: Int,
        success: Boolean,
    ) {
        emitTurnEvent(taskId) {
            AgentEvent.TurnEnded(
                id = UUID.randomUUID().toString(),
                sessionId = emitSessionId ?: taskId,
                sourceAgentId = emitSourceAgentId ?: taskId,
                timestamp = System.currentTimeMillis(),
                correlationId = runId,
                iteration = iterations,
                durationMs = 0,
                isFinal = true,
                success = success,
                runId = runId,
                parentRunId = parentRunId,
                depth = depth,
            )
        }
    }

    private fun buildRunContextAndState(
        taskId: String,
        mode: TaskMode,
        executionMode: ExecutionMode,
        listener: TurnEventListener?,
        streamCallback: StreamCallback?,
        model: String?,
        provider: String?,
        userContextRefs: List<ContextReference>,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        userMessageStrategy: UserMessageStrategy,
        emitSessionId: String?,
        emitSourceAgentId: String?,
        agentName: String?,
        lastRequest: LastRequestHolder,
        changedFiles: ChangedFiles,
    ): Pair<TurnRunContext, TurnLoopState> {
        val evSessionId = emitSessionId ?: taskId
        val evSourceAgentId = emitSourceAgentId ?: taskId
        val config = TurnLoopConfigs.forMode(mode)
        val maxIterations = turnLLMCaller.resolveMaxIterations(config, profileOverrides)

        val subagentHasWriteTools = if (runProfile == TurnRunProfile.SUBAGENT && profileOverrides != null) {
            turnPromptBuilder.resolveToolsForProfile(mode, taskId, profileOverrides).any {
                it.mode == ToolMode.WRITE &&
                    it.name.lowercase() !in SubagentToolFilter.SYSTEM_TOOLS
            }
        } else {
            true
        }

        val maxConsecutiveIdenticalFailures = configService.getTyped(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS, taskId)
        val subagentMetadata: String? = if (runProfile == TurnRunProfile.SUBAGENT) {
            val name = profileOverrides?.subagentName ?: "subagent"
            """{"subagent_name":"$name"}"""
        } else null

        val persistAgentInstanceId: String? = resolveSubagentInstanceId(profileOverrides)
        val persistAgentName: String? = profileOverrides?.subagentName
        val persistAgentDepth: Int? = storedAgentDepth(profileOverrides)
        lastRequest.agent = AgentRef(persistAgentInstanceId, persistAgentName, persistAgentDepth)

        val turnPersistence = TurnPersistence(
            taskId = taskId,
            listener = listener,
            runId = runId,
            parentRunId = parentRunId,
            depth = depth,
            subagentMetadata = subagentMetadata,
            agentInstanceId = persistAgentInstanceId,
            agentName = persistAgentName,
            agentDepth = persistAgentDepth,
            mode = mode,
            maxIterations = maxIterations,
            turnToolExecutor = turnToolExecutor,
            turnFinalizer = turnFinalizer,
            chatMessageRepository = chatMessageRepository,
            onResetTurnState = { updateTurnState { TurnStateSnapshot() } },
        )

        val baseStreamCallback: StreamCallback? = if (streamCallback != null || listener != null) {
            { chunk ->
                chunk.toolCallProgress?.let { p ->
                    listener?.onLlmToolCallProgress(taskId, p.index, p.name, p.accumulatedArguments)
                }
                streamCallback?.invoke(chunk)
            }
        } else null

        val effectiveStreamCallback: StreamCallback? = if (persistAgentName != null && agentEventBus != null) {
            val bus = agentEventBus
            val wrappedName = persistAgentName
            val wrappedDepth = persistAgentDepth ?: 1
            val delegate = baseStreamCallback
            { chunk ->
                delegate?.invoke(chunk)
                bus.tryEmit(
                    AgentEvent.StreamChunk(
                        id = UUID.randomUUID().toString(),
                        sessionId = evSessionId,
                        sourceAgentId = evSourceAgentId,
                        timestamp = System.currentTimeMillis(),
                        correlationId = runId,
                        delta = chunk.delta,
                        accumulated = chunk.accumulated,
                        isComplete = chunk.isComplete,
                        runId = runId,
                        depth = wrappedDepth,
                        agentName = wrappedName,
                    )
                )
            }
        } else baseStreamCallback

        val (effectiveModel, effectiveProvider) = turnLLMCaller.resolveModelSelection(
            mode = mode,
            taskId = taskId,
            model = model,
            provider = provider,
            profileOverrides = profileOverrides,
        )
        val responseFormat = turnLLMCaller.resolveResponseFormat(mode, effectiveProvider)
        val initialNativeToolSchemas = resolveInitialNativeToolSchemas(
            taskId, mode, effectiveModel, effectiveProvider, profileOverrides,
        )

        val state = TurnLoopState(
            errorWindowSize = config.errorWindowSize,
            depth = depth,
            initialNativeToolSchemas = initialNativeToolSchemas,
            lastRequest = lastRequest,
            changedFiles = changedFiles,
        )
        turnPersistence.guardrailStats = state::toGuardrailStats

        val ctx = TurnRunContext(
            taskId = taskId,
            mode = mode,
            executionMode = executionMode,
            listener = listener,
            userContextRefs = userContextRefs,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            runId = runId,
            parentRunId = parentRunId,
            depth = depth,
            userMessageStrategy = userMessageStrategy,
            evSessionId = evSessionId,
            evSourceAgentId = evSourceAgentId,
            agentName = agentName,
            config = config,
            maxIterations = maxIterations,
            subagentHasWriteTools = subagentHasWriteTools,
            maxConsecutiveIdenticalFailures = maxConsecutiveIdenticalFailures,
            persistAgentInstanceId = persistAgentInstanceId,
            persistAgentName = persistAgentName,
            persistAgentDepth = persistAgentDepth,
            effectiveStreamCallback = effectiveStreamCallback,
            effectiveModel = effectiveModel,
            effectiveProvider = effectiveProvider,
            responseFormat = responseFormat,
            maxCostUsd = configService.getTyped(ConfigKeys.AGENT_MAX_COST_USD, taskId),
            maxTurnMinutes = configService.getTyped(ConfigKeys.AGENT_MAX_TURN_MINUTES, taskId),
            baseFormatNudges = configService.getTyped(ConfigKeys.AGENT_MAX_FORMAT_NUDGES, taskId),
            turnStartMs = System.currentTimeMillis(),
            turnPersistence = turnPersistence,
            turnStateUpdater = { phase -> updateTurnState { copy(phase = phase) } },
        )
        return ctx to state
    }

    private suspend fun executeLoop(
        taskId: String,
        mode: TaskMode,
        executionMode: ExecutionMode,
        listener: TurnEventListener?,
        streamCallback: StreamCallback?,
        model: String?,
        provider: String?,
        userContextRefs: List<ContextReference>,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        @Suppress("UNUSED_PARAMETER") source: TurnSource,
        userMessageStrategy: UserMessageStrategy,
        emitSessionId: String?,
        emitSourceAgentId: String?,
        agentName: String?,
        lastRequest: LastRequestHolder,
        changedFiles: ChangedFiles,
    ): TurnResult {
        val (ctx, state) = buildRunContextAndState(
            taskId, mode, executionMode, listener, streamCallback, model, provider,
            userContextRefs, runProfile, profileOverrides, runId, parentRunId, depth,
            userMessageStrategy, emitSessionId, emitSourceAgentId, agentName, lastRequest, changedFiles,
        )

        try {
            while (state.iteration < ctx.maxIterations) {
                if (GlobalMetrics.isCancelled(taskId)) {
                    throw CancellationException("Operation cancelled by user")
                }
                state.iteration++

                logger.info { "[TURN_ITERATION] taskId=$taskId, iteration=${state.iteration}/${ctx.maxIterations}" }

                if (ctx.maxCostUsd > 0.0) {
                    val currentCostUsd = taskRepository.findById(taskId)?.costUsd ?: 0.0
                    if (CostLimitGuard.isExceeded(currentCostUsd, ctx.maxCostUsd)) {
                        logger.warn { "[COST_LIMIT_EXCEEDED] taskId=$taskId, cost=$currentCostUsd >= max=${ctx.maxCostUsd} - aborting turn" }
                        val result = state.buildResult(
                            success = false,
                            response = "COST_LIMIT_EXCEEDED: session cost \$%.4f reached the --max-cost ceiling of \$%.4f. Stopping the turn."
                                .format(currentCostUsd, ctx.maxCostUsd),
                            incomplete = true,
                        )
                        return ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.COST_LIMIT)
                    }
                }

                TurnBudgetGuard.check(
                    elapsedMillis = System.currentTimeMillis() - ctx.turnStartMs,
                    maxMinutes = ctx.maxTurnMinutes,
                )?.let { breach ->
                    val reason = TurnBudgetGuard.describe(breach)
                    logger.warn { "[TURN_BUDGET_EXCEEDED] taskId=$taskId, iteration=${state.iteration} - $reason" }
                    val result = state.buildResult(success = false, response = reason, incomplete = true)
                    return ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.TIME_LIMIT)
                }

                val iterationStartMs = System.currentTimeMillis()
                emitTurnEvent(taskId) {
                    AgentEvent.TurnStarted(
                        id = UUID.randomUUID().toString(),
                        sessionId = ctx.evSessionId,
                        sourceAgentId = ctx.evSourceAgentId,
                        timestamp = iterationStartMs,
                        correlationId = runId,
                        iteration = state.iteration,
                        maxIterations = ctx.maxIterations,
                        mode = mode.name,
                        runId = runId,
                        parentRunId = parentRunId,
                        depth = depth,
                    )
                }

                val iterationToken = GlobalMetrics.beginOperation(
                    OperationInfo.TurnLoop(state.iteration, ctx.maxIterations, mode.name)
                )

                val outcome = try {
                    iterationRunner.runIteration(ctx, state)
                } finally {
                    GlobalMetrics.endOperation(iterationToken)
                    val iterationDurationMs = System.currentTimeMillis() - iterationStartMs
                    emitTurnEvent(taskId) {
                        AgentEvent.TurnEnded(
                            id = UUID.randomUUID().toString(),
                            sessionId = ctx.evSessionId,
                            sourceAgentId = ctx.evSourceAgentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = runId,
                            iteration = state.iteration,
                            durationMs = iterationDurationMs,
                            isFinal = false,
                            runId = runId,
                            parentRunId = parentRunId,
                            depth = depth,
                        )
                    }
                    hookService?.trigger(
                        "after_turn_loop",
                        mapOf(
                            "taskId" to taskId,
                            "mode" to mode.name,
                            "iteration" to state.iteration.toString(),
                            "agentName" to (profileOverrides?.subagentName ?: "default"),
                        ),
                    )
                }

                if (outcome is IterationOutcome.Finished) {
                    return outcome.result
                }
            }
        } catch (e: StreamAbortedException) {
            updateTurnState { copy(phase = TurnPhase.FAILED) }
            logger.warn {
                "[TURN_STREAM_ABORTED] taskId=$taskId, iteration=${state.iteration}, code=${e.code}, " +
                    "reason=${e.reason}, partialLength=${e.partialContent.length}"
            }
            emitTurnEvent(taskId) {
                AgentEvent.StreamAborted(
                    id = UUID.randomUUID().toString(),
                    sessionId = ctx.evSessionId,
                    sourceAgentId = ctx.evSourceAgentId,
                    timestamp = System.currentTimeMillis(),
                    correlationId = runId,
                    iteration = state.iteration,
                    code = e.code,
                    reason = e.reason,
                    partialLength = e.partialContent.length,
                    partialPreview = e.partialContent.take(500),
                    runId = runId,
                    parentRunId = parentRunId,
                    depth = depth,
                )
            }
            hookService?.trigger(
                "on_agent_error",
                mapOf(
                    "taskId" to taskId,
                    "mode" to mode.name,
                    "error" to "Stream aborted by guardrail [${e.code}]: ${e.reason}",
                    "guardrailCode" to e.code,
                    "agentName" to (profileOverrides?.subagentName ?: "default"),
                ),
            )
            val result = state.buildResult(
                success = false,
                response = "Stream aborted by guardrail [${e.code}]: ${e.reason}",
            )
            return ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.STREAM_GUARDRAIL)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                updateTurnState { copy(phase = TurnPhase.FAILED) }
                runCatching {
                    hookService?.trigger(
                        "on_agent_error",
                        mapOf(
                            "taskId" to taskId,
                            "mode" to mode.name,
                            "error" to "Operation cancelled by user",
                            "agentName" to (profileOverrides?.subagentName ?: "default"),
                        ),
                    )
                }
                val result = state.buildResult(
                    success = false,
                    response = state.lastStreamedAssistantText?.takeIf { it.isNotBlank() }
                        ?: "Operation cancelled by user.",
                )
                runCatching { ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.CANCELLED) }
            }
            throw e
        } catch (e: Exception) {
            updateTurnState { copy(phase = TurnPhase.FAILED) }
            if (depth == 0) {
                TurnStopReasonTracker.record(taskId, TurnStopReason.EXCEPTION)
                TurnIterationTracker.record(
                    taskId,
                    IterationSummary(used = state.iteration, limit = ctx.maxIterations),
                )
            }
            emitTurnFinal(
                taskId, emitSessionId, emitSourceAgentId, runId, parentRunId, depth,
                iterations = state.iteration, success = false,
            )
            throw e
        }

        updateTurnState { copy(phase = TurnPhase.FAILED) }
        logger.warn { "[TURN_MAX_ITERATIONS] taskId=$taskId, exceeded ${ctx.maxIterations} iterations" }
        hookService?.trigger(
            "on_agent_error",
            mapOf(
                "taskId" to taskId,
                "mode" to mode.name,
                "error" to "Maximum iterations exceeded",
                "agentName" to (profileOverrides?.subagentName ?: "default"),
            ),
        )
        val result = state.buildResult(
            success = false,
            response = state.lastStreamedAssistantText?.takeIf { it.isNotBlank() }
                ?: "Error: Maximum iterations exceeded. The agent may be stuck in a loop.",
            incomplete = true,
        )
        return ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.MAX_ITERATIONS)
    }

    private fun resolveInitialNativeToolSchemas(
        taskId: String,
        mode: TaskMode,
        effectiveModel: String,
        effectiveProvider: String,
        profileOverrides: TurnProfileOverrides?,
    ): List<ToolSchema>? {
        val svc = toolPermissionsService
        if (svc == null) {
            logger.debug {
                "[NATIVE_TOOLS] Disabled for taskId=$taskId, mode=$mode -> JSON-in-text path - " +
                    "no ToolPermissionsService (tools are off for this run)"
            }
            return null
        }
        val nativeModeRaw = configService.getTyped(ConfigKeys.NATIVE_TOOLS_MODE, taskId)
        val nativeToolsMode = parseNativeToolsMode(nativeModeRaw)
        val modelDef = ModelDefinitions.getDefinition(effectiveProvider, effectiveModel)
        val fallbackSet = NativeToolsFallbackTracker.getFallbackSet()
        val providerTools = ProviderToolSupport.cached(effectiveProvider, effectiveModel, configService)
        val nativeReason = nativeToolsDecisionReason(nativeToolsMode, modelDef, effectiveModel, fallbackSet, providerTools)
        return if (shouldUseNativeTools(nativeToolsMode, modelDef, effectiveModel, fallbackSet, providerTools)) {
            val modeSchemas = toolRegistry.getToolSchemas(mode, svc, taskId)
            val filtered = turnPromptBuilder.filterNativeToolSchemasByProfile(modeSchemas, profileOverrides)
            if (filtered.size != modeSchemas.size) {
                logger.info {
                    "[NATIVE_TOOLS] Filtered schemas for profile '${profileOverrides?.subagentName ?: "?"}': " +
                        "${modeSchemas.size} -> ${filtered.size}"
                }
            }
            logger.info {
                "[NATIVE_TOOLS] Enabled for taskId=$taskId, mode=$mode, " +
                    "model=$effectiveProvider/$effectiveModel, schemas=${filtered.size} - $nativeReason"
            }
            filtered
        } else {
            logger.info {
                "[NATIVE_TOOLS] Disabled for taskId=$taskId, mode=$mode, " +
                    "model=$effectiveProvider/$effectiveModel -> JSON-in-text path - $nativeReason"
            }
            null
        }
    }

    internal fun isNativeToolTemplateParseError(error: Throwable): Boolean =
        NativeToolTemplateError.matches(error)

    internal fun getLastUserMessage(taskId: String, agentInstanceId: String? = null): String? =
        completionPipeline.getLastUserMessage(taskId, agentInstanceId)
}
