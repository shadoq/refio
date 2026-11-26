package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.debug.ContextOverflowTracker
import pl.jclab.refio.core.debug.TurnFailureMarkerTracker
import pl.jclab.refio.core.debug.TurnNativeToolsTracker
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.errors.NativeToolTemplateError
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.ModelWindow
import pl.jclab.refio.core.llm.NativeToolsFallbackTracker
import pl.jclab.refio.core.llm.ToolsNotSupportedException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PendingUserMessageQueue
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.services.TokenRatioCalibrator
import pl.jclab.refio.core.services.ToolResultData
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.ModelUsageStats
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.services.monitoring.ToolUsageStats
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolSchema
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("AgentTurnLoop")

/**
 * Executes a single iteration of the agent turn loop:
 * 1. Builds prompt and updates prompt snapshot
 * 2. Calls model via [TurnLLMCaller] with one-shot native-to-JSON fallbacks
 * 3. Handles refusals / empty responses
 * 4. Extracts tool calls and executes tool batches (with guardrails and soft nudges)
 * 5. Delegates no-tool-call responses to [TurnCompletionPipeline]
 */
internal class TurnIterationRunner(
    private val chatMessageRepository: ChatMessageRepository,
    private val configService: ConfigService,
    private val toolRegistry: ToolRegistry,
    private val turnPromptBuilder: TurnPromptBuilder,
    private val toolCallParser: ToolCallParser,
    private val toolCallExtractor: ToolCallExtractor,
    private val turnToolExecutor: TurnToolExecutor,
    private val turnLLMCaller: TurnLLMCaller,
    private val turnResponseProcessor: TurnResponseProcessor,
    private val llmResponseRecovery: LLMResponseRecovery,
    private val completionPipeline: TurnCompletionPipeline,
    private val tokenEstimator: PromptTokenEstimator,
    private val pendingUserMessageQueue: PendingUserMessageQueue?,
    private val agentEventBus: AgentEventBus?,
    private val turnVerifier: TurnVerifier?,
    private val updateTurnState: (TurnStateSnapshot.() -> TurnStateSnapshot) -> Unit,
    private val onPromptSnapshot: (PromptSnapshot) -> Unit,
    private val emitTurnEvent: suspend (String, () -> AgentEvent) -> Unit,
) {
    private data class LlmCallOutcome(
        val response: LLMResponse,
        val prompt: TurnPrompt,
        val nativeToolSchemas: List<ToolSchema>?,
    )

    private data class ToolBatchTracking(
        val repetitionAbort: TurnGuardrails.LoopStatus.ABORT?,
        val repetitionAbortToolName: String?,
        val noopCallIds: Set<String>,
        val consecutiveIdenticalFailures: Int,
        val lastFailureSignature: String?,
        val blockedAbort: TurnGuardrails.LoopStatus.ABORT? = null,
        val deniedAbort: TurnGuardrails.LoopStatus.ABORT? = null,
    )

    private sealed interface EmptyCheckOutcome {
        data class Proceed(val response: LLMResponse) : EmptyCheckOutcome
        data class Stop(val outcome: IterationOutcome) : EmptyCheckOutcome
    }

    suspend fun runIteration(ctx: TurnRunContext, state: TurnLoopState): IterationOutcome {
        val rawResponse = invokeModelAndRecordMetrics(ctx, state)

        val llmResponse = when (val check = handleRefusalOrEmpty(ctx, state, rawResponse)) {
            is EmptyCheckOutcome.Proceed -> check.response
            is EmptyCheckOutcome.Stop -> return check.outcome
        }

        val nativeCalls = llmResponse.nativeToolCalls
        val usedNativeChannel = nativeCalls != null
        val nativeProducedNoCall = nativeCalls.isNullOrEmpty()

        updateNativeIgnoredStreak(ctx, state, llmResponse, nativeProducedNoCall)

        val contentForExtraction = if (usedNativeChannel) {
            llmResponse.content
        } else {
            toolCallParser.preprocessContent(llmResponse.content, ctx.taskId)
        }
        val jsonEnvelopeInspection = if (usedNativeChannel) {
            toolCallParser.inspectJsonEnvelope("")
        } else {
            toolCallParser.inspectJsonEnvelope(contentForExtraction)
        }
        val extraction = toolCallExtractor.extract(llmResponse, contentForExtraction, ctx.mode, ctx.profileOverrides)
        val toolCalls = when (extraction) {
            is ExtractionResult.Calls -> {
                logger.info {
                    "[TOOLCALL] taskId=${ctx.taskId}, iteration=${state.iteration}, source=${extraction.source}, " +
                        "count=${extraction.calls.size}, nativeChannel=$usedNativeChannel, " +
                        "tools=${extraction.calls.joinToString(",") { it.name }}"
                }
                extraction.calls
            }
            is ExtractionResult.None -> {
                logger.debug {
                    "[TOOLCALL] taskId=${ctx.taskId}, iteration=${state.iteration}, none reason=${extraction.reason}, " +
                        "nativeChannel=$usedNativeChannel, finishReason=${llmResponse.finishReason}"
                }
                emptyList()
            }
        }
        val looksLikeJsonResponse = if (usedNativeChannel) {
            false
        } else {
            jsonEnvelopeInspection.hasJsonEnvelope || contentForExtraction.trim().startsWith("[")
        }

        if (extraction is ExtractionResult.None && extraction.isOutputTruncated) {
            logger.error {
                "[TRUNCATED_RESPONSE] Response truncated (finishReason=${llmResponse.finishReason}) " +
                    "before a complete tool call (reason=${extraction.reason}). " +
                    "Response length: ${llmResponse.content.length} chars"
            }
            val result = state.buildResult(
                success = false,
                response = "The agent's response was truncated due to output length limits and could not be completed.\n\n" +
                    "**Solutions:**\n" +
                    "1. Ask for a smaller/more concise implementation\n" +
                    "2. Break the task into smaller parts\n" +
                    "3. Use a model with a larger context window",
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.OUTPUT_TRUNCATED)
            )
        }

        return if (toolCalls.isNotEmpty()) {
            executeToolBatch(ctx, state, llmResponse, toolCalls, usedNativeChannel)
        } else {
            completionPipeline.handleNoToolCalls(
                ctx = ctx,
                state = state,
                llmResponse = llmResponse,
                contentForExtraction = contentForExtraction,
                usedNativeChannel = usedNativeChannel,
                nativeProducedNoCall = nativeProducedNoCall,
                looksLikeJsonResponse = looksLikeJsonResponse,
                jsonEnvelopeInspection = jsonEnvelopeInspection,
            )
        }
    }

    private suspend fun invokeModelAndRecordMetrics(
        ctx: TurnRunContext,
        state: TurnLoopState,
    ): LLMResponse {
        updateTurnState {
            copy(
                phase = TurnPhase.BUILDING_PROMPT,
                iteration = state.iteration,
                maxIterations = ctx.maxIterations,
                taskId = ctx.taskId,
            )
        }
        GlobalMetrics.setCurrentOperation(
            OperationInfo.TurnBuildingPrompt(state.iteration, turnPromptBuilder.getHistorySize(ctx.taskId))
        )

        val iterationNativeToolSchemas = state.activeNativeToolSchemas
        val useNativeTools = iterationNativeToolSchemas != null

        var prompt = buildPrompt(
            taskId = ctx.taskId,
            mode = ctx.mode,
            currentIteration = state.iteration,
            maxIterations = ctx.maxIterations,
            userContextRefs = ctx.userContextRefs,
            runProfile = ctx.runProfile,
            profileOverrides = ctx.profileOverrides,
            writeToolsExecutedInTurn = state.writeToolsExecutedInTurn,
            useNativeTools = useNativeTools,
            nativeToolSchemas = iterationNativeToolSchemas,
            agentName = ctx.agentName,
            sessionId = ctx.evSessionId,
            modelId = ctx.effectiveModel,
            runId = ctx.runId,
            turnStartedAtMs = ctx.turnStartMs,
            maxTurnMinutes = ctx.maxTurnMinutes,
        )

        updateTurnState { copy(phase = TurnPhase.CALLING_MODEL) }
        GlobalMetrics.setCurrentOperation(OperationInfo.TurnLLMCall(state.iteration, ctx.mode.name))

        val llmCallStartNanos = System.nanoTime()
        val llmCall = callModelWithNativeFallback(
            ctx = ctx,
            state = state,
            initialPrompt = prompt,
            initialNativeToolSchemas = state.activeNativeToolSchemas,
            guardianHasRestorableAnswer = state.guardianState.restorableResponse(state.usedTools.size) != null,
        )
        val llmResponse = llmCall.response
        prompt = llmCall.prompt
        state.activeNativeToolSchemas = llmCall.nativeToolSchemas
        state.lastRequest.request = LastLlmRequest.of(
            prompt, state.activeNativeToolSchemas, ctx.effectiveModel, ctx.effectiveProvider,
        )
        val llmDurationMs = (System.nanoTime() - llmCallStartNanos) / 1_000_000

        val iterationAssistantText = toolCallParser.extractTextResponse(llmResponse.content)
        if (iterationAssistantText.length >= TurnDeliverable.PLAN_DELIVERABLE_MIN_CHARS) {
            state.lastStreamedAssistantText = iterationAssistantText
        }

        val promptChars = prompt.systemPrompt.length + prompt.messages.sumOf { it.content.length }
        TokenRatioCalibrator.observe(
            ctx.effectiveModel,
            promptChars,
            llmResponse.usage.inputTokens,
            truncationSuspected = ContextOverflowTracker.didOverflow(ctx.taskId),
        )

        val contextWindow = ModelWindow.resolve(
            provider = ctx.effectiveProvider,
            model = ctx.effectiveModel,
            configService = configService,
            taskId = ctx.taskId,
        )
        if (llmResponse.usage.inputTokens > contextWindow) {
            logger.warn {
                "[CTX] overflow: input=${llmResponse.usage.inputTokens} > window=$contextWindow " +
                    "model=${ctx.effectiveProvider}/${ctx.effectiveModel}"
            }
            ContextOverflowTracker.markOverflow(ctx.taskId)
        }

        emitTurnEvent(ctx.taskId) {
            AgentEvent.LLMCallCompleted(
                id = UUID.randomUUID().toString(),
                sessionId = ctx.evSessionId,
                sourceAgentId = ctx.evSourceAgentId,
                timestamp = System.currentTimeMillis(),
                correlationId = ctx.runId,
                iteration = state.iteration,
                model = ctx.effectiveModel,
                provider = ctx.effectiveProvider,
                tokensIn = llmResponse.usage.inputTokens,
                tokensOut = llmResponse.usage.outputTokens,
                costUsd = llmResponse.cost,
                durationMs = llmDurationMs,
                finishReason = llmResponse.finishReason,
                runId = ctx.runId,
                parentRunId = ctx.parentRunId,
                depth = ctx.depth,
            )
        }

        if (GlobalMetrics.isCancelled(ctx.taskId)) {
            throw CancellationException("Operation cancelled by user")
        }

        state.totalTokensIn += llmResponse.usage.inputTokens
        state.totalTokensOut += llmResponse.usage.outputTokens
        state.totalCost += llmResponse.cost

        GlobalMetrics.forAgent(ctx.taskId).recordTokens(
            tokensIn = llmResponse.usage.inputTokens,
            tokensOut = llmResponse.usage.outputTokens,
            costUsd = llmResponse.cost,
        )
        ModelUsageStats.record(
            provider = ctx.effectiveProvider,
            model = ctx.effectiveModel,
            tokensIn = llmResponse.usage.inputTokens,
            tokensOut = llmResponse.usage.outputTokens,
            costUsd = llmResponse.cost,
            durationMs = llmDurationMs,
        )
        return llmResponse
    }

    private fun handleRefusalOrEmpty(
        ctx: TurnRunContext,
        state: TurnLoopState,
        initialResponse: LLMResponse,
    ): EmptyCheckOutcome {
        var llmResponse = initialResponse

        if (ctx.mode != TaskMode.CHAT &&
            llmResponse.finishReason.equals("refusal", ignoreCase = true) &&
            llmResponse.nativeToolCalls.isNullOrEmpty()
        ) {
            logger.warn {
                "[TURN_MODEL_REFUSED] taskId=${ctx.taskId}, iteration=${state.iteration}, " +
                    "provider=${ctx.effectiveProvider}, model=${ctx.effectiveModel}"
            }
            val result = state.buildResult(
                success = false,
                response = "The model (${ctx.effectiveModel}) declined to work on this request. " +
                    "Retrying with the same model will be refused again; rephrase the task " +
                    "or switch to a different model.",
            )
            return EmptyCheckOutcome.Stop(
                IterationOutcome.Finished(
                    ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.MODEL_REFUSED)
                )
            )
        }

        if (ctx.mode != TaskMode.CHAT &&
            llmResponse.content.isBlank() &&
            llmResponse.nativeToolCalls.isNullOrEmpty() &&
            state.activeNativeToolSchemas != null
        ) {
            return EmptyCheckOutcome.Stop(
                IterationOutcome.Finished(finalizeEmptyNativeResponse(ctx, state, llmResponse))
            )
        }

        if (ctx.mode != TaskMode.CHAT &&
            llmResponse.content.isBlank() &&
            llmResponse.nativeToolCalls.isNullOrEmpty() &&
            state.activeNativeToolSchemas == null
        ) {
            when (val decision = llmResponseRecovery.classifyEmptyContent(
                llmResponse,
                ctx.mode,
                jsonMode = true,
                iteration = state.iteration,
                maxIterations = ctx.maxIterations,
                state = state.recoveryState,
                profileOverrides = ctx.profileOverrides,
                hasRestorableAnswer = state.guardianState.restorableResponse(state.usedTools.size) != null,
                baseBudget = ctx.baseFormatNudges,
                deliverableProduced = state.fileWriteToolsExecutedInTurn > 0,
            )) {
                is LLMResponseRecovery.Decision.RecoverFromThinking -> {
                    logger.warn {
                        "[TURN_EMPTY_CONTENT_RECOVERED] taskId=${ctx.taskId}, iteration=${state.iteration}, " +
                            "thinkingLength=${llmResponse.thinking?.length ?: 0}"
                    }
                    llmResponse = llmResponse.copy(content = decision.newContent, thinking = null)
                }

                LLMResponseRecovery.Decision.Nudge -> {
                    state.recoveryState.nudgeCount++
                    logger.warn {
                        "[FORMAT_RETRY_NUDGE] taskId=${ctx.taskId}, iteration=${state.iteration}: " +
                            "LLM returned empty content in JSON mode. " +
                            "Nudge=${state.recoveryState.nudgeCount}/${ctx.baseFormatNudges}, finishReason=${llmResponse.finishReason}"
                    }
                    val resolvedThinking = turnResponseProcessor.resolveAssistantThinking(llmResponse)
                    if (!resolvedThinking.isNullOrBlank()) {
                        ctx.turnPersistence.persist(
                            role = MessageRole.ASSISTANT,
                            content = "",
                            thinking = resolvedThinking,
                            toolCalls = null,
                            tokensIn = llmResponse.usage.inputTokens,
                            tokensOut = llmResponse.usage.outputTokens,
                            cost = llmResponse.cost,
                        )
                    }
                    ctx.turnPersistence.persist(
                        role = MessageRole.SYSTEM,
                        content = "Your previous reply contained empty content in structured JSON mode. " +
                            "Generate the full JSON envelope again from scratch. " +
                            "Do not continue or patch the previous output. Reply with JSON only: " +
                            "{\"actions\":[{\"tool\":\"NAME\",\"args\":{...}}]," +
                            "\"response\":\"...\",\"intent\":\"implementation\"}. " +
                            "No prose, no markdown fences.",
                        metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.FORMAT_NUDGE),
                        toolCalls = null,
                    )
                    return EmptyCheckOutcome.Stop(IterationOutcome.Continue)
                }

                is LLMResponseRecovery.Decision.GiveUp -> {
                    val deliverableProduced = TurnDeliverable.produced(
                        state.fileWriteToolsExecutedInTurn,
                        ctx.mode,
                        "",
                        isSubagent = ctx.runProfile == TurnRunProfile.SUBAGENT,
                    )
                    if (deliverableProduced) {
                        logger.warn {
                            "[TURN_EMPTY_CONTENT_DELIVERABLE] taskId=${ctx.taskId}, iteration=${state.iteration}: " +
                                "empty JSON envelope after retries, but a deliverable already landed " +
                                "(writeToolsExecutedInTurn=${state.writeToolsExecutedInTurn}, mode=${ctx.mode}) - finalizing SUCCESS"
                        }
                        val result = state.buildResult(
                            success = true,
                            response = "Changes applied. The model did not produce a final summary, " +
                                "but the edits from this turn are on disk.",
                        )
                        return EmptyCheckOutcome.Stop(
                            IterationOutcome.Finished(ctx.turnPersistence.finish(result, persistAssistantMessage = true))
                        )
                    }
                    val restoredAnswer = state.guardianState.restorableResponse(state.usedTools.size)
                        ?.let { toolCallParser.extractTextResponse(it.content).ifEmpty { it.content } }
                        ?.takeIf { it.isNotBlank() }
                    if (restoredAnswer != null) {
                        logger.warn {
                            "[TURN_EMPTY_CONTENT_RESTORED] taskId=${ctx.taskId}, iteration=${state.iteration}: " +
                                "empty JSON envelope after a guardian re-entry, restoring the " +
                                "pre-re-entry answer instead of failing the turn"
                        }
                        val result = state.buildResult(success = true, response = restoredAnswer)
                        return EmptyCheckOutcome.Stop(
                            IterationOutcome.Finished(
                                ctx.turnPersistence.finish(
                                    result,
                                    persistAssistantMessage = !state.guardianState.captureAlreadyFinalized(state.usedTools.size),
                                )
                            )
                        )
                    }
                    logger.error {
                        "[TURN_FAILED] Empty content from model in JSON mode " +
                            "(mode=${ctx.mode}, reason=${decision.reason}, finishReason=${llmResponse.finishReason}, thinkingLength=${llmResponse.thinking?.length ?: 0})"
                    }
                    val response = if (ctx.mode == TaskMode.AGENT) {
                        "The agent returned empty content in structured mode and could not recover after retrying. " +
                            "Please rerun with the same task or switch to a more reliable model."
                    } else {
                        "Model returned empty content in structured mode. " +
                            "The selected model likely does not produce the required JSON envelope - " +
                            "try a different model (e.g. one tuned for tool use) or simplify the request."
                    }
                    val result = state.buildResult(success = false, response = response)
                    return EmptyCheckOutcome.Stop(
                        IterationOutcome.Finished(
                            ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.FORMAT_UNRECOVERABLE)
                        )
                    )
                }

                LLMResponseRecovery.Decision.NotApplicable -> Unit
            }
        }

        return EmptyCheckOutcome.Proceed(llmResponse)
    }

    private fun updateNativeIgnoredStreak(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
        nativeProducedNoCall: Boolean,
    ) {
        if (nativeProducedNoCall && state.activeNativeToolSchemas != null && isJsonEnvelopeFallback(llmResponse.content)) {
            state.nativeIgnoredStreak++
            if (state.nativeIgnoredStreak >= NATIVE_TOOLS_DEMOTE_AFTER_IGNORES) {
                val modelKey = ctx.effectiveModel
                NativeToolsFallbackTracker.markFallback(
                    modelKey,
                    "model ignored native tool_calls and emitted JSON envelope in text " +
                        "${state.nativeIgnoredStreak} times in a row",
                )
                logger.warn {
                    "[NATIVE_TOOLS_FALLBACK] taskId=${ctx.taskId}, model=$modelKey - model ignored native " +
                        "tool_calls ${state.nativeIgnoredStreak} times in a row; future iterations will use " +
                        "JSON-in-text path"
                }
                state.activeNativeToolSchemas = null
            } else {
                logger.info {
                    "[NATIVE_TOOLS_SOFT_IGNORE] taskId=${ctx.taskId}, model=${ctx.effectiveModel} ignored " +
                        "native tool_calls and emitted a JSON envelope in text " +
                        "(streak=${state.nativeIgnoredStreak}/$NATIVE_TOOLS_DEMOTE_AFTER_IGNORES) - keeping native " +
                        "offered; a capable model often self-corrects next iteration"
                }
            }
        } else if (state.activeNativeToolSchemas != null) {
            state.nativeIgnoredStreak = 0
        }
    }

    private suspend fun executeToolBatch(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
        toolCalls: List<ToolCallData>,
        usedNativeChannel: Boolean,
    ): IterationOutcome {
        logger.info { "[TOOL_CALLS] taskId=${ctx.taskId}, count=${toolCalls.size}" }
        val assistantContent = if (usedNativeChannel) {
            val raw = llmResponse.content
            when {
                raw.isBlank() -> ""
                isJsonEnvelopeFallback(raw) -> {
                    logger.warn {
                        "[NATIVE_DUPLICATE_ENVELOPE] taskId=${ctx.taskId}, model=${ctx.effectiveModel} - " +
                            "stripping duplicate JSON envelope from assistant content (${raw.length} chars)"
                    }
                    ""
                }
                else -> raw
            }
        } else {
            llmResponse.content
        }
        ctx.turnPersistence.persist(
            role = MessageRole.ASSISTANT,
            content = assistantContent,
            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
            toolCalls = toolCalls,
            tokensIn = llmResponse.usage.inputTokens,
            tokensOut = llmResponse.usage.outputTokens,
            cost = llmResponse.cost,
        )

        state.usedTools.addAll(toolCalls.map { it.name })

        if (GlobalMetrics.isCancelled(ctx.taskId)) {
            throw CancellationException("Operation cancelled by user")
        }

        updateTurnState {
            copy(
                phase = TurnPhase.EXECUTING_TOOLS,
                activeToolName = toolCalls.firstOrNull()?.name,
                activeToolCount = toolCalls.size,
            )
        }

        val toolStartNanos = ConcurrentHashMap<String, Long>()
        val toolDurationsMs = ConcurrentHashMap<String, Long>()
        val effectiveListener = wrapListenerForToolTiming(ctx.listener, toolStartNanos, toolDurationsMs)
        val batchStartNanos = System.nanoTime()

        if (turnVerifier != null &&
            ctx.mode == TaskMode.AGENT &&
            ctx.depth == 0 &&
            toolCalls.any { turnToolExecutor.isFileWriteTool(it.name) || it.name == SUBAGENT_TOOL }
        ) {
            turnVerifier.captureBaseline(ctx.taskId)
        }

        val toolResults = try {
            turnToolExecutor.executeToolCalls(
                taskId = ctx.taskId,
                toolCalls = toolCalls,
                mode = ctx.mode,
                executionMode = ctx.executionMode,
                listener = effectiveListener,
                iteration = state.iteration,
                config = ctx.config,
                profileOverrides = ctx.profileOverrides,
                runId = ctx.runId,
                depth = ctx.depth,
                agentName = ctx.agentName,
                sessionId = ctx.evSessionId,
                stateUpdater = ctx.turnStateUpdater,
            )
        } catch (e: ToolRejectedException) {
            logger.info { "[REJECTED] User rejected tool '${e.toolName}': ${e.reason ?: "no reason"}" }
            chatMessageRepository.createToolResult(
                taskId = ctx.taskId,
                toolCallId = e.toolCallId,
                subtaskId = null,
                result = "Error: User rejected - ${e.reason ?: "no reason"}",
                agentInstanceId = ctx.persistAgentInstanceId,
                agentName = ctx.persistAgentName,
                agentDepth = ctx.persistAgentDepth,
            )
            ctx.turnPersistence.persist(
                role = MessageRole.SYSTEM,
                content = "User rejected tool '${e.toolName}'. Reason: ${e.reason ?: "not specified"}",
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.TOOL_REJECTION),
            )
            updateTurnState { copy(phase = TurnPhase.IDLE) }
            val result = state.buildResult(
                success = false,
                response = "User rejected tool '${e.toolName}'",
                distinctTools = false,
                rejectedByUser = true,
                rejectedToolName = e.toolName,
                rejectionReason = e.reason,
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = false, stopReason = TurnStopReason.DENIED_TOOL)
            )
        }

        for ((toolCall, resultData) in toolResults) {
            chatMessageRepository.createToolResult(
                taskId = ctx.taskId,
                toolCallId = toolCall.id,
                subtaskId = resultData.subtaskId,
                result = resultData.content,
                isSummarized = resultData.isSummarized,
                rawOutput = resultData.rawOutput,
                metadata = resultData.metadata,
                agentInstanceId = ctx.persistAgentInstanceId,
                agentName = ctx.persistAgentName,
                agentDepth = ctx.persistAgentDepth,
                tokensIn = resultData.subTokensIn,
                tokensOut = resultData.subTokensOut,
                cost = resultData.subCost,
            )
        }

        emitToolCalledEvents(
            ctx.taskId, toolResults, toolDurationsMs, batchStartNanos,
            ctx.evSessionId, ctx.evSourceAgentId, ctx.runId, ctx.parentRunId, ctx.depth, state.iteration,
        )

        handleAwaitingResponses(ctx.taskId, toolResults, ctx.turnPersistence)

        val batchInput = toolResults.map { (call, resultData) ->
            ToolCallWithResult(
                toolName = call.name,
                params = TurnJsonUtils.parseJsonToMap(call.arguments),
                success = resultData.success,
                resultPreview = resultData.content.take(100),
            )
        }
        val batchSummary = ToolBatchSummary.summarize(batchInput)
        ctx.listener?.onToolBatchCompleted(ctx.taskId, batchSummary)

        val tracking = trackToolBatch(
            toolResults,
            state.errorTracker,
            state.repetitionTracker,
            state.blockedTracker,
            state.deniedTracker,
            state.consecutiveIdenticalFailures,
            state.lastFailureSignature,
        )
        state.consecutiveIdenticalFailures = tracking.consecutiveIdenticalFailures
        state.lastFailureSignature = tracking.lastFailureSignature
        state.noopWriteCount += tracking.noopCallIds.size

        val repetitionAbort = tracking.repetitionAbort
        if (repetitionAbort != null) {
            logger.warn { "[REPETITION_ABORT] taskId=${ctx.taskId}, incomplete=${repetitionAbort.incomplete}, reason=${repetitionAbort.reason}" }
            val abortToolName = tracking.repetitionAbortToolName
            val loopedOnVerification = abortToolName != null && turnToolExecutor.isVerificationTool(abortToolName)
            val realEditLanded = state.usedTools.any { turnToolExecutor.isFileWriteTool(it) }
            if (!repetitionAbort.incomplete && loopedOnVerification && realEditLanded) {
                logger.warn {
                    "[REPETITION_ABORT] taskId=${ctx.taskId} - loop was on verification tool " +
                        "'$abortToolName' and a deliverable already landed " +
                        "(writeToolsExecutedInTurn=${state.writeToolsExecutedInTurn}) - finalizing SUCCESS"
                }
                return IterationOutcome.Finished(finalizeLoopAfterEdit(ctx, state, abortToolName))
            }
            TurnFailureMarkerTracker.record(
                ctx.taskId,
                if (repetitionAbort.incomplete) TurnFailureMarkerTracker.NOOP_WRITE_STALL
                else TurnFailureMarkerTracker.LOOP_ABORTED,
            )
            val result = state.buildResult(
                success = false,
                response = if (repetitionAbort.incomplete) repetitionAbort.reason
                else "Loop detected: ${repetitionAbort.reason} Stopping the turn.",
                incomplete = repetitionAbort.incomplete,
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(
                    result,
                    persistAssistantMessage = true,
                    stopReason = if (repetitionAbort.incomplete) TurnStopReason.NOOP_WRITE_STALL else TurnStopReason.REPETITION_LOOP,
                )
            )
        }

        val blockedAbort = tracking.blockedAbort
        if (blockedAbort != null) {
            logger.warn {
                "[BLOCKED_TOOL_ABORT] taskId=${ctx.taskId}, depth=${ctx.depth}, " +
                    "subagent=${ctx.profileOverrides?.subagentName ?: "-"}, reason=${blockedAbort.reason}"
            }
            val result = state.buildResult(success = false, response = blockedAbort.reason, incomplete = true)
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.BLOCKED_TOOL)
            )
        }

        val deniedAbort = tracking.deniedAbort
        if (deniedAbort != null) {
            logger.warn { "[DENIED_TOOL_ABORT] taskId=${ctx.taskId}, reason=${deniedAbort.reason}" }
            TurnFailureMarkerTracker.record(ctx.taskId, TurnFailureMarkerTracker.TOOL_DENIED_REPEATEDLY)
            val deniedResult = state.buildResult(success = false, response = deniedAbort.reason, incomplete = true)
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(deniedResult, persistAssistantMessage = true, stopReason = TurnStopReason.DENIED_TOOL)
            )
        }

        val writeToolCalls = turnToolExecutor.countWriteToolCalls(toolCalls)
        val verificationToolCalls = turnToolExecutor.countVerificationToolCalls(toolCalls)
        state.writeToolsExecutedInTurn += writeToolCalls
        state.changedFiles.record(toolResults.map { it.second })
        state.fileWriteToolsExecutedInTurn +=
            turnToolExecutor.countLandedFileWriteToolCalls(toolCalls, toolResults.map { it.second })
        if (writeToolCalls > 0) {
            state.verificationToolsExecutedAfterWrite = 0
        } else if (state.writeToolsExecutedInTurn > 0) {
            state.verificationToolsExecutedAfterWrite += verificationToolCalls
        }

        applySoftNudges(
            ctx.taskId, ctx.mode, ctx.depth, state.iteration, ctx.maxIterations,
            toolCalls, tracking.noopCallIds, state.softNudges, ctx.turnPersistence,
        )

        if (state.consecutiveIdenticalFailures >= ctx.maxConsecutiveIdenticalFailures) {
            logger.warn {
                "[DEFINITIVE_LOOP] taskId=${ctx.taskId}, signature=${state.lastFailureSignature}, " +
                    "consecutiveIdenticalFailures=${state.consecutiveIdenticalFailures}/${ctx.maxConsecutiveIdenticalFailures}"
            }
            val result = state.buildResult(
                success = false,
                response = "Definitive loop detected: the same tool call failed ${state.consecutiveIdenticalFailures} times in a row with identical arguments. " +
                    "The agent appears stuck retrying the same failing operation. Please rephrase your request or adjust the approach.",
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.REPETITION_LOOP)
            )
        }

        if (state.errorTracker.shouldAbort(ctx.config.errorRateThreshold)) {
            val result = state.buildResult(
                success = false,
                response = "Too many tool errors (${state.errorTracker.getStats()} failure rate). Please review tool usage and arguments.",
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.TOOL_ERROR_RATE)
            )
        }

        if (pendingUserMessageQueue?.consumePending(ctx.taskId) == true) {
            logger.info { "[MID_EXEC_INPUT] New user message detected after tool execution, nudging LLM (iteration=${state.iteration})" }
            ctx.turnPersistence.persist(
                role = MessageRole.SYSTEM,
                content = "[New user message above - address it next]",
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.INTERJECTION_NOTICE),
                toolCalls = null,
            )
        }

        return IterationOutcome.Continue
    }

    private suspend fun callModelWithNativeFallback(
        ctx: TurnRunContext,
        state: TurnLoopState,
        initialPrompt: TurnPrompt,
        initialNativeToolSchemas: List<ToolSchema>?,
        guardianHasRestorableAnswer: Boolean,
    ): LlmCallOutcome {
        var prompt = initialPrompt
        var nativeToolSchemas = initialNativeToolSchemas

        suspend fun rebuildPromptWithoutNativeTools(): TurnPrompt = buildPrompt(
            taskId = ctx.taskId,
            mode = ctx.mode,
            currentIteration = state.iteration,
            maxIterations = ctx.maxIterations,
            userContextRefs = ctx.userContextRefs,
            runProfile = ctx.runProfile,
            profileOverrides = ctx.profileOverrides,
            writeToolsExecutedInTurn = state.writeToolsExecutedInTurn,
            useNativeTools = false,
            agentName = ctx.agentName,
            sessionId = ctx.evSessionId,
            modelId = ctx.effectiveModel,
            runId = ctx.runId,
            turnStartedAtMs = ctx.turnStartMs,
            maxTurnMinutes = ctx.maxTurnMinutes,
        )

        while (true) {
            try {
                // Single unified LLM call path through TurnLLMCaller (which owns retry budget,
                // ioDispatcher, decisionTemperature, cacheable_system_length, and responseFormat).
                val response = turnLLMCaller.callLLM(
                    taskId = ctx.taskId,
                    mode = ctx.mode,
                    prompt = prompt,
                    streamCallback = ctx.effectiveStreamCallback,
                    model = ctx.effectiveModel,
                    provider = ctx.effectiveProvider,
                    profileOverrides = ctx.profileOverrides,
                    nativeToolSchemas = nativeToolSchemas,
                )
                if (nativeToolSchemas != null &&
                    response.content.isBlank() &&
                    response.nativeToolCalls.isNullOrEmpty() &&
                    !guardianHasRestorableAnswer
                ) {
                    logger.warn {
                        "[NATIVE_TOOLS_EMPTY_FALLBACK] taskId=${ctx.taskId}, model=${ctx.effectiveModel} - " +
                            "native response was empty (blank content, zero tool calls); one-shot JSON-path retry (no persistent fallback)"
                    }
                    TurnNativeToolsTracker.recordDegradation(
                        ctx.taskId, TurnNativeToolsTracker.REASON_EMPTY_NATIVE_RESPONSE,
                    )
                    nativeToolSchemas = null
                    prompt = rebuildPromptWithoutNativeTools()
                    continue
                }
                return LlmCallOutcome(response, prompt, nativeToolSchemas)
            } catch (e: ToolsNotSupportedException) {
                NativeToolsFallbackTracker.markFallback(ctx.effectiveModel, e.message ?: "provider error")
                if (nativeToolSchemas == null) {
                    throw e
                }
                logger.warn {
                    "[NATIVE_TOOLS_FALLBACK] taskId=${ctx.taskId}, model=${ctx.effectiveModel} - " +
                        "rebuilding prompt and retrying on JSON path"
                }
                TurnNativeToolsTracker.recordDegradation(ctx.taskId, TurnNativeToolsTracker.REASON_TOOLS_UNSUPPORTED)
                nativeToolSchemas = null
                prompt = rebuildPromptWithoutNativeTools()
            } catch (e: RefioError.LLMError) {
                if (nativeToolSchemas == null || !NativeToolTemplateError.matches(e)) {
                    throw e
                }
                logger.warn {
                    "[NATIVE_TOOLS_PARSE_FALLBACK] taskId=${ctx.taskId}, model=${ctx.effectiveModel} - " +
                        "provider rejected a malformed tool-call template; one-shot JSON-path retry (no persistent fallback)"
                }
                TurnNativeToolsTracker.recordDegradation(ctx.taskId, TurnNativeToolsTracker.REASON_TEMPLATE_PARSE_ERROR)
                nativeToolSchemas = null
                prompt = rebuildPromptWithoutNativeTools()
            }
        }
    }

    private fun finalizeEmptyNativeResponse(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
    ): TurnResult {
        val recoverable = state.guardianState.restorableResponse(state.usedTools.size)
        if (recoverable != null) {
            logger.warn {
                "[TURN_NATIVE_EMPTY_RECOVERED] taskId=${ctx.taskId}, iteration=${state.iteration} - " +
                    "re-entry produced empty native response with no new tool work; " +
                    "finalizing the pre-re-entry answer the user already saw."
            }
            val recoveredText = toolCallParser.extractTextResponse(recoverable.content)
            ctx.turnPersistence.persist(
                role = MessageRole.ASSISTANT,
                content = recoveredText.ifEmpty { recoverable.content },
                thinking = turnResponseProcessor.resolveAssistantThinking(recoverable),
                toolCalls = null,
                tokensIn = recoverable.usage.inputTokens,
                tokensOut = recoverable.usage.outputTokens,
                cost = recoverable.cost,
            )
            val result = state.buildResult(
                success = true,
                response = recoveredText.ifEmpty { recoverable.content },
            )
            return ctx.turnPersistence.finish(result, persistAssistantMessage = false)
        }

        logger.error {
            "[TURN_FAILED_NATIVE_EMPTY] taskId=${ctx.taskId}, iteration=${state.iteration}, " +
                "mode=${ctx.mode}, provider=${ctx.effectiveProvider}, model=${ctx.effectiveModel}, " +
                "finishReason=${llmResponse.finishReason}, " +
                "inputTokens=${llmResponse.usage.inputTokens}, " +
                "outputTokens=${llmResponse.usage.outputTokens}. " +
                "Model returned empty content with zero native tool calls - most likely " +
                "the prompt exceeded the provider's context window and was silently truncated."
        }
        val response = "Model returned no content and no tool calls. " +
            "This usually means the prompt exceeded the model's context window " +
            "(inputTokens=${llmResponse.usage.inputTokens}, finishReason=${llmResponse.finishReason}). " +
            "Try: (a) shrink the conversation history, (b) increase providers.${ctx.effectiveProvider}.${ctx.effectiveProvider}_context_size, " +
            "or (c) switch to a model with a larger window."
        val result = state.buildResult(success = false, response = response)
        return ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.EMPTY_RESPONSE)
    }

    private suspend fun finalizeLoopAfterEdit(
        ctx: TurnRunContext,
        state: TurnLoopState,
        abortToolName: String?,
    ): TurnResult {
        val verifier = turnVerifier?.takeIf { ctx.mode == TaskMode.AGENT && ctx.depth == 0 }
        val stopped = "Stopped a repeated verification step ($abortToolName) that produced no new information."
        val outcome = verifier?.verify(ctx.taskId)
        val summary = if (verifier != null && outcome != null) {
            completionPipeline.recordVerification(
                verifier, ctx.taskId, outcome,
                if (outcome.executed) state.verificationAttempts + 1 else state.verificationAttempts,
                state.verificationSummary,
            )
        } else {
            state.verificationSummary
        }
        val exitCode = when (outcome) {
            is TurnVerifier.Outcome.Failed -> outcome.exitCode
            is TurnVerifier.Outcome.PreExistingFailure -> outcome.exitCode
            else -> null
        }
        if (exitCode != null) {
            logger.warn { "[REPETITION_ABORT] taskId=${ctx.taskId} - the project's check still fails (exit=$exitCode) - finalizing VERIFICATION_FAILED" }
            TurnFailureMarkerTracker.record(ctx.taskId, TurnFailureMarkerTracker.VERIFICATION_FAILED)
            val errors = (outcome as? TurnVerifier.Outcome.Failed)?.errors.orEmpty()
            val result = state.buildResult(
                success = false,
                response = "Changes applied, but the project's check still fails (exit $exitCode). $stopped" +
                    if (errors.isEmpty()) "" else " Errors:\n" + errors.joinToString("\n") { "- $it" },
                verification = summary,
            )
            return ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.VERIFICATION_FAILED)
        }
        val untested = if (outcome == null || outcome is TurnVerifier.Outcome.Skipped) {
            " No check command was found, so the change was not tested."
        } else {
            ""
        }
        val okResult = state.buildResult(
            success = true,
            response = "Changes applied. $stopped$untested",
            incomplete = false,
            verification = summary,
        )
        return ctx.turnPersistence.finish(okResult, persistAssistantMessage = true)
    }

    private suspend fun buildPrompt(
        taskId: String,
        mode: TaskMode,
        currentIteration: Int = 0,
        maxIterations: Int = 50,
        userContextRefs: List<ContextReference> = emptyList(),
        runProfile: TurnRunProfile = TurnRunProfile.DEFAULT,
        profileOverrides: TurnProfileOverrides? = null,
        writeToolsExecutedInTurn: Int = 0,
        useNativeTools: Boolean = false,
        nativeToolSchemas: List<ToolSchema>? = null,
        agentName: String? = null,
        sessionId: String? = null,
        modelId: String? = null,
        runId: String? = null,
        turnStartedAtMs: Long? = null,
        maxTurnMinutes: Long = 0L,
    ): TurnPrompt {
        val turnPrompt = turnPromptBuilder.buildPrompt(
            taskId = taskId,
            mode = mode,
            currentIteration = currentIteration,
            maxIterations = maxIterations,
            userContextRefs = userContextRefs,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            writeToolsExecutedInTurn = writeToolsExecutedInTurn,
            nativeToolsActive = useNativeTools,
            nativeToolSchemas = nativeToolSchemas,
            agentName = agentName,
            sessionId = sessionId,
            modelId = modelId,
            runId = runId,
            turnStartedAtMs = turnStartedAtMs,
            maxTurnMinutes = maxTurnMinutes,
        )

        val contextTrace = turnPromptBuilder.getLastContextTrace()
        if (contextTrace != null) {
            val systemTokens = tokenEstimator.estimateString(turnPrompt.systemPrompt)
            val messagesTokens = turnPrompt.messages.sumOf { tokenEstimator.estimateString(it.content) }
            val totalTokens = systemTokens + messagesTokens
            val toolNames = toolRegistry.getAllTools().map { it.name }
            val baseSectionTokens = turnPromptBuilder.getLastSectionTokens() ?: emptyMap()
            val sectionTokens = buildSnapshotSectionTokens(
                baseSectionTokens = baseSectionTokens,
                systemPromptTokens = systemTokens,
                messages = turnPrompt.messages,
                totalTokens = totalTokens,
            )

            onPromptSnapshot(
                PromptSnapshot(
                    taskId = taskId,
                    iteration = currentIteration,
                    systemPromptTokens = systemTokens,
                    messagesTokens = messagesTokens,
                    totalTokens = totalTokens,
                    toolCount = toolNames.size,
                    toolNames = toolNames,
                    contextTrace = contextTrace,
                    systemPromptPreview = turnPrompt.systemPrompt.take(500),
                    renderedRequest = renderTurnPromptForInspection(mode, turnPrompt),
                    sectionTokens = sectionTokens,
                )
            )
        }

        return turnPrompt
    }

    private fun renderTurnPromptForInspection(mode: TaskMode, prompt: TurnPrompt): String = buildString {
        appendLine("Mode: ${mode.name}")
        appendLine()
        appendLine("SYSTEM PROMPT (${tokenEstimator.estimateString(prompt.systemPrompt)} tokens, ${prompt.systemPrompt.length} chars):")
        appendLine(prompt.systemPrompt)
        appendLine()
        appendLine("MESSAGES (${prompt.messages.size}):")
        if (prompt.messages.isEmpty()) {
            appendLine("(none)")
        } else {
            prompt.messages.forEachIndexed { index, msg ->
                appendLine("[MESSAGE ${index + 1}] role=${msg.role}")
                appendLine(msg.content)
                appendLine()
            }
        }
        if (isNotEmpty() && last() == '\n') setLength(length - 1)
    }

    private fun buildSnapshotSectionTokens(
        baseSectionTokens: Map<String, ContextSectionTokenInfo>,
        systemPromptTokens: Int,
        messages: List<LLMMessage>,
        totalTokens: Int,
    ): Map<String, ContextSectionTokenInfo> {
        val denominator = totalTokens.coerceAtLeast(1).toDouble()
        val result = linkedMapOf<String, ContextSectionTokenInfo>()

        if (systemPromptTokens > 0) {
            result["system_prompt"] = ContextSectionTokenInfo(
                name = "System Prompt",
                tokens = systemPromptTokens,
                chars = systemPromptTokens * 4,
                percentage = systemPromptTokens / denominator * 100.0,
            )
        }

        result.putAll(baseSectionTokens)

        val userTokens = messages.filter { it.role == "user" }.sumOf { tokenEstimator.estimateString(it.content) }
        val assistantTokens = messages.filter { it.role == "assistant" }.sumOf { tokenEstimator.estimateString(it.content) }
        val otherTokens = messages.filter { it.role !in setOf("user", "assistant") }.sumOf { tokenEstimator.estimateString(it.content) }

        if (userTokens > 0) {
            result["messages_user"] = ContextSectionTokenInfo("User Messages", userTokens, userTokens * 4, userTokens / denominator * 100.0)
        }
        if (assistantTokens > 0) {
            result["messages_assistant"] = ContextSectionTokenInfo("Assistant Messages", assistantTokens, assistantTokens * 4, assistantTokens / denominator * 100.0)
        }
        if (otherTokens > 0) {
            result["messages_other"] = ContextSectionTokenInfo("Other Role Messages", otherTokens, otherTokens * 4, otherTokens / denominator * 100.0)
        }

        return result
    }

    private fun wrapListenerForToolTiming(
        listener: TurnEventListener?,
        toolStartNanos: ConcurrentHashMap<String, Long>,
        toolDurationsMs: ConcurrentHashMap<String, Long>,
    ): TurnEventListener? {
        val innerListener = listener ?: return null
        return object : TurnEventListener {
            override fun onTurnStarted(taskId: String, mode: TaskMode, runId: String, parentRunId: String?, depth: Int) {
                innerListener.onTurnStarted(taskId, mode, runId, parentRunId, depth)
            }
            override fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData) {
                toolStartNanos[toolCall.id] = System.nanoTime()
                innerListener.onToolExecutionStarted(taskId, toolCall)
            }
            override fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {
                innerListener.onToolStreamChunk(taskId, toolCallId, delta, accumulated)
            }
            override fun onToolExecutionCompleted(taskId: String, toolCall: ToolCallData, result: String, success: Boolean) {
                toolStartNanos[toolCall.id]?.let { start ->
                    toolDurationsMs[toolCall.id] = (System.nanoTime() - start) / 1_000_000
                }
                innerListener.onToolExecutionCompleted(taskId, toolCall, result, success)
            }
            override fun onStreamChunk(taskId: String, delta: String, accumulated: String) {
                innerListener.onStreamChunk(taskId, delta, accumulated)
            }
            override fun onToolBatchCompleted(taskId: String, summary: ToolBatchSummary.BatchSummary) {
                innerListener.onToolBatchCompleted(taskId, summary)
            }
            override fun onTurnCompleted(taskId: String, result: TurnResult, runId: String, parentRunId: String?, depth: Int) {
                innerListener.onTurnCompleted(taskId, result, runId, parentRunId, depth)
            }
        }
    }

    private suspend fun emitToolCalledEvents(
        taskId: String,
        toolResults: List<Pair<ToolCallData, ToolResultData>>,
        toolDurationsMs: Map<String, Long>,
        batchStartNanos: Long,
        evSessionId: String,
        evSourceAgentId: String,
        runId: String,
        parentRunId: String?,
        depth: Int,
        iteration: Int,
    ) {
        val batchDurationMs = (System.nanoTime() - batchStartNanos) / 1_000_000
        val fallbackPerToolMs = if (toolResults.isNotEmpty()) batchDurationMs / toolResults.size else 0L
        for ((toolCall, resultData) in toolResults) {
            val success = resultData.success
            val durationMs = toolDurationsMs[toolCall.id] ?: fallbackPerToolMs
            ToolUsageStats.record(
                toolName = toolCall.name,
                durationMs = durationMs,
                success = success,
                errorMessage = if (success) null else resultData.content.take(200),
            )
            emitTurnEvent(taskId) {
                AgentEvent.ToolCalled(
                    id = UUID.randomUUID().toString(),
                    sessionId = evSessionId,
                    sourceAgentId = evSourceAgentId,
                    timestamp = System.currentTimeMillis(),
                    correlationId = runId,
                    iteration = iteration,
                    toolName = toolCall.name,
                    argumentsPreview = toolCall.arguments.take(120),
                    durationMs = durationMs,
                    success = success,
                    errorMessage = if (success) null else resultData.content.take(200),
                    resultPreview = resultData.content.take(200),
                    runId = runId,
                    parentRunId = parentRunId,
                    depth = depth,
                )
            }
        }
    }

    private suspend fun handleAwaitingResponses(
        @Suppress("UNUSED_PARAMETER") taskId: String,
        toolResults: List<Pair<ToolCallData, ToolResultData>>,
        turnPersistence: TurnPersistence,
    ) {
        val bus = agentEventBus ?: return
        for ((toolCall, resultData) in toolResults) {
            val metadata = resultData.metadata?.let { TurnJsonUtils.parseJsonToMap(it) }
            if (metadata?.get("type") == "AWAITING_RESPONSE") {
                val requestId = metadata["requestId"] as? String ?: continue
                val timeout = 300_000L
                logger.info { "[AWAITING_RESPONSE] Tool ${toolCall.name} waiting for response to $requestId" }
                updateTurnState { copy(phase = TurnPhase.WAITING_FOR_PERMISSION) }

                val response = try {
                    kotlinx.coroutines.withTimeout(timeout) {
                        bus.eventsOfType<AgentEvent.DataResponse>()
                            .filter { it.requestId == requestId }
                            .first()
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    null
                }

                updateTurnState { copy(phase = TurnPhase.EXECUTING_TOOLS) }

                val responseContent = if (response != null) {
                    "Response received: ${response.response}"
                } else {
                    "No response received within timeout. Continue with available information."
                }

                turnPersistence.persist(
                    role = MessageRole.SYSTEM,
                    content = responseContent,
                    metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.PEER_RESPONSE),
                )
                logger.info { "[AWAITING_RESPONSE] Got response for $requestId: ${responseContent.take(100)}" }
            }
        }
    }

    private fun trackToolBatch(
        toolResults: List<Pair<ToolCallData, ToolResultData>>,
        errorTracker: TurnGuardrails.ToolErrorTracker,
        repetitionTracker: TurnGuardrails.TurnRepetitionTracker,
        blockedTracker: TurnGuardrails.ConsecutiveBlockedToolTracker,
        deniedTracker: TurnGuardrails.ConsecutiveDeniedToolTracker,
        consecutiveIdenticalFailures: Int,
        lastFailureSignature: String?,
    ): ToolBatchTracking {
        @Suppress("NAME_SHADOWING")
        var consecutiveIdenticalFailures = consecutiveIdenticalFailures
        @Suppress("NAME_SHADOWING")
        var lastFailureSignature = lastFailureSignature
        var repetitionAbort: TurnGuardrails.LoopStatus.ABORT? = null
        var blockedAbort: TurnGuardrails.LoopStatus.ABORT? = null
        var deniedAbort: TurnGuardrails.LoopStatus.ABORT? = null
        var repetitionAbortToolName: String? = null
        val noopCallIds = mutableSetOf<String>()
        val judgedResults = toolResults.filterNot { (_, result) -> result.notPermitted }
        errorTracker.recordBatch(failed = judgedResults.count { (_, result) -> !result.success }, total = judgedResults.size)
        for ((toolCall, result) in toolResults) {
            if (result.noop) noopCallIds.add(toolCall.id)
            val success = result.success
            when (val deniedStatus = deniedTracker.record(result.notPermitted)) {
                is TurnGuardrails.LoopStatus.ABORT -> if (deniedAbort == null) deniedAbort = deniedStatus
                TurnGuardrails.LoopStatus.OK -> Unit
            }
            when (val blockedStatus = blockedTracker.record(result.blocked)) {
                is TurnGuardrails.LoopStatus.ABORT -> if (blockedAbort == null) blockedAbort = blockedStatus
                TurnGuardrails.LoopStatus.OK -> Unit
            }
            if (success) {
                consecutiveIdenticalFailures = 0
                lastFailureSignature = null
            } else {
                val signature = "${toolCall.name}:${toolCall.arguments.hashCode()}"
                if (signature == lastFailureSignature) {
                    consecutiveIdenticalFailures++
                } else {
                    consecutiveIdenticalFailures = 1
                    lastFailureSignature = signature
                }
            }

            val parsedArgs: Map<String, Any?>? = runCatching {
                TurnJsonUtils.parseJsonToMap(toolCall.arguments)
            }.getOrNull()
            if (parsedArgs != null) {
                val output = if (success) (result.loopSignature ?: result.content) else null
                when (val status = repetitionTracker.record(toolCall.name, parsedArgs, output, isNoopWrite = result.noop)) {
                    is TurnGuardrails.LoopStatus.ABORT ->
                        if (repetitionAbort == null) {
                            repetitionAbort = status
                            repetitionAbortToolName = toolCall.name
                        }
                    TurnGuardrails.LoopStatus.OK -> Unit
                }
            }
        }
        return ToolBatchTracking(
            repetitionAbort = repetitionAbort,
            repetitionAbortToolName = repetitionAbortToolName,
            noopCallIds = noopCallIds,
            consecutiveIdenticalFailures = consecutiveIdenticalFailures,
            lastFailureSignature = lastFailureSignature,
            blockedAbort = blockedAbort,
            deniedAbort = deniedAbort,
        )
    }

    private fun applySoftNudges(
        taskId: String,
        mode: TaskMode,
        depth: Int,
        iteration: Int,
        maxIterations: Int,
        toolCalls: List<ToolCallData>,
        noopCallIds: Set<String>,
        state: SoftNudgeState,
        turnPersistence: TurnPersistence,
    ) {
        if (mode != TaskMode.AGENT || depth != 0 || iteration >= maxIterations) return

        if (turnToolExecutor.batchMakesConsolidationProgress(toolCalls, noopCallIds)) {
            state.consecutiveGatheringCalls = 0
        } else {
            state.consecutiveGatheringCalls += turnToolExecutor.countGatheringToolCalls(toolCalls)
        }
        if (state.consecutiveGatheringCalls >= READ_ONLY_CONSOLIDATION_THRESHOLD &&
            state.consolidationNudges < MAX_CONSOLIDATION_NUDGES
        ) {
            logger.info {
                "[CONSOLIDATION_NUDGE] taskId=$taskId, gatheringCalls=${state.consecutiveGatheringCalls}, " +
                    "nudge=${state.consolidationNudges + 1}/$MAX_CONSOLIDATION_NUDGES"
            }
            turnPersistence.persist(
                role = MessageRole.SYSTEM,
                content = buildConsolidationNudge(state.consecutiveGatheringCalls),
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.GUARDIAN_NUDGE),
                toolCalls = null,
            )
            state.consolidationNudges++
            state.consecutiveGatheringCalls = 0
        }

        if (state.regenerationNudges < MAX_REGENERATION_NUDGES) {
            val regeneratedPath = TurnToolExecutor
                .fullRegenerationPaths(toolCalls, noopCallIds)
                .map { it to state.fullRegenCountByPath.merge(it, 1, Int::plus)!! }
                .firstOrNull { (_, count) -> count >= REGENERATION_NUDGE_THRESHOLD }
                ?.first
            if (regeneratedPath != null) {
                val regenCount = state.fullRegenCountByPath[regeneratedPath] ?: 0
                logger.info {
                    "[REGENERATION_NUDGE] taskId=$taskId, path=$regeneratedPath, " +
                        "regenerations=$regenCount, nudge=${state.regenerationNudges + 1}/$MAX_REGENERATION_NUDGES"
                }
                turnPersistence.persist(
                    role = MessageRole.SYSTEM,
                    content = buildRegenerationNudge(regeneratedPath, regenCount),
                    metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.GUARDIAN_NUDGE),
                    toolCalls = null,
                )
                state.regenerationNudges++
            }
        }

        if (toolCalls.any { it.name == SUBAGENT_TOOL }) {
            state.consecutiveSubagentDefinitions = 0
            state.definedSubagentNames.clear()
        } else {
            state.consecutiveSubagentDefinitions += TurnToolExecutor.subagentDefinitionCalls(toolCalls).size
            state.definedSubagentNames += TurnToolExecutor.subagentDefinitionNames(toolCalls)
        }
        if (state.consecutiveSubagentDefinitions >= TurnToolExecutor.SUBAGENT_INVOKE_NUDGE_THRESHOLD &&
            state.subagentInvokeNudges < MAX_SUBAGENT_INVOKE_NUDGES
        ) {
            logger.info {
                "[SUBAGENT_INVOKE_NUDGE] taskId=$taskId, definitions=${state.consecutiveSubagentDefinitions}, " +
                    "agents=${state.definedSubagentNames.joinToString(",")}, " +
                    "nudge=${state.subagentInvokeNudges + 1}/$MAX_SUBAGENT_INVOKE_NUDGES"
            }
            turnPersistence.persist(
                role = MessageRole.SYSTEM,
                content = buildSubagentInvokeNudge(state.definedSubagentNames.toList()),
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.GUARDIAN_NUDGE),
                toolCalls = null,
            )
            state.subagentInvokeNudges++
            state.consecutiveSubagentDefinitions = 0
        }
    }

    private fun isJsonEnvelopeFallback(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) return false
        val body = if (trimmed.startsWith("```")) trimmed.removePrefix("```").removePrefix("json").trim() else trimmed
        return body.contains("\"actions\"") && (body.contains("\"tool\"") || body.contains("\"response\""))
    }

    private fun buildConsolidationNudge(gatheringCalls: Int): String = buildString {
        appendLine("[progressive hint - lots of reading, nothing produced yet]")
        appendLine(
            "You have made $gatheringCalls information-gathering calls (reads/searches) in a row " +
                "without writing a file, persisting findings, or delivering a result."
        )
        appendLine(
            "Long read-heavy runs lose their own evidence: older tool outputs are compressed out of " +
                "your recent-work context, so by the time you try to write the result, the details " +
                "(and the file:line citations) you gathered are already gone - which is why reading " +
                "even more before consolidating does not help."
        )
        appendLine("Consolidate NOW - do at least one of:")
        appendLine(
            "  (1) persist what you have learned so far with `memory(action=\"write\", ...)` " +
                "(the key facts + exact file:line citations you need), so it survives compaction;"
        )
        appendLine(
            "  (2) start producing the requested deliverable incrementally - write a partial file / " +
                "the first sections now and extend them - instead of reading everything first;"
        )
        append(
            "  (3) if the task is too large to hold at once, finish one self-contained part " +
                "(write it out) before gathering more."
        )
    }

    private fun buildRegenerationNudge(path: String, regenerations: Int): String = buildString {
        appendLine("[progressive hint - you keep rebuilding the same file from scratch]")
        appendLine(
            "You have regenerated `$path` whole-file $regenerations times this turn " +
                "(advance_code_editing / create_new_file each rebuild the entire file)."
        )
        appendLine(
            "A write that returned a diff / line count SUCCEEDED and is complete - that diff is the " +
                "authoritative current content. Regenerating a successfully-written file without a " +
                "concrete build/test/run error pointing at it just burns another full multi-minute " +
                "generation and usually produces a DIFFERENT file (new bugs), not a better one."
        )
        appendLine("Do one of:")
        appendLine(
            "  (1) if a SPECIFIC defect remains, fix only that with a targeted edit " +
                "(`code_editing` / `multi_edit`) - do NOT rewrite the whole file;"
        )
        append(
            "  (2) otherwise the file is done - deliver your final answer now instead of " +
                "regenerating again."
        )
    }

    private fun buildSubagentInvokeNudge(agentNames: List<String>): String = buildString {
        val named = agentNames.filter { it.isNotBlank() }
        appendLine("[progressive hint - you keep defining agents but never run one]")
        appendLine(
            if (named.isEmpty()) {
                "You have called `manage_subagent` repeatedly without a single `invoke_subagent`."
            } else {
                "You have defined ${named.joinToString(", ") { "`$it`" }} with `manage_subagent`, " +
                    "but you have not run any of them."
            }
        )
        appendLine(
            "`manage_subagent` only stores a definition - it does no work. Only " +
                "`invoke_subagent(name=..., goal=...)` actually runs an agent, and a subagent is blind: " +
                "its `goal` must be self-contained (exact task, relevant paths, required output format)."
        )
        appendLine("Do one of:")
        appendLine(
            if (named.isEmpty()) {
                "  (1) run an existing agent now - `manage_subagent(action=\"list\")` shows what is available;"
            } else {
                "  (1) run `invoke_subagent(name=\"${named.first()}\", goal=\"...\")` NOW - " +
                    "dispatch several in one response if they are independent;"
            }
        )
        append(
            "  (2) or drop the delegation and do the work yourself with the read/write tools - " +
                "for a small job that is cheaper than a subagent."
        )
    }

    companion object {
        private const val SUBAGENT_TOOL = "invoke_subagent"
        private const val MAX_SUBAGENT_INVOKE_NUDGES = 1
        private const val READ_ONLY_CONSOLIDATION_THRESHOLD = 14
        private const val MAX_CONSOLIDATION_NUDGES = 2
        private const val REGENERATION_NUDGE_THRESHOLD = 3
        private const val MAX_REGENERATION_NUDGES = 1
        private const val NATIVE_TOOLS_DEMOTE_AFTER_IGNORES = 2
    }
}
