package pl.jclab.refio.core.services

import kotlinx.coroutines.flow.StateFlow
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.services.turn.GuardianRegistry
import pl.jclab.refio.core.services.turn.PromptSnapshot
import pl.jclab.refio.core.services.turn.ToolCallParser
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.services.turn.TurnExecutor
import pl.jclab.refio.core.services.turn.TurnFinalizer
import pl.jclab.refio.core.services.turn.TurnLLMCaller
import pl.jclab.refio.core.services.turn.TurnPromptBuilder
import pl.jclab.refio.core.services.turn.TurnResponseProcessor
import pl.jclab.refio.core.services.turn.TurnStateSnapshot
import pl.jclab.refio.core.services.turn.TurnSubagentValidator
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import pl.jclab.refio.core.tools.base.ToolRegistry
import java.util.*

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
    private val toolPermissionsService: ToolPermissionsService? = null,
    /**
     * Deterministic post-turn verification (project build/test) with a bounded repair loop.
     * Null disables the step. See [pl.jclab.refio.core.services.turn.TurnVerifier].
     */
    private val turnVerifier: pl.jclab.refio.core.services.turn.TurnVerifier? = null
) {
    /**
     * The turn loop itself. This class is a thin facade over it: it owns the same dependency set
     * (minus the validator and tool-description builder used only at the facade boundary) and the
     * turn-state / prompt-snapshot StateFlows the UI observes.
     */
    private val turnExecutor = TurnExecutor(
        llmClient = llmClient,
        chatMessageRepository = chatMessageRepository,
        taskRepository = taskRepository,
        subtaskRepository = subtaskRepository,
        configService = configService,
        toolRegistry = toolRegistry,
        taskVerifier = taskVerifier,
        turnPromptBuilder = turnPromptBuilder,
        toolCallParser = toolCallParser,
        turnToolExecutor = turnToolExecutor,
        turnLLMCaller = turnLLMCaller,
        turnResponseProcessor = turnResponseProcessor,
        turnFinalizer = turnFinalizer,
        completionGuardians = completionGuardians,
        tokenEstimator = tokenEstimator,
        conversationCompactor = conversationCompactor,
        llmRetryHandler = llmRetryHandler,
        workingMemoryIntegration = workingMemoryIntegration,
        pendingUserMessageQueue = pendingUserMessageQueue,
        agentEventBus = agentEventBus,
        hookService = hookService,
        toolPermissionsService = toolPermissionsService,
        turnVerifier = turnVerifier
    )

    /** Live turn state for UI observation; owned by [TurnExecutor]. */
    val turnState: StateFlow<TurnStateSnapshot> get() = turnExecutor.turnState

    /** Last prompt snapshot for UI inspection; owned by [TurnExecutor]. */
    val lastPromptSnapshot: StateFlow<PromptSnapshot?> get() = turnExecutor.lastPromptSnapshot

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
            turnExecutor.execute(
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
                source = TurnExecutor.TurnSource.RUN,
                userMessageStrategy = TurnExecutor.UserMessageStrategy { userInput },
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

        // Execute turn loop (continues from current history state).
        // Mark the turn active so background RAG indexing/embedding yields the SQLite WAL writer-lock
        // for its duration, exactly as runTurn does — an interactive resume-after-approval is still a
        // live agent turn, and skipping this let concurrent RAG writes stall tool subtask-status
        // writes. try/finally keeps the count balanced.
        GlobalMetrics.beginAgentTurn()
        return try {
            turnExecutor.execute(
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
                source = TurnExecutor.TurnSource.CONTINUE,
                userMessageStrategy = TurnExecutor.UserMessageStrategy { turnExecutor.getLastUserMessage(taskId, profileOverrides?.agentInstanceId) },
                emitSessionId = emitSessionId,
                emitSourceAgentId = emitSourceAgentId,
                agentName = agentName
            )
        } finally {
            GlobalMetrics.endAgentTurn()
        }
    }

    /**
     * Delegates to [TurnExecutor.isNativeToolTemplateParseError]. Kept on the facade because it is
     * unit-tested directly against this class.
     */
    internal fun isNativeToolTemplateParseError(error: Throwable): Boolean =
        turnExecutor.isNativeToolTemplateParseError(error)
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
    val incomplete: Boolean = false,
    /**
     * Outcome of the deterministic post-turn verification step (project build/test run by the
     * loop code after a file-writing AGENT turn). Null when verification was not applicable to
     * this exit path; [pl.jclab.refio.core.debug.VerificationSummary.NOT_RUN] when it was
     * considered but never executed. `result == "FAILED"` means the repair rounds were exhausted
     * and the turn ended as a verification failure - never faked as success.
     */
    val verification: pl.jclab.refio.core.debug.VerificationSummary? = null
)
