package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.debug.TurnFailureMarkerTracker
import pl.jclab.refio.core.debug.TurnNativeToolsTracker
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.debug.TurnVerificationTracker
import pl.jclab.refio.core.debug.VerificationSummary
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PendingUserMessageQueue
import pl.jclab.refio.core.services.TaskVerifier
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.hooks.HookService
import java.util.concurrent.CancellationException

private val logger = dualLogger("AgentTurnLoop")

/**
 * Handles the no-tool-calls branch of a turn iteration:
 * - mid-execution user interjections
 * - content chanting and cross-iteration text-repetition guards
 * - format retry nudges and deliverable-aware hard-fail handling
 * - task verification and completion guardians (with bounded re-entry)
 * - deterministic post-turn verification with bounded repair loop
 * - finalization and assistant response persistence
 */
internal class TurnCompletionPipeline(
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    private val configService: ConfigService,
    private val taskVerifier: TaskVerifier,
    private val toolCallParser: ToolCallParser,
    private val turnToolExecutor: TurnToolExecutor,
    private val turnResponseProcessor: TurnResponseProcessor,
    private val completionGuardians: GuardianRegistry,
    private val pendingUserMessageQueue: PendingUserMessageQueue?,
    private val hookService: HookService?,
    private val turnVerifier: TurnVerifier?,
    private val updateTurnState: (TurnStateSnapshot.() -> TurnStateSnapshot) -> Unit,
) {
    suspend fun handleNoToolCalls(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
        contentForExtraction: String,
        usedNativeChannel: Boolean,
        nativeProducedNoCall: Boolean,
        looksLikeJsonResponse: Boolean,
        jsonEnvelopeInspection: ToolCallParser.JsonEnvelopeInspection,
    ): IterationOutcome {
        val guardOutcome = handleFormatGuards(
            ctx, state, llmResponse, contentForExtraction,
            usedNativeChannel, nativeProducedNoCall, looksLikeJsonResponse, jsonEnvelopeInspection,
        )
        if (guardOutcome != null) return guardOutcome

        val guardianOutcome = runVerificationAndGuardians(ctx, state, llmResponse)
        if (guardianOutcome != null) return guardianOutcome

        val verifyOutcome = runDeterministicVerification(ctx, state, llmResponse)
        if (verifyOutcome != null) return verifyOutcome

        return finalizeSuccess(ctx, state, llmResponse)
    }

    private suspend fun handleFormatGuards(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
        contentForExtraction: String,
        usedNativeChannel: Boolean,
        nativeProducedNoCall: Boolean,
        looksLikeJsonResponse: Boolean,
        jsonEnvelopeInspection: ToolCallParser.JsonEnvelopeInspection,
    ): IterationOutcome? {
        if (pendingUserMessageQueue?.consumePending(ctx.taskId) == true) {
            logger.info { "[MID_EXEC_INPUT] New user message detected before turn completion, continuing loop (iteration=${state.iteration})" }
            ctx.turnPersistence.persist(
                role = MessageRole.SYSTEM,
                content = "[New user message above - address it before finishing]",
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.INTERJECTION_NOTICE),
                toolCalls = null,
            )
            val textResponse = toolCallParser.extractTextResponse(llmResponse.content)
            ctx.turnPersistence.persist(
                role = MessageRole.ASSISTANT,
                content = textResponse.ifEmpty { llmResponse.content },
                thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                toolCalls = null,
                tokensIn = llmResponse.usage.inputTokens,
                tokensOut = llmResponse.usage.outputTokens,
                cost = llmResponse.cost,
            )
            return IterationOutcome.Continue
        }

        val hasIncompleteJsonEnvelope =
            jsonEnvelopeInspection.hasJsonEnvelope && !jsonEnvelopeInspection.isComplete

        if (contentForExtraction.isNotBlank() && nativeProducedNoCall) {
            val chantingStatus = TurnGuardrails.ContentChantingDetector.inspect(contentForExtraction)
            if (chantingStatus is TurnGuardrails.LoopStatus.ABORT) {
                logger.warn { "[CONTENT_CHANTING] taskId=${ctx.taskId}, iteration=${state.iteration}: ${chantingStatus.reason}" }
                val result = state.buildResult(success = false, response = chantingStatus.reason)
                return IterationOutcome.Finished(
                    ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.CONTENT_CHANTING)
                )
            }
        }

        if (contentForExtraction.isNotBlank() && nativeProducedNoCall) {
            val textRepeatStatus = state.textRepetitionTracker.record(contentForExtraction)
            if (textRepeatStatus is TurnGuardrails.LoopStatus.ABORT) {
                return IterationOutcome.Finished(
                    finalizeTextRepetition(ctx, state, textRepeatStatus.reason, contentForExtraction, llmResponse)
                )
            }
        }

        val isEffectivelyEmptyEnvelope =
            jsonEnvelopeInspection.hasJsonEnvelope &&
                jsonEnvelopeInspection.isComplete &&
                !usedNativeChannel &&
                isEmptyJsonEnvelope(contentForExtraction)

        val nativeTextEmbeddedToolCall =
            state.activeNativeToolSchemas != null &&
                nativeProducedNoCall &&
                looksLikeTextEmbeddedToolCall(contentForExtraction)

        val isRepeatedPlainText =
            !looksLikeJsonResponse &&
                contentForExtraction.isNotBlank() &&
                state.recoveryState.lastPlainText?.trim() == contentForExtraction.trim()

        val nativeToolsActive = state.activeNativeToolSchemas != null
        val formatNudgeCap = LLMResponseRecovery.formatNudgeBudget(
            ctx.baseFormatNudges,
            state.fileWriteToolsExecutedInTurn > 0,
        )

        val requiresFormatRetry =
            nativeProducedNoCall &&
                contentForExtraction.isNotBlank() &&
                state.recoveryState.nudgeCount < formatNudgeCap &&
                state.iteration < ctx.maxIterations &&
                !isRepeatedPlainText &&
                (
                    isEffectivelyEmptyEnvelope ||
                        nativeTextEmbeddedToolCall ||
                        (ctx.mode == TaskMode.AGENT && !nativeToolsActive &&
                            (hasIncompleteJsonEnvelope || !looksLikeJsonResponse))
                )

        val shouldHardFailFormat =
            nativeProducedNoCall &&
                contentForExtraction.isNotBlank() &&
                !looksLikeJsonResponse &&
                !hasIncompleteJsonEnvelope &&
                (state.recoveryState.nudgeCount >= formatNudgeCap || isRepeatedPlainText)

        if (shouldHardFailFormat) {
            val deliverableProduced = TurnDeliverable.produced(
                state.fileWriteToolsExecutedInTurn,
                ctx.mode,
                contentForExtraction,
                isSubagent = ctx.runProfile == TurnRunProfile.SUBAGENT,
            )
            logger.error {
                "[FORMAT_UNRECOVERABLE] taskId=${ctx.taskId}, iteration=${state.iteration}: " +
                    "model kept returning plain text after ${state.recoveryState.nudgeCount} nudge(s) " +
                    "(repeated=$isRepeatedPlainText, toolsUsedSoFar=${state.usedTools.size}, " +
                    "writeToolsExecutedInTurn=${state.writeToolsExecutedInTurn}). " +
                    if (deliverableProduced) {
                        "A deliverable already landed - surfacing the prose and finalizing SUCCESS."
                    } else {
                        "Surfacing the prose answer and marking the turn INCOMPLETE."
                    }
            }
            val result = state.buildResult(
                success = deliverableProduced,
                response = contentForExtraction,
                incomplete = !deliverableProduced,
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(
                    result,
                    persistAssistantMessage = true,
                    stopReason = if (deliverableProduced) TurnStopReason.COMPLETED else TurnStopReason.FORMAT_NUDGE_EXHAUSTED,
                )
            )
        }

        if (requiresFormatRetry) {
            state.recoveryState.lastPlainText = contentForExtraction
            state.recoveryState.nudgeCount++
            val retryReason = when {
                isEffectivelyEmptyEnvelope -> "LLM returned empty JSON envelope (no actions, no response)"
                nativeTextEmbeddedToolCall -> "LLM emitted tool call in text content instead of native tool_calls channel"
                hasIncompleteJsonEnvelope -> "LLM returned incomplete JSON envelope"
                else -> "LLM returned plain text without JSON structure"
            }
            logger.warn {
                "[FORMAT_RETRY_NUDGE] taskId=${ctx.taskId}, iteration=${state.iteration}: " +
                    "$retryReason. Nudge=${state.recoveryState.nudgeCount}/${ctx.baseFormatNudges}, " +
                    "content='${contentForExtraction.take(80)}'"
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
                content = when {
                    nativeTextEmbeddedToolCall ->
                        "Your previous reply embedded a tool call inside the text content " +
                            "(e.g. <tool_call>...</tool_call> or {\"name\":\"...\",\"arguments\":{...}} in prose). " +
                            "Those are ignored - the harness only dispatches tool calls received through the " +
                            "provider's native tool_calls / tool_use channel. Re-emit the same intent now as a " +
                            "structured native tool call (the SDK / API does this automatically when you " +
                            "invoke a function); do not write the tool call as text."
                    hasIncompleteJsonEnvelope ->
                        "Your previous reply contained incomplete JSON. Generate the full JSON envelope again from scratch. " +
                            "Do not continue or patch the previous output. Reply with JSON only: " +
                            "{\"actions\":[{\"tool\":\"NAME\",\"args\":{...}}]," +
                            "\"response\":\"...\",\"intent\":\"implementation\"}. " +
                            "No prose, no markdown fences."
                    else ->
                        "Reply with JSON only: " +
                            "{\"actions\":[{\"tool\":\"NAME\",\"args\":{...}}]," +
                            "\"response\":\"...\",\"intent\":\"implementation\"}. " +
                            "No prose, no markdown fences."
                },
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.FORMAT_NUDGE),
                toolCalls = null,
            )
            return IterationOutcome.Continue
        }

        if (!usedNativeChannel && ctx.mode == TaskMode.AGENT && hasIncompleteJsonEnvelope) {
            logger.error {
                "[MALFORMED_JSON_ENVELOPE] taskId=${ctx.taskId}, iteration=${state.iteration}: " +
                    "assistant returned incomplete JSON after retries exhausted"
            }
            val result = state.buildResult(
                success = false,
                response = "The agent returned an incomplete JSON envelope and could not recover after retrying. " +
                    "Please rerun with the same task or switch to a more reliable model.",
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.FORMAT_UNRECOVERABLE)
            )
        }

        if (state.errorTracker.shouldAbort(ctx.config.errorRateThreshold)) {
            val result = state.buildResult(
                success = false,
                response = "Too many tool errors (${state.errorTracker.getStats()} failure rate). Task aborted.",
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.TOOL_ERROR_RATE)
            )
        }

        return null
    }

    private suspend fun runVerificationAndGuardians(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
    ): IterationOutcome? {
        val shouldRunTaskVerification =
            configService.shouldVerifyTask(ctx.taskId, state.iteration, state.writeToolsExecutedInTurn)

        val userMessageForVerification = ctx.userMessageStrategy.getUserMessage(ctx.taskId)
        if (!verifyTaskCompletionIfNeeded(
                ctx.taskId, shouldRunTaskVerification, userMessageForVerification, llmResponse.content,
                ctx.persistAgentInstanceId, ctx.persistAgentName, ctx.persistAgentDepth,
            )
        ) {
            return IterationOutcome.Continue
        }

        if (completionGuardians.isEmpty) return null

        if (state.guardianState.replenishIfSustainedProgress(
                state.usedTools.size,
                TurnGuardianState.DEFAULT_PROGRESS_RESET_THRESHOLD,
            )
        ) {
            logger.info {
                "[GUARDIAN] taskId=${ctx.taskId} re-entry budget replenished after sustained " +
                    "progress (toolsUsed=${state.usedTools.size}) - next terminal stall gets a fresh re-entry"
            }
        }
        val guardianTextResponse = toolCallParser.extractTextResponse(llmResponse.content)
        val guardianContext = GuardianContext(
            taskId = ctx.taskId,
            mode = ctx.mode,
            runProfile = ctx.runProfile,
            iteration = state.iteration,
            maxIterations = ctx.maxIterations,
            userRequest = userMessageForVerification,
            finalResponse = guardianTextResponse.ifEmpty { llmResponse.content },
            toolsUsed = state.usedTools.toList(),
            writeToolsExecutedInTurn = state.writeToolsExecutedInTurn,
            fileWriteToolsExecutedInTurn = state.fileWriteToolsExecutedInTurn,
            verificationToolsExecutedAfterWrite = state.verificationToolsExecutedAfterWrite,
            priorReentries = state.guardianState.reentryCount,
            toolsUsedSizeAtPriorReentry = state.guardianState.usedToolsAtLastReentry,
            completionCondition = taskRepository.getCompletionCondition(ctx.taskId),
            subagentHasWriteTools = ctx.subagentHasWriteTools,
        )
        when (val decision = completionGuardians.runChecks(guardianContext)) {
            is GuardianDecision.Reenter -> {
                val reportText = guardianTextResponse.ifEmpty { llmResponse.content }
                val capturedReport = state.guardianState.captureIfFirst(
                    llmResponse,
                    hasVisibleText = reportText.isNotBlank(),
                )
                state.guardianState.onReentry(state.usedTools.size)
                if (capturedReport && state.fileWriteToolsExecutedInTurn == 0) {
                    ctx.turnPersistence.persist(
                        role = MessageRole.ASSISTANT,
                        content = reportText,
                        thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                        toolCalls = null,
                        tokensIn = llmResponse.usage.inputTokens,
                        tokensOut = llmResponse.usage.outputTokens,
                        cost = llmResponse.cost,
                    )
                    state.guardianState.capturePersisted = true
                }
                if (state.activeNativeToolSchemas != null && state.fileWriteToolsExecutedInTurn == 0) {
                    logger.warn {
                        "[NATIVE_TOOLS_GUARDIAN_FALLBACK] taskId=${ctx.taskId} - guardian re-entry " +
                            "after a native no-call; retrying on the JSON contract for this turn"
                    }
                    TurnNativeToolsTracker.recordDegradation(
                        ctx.taskId,
                        TurnNativeToolsTracker.REASON_GUARDIAN_REENTRY,
                    )
                    state.activeNativeToolSchemas = null
                }
                ctx.turnPersistence.persist(
                    role = MessageRole.SYSTEM,
                    content = decision.nudge,
                    metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.GUARDIAN_NUDGE),
                    toolCalls = null,
                )
                return IterationOutcome.Continue
            }
            is GuardianDecision.Incomplete -> {
                logger.info { "[TURN_INCOMPLETE] taskId=${ctx.taskId} reason=${decision.reason}" }
                state.turnIncompleteReason = decision.reason
            }
            GuardianDecision.Pass -> Unit
        }
        return null
    }

    private suspend fun runDeterministicVerification(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
    ): IterationOutcome? {
        if (turnVerifier == null ||
            state.turnIncompleteReason != null ||
            ctx.mode != TaskMode.AGENT ||
            ctx.depth != 0 ||
            !(state.usedTools.any { turnToolExecutor.isFileWriteTool(it) } || state.changedFiles.isNotEmpty())
        ) {
            return null
        }

        updateTurnState { copy(phase = TurnPhase.FINALIZING) }
        val outcome = turnVerifier.verify(ctx.taskId)
        if (outcome.executed) state.verificationAttempts++
        state.verificationSummary = recordVerification(
            turnVerifier, ctx.taskId, outcome, state.verificationAttempts, state.verificationSummary,
        )
        if (outcome is TurnVerifier.Outcome.Failed) {
            val maxRepairRounds = turnVerifier.maxRepairRounds(ctx.taskId)
            val errorList = outcome.errors.joinToString("\n") { "- $it" }
            if (state.verificationAttempts <= maxRepairRounds && state.iteration < ctx.maxIterations) {
                logger.warn {
                    "[VERIFY] taskId=${ctx.taskId} failed (exit=${outcome.exitCode}) - " +
                        "repair round ${state.verificationAttempts}/$maxRepairRounds"
                }
                val verifyTextResponse = toolCallParser.extractTextResponse(llmResponse.content)
                ctx.turnPersistence.persist(
                    role = MessageRole.ASSISTANT,
                    content = verifyTextResponse.ifEmpty { llmResponse.content },
                    thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                    toolCalls = null,
                    tokensIn = llmResponse.usage.inputTokens,
                    tokensOut = llmResponse.usage.outputTokens,
                    cost = llmResponse.cost,
                )
                ctx.turnPersistence.persist(
                    role = MessageRole.SYSTEM,
                    content = "Verification failed (exit ${outcome.exitCode}). Errors:\n$errorList\nFix them.",
                    metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.VERIFICATION_FEEDBACK),
                    toolCalls = null,
                )
                return IterationOutcome.Continue
            }
            logger.error {
                "[VERIFY] taskId=${ctx.taskId} still failing after ${state.verificationAttempts} " +
                    "attempt(s) (maxRepairRounds=$maxRepairRounds) - finalizing VERIFICATION_FAILED"
            }
            TurnFailureMarkerTracker.record(ctx.taskId, TurnFailureMarkerTracker.VERIFICATION_FAILED)
            val result = state.buildResult(
                success = false,
                response = "Verification failed (exit ${outcome.exitCode}) after " +
                    "${state.verificationAttempts} attempt(s). Errors:\n$errorList",
                verification = state.verificationSummary,
            )
            return IterationOutcome.Finished(
                ctx.turnPersistence.finish(result, persistAssistantMessage = true, stopReason = TurnStopReason.VERIFICATION_FAILED)
            )
        }
        return null
    }

    private suspend fun finalizeSuccess(
        ctx: TurnRunContext,
        state: TurnLoopState,
        llmResponse: LLMResponse,
    ): IterationOutcome {
        updateTurnState { copy(phase = TurnPhase.FINALIZING) }
        logger.info { "[TURN_COMPLETE] taskId=${ctx.taskId}, iterations=${state.iteration}" }

        val effectiveResponse = state.guardianState.effectiveResponse(llmResponse, state.usedTools.size)
        val textResponse = toolCallParser.extractTextResponse(effectiveResponse.content)
        turnResponseProcessor.tryCreatePlanSubtasks(ctx.taskId, ctx.mode, ctx.executionMode, effectiveResponse, ctx.runProfile)

        val surfacedResponse =
            if (state.fileWriteToolsExecutedInTurn > 0 &&
                state.guardianState.restorableResponse(state.usedTools.size) != null
            ) {
                DELIVERABLE_STALL_SIGNOFF
            } else {
                textResponse.ifEmpty { effectiveResponse.content }
            }

        if (!state.guardianState.captureAlreadyFinalized(state.usedTools.size)) {
            ctx.turnPersistence.persist(
                role = MessageRole.ASSISTANT,
                content = surfacedResponse,
                thinking = turnResponseProcessor.resolveAssistantThinking(effectiveResponse),
                toolCalls = null,
                tokensIn = effectiveResponse.usage.inputTokens,
                tokensOut = effectiveResponse.usage.outputTokens,
                cost = effectiveResponse.cost,
            )
        }

        val result = state.buildResult(
            success = state.turnIncompleteReason == null,
            response = surfacedResponse,
            incomplete = state.turnIncompleteReason != null,
            verification = state.verificationSummary,
        )

        updateTurnState { copy(phase = TurnPhase.COMPLETED, tokensUsed = state.totalTokensIn + state.totalTokensOut) }
        hookService?.trigger(
            "on_agent_complete",
            mapOf(
                "taskId" to ctx.taskId,
                "mode" to ctx.mode.name,
                "iterations" to state.iteration.toString(),
                "agentName" to (ctx.profileOverrides?.subagentName ?: "default"),
            ),
        )
        return IterationOutcome.Finished(
            ctx.turnPersistence.finish(
                result,
                persistAssistantMessage = false,
                stopReason = if (state.turnIncompleteReason != null) TurnStopReason.GUARDIAN_INCOMPLETE else TurnStopReason.COMPLETED,
            )
        )
    }

    private fun finalizeTextRepetition(
        ctx: TurnRunContext,
        state: TurnLoopState,
        abortReason: String,
        contentForExtraction: String,
        llmResponse: LLMResponse,
    ): TurnResult {
        logger.warn { "[TEXT_REPETITION] taskId=${ctx.taskId}, iteration=${state.iteration}: $abortReason" }
        val repeatedText = toolCallParser.extractTextResponse(contentForExtraction)
            .ifEmpty { contentForExtraction }
        val deliverableProduced = TurnDeliverable.produced(
            state.fileWriteToolsExecutedInTurn,
            ctx.mode,
            repeatedText,
            isSubagent = ctx.runProfile == TurnRunProfile.SUBAGENT,
        )
        if (deliverableProduced) {
            turnResponseProcessor.tryCreatePlanSubtasks(ctx.taskId, ctx.mode, ctx.executionMode, llmResponse, ctx.runProfile)
        }
        val result = state.buildResult(
            success = deliverableProduced,
            response = when {
                !deliverableProduced -> abortReason
                state.fileWriteToolsExecutedInTurn > 0 -> DELIVERABLE_STALL_SIGNOFF
                else -> repeatedText
            },
            incomplete = !deliverableProduced,
        )
        return ctx.turnPersistence.finish(
            result,
            persistAssistantMessage = true,
            stopReason = if (deliverableProduced) TurnStopReason.COMPLETED else TurnStopReason.TEXT_REPETITION,
        )
    }

    internal fun recordVerification(
        turnVerifier: TurnVerifier,
        taskId: String,
        outcome: TurnVerifier.Outcome,
        attempts: Int,
        previous: VerificationSummary?,
    ): VerificationSummary {
        when (outcome) {
            is TurnVerifier.Outcome.Skipped ->
                logger.info { "[VERIFY] taskId=$taskId skipped: ${outcome.reason}" }
            is TurnVerifier.Outcome.PreExistingFailure ->
                logger.info { "[VERIFY] taskId=$taskId failure already present before the turn - finalizing without repair" }
            else -> Unit
        }
        val summary = turnVerifier.summarize(taskId, outcome, attempts, previous)
        TurnVerificationTracker.record(taskId, summary)
        return summary
    }

    private suspend fun verifyTaskCompletionIfNeeded(
        taskId: String,
        shouldRunVerification: Boolean,
        userRequestFallback: String?,
        llmContent: String,
        agentInstanceId: String? = null,
        agentName: String? = null,
        agentDepth: Int? = null,
    ): Boolean {
        if (!shouldRunVerification) return true

        val userRequest = userRequestFallback?.takeIf { it.isNotBlank() }
            ?: getLastUserMessage(taskId, agentInstanceId)
        if (userRequest == null) {
            logger.warn { "[VERIFY] No user message to verify against, treating turn as complete: taskId=$taskId" }
            return true
        }

        val verification = try {
            taskVerifier.verifyCompletion(taskId, userRequest, llmContent, agentInstanceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "[VERIFY] Verification failed to run, treating turn as complete: ${e.message}" }
            return true
        }
        if (verification.isComplete) return true

        val suggested = if (verification.suggestedActions.isNotEmpty()) {
            " Suggested: ${verification.suggestedActions.joinToString("; ")}"
        } else {
            ""
        }
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.SYSTEM,
            content = "Task verification failed: ${verification.reason}.$suggested",
            metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.VERIFICATION_FEEDBACK),
            toolCalls = null,
            agentInstanceId = agentInstanceId,
            agentName = agentName,
            agentDepth = agentDepth,
        )
        return false
    }

    internal fun getLastUserMessage(taskId: String, agentInstanceId: String? = null): String? {
        return try {
            chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId)
                .lastOrNull { it.role == MessageRole.USER }
                ?.content
        } catch (e: Exception) {
            null
        }
    }

    private fun isEmptyJsonEnvelope(content: String): Boolean {
        val trimmed = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return false
            val hasActions = listOf("actions", "tool_calls", "subtasks", "steps").any { key ->
                (obj[key] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true
            }
            if (hasActions) return false
            val hasText = listOf("response", "content", "answer", "text").any { key ->
                val v = obj[key] as? kotlinx.serialization.json.JsonPrimitive
                v != null && v.isString && v.content.isNotBlank()
            }
            !hasText
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeTextEmbeddedToolCall(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains("<tool_call", ignoreCase = true) ||
            trimmed.contains("<function_call", ignoreCase = true) ||
            trimmed.contains("</tool_call", ignoreCase = true) ||
            trimmed.contains("</function_call", ignoreCase = true)
        ) {
            return true
        }
        val hasToolCallsKey = trimmed.contains("\"tool_calls\"")
        val hasNameAndArgs = trimmed.contains("\"name\"") &&
            (trimmed.contains("\"arguments\"") || trimmed.contains("\"parameters\""))
        return hasToolCallsKey || hasNameAndArgs
    }

    companion object {
        internal const val DELIVERABLE_STALL_SIGNOFF =
            "Done - the requested file changes were written this turn. The agent's trailing follow-up " +
                "produced no further tool call and was skipped; the recorded write is the confirmation."
    }
}
