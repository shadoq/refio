package pl.jclab.refio.core.services

// Import TurnLoopConfigs from core.services (not turn/ package)
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
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.AgentTurnLoop.UserMessageStrategy
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.services.turn.ToolCallParser
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.services.turn.TurnPrompt
import pl.jclab.refio.core.services.turn.GuardianContext
import pl.jclab.refio.core.services.turn.GuardianDecision
import pl.jclab.refio.core.services.turn.GuardianRegistry
import pl.jclab.refio.core.services.turn.TurnFinalizer
import pl.jclab.refio.core.services.turn.TurnGuardrails
import pl.jclab.refio.core.services.turn.TurnJsonUtils
import pl.jclab.refio.core.services.turn.TurnLLMCaller
import pl.jclab.refio.core.services.turn.TurnPromptBuilder
import pl.jclab.refio.core.services.turn.TurnResponseProcessor
import pl.jclab.refio.core.services.turn.ContextDecisionTrace
import pl.jclab.refio.core.services.turn.ContextSectionRecord
import pl.jclab.refio.core.services.turn.PromptSnapshot
import pl.jclab.refio.core.services.turn.ToolBatchSummary
import pl.jclab.refio.core.services.turn.ToolCallWithResult
import pl.jclab.refio.core.services.turn.TurnPhase
import pl.jclab.refio.core.services.turn.TurnStateSnapshot
import pl.jclab.refio.core.services.turn.TurnSubagentValidator
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import pl.jclab.refio.core.services.turn.ToolRejectedException
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolSchema
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.NativeToolCall
import pl.jclab.refio.core.llm.NativeToolsFallbackTracker
import pl.jclab.refio.core.llm.ToolsNotSupportedException
import pl.jclab.refio.core.llm.parseNativeToolsMode
import pl.jclab.refio.core.llm.shouldUseNativeTools
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.logging.dualLogger
import java.util.*
import java.util.concurrent.CancellationException

// Type aliases for turn/ package classes
private typealias ToolErrorTracker = TurnGuardrails.ToolErrorTracker
private typealias TurnRepetitionTracker = TurnGuardrails.TurnRepetitionTracker

private val logger = dualLogger("AgentTurnLoop")


