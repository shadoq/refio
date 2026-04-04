package pl.jclab.refio.core.services

// Import TurnLoopConfigs from core.services (not turn/ package)
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
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
import pl.jclab.refio.core.services.turn.TurnFinalizer
import pl.jclab.refio.core.services.turn.TurnGuardrails
import pl.jclab.refio.core.services.turn.TurnJsonUtils
import pl.jclab.refio.core.services.turn.TurnLLMCaller
import pl.jclab.refio.core.services.turn.TurnNudgeBuilder
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
import pl.jclab.refio.core.logging.dualLogger
import java.util.*
import java.util.concurrent.CancellationException

// Type aliases for turn/ package classes
private typealias ToolErrorTracker = TurnGuardrails.ToolErrorTracker
private typealias LoopDetector = TurnGuardrails.LoopDetector
private typealias LoopStatus = TurnGuardrails.LoopStatus
private typealias AssistantIntent = TurnGuardrails.AssistantIntent

private val logger = dualLogger("AgentTurnLoop")

/** Tool names that are read-only — used by read-only budget guard (ADR-0044). */
private val READ_ONLY_TOOL_NAMES = setOf("read_file", "read_directory", "file_search", "grep_search", "view_diff")
private val TRANSIENT_HTTP_PATTERN = Regex("(?i)(timeout|timed out|503|502|429|connection refused|ECONNRESET|ECONNREFUSED)")


/**
 * Adapter to convert between TurnPrompt and LLMCallPrompt.
 */
