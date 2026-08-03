package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.NativeToolsFallbackTracker
import pl.jclab.refio.core.llm.ToolsNotSupportedException
import pl.jclab.refio.core.llm.nativeToolsDecisionReason
import pl.jclab.refio.core.llm.parseNativeToolsMode
import pl.jclab.refio.core.llm.shouldUseNativeTools
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.debug.TurnFailureMarkerTracker
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConversationCompactor
import pl.jclab.refio.core.services.LLMRetryHandler
import pl.jclab.refio.core.services.NoopTaskVerifier
import pl.jclab.refio.core.services.PendingUserMessageQueue
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.services.TaskVerifier
import pl.jclab.refio.core.services.TokenRatioCalibrator
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.ToolResultData
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.services.TurnLoopConfigs
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.WorkingMemoryIntegration
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolSchema
import java.util.*
import java.util.concurrent.CancellationException

// Type aliases for turn/ package classes
private typealias ToolErrorTracker = TurnGuardrails.ToolErrorTracker
private typealias TurnRepetitionTracker = TurnGuardrails.TurnRepetitionTracker

private val logger = dualLogger("AgentTurnLoop")


/**
 * TurnExecutor - Turn-based execution loop implementing Codex CLI-style pattern.
 *
 * This service implements the turn-loop pattern where:
 * 1. User sends message
 * 2. Model processes and may emit tool calls
 * 3. Tool calls are executed, results added to context
 * 4. Model continues until it responds with text (no more tool calls)
 * 5. Turn completes
 *
 * Tool results are summarized to reduce context size.
 * - Last tool result uses RAW output for precision
 * - Older tool results use summaries
 *
 * Key principles:
 * - One turn = one request from user
 * - Context grows within turn (tool calls and results)
 * - Model self-directs tool usage
 * - No separate "plan" entity - plan is just text response
 *
 * @see docs/features/0012-simple-agent-flow.md
 */