/**
 * AgentTurnLoop - Turn-based execution loop implementing Codex CLI-style pattern.
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
class AgentTurnLoop(
    // Core dependencies
    private val llmClient: LLMClient,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val configService: ConfigService,
    private val toolRegistry: ToolRegistry,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val taskVerifier: TaskVerifier = NoopTaskVerifier(),

    // turn/ package components
    private val turnPromptBuilder: TurnPromptBuilder,
    private val toolCallParser: ToolCallParser,
    private val turnToolExecutor: TurnToolExecutor,
    private val turnLLMCaller: TurnLLMCaller,
    private val turnResponseProcessor: TurnResponseProcessor,
    private val turnFinalizer: TurnFinalizer,
    private val turnSubagentValidator: TurnSubagentValidator,
    /**
     * beforeFinish guardian registry. When non-empty, runs after task verification at the
     * natural completion exit and may push the loop back into another iteration with a nudge.
     * See [GuardianRegistry] / [TurnCompletionGuardian]. Empty by default — no behavior change.
     */
    private val completionGuardians: GuardianRegistry = GuardianRegistry(),

    // ADR-0028: Optional dependencies for enhanced turn loop
    private val tokenEstimator: PromptTokenEstimator = PromptTokenEstimator(),
    private val conversationCompactor: ConversationCompactor? = null,
    private val llmRetryHandler: LLMRetryHandler? = null,
    private val workingMemoryIntegration: WorkingMemoryIntegration? = null,
    private val pendingUserMessageQueue: PendingUserMessageQueue? = null,
    private val agentEventBus: pl.jclab.refio.core.agents.events.AgentEventBus? = null,
    private val hookService: pl.jclab.refio.core.services.hooks.HookService? = null,
    private val toolPermissionsService: ToolPermissionsService? = null
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _turnState = MutableStateFlow(TurnStateSnapshot())
    val turnState: StateFlow<TurnStateSnapshot> = _turnState.asStateFlow()

    private val _lastPromptSnapshot = MutableStateFlow<PromptSnapshot?>(null)
    val lastPromptSnapshot: StateFlow<PromptSnapshot?> = _lastPromptSnapshot.asStateFlow()

    private fun updateTurnState(update: TurnStateSnapshot.() -> TurnStateSnapshot) {
        _turnState.value = _turnState.value.update()
    }

    // Type aliases for guardrails classes - using turn/ package implementations
    // Note: Using full qualified names instead of nested typealiases (not supported in Kotlin classes)

    /**
     * Strategy interface for obtaining user message during task verification.
     * runTurn has direct userInput, continueTurn must fetch from history.
     */
    private fun interface UserMessageStrategy {
        suspend fun getUserMessage(taskId: String): String?
    }

    /**
     * Turn source for logging differences.
     */
    private enum class TurnSource {
        RUN,
        CONTINUE
    }

    /**
     * Run a single turn with the user input.
     *
     * A "turn" consists of:
     * 1. Save user message to history
     * 2. Enter loop:
     *    a. Build prompt from history
     *    b. Call LLM
     *    c. If model invoked tools:
     *       - Save tool calls to history
     *       - Execute tools
     *       - Save results to history
     *       - Continue loop
     *    d. If model responded with text:
     *       - Save response to history
     *       - Exit loop
     * 3. Return turn result
     *
     * @param taskId Task ID
     * @param userInput User's input message
     * @param mode Task mode (PLAN or AGENT)
     * @param executionMode Execution mode (AUTO or INTERACTIVE)
     * @param listener Optional listener for turn events
     * @param streamCallback Optional callback for streaming response chunks
     * @param userContextRefs User-provided context references (@ mentions)
     * @return TurnResult with final response and metadata
     */
    suspend fun runTurn(
        taskId: String,
        userInput: String,
        mode: TaskMode,
        executionMode: ExecutionMode = ExecutionMode.AUTO,
        listener: TurnEventListener? = null,
        streamCallback: StreamCallback? = null,
        model: String? = null,
        provider: String? = null,
        userContextRefs: List<pl.jclab.refio.api.models.ContextReference> = emptyList(),
        runProfile: TurnRunProfile = TurnRunProfile.DEFAULT,
        profileOverrides: TurnProfileOverrides? = null,
        /** Override for AgentEvent.sessionId (see TurnRequest.emitSessionId). */
        emitSessionId: String? = null,
        /** Override for AgentEvent.sourceAgentId (see TurnRequest.emitSourceAgentId). */
        emitSourceAgentId: String? = null,
        /** Stable agent name for A2A routing (see TurnRequest.agentName). */
        agentName: String? = null
    ): TurnResult {
        val runId = UUID.randomUUID().toString()
        val parentRunId = profileOverrides?.parentRunId
        val depth = profileOverrides?.depth ?: 0
        turnSubagentValidator.validateDepth(profileOverrides)
        turnSubagentValidator.validateRecursion(runProfile, profileOverrides)

        logger.info {
            "[TURN_START] taskId=$taskId, mode=$mode, executionMode=$executionMode, " +
            "model=${model ?: "auto"}, provider=${provider ?: "auto"}, " +
            "runProfile=$runProfile, runId=$runId, parentRunId=${parentRunId ?: "-"}, depth=$depth"
        }

        val maxIterationsHint = profileOverrides?.maxIterationsOverride?.takeIf { it > 0 } ?: 25
        GlobalMetrics.setCurrentOperation(
            OperationInfo.TurnLoop(1, maxIterationsHint, mode.name)
        )

        taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        listener?.onTurnStarted(taskId, mode, runId, parentRunId, depth)

        hookService?.trigger("before_turn_loop", mapOf(
            "taskId" to taskId,
            "mode" to mode.name,
            "agentName" to (profileOverrides?.subagentName ?: "default")
        ))

        // Step 1: Save user message to history
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.USER,
            content = userInput,
            agentInstanceId = profileOverrides?.agentInstanceId,
            agentName = profileOverrides?.subagentName,
            agentDepth = profileOverrides?.subagentName?.let { (profileOverrides.depth) + 1 },
        )

        // Step 2: Execute turn loop.
        // Mark the turn active so background RAG indexing/embedding yields the SQLite
        // WAL writer-lock for its duration — concurrent RAG writes otherwise stalled
        // tool subtask-status writes ~122s. try/finally keeps the count balanced.
        GlobalMetrics.beginAgentTurn()
        return try {
            executeTurnLoop(
                taskId = taskId,
                mode = mode,
                executionMode = executionMode,
                listener = listener,
                streamCallback = streamCallback,
                model = model,
                provider = provider,
                userContextRefs = userContextRefs,
                runProfile = runProfile,
                profileOverrides = profileOverrides,
                runId = runId,
                parentRunId = parentRunId,
                depth = depth,
                source = TurnSource.RUN,
                userMessageStrategy = UserMessageStrategy { userInput },
                emitSessionId = emitSessionId,
                emitSourceAgentId = emitSourceAgentId,
                agentName = agentName
            )
        } finally {
            GlobalMetrics.endAgentTurn()
        }
    }

    /**
     * Continue a turn after user provides additional input (for INTERACTIVE mode).
     *
     * This is called when the turn was paused for user confirmation of a tool call.
     *
     * @param taskId Task ID
     * @param mode Task mode
     * @param executionMode Execution mode
     * @param listener Optional listener for turn events
     * @param streamCallback Optional callback for streaming response chunks
     * @param userContextRefs User-provided context references (@ mentions)
     * @return TurnResult with final response and metadata
     */
    suspend fun continueTurn(
        taskId: String,
        mode: TaskMode,
        executionMode: ExecutionMode = ExecutionMode.AUTO,
        listener: TurnEventListener? = null,
        streamCallback: StreamCallback? = null,
        model: String? = null,
        provider: String? = null,
        userContextRefs: List<pl.jclab.refio.api.models.ContextReference> = emptyList(),
        runProfile: TurnRunProfile = TurnRunProfile.DEFAULT,
        profileOverrides: TurnProfileOverrides? = null,
        emitSessionId: String? = null,
        emitSourceAgentId: String? = null,
        /** Stable agent name for A2A routing (see TurnRequest.agentName). */
        agentName: String? = null
    ): TurnResult {
        val runId = UUID.randomUUID().toString()
        val parentRunId = profileOverrides?.parentRunId
        val depth = profileOverrides?.depth ?: 0
        turnSubagentValidator.validateDepth(profileOverrides)
        turnSubagentValidator.validateRecursion(runProfile, profileOverrides)

        logger.info {
            "[TURN_CONTINUE] taskId=$taskId, mode=$mode, runProfile=$runProfile, " +
                "runId=$runId, parentRunId=${parentRunId ?: "-"}, depth=$depth"
        }
        listener?.onTurnStarted(taskId, mode, runId, parentRunId, depth)

        hookService?.trigger("before_turn_loop", mapOf(
            "taskId" to taskId,
            "mode" to mode.name,
            "agentName" to (profileOverrides?.subagentName ?: "default")
        ))

        // Execute turn loop (continues from current history state)
        return executeTurnLoop(
            taskId = taskId,
            mode = mode,
            executionMode = executionMode,
            listener = listener,
            streamCallback = streamCallback,
            model = model,
            provider = provider,
            userContextRefs = userContextRefs,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            runId = runId,
            parentRunId = parentRunId,
            depth = depth,
            source = TurnSource.CONTINUE,
            userMessageStrategy = UserMessageStrategy { getLastUserMessage(taskId, profileOverrides?.agentInstanceId) },
            emitSessionId = emitSessionId,
            emitSourceAgentId = emitSourceAgentId,
            agentName = agentName
        )

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

    private suspend fun executeTurnLoop(
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
        val errorTracker = ToolErrorTracker(windowSize = config.errorWindowSize)
        // Unified repetition tracker — catches two overlapping "stuck on same object"
        // failure modes with a single state: (a) the same (tool, target) pair invoked
        // many times total, and (b) the same tool producing byte-identical output on
        // successive successful runs. See TurnGuardrails.TurnRepetitionTracker.
        //
        // Subagent budget control: when depth >= 1 we tighten the byte-identical abort
        // threshold from 4 → 2. Subagents have narrower goals than top-level turns and
        // smaller token budgets — a second byte-identical read of the same large file
        // (observed 2026-05 with qwen3.5:9b reading AgentTurnLoop.kt 4× = ~500K input
        // tokens before abort) is already a strong "no progress" signal worth cutting
        // on. Top-level turns keep the default of 4 because user-driven exploration
        // legitimately re-reads the same file across user-driven sub-tasks.
        val repetitionTracker = TurnRepetitionTracker(
            identicalOutputAbortThreshold = if (depth >= 1) 2 else 4
        )
        // Cross-iteration assistant-text repetition guard: catches the model repeating the
        // SAME no-tool-call text on successive terminal points (e.g. after a guardian / format
        // nudge re-entry) — invisible to [repetitionTracker] (records only on tool execution)
        // and to ContentChantingDetector (intra-response only). See TurnGuardrails.
        val textRepetitionTracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
        // Counter for write tools executed in the current turn — still tracked because
        // buildPrompt and the completion guardians both want to know.
        var writeToolsExecutedInTurn = 0
        var verificationToolsExecutedAfterWrite = 0
        // "Read forever, never deliver" soft guard: consecutive information-gathering calls
        // (reads/searches) with no write/persist/deliver in between. A long read-only spree
        // loses its own evidence — older tool outputs get compressed out of RECENT_WORK before
        // the model writes anything — so we nudge it to consolidate (persist to memory / deliver
        // incrementally). Resets on any progress; bounded to MAX_CONSOLIDATION_NUDGES.
        var consecutiveGatheringCalls = 0
        var consolidationNudgeCount = 0
        // Definitive-loop guard: counts consecutive failures of the SAME (tool + args).
        // Resets whenever arguments change, a different tool is used, or any tool succeeds.
        // Catches true retry loops while allowing the agent to explore with varied calls.
        var consecutiveIdenticalFailures = 0
        var lastFailureSignature: String? = null
        // beforeFinish guardian re-entry counter (capped by GuardianRegistry.maxReentries).
        var guardianReentryCount = 0
        // Set when a completion guardian marks the turn INCOMPLETE (request not delivered and no
        // further re-entry will help). Non-null → the final TurnResult carries incomplete=true so
        // CoreSessionService records the task as INCOMPLETE instead of silently SUCCESS.
        var turnIncompleteReason: String? = null
        // Snapshot of usedTools.size at the moment of the most recent guardian re-entry.
        // Lets NextSpeakerJudgeGuardian detect "previous nudge produced no new tool call"
        // and short-circuit the loop instead of burning another judge call + LLM iteration
        // on the same stuck pattern. See [NextSpeakerJudgeGuardian.check].
        var usedToolsSizeAtLastReentry = 0
        // The first terminal text response that a guardian re-entry discarded. The model had
        // already streamed this answer to the user's bubble; re-entering the loop drops it
        // (we `continue` before persisting). If the re-entry then produces NO new tool call
        // (the judge's no-progress short-circuit to Pass), the follow-up response is usually a
        // degraded re-phrasing — finalizing it would replace the good answer the user saw with
        // a worse one and lose the original from history entirely (observed 2026-05, sessions
        // 54cf9c8c / 070ab0e5). We keep the discarded answer here and restore it at finalize
        // when the re-entry added no tool work. Captured once (first re-entry) so the earliest,
        // most-complete terminal answer wins.
        var candidateFinalResponse: pl.jclab.refio.core.llm.LLMResponse? = null
        // Plain-text guard (AGENT mode only): counts nudges sent when the model replies with
        // prose instead of the required JSON envelope. Bounded to 2 — if the model cannot
        // recover after two explicit reminders, further retries won't help and we fall
        // through to normal finalization instead of spinning. Weaker models (e.g. qwen3.5:9b)
        // under context pressure routinely drop format for one iteration; a single short
        // nudge usually brings them back. Without this guard the loop exits as `success=true`
        // on the first plain-text response, silently abandoning mid-task work.
        // NOTE: Nudges are skipped when the model previously executed tool calls — plain text
        // after successful tool usage is treated as intentional completion, not format loss.
        var plainTextNudgeCount = 0
        var lastPlainTextContent: String? = null
        var totalTokensIn = 0
        var totalTokensOut = 0
        var totalCost = 0.0
        val usedTools = mutableListOf<String>()
        val maxConsecutiveIdenticalFailures = configService.getTyped(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS, taskId)
        val subagentMetadata: String? = if (runProfile == TurnRunProfile.SUBAGENT) {
            val name = profileOverrides?.subagentName ?: "subagent"
            """{"subagent_name":"$name"}"""
        } else null

        // When running as a subagent, persist messages with agentName / agentDepth so the
        // IntelliJ chat bubble renderer groups them under a per-agent header.
        // agentInstanceId isolates the subagent's chat history from the parent and from
        // sibling subagents — see ChatMessageRepository.findHistoryForInvocation.
        val persistAgentInstanceId: String? = profileOverrides?.agentInstanceId
        val persistAgentName: String? = profileOverrides?.subagentName
        val persistAgentDepth: Int? = if (persistAgentName != null) (profileOverrides?.depth ?: 0) + 1 else null

        // For subagent turns, wrap the caller's streamCallback so each token delta is ALSO
        // published as AgentEvent.StreamChunk with runId/depth/agentName. CoreSessionService
        // subscribes to these events to render a per-agent streaming bubble that updates live
        // while the subagent's LLM is still generating. Top-level turns skip the wrapper — their
        // deltas already feed the main streaming message directly via streamCallback.
        val effectiveStreamCallback: StreamCallback? = if (persistAgentName != null && agentEventBus != null) {
            val bus = agentEventBus
            val wrappedName = persistAgentName
            val wrappedDepth = persistAgentDepth ?: 1
            val wrappedRunId = runId
            val wrappedSessionId = evSessionId
            val wrappedSourceAgentId = evSourceAgentId
            val delegate = streamCallback
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
            streamCallback
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
        val initialNativeToolSchemas: List<ToolSchema>? = run {
            val svc = toolPermissionsService ?: return@run null
            val nativeModeRaw = configService.getTyped(ConfigKeys.NATIVE_TOOLS_MODE, taskId)
            val nativeToolsMode = parseNativeToolsMode(nativeModeRaw)
            val modelDef = ModelDefinitions.getDefinition(effectiveProvider, effectiveModel)
            if (shouldUseNativeTools(nativeToolsMode, modelDef, effectiveModel, NativeToolsFallbackTracker.getFallbackSet())) {
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
                filtered
            } else {
                null
            }
        }
        var activeNativeToolSchemas = initialNativeToolSchemas
        activeNativeToolSchemas?.let { schemas ->
            logger.info { "[NATIVE_TOOLS] Enabled for taskId=$taskId, mode=$mode, schemas=${schemas.size}" }
        }

        // Wire turn state updater so TurnToolExecutor can set WAITING_FOR_PERMISSION
        turnToolExecutor.turnStateUpdater = { phase ->
            updateTurnState { copy(phase = phase) }
        }

        // Per-tool timing map for emitting ToolCalled events with accurate durations.
        // Populated by the wrapped tool listener below; consumed after executeToolCalls returns.
        val toolStartNanos = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val toolDurationsMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        try {
            while (iteration < maxIterations) {
                if (GlobalMetrics.isCancelled()) {
                    throw CancellationException("Operation cancelled by user")
                }
                iteration++

                logger.info { "[TURN_ITERATION] taskId=$taskId, iteration=$iteration/$maxIterations" }

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
                            agentName = agentName, sessionId = evSessionId
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
                        agentName = agentName, sessionId = evSessionId
                    )

                    // Call LLM
                    updateTurnState { copy(phase = TurnPhase.CALLING_MODEL) }
                    GlobalMetrics.setCurrentOperation(OperationInfo.TurnLLMCall(iteration, mode.name))

                    val llmCallStartNanos = System.nanoTime()
                    // Mutable so the empty-content recovery path below can re-bind it after pulling
                    // a JSON envelope out of the `thinking` field (qwen3 / Ollama edge case).
                    suspend fun callModelWithPrompt(
                        currentPrompt: TurnPrompt,
                        nativeSchemas: List<ToolSchema>?
                    ) = if (config.maxRetries > 0 && llmRetryHandler != null) {
                        val thinkingRequested = configService.getTyped<Boolean>(ConfigKeys.GENERAL_THINKING_ENABLED, taskId)
                        llmRetryHandler.callWithRetry(
                            provider = effectiveProvider,
                            model = effectiveModel,
                            messages = currentPrompt.messages,
                            systemPrompt = currentPrompt.systemPrompt,
                            taskId = taskId,
                            source = "AgentTurnLoop",
                            maxRetries = config.maxRetries,
                            baseDelayMs = config.retryBackoffMs,
                            responseFormat = responseFormat,
                            thinking = turnLLMCaller.resolveThinkingEnabled(effectiveProvider, effectiveModel, thinkingRequested),
                            reasoningEffort = profileOverrides?.reasoningEffort,
                            noEgressEnabled = configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, taskId),
                            stream = effectiveStreamCallback != null,
                            onChunk = effectiveStreamCallback,
                            kwargs = nativeSchemas?.let { mapOf("native_tools" to it) } ?: emptyMap()
                        )
                    } else {
                        turnLLMCaller.callLLM(
                            taskId = taskId,
                            mode = mode,
                            prompt = currentPrompt,
                            streamCallback = effectiveStreamCallback,
                            model = effectiveModel,
                            provider = effectiveProvider,
                            profileOverrides = profileOverrides,
                            nativeToolSchemas = nativeSchemas
                        )
                    }

                    var llmResponse: pl.jclab.refio.core.llm.LLMResponse
                    while (true) {
                        try {
                            // Pass the MUTABLE activeNativeToolSchemas (not the frozen
                            // iterationNativeToolSchemas snapshot) so a fallback catch below that
                            // sets it to null actually drops native tools on the retry — otherwise
                            // the rebuilt JSON-contract prompt would still ship native schemas.
                            llmResponse = callModelWithPrompt(prompt, activeNativeToolSchemas)
                            break
                        } catch (e: ToolsNotSupportedException) {
                            val modelKey = effectiveModel ?: "unknown"
                            NativeToolsFallbackTracker.markFallback(modelKey, e.message ?: "provider error")
                            if (activeNativeToolSchemas == null) {
                                throw e
                            }
                            logger.warn {
                                "[NATIVE_TOOLS_FALLBACK] taskId=$taskId, model=$modelKey — " +
                                    "rebuilding prompt and retrying on JSON path"
                            }
                            activeNativeToolSchemas = null
                            prompt = buildPrompt(
                                taskId, mode, iteration, maxIterations,
                                userContextRefs, runProfile, profileOverrides,
                                writeToolsExecutedInTurn, false,
                                agentName = agentName, sessionId = evSessionId
                            )
                        } catch (e: RefioError.LLMError) {
                            // Ollama's qwen tool-call template can 500 server-side on malformed
                            // function-call XML the model emits (observed: "XML syntax error ...
                            // <parameter> closed by </function>"). That is a per-prompt generation
                            // glitch, NOT a provider capability gap — the same model uses native
                            // tools fine on simpler prompts — so do a ONE-SHOT retry on the
                            // JSON-envelope path WITHOUT persisting a fallback (contrast the
                            // ToolsNotSupportedException branch above, which marks the model
                            // permanently via NativeToolsFallbackTracker). If native tools were
                            // already dropped, rethrow — nothing left to fall back to.
                            if (activeNativeToolSchemas == null || !isNativeToolTemplateParseError(e)) {
                                throw e
                            }
                            logger.warn {
                                "[NATIVE_TOOLS_PARSE_FALLBACK] taskId=$taskId, model=${effectiveModel ?: "unknown"} — " +
                                    "provider rejected a malformed tool-call template; one-shot JSON-path retry (no persistent fallback)"
                            }
                            activeNativeToolSchemas = null
                            prompt = buildPrompt(
                                taskId, mode, iteration, maxIterations,
                                userContextRefs, runProfile, profileOverrides,
                                writeToolsExecutedInTurn, false,
                                agentName = agentName, sessionId = evSessionId
                            )
                        }
                    }
                    val llmDurationMs = (System.nanoTime() - llmCallStartNanos) / 1_000_000

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

                        // Recovery before hard-fail: a guardian re-entry may have discarded a
                        // COMPLETE prior answer (stashed in candidateFinalResponse) and the
                        // re-entry then produced nothing new — empty content, no native tool
                        // calls, no new tool work. The answer the user already saw is the correct
                        // result; finalize it as success instead of failing the whole turn.
                        // Same restore condition as the terminal-text branch below
                        // (guardianReentryCount > 0 && no new tool work since the re-entry). This
                        // is what saved the "run git 3× then summarize" task from being marked
                        // FAILED after the judge wrongly re-entered a completed turn.
                        val recoverable = candidateFinalResponse
                            ?.takeIf { guardianReentryCount > 0 && usedTools.size <= usedToolsSizeAtLastReentry }
                        if (recoverable != null) {
                            logger.warn {
                                "[TURN_NATIVE_EMPTY_RECOVERED] taskId=$taskId, iteration=$iteration — " +
                                    "re-entry produced empty native response with no new tool work; " +
                                    "finalizing the pre-re-entry answer the user already saw."
                            }
                            val recoveredText = toolCallParser.extractTextResponse(recoverable.content)
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.ASSISTANT,
                                content = recoveredText.ifEmpty { recoverable.content },
                                thinking = turnResponseProcessor.resolveAssistantThinking(recoverable),
                                toolCalls = null,
                                tokensIn = recoverable.usage.inputTokens,
                                tokensOut = recoverable.usage.outputTokens,
                                cost = recoverable.cost,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = false, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
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
                        return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
                    }

                    if (mode != TaskMode.CHAT
                        && llmResponse.content.isBlank()
                        && llmResponse.nativeToolCalls.isNullOrEmpty()
                        && activeNativeToolSchemas == null) {
                        // Fallback 1: recover JSON from the thinking field. Some Ollama setups
                        // (qwen3 with think=true defaulted) emit the JSON envelope inside `thinking`
                        // while `content` stays empty. We accept recovery if thinking *looks like*
                        // a JSON envelope — either it parses to tool calls, OR its trimmed form
                        // starts with `{` (a final-response envelope without `actions`). The
                        // downstream pipeline handles both shapes.
                        //
                        // The explicit `activeNativeToolSchemas == null` check separates this from
                        // the symmetric native-tools branch above — they need different handling
                        // (this one can nudge the model toward the JSON envelope; the native branch
                        // can't because the model wasn't asked to emit one).
                        val thinking = llmResponse.thinking
                        val thinkingTrimmed = thinking?.trim().orEmpty()
                        val looksLikeEnvelope = thinkingTrimmed.startsWith("{")
                        val recoveredFromThinking = if (!thinking.isNullOrBlank()) {
                            runCatching { toolCallParser.extractToolCalls(thinking, mode, profileOverrides) }
                                .getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }

                        if (recoveredFromThinking.isNotEmpty() || looksLikeEnvelope) {
                            logger.warn {
                                "[TURN_EMPTY_CONTENT_RECOVERED] taskId=$taskId, iteration=$iteration, " +
                                    "recovered=${recoveredFromThinking.size} tool calls, envelope=$looksLikeEnvelope, " +
                                    "thinkingLength=${thinking?.length ?: 0}"
                            }
                            // Re-bind llmResponse so downstream code (extractToolCalls,
                            // ChatMessage persistence, etc.) sees the recovered envelope as content.
                            llmResponse = llmResponse.copy(content = thinking ?: "", thinking = null)
                            // Fall through to the regular tool-call extraction path.
                        } else {
                            val canRetryEmptyContent =
                                mode == TaskMode.AGENT &&
                                    plainTextNudgeCount < 2 &&
                                    iteration < maxIterations

                            if (canRetryEmptyContent) {
                                plainTextNudgeCount++
                                logger.warn {
                                    "[FORMAT_RETRY_NUDGE] taskId=$taskId, iteration=$iteration: " +
                                        "LLM returned empty content in JSON mode. " +
                                        "Nudge=$plainTextNudgeCount/2, finishReason=${llmResponse.finishReason}"
                                }
                                val resolvedThinking = turnResponseProcessor.resolveAssistantThinking(llmResponse)
                                if (!resolvedThinking.isNullOrBlank()) {
                                    chatMessageRepository.create(
                                        taskId = taskId,
                                        role = MessageRole.ASSISTANT,
                                        content = "",
                                        thinking = resolvedThinking,
                                        toolCalls = null,
                                        tokensIn = llmResponse.usage.inputTokens,
                                        tokensOut = llmResponse.usage.outputTokens,
                                        cost = llmResponse.cost,
                                        agentInstanceId = persistAgentInstanceId,
                                        agentName = persistAgentName,
                                        agentDepth = persistAgentDepth,
                                    )
                                }
                                chatMessageRepository.create(
                                    taskId = taskId,
                                    role = MessageRole.SYSTEM,
                                    content = "Your previous reply contained empty content in structured JSON mode. " +
                                        "Generate the full JSON envelope again from scratch. " +
                                        "Do not continue or patch the previous output. Reply with JSON only: " +
                                        "{\"actions\":[{\"tool\":\"NAME\",\"args\":{...}}]," +
                                        "\"response\":\"...\",\"intent\":\"implementation\"}. " +
                                        "No prose, no markdown fences.",
                                    toolCalls = null,
                                    agentInstanceId = persistAgentInstanceId,
                                    agentName = persistAgentName,
                                    agentDepth = persistAgentDepth,
                                )
                                continue
                            }

                            logger.error {
                                "[TURN_FAILED] Empty content from model in JSON mode " +
                                    "(mode=$mode, finishReason=${llmResponse.finishReason}, thinkingLength=${llmResponse.thinking?.length ?: 0})"
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
                        }
                    }

                    // Check if model invoked tools — two paths:
                    // 1. Native path: llmResponse.nativeToolCalls != null (set by adapter when tools were requested)
                    // 2. JSON-in-text path: classic ToolCallParser extraction from content
                    val nativeCalls = llmResponse.nativeToolCalls
                    val contentForExtraction: String
                    val toolCalls: List<ToolCallData>
                    val looksLikeJsonResponse: Boolean
                    val jsonEnvelopeInspection: pl.jclab.refio.core.services.turn.ToolCallParser.JsonEnvelopeInspection

                    if (nativeCalls != null) {
                        // Native function-calling path — skip JSON-in-text parsing entirely.
                        // nativeCalls.isEmpty() is a legitimate "model returned final prose, no
                        // tool execution needed" — falls through to the no-tools terminal branch
                        // below WITHOUT running the noisy JSON parser on the prose content.
                        logger.info { "[NATIVE_TOOLS_PATH] taskId=$taskId, iteration=$iteration, calls=${nativeCalls.size}" }
                        contentForExtraction = llmResponse.content
                        toolCalls = nativeCalls.map { native ->
                            ToolCallData(id = native.id, name = native.name, arguments = native.argumentsJson)
                        }
                        looksLikeJsonResponse = false
                        jsonEnvelopeInspection = toolCallParser.inspectJsonEnvelope("")
                    } else if (activeNativeToolSchemas != null && isJsonEnvelopeFallback(llmResponse.content)) {
                        // Native tools were requested but the model emitted a JSON envelope in text
                        // instead of native tool_calls. Treat this as a native-calling failure:
                        // mark the model in the session fallback cache so subsequent iterations use
                        // the JSON-in-text path, and parse the envelope we already have.
                        val modelKey = effectiveModel ?: "unknown"
                        NativeToolsFallbackTracker.markFallback(
                            modelKey,
                            "model ignored native tool_calls and emitted JSON envelope in text"
                        )
                        logger.warn {
                            "[NATIVE_TOOLS_FALLBACK] taskId=$taskId, model=$modelKey — parsing envelope " +
                                "from text content; future iterations will use JSON-in-text path"
                        }
                        activeNativeToolSchemas = null
                        contentForExtraction = toolCallParser.preprocessContent(llmResponse.content, taskId)
                        jsonEnvelopeInspection = toolCallParser.inspectJsonEnvelope(contentForExtraction)
                        toolCalls = toolCallParser.extractToolCalls(contentForExtraction, mode, profileOverrides)
                        looksLikeJsonResponse =
                            jsonEnvelopeInspection.hasJsonEnvelope || contentForExtraction.trim().startsWith("[")
                    } else {
                        // Classic JSON-in-text path
                        contentForExtraction = toolCallParser.preprocessContent(llmResponse.content, taskId)
                        jsonEnvelopeInspection = toolCallParser.inspectJsonEnvelope(contentForExtraction)
                        toolCalls = toolCallParser.extractToolCalls(contentForExtraction, mode, profileOverrides)
                        looksLikeJsonResponse =
                            jsonEnvelopeInspection.hasJsonEnvelope || contentForExtraction.trim().startsWith("[")
                    }

                    // Check for truncated response with incomplete JSON (JSON-in-text path only)
                    val isTruncatedWithIncompleteJson =
                        nativeCalls == null &&
                        llmResponse.finishReason == "length" &&
                        jsonEnvelopeInspection.hasJsonEnvelope &&
                        !jsonEnvelopeInspection.isComplete &&
                        toolCalls.isEmpty()

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
                        return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
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
                        val assistantContent = if (nativeCalls != null) {
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
                        chatMessageRepository.create(
                            taskId = taskId,
                            role = MessageRole.ASSISTANT,
                            content = assistantContent,
                            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                            toolCalls = toolCalls,
                            tokensIn = llmResponse.usage.inputTokens,
                            tokensOut = llmResponse.usage.outputTokens,
                            cost = llmResponse.cost,
                            agentInstanceId = persistAgentInstanceId,
                            agentName = persistAgentName,
                            agentDepth = persistAgentDepth,
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
                        val innerListener = listener
                        val effectiveListener: pl.jclab.refio.core.services.turn.TurnEventListener? =
                            if (innerListener != null) {
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

                        // Batch-level timing fallback (used when no caller listener is available).
                        val batchStartNanos = System.nanoTime()

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
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = "User rejected tool '${e.toolName}'. Reason: ${e.reason ?: "not specified"}",
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
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
                            return turnFinalizer.completeTurn(
                                taskId, result, listener, runId, parentRunId, depth,
                                persistAssistantMessage = false, metadata = subagentMetadata,
                                agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth,
                            )
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

                        // Emit ToolCalled events for Session Trace / Tool analytics.
                        // When per-tool timings aren't available (no caller listener) fall back to
                        // a batch-average estimate so the Debug panel still gets meaningful data.
                        val batchDurationMs = (System.nanoTime() - batchStartNanos) / 1_000_000
                        val fallbackPerToolMs = if (toolResults.isNotEmpty()) batchDurationMs / toolResults.size else 0L
                        for ((toolCall, resultData) in toolResults) {
                            val success = !resultData.content.startsWith("Error:")
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

                        // Working memory is recorded inside TurnToolExecutor with originId=subtaskId
                        // so every context section (MESSAGES / RECENT_WORK / WORKING_MEMORY) keys off
                        // the same subtask id. Recording here would overwrite those entries with
                        // originId=toolCall.id and desynchronize the identifiers.

                        // Handle AWAITING_RESPONSE from send_message tool
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

                                chatMessageRepository.create(
                                    taskId = taskId,
                                    role = MessageRole.SYSTEM,
                                    content = responseContent,
                                    agentInstanceId = persistAgentInstanceId,
                                    agentName = persistAgentName,
                                    agentDepth = persistAgentDepth,
                                )
                                logger.info { "[AWAITING_RESPONSE] Got response for $requestId: ${responseContent.take(100)}" }
                            }
                        }

                        // Generate batch summary for UI
                        val batchInput = toolResults.map { (call, resultData) ->
                            ToolCallWithResult(
                                toolName = call.name,
                                params = TurnJsonUtils.parseJsonToMap(call.arguments),
                                success = !resultData.content.startsWith("Error:"),
                                resultPreview = resultData.content.take(100)
                            )
                        }
                        val batchSummary = ToolBatchSummary.summarize(batchInput)
                        listener?.onToolBatchCompleted(taskId, batchSummary)

                        // Track error rate + definitive-loop detection + unified repetition tracker.
                        // Definitive loop = the SAME tool with the SAME arguments failing repeatedly.
                        // Varying either resets the counter so the agent can still explore freely.
                        var repetitionAbort: TurnGuardrails.LoopStatus.ABORT? = null
                        // Ids of write calls whose generated content was identical to the file (no-op).
                        // A no-op write is NOT consolidation progress (P1) — it must not reset the
                        // read-only spree counter below, otherwise a futile edit masks a read-forever loop.
                        val noopCallIds = mutableSetOf<String>()
                        for ((toolCall, result) in toolResults) {
                            if (result.noop) noopCallIds.add(toolCall.id)
                            val success = !result.content.startsWith("Error:")
                            errorTracker.recordResult(success)
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
                                        if (repetitionAbort == null) repetitionAbort = status
                                    TurnGuardrails.LoopStatus.OK -> Unit
                                }
                            }
                        }

                        if (repetitionAbort != null) {
                            logger.warn { "[REPETITION_ABORT] taskId=$taskId, incomplete=${repetitionAbort.incomplete}, reason=${repetitionAbort.reason}" }
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
                        }

                        val writeToolCalls = turnToolExecutor.countWriteToolCalls(toolCalls)
                        val verificationToolCalls = turnToolExecutor.countVerificationToolCalls(toolCalls)
                        writeToolsExecutedInTurn += writeToolCalls
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
                                chatMessageRepository.create(
                                    taskId = taskId,
                                    role = MessageRole.SYSTEM,
                                    content = buildConsolidationNudge(consecutiveGatheringCalls),
                                    // Tagged as guardian_nudge so the UI renders it as a gentle
                                    // "agent guidance" note (same category: internal steering),
                                    // not a full alarming SYSTEM bubble. See MessageMetadataExtractor.
                                    metadata = """{"type":"guardian_nudge"}""",
                                    toolCalls = null,
                                    agentInstanceId = persistAgentInstanceId,
                                    agentName = persistAgentName,
                                    agentDepth = persistAgentDepth,
                                )
                                consolidationNudgeCount++
                                consecutiveGatheringCalls = 0
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
                        }

                        // Check for mid-execution user messages after tool execution
                        if (pendingUserMessageQueue?.consumePending(taskId) == true) {
                            logger.info { "[MID_EXEC_INPUT] New user message detected after tool execution, nudging LLM (iteration=$iteration)" }
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = "[New user message above — address it next]",
                                toolCalls = null,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                            )
                        }

                        // Continue loop - model will see the results
                    } else {
                        // No tool calls - model responded with text

                        // Before exiting, check if user sent new messages during execution
                        if (pendingUserMessageQueue?.consumePending(taskId) == true) {
                            logger.info { "[MID_EXEC_INPUT] New user message detected before turn completion, continuing loop (iteration=$iteration)" }
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = "[New user message above — address it before finishing]",
                                toolCalls = null,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                            )
                            // Save the current assistant response before continuing
                            val textResponse = toolCallParser.extractTextResponse(llmResponse.content)
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.ASSISTANT,
                                content = textResponse.ifEmpty { llmResponse.content },
                                thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                                toolCalls = null,
                                tokensIn = llmResponse.usage.inputTokens,
                                tokensOut = llmResponse.usage.outputTokens,
                                cost = llmResponse.cost,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
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
                            nativeCalls.isNullOrEmpty() &&
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
                                return turnFinalizer.completeTurn(
                                    taskId, result, listener, runId, parentRunId, depth,
                                    persistAssistantMessage = true,
                                    metadata = subagentMetadata,
                                    agentInstanceId = persistAgentInstanceId,
                                    agentName = persistAgentName,
                                    agentDepth = persistAgentDepth,
                                )
                            }
                        }

                        // Cross-iteration text-repetition guard (see ConsecutiveTextRepetitionTracker).
                        // The model emitted a no-tool-call text; if it is byte-identical to the
                        // previous such text, the loop is stuck repeating itself (typically after a
                        // guardian/format nudge re-entry) and no further progress is possible — abort.
                        if (contentForExtraction.isNotBlank() &&
                            nativeCalls.isNullOrEmpty() &&
                            toolCalls.isEmpty()
                        ) {
                            val textRepeatStatus = textRepetitionTracker.record(contentForExtraction)
                            if (textRepeatStatus is TurnGuardrails.LoopStatus.ABORT) {
                                logger.warn { "[TEXT_REPETITION] taskId=$taskId, iteration=$iteration: ${textRepeatStatus.reason}" }
                                val result = TurnResult(
                                    success = false,
                                    response = textRepeatStatus.reason,
                                    iterations = iteration,
                                    tokensIn = totalTokensIn,
                                    tokensOut = totalTokensOut,
                                    cost = totalCost,
                                    toolsUsed = usedTools.distinct()
                                )
                                return turnFinalizer.completeTurn(
                                    taskId, result, listener, runId, parentRunId, depth,
                                    persistAssistantMessage = true,
                                    metadata = subagentMetadata,
                                    agentInstanceId = persistAgentInstanceId,
                                    agentName = persistAgentName,
                                    agentDepth = persistAgentDepth,
                                )
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
                                nativeCalls == null &&
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
                                nativeCalls.isNullOrEmpty() &&
                                toolCalls.isEmpty() &&
                                looksLikeTextEmbeddedToolCall(contentForExtraction)
                        val isRepeatedPlainText =
                            !looksLikeJsonResponse &&
                                contentForExtraction.isNotBlank() &&
                                lastPlainTextContent?.trim() == contentForExtraction.trim()
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
                            nativeCalls.isNullOrEmpty() &&
                                contentForExtraction.isNotBlank() &&
                                plainTextNudgeCount < 2 &&
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
                        // outputs, plainTextNudgeCount can only be >= 1 when one of those was
                        // detected — legitimate plain-text final answers in native mode never
                        // trigger a nudge and so can never trip this gate. `isRepeatedPlainText`
                        // gives an early-exit when the model returns byte-identical content after
                        // the first nudge (nothing further is going to change).
                        val shouldHardFailFormat =
                            nativeCalls.isNullOrEmpty() &&
                                contentForExtraction.isNotBlank() &&
                                !looksLikeJsonResponse &&
                                !hasIncompleteJsonEnvelope &&
                                (plainTextNudgeCount >= 2 || isRepeatedPlainText)

                        if (shouldHardFailFormat) {
                            logger.error {
                                "[FORMAT_UNRECOVERABLE] taskId=$taskId, iteration=$iteration: " +
                                    "model kept returning plain text after $plainTextNudgeCount nudge(s) " +
                                    "(repeated=$isRepeatedPlainText, toolsUsedSoFar=${usedTools.size}). Failing turn."
                            }
                            val result = TurnResult(
                                success = false,
                                response = "The model kept replying with plain text instead of the required JSON " +
                                    "envelope and never produced a tool call. Nudges were exhausted. " +
                                    "This usually means the selected model cannot follow the structured format " +
                                    "— try a model tuned for tool use (e.g. qwen3.5:9b, llama3.1) or enable native " +
                                    "function-calling.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnFinalizer.completeTurn(
                                taskId,
                                result,
                                listener,
                                runId,
                                parentRunId,
                                depth,
                                persistAssistantMessage = true,
                                metadata = subagentMetadata,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                            )
                        }

                        if (requiresFormatRetry) {
                            lastPlainTextContent = contentForExtraction
                            plainTextNudgeCount++
                            val retryReason = when {
                                isEffectivelyEmptyEnvelope -> "LLM returned empty JSON envelope (no actions, no response)"
                                nativeTextEmbeddedToolCall -> "LLM emitted tool call in text content instead of native tool_calls channel"
                                hasIncompleteJsonEnvelope -> "LLM returned incomplete JSON envelope"
                                else -> "LLM returned plain text without JSON structure"
                            }
                            logger.warn {
                                "[FORMAT_RETRY_NUDGE] taskId=$taskId, iteration=$iteration: " +
                                    "$retryReason. " +
                                    "Nudge=$plainTextNudgeCount/2, content='${contentForExtraction.take(80)}'"
                            }
                            val resolvedThinking = turnResponseProcessor.resolveAssistantThinking(llmResponse)
                            if (!resolvedThinking.isNullOrBlank()) {
                                chatMessageRepository.create(
                                    taskId = taskId,
                                    role = MessageRole.ASSISTANT,
                                    content = "",
                                    thinking = resolvedThinking,
                                    toolCalls = null,
                                    tokensIn = llmResponse.usage.inputTokens,
                                    tokensOut = llmResponse.usage.outputTokens,
                                    cost = llmResponse.cost,
                                    agentInstanceId = persistAgentInstanceId,
                                    agentName = persistAgentName,
                                    agentDepth = persistAgentDepth,
                                )
                            }
                            chatMessageRepository.create(
                                taskId = taskId,
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
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                            )
                            continue
                        }

                        if (nativeCalls == null && mode == TaskMode.AGENT && hasIncompleteJsonEnvelope) {
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
                            return turnFinalizer.completeTurn(
                                taskId,
                                result,
                                listener,
                                runId,
                                parentRunId,
                                depth,
                                persistAssistantMessage = true,
                                metadata = subagentMetadata,
                                agentInstanceId = persistAgentInstanceId,
                                agentName = persistAgentName,
                                agentDepth = persistAgentDepth,
                            )
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
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
                                verificationToolsExecutedAfterWrite = verificationToolsExecutedAfterWrite,
                                priorReentries = guardianReentryCount,
                                toolsUsedSizeAtPriorReentry = usedToolsSizeAtLastReentry,
                                completionCondition = taskRepository.getCompletionCondition(taskId)
                            )
                            when (val decision = completionGuardians.runChecks(guardianContext)) {
                                is GuardianDecision.Reenter -> {
                                    // Preserve the answer the user already saw before we drop it
                                    // by re-entering. Keep only the FIRST one — later re-entries
                                    // tend to degrade. Restored at finalize if the re-entry adds
                                    // no tool work (see [candidateFinalResponse]).
                                    if (candidateFinalResponse == null &&
                                        guardianTextResponse.ifEmpty { llmResponse.content }.isNotBlank()) {
                                        candidateFinalResponse = llmResponse
                                    }
                                    guardianReentryCount++
                                    usedToolsSizeAtLastReentry = usedTools.size
                                    chatMessageRepository.create(
                                        taskId = taskId,
                                        role = MessageRole.SYSTEM,
                                        content = decision.nudge,
                                        // Flag as an internal guardian steering message so the UI
                                        // renders a gentle "agent guidance" note instead of the full
                                        // SYSTEM bubble with the alarming "STOP — the turn is NOT
                                        // finished" wall of text (which is model-facing, not for the
                                        // user). Full text stays in DB. See OtherBubbleRenderer.
                                        metadata = """{"type":"guardian_nudge"}""",
                                        toolCalls = null,
                                        agentInstanceId = persistAgentInstanceId,
                                        agentName = persistAgentName,
                                        agentDepth = persistAgentDepth,
                                    )
                                    continue
                                }
                                is GuardianDecision.Incomplete -> {
                                    // Judge says the request was NOT delivered and no further
                                    // re-entry will help (single re-entry spent / prior nudge
                                    // produced no new tool call). Finalize the turn but flag it
                                    // INCOMPLETE so it is never recorded as SUCCESS. The
                                    // candidateFinalResponse restore below still keeps the best
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

                        // Model responded with text - save and complete turn
                        updateTurnState { copy(phase = TurnPhase.FINALIZING) }
                        logger.info { "[TURN_COMPLETE] taskId=$taskId, iterations=$iteration" }

                        // If a guardian re-entry discarded a good answer and the re-entry then
                        // added NO tool work (usedTools unchanged since the re-entry snapshot),
                        // finalize the original answer the user already saw instead of the
                        // degraded re-phrasing that followed the nudge. When the re-entry DID
                        // call a tool (usedTools grew), its later response incorporates that new
                        // work and is the right one to keep.
                        val effectiveResponse = candidateFinalResponse
                            ?.takeIf { guardianReentryCount > 0 && usedTools.size <= usedToolsSizeAtLastReentry }
                            ?: llmResponse
                        val textResponse = toolCallParser.extractTextResponse(effectiveResponse.content)
                        turnResponseProcessor.tryCreatePlanSubtasks(taskId, mode, executionMode, effectiveResponse, runProfile)

                        chatMessageRepository.create(
                            taskId = taskId,
                            role = MessageRole.ASSISTANT,
                            content = textResponse.ifEmpty { effectiveResponse.content },
                            thinking = turnResponseProcessor.resolveAssistantThinking(effectiveResponse),
                            toolCalls = null,
                            tokensIn = effectiveResponse.usage.inputTokens,
                            tokensOut = effectiveResponse.usage.outputTokens,
                            cost = effectiveResponse.cost,
                            agentInstanceId = persistAgentInstanceId,
                            agentName = persistAgentName,
                            agentDepth = persistAgentDepth,
                        )

                        val result = TurnResult(
                            success = turnIncompleteReason == null,
                            response = textResponse.ifEmpty { effectiveResponse.content },
                            iterations = iteration,
                            tokensIn = totalTokensIn,
                            tokensOut = totalTokensOut,
                            cost = totalCost,
                            toolsUsed = usedTools.distinct(),
                            incomplete = turnIncompleteReason != null
                        )

                        updateTurnState { copy(phase = TurnPhase.COMPLETED, tokensUsed = totalTokensIn + totalTokensOut) }
                        hookService?.trigger("on_agent_complete", mapOf(
                            "taskId" to taskId,
                            "mode" to mode.name,
                            "iterations" to iteration.toString(),
                            "agentName" to (profileOverrides?.subagentName ?: "default")
                        ))
                        val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = false, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
                        updateTurnState { TurnStateSnapshot() }
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
            val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
            updateTurnState { TurnStateSnapshot() }
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
                response = "Operation cancelled by user.",
                iterations = iteration,
                tokensIn = totalTokensIn,
                tokensOut = totalTokensOut,
                cost = totalCost,
                toolsUsed = usedTools.distinct()
            )
            val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
            updateTurnState { TurnStateSnapshot() }
            return finalResult
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
            response = "Error: Maximum iterations exceeded. The agent may be stuck in a loop.",
            iterations = iteration,
            tokensIn = totalTokensIn,
            tokensOut = totalTokensOut,
            cost = totalCost,
            toolsUsed = usedTools.distinct()
        )
        val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata, agentInstanceId = persistAgentInstanceId, agentName = persistAgentName, agentDepth = persistAgentDepth)
        updateTurnState { TurnStateSnapshot() }
        return finalResult
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
     * @param currentIteration Current iteration number (for AGENT mode iteration tracking, ADR 0019 P12)
     * @param maxIterations Maximum iterations (for AGENT mode iteration tracking, ADR 0019 P12)
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
        agentName: String? = null,
        sessionId: String? = null
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
            agentName = agentName,
            sessionId = sessionId
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

        val verification = taskVerifier.verifyCompletion(taskId, userRequest, llmContent)
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
    private fun getLastUserMessage(taskId: String, agentInstanceId: String? = null): String? {
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

    companion object {
        /**
         * After this many consecutive information-gathering calls (reads/searches) with no
         * write/persist/deliver, inject the consolidation nudge. Set above a normal multi-file
         * read pass (a thorough exploration legitimately reads ~10 files) so it fires on the
         * "read forever, never deliver" pathology, not on healthy exploration.
         */
        private const val READ_ONLY_CONSOLIDATION_THRESHOLD = 14

        /** Bound on consolidation nudges per turn — a soft hint, not a hard stop; never spam it. */
        private const val MAX_CONSOLIDATION_NUDGES = 2
    }
}

/**
 * Turn result data class.
 */
data class TurnResult(
    val success: Boolean,
    val response: String,
    val iterations: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val cost: Double,
    val toolsUsed: List<String> = emptyList(),
    val rejectedByUser: Boolean = false,
    val rejectedToolName: String? = null,
    val rejectionReason: String? = null,
    val unansweredQuestions: List<String>? = null,
    /**
     * True when the turn finalized WITHOUT delivering the user's request — a completion guardian
     * (e.g. NextSpeakerJudgeGuardian) determined the request was not delivered and no further
     * re-entry would help. Maps to [pl.jclab.refio.core.db.TaskStatus.INCOMPLETE], a distinct
     * state from FAILED (an error) and SUCCESS (delivered).
     */
    val incomplete: Boolean = false
)