private object TurnPromptAdapter {
    fun toLLMCallPrompt(prompt: TurnPrompt) = pl.jclab.refio.core.services.turn.LLMCallPrompt(
        systemPrompt = prompt.systemPrompt,
        messages = prompt.messages
    )
}

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

    // ADR-0028: Optional dependencies for enhanced turn loop
    private val tokenEstimator: PromptTokenEstimator = PromptTokenEstimator(),
    private val conversationCompactor: ConversationCompactor? = null,
    private val llmRetryHandler: LLMRetryHandler? = null,
    private val workingMemoryIntegration: WorkingMemoryIntegration? = null,
    private val pendingUserMessageQueue: PendingUserMessageQueue? = null
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _turnState = MutableStateFlow(TurnStateSnapshot())
    val turnState: StateFlow<TurnStateSnapshot> = _turnState.asStateFlow()

    private val _lastPromptSnapshot = MutableStateFlow<PromptSnapshot?>(null)
    val lastPromptSnapshot: StateFlow<PromptSnapshot?> = _lastPromptSnapshot.asStateFlow()

    private fun updateTurnState(update: TurnStateSnapshot.() -> TurnStateSnapshot) {
        _turnState.value = _turnState.value.update()
    }

    /**
     * Listener for turn events (tool execution, streaming, etc.).
     */
    interface TurnEventListener : pl.jclab.refio.core.services.turn.TurnCompletionListener {
        fun onTurnStarted(
            taskId: String,
            mode: TaskMode,
            runId: String,
            parentRunId: String?,
            depth: Int
        ) {}

        fun onToolExecutionStarted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData) {}
        fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {}
        fun onToolExecutionCompleted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData, result: String, success: Boolean) {}
        fun onStreamChunk(taskId: String, delta: String, accumulated: String) {}

        companion object {
            /**
             * Create from turn/ package TurnEventListener (inverse of [toTurnEventListener]).
             */
            fun fromTurnEventListener(source: pl.jclab.refio.core.services.turn.TurnEventListener): TurnEventListener =
                object : TurnEventListener {
                    override fun onTurnStarted(taskId: String, mode: TaskMode, runId: String, parentRunId: String?, depth: Int) {
                        source.onTurnStarted(taskId, mode, runId, parentRunId, depth)
                    }
                    override fun onToolExecutionStarted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData) {
                        source.onToolExecutionStarted(taskId, toolCall)
                    }
                    override fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {
                        source.onToolStreamChunk(taskId, toolCallId, delta, accumulated)
                    }
                    override fun onToolExecutionCompleted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData, result: String, success: Boolean) {
                        source.onToolExecutionCompleted(taskId, toolCall, result, success)
                    }
                    override fun onStreamChunk(taskId: String, delta: String, accumulated: String) {
                        source.onStreamChunk(taskId, delta, accumulated)
                    }
                    override fun onTurnCompleted(taskId: String, result: pl.jclab.refio.core.services.TurnResult, runId: String, parentRunId: String?, depth: Int) {
                        source.onTurnCompleted(taskId, result, runId, parentRunId, depth)
                    }
                }
        }

        /**
         * Convert to turn/ package TurnEventListener for compatibility.
         */
        fun toTurnEventListener(): pl.jclab.refio.core.services.turn.TurnEventListener =
            object : pl.jclab.refio.core.services.turn.TurnEventListener {
                override fun onTurnStarted(taskId: String, mode: pl.jclab.refio.core.db.TaskMode, runId: String, parentRunId: String?, depth: Int) {
                    this@TurnEventListener.onTurnStarted(taskId, mode, runId, parentRunId, depth)
                }

                override fun onToolExecutionStarted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData) {
                    this@TurnEventListener.onToolExecutionStarted(taskId, toolCall)
                }

                override fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {
                    this@TurnEventListener.onToolStreamChunk(taskId, toolCallId, delta, accumulated)
                }

                override fun onToolExecutionCompleted(taskId: String, toolCall: pl.jclab.refio.core.db.ToolCallData, result: String, success: Boolean) {
                    this@TurnEventListener.onToolExecutionCompleted(taskId, toolCall, result, success)
                }

                override fun onStreamChunk(taskId: String, delta: String, accumulated: String) {
                    this@TurnEventListener.onStreamChunk(taskId, delta, accumulated)
                }
            }
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
        profileOverrides: TurnProfileOverrides? = null
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

        // Step 1: Save user message to history
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.USER,
            content = userInput
        )

        // Step 2: Execute turn loop
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
            source = TurnSource.RUN,
            userMessageStrategy = UserMessageStrategy { userInput }
        )
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
        profileOverrides: TurnProfileOverrides? = null
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
            userMessageStrategy = UserMessageStrategy { getLastUserMessage(taskId) }
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
        userMessageStrategy: UserMessageStrategy
    ): TurnResult {
        // Initialize loop variables
        var iteration = 0
        val config = TurnLoopConfigs.forMode(mode)
        val maxIterations = turnLLMCaller.resolveMaxIterations(config, profileOverrides)
        val errorTracker = ToolErrorTracker(windowSize = config.errorWindowSize)
        val loopDetector = LoopDetector(
            maxConsecutiveRepeats = config.loopMaxConsecutiveRepeats,
            maxSameToolTotal = config.loopMaxSameToolTotal,
            warnConsecutiveThreshold = config.loopWarnConsecutiveThreshold,
            warnTotalThreshold = config.loopWarnTotalThreshold
        )
        var formatRetryCount = 0
        var writeToolsExecutedInTurn = 0
        var consecutiveReadOnlyIterations = 0
        var consecutiveToolErrors = 0
        var lastToolResultsHadTransientHttpError = false
        var transientErrorNudgeCount = 0
        var intentNudgeCount = 0
        var plainTextNudgeCount = 0
        var toolErrorNudgeCount = 0
        var lastIterationHadToolErrors = false
        var totalTokensIn = 0
        var totalTokensOut = 0
        var totalCost = 0.0
        val usedTools = mutableListOf<String>()
        val maxConsecutiveToolErrors = configService.getTyped(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS, taskId)
        val subagentMetadata: String? = if (runProfile == TurnRunProfile.SUBAGENT) {
            val name = profileOverrides?.subagentName ?: "subagent"
            """{"subagent_name":"$name"}"""
        } else null
        val (effectiveModel, effectiveProvider) = turnLLMCaller.resolveModelSelection(
            mode = mode,
            taskId = taskId,
            model = model,
            provider = provider,
            profileOverrides = profileOverrides
        )
        val responseFormat = turnLLMCaller.resolveResponseFormat(mode, effectiveProvider)

        // Wire turn state updater so TurnToolExecutor can set WAITING_FOR_PERMISSION
        turnToolExecutor.turnStateUpdater = { phase ->
            updateTurnState { copy(phase = phase) }
        }

        try {
            while (iteration < maxIterations) {
                if (GlobalMetrics.isCancelled()) {
                    throw CancellationException("Operation cancelled by user")
                }
                iteration++

                logger.info { "[TURN_ITERATION] taskId=$taskId, iteration=$iteration/$maxIterations" }

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

                    // Auto-compact if context window is filling
                    if (config.enableAutoCompaction && conversationCompactor != null) {
                        val maxTokens = tokenEstimator.getSafeTokenLimit(effectiveProvider, effectiveModel)
                        val tempPrompt = buildPrompt(
                            taskId, mode, iteration, maxIterations,
                            userContextRefs, runProfile, profileOverrides,
                            writeToolsExecutedInTurn
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

                    val prompt = buildPrompt(
                        taskId, mode, iteration, maxIterations,
                        userContextRefs, runProfile, profileOverrides,
                        writeToolsExecutedInTurn
                    )

                    // Call LLM
                    updateTurnState { copy(phase = TurnPhase.CALLING_MODEL) }
                    GlobalMetrics.setCurrentOperation(OperationInfo.TurnLLMCall(iteration, mode.name))

                    val llmPrompt = TurnPromptAdapter.toLLMCallPrompt(prompt)
                    val llmResponse = if (config.maxRetries > 0 && llmRetryHandler != null) {
                        llmRetryHandler.callWithRetry(
                            provider = effectiveProvider,
                            model = effectiveModel,
                            messages = prompt.messages,
                            systemPrompt = prompt.systemPrompt,
                            taskId = taskId,
                            source = "AgentTurnLoop",
                            maxRetries = config.maxRetries,
                            baseDelayMs = config.retryBackoffMs,
                            responseFormat = responseFormat,
                            stream = streamCallback != null,
                            onChunk = streamCallback
                        )
                    } else {
                        turnLLMCaller.callLLM(
                            taskId = taskId,
                            mode = mode,
                            prompt = llmPrompt,
                            streamCallback = streamCallback,
                            model = effectiveModel,
                            provider = effectiveProvider,
                            profileOverrides = profileOverrides
                        )
                    }

                    if (GlobalMetrics.isCancelled()) {
                        throw CancellationException("Operation cancelled by user")
                    }

                    totalTokensIn += llmResponse.usage.inputTokens
                    totalTokensOut += llmResponse.usage.outputTokens
                    totalCost += llmResponse.cost

                    if (mode != TaskMode.CHAT && llmResponse.content.isBlank()) {
                        if (formatRetryCount < config.maxFormatRetries) {
                            formatRetryCount++
                            logger.warn {
                                "[TURN_EMPTY_CONTENT] taskId=$taskId, iteration=$iteration, " +
                                    "retry=$formatRetryCount/${config.maxFormatRetries}, " +
                                    "finishReason=${llmResponse.finishReason}, " +
                                    "thinkingLength=${llmResponse.thinking?.length ?: 0}"
                            }
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = TurnGuardrails.buildInvalidFormatMessage(mode),
                                toolCalls = null
                            )
                            continue
                        }

                        logger.error {
                            "[TURN_FAILED] Empty content from model in JSON mode " +
                                "(mode=$mode, finishReason=${llmResponse.finishReason}, thinkingLength=${llmResponse.thinking?.length ?: 0})"
                        }
                        val result = TurnResult(
                            success = false,
                            response = "Model repeatedly returned empty content in structured mode. " +
                                "No tool actions or final response could be parsed.",
                            iterations = iteration,
                            tokensIn = totalTokensIn,
                            tokensOut = totalTokensOut,
                            cost = totalCost,
                            toolsUsed = usedTools.distinct()
                        )
                        return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
                    }

                    // Check if model invoked tools
                    val contentForExtraction = toolCallParser.preprocessContent(llmResponse.content, taskId)
                    val toolCalls = toolCallParser.extractToolCalls(contentForExtraction, mode, profileOverrides)

                    // Check for truncated response with incomplete JSON
                    val isTruncatedWithIncompleteJson =
                        llmResponse.finishReason == "length" &&
                        contentForExtraction.trim().startsWith("{") &&
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
                        return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
                    }

                    // Format retry logic
                    if (
                        toolCalls.isEmpty() &&
                        toolCallParser.shouldRequestRetry(contentForExtraction, mode) &&
                        formatRetryCount < config.maxFormatRetries
                    ) {
                        formatRetryCount++
                        chatMessageRepository.create(
                            taskId = taskId,
                            role = MessageRole.SYSTEM,
                            content = TurnGuardrails.buildInvalidFormatMessage(mode),
                            toolCalls = null
                        )
                        logger.warn { "[TOOL_CALLS] Invalid format detected, requesting retry (attempt=$formatRetryCount)" }
                        continue
                    }

                    if (toolCalls.isNotEmpty()) {
                        // Loop detection before executing
                        for (toolCall in toolCalls) {
                            val loopStatus = loopDetector.recordToolCall(toolCall.name, toolCall.arguments)
                            when (loopStatus) {
                                is TurnGuardrails.LoopStatus.ABORT -> {
                                    logger.warn { "[LOOP_DETECTED] ${loopStatus.reason}" }
                                    val result = TurnResult(
                                        success = false,
                                        response = "Loop detected: ${loopStatus.reason}. The agent may be stuck. " +
                                            "Please rephrase your request or provide more specific instructions.",
                                        iterations = iteration,
                                        tokensIn = totalTokensIn,
                                        tokensOut = totalTokensOut,
                                        cost = totalCost,
                                        toolsUsed = usedTools.distinct()
                                    )
                                    return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
                                }
                                is TurnGuardrails.LoopStatus.WARN -> {
                                    logger.warn { "[LOOP_WARNING] ${loopStatus.message}" }
                                }
                                TurnGuardrails.LoopStatus.OK -> { /* continue */ }
                            }
                        }

                        // Save assistant message with tool calls
                        logger.info { "[TOOL_CALLS] taskId=$taskId, count=${toolCalls.size}" }
                        chatMessageRepository.create(
                            taskId = taskId,
                            role = MessageRole.ASSISTANT,
                            content = llmResponse.content,
                            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                            toolCalls = toolCalls,
                            tokensIn = llmResponse.usage.inputTokens,
                            tokensOut = llmResponse.usage.outputTokens,
                            cost = llmResponse.cost
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

                        val toolResults = try {
                            turnToolExecutor.executeToolCalls(
                                taskId = taskId,
                                toolCalls = toolCalls,
                                mode = mode,
                                executionMode = executionMode,
                                listener = listener?.toTurnEventListener(),
                                iteration = iteration,
                                config = config,
                                profileOverrides = profileOverrides,
                                runId = runId,
                                depth = depth
                            )
                        } catch (e: ToolRejectedException) {
                            logger.info { "[REJECTED] User rejected tool '${e.toolName}': ${e.reason ?: "no reason"}" }
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = "User rejected tool '${e.toolName}'. Reason: ${e.reason ?: "not specified"}"
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
                                persistAssistantMessage = false, metadata = subagentMetadata
                            )
                        }

                        // Save tool results
                        for ((toolCall, resultData) in toolResults) {
                            chatMessageRepository.createToolResult(
                                taskId = taskId,
                                toolCallId = toolCall.id,
                                result = resultData.content,
                                isSummarized = resultData.isSummarized,
                                rawOutput = resultData.rawOutput,
                                metadata = resultData.metadata
                            )
                        }

                        // Record to working memory
                        if (config.enableWorkingMemory && workingMemoryIntegration != null) {
                            for ((toolCall, resultData) in toolResults) {
                                if (!resultData.content.startsWith("Error:")) {
                                    val params = TurnJsonUtils.parseJsonToMap(toolCall.arguments)
                                    workingMemoryIntegration.recordToolKnowledge(
                                        taskId = taskId,
                                        toolName = toolCall.name,
                                        params = params,
                                        result = resultData.rawOutput ?: resultData.content,
                                        iteration = iteration
                                    )
                                }
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
                        listener?.toTurnEventListener()?.onToolBatchCompleted(taskId, batchSummary)

                        // Track error rate
                        for ((_, result) in toolResults) {
                            val success = !result.content.startsWith("Error:")
                            errorTracker.recordResult(success)
                            consecutiveToolErrors = if (success) 0 else consecutiveToolErrors + 1
                        }

                        writeToolsExecutedInTurn += turnToolExecutor.countWriteToolCalls(toolCalls)

                        // Track transient HTTP errors for retry nudge
                        lastToolResultsHadTransientHttpError = toolResults.any { (call, result) ->
                            call.name == "http_request" && TRANSIENT_HTTP_PATTERN.containsMatchIn(result.content)
                        }

                        // Read-only budget guard (ADR-0044): track consecutive read-only iterations
                        val hasOnlyReadTools = toolCalls.all { it.name in READ_ONLY_TOOL_NAMES }
                        if (hasOnlyReadTools) {
                            consecutiveReadOnlyIterations++
                        } else {
                            consecutiveReadOnlyIterations = 0
                        }

                        if (TurnGuardrails.isReadOnlyLoop(mode, consecutiveReadOnlyIterations, config.maxConsecutiveReadOnlyIterations)) {
                            logger.warn {
                                "[READ_ONLY_LOOP] taskId=$taskId, consecutiveReadOnly=$consecutiveReadOnlyIterations, " +
                                    "threshold=${config.maxConsecutiveReadOnlyIterations}. Nudging agent to write."
                            }
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = TurnNudgeBuilder.buildReadingBudgetExceededMessage(),
                                toolCalls = null
                            )
                            consecutiveReadOnlyIterations = 0
                        }

                        if (consecutiveToolErrors >= maxConsecutiveToolErrors) {
                            val result = TurnResult(
                                success = false,
                                response = "Too many consecutive tool errors ($consecutiveToolErrors/$maxConsecutiveToolErrors). Please review tool usage and arguments.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
                        }

                        // Check for mid-execution user messages after tool execution
                        if (pendingUserMessageQueue?.consumePending(taskId) == true) {
                            logger.info { "[MID_EXEC_INPUT] New user message detected after tool execution, nudging LLM (iteration=$iteration)" }
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = "[New user message above — address it next]",
                                toolCalls = null
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
                                toolCalls = null
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
                                cost = llmResponse.cost
                            )
                            continue
                        }

                        // Nudge agent to retry after transient HTTP error instead of giving up
                        if (mode == TaskMode.AGENT && lastToolResultsHadTransientHttpError && transientErrorNudgeCount < 1) {
                            transientErrorNudgeCount++
                            lastToolResultsHadTransientHttpError = false
                            logger.info {
                                "[TRANSIENT_ERROR_NUDGE] taskId=$taskId, iteration=$iteration: " +
                                    "Agent gave up after transient HTTP error, nudging to retry"
                            }
                            // Save the current assistant response before nudging
                            val textResponse = toolCallParser.extractTextResponse(llmResponse.content)
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.ASSISTANT,
                                content = textResponse.ifEmpty { llmResponse.content },
                                thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                                toolCalls = null,
                                tokensIn = llmResponse.usage.inputTokens,
                                tokensOut = llmResponse.usage.outputTokens,
                                cost = llmResponse.cost
                            )
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = TurnNudgeBuilder.buildTransientHttpErrorNudgeMessage(),
                                toolCalls = null
                            )
                            continue
                        }

                        if (mode != TaskMode.CHAT && toolCallParser.isMeaninglessJson(contentForExtraction)) {
                            if (formatRetryCount < config.maxFormatRetries) {
                                formatRetryCount++
                                logger.warn {
                                    "[MEANINGLESS_JSON] taskId=$taskId, iteration=$iteration, " +
                                        "retry=$formatRetryCount/${config.maxFormatRetries}, " +
                                        "content='${contentForExtraction.take(100)}'"
                                }
                                chatMessageRepository.create(
                                    taskId = taskId,
                                    role = MessageRole.SYSTEM,
                                    content = TurnGuardrails.buildInvalidFormatMessage(mode),
                                    toolCalls = null
                                )
                                continue
                            }

                            logger.error {
                                "[MEANINGLESS_JSON_ABORT] taskId=$taskId, retries=$formatRetryCount, " +
                                    "content='${contentForExtraction.take(100)}'"
                            }
                            val result = TurnResult(
                                success = false,
                                response = "Model repeatedly returned empty or meaningless JSON. " +
                                    "This usually means the selected model does not support the required structured output.",
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
                                metadata = subagentMetadata
                            )
                        }

                        // Check error rate abort
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
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
                        }

                        // Check for missing intent field (AGENT mode)
                        if (mode == TaskMode.AGENT &&
                            toolCallParser.hasExplicitEmptyActionsArray(contentForExtraction) &&
                            toolCallParser.extractAssistantIntent(contentForExtraction) == AssistantIntent.UNKNOWN
                        ) {
                            if (intentNudgeCount < 2) {
                                intentNudgeCount++
                                logger.warn {
                                    "[AGENT_INTENT_MISSING] ${source.name.lowercase()}: Empty actions without intent on iteration=$iteration. " +
                                        "Nudge=$intentNudgeCount/2"
                                }
                                chatMessageRepository.create(
                                    taskId = taskId,
                                    role = MessageRole.SYSTEM,
                                    content = TurnNudgeBuilder.buildMissingIntentNudgeMessage(),
                                    toolCalls = null
                                )
                                continue
                            }

                            val result = TurnResult(
                                success = false,
                                response = "Missing required 'intent' field for empty-actions response. " +
                                    "Return JSON with intent=implementation|analysis.",
                                iterations = iteration,
                                tokensIn = totalTokensIn,
                                tokensOut = totalTokensOut,
                                cost = totalCost,
                                toolsUsed = usedTools.distinct()
                            )
                            return turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
                        }

                        // Nudge when LLM returns plain text without any JSON structure
                        // (common with weaker models that "forget" the required format mid-task)
                        if (mode == TaskMode.AGENT &&
                            !contentForExtraction.trim().let { it.startsWith("{") || it.startsWith("[") } &&
                            contentForExtraction.isNotBlank() &&
                            plainTextNudgeCount < 2
                        ) {
                            plainTextNudgeCount++
                            logger.warn {
                                "[PLAIN_TEXT_NUDGE] taskId=$taskId, iteration=$iteration: " +
                                    "LLM returned plain text without JSON structure. " +
                                    "Nudge=$plainTextNudgeCount/2, content='${contentForExtraction.take(80)}'"
                            }
                            // Save the current assistant response before nudging
                            val textResponse = toolCallParser.extractTextResponse(llmResponse.content)
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.ASSISTANT,
                                content = textResponse.ifEmpty { llmResponse.content },
                                thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                                toolCalls = null,
                                tokensIn = llmResponse.usage.inputTokens,
                                tokensOut = llmResponse.usage.outputTokens,
                                cost = llmResponse.cost
                            )
                            chatMessageRepository.create(
                                taskId = taskId,
                                role = MessageRole.SYSTEM,
                                content = TurnNudgeBuilder.buildPlainTextNudgeMessage(),
                                toolCalls = null
                            )
                            continue
                        }

                        // NO_CHANGES_NEEDED reconfirmation: let LLM reconsider once
                        // Task verification
                        val userMessageForVerification = userMessageStrategy.getUserMessage(taskId)
                        if (!verifyTaskCompletionIfNeeded(taskId, iteration, userMessageForVerification, llmResponse.content, writeToolsExecutedInTurn)) {
                            continue
                        }

                        // Model responded with text - save and complete turn
                        updateTurnState { copy(phase = TurnPhase.FINALIZING) }
                        logger.info { "[TURN_COMPLETE] taskId=$taskId, iterations=$iteration" }

                        val textResponse = toolCallParser.extractTextResponse(llmResponse.content)
                        turnResponseProcessor.tryCreatePlanSubtasks(taskId, mode, executionMode, llmResponse)

                        chatMessageRepository.create(
                            taskId = taskId,
                            role = MessageRole.ASSISTANT,
                            content = textResponse.ifEmpty { llmResponse.content },
                            thinking = turnResponseProcessor.resolveAssistantThinking(llmResponse),
                            toolCalls = null,
                            tokensIn = llmResponse.usage.inputTokens,
                            tokensOut = llmResponse.usage.outputTokens,
                            cost = llmResponse.cost
                        )

                        val result = TurnResult(
                            success = true,
                            response = textResponse.ifEmpty { llmResponse.content },
                            iterations = iteration,
                            tokensIn = totalTokensIn,
                            tokensOut = totalTokensOut,
                            cost = totalCost,
                            toolsUsed = usedTools.distinct()
                        )

                        updateTurnState { copy(phase = TurnPhase.COMPLETED, tokensUsed = totalTokensIn + totalTokensOut) }
                        val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = false, metadata = subagentMetadata)
                        updateTurnState { TurnStateSnapshot() }
                        return finalResult
                    }
                } finally {
                    GlobalMetrics.endOperation(iterationToken)
                }
            }
        } catch (e: CancellationException) {
            updateTurnState { copy(phase = TurnPhase.FAILED) }
            val result = TurnResult(
                success = false,
                response = "Operation cancelled by user.",
                iterations = iteration,
                tokensIn = totalTokensIn,
                tokensOut = totalTokensOut,
                cost = totalCost,
                toolsUsed = usedTools.distinct()
            )
            val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
            updateTurnState { TurnStateSnapshot() }
            return finalResult
        }

        // Max iterations exceeded
        updateTurnState { copy(phase = TurnPhase.FAILED) }
        logger.warn { "[TURN_MAX_ITERATIONS] taskId=$taskId, exceeded $maxIterations iterations" }
        val result = TurnResult(
            success = false,
            response = "Error: Maximum iterations exceeded. The agent may be stuck in a loop.",
            iterations = iteration,
            tokensIn = totalTokensIn,
            tokensOut = totalTokensOut,
            cost = totalCost,
            toolsUsed = usedTools.distinct()
        )
        val finalResult = turnFinalizer.completeTurn(taskId, result, listener, runId, parentRunId, depth, persistAssistantMessage = true, metadata = subagentMetadata)
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
        writeToolsExecutedInTurn: Int = 0
    ): TurnPrompt {
        val turnPrompt = turnPromptBuilder.buildPrompt(
            taskId = taskId,
            mode = mode,
            currentIteration = currentIteration,
            maxIterations = maxIterations,
            userContextRefs = userContextRefs,
            runProfile = runProfile,
            profileOverrides = profileOverrides,
            writeToolsExecutedInTurn = writeToolsExecutedInTurn
        )

        // Build PromptSnapshot for UI inspection
        val contextTrace = turnPromptBuilder.getLastContextTrace()
        if (contextTrace != null) {
            val systemTokens = tokenEstimator.estimateString(turnPrompt.systemPrompt)
            val messagesTokens = turnPrompt.messages.sumOf { tokenEstimator.estimateString(it.content) }
            val toolNames = toolRegistry.getAllTools().map { it.name }
            _lastPromptSnapshot.value = PromptSnapshot(
                taskId = taskId,
                iteration = currentIteration,
                systemPromptTokens = systemTokens,
                messagesTokens = messagesTokens,
                totalTokens = systemTokens + messagesTokens,
                toolCount = toolNames.size,
                toolNames = toolNames,
                contextTrace = contextTrace,
                systemPromptPreview = turnPrompt.systemPrompt.take(500)
            )
        }

        return TurnPrompt(
            systemPrompt = turnPrompt.systemPrompt,
            messages = turnPrompt.messages
        )
    }


    /**
     * Verify task completion if enabled.
     */
    private suspend fun verifyTaskCompletionIfNeeded(
        taskId: String,
        iteration: Int,
        userRequestFallback: String?,
        llmContent: String,
        writeToolsExecutedInTurn: Int
    ): Boolean {
        if (!configService.shouldVerifyTask(taskId, iteration, writeToolsExecutedInTurn)) {
            return true
        }

        val userRequest = userRequestFallback?.takeIf { it.isNotBlank() }
            ?: getLastUserMessage(taskId)
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
            toolCalls = null
        )
        return false
    }

    /**
     * Get last user message from history.
     */
    private fun getLastUserMessage(taskId: String): String? {
        return try {
            chatMessageRepository.findByTaskId(taskId)
                .lastOrNull { it.role == MessageRole.USER }
                ?.content
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Turn prompt data class.
 */
data class TurnPrompt(
    val systemPrompt: String,
    val messages: List<LLMMessage>
)

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
    val rejectionReason: String? = null
)