internal class TurnExecutor(
    // Core dependencies
    private val llmClient: LLMClient,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val configService: ConfigService,
    private val toolRegistry: ToolRegistry,
    private val taskVerifier: TaskVerifier = NoopTaskVerifier(),

    // turn/ package components
    private val turnPromptBuilder: TurnPromptBuilder,
    private val toolCallParser: ToolCallParser,
    private val turnToolExecutor: TurnToolExecutor,
    private val turnLLMCaller: TurnLLMCaller,
    private val turnResponseProcessor: TurnResponseProcessor,
    private val turnFinalizer: TurnFinalizer,
    /**
     * beforeFinish guardian registry. When non-empty, runs after task verification at the
     * natural completion exit and may push the loop back into another iteration with a nudge.
     * See [GuardianRegistry] / [TurnCompletionGuardian]. Empty by default — no behavior change.
     */
    private val completionGuardians: GuardianRegistry = GuardianRegistry(),

    // Optional dependencies for enhanced turn loop
    private val tokenEstimator: PromptTokenEstimator = PromptTokenEstimator(),
    private val conversationCompactor: ConversationCompactor? = null,
    private val llmRetryHandler: LLMRetryHandler? = null,
    private val workingMemoryIntegration: WorkingMemoryIntegration? = null,
    private val pendingUserMessageQueue: PendingUserMessageQueue? = null,
    private val agentEventBus: pl.jclab.refio.core.agents.events.AgentEventBus? = null,
    private val hookService: pl.jclab.refio.core.services.hooks.HookService? = null,
    private val toolPermissionsService: ToolPermissionsService? = null,
    /**
     * Deterministic post-turn verification (project build/test run by the loop code, not the
     * model) with a bounded repair loop. Null disables the step entirely. See [TurnVerifier].
     */
    private val turnVerifier: TurnVerifier? = null
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Unified tool-call extraction. Wraps [toolCallParser] + native mapping + guarded
     * Hermes / Qwen-Coder-XML recovery behind one [ToolCallExtractor.extract] call, so the turn loop
     * no longer branches "native vs JSON" to figure out what the model invoked.
     */
    private val toolCallExtractor = ToolCallExtractor(toolCallParser, toolRegistry)
    private val llmResponseRecovery = LLMResponseRecovery(toolCallParser)

    private val _turnState = MutableStateFlow(TurnStateSnapshot())
    val turnState: StateFlow<TurnStateSnapshot> = _turnState.asStateFlow()

    private val _lastPromptSnapshot = MutableStateFlow<PromptSnapshot?>(null)
    val lastPromptSnapshot: StateFlow<PromptSnapshot?> = _lastPromptSnapshot.asStateFlow()

    private fun updateTurnState(update: TurnStateSnapshot.() -> TurnStateSnapshot) {
        _turnState.value = _turnState.value.update()
    }

    /**
     * Per-turn finalization + persistence binding. execute() has ~15 exit points that all
     * called turnFinalizer.completeTurn with the same 9 threaded identifiers, and ~12
     * chatMessageRepository.create calls repeating the same agent-attribution triple.
     * Binding them once per turn removes that duplication without changing behavior.
     */
    private inner class TurnPersistence(
        val taskId: String,
        val listener: TurnEventListener?,
        val runId: String,
        val parentRunId: String?,
        val depth: Int,
        val subagentMetadata: String?,
        val agentInstanceId: String?,
        val agentName: String?,
        val agentDepth: Int?,
    ) {
        /**
         * Every terminal exit of [execute] goes through here, which is why the running state is
         * cleared here rather than at each of the ~20 return sites - only a handful of them used
         * to do it, so an abort left the UI showing a live step and a Stop button for an agent
         * that had already finished.
         *
         * A nested turn keeps the state alone: it shares this flow with the parent, which is
         * still running and whose next iteration overwrites it anyway.
         */
        fun finish(result: TurnResult, persistAssistantMessage: Boolean): TurnResult =
            turnFinalizer.completeTurn(
                taskId, result, listener, runId, parentRunId, depth,
                persistAssistantMessage = persistAssistantMessage,
                metadata = subagentMetadata,
                agentInstanceId = agentInstanceId,
                agentName = agentName,
                agentDepth = agentDepth,
            ).also { if (depth == 0) updateTurnState { TurnStateSnapshot() } }

        fun persist(
            role: MessageRole,
            content: String,
            thinking: String? = null,
            toolCalls: List<ToolCallData>? = null,
            tokensIn: Int? = null,
            tokensOut: Int? = null,
            cost: Double? = null,
            metadata: String? = null,
        ) {
            chatMessageRepository.create(
                taskId = taskId,
                role = role,
                content = content,
                thinking = thinking,
                toolCalls = toolCalls,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                cost = cost,
                metadata = metadata,
                agentInstanceId = agentInstanceId,
                agentName = agentName,
                agentDepth = agentDepth,
            )
        }
    }

    // Type aliases for guardrails classes - using turn/ package implementations
    // Note: Using full qualified names instead of nested typealiases (not supported in Kotlin classes)

    /**
     * Strategy interface for obtaining user message during task verification.
     * runTurn has direct userInput, continueTurn must fetch from history.
     */
    internal fun interface UserMessageStrategy {
        suspend fun getUserMessage(taskId: String): String?
    }

    /**
     * Turn source for logging differences.
     */
    internal enum class TurnSource {
        RUN,
        CONTINUE
    }

    /**
     * Extracted common turn loop logic shared by runTurn and continueTurn.
     *
     * This method contains the core while loop that:
     * 1. Builds prompt from conversation history
     * 2. Calls LLM
     * 3. Executes tool calls (if any)
     * 4. Handles loop detection, error tracking
     * 5. Returns when model responds with text
     *
     * @param source RUN or CONTINUE - affects some logging messages
     * @param userMessageStrategy Strategy for obtaining user message during task verification
     */
    /**
     * Emit a Turn lifecycle event to AgentEventBus if available.
     * Non-fatal on failure (GUI visualization is best-effort).
     */
    private suspend fun emitTurnEvent(
        @Suppress("UNUSED_PARAMETER") taskId: String,
        build: () -> pl.jclab.refio.core.agents.events.AgentEvent
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
        userContextRefs: List<pl.jclab.refio.api.models.ContextReference>,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        source: TurnSource,
        userMessageStrategy: UserMessageStrategy,
        emitSessionId: String? = null,
        emitSourceAgentId: String? = null,
        /** Stable agent name for A2A routing — injected into tool params as AGENT_NAME. */
        agentName: String? = null
    ): TurnResult {
        // sessionId/sourceAgentId used in AgentEvent emissions. Default to taskId so
        // single-agent sessions remain self-contained; multi-agent overrides with parent ids.
        val evSessionId = emitSessionId ?: taskId
        val evSourceAgentId = emitSourceAgentId ?: taskId
        // Initialize loop variables
        var iteration = 0
        val config = TurnLoopConfigs.forMode(mode)
        val maxIterations = turnLLMCaller.resolveMaxIterations(config, profileOverrides)

        // Marks this run terminal for the Agents Graph. The per-iteration TurnEnded only closes an
        // iteration span (isFinal=false); without a final one, the graph node - keyed by runId for
        // subagents and by the session for the top level - is never flipped off RUNNING and lingers
        // as "[RUNNING]" long after the turn finished. Emitted at every turn exit below.
        // durationMs=0 so it doesn't double-count the iteration duration already reported.
        suspend fun emitTurnFinal(success: Boolean) {
            emitTurnEvent(taskId) {
                pl.jclab.refio.core.agents.events.AgentEvent.TurnEnded(
                    id = UUID.randomUUID().toString(),
                    sessionId = evSessionId,
                    sourceAgentId = evSourceAgentId,
                    timestamp = System.currentTimeMillis(),
                    correlationId = runId,
                    iteration = iteration,
                    durationMs = 0,
                    isFinal = true,
                    success = success,
                    runId = runId,
                    parentRunId = parentRunId,
                    depth = depth,
                )
            }
        }
        val errorTracker = ToolErrorTracker(windowSize = config.errorWindowSize)
        // Unified repetition tracker — catches two overlapping "stuck on same object"
        // failure modes with a single state: (a) the same (tool, target) pair invoked
        // many times total, and (b) the same tool producing byte-identical output on
        // successive successful runs. See TurnGuardrails.TurnRepetitionTracker.
        //
        // Subagent budget control: when depth >= 1 we tighten the byte-identical abort
        // threshold from 4 → 2 for WRITE/EXEC tools (edits, run_terminal_command, run_code,
        // http_request). Subagents have narrower goals and smaller token budgets, so a
        // write/poll loop repeating identical output twice is a strong "no progress" signal.
        // READ-ONLY tools keep the lenient default of 4 (readOnlyIdenticalOutputAbortThreshold):
        // a subagent re-reading the same file to double-check is benign, and threshold 2 was
        // false-killing legitimate review subagents (all three died on read_file@ChatService.kt).
        val repetitionTracker = TurnRepetitionTracker(
            identicalOutputAbortThreshold = if (depth >= 1) 2 else 4
        )
        // Cross-iteration assistant-text repetition guard: catches the model repeating the
        // SAME no-tool-call text on successive terminal points (e.g. after a guardian / format
        // nudge re-entry) — invisible to [repetitionTracker] (records only on tool execution)
        // and to ContentChantingDetector (intra-response only). See TurnGuardrails.
        val textRepetitionTracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
        // Hard backstop for a model that keeps calling tools it does not have (profile-blocked).
        // The error-rate window dilutes and the definitive-loop signature resets on varying args,
        // so a runaway "wrong toolset" loop needs its own arg-independent counter. See TurnGuardrails.
        val blockedTracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
        // Whether this subagent can produce a file/exec deliverable at all. A read-only subagent
        // (no write/exec tool in its profile) can only deliver prose, so the completion judge must
        // not re-enter it once it produced a substantial reply — there is no "delivering" tool call
        // to demand. Safe internal tools (think/tasks/memory) do NOT count as write capability.
        // Computed once (the profile is static); true for top-level runs (guardian ignores it there).
        val subagentHasWriteTools = if (runProfile == TurnRunProfile.SUBAGENT && profileOverrides != null) {
            turnPromptBuilder.resolveToolsForProfile(mode, taskId, profileOverrides).any {
                it.mode == pl.jclab.refio.core.tools.base.ToolMode.WRITE &&
                    it.name.lowercase() !in pl.jclab.refio.core.subagents.SubagentToolFilter.SYSTEM_TOOLS
            }
        } else {
            true
        }
        // Counter for write tools executed in the current turn — still tracked because
        // buildPrompt and the completion guardians both want to know.
        var writeToolsExecutedInTurn = 0
        // Strict deliverable signal: only real FILE edits/creates, NOT run_terminal_command/run_code
        // (mode=WRITE for approval but produce no file). This is what the deliverable-aware
        // finalization checks, so a `mkdir`-and-stall is not mistaken for a completed turn.
        var fileWriteToolsExecutedInTurn = 0
        var verificationToolsExecutedAfterWrite = 0
        // "Read forever, never deliver" soft guard: consecutive information-gathering calls
        // (reads/searches) with no write/persist/deliver in between. A long read-only spree
        // loses its own evidence — older tool outputs get compressed out of RECENT_WORK before
        // the model writes anything — so we nudge it to consolidate (persist to memory / deliver
        // incrementally). Resets on any progress; bounded to MAX_CONSOLIDATION_NUDGES.
        var consecutiveGatheringCalls = 0
        var consolidationNudgeCount = 0
        // "Regenerate the same file forever" soft guard: how many times each path has been
        // rebuilt whole-file (advance_code_editing / create_new_file) THIS turn. A successful
        // write is complete — its diff is authoritative — so a 2nd from-scratch regeneration of a
        // path, absent a concrete build/test error, wastes a full multi-minute generation when a
        // targeted edit would do. On the repeat we nudge toward code_editing/deliver. Bounded to
        // MAX_REGENERATION_NUDGES; per-path counts persist for the whole turn (one user request).
        val fullRegenCountByPath = mutableMapOf<String, Int>()
        var regenerationNudgeCount = 0
        // "Define agents forever, never run one" soft guard: consecutive manage_subagent
        // create/update calls with no invoke_subagent in between. Defining an agent produces
        // nothing on its own, so this pattern means the model mistook setup for the work.
        // Resets on any invoke_subagent; bounded to MAX_SUBAGENT_INVOKE_NUDGES per turn.
        var consecutiveSubagentDefinitions = 0
        val definedSubagentNames = linkedSetOf<String>()
        var subagentInvokeNudgeCount = 0
        // Definitive-loop guard: counts consecutive failures of the SAME (tool + args).
        // Resets whenever arguments change, a different tool is used, or any tool succeeds.
        // Catches true retry loops while allowing the agent to explore with varied calls.
        var consecutiveIdenticalFailures = 0
        var lastFailureSignature: String? = null
        // Guardian re-entry state — re-entry counter, snapshot of usedTools.size at the last
        // re-entry, and the first terminal answer a re-entry discarded (capture-once + restore).
        // See [TurnGuardianState] for the full rationale behind the capture-once policy.
        val guardianState = TurnGuardianState()
        // Set when a completion guardian marks the turn INCOMPLETE (request not delivered and no
        // further re-entry will help). Non-null → the final TurnResult carries incomplete=true so
        // CoreSessionService records the task as INCOMPLETE instead of silently SUCCESS.
        var turnIncompleteReason: String? = null
        // Deterministic post-turn verification state: number of verification command executions
        // so far (initial run + re-runs after repair rounds) and the summary of the latest
        // verification outcome, carried into the final TurnResult. See [TurnVerifier].
        var verificationAttempts = 0
        var verificationSummary: pl.jclab.refio.core.debug.VerificationSummary? = null
        // Plain-text guard (AGENT mode only): counts nudges sent when the model replies with
        // prose instead of the required JSON envelope. Bounded to 2 — if the model cannot
        // recover after two explicit reminders, further retries won't help and we fall
        // through to normal finalization instead of spinning. Weaker models (e.g. qwen3.5:9b)
        // under context pressure routinely drop format for one iteration; a single short
        // nudge usually brings them back. Without this guard the loop exits as `success=true`
        // on the first plain-text response, silently abandoning mid-task work.
        // NOTE: Nudges are skipped when the model previously executed tool calls — plain text
        // after successful tool usage is treated as intentional completion, not format loss.
        // Shared nudge budget for both empty-content (delegated to [LLMResponseRecovery]) and
        // broken-format sites below; one counter so the combined max-2-nudges policy holds.
        val recoveryState = RecoveryState()
        var totalTokensIn = 0
        var totalTokensOut = 0
        var totalCost = 0.0
        val usedTools = mutableListOf<String>()
        // Last substantial assistant prose seen this turn. Streamed text has no DB twin until the
        // turn finalizes cleanly, so an abort/cancel exit that persists a generic string would make
        // the report the user was watching vanish (0115 left this gap on the abort paths). Captured
        // each iteration; used by the cancel / max-iterations exits to persist what was on screen.
        var lastStreamedAssistantText: String? = null
        val maxConsecutiveIdenticalFailures = configService.getTyped(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS, taskId)
        val subagentMetadata: String? = if (runProfile == TurnRunProfile.SUBAGENT) {
            val name = profileOverrides?.subagentName ?: "subagent"
            """{"subagent_name":"$name"}"""
        } else null

        // When running as a subagent, persist messages with agentName / agentDepth so the
        // IntelliJ chat bubble renderer groups them under a per-agent header.
        // agentInstanceId isolates the subagent's chat history from the parent and from
        // sibling subagents — see ChatMessageRepository.findHistoryForInvocation.
        val persistAgentInstanceId: String? = resolveSubagentInstanceId(profileOverrides)
        val persistAgentName: String? = profileOverrides?.subagentName
        val persistAgentDepth: Int? = if (persistAgentName != null) (profileOverrides?.depth ?: 0) + 1 else null

        val turnPersistence = TurnPersistence(
            taskId, listener, runId, parentRunId, depth,
            subagentMetadata, persistAgentInstanceId, persistAgentName, persistAgentDepth,
        )

        // For subagent turns, wrap the caller's streamCallback so each token delta is ALSO
        // published as AgentEvent.StreamChunk with runId/depth/agentName. CoreSessionService
        // subscribes to these events to render a per-agent streaming bubble that updates live
        // while the subagent's LLM is still generating. Top-level turns skip the wrapper — their
        // deltas already feed the main streaming message directly via streamCallback.
        // Forward native tool-call progress snapshots to the TurnEventListener (headless/lifecycle
        // observability) while still chaining to the caller's UI streamCallback. The UI
        // (WorkflowEventListener) gets the same snapshots via CoreSessionService's own streamCallback.
        val baseStreamCallback: StreamCallback? = if (streamCallback != null || listener != null) {
            { chunk ->
                chunk.toolCallProgress?.let { p ->
                    listener?.onLlmToolCallProgress(taskId, p.index, p.name, p.accumulatedArguments)
                }
                streamCallback?.invoke(chunk)
            }
        } else {
            null
        }

        val effectiveStreamCallback: StreamCallback? = if (persistAgentName != null && agentEventBus != null) {
            val bus = agentEventBus
            val wrappedName = persistAgentName
            val wrappedDepth = persistAgentDepth ?: 1
            val wrappedRunId = runId
            val wrappedSessionId = evSessionId
            val wrappedSourceAgentId = evSourceAgentId
            val delegate = baseStreamCallback
            { chunk ->
                delegate?.invoke(chunk)
                bus.tryEmit(
                    pl.jclab.refio.core.agents.events.AgentEvent.StreamChunk(
                        id = UUID.randomUUID().toString(),
                        sessionId = wrappedSessionId,
                        sourceAgentId = wrappedSourceAgentId,
                        timestamp = System.currentTimeMillis(),
                        correlationId = wrappedRunId,
                        delta = chunk.delta,
                        accumulated = chunk.accumulated,
                        isComplete = chunk.isComplete,
                        runId = wrappedRunId,
                        depth = wrappedDepth,
                        agentName = wrappedName,
                    )
                )
            }
        } else {
            baseStreamCallback
        }
        val (effectiveModel, effectiveProvider) = turnLLMCaller.resolveModelSelection(
            mode = mode,
            taskId = taskId,
            model = model,
            provider = provider,
            profileOverrides = profileOverrides
        )
        val responseFormat = turnLLMCaller.resolveResponseFormat(mode, effectiveProvider)

        // Resolve native tools mode once per turn (not per iteration — model/config don't change mid-turn)
        val initialNativeToolSchemas = resolveInitialNativeToolSchemas(
            taskId, mode, effectiveModel, effectiveProvider, profileOverrides
        )
        var activeNativeToolSchemas = initialNativeToolSchemas
        // R1: consecutive iterations where native tools were offered but the model
        // ignored the native channel and emitted a {response,actions} JSON envelope in text instead.
        // A capable native model occasionally mirrors the envelope shown as a negative example in the
        // prompt; demoting it off native on the FIRST such slip (the old one-strike behavior) was too
        // sticky and persisted to disk. Only a streak of NATIVE_TOOLS_DEMOTE_AFTER_IGNORES demotes;
        // any iteration that uses the native channel resets it.
        var nativeIgnoredStreak = 0

        // Wire turn state updater so TurnToolExecutor can set WAITING_FOR_PERMISSION
        turnToolExecutor.turnStateUpdater = { phase ->
            updateTurnState { copy(phase = phase) }
        }

        // Per-tool timing map for emitting ToolCalled events with accurate durations.
        // Populated by the wrapped tool listener below; consumed after executeToolCalls returns.
        val toolStartNanos = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val toolDurationsMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        // Per-session cost ceiling (--max-cost / agent.max_cost_usd). 0 = disabled. Config doesn't
        // change mid-turn, so resolve once here and re-read only the live task cost per iteration.
        val maxCostUsd = configService.getTyped(ConfigKeys.AGENT_MAX_COST_USD, taskId)

        try {
            while (iteration < maxIterations) {
                if (GlobalMetrics.isCancelled()) {
                    throw CancellationException("Operation cancelled by user")
                }
                iteration++

                logger.info { "[TURN_ITERATION] taskId=$taskId, iteration=$iteration/$maxIterations" }

                // Hard cost ceiling: stop BEFORE paying for another LLM call once the
                // session's live cost (auto-incremented in LLMClient.complete) reaches --max-cost.
                if (maxCostUsd > 0.0) {
                    val currentCostUsd = taskRepository.findById(taskId)?.costUsd ?: 0.0
                    if (CostLimitGuard.isExceeded(currentCostUsd, maxCostUsd)) {
                        logger.warn { "[COST_LIMIT_EXCEEDED] taskId=$taskId, cost=$currentCostUsd >= max=$maxCostUsd — aborting turn" }
                        val result = TurnResult(
                            success = false,
                            response = "COST_LIMIT_EXCEEDED: session cost \$%.4f reached the --max-cost ceiling of \$%.4f. Stopping the turn."
                                .format(currentCostUsd, maxCostUsd),
                            iterations = iteration,
                            tokensIn = totalTokensIn,
                            tokensOut = totalTokensOut,
                            cost = totalCost,
                            toolsUsed = usedTools.distinct(),
                            incomplete = true
                        )
                        return turnPersistence.finish(result, persistAssistantMessage = true)
                    }
                }

                // Emit TurnStarted for Session Trace panel
                val iterationStartMs = System.currentTimeMillis()
                emitTurnEvent(taskId) {
                    pl.jclab.refio.core.agents.events.AgentEvent.TurnStarted(
                        id = UUID.randomUUID().toString(),
                        sessionId = evSessionId,
                        sourceAgentId = evSourceAgentId,
                        timestamp = iterationStartMs,
                        correlationId = runId,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        mode = mode.name,
                        runId = runId,
                        parentRunId = parentRunId,
                        depth = depth
                    )
                }

                // Track turn iteration
                val iterationToken = GlobalMetrics.beginOperation(
                    OperationInfo.TurnLoop(iteration, maxIterations, mode.name)
                )

                try {
                    // Build prompt from conversation history
                    updateTurnState { copy(phase = TurnPhase.BUILDING_PROMPT, iteration = iteration, maxIterations = maxIterations, taskId = taskId) }
                    GlobalMetrics.setCurrentOperation(
                        OperationInfo.TurnBuildingPrompt(iteration, turnPromptBuilder.getHistorySize(taskId))
                    )

                    val iterationNativeToolSchemas = activeNativeToolSchemas
                    val useNativeTools = iterationNativeToolSchemas != null

                    // Auto-compact if context window is filling.
                    // ModelWindow.resolve honors the user's provider override
                    // (PROVIDER_OLLAMA_CONTEXT_SIZE etc.) — getSafeTokenLimit alone did not,
                    // which is why compaction never fired when the user shrunk the Ollama window.
                    if (config.enableAutoCompaction && conversationCompactor != null) {
                        val maxTokens = pl.jclab.refio.core.llm.ModelWindow.resolve(
                            provider = effectiveProvider,
                            model = effectiveModel,
                            configService = configService,
                            taskId = taskId,
                        )
                        val tempPrompt = buildPrompt(
                            taskId, mode, iteration, maxIterations,
                            userContextRefs, runProfile, profileOverrides,
                            writeToolsExecutedInTurn, useNativeTools,
                            nativeToolSchemas = iterationNativeToolSchemas,
                            agentName = agentName, sessionId = evSessionId, modelId = effectiveModel, runId = runId
                        )
                        val (fits, estimated) = tokenEstimator.checkFits(tempPrompt, maxTokens, provider = effectiveProvider)

                        if (!fits) {
                            conversationCompactor.maybeCompact(
                                taskId = taskId,
                                currentTokens = estimated,
                                maxTokens = maxTokens,
                                threshold = config.compactionThreshold
                            )
                        }
                    }

                    var prompt = buildPrompt(
                        taskId, mode, iteration, maxIterations,
                        userContextRefs, runProfile, profileOverrides,
                        writeToolsExecutedInTurn, useNativeTools,
                        nativeToolSchemas = iterationNativeToolSchemas,
                        agentName = agentName, sessionId = evSessionId, modelId = effectiveModel, runId = runId
                    )

                    // Call LLM
                    updateTurnState { copy(phase = TurnPhase.CALLING_MODEL) }
                    GlobalMetrics.setCurrentOperation(OperationInfo.TurnLLMCall(iteration, mode.name))

                    val llmCallStartNanos = System.nanoTime()
                    val llmCall = callModelWithNativeFallback(
                        taskId = taskId,
                        mode = mode,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        userContextRefs = userContextRefs,
                        runProfile = runProfile,
                        profileOverrides = profileOverrides,
                        writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                        agentName = agentName,
                        sessionId = evSessionId,
                        runId = runId,
                        config = config,
                        effectiveModel = effectiveModel,
                        effectiveProvider = effectiveProvider,
                        responseFormat = responseFormat,
                        streamCallback = effectiveStreamCallback,
                        initialPrompt = prompt,
                        initialNativeToolSchemas = activeNativeToolSchemas,
                        guardianHasRestorableAnswer = guardianState.restorableResponse(usedTools.size) != null
                    )
                    // The helper owns the retry loop, so its final prompt / schema state has to
                    // come back out: a fallback there drops native tools for the rest of the turn.
                    var llmResponse = llmCall.response
                    prompt = llmCall.prompt
                    activeNativeToolSchemas = llmCall.nativeToolSchemas
                    val llmDurationMs = (System.nanoTime() - llmCallStartNanos) / 1_000_000

                    // Preserve the last substantial assistant PROSE (not a tool-call envelope) so an
                    // abort/cancel exit can persist what the user was watching instead of a generic
                    // string. Extract the text response so a JSON envelope isn't stored raw.
                    val iterationAssistantText = toolCallParser.extractTextResponse(llmResponse.content)
                    if (iterationAssistantText.length >= TurnDeliverable.PLAN_DELIVERABLE_MIN_CHARS) {
                        lastStreamedAssistantText = iterationAssistantText
                    }

                    // Closed-loop chars/token calibration: feed the real
                    // input-token count back so the next turn's budget math self-corrects per model.
                    val promptChars = prompt.systemPrompt.length +
                        prompt.messages.sumOf { it.content.length }
                    TokenRatioCalibrator.observe(effectiveModel, promptChars, llmResponse.usage.inputTokens)

                    // Generic context-overflow guard for providers that report the
                    // TRUE pre-truncation input count (cloud: OpenAI/Anthropic/Gemini). Ollama is
                    // covered separately by its pre-send estimate (its returned usage is already
                    // post-truncation, so it can't trip this). Never let a too-large prompt pass as
                    // a silent success — warn loudly and flag the run.
                    // Resolve the window via ModelWindow (the single window resolver, same as the
                    // context budget and auto-compaction above). getSafeTokenLimit used a hardcoded
                    // per-model table that fell back to 128k for any model not listed (every newer
                    // OpenRouter model), so it false-flagged overflow on prompts the provider actually
                    // accepted while the budget math sized the window correctly.
                    val contextWindow = pl.jclab.refio.core.llm.ModelWindow.resolve(
                        provider = effectiveProvider,
                        model = effectiveModel,
                        configService = configService,
                        taskId = taskId,
                    )
                    if (llmResponse.usage.inputTokens > contextWindow) {
                        logger.warn {
                            "[CTX] overflow: input=${llmResponse.usage.inputTokens} > window=$contextWindow " +
                                "model=$effectiveProvider/$effectiveModel"
                        }
                        pl.jclab.refio.core.debug.ContextOverflowTracker.markOverflow(taskId)
                    }

                    // Emit LLMCallCompleted for Session Trace panel / cost analytics
                    emitTurnEvent(taskId) {
                        pl.jclab.refio.core.agents.events.AgentEvent.LLMCallCompleted(
                            id = UUID.randomUUID().toString(),
                            sessionId = evSessionId,
                            sourceAgentId = evSourceAgentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = runId,
                            iteration = iteration,
                            model = effectiveModel ?: "unknown",
                            provider = effectiveProvider,
                            tokensIn = llmResponse.usage.inputTokens,
                            tokensOut = llmResponse.usage.outputTokens,
                            costUsd = llmResponse.cost,
                            durationMs = llmDurationMs,
                            finishReason = llmResponse.finishReason,
                            runId = runId,
                            parentRunId = parentRunId,
                            depth = depth
                        )
                    }

                    if (GlobalMetrics.isCancelled()) {
                        throw CancellationException("Operation cancelled by user")
                    }

                    totalTokensIn += llmResponse.usage.inputTokens
                    totalTokensOut += llmResponse.usage.outputTokens
                    totalCost += llmResponse.cost

                    // Per-iteration task metrics are auto-incremented inside LLMClient.complete()
                    // via the taskId TurnLLMCaller passes; no manual increment here.

                    // Populate per-session metrics for Session Trace footer / cost analytics
                    GlobalMetrics.forAgent(taskId).recordTokens(
                        tokensIn = llmResponse.usage.inputTokens,
                        tokensOut = llmResponse.usage.outputTokens,
                        costUsd = llmResponse.cost
                    )
                    // Feed global model analytics (Debug panel)
                    pl.jclab.refio.core.services.monitoring.ModelUsageStats.record(
                        provider = effectiveProvider,
                        model = effectiveModel ?: "unknown",
                        tokensIn = llmResponse.usage.inputTokens,
                        tokensOut = llmResponse.usage.outputTokens,
                        costUsd = llmResponse.cost,
                        durationMs = llmDurationMs
                    )

                    // Native-tools mode: empty content + zero tool calls is NEVER legitimate.
                    // It used to be treated as "model returned final prose, no tools needed" and
                    // fell through as success=true with an empty ASSISTANT message — that silently
                    // hid Ollama context overflow (input > num_ctx → truncation → empty output).
                    // No nudge here: the model didn't pick the wrong format, it produced nothing
                    // at all. Fail loud with a diagnostic that points at the most common cause.
                    if (mode != TaskMode.CHAT
                        && llmResponse.content.isBlank()
                        && llmResponse.nativeToolCalls.isNullOrEmpty()
                        && activeNativeToolSchemas != null) {
                        return finalizeEmptyNativeResponse(
                            taskId = taskId,
                            mode = mode,
                            iteration = iteration,
                            llmResponse = llmResponse,
                            effectiveModel = effectiveModel,
                            effectiveProvider = effectiveProvider,
                            guardianState = guardianState,
                            usedTools = usedTools,
                            totalTokensIn = totalTokensIn,
                            totalTokensOut = totalTokensOut,
                            totalCost = totalCost,
                            turnPersistence = turnPersistence
                        )
                    }

                    if (mode != TaskMode.CHAT
                        && llmResponse.content.isBlank()
                        && llmResponse.nativeToolCalls.isNullOrEmpty()
                        && activeNativeToolSchemas == null) {
                        // Empty content in JSON-in-text mode — delegate the recover/nudge/give-up
                        // decision to [LLMResponseRecovery] (testable in isolation) and execute the
                        // chosen side effect here. Some Ollama setups (qwen3 with think=true) emit
                        // the JSON envelope inside `thinking` while `content` stays empty; recovery
                        // re-binds it, otherwise we nudge the model (bounded to 2) or fail loud.
                        when (val decision = llmResponseRecovery.classifyEmptyContent(
                            llmResponse,
                            mode,
                            jsonMode = true, // the enclosing `if` already requires activeNativeToolSchemas == null

                            iteration = iteration,
                            maxIterations = maxIterations,
                            state = recoveryState,
                            profileOverrides = profileOverrides,
                            hasRestorableAnswer =
                                guardianState.restorableResponse(usedTools.size) != null,
                        )) {
                            is LLMResponseRecovery.Decision.RecoverFromThinking -> {
                                logger.warn {
                                    "[TURN_EMPTY_CONTENT_RECOVERED] taskId=$taskId, iteration=$iteration, " +
                                        "thinkingLength=${llmResponse.thinking?.length ?: 0}"
                                }
                                // Re-bind llmResponse so downstream code (extractToolCalls,
                                // ChatMessage persistence, etc.) sees the recovered envelope as content.
                                llmResponse = llmResponse.copy(content = decision.newContent, thinking = null)
                                // Fall through to the regular tool-call extraction path.
                            }

                            LLMResponseRecovery.Decision.Nudge -> {
                                recoveryState.nudgeCount++
                                logger.warn {
                                    "[FORMAT_RETRY_NUDGE] taskId=$taskId, iteration=$iteration: " +
                                        "LLM returned empty content in JSON mode. " +
                                        "Nudge=${recoveryState.nudgeCount}/2, finishReason=${llmResponse.finishReason}"
                                }
                                val resolvedThinking = turnResponseProcessor.resolveAssistantThinking(llmResponse)
                                if (!resolvedThinking.isNullOrBlank()) {
                                    turnPersistence.persist(
                                        role = MessageRole.ASSISTANT,
                                        content = "",
                                        thinking = resolvedThinking,
                                        toolCalls = null,
                                        tokensIn = llmResponse.usage.inputTokens,
                                        tokensOut = llmResponse.usage.outputTokens,
                                        cost = llmResponse.cost,
                                    )
                                }
                                turnPersistence.persist(
                                    role = MessageRole.SYSTEM,
                                    content = "Your previous reply contained empty content in structured JSON mode. " +
                                        "Generate the full JSON envelope again from scratch. " +
                                        "Do not continue or patch the previous output. Reply with JSON only: " +
                                        "{\"actions\":[{\"tool\":\"NAME\",\"args\":{...}}]," +
                                        "\"response\":\"...\",\"intent\":\"implementation\"}. " +
                                        "No prose, no markdown fences.",
                                    toolCalls = null,
                                )
                                continue
                            }

                            is LLMResponseRecovery.Decision.GiveUp -> {
                                // Deliverable-aware finalization. A write/edit already executed
                                // this turn means the file deliverable is on disk and the empty
                                // JSON envelope is only a failed sign-off - reporting FAILURE then
                                // discards completed work. Observed dominant on local models: the
                                // edit landed, the model then emitted an empty structured reply and
                                // could not recover, so a correct task returned failure. Same
                                // predicate the format hard-fail and the guardian already use.
                                val deliverableProduced =
                                    TurnDeliverable.produced(
                                        fileWriteToolsExecutedInTurn,
                                        mode,
                                        "",
                                        isSubagent = runProfile == TurnRunProfile.SUBAGENT,
                                    )
                                if (deliverableProduced) {
                                    logger.warn {
                                        "[TURN_EMPTY_CONTENT_DELIVERABLE] taskId=$taskId, iteration=$iteration: " +
                                            "empty JSON envelope after retries, but a deliverable already landed " +
                                            "(writeToolsExecutedInTurn=$writeToolsExecutedInTurn, mode=$mode) - finalizing SUCCESS"
                                    }
                                    val result = TurnResult(
                                        success = true,
                                        response = "Changes applied. The model did not produce a final summary, " +
                                            "but the edits from this turn are on disk.",
                                        iterations = iteration,
                                        tokensIn = totalTokensIn,
                                        tokensOut = totalTokensOut,
                                        cost = totalCost,
                                        toolsUsed = usedTools.distinct()
                                    )
                                    return turnPersistence.finish(result, persistAssistantMessage = true)
                                }
                                // Second deliverable shape: an answer a guardian re-entry is holding.
                                // The re-entry stashes the terminal answer the user already saw, and it
                                // is also what pushes a native-channel turn onto the JSON contract. When
                                // the model then returns nothing on that contract, the stash is the only
                                // surviving record of a turn whose work is done - failing here discarded
                                // a delivered answer (observed on ornith:35b: two subagents fixed both
                                // files, the parent turn was still reported FAILED). Restore it, the way
                                // the clean finalize path does.
                                val restoredAnswer = guardianState.restorableResponse(usedTools.size)
                                    ?.let { toolCallParser.extractTextResponse(it.content).ifEmpty { it.content } }
                                    ?.takeIf { it.isNotBlank() }
                                if (restoredAnswer != null) {
                                    logger.warn {
                                        "[TURN_EMPTY_CONTENT_RESTORED] taskId=$taskId, iteration=$iteration: " +
                                            "empty JSON envelope after a guardian re-entry, restoring the " +
                                            "pre-re-entry answer instead of failing the turn"
                                    }
                                    val result = TurnResult(
                                        success = true,
                                        response = restoredAnswer,
                                        iterations = iteration,
                                        tokensIn = totalTokensIn,
                                        tokensOut = totalTokensOut,
                                        cost = totalCost,
                                        toolsUsed = usedTools.distinct()
                                    )
                                    // Already written as its own ASSISTANT row at re-entry - persisting
                                    // again would duplicate it verbatim.
                                    return turnPersistence.finish(
                                        result,
                                        persistAssistantMessage = !guardianState.captureAlreadyFinalized(usedTools.size),
                                    )
                                }
                                logger.error {
                                    "[TURN_FAILED] Empty content from model in JSON mode " +
                                        "(mode=$mode, reason=${decision.reason}, finishReason=${llmResponse.finishReason}, thinkingLength=${llmResponse.thinking?.length ?: 0})"
                                }
                                val response = if (mode == TaskMode.AGENT) {
                                    "The agent returned empty content in structured mode and could not recover after retrying. " +
                                        "Please rerun with the same task or switch to a more reliable model."
                                } else {
                                    "Model returned empty content in structured mode. " +
                                        "The selected model likely does not produce the required JSON envelope — " +
                                        "try a different model (e.g. one tuned for tool use) or simplify the request."
                                }
                                val result = TurnResult(
                                    success = false,
                                    response = response,
                                    iterations = iteration,
                                    tokensIn = totalTokensIn,
                                    tokensOut = totalTokensOut,
                                    cost = totalCost,
                                    toolsUsed = usedTools.distinct()
                                )
                                return turnPersistence.finish(result, persistAssistantMessage = true)
                            }

                            LLMResponseRecovery.Decision.NotApplicable -> {
                                // Unreachable: the enclosing `if` already matches classifyEmptyContent's
                                // applicability predicate. Defensive no-op keeps the `when` exhaustive.
                            }
                        }
                    }

                    // Check if model invoked tools — two paths:
                    // 1. Native path: llmResponse.nativeToolCalls != null (set by adapter when tools were requested)
                    // 2. JSON-in-text path: classic ToolCallParser extraction from content
                    val nativeCalls = llmResponse.nativeToolCalls
                    // Two facts derived from the native channel drive every native-vs-text branch
                    // below. Name them once (the two are NOT the same): `usedNativeChannel` is whether
                    // the adapter returned a tool_calls list at all (it does so — possibly empty — only
                    // when native tools were sent this turn, making the native path authoritative);
                    // `nativeProducedNoCall` is whether no dispatchable native call came back (channel
                    // inactive, OR active but empty), which is what the text-recovery / format guards key on.
                    val usedNativeChannel = nativeCalls != null
                    val nativeProducedNoCall = nativeCalls.isNullOrEmpty()
                    val contentForExtraction: String
                    val toolCalls: List<ToolCallData>
                    val looksLikeJsonResponse: Boolean
                    val jsonEnvelopeInspection: pl.jclab.refio.core.services.turn.ToolCallParser.JsonEnvelopeInspection

                    // Native-requested-but-text-emitted bookkeeping (output-format selection, distinct
                    // from extraction): if we asked this model for native tools, got none back, and it
                    // instead emitted a JSON envelope in text, stop asking it for native tools so future
                    // iterations go straight to the JSON-in-text contract.
                    if (nativeProducedNoCall && activeNativeToolSchemas != null && isJsonEnvelopeFallback(llmResponse.content)) {
                        nativeIgnoredStreak++
                        if (nativeIgnoredStreak >= NATIVE_TOOLS_DEMOTE_AFTER_IGNORES) {
                            val modelKey = effectiveModel ?: "unknown"
                            NativeToolsFallbackTracker.markFallback(
                                modelKey,
                                "model ignored native tool_calls and emitted JSON envelope in text " +
                                    "$nativeIgnoredStreak times in a row"
                            )
                            logger.warn {
                                "[NATIVE_TOOLS_FALLBACK] taskId=$taskId, model=$modelKey — model ignored native " +
                                    "tool_calls $nativeIgnoredStreak times in a row; future iterations will use " +
                                    "JSON-in-text path"
                            }
                            activeNativeToolSchemas = null
                        } else {
                            logger.info {
                                "[NATIVE_TOOLS_SOFT_IGNORE] taskId=$taskId, model=${effectiveModel ?: "?"} ignored " +
                                    "native tool_calls and emitted a JSON envelope in text " +
                                    "(streak=$nativeIgnoredStreak/$NATIVE_TOOLS_DEMOTE_AFTER_IGNORES) — keeping native " +
                                    "offered; a capable model often self-corrects next iteration"
                            }
                        }
                    } else if (activeNativeToolSchemas != null) {
                        // Native still active and this iteration was NOT an envelope-ignore (the model used
                        // the native channel, recovered via another text format, or finished cleanly) —
                        // the streak must only count CONSECUTIVE ignores, so reset it.
                        nativeIgnoredStreak = 0
                    }

                    // Unified extraction: one call regardless of how the model expressed the
                    // tool call (native channel / JSON envelope / Hermes / Qwen-Coder XML). On the native
                    // path the content is left raw and envelope inspection is skipped (it is authoritative);
                    // otherwise content is preprocessed and inspected for the downstream truncation guards.
                    contentForExtraction = if (usedNativeChannel) {
                        llmResponse.content
                    } else {
                        toolCallParser.preprocessContent(llmResponse.content, taskId)
                    }
                    jsonEnvelopeInspection = if (usedNativeChannel) {
                        toolCallParser.inspectJsonEnvelope("")
                    } else {
                        toolCallParser.inspectJsonEnvelope(contentForExtraction)
                    }
                    val extraction = toolCallExtractor.extract(llmResponse, contentForExtraction, mode, profileOverrides)
                    toolCalls = when (extraction) {
                        is ExtractionResult.Calls -> {
                            logger.info {
                                "[TOOLCALL] taskId=$taskId, iteration=$iteration, source=${extraction.source}, " +
                                    "count=${extraction.calls.size}, nativeChannel=$usedNativeChannel, " +
                                    "tools=${extraction.calls.joinToString(",") { it.name }}"
                            }
                            extraction.calls
                        }
                        is ExtractionResult.None -> {
                            logger.debug {
                                "[TOOLCALL] taskId=$taskId, iteration=$iteration, none reason=${extraction.reason}, " +
                                    "nativeChannel=$usedNativeChannel, finishReason=${llmResponse.finishReason}"
                            }
                            emptyList()
                        }
                    }
                    looksLikeJsonResponse = if (usedNativeChannel) {
                        false
                    } else {
                        jsonEnvelopeInspection.hasJsonEnvelope || contentForExtraction.trim().startsWith("[")
                    }

                    // Truncated response with incomplete JSON. Detection now lives in ToolCallExtractor
                    // — it inspects the envelope and reports the distinct reason — so the
                    // turn loop only reacts to that verdict instead of re-deriving the condition here.
                    val isTruncatedWithIncompleteJson =
                        extraction is ExtractionResult.None && extraction.reason == "incomplete-json-truncated"

                    if (isTruncatedWithIncompleteJson) {
                        logger.error {
                            "[TRUNCATED_RESPONSE] Response truncated (finishReason=length) with incomplete JSON. " +
                            "Response length: ${llmResponse.content.length} chars"
                        }
                        val result = TurnResult(
                            success = false,
                            response = "The agent's response was truncated due to output length limits and could not be completed.\n\n" +
                                "**Solutions:**\n" +
                                "1. Ask for a smaller/more concise implementation\n" +
                                "2. Break the task into smaller parts\n" +
                                "3. Use a model with a larger context window",
                            iterations = iteration,
                            tokensIn = totalTokensIn,
                            tokensOut = totalTokensOut,
                            cost = totalCost,
                            toolsUsed = usedTools.distinct()
                        )
                        return turnPersistence.finish(result, persistAssistantMessage = true)
                    }

                    if (toolCalls.isNotEmpty()) {
                        // Tool-call loop detection (consecutive/total same-tool calls) is done by
                        // TurnRepetitionTracker + consecutiveIdenticalFailures + maxIterations.
                        // Earlier generations also injected soft SYSTEM nudges for format retries,
                        // read-only loops, transient HTTP errors etc.; those were all removed
                        // because they confused the model more than they helped.

                        // Save assistant message with tool calls.
                        // For both paths we persist a canonical {response, actions} JSON envelope
                        // so the UI renders a "Plan" bubble summarizing the actions. Tool calls
                        // themselves are also stored structurally in `toolCalls` for execution and
                        // adapter mapping back to native tool_use on later turns.
                        //
                        // Native path: preserve original LLM text for UI display (e.g. "I'll create
                        // the file…"). Tool calls are stored structurally in toolCallsJson so the
                        // LLM history is correctly reconstructed independently of the content field.
                        // Fall back to a JSON envelope only when the model produced no text at all.
                        logger.info { "[TOOL_CALLS] taskId=$taskId, count=${toolCalls.size}" }
                        val assistantContent = if (usedNativeChannel) {
                            // Native path: tool calls live in `toolCalls` structurally; UI renders
                            // ToolCallBubble from toolCallInfo (not from content), and Plan bubble is
                            // explicitly skipped when toolCallInfo is present (AssistantBubbleRenderer
                            // line 93). So content here is only for genuine prose like "I'll create
                            // the file…".
                            //
                            // Deepseek (and similar weak models) emit BOTH native tool_calls AND a
                            // duplicate {actions:[...]} envelope as text. Strip the envelope outright
                            // — do NOT regenerate one, because regeneration just rewrites the same
                            // noise into the next turn's history and confuses subsequent calls.
                            val raw = llmResponse.content
                            when {
                                raw.isBlank() -> ""
                                isJsonEnvelopeFallback(raw) -> {
                                    logger.warn {
                                        "[NATIVE_DUPLICATE_ENVELOPE] taskId=$taskId, model=${effectiveModel ?: "?"} — " +
                                            "stripping duplicate JSON envelope from assistant content (${raw.length} chars)"
                                    }
                                    ""
                                }
                                else -> raw
                            }
                        } else {
                            llmResponse.content
                        }
                        turnPersistence.persist(
                            role = MessageRole.ASSISTANT,
                            content = assistantContent,
                            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                            toolCalls = toolCalls,
                            tokensIn = llmResponse.usage.inputTokens,
                            tokensOut = llmResponse.usage.outputTokens,
                            cost = llmResponse.cost,
                        )

                        // Track used tool names
                        usedTools.addAll(toolCalls.map { it.name })

                        if (GlobalMetrics.isCancelled()) {
                            throw CancellationException("Operation cancelled by user")
                        }

                        // Execute tools
                        updateTurnState { copy(
                            phase = TurnPhase.EXECUTING_TOOLS,
                            activeToolName = toolCalls.firstOrNull()?.name,
                            activeToolCount = toolCalls.size
                        ) }

                        // If the caller passed a listener, wrap it to capture per-tool timings.
                        // IMPORTANT: do NOT pass a non-null listener into executeToolCalls when the
                        // caller didn't, because TurnToolExecutor takes a different (streaming) code
                        // path for certain tools when listener != null (see TurnToolExecutor.kt:424).
                        // When no caller listener is present we fall back to batch-level timing.
                        toolStartNanos.clear()
                        toolDurationsMs.clear()
                        val effectiveListener = wrapListenerForToolTiming(listener, toolStartNanos, toolDurationsMs)

                        // Batch-level timing fallback (used when no caller listener is available).
                        val batchStartNanos = System.nanoTime()

                        // Before the first file write of a verifiable turn, record whether the
                        // project's verification command passes on the UNMODIFIED tree. The
                        // finalization verify uses that baseline to avoid blaming the agent (and
                        // starting a repair loop) for a build/test command that was already red.
                        // Same gating as the finalization verify: top-level AGENT turns only.
                        if (turnVerifier != null &&
                            mode == TaskMode.AGENT &&
                            depth == 0 &&
                            toolCalls.any { turnToolExecutor.isFileWriteTool(it.name) }
                        ) {
                            turnVerifier.captureBaseline(taskId)
                        }

                        val toolResults = try {
                            turnToolExecutor.executeToolCalls(
                                taskId = taskId,
                                toolCalls = toolCalls,
                                mode = mode,
                                executionMode = executionMode,
                                listener = effectiveListener,
                                iteration = iteration,
                                config = config,
                                profileOverrides = profileOverrides,
                                runId = runId,
                                depth = depth,
                                agentName = agentName,
                                sessionId = evSessionId,
                            )
                        } catch (e: ToolRejectedException) {
                            logger.info { "[REJECTED] User rejected tool '${e.toolName}': ${e.reason ?: "no reason"}" }
                            // Persist a TOOL result message for the rejected tool call so the
                            // assistant message's toolCallsJson pairs up with an actual result
                            // on reload. Without this, MessageDispatcher derives the bubble
                            // status as EXECUTING (no result) — better than the old hardcoded
                            // COMPLETED, but still wrong for a rejection. With the result row
                            // in place, the bubble correctly renders as ✗ Failed.
                            chatMessageRepository.createToolResult(
                                taskId = taskId,
                                toolCallId = e.toolCallId,
                                subtaskId = null,
                                result = "Error: User rejected — ${e.reason ?: "no reason"}",
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                            )
                            turnPersistence.persist(
                                role = MessageRole.SYSTEM,
                                content = "User rejected tool '${e.toolName}'. Reason: ${e.reason ?: "not specified"}",
                            )
                            updateTurnState { copy(phase = TurnPhase.IDLE) }
                            val result = TurnResult(
                                success = false,
                                response = "User rejected tool '${e.toolName}'",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.toList(),
                                rejectedByUser = true,
                                rejectedToolName = e.toolName,
                                rejectionReason = e.reason
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = false)
                        }

                        // Save tool results. Forward the persisted Subtask id so the TOOL chat
                        // message can render its header with the same identifier as RECENT_WORK
                        // and WORKING_MEMORY.
                        for ((toolCall, resultData) in toolResults) {
                            chatMessageRepository.createToolResult(
                                taskId = taskId,
                                toolCallId = toolCall.id,
                                subtaskId = resultData.subtaskId,
                                result = resultData.content,
                                isSummarized = resultData.isSummarized,
                                rawOutput = resultData.rawOutput,
                                metadata = resultData.metadata,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                                tokensIn = resultData.subTokensIn,
                                tokensOut = resultData.subTokensOut,
                                cost = resultData.subCost,
                            )
                        }

                        emitToolCalledEvents(
                            taskId, toolResults, toolDurationsMs, batchStartNanos,
                            evSessionId, evSourceAgentId, runId, parentRunId, depth, iteration
                        )

                        // Working memory is recorded inside TurnToolExecutor with originId=subtaskId
                        // so every context section (MESSAGES / RECENT_WORK / WORKING_MEMORY) keys off
                        // the same subtask id. Recording here would overwrite those entries with
                        // originId=toolCall.id and desynchronize the identifiers.

                        handleAwaitingResponses(taskId, toolResults, turnPersistence)

                        // Generate batch summary for UI
                        val batchInput = toolResults.map { (call, resultData) ->
                            ToolCallWithResult(
                                toolName = call.name,
                                params = TurnJsonUtils.parseJsonToMap(call.arguments),
                                success = resultData.success,
                                resultPreview = resultData.content.take(100)
                            )
                        }
                        val batchSummary = ToolBatchSummary.summarize(batchInput)
                        listener?.onToolBatchCompleted(taskId, batchSummary)

                        val tracking = trackToolBatch(
                            toolResults, errorTracker, repetitionTracker, blockedTracker,
                            consecutiveIdenticalFailures, lastFailureSignature
                        )
                        consecutiveIdenticalFailures = tracking.consecutiveIdenticalFailures
                        lastFailureSignature = tracking.lastFailureSignature
                        val repetitionAbort = tracking.repetitionAbort
                        val repetitionAbortToolName = tracking.repetitionAbortToolName
                        val noopCallIds = tracking.noopCallIds

                        if (repetitionAbort != null) {
                            logger.warn { "[REPETITION_ABORT] taskId=$taskId, incomplete=${repetitionAbort.incomplete}, reason=${repetitionAbort.reason}" }
                            // Deliverable-aware (FM-3): a byte-identical-OUTPUT loop (NOT a
                            // no-op-write streak) on an optional VERIFICATION tool (compile/run/search/read),
                            // AFTER a deliverable already landed this turn, is the model flailing on
                            // self-verification — the real work is done, so report SUCCESS instead of failing a
                            // completed turn. A no-op-write streak (incomplete=true), a loop on a WRITE tool, or
                            // a turn with no deliverable stays a failure: there the deliverable never landed.
                            val abortToolName = repetitionAbortToolName
                            val loopedOnVerification = abortToolName != null && turnToolExecutor.isVerificationTool(abortToolName)
                            // Strict deliverable signal here (NOT writeToolsExecutedInTurn): a real FILE
                            // edit must have landed. run_terminal_command/run_code are mode=WRITE for
                            // approval but produce no file, so a loop of failing commands with no edit is
                            // NOT a delivered turn and must keep failing.
                            val realEditLanded = usedTools.any { turnToolExecutor.isFileWriteTool(it) }
                            val deliverableDespiteLoop = !repetitionAbort.incomplete &&
                                loopedOnVerification &&
                                realEditLanded
                            if (deliverableDespiteLoop) {
                                logger.warn {
                                    "[REPETITION_ABORT] taskId=$taskId — loop was on verification tool " +
                                        "'$abortToolName' and a deliverable already landed " +
                                        "(writeToolsExecutedInTurn=$writeToolsExecutedInTurn) — finalizing SUCCESS"
                                }
                                val okResult = TurnResult(
                                    success = true,
                                    response = "Changes applied. Stopped a repeated verification step " +
                                        "($abortToolName) that produced no new information; the deliverable is in place.",
                                    iterations = iteration,
                                    tokensIn = totalTokensIn,
                                    tokensOut = totalTokensOut,
                                    cost = totalCost,
                                    toolsUsed = usedTools.distinct(),
                                    incomplete = false
                                )
                                return turnPersistence.finish(okResult, persistAssistantMessage = true)
                            }
                            // Record why the turn aborted so the e2e classifier can tell a no-op-write
                            // stall from a byte-identical output loop.
                            TurnFailureMarkerTracker.record(
                                taskId,
                                if (repetitionAbort.incomplete) TurnFailureMarkerTracker.NOOP_WRITE_STALL
                                else TurnFailureMarkerTracker.LOOP_ABORTED
                            )
                            val result = TurnResult(
                                success = false,
                                response = if (repetitionAbort.incomplete) repetitionAbort.reason
                                           else "Loop detected: ${repetitionAbort.reason} Stopping the turn.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct(),
                                // Futile-edit aborts (no-op-write streak, P2) are INCOMPLETE: the
                                // deliverable was never produced, but it is abandonment, not a hard error.
                                incomplete = repetitionAbort.incomplete
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        // Hard backstop: the model repeatedly asked for tools it does not have.
                        // Abort INCOMPLETE (the deliverable was never produced) rather than let it
                        // burn iterations on a tool the harness keeps rejecting. Independent of the
                        // error-rate window and the definitive-loop signature, both of which miss
                        // this pattern (see TurnGuardrails.ConsecutiveBlockedToolTracker).
                        val blockedAbort = tracking.blockedAbort
                        if (blockedAbort != null) {
                            logger.warn {
                                "[BLOCKED_TOOL_ABORT] taskId=$taskId, depth=$depth, " +
                                    "subagent=${profileOverrides?.subagentName ?: "-"}, reason=${blockedAbort.reason}"
                            }
                            val result = TurnResult(
                                success = false,
                                response = blockedAbort.reason,
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct(),
                                incomplete = true
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        val writeToolCalls = turnToolExecutor.countWriteToolCalls(toolCalls)
                        val verificationToolCalls = turnToolExecutor.countVerificationToolCalls(toolCalls)
                        writeToolsExecutedInTurn += writeToolCalls
                        fileWriteToolsExecutedInTurn += turnToolExecutor.countFileWriteToolCalls(toolCalls)
                        if (writeToolCalls > 0) {
                            verificationToolsExecutedAfterWrite = 0
                        } else if (writeToolsExecutedInTurn > 0) {
                            verificationToolsExecutedAfterWrite += verificationToolCalls
                        }

                        // "Read forever, never deliver" soft nudge. Top-level AGENT only:
                        // subagents are frequently read-only-by-design (and already have the
                        // tighter byte-identical abort), and PLAN cannot write files so the
                        // "produce a deliverable" half of the advice is moot there.
                        if (mode == TaskMode.AGENT && depth == 0 && iteration < maxIterations) {
                            if (turnToolExecutor.batchMakesConsolidationProgress(toolCalls, noopCallIds)) {
                                consecutiveGatheringCalls = 0
                            } else {
                                consecutiveGatheringCalls += turnToolExecutor.countGatheringToolCalls(toolCalls)
                            }
                            if (consecutiveGatheringCalls >= READ_ONLY_CONSOLIDATION_THRESHOLD &&
                                consolidationNudgeCount < MAX_CONSOLIDATION_NUDGES
                            ) {
                                logger.info {
                                    "[CONSOLIDATION_NUDGE] taskId=$taskId, gatheringCalls=$consecutiveGatheringCalls, " +
                                        "nudge=${consolidationNudgeCount + 1}/$MAX_CONSOLIDATION_NUDGES"
                                }
                                turnPersistence.persist(
                                    role = MessageRole.SYSTEM,
                                    content = buildConsolidationNudge(consecutiveGatheringCalls),
                                    // Tagged as guardian_nudge so the UI renders it as a gentle
                                    // "agent guidance" note (same category: internal steering),
                                    // not a full alarming SYSTEM bubble. See MessageMetadataExtractor.
                                    metadata = """{"type":"guardian_nudge"}""",
                                    toolCalls = null,
                                )
                                consolidationNudgeCount++
                                consecutiveGatheringCalls = 0
                            }
                        }

                        // "Regenerate the same file forever" soft nudge. Top-level AGENT only
                        // (same rationale as the consolidation nudge: subagents/PLAN don't hit this
                        // pathology the same way). When the model rebuilds a path whole-file a 2nd+
                        // time this turn, remind it that a successful write is complete and a
                        // targeted edit (or delivering) beats another full regeneration. Non-blocking.
                        if (mode == TaskMode.AGENT && depth == 0 && iteration < maxIterations &&
                            regenerationNudgeCount < MAX_REGENERATION_NUDGES
                        ) {
                            val regeneratedPath = TurnToolExecutor
                                .fullRegenerationPaths(toolCalls, noopCallIds)
                                .map { it to fullRegenCountByPath.merge(it, 1, Int::plus)!! }
                                .firstOrNull { (_, count) -> count >= REGENERATION_NUDGE_THRESHOLD }
                                ?.first
                            if (regeneratedPath != null) {
                                val regenCount = fullRegenCountByPath[regeneratedPath] ?: 0
                                logger.info {
                                    "[REGENERATION_NUDGE] taskId=$taskId, path=$regeneratedPath, " +
                                        "regenerations=$regenCount, nudge=${regenerationNudgeCount + 1}/$MAX_REGENERATION_NUDGES"
                                }
                                turnPersistence.persist(
                                    role = MessageRole.SYSTEM,
                                    content = buildRegenerationNudge(regeneratedPath, regenCount),
                                    metadata = """{"type":"guardian_nudge"}""",
                                    toolCalls = null,
                                )
                                regenerationNudgeCount++
                            }
                        }

                        // "Define agents forever, never run one" soft nudge. Top-level AGENT only.
                        // manage_subagent only WRITES a definition; invoke_subagent is what runs it,
                        // and the create result already says so - a model that keeps defining has
                        // mistaken the setup for the work (observed: four turns creating and
                        // re-creating the same 'root-analyzer', zero analysis produced). Non-blocking.
                        if (mode == TaskMode.AGENT && depth == 0 && iteration < maxIterations) {
                            if (toolCalls.any { it.name == "invoke_subagent" }) {
                                consecutiveSubagentDefinitions = 0
                                definedSubagentNames.clear()
                            } else {
                                consecutiveSubagentDefinitions +=
                                    TurnToolExecutor.subagentDefinitionCalls(toolCalls).size
                                definedSubagentNames += TurnToolExecutor.subagentDefinitionNames(toolCalls)
                            }
                            if (consecutiveSubagentDefinitions >= TurnToolExecutor.SUBAGENT_INVOKE_NUDGE_THRESHOLD &&
                                subagentInvokeNudgeCount < MAX_SUBAGENT_INVOKE_NUDGES
                            ) {
                                logger.info {
                                    "[SUBAGENT_INVOKE_NUDGE] taskId=$taskId, definitions=$consecutiveSubagentDefinitions, " +
                                        "agents=${definedSubagentNames.joinToString(",")}, " +
                                        "nudge=${subagentInvokeNudgeCount + 1}/$MAX_SUBAGENT_INVOKE_NUDGES"
                                }
                                turnPersistence.persist(
                                    role = MessageRole.SYSTEM,
                                    content = buildSubagentInvokeNudge(definedSubagentNames.toList()),
                                    metadata = """{"type":"guardian_nudge"}""",
                                    toolCalls = null,
                                )
                                subagentInvokeNudgeCount++
                                consecutiveSubagentDefinitions = 0
                            }
                        }

                        if (consecutiveIdenticalFailures >= maxConsecutiveIdenticalFailures) {
                            logger.warn {
                                "[DEFINITIVE_LOOP] taskId=$taskId, signature=$lastFailureSignature, " +
                                    "consecutiveIdenticalFailures=$consecutiveIdenticalFailures/$maxConsecutiveIdenticalFailures"
                            }
                            val result = TurnResult(
                                success = false,
                                response = "Definitive loop detected: the same tool call failed $consecutiveIdenticalFailures times in a row with identical arguments. " +
                                    "The agent appears stuck retrying the same failing operation. Please rephrase your request or adjust the approach.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        if (errorTracker.shouldAbort(config.errorRateThreshold)) {
                            val result = TurnResult(
                                success = false,
                                response = "Too many tool errors (${errorTracker.getStats()} failure rate). Please review tool usage and arguments.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        // Check for mid-execution user messages after tool execution
                        if (pendingUserMessageQueue?.consumePending(taskId) == true) {
                            logger.info { "[MID_EXEC_INPUT] New user message detected after tool execution, nudging LLM (iteration=$iteration)" }
                            turnPersistence.persist(
                                role = MessageRole.SYSTEM,
                                content = "[New user message above — address it next]",
                                toolCalls = null,
                            )
                        }

                        // Continue loop - model will see the results
                    } else {
                        // No tool calls - model responded with text

                        // Before exiting, check if user sent new messages during execution
                        if (pendingUserMessageQueue?.consumePending(taskId) == true) {
                            logger.info { "[MID_EXEC_INPUT] New user message detected before turn completion, continuing loop (iteration=$iteration)" }
                            turnPersistence.persist(
                                role = MessageRole.SYSTEM,
                                content = "[New user message above — address it before finishing]",
                                toolCalls = null,
                            )
                            // Save the current assistant response before continuing
                            val textResponse = toolCallParser.extractTextResponse(llmResponse.content)
                            turnPersistence.persist(
                                role = MessageRole.ASSISTANT,
                                content = textResponse.ifEmpty { llmResponse.content },
                                thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                                toolCalls = null,
                                tokensIn = llmResponse.usage.inputTokens,
                                tokensOut = llmResponse.usage.outputTokens,
                                cost = llmResponse.cost,
                            )
                            continue
                        }

                        // Plain-text guard (AGENT mode only). When the model returns prose instead
                        // of the required JSON envelope, send one short SYSTEM reminder and retry.
                        // Bounded to 2 nudges — more than that means the model is structurally
                        // unable to produce JSON and further retries are wasted turns. Guarded by
                        // `iteration < maxIterations` so we don't nudge on the last possible turn.
                        //
                        // IMPORTANT: If the model previously executed tool calls (usedTools is
                        // non-empty), plain text is treated as intentional task completion — not a
                        // format lapse. Nudging in this case wastes 2-3 iterations regenerating
                        // the same summary. Incomplete JSON envelopes are still retried regardless,
                        // as they indicate a truncated response that needs regeneration.
                        //
                        // We intentionally do NOT persist the plain-text body as an ASSISTANT
                        // message: doing so reinjects the bad output into the next prompt and
                        // trains the model to keep emitting plain text. The model's `thinking`
                        // field (if any) is persisted separately so the user still has an audit
                        // trail of what it tried to do.
                        //
                        // Nudge message is inlined (short, verbatim) because TurnNudgeBuilder was
                        // removed — reanimating the whole nudge layer for this one case would
                        // contradict the intentional simplification in TurnGuardrails.
                        val hasIncompleteJsonEnvelope =
                            jsonEnvelopeInspection.hasJsonEnvelope && !jsonEnvelopeInspection.isComplete

                        // Content-chanting check: the model is in a runaway generation loop,
                        // repeating the same 50-char phrase 10+ times. Caught here (post-stream)
                        // rather than mid-stream to keep the streaming path simple. See
                        // TurnGuardrails.ContentChantingDetector for the heuristic rationale.
                        // We only inspect non-empty content, and only when the response did not
                        // produce a tool call (a chant inside a comment block of a tool call is
                        // typically still semantically useful — let the tool execute).
                        if (contentForExtraction.isNotBlank() &&
                            nativeProducedNoCall &&
                            toolCalls.isEmpty()
                        ) {
                            val chantingStatus = TurnGuardrails.ContentChantingDetector.inspect(contentForExtraction)
                            if (chantingStatus is TurnGuardrails.LoopStatus.ABORT) {
                                logger.warn { "[CONTENT_CHANTING] taskId=$taskId, iteration=$iteration: ${chantingStatus.reason}" }
                                val result = TurnResult(
                                    success = false,
                                    response = chantingStatus.reason,
                                    iterations = iteration,
                                    tokensIn = totalTokensIn,
                                    tokensOut = totalTokensOut,
                                    cost = totalCost,
                                    toolsUsed = usedTools.distinct()
                                )
                                return turnPersistence.finish(result, persistAssistantMessage = true)
                            }
                        }

                        // Cross-iteration text-repetition guard (see ConsecutiveTextRepetitionTracker).
                        // The model emitted a no-tool-call text; if it is byte-identical to the
                        // previous such text, the loop is stuck repeating itself (typically after a
                        // guardian/format nudge re-entry) and no further progress is possible — abort.
                        if (contentForExtraction.isNotBlank() &&
                            nativeProducedNoCall &&
                            toolCalls.isEmpty()
                        ) {
                            val textRepeatStatus = textRepetitionTracker.record(contentForExtraction)
                            if (textRepeatStatus is TurnGuardrails.LoopStatus.ABORT) {
                                logger.warn { "[TEXT_REPETITION] taskId=$taskId, iteration=$iteration: ${textRepeatStatus.reason}" }
                                // Deliverable-aware, like every other terminal abort. Repeating itself is
                                // what a model does once its work is done and it has nothing new to say -
                                // the self-verification phase after a write is where this shows up. With
                                // the file already on disk, failing here would throw away a finished turn
                                // (observed on local models building a single-page app: the write landed,
                                // the model then re-stated its sign-off twice and the turn was reported
                                // FAILED). With nothing written there is nothing to rescue, so the abort
                                // stands and the prose loop is still stopped.
                                val deliverableProduced = TurnDeliverable.produced(
                                    fileWriteToolsExecutedInTurn,
                                    mode,
                                    "",
                                    isSubagent = runProfile == TurnRunProfile.SUBAGENT,
                                )
                                val result = TurnResult(
                                    success = deliverableProduced,
                                    response = if (deliverableProduced) DELIVERABLE_STALL_SIGNOFF else textRepeatStatus.reason,
                                    iterations = iteration,
                                    tokensIn = totalTokensIn,
                                    tokensOut = totalTokensOut,
                                    cost = totalCost,
                                    toolsUsed = usedTools.distinct()
                                )
                                return turnPersistence.finish(result, persistAssistantMessage = true)
                            }
                        }
                        // Detect "effectively empty" JSON envelope: model returned a complete object
                        // like `{}` or `{"response":""}` with no actions/subtasks and no prose.
                        // Seen with MiniMax under native-tools + response_format=json_object conflict
                        // (fixed separately in TurnLLMCaller). Without this guard the turn exits as
                        // success=true with 0 actions and the user sees nothing happen.
                        val isEffectivelyEmptyEnvelope =
                            jsonEnvelopeInspection.hasJsonEnvelope &&
                                jsonEnvelopeInspection.isComplete &&
                                toolCalls.isEmpty() &&
                                !usedNativeChannel &&
                                isEmptyJsonEnvelope(contentForExtraction)
                        // Native-tools mode: model emitted a tool call in text instead of via the
                        // native tool_calls channel. Two observed patterns:
                        //   - pseudo-XML: <tool_call>...</tool_call>, <function_call>...
                        //   - inline JSON: {"name":"foo","arguments":{...}} or {"tool_calls":[...]}
                        // Parser extracts nothing from these (shape isn't {actions:[...]}), so
                        // without this guard the turn exits as success=true with 0 actions.
                        // Observed with glm-5, glm-5.1, glm-4.7 on Z.AI.
                        // Adapters now return emptyList (not null) when native tools were sent
                        // and the model produced 0 calls — both shapes mean "no native calls".
                        //
                        // This is the only kept "format" detector — it RECOVERS data (the model
                        // had real tool intent, just used the wrong channel). The previous prose-
                        // pattern detectors (`looksLikeIntentAnnouncement`, `looksLikeToolMarkerOnly`)
                        // were removed: the system prompt already tells the model not to announce
                        // intent without a tool call, and regex detection on top added redundant
                        // nudges + false-positive risk on legitimate trailing prose like "Let me
                        // summarize what I found...". Weak models that ignore the prompt rule
                        // simply exit silently; users retry. Matches Codex / Claude Code / Continue
                        // philosophy — trust the model, no algorithmic detection of "model lapsed
                        // into prose".
                        val nativeTextEmbeddedToolCall =
                            activeNativeToolSchemas != null &&
                                nativeProducedNoCall &&
                                toolCalls.isEmpty() &&
                                looksLikeTextEmbeddedToolCall(contentForExtraction)
                        val isRepeatedPlainText =
                            !looksLikeJsonResponse &&
                                contentForExtraction.isNotBlank() &&
                                recoveryState.lastPlainText?.trim() == contentForExtraction.trim()
                        // When native function-calling is active and the model returned prose
                        // without any tool_calls, that is a legitimate final answer — not a
                        // format lapse. The JSON-envelope contract only applies to the legacy
                        // JSON-in-text path. Skipping the guard here lets informational answers
                        // ("Co to za projekt?") terminate cleanly in AGENT mode instead of being
                        // nudged into a JSON envelope the model was never asked to emit.
                        val nativeToolsActive = activeNativeToolSchemas != null

                        // Format retry fires only for objectively-broken outputs:
                        //   - empty JSON envelope ({} or {"response":""}), or
                        //   - tool call embedded in text instead of native channel, or
                        //   - (AGENT, JSON-in-text mode only) missing/malformed envelope.
                        // PLAN never opted into the JSON envelope contract so it gets only the
                        // first two triggers via the native-channel path.
                        val requiresFormatRetry =
                            nativeProducedNoCall &&
                                contentForExtraction.isNotBlank() &&
                                recoveryState.nudgeCount < 2 &&
                                iteration < maxIterations &&
                                !isRepeatedPlainText &&
                                (
                                    isEffectivelyEmptyEnvelope ||
                                        nativeTextEmbeddedToolCall ||
                                        (mode == TaskMode.AGENT && !nativeToolsActive &&
                                            (hasIncompleteJsonEnvelope || !looksLikeJsonResponse))
                                )

                        // Hard-fail only after nudge bounds are exhausted on a tracked failure
                        // mode. Because requiresFormatRetry now fires only on objectively-broken
                        // outputs, recoveryState.nudgeCount can only be >= 1 when one of those was
                        // detected — legitimate plain-text final answers in native mode never
                        // trigger a nudge and so can never trip this gate. `isRepeatedPlainText`
                        // gives an early-exit when the model returns byte-identical content after
                        // the first nudge (nothing further is going to change).
                        val shouldHardFailFormat =
                            nativeProducedNoCall &&
                                contentForExtraction.isNotBlank() &&
                                !looksLikeJsonResponse &&
                                !hasIncompleteJsonEnvelope &&
                                (recoveryState.nudgeCount >= 2 || isRepeatedPlainText)

                        if (shouldHardFailFormat) {
                            // Deliverable-aware finalization. If a write/edit already executed this
                            // turn, the deliverable is on disk and this format breakdown is on a
                            // trailing (often optional "let me double-check") step — reporting the
                            // turn as a failure then punishes completed work. Observed cross-model
                            // (qwen3-coder:30b on a constant-change task: the edit landed correctly,
                            // then a malformed prose grep_search tripped this gate and the turn was
                            // recorded INCOMPLETE). Finalize SUCCESS and surface the prose. A turn
                            // that produced NO deliverable stays INCOMPLETE — a genuine format
                            // breakdown with nothing delivered, the case this gate was built for.
                            val deliverableProduced =
                                TurnDeliverable.produced(
                                    fileWriteToolsExecutedInTurn,
                                    mode,
                                    contentForExtraction,
                                    isSubagent = runProfile == TurnRunProfile.SUBAGENT,
                                )
                            logger.error {
                                "[FORMAT_UNRECOVERABLE] taskId=$taskId, iteration=$iteration: " +
                                    "model kept returning plain text after ${recoveryState.nudgeCount} nudge(s) " +
                                    "(repeated=$isRepeatedPlainText, toolsUsedSoFar=${usedTools.size}, " +
                                    "writeToolsExecutedInTurn=$writeToolsExecutedInTurn). " +
                                    if (deliverableProduced) {
                                        "A deliverable already landed — surfacing the prose and finalizing SUCCESS."
                                    } else {
                                        "Surfacing the prose answer and marking the turn INCOMPLETE."
                                    }
                            }
                            // The model produced prose but never wrapped it in the required JSON
                            // envelope / tool call. Surface that text instead of discarding it behind
                            // a generic error (which used to lose a valid deliverable).
                            val result = TurnResult(
                                success = deliverableProduced,
                                response = contentForExtraction,
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct(),
                                incomplete = !deliverableProduced
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        if (requiresFormatRetry) {
                            recoveryState.lastPlainText = contentForExtraction
                            recoveryState.nudgeCount++
                            val retryReason = when {
                                isEffectivelyEmptyEnvelope -> "LLM returned empty JSON envelope (no actions, no response)"
                                nativeTextEmbeddedToolCall -> "LLM emitted tool call in text content instead of native tool_calls channel"
                                hasIncompleteJsonEnvelope -> "LLM returned incomplete JSON envelope"
                                else -> "LLM returned plain text without JSON structure"
                            }
                            logger.warn {
                                "[FORMAT_RETRY_NUDGE] taskId=$taskId, iteration=$iteration: " +
                                    "$retryReason. " +
                                    "Nudge=${recoveryState.nudgeCount}/2, content='${contentForExtraction.take(80)}'"
                            }
                            val resolvedThinking = turnResponseProcessor.resolveAssistantThinking(llmResponse)
                            if (!resolvedThinking.isNullOrBlank()) {
                                turnPersistence.persist(
                                    role = MessageRole.ASSISTANT,
                                    content = "",
                                    thinking = resolvedThinking,
                                    toolCalls = null,
                                    tokensIn = llmResponse.usage.inputTokens,
                                    tokensOut = llmResponse.usage.outputTokens,
                                    cost = llmResponse.cost,
                                )
                            }
                            turnPersistence.persist(
                                role = MessageRole.SYSTEM,
                                content = when {
                                    nativeTextEmbeddedToolCall ->
                                        "Your previous reply embedded a tool call inside the text content " +
                                            "(e.g. <tool_call>...</tool_call> or {\"name\":\"...\",\"arguments\":{...}} in prose). " +
                                            "Those are ignored — the harness only dispatches tool calls received through the " +
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
                                toolCalls = null,
                            )
                            continue
                        }

                        if (!usedNativeChannel && mode == TaskMode.AGENT && hasIncompleteJsonEnvelope) {
                            logger.error {
                                "[MALFORMED_JSON_ENVELOPE] taskId=$taskId, iteration=$iteration: " +
                                    "assistant returned incomplete JSON after retries exhausted"
                            }
                            val result = TurnResult(
                                success = false,
                                response = "The agent returned an incomplete JSON envelope and could not recover after retrying. " +
                                    "Please rerun with the same task or switch to a more reliable model.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        // Check error rate abort (hard abort — same threshold as tool-calls branch).
                        if (errorTracker.shouldAbort(config.errorRateThreshold)) {
                            val result = TurnResult(
                                success = false,
                                response = "Too many tool errors (${errorTracker.getStats()} failure rate). Task aborted.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnPersistence.finish(result, persistAssistantMessage = true)
                        }

                        val shouldRunTaskVerification =
                            configService.shouldVerifyTask(taskId, iteration, writeToolsExecutedInTurn)

                        // NO_CHANGES_NEEDED reconfirmation: let LLM reconsider once
                        // Task verification
                        val userMessageForVerification = userMessageStrategy.getUserMessage(taskId)
                        if (!verifyTaskCompletionIfNeeded(taskId, shouldRunTaskVerification, userMessageForVerification, llmResponse.content, persistAgentInstanceId, persistAgentName, persistAgentDepth)) {
                            continue
                        }

                        // beforeFinish guardian hook (lesson S03E03): run deterministic completion
                        // checks BEFORE persisting the final assistant message. A guardian may push
                        // the loop back into another iteration via a SYSTEM nudge. Bounded by
                        // GuardianRegistry.maxReentries to prevent infinite loops.
                        if (!completionGuardians.isEmpty) {
                            // Replenish the bounded re-entry budget if the agent has made sustained
                            // progress (>= N new tool calls) since the last re-entry. Without this the
                            // single re-entry is spent for the whole turn, so a budget consumed early
                            // on a trivial pause leaves a genuine near-completion stall many iterations
                            // later with no safety net. See [TurnGuardianState.replenishIfSustainedProgress].
                            if (guardianState.replenishIfSustainedProgress(
                                    usedTools.size,
                                    TurnGuardianState.DEFAULT_PROGRESS_RESET_THRESHOLD
                                )
                            ) {
                                logger.info {
                                    "[GUARDIAN] taskId=$taskId re-entry budget replenished after sustained " +
                                        "progress (toolsUsed=${usedTools.size}) — next terminal stall gets a fresh re-entry"
                                }
                            }
                            val guardianTextResponse = toolCallParser.extractTextResponse(llmResponse.content)
                            val guardianContext = GuardianContext(
                                taskId = taskId,
                                mode = mode,
                                runProfile = runProfile,
                                iteration = iteration,
                                maxIterations = maxIterations,
                                userRequest = userMessageForVerification,
                                finalResponse = guardianTextResponse.ifEmpty { llmResponse.content },
                                // Full call list (with repeats), NOT distinct: the judge must see
                                // that e.g. run_terminal_command ran 3× to verify "use it three
                                // times" requirements. Collapsing to distinct names lost the count
                                // and made the judge wrongly re-enter a completed multi-call task.
                                toolsUsed = usedTools.toList(),
                                writeToolsExecutedInTurn = writeToolsExecutedInTurn,
                                fileWriteToolsExecutedInTurn = fileWriteToolsExecutedInTurn,
                                verificationToolsExecutedAfterWrite = verificationToolsExecutedAfterWrite,
                                priorReentries = guardianState.reentryCount,
                                toolsUsedSizeAtPriorReentry = guardianState.usedToolsAtLastReentry,
                                completionCondition = taskRepository.getCompletionCondition(taskId),
                                subagentHasWriteTools = subagentHasWriteTools
                            )
                            when (val decision = completionGuardians.runChecks(guardianContext)) {
                                is GuardianDecision.Reenter -> {
                                    // Preserve the answer the user already saw before we drop it
                                    // by re-entering. Keep only the FIRST one — later re-entries
                                    // tend to degrade. Restored at finalize if the re-entry adds
                                    // no tool work (see [TurnGuardianState]).
                                    val reportText = guardianTextResponse.ifEmpty { llmResponse.content }
                                    val capturedReport = guardianState.captureIfFirst(
                                        llmResponse,
                                        hasVisibleText = reportText.isNotBlank(),
                                    )
                                    guardianState.onReentry(usedTools.size)
                                    // Persist the report the user watched stream as its own ASSISTANT
                                    // row, chronologically before the nudge, so a re-entry that goes on
                                    // to do more work does not make that report vanish from history
                                    // (it used to be dropped: only the SYSTEM nudge was persisted here,
                                    // and finalize restored the capture only when the re-entry added no
                                    // tool). Skipped once a FILE deliverable already landed this turn:
                                    // there the captured text is the forward-looking intent stub the
                                    // deliverable-aware sign-off below intentionally replaces, so
                                    // persisting it verbatim would resurrect exactly that stub. When the
                                    // re-entry adds no new tool, [captureAlreadyFinalized] makes finalize
                                    // skip re-persisting this same row.
                                    if (capturedReport && fileWriteToolsExecutedInTurn == 0) {
                                        turnPersistence.persist(
                                            role = MessageRole.ASSISTANT,
                                            content = reportText,
                                            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                                            toolCalls = null,
                                            tokensIn = llmResponse.usage.inputTokens,
                                            tokensOut = llmResponse.usage.outputTokens,
                                            cost = llmResponse.cost,
                                        )
                                        guardianState.capturePersisted = true
                                    }
                                    // Soft fallback native→JSON on a stalled re-entry. The model
                                    // reached this terminal point by emitting prose with no tool call. If it
                                    // was on the native channel, re-entering on that SAME channel tends to
                                    // reproduce the stall (observed: qwen3.5:9b narrating "Let me explore…"
                                    // with zero tool_calls). Drop native tools so the single bounded re-entry
                                    // retries on the JSON-in-text contract, which weak local models often
                                    // follow better — the prompt rebuild at the top of the next iteration
                                    // picks this up via `useNativeTools = activeNativeToolSchemas != null`.
                                    // This is the prose twin of the JSON-envelope fallback above (~1101);
                                    // no persistent NativeToolsFallbackTracker mark — only this turn switches.
                                    //
                                    // EXCEPTION: skip the drop once a FILE deliverable has already landed
                                    // this turn. Here the re-entry is a sign-off safety net, not a retry that
                                    // must produce the first deliverable — there is nothing critical to
                                    // recover. Switching to the JSON contract would only expose the finished
                                    // turn to the JSON format-nudge machinery ("Reply with JSON only"), which
                                    // treats the model's harmless follow-up prose as a broken envelope and
                                    // burns extra iterations before the deliverable-aware finalize (observed:
                                    // qwen3.6:27b landed the file, then narrated "Let me verify…" for 3 wasted
                                    // iterations). Staying on native lets the turn finalize on the clean
                                    // guardian-Pass path instead.
                                    if (activeNativeToolSchemas != null && fileWriteToolsExecutedInTurn == 0) {
                                        logger.warn {
                                            "[NATIVE_TOOLS_GUARDIAN_FALLBACK] taskId=$taskId — guardian re-entry " +
                                                "after a native no-call; retrying on the JSON contract for this turn"
                                        }
                                        activeNativeToolSchemas = null
                                    }
                                    turnPersistence.persist(
                                        role = MessageRole.SYSTEM,
                                        content = decision.nudge,
                                        // Flag as an internal guardian steering message so the UI
                                        // renders a gentle "agent guidance" note instead of the full
                                        // SYSTEM bubble with the alarming "STOP — the turn is NOT
                                        // finished" wall of text (which is model-facing, not for the
                                        // user). Full text stays in DB. See OtherBubbleRenderer.
                                        metadata = """{"type":"guardian_nudge"}""",
                                        toolCalls = null,
                                    )
                                    continue
                                }
                                is GuardianDecision.Incomplete -> {
                                    // Judge says the request was NOT delivered and no further
                                    // re-entry will help (single re-entry spent / prior nudge
                                    // produced no new tool call). Finalize the turn but flag it
                                    // INCOMPLETE so it is never recorded as SUCCESS. The
                                    // guardianState restore below still keeps the best
                                    // text the user already saw.
                                    logger.info { "[TURN_INCOMPLETE] taskId=$taskId reason=${decision.reason}" }
                                    turnIncompleteReason = decision.reason
                                    // fall through to finalize (do NOT continue)
                                }
                                GuardianDecision.Pass -> {
                                    // proceed to finalize
                                }
                            }
                        }

                        // Deterministic verification (loop code, not the model): the turn is about
                        // to finalize as complete with a deliverable, so run the project's
                        // build/test command and, on failure, feed a concrete error list back to
                        // the model for a bounded repair loop. Necessary condition: a real FILE
                        // edit landed this turn - turns without file writes are NEVER verified,
                        // which protects against the known regression of optional post-deliverable
                        // self-verification loops (and naturally excludes PLAN, which cannot
                        // write). Top-level turns only: a subagent's writes are re-verified by the
                        // parent turn's own finalization, and running a full build per subagent
                        // would multiply cost for no extra signal.
                        if (turnVerifier != null &&
                            turnIncompleteReason == null &&
                            mode == TaskMode.AGENT &&
                            depth == 0 &&
                            usedTools.any { turnToolExecutor.isFileWriteTool(it) }
                        ) {
                            updateTurnState { copy(phase = TurnPhase.FINALIZING) }
                            when (val outcome = turnVerifier.verify(taskId)) {
                                is TurnVerifier.Outcome.Skipped -> {
                                    logger.info { "[VERIFY] taskId=$taskId skipped: ${outcome.reason}" }
                                }
                                TurnVerifier.Outcome.Passed -> {
                                    verificationAttempts++
                                    verificationSummary = pl.jclab.refio.core.debug.VerificationSummary(
                                        ran = true,
                                        attempts = verificationAttempts,
                                        result = pl.jclab.refio.core.debug.VerificationSummary.RESULT_PASSED
                                    )
                                    pl.jclab.refio.core.debug.TurnVerificationTracker.record(taskId, verificationSummary!!)
                                }
                                is TurnVerifier.Outcome.Failed -> {
                                    verificationAttempts++
                                    val maxRepairRounds = turnVerifier.maxRepairRounds(taskId)
                                    val errorList = outcome.errors.joinToString("\n") { "- $it" }
                                    if (verificationAttempts <= maxRepairRounds && iteration < maxIterations) {
                                        logger.warn {
                                            "[VERIFY] taskId=$taskId failed (exit=${outcome.exitCode}) - " +
                                                "repair round $verificationAttempts/$maxRepairRounds"
                                        }
                                        // Keep the model's completion text in history, then send
                                        // the error list as the single repair message and re-enter
                                        // the loop; the next terminal point re-verifies.
                                        val verifyTextResponse = toolCallParser.extractTextResponse(llmResponse.content)
                                        turnPersistence.persist(
                                            role = MessageRole.ASSISTANT,
                                            content = verifyTextResponse.ifEmpty { llmResponse.content },
                                            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                                            toolCalls = null,
                                            tokensIn = llmResponse.usage.inputTokens,
                                            tokensOut = llmResponse.usage.outputTokens,
                                            cost = llmResponse.cost,
                                        )
                                        turnPersistence.persist(
                                            role = MessageRole.SYSTEM,
                                            content = "Verification failed (exit ${outcome.exitCode}). Errors:\n$errorList\nFix them.",
                                            toolCalls = null,
                                        )
                                        continue
                                    }
                                    logger.error {
                                        "[VERIFY] taskId=$taskId still failing after $verificationAttempts " +
                                            "attempt(s) (maxRepairRounds=$maxRepairRounds) - finalizing VERIFICATION_FAILED"
                                    }
                                    verificationSummary = pl.jclab.refio.core.debug.VerificationSummary(
                                        ran = true,
                                        attempts = verificationAttempts,
                                        result = pl.jclab.refio.core.debug.VerificationSummary.RESULT_FAILED
                                    )
                                    pl.jclab.refio.core.debug.TurnVerificationTracker.record(taskId, verificationSummary!!)
                                    TurnFailureMarkerTracker.record(taskId, TurnFailureMarkerTracker.VERIFICATION_FAILED)
                                    val result = TurnResult(
                                        success = false,
                                        response = "Verification failed (exit ${outcome.exitCode}) after " +
                                            "$verificationAttempts attempt(s). Errors:\n$errorList",
                                        iterations = iteration,
                                        tokensIn = totalTokensIn,
                                        tokensOut = totalTokensOut,
                                        cost = totalCost,
                                        toolsUsed = usedTools.distinct(),
                                        verification = verificationSummary
                                    )
                                    return turnPersistence.finish(result, persistAssistantMessage = true)
                                }
                            }
                        }

                        // Model responded with text - save and complete turn
                        updateTurnState { copy(phase = TurnPhase.FINALIZING) }
                        logger.info { "[TURN_COMPLETE] taskId=$taskId, iterations=$iteration" }

                        // If a guardian re-entry discarded a good answer and the re-entry then
                        // added NO tool work (usedTools unchanged since the re-entry snapshot),
                        // finalize the original answer the user already saw instead of the
                        // degraded re-phrasing that followed the nudge. When the re-entry DID
                        // call a tool (usedTools grew), its later response incorporates that new
                        // work and is the right one to keep.
                        val effectiveResponse = guardianState.effectiveResponse(llmResponse, usedTools.size)
                        val textResponse = toolCallParser.extractTextResponse(effectiveResponse.content)
                        turnResponseProcessor.tryCreatePlanSubtasks(taskId, mode, executionMode, effectiveResponse, runProfile)

                        // Deliverable-aware clean sign-off. When we are restoring a guardian stall-capture
                        // (a re-entry happened and added no new tool work) AND a real FILE deliverable
                        // already landed this turn, the restored text is by construction the forward-looking
                        // intent stub the guardian flagged as not-clearly-done ("Let me now verify the
                        // file…"). Surfacing it verbatim makes a completed turn read as if it stopped
                        // mid-check. Replace it with a concise, factual completion note. Scoped to
                        // file-write turns so the note is always true; a restored answer on a no-write turn
                        // (a read-only summary the judge misjudged) is left untouched.
                        val surfacedResponse =
                            if (fileWriteToolsExecutedInTurn > 0 &&
                                guardianState.restorableResponse(usedTools.size) != null
                            ) {
                                DELIVERABLE_STALL_SIGNOFF
                            } else {
                                textResponse.ifEmpty { effectiveResponse.content }
                            }

                        // Skip the terminal persist when this exact answer was already written as its
                        // own ASSISTANT row at re-entry AND the re-entry added no new tool work — the
                        // restored capture would be a byte-for-byte duplicate of that row. When the
                        // re-entry DID add work, captureAlreadyFinalized is false so the new terminal
                        // response is still persisted after the earlier report.
                        if (!guardianState.captureAlreadyFinalized(usedTools.size)) {
                            turnPersistence.persist(
                                role = MessageRole.ASSISTANT,
                                content = surfacedResponse,
                                thinking = turnResponseProcessor.resolveAssistantThinking(effectiveResponse),
                                toolCalls = null,
                                tokensIn = effectiveResponse.usage.inputTokens,
                                tokensOut = effectiveResponse.usage.outputTokens,
                                cost = effectiveResponse.cost,
                            )
                        }

                        val result = TurnResult(
                            success = turnIncompleteReason == null,
                            response = surfacedResponse,
                            iterations = iteration,
                            tokensIn = totalTokensIn,
                            tokensOut = totalTokensOut,
                            cost = totalCost,
                            toolsUsed = usedTools.distinct(),
                            incomplete = turnIncompleteReason != null,
                            verification = verificationSummary
                        )

                        updateTurnState { copy(phase = TurnPhase.COMPLETED, tokensUsed = totalTokensIn + totalTokensOut) }
                        hookService?.trigger("on_agent_complete", mapOf(
                            "taskId" to taskId,
                            "mode" to mode.name,
                            "iterations" to iteration.toString(),
                            "agentName" to (profileOverrides?.subagentName ?: "default")
                        ))
                        val finalResult = turnPersistence.finish(result, persistAssistantMessage = false)
                        emitTurnFinal(result.success)
                        return finalResult
                    }
                } finally {
                    GlobalMetrics.endOperation(iterationToken)
                    // Emit TurnEnded so the trace panel can close the iteration span
                    val iterationDurationMs = System.currentTimeMillis() - iterationStartMs
                    emitTurnEvent(taskId) {
                        pl.jclab.refio.core.agents.events.AgentEvent.TurnEnded(
                            id = UUID.randomUUID().toString(),
                            sessionId = evSessionId,
                            sourceAgentId = evSourceAgentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = runId,
                            iteration = iteration,
                            durationMs = iterationDurationMs,
                            isFinal = false,
                            runId = runId,
                            parentRunId = parentRunId,
                            depth = depth
                        )
                    }
                    hookService?.trigger("after_turn_loop", mapOf(
                        "taskId" to taskId,
                        "mode" to mode.name,
                        "iteration" to iteration.toString(),
                        "agentName" to (profileOverrides?.subagentName ?: "default")
                    ))
                }
            }
        } catch (e: pl.jclab.refio.core.llm.streaming.StreamAbortedException) {
            // Guardrail trip (repetition loop, output size limit, wall-clock deadline).
            // StreamAbortedException extends CancellationException so it would otherwise be
            // caught by the generic block below and misreported as "cancelled by user".
            // Preserve diagnostic fields, emit a dedicated event, and surface the real reason.
            updateTurnState { copy(phase = TurnPhase.FAILED) }
            logger.warn {
                "[TURN_STREAM_ABORTED] taskId=$taskId, iteration=$iteration, code=${e.code}, " +
                "reason=${e.reason}, partialLength=${e.partialContent.length}"
            }
            emitTurnEvent(taskId) {
                pl.jclab.refio.core.agents.events.AgentEvent.StreamAborted(
                    id = UUID.randomUUID().toString(),
                    sessionId = evSessionId,
                    sourceAgentId = evSourceAgentId,
                    timestamp = System.currentTimeMillis(),
                    correlationId = runId,
                    iteration = iteration,
                    code = e.code,
                    reason = e.reason,
                    partialLength = e.partialContent.length,
                    partialPreview = e.partialContent.take(500),
                    runId = runId,
                    parentRunId = parentRunId,
                    depth = depth
                )
            }
            hookService?.trigger("on_agent_error", mapOf(
                "taskId" to taskId,
                "mode" to mode.name,
                "error" to "Stream aborted by guardrail [${e.code}]: ${e.reason}",
                "guardrailCode" to e.code,
                "agentName" to (profileOverrides?.subagentName ?: "default")
            ))
            val result = TurnResult(
                success = false,
                response = "Stream aborted by guardrail [${e.code}]: ${e.reason}",
                iterations = iteration,
                tokensIn = totalTokensIn,
                tokensOut = totalTokensOut,
                cost = totalCost,
                toolsUsed = usedTools.distinct()
            )
            val finalResult = turnPersistence.finish(result, persistAssistantMessage = true)
            emitTurnFinal(success = false)
            return finalResult
        } catch (e: CancellationException) {
            updateTurnState { copy(phase = TurnPhase.FAILED) }
            hookService?.trigger("on_agent_error", mapOf(
                "taskId" to taskId,
                "mode" to mode.name,
                "error" to "Operation cancelled by user",
                "agentName" to (profileOverrides?.subagentName ?: "default")
            ))
            val result = TurnResult(
                success = false,
                // Keep the report the user was watching. Streamed prose has no DB row until a clean
                // finalize; on cancel the transient is dropped, so without this it vanishes and is
                // replaced by the generic string. Fall back to the generic string when nothing
                // substantial was streamed.
                response = lastStreamedAssistantText?.takeIf { it.isNotBlank() }
                    ?: "Operation cancelled by user.",
                iterations = iteration,
                tokensIn = totalTokensIn,
                tokensOut = totalTokensOut,
                cost = totalCost,
                toolsUsed = usedTools.distinct()
            )
            val finalResult = turnPersistence.finish(result, persistAssistantMessage = true)
            emitTurnFinal(success = false)
            return finalResult
        } catch (e: Exception) {
            // Backstop for every other failure (e.g. a DB write throwing mid-turn). The specific
            // catches above emit the terminal TurnEnded on their paths; without this one an
            // uncaught exception escapes and the agent's graph node is never flipped off RUNNING.
            // Best-effort emit (emitTurnEvent swallows), then rethrow so the caller still sees and
            // reports the real error unchanged.
            updateTurnState { copy(phase = TurnPhase.FAILED) }
            emitTurnFinal(success = false)
            throw e
        }

        // Max iterations exceeded
        updateTurnState { copy(phase = TurnPhase.FAILED) }
        logger.warn { "[TURN_MAX_ITERATIONS] taskId=$taskId, exceeded $maxIterations iterations" }
        hookService?.trigger("on_agent_error", mapOf(
            "taskId" to taskId,
            "mode" to mode.name,
            "error" to "Maximum iterations exceeded",
            "agentName" to (profileOverrides?.subagentName ?: "default")
        ))
        val result = TurnResult(
            success = false,
            // Preserve the last substantial streamed report (see cancel path) rather than drop it for
            // a generic max-iterations string — the user was watching real output.
            response = lastStreamedAssistantText?.takeIf { it.isNotBlank() }
                ?: "Error: Maximum iterations exceeded. The agent may be stuck in a loop.",
            iterations = iteration,
            tokensIn = totalTokensIn,
            tokensOut = totalTokensOut,
            cost = totalCost,
            toolsUsed = usedTools.distinct()
        )
        val finalResult = turnPersistence.finish(result, persistAssistantMessage = true)
        emitTurnFinal(success = false)
        return finalResult
    }

    /**
     * Terminal handling for a native-tools response that carried neither content nor tool calls.
     *
     * Prefers recovery: a guardian re-entry can discard a COMPLETE prior answer and then produce
     * nothing new, in which case the answer the user already saw is the correct result and the turn
     * succeeds on it. Only when nothing is restorable is this a real dead end, and the failure text
     * names the likely cause (a prompt silently truncated past the provider's context window).
     *
     * Extracted from `execute` to keep that method under the JVM's 64 KB per-method ceiling.
     */
    private suspend fun finalizeEmptyNativeResponse(
        taskId: String,
        mode: TaskMode,
        iteration: Int,
        llmResponse: pl.jclab.refio.core.llm.LLMResponse,
        effectiveModel: String,
        effectiveProvider: String,
        guardianState: TurnGuardianState,
        usedTools: List<String>,
        totalTokensIn: Int,
        totalTokensOut: Int,
        totalCost: Double,
        turnPersistence: TurnPersistence
    ): TurnResult {
        val recoverable = guardianState.restorableResponse(usedTools.size)
        if (recoverable != null) {
            logger.warn {
                "[TURN_NATIVE_EMPTY_RECOVERED] taskId=$taskId, iteration=$iteration — " +
                    "re-entry produced empty native response with no new tool work; " +
                    "finalizing the pre-re-entry answer the user already saw."
            }
            val recoveredText = toolCallParser.extractTextResponse(recoverable.content)
            turnPersistence.persist(
                role = MessageRole.ASSISTANT,
                content = recoveredText.ifEmpty { recoverable.content },
                thinking = turnResponseProcessor.resolveAssistantThinking(recoverable),
                toolCalls = null,
                tokensIn = recoverable.usage.inputTokens,
                tokensOut = recoverable.usage.outputTokens,
                cost = recoverable.cost,
            )
            val result = TurnResult(
                success = true,
                response = recoveredText.ifEmpty { recoverable.content },
                iterations = iteration,
                tokensIn = totalTokensIn,
                tokensOut = totalTokensOut,
                cost = totalCost,
                toolsUsed = usedTools.distinct()
            )
            return turnPersistence.finish(result, persistAssistantMessage = false)
        }

        logger.error {
            "[TURN_FAILED_NATIVE_EMPTY] taskId=$taskId, iteration=$iteration, " +
                "mode=$mode, provider=$effectiveProvider, model=$effectiveModel, " +
                "finishReason=${llmResponse.finishReason}, " +
                "inputTokens=${llmResponse.usage.inputTokens}, " +
                "outputTokens=${llmResponse.usage.outputTokens}. " +
                "Model returned empty content with zero native tool calls — most likely " +
                "the prompt exceeded the provider's context window and was silently truncated."
        }
        val response = "Model returned no content and no tool calls. " +
            "This usually means the prompt exceeded the model's context window " +
            "(inputTokens=${llmResponse.usage.inputTokens}, finishReason=${llmResponse.finishReason}). " +
            "Try: (a) shrink the conversation history, (b) increase providers.${effectiveProvider}.${effectiveProvider}_context_size, " +
            "or (c) switch to a model with a larger window."
        val result = TurnResult(
            success = false,
            response = response,
            iterations = iteration,
            tokensIn = totalTokensIn,
            tokensOut = totalTokensOut,
            cost = totalCost,
            toolsUsed = usedTools.distinct()
        )
        return turnPersistence.finish(result, persistAssistantMessage = true)
    }

    /** What the LLM call produced, plus the prompt/schema state a native fallback may have rewritten. */
    private data class LlmCallOutcome(
        val response: pl.jclab.refio.core.llm.LLMResponse,
        val prompt: TurnPrompt,
        val nativeToolSchemas: List<ToolSchema>?
    )

    /**
     * One decision-turn LLM call, including the native-tools fallbacks.
     *
     * Three ways a native call can fail without the model being at fault, each rescued once by
     * rebuilding the prompt on the JSON-envelope path: an empty native response (HTTP 200, blank
     * content, zero tool calls - the gemma/ALWAYS case), a provider that rejects tool schemas
     * outright ([ToolsNotSupportedException], which also demotes the model permanently), and a
     * provider-side 500 on a malformed tool-call template (a per-prompt glitch, so no persistent
     * demotion). Dropping the schemas is what makes each retry one-shot: the rebuilt prompt carries
     * no native tools, so the same branch cannot be re-entered.
     *
     * Lives outside `execute` because that method sat at 63.5 KB of bytecode - within 2 KB of the
     * JVM's 64 KB per-method ceiling, and already too large for JaCoCo to instrument, which silently
     * dropped the whole class from coverage.
     */
    private suspend fun callModelWithNativeFallback(
        taskId: String,
        mode: TaskMode,
        iteration: Int,
        maxIterations: Int,
        userContextRefs: List<pl.jclab.refio.api.models.ContextReference>,
        runProfile: TurnRunProfile,
        profileOverrides: TurnProfileOverrides?,
        writeToolsExecutedInTurn: Int,
        agentName: String?,
        sessionId: String,
        runId: String,
        config: TurnLoopConfig,
        effectiveModel: String,
        effectiveProvider: String,
        responseFormat: Map<String, String>?,
        streamCallback: StreamCallback?,
        initialPrompt: TurnPrompt,
        initialNativeToolSchemas: List<ToolSchema>?,
        guardianHasRestorableAnswer: Boolean
    ): LlmCallOutcome {
        var prompt = initialPrompt
        var nativeToolSchemas = initialNativeToolSchemas

        suspend fun callModelWithPrompt(
            currentPrompt: TurnPrompt,
            nativeSchemas: List<ToolSchema>?
        ) = if (config.maxRetries > 0 && llmRetryHandler != null) {
            val configuredEffort = configService.getTyped(ConfigKeys.GENERAL_REASONING_EFFORT, taskId)
            val thinkingEnabled = turnLLMCaller.resolveThinkingEnabled(effectiveProvider, effectiveModel, configuredEffort.isOn)
            llmRetryHandler.callWithRetry(
                provider = effectiveProvider,
                model = effectiveModel,
                messages = currentPrompt.messages,
                systemPrompt = currentPrompt.systemPrompt,
                taskId = taskId,
                // Retry path of the decision turn — keep parity with TurnLLMCaller's
                // non-retry path so PLAN/AGENT is distinguishable in the api-log Source.
                source = "AgentTurnLoop:${mode.name}",
                maxRetries = config.maxRetries,
                baseDelayMs = config.retryBackoffMs,
                responseFormat = responseFormat,
                thinking = thinkingEnabled,
                reasoningEffort = profileOverrides?.reasoningEffort
                    ?: configuredEffort.toEffortString()?.takeIf { thinkingEnabled },
                noEgressEnabled = configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, taskId),
                stream = streamCallback != null,
                onChunk = streamCallback,
                kwargs = nativeSchemas?.let { mapOf("native_tools" to it) } ?: emptyMap()
            )
        } else {
            turnLLMCaller.callLLM(
                taskId = taskId,
                mode = mode,
                prompt = currentPrompt,
                streamCallback = streamCallback,
                model = effectiveModel,
                provider = effectiveProvider,
                profileOverrides = profileOverrides,
                nativeToolSchemas = nativeSchemas
            )
        }

        suspend fun rebuildPromptWithoutNativeTools(): TurnPrompt = buildPrompt(
            taskId, mode, iteration, maxIterations,
            userContextRefs, runProfile, profileOverrides,
            writeToolsExecutedInTurn, false,
            agentName = agentName, sessionId = sessionId, modelId = effectiveModel, runId = runId
        )

        while (true) {
            try {
                // Pass the MUTABLE nativeToolSchemas (not the caller's frozen per-iteration
                // snapshot) so a fallback below that sets it to null actually drops native tools
                // on the retry — otherwise the rebuilt JSON-contract prompt would still ship them.
                val response = callModelWithPrompt(prompt, nativeToolSchemas)
                // Skip the empty-native rescue on a guardian re-entry: the stashed pre-re-entry
                // answer is what the user already saw, and the empty-native branch finalizes it.
                if (nativeToolSchemas != null
                    && response.content.isBlank()
                    && response.nativeToolCalls.isNullOrEmpty()
                    && !guardianHasRestorableAnswer
                ) {
                    logger.warn {
                        "[NATIVE_TOOLS_EMPTY_FALLBACK] taskId=$taskId, model=$effectiveModel — " +
                            "native response was empty (blank content, zero tool calls); one-shot JSON-path retry (no persistent fallback)"
                    }
                    nativeToolSchemas = null
                    prompt = rebuildPromptWithoutNativeTools()
                    continue
                }
                return LlmCallOutcome(response, prompt, nativeToolSchemas)
            } catch (e: ToolsNotSupportedException) {
                NativeToolsFallbackTracker.markFallback(effectiveModel, e.message ?: "provider error")
                if (nativeToolSchemas == null) {
                    throw e
                }
                logger.warn {
                    "[NATIVE_TOOLS_FALLBACK] taskId=$taskId, model=$effectiveModel — " +
                        "rebuilding prompt and retrying on JSON path"
                }
                nativeToolSchemas = null
                prompt = rebuildPromptWithoutNativeTools()
            } catch (e: RefioError.LLMError) {
                // Ollama's qwen tool-call template can 500 server-side on malformed function-call
                // XML the model emits. That is a per-prompt generation glitch, NOT a provider
                // capability gap — the same model uses native tools fine on simpler prompts — so
                // retry once on the JSON path WITHOUT persisting a fallback (contrast the
                // ToolsNotSupportedException branch above). If native tools were already dropped,
                // rethrow: nothing left to fall back to.
                if (nativeToolSchemas == null || !isNativeToolTemplateParseError(e)) {
                    throw e
                }
                logger.warn {
                    "[NATIVE_TOOLS_PARSE_FALLBACK] taskId=$taskId, model=$effectiveModel — " +
                        "provider rejected a malformed tool-call template; one-shot JSON-path retry (no persistent fallback)"
                }
                nativeToolSchemas = null
                prompt = rebuildPromptWithoutNativeTools()
            }
        }
    }

    private fun resolveInitialNativeToolSchemas(
        taskId: String,
        mode: TaskMode,
        effectiveModel: String,
        effectiveProvider: String,
        profileOverrides: TurnProfileOverrides?
    ): List<ToolSchema>? {
        val svc = toolPermissionsService
        if (svc == null) {
            logger.debug {
                "[NATIVE_TOOLS] Disabled for taskId=$taskId, mode=$mode → JSON-in-text path — " +
                    "no ToolPermissionsService (tools are off for this run)"
            }
            return null
        }
        val nativeModeRaw = configService.getTyped(ConfigKeys.NATIVE_TOOLS_MODE, taskId)
        val nativeToolsMode = parseNativeToolsMode(nativeModeRaw)
        val modelDef = ModelDefinitions.getDefinition(effectiveProvider, effectiveModel)
        val fallbackSet = NativeToolsFallbackTracker.getFallbackSet()
        // One human-readable reason string, reused in both the enabled and disabled log lines,
        // so the native-vs-JSON decision for a run is explainable from the log alone.
        val nativeReason = nativeToolsDecisionReason(nativeToolsMode, modelDef, effectiveModel, fallbackSet)
        return if (shouldUseNativeTools(nativeToolsMode, modelDef, effectiveModel, fallbackSet)) {
            val modeSchemas = toolRegistry.getToolSchemas(mode, svc, taskId)
            // Subagent profiles must see ONLY their allowed/disallowed tools in the native
            // `tools` array — otherwise the model calls tools the harness then rejects with
            // "Tool 'X' is not available to the subagent". The prompt's <available_tools>
            // is already filtered via resolveToolDescriptionsForProfile; this aligns the
            // native channel.
            val filtered = turnPromptBuilder.filterNativeToolSchemasByProfile(modeSchemas, profileOverrides)
            if (filtered.size != modeSchemas.size) {
                logger.info {
                    "[NATIVE_TOOLS] Filtered schemas for profile '${profileOverrides?.subagentName ?: "?"}': " +
                        "${modeSchemas.size} → ${filtered.size}"
                }
            }
            logger.info {
                "[NATIVE_TOOLS] Enabled for taskId=$taskId, mode=$mode, " +
                    "model=$effectiveProvider/$effectiveModel, schemas=${filtered.size} — $nativeReason"
            }
            filtered
        } else {
            logger.info {
                "[NATIVE_TOOLS] Disabled for taskId=$taskId, mode=$mode, " +
                    "model=$effectiveProvider/$effectiveModel → JSON-in-text path — $nativeReason"
            }
            null
        }
    }

    // Wraps the caller's listener to capture per-tool start/duration into the given maps.
    // Returns null when the caller passed no listener (see the IMPORTANT note at the call site).
    private fun wrapListenerForToolTiming(
        listener: TurnEventListener?,
        toolStartNanos: java.util.concurrent.ConcurrentHashMap<String, Long>,
        toolDurationsMs: java.util.concurrent.ConcurrentHashMap<String, Long>
    ): TurnEventListener? {
        val innerListener = listener
        return if (innerListener != null) {
            object : pl.jclab.refio.core.services.turn.TurnEventListener {
                override fun onTurnStarted(taskId: String, mode: TaskMode, runId: String, parentRunId: String?, depth: Int) {
                    innerListener.onTurnStarted(taskId, mode, runId, parentRunId, depth)
                }
                override fun onToolExecutionStarted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData) {
                    toolStartNanos[toolCall.id] = System.nanoTime()
                    innerListener.onToolExecutionStarted(taskId, toolCall)
                }
                override fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {
                    innerListener.onToolStreamChunk(taskId, toolCallId, delta, accumulated)
                }
                override fun onToolExecutionCompleted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData, result: String, success: Boolean) {
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
                override fun onTurnCompleted(taskId: String, result: pl.jclab.refio.core.services.TurnResult, runId: String, parentRunId: String?, depth: Int) {
                    innerListener.onTurnCompleted(taskId, result, runId, parentRunId, depth)
                }
            }
        } else null
    }

    // Emit ToolCalled events for Session Trace / Tool analytics.
    // When per-tool timings aren't available (no caller listener) fall back to
    // a batch-average estimate so the Debug panel still gets meaningful data.
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
        iteration: Int
    ) {
        val batchDurationMs = (System.nanoTime() - batchStartNanos) / 1_000_000
        val fallbackPerToolMs = if (toolResults.isNotEmpty()) batchDurationMs / toolResults.size else 0L
        for ((toolCall, resultData) in toolResults) {
            val success = resultData.success
            val durationMs = toolDurationsMs[toolCall.id] ?: fallbackPerToolMs
            // Feed global tool analytics (Debug panel)
            pl.jclab.refio.core.services.monitoring.ToolUsageStats.record(
                toolName = toolCall.name,
                durationMs = durationMs,
                success = success,
                errorMessage = if (success) null else resultData.content.take(200)
            )
            emitTurnEvent(taskId) {
                pl.jclab.refio.core.agents.events.AgentEvent.ToolCalled(
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
                    depth = depth
                )
            }
        }
    }

    // Handle AWAITING_RESPONSE from send_message tool
    private suspend fun handleAwaitingResponses(
        @Suppress("UNUSED_PARAMETER") taskId: String,
        toolResults: List<Pair<ToolCallData, ToolResultData>>,
        turnPersistence: TurnPersistence
    ) {
        for ((toolCall, resultData) in toolResults) {
            val metadata = resultData.metadata?.let { TurnJsonUtils.parseJsonToMap(it) }
            if (metadata?.get("type") == "AWAITING_RESPONSE" && agentEventBus != null) {
                val requestId = metadata["requestId"] as? String ?: continue
                val timeout = 300_000L // 5 minutes
                logger.info { "[AWAITING_RESPONSE] Tool ${toolCall.name} waiting for response to $requestId" }
                updateTurnState { copy(phase = TurnPhase.WAITING_FOR_PERMISSION) } // reuse state

                val response = try {
                    kotlinx.coroutines.withTimeout(timeout) {
                        agentEventBus.eventsOfType<pl.jclab.refio.core.agents.events.AgentEvent.DataResponse>()
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
                )
                logger.info { "[AWAITING_RESPONSE] Got response for $requestId: ${responseContent.take(100)}" }
            }
        }
    }

    private data class ToolBatchTracking(
        val repetitionAbort: TurnGuardrails.LoopStatus.ABORT?,
        val repetitionAbortToolName: String?,
        val noopCallIds: Set<String>,
        val consecutiveIdenticalFailures: Int,
        val lastFailureSignature: String?,
        // Set when the model called unavailable (profile-blocked) tools too many times in a row.
        val blockedAbort: TurnGuardrails.LoopStatus.ABORT? = null,
    )

    // Track error rate + definitive-loop detection + unified repetition tracker.
    // Definitive loop = the SAME tool with the SAME arguments failing repeatedly.
    // Varying either resets the counter so the agent can still explore freely.
    private fun trackToolBatch(
        toolResults: List<Pair<ToolCallData, ToolResultData>>,
        errorTracker: ToolErrorTracker,
        repetitionTracker: TurnRepetitionTracker,
        blockedTracker: TurnGuardrails.ConsecutiveBlockedToolTracker,
        consecutiveIdenticalFailures: Int,
        lastFailureSignature: String?,
    ): ToolBatchTracking {
        @Suppress("NAME_SHADOWING")
        var consecutiveIdenticalFailures = consecutiveIdenticalFailures
        @Suppress("NAME_SHADOWING")
        var lastFailureSignature = lastFailureSignature
        var repetitionAbort: TurnGuardrails.LoopStatus.ABORT? = null
        // Set once the model has called unavailable (profile-blocked) tools too many times in a row.
        var blockedAbort: TurnGuardrails.LoopStatus.ABORT? = null
        // Name of the tool that triggered the repetition abort (for deliverable-aware
        // handling: a loop on an optional VERIFICATION tool after the work is done is not
        // the same as a loop that never produced the deliverable).
        var repetitionAbortToolName: String? = null
        // Ids of write calls whose generated content was identical to the file (no-op).
        // A no-op write is NOT consolidation progress (P1) — it must not reset the
        // read-only spree counter below, otherwise a futile edit masks a read-forever loop.
        val noopCallIds = mutableSetOf<String>()
        for ((toolCall, result) in toolResults) {
            if (result.noop) noopCallIds.add(toolCall.id)
            val success = result.success
            errorTracker.recordResult(success)
            // Arg-independent "wrong toolset" backstop: a run of profile-blocked calls aborts even
            // when varying args keep the definitive-loop signature resetting and the error window
            // stays diluted. First ABORT in the batch wins.
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

            // Parse args defensively: if the JSON is malformed we skip tracking
            // for this call rather than crashing the turn loop.
            val parsedArgs: Map<String, Any?>? = runCatching {
                TurnJsonUtils.parseJsonToMap(toolCall.arguments)
            }.getOrNull()
            if (parsedArgs != null) {
                // Output is only forwarded on success — a failing call's error
                // text belongs to the error tracker, not the output-hash signal.
                // Use loopSignature (raw output sans hints/nudge) so the progressive
                // "[⚠ possible loop]" nudge — whose tail embeds a per-call subtask UUID
                // — cannot make every repeated read look "different" and defeat the
                // byte-identical hard-abort. Falls back to content for paths that don't
                // set a signature (blocked/error/synthetic results carry no nudge anyway).
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
        )
    }

    /**
     * Build prompt for LLM from conversation history.
     *
     * Uses ContextService for:
     * - Message filtering and formatting
     * - Tool result summarization/compaction in conversation history
     * - Project context building (RAG, @ mentions)
     *
     * @param taskId Task ID
     * @param mode Task mode
     * @param currentIteration Current iteration number (for AGENT mode iteration tracking)
     * @param maxIterations Maximum iterations (for AGENT mode iteration tracking)
     * @param userContextRefs User-provided @ mentions for context
     * @return Prompt with system message and conversation history
     */
    private suspend fun buildPrompt(
        taskId: String,
        mode: TaskMode,
        currentIteration: Int = 0,
        maxIterations: Int = 50,
        userContextRefs: List<pl.jclab.refio.api.models.ContextReference> = emptyList(),
        runProfile: TurnRunProfile = TurnRunProfile.DEFAULT,
        profileOverrides: TurnProfileOverrides? = null,
        writeToolsExecutedInTurn: Int = 0,
        useNativeTools: Boolean = false,
        nativeToolSchemas: List<ToolSchema>? = null,
        agentName: String? = null,
        sessionId: String? = null,
        modelId: String? = null,
        runId: String? = null
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
            runId = runId
        )

        // Build PromptSnapshot for UI inspection
        val contextTrace = turnPromptBuilder.getLastContextTrace()
        if (contextTrace != null) {
            val systemTokens = tokenEstimator.estimateString(turnPrompt.systemPrompt)
            val messagesTokens = turnPrompt.messages.sumOf { tokenEstimator.estimateString(it.content) }
            val totalTokens = systemTokens + messagesTokens
            val toolNames = toolRegistry.getAllTools().map { it.name }

            // Build granular section tokens: base context sections + system prompt + messages
            val baseSectionTokens = turnPromptBuilder.getLastSectionTokens() ?: emptyMap()
            val sectionTokens = buildSnapshotSectionTokens(
                baseSectionTokens = baseSectionTokens,
                systemPromptTokens = systemTokens,
                messagesTokens = messagesTokens,
                messages = turnPrompt.messages,
                totalTokens = totalTokens
            )

            _lastPromptSnapshot.value = PromptSnapshot(
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
                sectionTokens = sectionTokens
            )
        }

        return TurnPrompt(
            systemPrompt = turnPrompt.systemPrompt,
            messages = turnPrompt.messages
        )
    }

    /**
     * Render the turn's final prompt (system + messages) as a single string for the IntelliJ
     * "View Full" inspector. Captured at the moment of the LLM call so it reflects the actual
     * post-compaction / post-truncation payload, NOT a re-computed approximation.
     *
     * Format mirrors [pl.jclab.refio.core.api.routers.ProjectContextRouter.renderActiveRequestPreview]
     * so users get the same shape whether they hit "Refresh" (recomputed preview) or read what
     * was just sent (this snapshot).
     */
    private fun renderTurnPromptForInspection(
        mode: TaskMode,
        prompt: TurnPrompt
    ): String = buildString {
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

    /**
     * Build full section token map for PromptSnapshot.
     * Combines granular context sections (from XML tag parsing) with system prompt
     * and per-role message tokens — matching the breakdown from manual UI refresh.
     */
    private fun buildSnapshotSectionTokens(
        baseSectionTokens: Map<String, ContextSectionTokenInfo>,
        systemPromptTokens: Int,
        messagesTokens: Int,
        messages: List<LLMMessage>,
        totalTokens: Int
    ): Map<String, ContextSectionTokenInfo> {
        val denominator = totalTokens.coerceAtLeast(1).toDouble()
        val result = linkedMapOf<String, ContextSectionTokenInfo>()

        // System prompt
        if (systemPromptTokens > 0) {
            result["system_prompt"] = ContextSectionTokenInfo(
                name = "System Prompt",
                tokens = systemPromptTokens,
                chars = systemPromptTokens * 4,
                percentage = systemPromptTokens / denominator * 100.0
            )
        }

        // Granular context sections (already using the right keys for color palette)
        result.putAll(baseSectionTokens)

        // Per-role message breakdown
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

    /**
     * Verify task completion if enabled.
     */
    private suspend fun verifyTaskCompletionIfNeeded(
        taskId: String,
        shouldRunVerification: Boolean,
        userRequestFallback: String?,
        llmContent: String,
        agentInstanceId: String? = null,
        agentName: String? = null,
        agentDepth: Int? = null
    ): Boolean {
        if (!shouldRunVerification) {
            return true
        }

        val userRequest = userRequestFallback?.takeIf { it.isNotBlank() }
            ?: getLastUserMessage(taskId, agentInstanceId)
            ?: throw IllegalStateException("Missing user message for task verification: $taskId")

        val verification = taskVerifier.verifyCompletion(taskId, userRequest, llmContent, agentInstanceId)
        if (verification.isComplete) {
            return true
        }

        val suggested = if (verification.suggestedActions.isNotEmpty()) {
            " Suggested: ${verification.suggestedActions.joinToString("; ")}"
        } else {
            ""
        }
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.SYSTEM,
            content = "Task verification failed: ${verification.reason}.$suggested",
            toolCalls = null,
            agentInstanceId = agentInstanceId,
            agentName = agentName,
            agentDepth = agentDepth,
        )
        return false
    }

    /**
     * True when an [RefioError.LLMError] is Ollama's server-side tool-call *template* parser
     * choking on malformed function-call XML the model emitted (HTTP 500, e.g. "XML syntax
     * error on line N: element <parameter> closed by </function>"). This is a per-prompt
     * generation glitch specific to native function calling — recoverable by retrying on the
     * JSON-envelope path — NOT a sign the model can never do native tools. Walks the cause
     * chain because the signature lives in the wrapped provider exception.
     */
    internal fun isNativeToolTemplateParseError(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 5) {
            val msg = cause.message?.lowercase() ?: ""
            if (msg.contains("xml syntax error") || msg.contains("element <parameter>")) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    /**
     * Heuristic: does `content` look like a `{response, actions}` JSON envelope the model
     * emitted in text instead of using native tool_calls? Used to trigger the native→JSON
     * fallback path without waiting for a provider-level [ToolsNotSupportedException].
     */
    /**
     * Returns true when `content` parses as a JSON object with no usable payload:
     * no `actions`/`tool_calls`/`subtasks`/`steps` array, and no non-blank `response`/`content` text.
     * Such responses are a silent no-op — we nudge the model to emit a real envelope.
     */
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

    private fun isJsonEnvelopeFallback(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) return false
        // Fast path: presence of the distinctive keys. Avoids parsing JSON on every turn.
        val body = if (trimmed.startsWith("```")) trimmed.removePrefix("```").removePrefix("json").trim() else trimmed
        return body.contains("\"actions\"") && (body.contains("\"tool\"") || body.contains("\"response\""))
    }

    /**
     * Detect tool calls embedded in text content (not via native tool_calls channel).
     * Matches two observed patterns from weak models under native-tools mode:
     *   - pseudo-XML: <tool_call>...</tool_call>, <function_call>..., <tool_call'>
     *   - inline JSON tool shape: {"name":"foo","arguments":{...}} or {"tool_calls":[...]}
     * Parser can't dispatch these (shape isn't {actions:[...]}) so we must nudge.
     */
    private fun looksLikeTextEmbeddedToolCall(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        // Pseudo-XML tags (tolerant of trailing punctuation like `<tool_call'>` from glm-5)
        if (trimmed.contains("<tool_call", ignoreCase = true) ||
            trimmed.contains("<function_call", ignoreCase = true) ||
            trimmed.contains("</tool_call", ignoreCase = true) ||
            trimmed.contains("</function_call", ignoreCase = true)
        ) {
            return true
        }
        // Inline JSON tool-call shape without the {actions:[...]} envelope
        val hasToolCallsKey = trimmed.contains("\"tool_calls\"")
        val hasNameAndArgs = trimmed.contains("\"name\"") &&
            (trimmed.contains("\"arguments\"") || trimmed.contains("\"parameters\""))
        return hasToolCallsKey || hasNameAndArgs
    }

    /**
     * Get last user message from history.
     */
    internal fun getLastUserMessage(taskId: String, agentInstanceId: String? = null): String? {
        return try {
            chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId)
                .lastOrNull { it.role == MessageRole.USER }
                ?.content
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Model-facing nudge injected after a long run of information-gathering with no write/persist.
     * Inlined (like the other short steering nudges in this loop) since TurnNudgeBuilder was removed.
     * Addresses the c19 failure: the model reads 25+ files and never writes, while RECENT_WORK
     * truncation silently drops the evidence it gathered, so it can never assemble the deliverable.
     */
    private fun buildConsolidationNudge(gatheringCalls: Int): String = buildString {
        appendLine("[⚠ progressive hint — lots of reading, nothing produced yet]")
        appendLine(
            "You have made $gatheringCalls information-gathering calls (reads/searches) in a row " +
                "without writing a file, persisting findings, or delivering a result."
        )
        appendLine(
            "Long read-heavy runs lose their own evidence: older tool outputs are compressed out of " +
                "your recent-work context, so by the time you try to write the result, the details " +
                "(and the file:line citations) you gathered are already gone — which is why reading " +
                "even more before consolidating does not help."
        )
        appendLine("Consolidate NOW — do at least one of:")
        appendLine(
            "  (1) persist what you have learned so far with `memory(action=\"write\", ...)` " +
                "(the key facts + exact file:line citations you need), so it survives compaction;"
        )
        appendLine(
            "  (2) start producing the requested deliverable incrementally — write a partial file / " +
                "the first sections now and extend them — instead of reading everything first;"
        )
        append(
            "  (3) if the task is too large to hold at once, finish one self-contained part " +
                "(write it out) before gathering more."
        )
    }

    private fun buildRegenerationNudge(path: String, regenerations: Int): String = buildString {
        appendLine("[⚠ progressive hint — you keep rebuilding the same file from scratch]")
        appendLine(
            "You have regenerated `$path` whole-file $regenerations times this turn " +
                "(advance_code_editing / create_new_file each rebuild the entire file)."
        )
        appendLine(
            "A write that returned a diff / line count SUCCEEDED and is complete — that diff is the " +
                "authoritative current content. Regenerating a successfully-written file without a " +
                "concrete build/test/run error pointing at it just burns another full multi-minute " +
                "generation and usually produces a DIFFERENT file (new bugs), not a better one."
        )
        appendLine("Do one of:")
        appendLine(
            "  (1) if a SPECIFIC defect remains, fix only that with a targeted edit " +
                "(`code_editing` / `multi_edit`) — do NOT rewrite the whole file;"
        )
        append(
            "  (2) otherwise the file is done — deliver your final answer now instead of " +
                "regenerating again."
        )
    }

    private fun buildSubagentInvokeNudge(agentNames: List<String>): String = buildString {
        val named = agentNames.filter { it.isNotBlank() }
        appendLine("[⚠ progressive hint — you keep defining agents but never run one]")
        appendLine(
            if (named.isEmpty()) {
                "You have called `manage_subagent` repeatedly without a single `invoke_subagent`."
            } else {
                "You have defined ${named.joinToString(", ") { "`$it`" }} with `manage_subagent`, " +
                    "but you have not run any of them."
            }
        )
        appendLine(
            "`manage_subagent` only stores a definition — it does no work. Only " +
                "`invoke_subagent(name=..., goal=...)` actually runs an agent, and a subagent is blind: " +
                "its `goal` must be self-contained (exact task, relevant paths, required output format)."
        )
        appendLine("Do one of:")
        appendLine(
            if (named.isEmpty()) {
                "  (1) run an existing agent now — `manage_subagent(action=\"list\")` shows what is available;"
            } else {
                "  (1) run `invoke_subagent(name=\"${named.first()}\", goal=\"...\")` NOW — " +
                    "dispatch several in one response if they are independent;"
            }
        )
        append(
            "  (2) or drop the delegation and do the work yourself with the read/write tools — " +
                "for a small job that is cheaper than a subagent."
        )
    }

    companion object {
        /** At most one "you never invoke your agents" nudge per turn — a soft hint, never spam. */
        private const val MAX_SUBAGENT_INVOKE_NUDGES = 1

        /**
         * After this many consecutive information-gathering calls (reads/searches) with no
         * write/persist/deliver, inject the consolidation nudge. Set above a normal multi-file
         * read pass (a thorough exploration legitimately reads ~10 files) so it fires on the
         * "read forever, never deliver" pathology, not on healthy exploration.
         */
        private const val READ_ONLY_CONSOLIDATION_THRESHOLD = 14

        /** Bound on consolidation nudges per turn — a soft hint, not a hard stop; never spam it. */
        private const val MAX_CONSOLIDATION_NUDGES = 2

        /**
         * Whole-file rebuilds of the SAME path that trigger the repeated-regeneration nudge.
         * Deliberately lenient: a file is legitimately touched many times per turn, and even a
         * second from-scratch rebuild can be reasonable. Only at 3 whole-file rebuilds of one path
         * is it clearly pathological (the reported c71be484 case rebuilt one file 3×). Targeted
         * edits (code_editing/multi_edit) never count, so ordinary iterative editing is unaffected.
         */
        private const val REGENERATION_NUDGE_THRESHOLD = 3

        /** At most one repeated-regeneration nudge per turn — a single soft hint, never spam. */
        private const val MAX_REGENERATION_NUDGES = 1

        /**
         * R1: how many CONSECUTIVE native-ignored JSON-envelope-in-text responses demote a
         * model off native tool-calling (a persisted `NativeToolsFallbackTracker` mark). A capable model
         * occasionally mirrors the envelope shown as a negative example in the prompt; one slip must not
         * permanently kick it off native, but a repeated streak is a genuine "won't use native here" signal.
         */
        private const val NATIVE_TOOLS_DEMOTE_AFTER_IGNORES = 2

        /**
         * Completion note surfaced when a turn finalizes SUCCESS with a FILE deliverable already on
         * disk but the model's last terminal text was only a forward-looking intent stub ("Let me now
         * verify the file…") that a guardian re-entry discarded. Replacing that dangling prose keeps a
         * completed turn from reading as if it stopped mid-check. English to match the other
         * loop-generated turn responses in this file.
         */
        private const val DELIVERABLE_STALL_SIGNOFF =
            "Done - the requested file changes were written this turn. The agent's trailing follow-up " +
                "produced no further tool call and was skipped; the recorded write is the confirmation."
    }
}
