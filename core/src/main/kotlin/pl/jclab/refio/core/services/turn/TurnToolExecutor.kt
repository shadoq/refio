package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.ToolResultData
import pl.jclab.refio.core.services.ToolResultSummary
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.WorkingMemoryIntegration
import pl.jclab.refio.core.services.ToolCall as CoreToolCall
import pl.jclab.refio.core.services.context.ContextTokenEstimator
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.utils.GsonInstance.gson
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("TurnToolExecutor")

/**
 * Executes tool calls with parallel/sequential strategy.
 * Handles subtask creation, result summarization, and snapshot management.
 */
class TurnToolExecutor(
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry,
    private val subtaskRepository: SubtaskRepository,
    private val toolResultSummarizer: ToolResultSummarizer,
    private val snapshotService: SnapshotService? = null,
    private val workingMemoryIntegration: WorkingMemoryIntegration? = null,
    private val taskRepository: TaskRepository? = null,
    private val chatMessageRepository: ChatMessageRepository? = null,
    private val approvalService: ToolApprovalService? = null,
    private val permissionsService: ToolPermissionsService? = null,
    private val hookService: pl.jclab.refio.core.services.hooks.HookService? = null
) {
    /** Callback to update turn phase (set by AgentTurnLoop before each turn) */
    var turnStateUpdater: ((TurnPhase) -> Unit)? = null

    private val streamingToolNames = setOf("advance_code_editing", "multi_line_editor")
    private val codingToolNames = setOf("advance_code_editing", "multi_line_editor")

    private fun isDelegationTool(name: String): Boolean =
        name.equals("invoke_subagent", ignoreCase = true) ||
        name.equals("delegate_to_strong_model", ignoreCase = true)

    /**
     * Detect whether the current tool call repeats a recent one with identical
     * arguments. Emits an in-band nudge that ships WITH the tool result so the
     * agent sees the warning on the next turn without a separate system message.
     *
     * Strategy: compare against the previous 3 SUCCESS subtasks of the same task.
     * We use exact string equality on `paramsJson`, which is safe because both
     * the current call and the stored subtask serialise arguments through the
     * same Gson instance — so identical args produce identical JSON.
     *
     * Returns null when no repeat is detected; the caller drops the nudge from
     * `outputWithWarnings` in that case.
     */
    private fun buildRepeatedCallNudge(
        taskId: String,
        currentSubtaskId: String,
        toolName: String,
        argumentsJson: String
    ): String? {
        // `think`, `memory` and similar reflective tools are expected to be called
        // repeatedly with similar args. Nudging on those would be noise.
        val noisyTools = setOf("think", "memory", "tasks")
        if (toolName in noisyTools) return null
        if (argumentsJson.isBlank()) return null

        val recent = try {
            transaction { subtaskRepository.findByTaskId(taskId) }
                .asReversed()
                .asSequence()
                .filter { it.id != currentSubtaskId }
                .filter { it.status == TaskStatus.SUCCESS }
                .take(3)
                .toList()
        } catch (e: Exception) {
            logger.debug { "[REPEATED_CALL_NUDGE] query failed, skipping: ${e.message}" }
            return null
        }

        val match = recent.firstOrNull {
            it.kind.name.equals(toolName, ignoreCase = true) &&
                it.paramsJson == argumentsJson
        } ?: return null

        logger.info {
            "[REPEATED_CALL] tool=$toolName taskId=$taskId currentSubtask=$currentSubtaskId " +
                "matchesPriorSubtask=${match.id} orderIndex=${match.orderIndex}"
        }

        return buildString {
            appendLine("[⚠ progressive hint — possible loop]")
            appendLine("You just ran `$toolName` with arguments identical to a prior successful call in this task (subtask ${match.id}).")
            appendLine("If the previous result did not advance the task, repeating it will not help either.")
            appendLine("Options: (1) change the arguments materially, (2) try a different tool — see <tool_selection> for alternatives, (3) if stuck on the same problem for 3+ attempts, call `delegate_to_strong_model` with a concrete summary of what failed, or (4) ask the user with `ask_user` when the goal itself is ambiguous.")
        }.trim()
    }

    companion object {
        /** Max raw output size (chars) to preserve in-context for DATA_PRODUCING tools */
        const val DATA_PRODUCING_RAW_OUTPUT_BUFFER = 16_000

        /** Max raw output size (chars) for read_file — lazy compression deferred to RECENT_WORK */
        const val READ_FILE_RAW_OUTPUT_BUFFER = 524_288

        /** Max tokens of enriched context to inject into coding tools */
        const val CODING_TOOL_CONTEXT_TOKENS = 8_000

        /**
         * Decide what content to store in conversation history for a tool result.
         * Returns (effectiveContent, isSummarized).
         */
        internal fun resolveEffectiveContent(
            rawOutput: String,
            summaryText: String,
            wasSummarized: Boolean,
            isDataProducing: Boolean,
            toolName: String = ""
        ): Pair<String, Boolean> {
            val rawLen = rawOutput.length
            return when {
                rawLen <= 500 -> rawOutput to false

                // read_file: keep raw up to 512KB, let RECENT_WORK compress lazily
                toolName == "read_file" && rawLen <= READ_FILE_RAW_OUTPUT_BUFFER ->
                    rawOutput to false

                isDataProducing && rawLen <= DATA_PRODUCING_RAW_OUTPUT_BUFFER && wasSummarized ->
                    rawOutput to true
                isDataProducing && wasSummarized ->
                    summaryText to true
                wasSummarized ->
                    summaryText to true
                else ->
                    // Non-summarized fallback for tools whose output is between
                    // 500 chars and the data-producing buffer. We previously did
                    // `rawOutput.take(2000)` here, which silently dropped trailing
                    // exit codes / API responses for any tool that wasn't routed
                    // through the summarizer — exactly the data-loss pattern this
                    // refactor exists to eliminate. Use the shared head+tail
                    // helper so both ends survive.
                    pl.jclab.refio.core.services.context.ToolResultCompression
                        .headTailTruncate(rawOutput, 2000) to false
            }
        }
    }

    /**
     * Execute list of tool calls with parallel support for READ_ONLY tools.
     */
    suspend fun executeToolCalls(
        taskId: String,
        toolCalls: List<ToolCallData>,
        mode: TaskMode,
        executionMode: ExecutionMode,
        listener: TurnEventListener?,
        iteration: Int,
        config: TurnLoopConfig,
        profileOverrides: TurnProfileOverrides? = null,
        runId: String,
        depth: Int
    ): List<Pair<ToolCallData, ToolResultData>> = coroutineScope {
        val maxOrderIndex = subtaskRepository.getMaxOrderIndex(taskId) ?: -1

        // Create subtasks for all tool calls (PENDING status)
        val subtaskIds = mutableMapOf<String, String>()
        toolCalls.forEachIndexed { index, toolCall ->
            val kind = toolRegistry.toSubtaskKind(toolCall.name)
            val description = buildToolDescription(toolCall)

            val subtask = subtaskRepository.create(
                taskId = taskId,
                orderIndex = maxOrderIndex + index + 1,
                kind = kind,
                description = description,
                paramsJson = toolCall.arguments,
                status = TaskStatus.PENDING
            )
            subtaskIds[toolCall.id] = subtask.id

            logger.debug { "[SUBTASK_CREATED] toolCall=${toolCall.name}, subtaskId=${subtask.id}, status=PENDING" }
        }

        val indexedCalls = toolCalls.mapIndexed { index, call -> index to call }
        val (allowedIndexed, blockedIndexed) = indexedCalls.partition { (_, toolCall) ->
            isToolAllowedByProfile(toolCall.name, profileOverrides)
        }

        val blockedResults = blockedIndexed.map { (index, toolCall) ->
            val subtaskId = subtaskIds[toolCall.id]!!
            val errorText = buildProfileBlockedError(toolCall.name, profileOverrides)
            subtaskRepository.updateStatus(subtaskId, TaskStatus.FAILED)
            subtaskRepository.updateResult(subtaskId, result = null, errorMessage = errorText)
            listener?.onToolExecutionCompleted(taskId, toolCall, errorText, false)
            hookService?.trigger("after_tool", mapOf(
                "toolName" to toolCall.name,
                "taskId" to taskId,
                "success" to "false",
                "mode" to mode.name
            ))
            logger.warn {
                "[TOOL_BLOCKED] runId=$runId, depth=$depth, tool=${toolCall.name}, " +
                    "subagent=${profileOverrides?.subagentName ?: "-"}"
            }
            index to (
                toolCall to ToolResultData(
                    toolCallId = toolCall.id,
                    subtaskId = subtaskId,
                    content = errorText,
                    isSummarized = false,
                    rawOutput = null,
                    metadata = null
                )
            )
        }

        if (allowedIndexed.isEmpty()) {
            return@coroutineScope blockedResults
                .sortedBy { it.first }
                .map { it.second }
        }

        // If any tool requires ASK approval, run sequentially to avoid multiple simultaneous dialogs
        val containsAskTool = permissionsService != null && approvalService != null &&
            allowedIndexed.any { (_, toolCall) ->
                permissionsService.getPermission(toolCall.name, mode) == PermissionLevel.ASK
            }
        // Recursion protection for delegation tools lives in InvokeSubagentTool.execute()
        // (subagentChain check), so running multiple invoke_subagent in parallel is safe.
        // LLM-level concurrency is bounded per-endpoint by OllamaRequestGate.
        val shouldDisableParallel = containsAskTool

        // Parallel execution for READ_ONLY tools
        if (config.parallelReadTools && allowedIndexed.size > 1 && !shouldDisableParallel) {
            val (readOnlyIndexed, writeIndexed) = allowedIndexed.partition { (_, tc) ->
                toolRegistry.getTool(tc.name)?.mode == ToolMode.READ_ONLY
            }

            if (readOnlyIndexed.isNotEmpty() && writeIndexed.isEmpty()) {
                // Cap concurrency: weak local models occasionally batch 7+ reads in one turn
                // (gpt-5.4-mini in particular). Running them all in parallel doesn't help —
                // the bottleneck is the next LLM call, not the reads. Chunk into windows of
                // `maxParallelReadTools` so we keep parallelism gains without unbounded fan-out.
                val cap = config.maxParallelReadTools.coerceAtLeast(1)
                if (readOnlyIndexed.size > cap) {
                    logger.warn {
                        "[PARALLEL_CAPPED] ${readOnlyIndexed.size} READ_ONLY tools in batch — " +
                            "capping concurrency at $cap (chunked execution)"
                    }
                } else {
                    logger.info {
                        "[PARALLEL] Executing ${readOnlyIndexed.size} READ_ONLY in parallel (no WRITE tools in batch)"
                    }
                }

                val readOnlyResults = readOnlyIndexed.chunked(cap).flatMap { chunk ->
                    chunk.map { (originalIndex, toolCall) ->
                        async {
                            val subtaskId = subtaskIds[toolCall.id]!!
                            val result = executeSingleTool(
                                taskId = taskId,
                                toolCall = toolCall,
                                subtaskId = subtaskId,
                                listener = listener,
                                iteration = iteration,
                                _config = config,
                                mode = mode,
                                executionMode = executionMode,
                                runId = runId,
                                depth = depth,
                                profileOverrides = profileOverrides,
                                _subtaskIds = subtaskIds
                            )
                            originalIndex to (toolCall to result)
                        }
                    }.awaitAll()
                }

                val writeResults = writeIndexed.map { (originalIndex, toolCall) ->
                    if (GlobalMetrics.isCancelled()) {
                        throw kotlinx.coroutines.CancellationException("Operation cancelled by user")
                    }
                    val subtaskId = subtaskIds[toolCall.id]!!

                    if (config.enableSnapshots && snapshotService != null) {
                        try {
                            val params = TurnJsonUtils.parseJsonToMap(toolCall.arguments)
                            val path = params["path"]?.toString()
                            if (path != null) {
                                val snapshotId = snapshotService.createSnapshot(taskId, subtaskId, listOf(path))
                                if (snapshotId != null) {
                                    subtaskRepository.linkSnapshot(subtaskId, snapshotId)
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "[SNAPSHOT] Failed to create snapshot for ${toolCall.name}" }
                        }
                    }

                    val result = executeSingleTool(
                        taskId = taskId,
                        toolCall = toolCall,
                        subtaskId = subtaskId,
                        listener = listener,
                        iteration = iteration,
                        _config = config,
                        mode = mode,
                        executionMode = executionMode,
                        runId = runId,
                        depth = depth,
                        profileOverrides = profileOverrides,
                        _subtaskIds = subtaskIds
                    )
                    originalIndex to (toolCall to result)
                }

                val allResults = (blockedResults + readOnlyResults + writeResults)
                    .sortedBy { it.first }
                    .map { it.second }

                return@coroutineScope allResults
            }
        }

        if (config.parallelReadTools && shouldDisableParallel) {
            logger.info { "[PARALLEL] Disabled for this batch: containsAskTool=$containsAskTool" }
        }

        // Sequential execution
        val sequentialResults = mutableListOf<Pair<Int, Pair<ToolCallData, ToolResultData>>>()
        for ((index, toolCall) in allowedIndexed) {
            if (GlobalMetrics.isCancelled()) {
                throw kotlinx.coroutines.CancellationException("Operation cancelled by user")
            }
            val subtaskId = subtaskIds[toolCall.id]!!
            val resultData = executeSingleTool(
                taskId = taskId,
                toolCall = toolCall,
                subtaskId = subtaskId,
                listener = listener,
                iteration = iteration,
                _config = config,
                mode = mode,
                executionMode = executionMode,
                runId = runId,
                depth = depth,
                profileOverrides = profileOverrides,
                _subtaskIds = subtaskIds
            )
            sequentialResults.add(index to (toolCall to resultData))
        }

        (blockedResults + sequentialResults)
            .sortedBy { it.first }
            .map { it.second }
    }

    /**
     * Execute a single tool call.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun executeSingleTool(
        taskId: String,
        toolCall: ToolCallData,
        subtaskId: String,
        listener: TurnEventListener?,
        iteration: Int,
        _config: TurnLoopConfig,
        mode: TaskMode,
        executionMode: ExecutionMode,
        runId: String,
        depth: Int,
        profileOverrides: TurnProfileOverrides?,
        _subtaskIds: Map<String, String>
    ): ToolResultData {
        if (toolCall.error != null) {
            val errorText = "Error: ${toolCall.error}"
            listener?.onToolExecutionCompleted(taskId, toolCall, errorText, false)
            hookService?.trigger("after_tool", mapOf(
                "toolName" to toolCall.name,
                "taskId" to taskId,
                "success" to "false",
                "mode" to mode.name
            ))
            subtaskRepository.updateStatus(subtaskId, TaskStatus.FAILED)
            subtaskRepository.updateResult(subtaskId, result = null, errorMessage = errorText)
            logger.debug { "[SUBTASK_FAILED] subtaskId=$subtaskId, tool=${toolCall.name}, error=$errorText" }
            return ToolResultData(
                toolCallId = toolCall.id,
                subtaskId = subtaskId,
                content = errorText,
                isSummarized = false,
                rawOutput = null,
                metadata = null
            )
        }

        // --- ASK permission check ---
        if (approvalService != null && permissionsService != null) {
            val permissionLevel = permissionsService.getPermission(toolCall.name, mode)
            if (permissionLevel == PermissionLevel.ASK) {
                val argumentsMap = TurnJsonUtils.parseJsonToMap(toolCall.arguments)
                turnStateUpdater?.invoke(TurnPhase.WAITING_FOR_PERMISSION)

                val request = ToolApprovalService.ApprovalRequest(
                    requestId = "${taskId}_${toolCall.id}",
                    taskId = taskId,
                    toolName = toolCall.name,
                    arguments = argumentsMap,
                    description = buildToolDescription(toolCall)
                )

                val decision = approvalService.requestApproval(request)

                turnStateUpdater?.invoke(TurnPhase.EXECUTING_TOOLS)

                when (decision) {
                    is ToolApprovalService.ApprovalDecision.Approved -> { /* continue */ }
                    is ToolApprovalService.ApprovalDecision.Trusted -> { /* continue, pattern saved */ }
                    is ToolApprovalService.ApprovalDecision.Rejected -> {
                        subtaskRepository.updateStatus(subtaskId, TaskStatus.FAILED)
                        subtaskRepository.updateResult(
                            subtaskId, result = null,
                            errorMessage = "User rejected: ${decision.reason ?: "no reason"}"
                        )
                        throw ToolRejectedException(
                            toolName = toolCall.name,
                            reason = decision.reason
                        )
                    }
                }
            }
        }
        // --- end ASK permission check ---

        val toolToken = GlobalMetrics.beginOperation(
            OperationInfo.TurnToolExecution(toolCall.name, iteration)
        )

        try {
            subtaskRepository.updateStatus(subtaskId, TaskStatus.RUNNING)
            logger.debug { "[SUBTASK_RUNNING] subtaskId=$subtaskId, tool=${toolCall.name}" }

            listener?.onToolExecutionStarted(taskId, toolCall)
            hookService?.trigger("before_tool", mapOf(
                "toolName" to toolCall.name,
                "taskId" to taskId,
                "mode" to mode.name
            ))

            val argumentsMap = TurnJsonUtils.parseJsonToMap(toolCall.arguments).toMutableMap()
            injectNestedSubagentMetadata(
                args = argumentsMap,
                toolCall = toolCall,
                taskId = taskId,
                mode = mode,
                executionMode = executionMode,
                runId = runId,
                depth = depth,
                profileOverrides = profileOverrides,
                listener = listener
            )

            // Inject internal metadata for SYSTEM tools (tasks, memory, etc.)
            val tool = toolRegistry.getTool(toolCall.name)
            if (tool?.category == ToolCategory.SYSTEM || isDelegationTool(toolCall.name)) {
                argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.TASK_ID, taskId)
                argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.MODE, mode.name)
                argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.ITERATION, iteration)
                argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.SESSION_ID, taskId)
                argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.SUBTASK_ID, subtaskId)
                profileOverrides?.subagentName?.let { argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.AGENT_NAME, it) }
                profileOverrides?.let { argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.AGENT_ID, runId) }
                profileOverrides?.parentRunId?.let { argumentsMap.putIfAbsent(pl.jclab.refio.core.tools.base.ToolInternalParams.PARENT_RUN_ID, it) }
            }

            // Inject enriched conversation context for LLM-based coding tools
            if (toolCall.name in codingToolNames) {
                val context = buildCodingToolContext(taskId, CODING_TOOL_CONTEXT_TOKENS)
                if (context.isNotBlank()) {
                    argumentsMap["conversation_context"] = context
                    logger.info {
                        "[CODING_CONTEXT] Injected ${context.length} chars (${ContextTokenEstimator.estimateTokens(context)} tokens) into ${toolCall.name}"
                    }
                }
            }

            val toolCallRequest = CoreToolCall(
                name = toolCall.name,
                params = argumentsMap
            )

            val toolResult = if (listener != null && toolCall.name in streamingToolNames) {
                val subtask = subtaskRepository.findById(subtaskId)
                    ?: throw IllegalStateException("Subtask not found for tool call: $subtaskId")

                val executionListener = object : ExecutionEventListener {
                    override fun onToolCodeGenerationStream(
                        step: Subtask,
                        toolName: String,
                        filePath: String,
                        streamContent: String,
                        isComplete: Boolean
                    ) {
                        if (step.id == subtaskId) {
                            listener.onToolStreamChunk(taskId, toolCall.id, "", streamContent)
                        }
                    }
                }

                val executionResult = toolExecutor.executeToolsWithStreaming(
                    toolCalls = listOf(toolCallRequest),
                    subtask = subtask,
                    listener = executionListener
                )

                val output = executionResult.outputs.firstOrNull()?.result
                    ?: throw IllegalStateException("Missing tool output for ${toolCall.name}")

                ToolResult(
                    success = output.success,
                    output = output.output,
                    error = output.error,
                    metadata = output.metadata,
                    filesChanged = output.affectedFiles,
                    nextActionHints = output.nextActionHints,
                    recovery = output.recovery,
                    changeSummary = output.changeSummary
                )
            } else {
                toolExecutor.executeTool(toolCallRequest, taskId)
            }

            if (toolResult.success) {
                val rawOutput = toolResult.output ?: "Success (no output)"
                // Append next-step hints from the tool itself (e.g. grep_search "no matches" hints,
                // file_search "broaden the glob" hints). The agent sees these directly in the
                // tool result, no separate nudge round-trip needed.
                val hintsBlock = toolResult.nextActionHints
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "[next-step hints]\n- ", separator = "\n- ")

                // Progressive nudge: if the agent just ran an identical tool call (same
                // name + arguments) that also succeeded, it's burning turns on a loop —
                // common failure mode for small local models (qwen/gemma) which keep
                // retrying `advance_code_editing` with the same description hoping for
                // different output. Detect by comparing against the previous subtask's
                // paramsJson; nudge the agent toward a different approach in-band so it
                // arrives with the tool result rather than via a round-trip.
                val repeatedCallNudge = buildRepeatedCallNudge(
                    taskId = taskId,
                    currentSubtaskId = subtaskId,
                    toolName = toolCall.name,
                    argumentsJson = toolCall.arguments
                )

                val outputWithWarnings = listOfNotNull(rawOutput, hintsBlock, repeatedCallNudge)
                    .joinToString("\n\n")
                val isInvokeSubagent = toolCall.name.equals("invoke_subagent", ignoreCase = true)
                val isDelegateStrong = toolCall.name.equals("delegate_to_strong_model", ignoreCase = true)
                val isMemoryGetSubtaskOutput = toolCall.name.equals("memory", ignoreCase = true)
                    && argumentsMap["action"]?.toString().equals("get_subtask_output", ignoreCase = true)
                val displayOutput = when {
                    isInvokeSubagent -> {
                        val subagentName = argumentsMap["subagent_name"]?.toString()?.trim().orEmpty()
                        val header = if (subagentName.isNotBlank()) {
                            "Subagent [$subagentName] result:"
                        } else {
                            "Subagent result:"
                        }
                        "$header\n\n$outputWithWarnings"
                    }
                    isDelegateStrong -> "Strong model result:\n\n$outputWithWarnings"
                    else -> outputWithWarnings
                }

                val summaryToken = GlobalMetrics.beginOperation(
                    OperationInfo.TurnToolSummarization(toolCall.name, iteration)
                )
                // Skip the LLM summarizer entirely when the tool already produced a structured
                // ChangeSummary (write tools). The diff + stats are more informative AND
                // deterministic — no need to spend a WEAK-model call paraphrasing them.
                val structuredChangeSummary = toolResult.changeSummary
                val summaryResult = try {
                    when {
                        isInvokeSubagent || isDelegateStrong || isMemoryGetSubtaskOutput ->
                            ToolResultSummary(displayOutput, wasSummarized = false, 0, 0, 0.0)
                        structuredChangeSummary != null -> {
                            val cs = structuredChangeSummary
                            val deterministic = buildString {
                                if (cs.created) append("Created ") else append("Edited ")
                                append(toolResult.filesChanged?.firstOrNull() ?: "(unknown)")
                                append(" (+${cs.addedLines}/-${cs.removedLines}")
                                cs.replacements?.let { append(", ${it} replacement(s)") }
                                append(")")
                                cs.unifiedDiff?.takeIf { it.isNotBlank() }?.let { diff ->
                                    append("\n```diff\n").append(diff.trimEnd()).append("\n```")
                                }
                            }
                            ToolResultSummary(deterministic, wasSummarized = true, 0, 0, 0.0)
                        }
                        outputWithWarnings.isNotBlank() ->
                            // Pass argumentsMap so the summarizer can pick the right context
                            // type — e.g. read_file on .json should not run code-analysis prompt.
                            toolResultSummarizer.summarizeToolResult(
                                toolName = toolCall.name,
                                rawOutput = outputWithWarnings,
                                taskId = taskId,
                                toolArgs = argumentsMap
                            )
                        else ->
                            ToolResultSummary(outputWithWarnings, wasSummarized = false, 0, 0, 0.0)
                    }
                } catch (e: Exception) {
                    // Summarizer LLM failure should NOT propagate as a tool execution error.
                    // The tool itself succeeded — fall back to deterministic compression.
                    logger.warn(e) {
                        "[SUMMARIZER_FALLBACK] Summarizer failed for tool=${toolCall.name}, " +
                            "falling back to deterministic compression: ${e.message}"
                    }
                    val compressed = toolResultSummarizer.compressToolResult(
                        outputWithWarnings, null,
                        pl.jclab.refio.core.services.context.CompressionLevel.SUMMARY
                    )
                    ToolResultSummary(compressed, wasSummarized = true, 0, 0, 0.0)
                } finally {
                    GlobalMetrics.endOperation(summaryToken)
                }

                listener?.onToolExecutionCompleted(taskId, toolCall, summaryResult.summary, true)
                hookService?.trigger("after_tool", mapOf(
                    "toolName" to toolCall.name,
                    "taskId" to taskId,
                    "success" to "true",
                    "mode" to mode.name
                ))

                subtaskRepository.updateStatus(subtaskId, TaskStatus.SUCCESS)
                subtaskRepository.updateResult(subtaskId, result = outputWithWarnings, summary = summaryResult.summary)
                logger.debug { "[SUBTASK_SUCCESS] subtaskId=$subtaskId, tool=${toolCall.name}" }

                workingMemoryIntegration?.recordToolKnowledge(
                    taskId = taskId,
                    toolName = toolCall.name,
                    params = argumentsMap,
                    result = outputWithWarnings,
                    iteration = iteration,
                    metadata = toolResult.metadata,
                    originId = subtaskId
                )

                // Decide what content to store in conversation history.
                // Raw output is always preserved in subtask for reference.
                val toolDef = toolRegistry.getTool(toolCall.name)
                val isDataProducing = toolDef?.category == ToolCategory.DATA_PRODUCING
                        || toolDef?.category == ToolCategory.FILE_PRODUCING

                val (effectiveContent, effectivelySummarized) = resolveEffectiveContent(
                    rawOutput = outputWithWarnings,
                    summaryText = summaryResult.summary,
                    wasSummarized = summaryResult.wasSummarized,
                    isDataProducing = isDataProducing,
                    toolName = toolCall.name
                )
                if (isDataProducing && outputWithWarnings.length <= DATA_PRODUCING_RAW_OUTPUT_BUFFER && summaryResult.wasSummarized) {
                    logger.info {
                        "[TOOL_DATA_PRESERVED] name=${toolCall.name}, rawChars=${outputWithWarnings.length}, " +
                        "summaryChars=${summaryResult.summary.length} (raw preserved, summary in subtask)"
                    }
                }

                logger.info {
                    "[TOOL_EXECUTED] name=${toolCall.name}, " +
                    "summarized=${effectivelySummarized}, dataProducing=$isDataProducing, " +
                    "chars=${outputWithWarnings.length}->${effectiveContent.length}"
                }

                val metadataMap = buildToolResultMetadata(toolCall.name, argumentsMap, toolResult.metadata)
                val metadataJson = metadataMap.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }

                val resultData = ToolResultData(
                    toolCallId = toolCall.id,
                    subtaskId = subtaskId,
                    content = effectiveContent,
                    isSummarized = effectivelySummarized,
                    rawOutput = outputWithWarnings,
                    metadata = metadataJson
                )

                return resultData
            } else {
                val errorDetail = toolResult.error
                    ?: toolResult.output?.takeIf { it.isNotBlank() }
                    ?: "Unknown error"
                // Surface tool-provided recovery + next-step hints to the agent so it can
                // attempt a corrected retry instead of guessing or giving up. Lesson 03E04:
                // failed tool results should explain *what to do next*, not just *what failed*.
                val errorText = buildString {
                    append("Error: ").append(errorDetail)
                    toolResult.recovery?.takeIf { it.isNotBlank() }?.let {
                        append("\nRecovery: ").append(it)
                    }
                    toolResult.nextActionHints?.takeIf { it.isNotEmpty() }?.let { hints ->
                        append("\nNext steps:")
                        hints.forEach { append("\n- ").append(it) }
                    }
                }
                listener?.onToolExecutionCompleted(taskId, toolCall, errorText, false)
                hookService?.trigger("after_tool", mapOf(
                    "toolName" to toolCall.name,
                    "taskId" to taskId,
                    "success" to "false",
                    "mode" to mode.name
                ))

                subtaskRepository.updateStatus(subtaskId, TaskStatus.FAILED)
                subtaskRepository.updateResult(subtaskId, result = null, errorMessage = errorText)
                logger.debug { "[SUBTASK_FAILED] subtaskId=$subtaskId, tool=${toolCall.name}, error=$errorText" }

                logger.info { "[TOOL_EXECUTED] name=${toolCall.name}, success=false" }

                return ToolResultData(
                    toolCallId = toolCall.id,
                    subtaskId = subtaskId,
                    content = errorText,
                    isSummarized = false,
                    rawOutput = null,
                    metadata = null
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "[TOOL_ERROR] Failed to execute ${toolCall.name}: ${e.message}" }
            val errorText = "Error: ${e.message}"
            listener?.onToolExecutionCompleted(taskId, toolCall, errorText, false)
            hookService?.trigger("after_tool", mapOf(
                "toolName" to toolCall.name,
                "taskId" to taskId,
                "success" to "false",
                "mode" to mode.name
            ))

            subtaskRepository.updateStatus(subtaskId, TaskStatus.FAILED)
            subtaskRepository.updateResult(
                subtaskId,
                result = null,
                errorMessage = errorText,
                errorStacktrace = e.stackTraceToString()
            )
            logger.debug { "[SUBTASK_FAILED] subtaskId=$subtaskId, tool=${toolCall.name}, exception=${e.message}" }

            return ToolResultData(
                toolCallId = toolCall.id,
                subtaskId = subtaskId,
                content = errorText,
                isSummarized = false,
                rawOutput = null,
                metadata = null
            )
        } finally {
            GlobalMetrics.endOperation(toolToken)
        }
    }

    /**
     * Build human-readable description for tool call subtask.
     */
    fun buildToolDescription(toolCall: ToolCallData): String {
        if (toolCall.error != null) {
            return "${toolCall.name}: invalid arguments"
        }
        val params = TurnJsonUtils.parseJsonToMap(toolCall.arguments)

        val keyInfo = when (toolCall.name) {
            "read_file" -> params["path"]?.toString() ?: ""
            "read_directory" -> params["path"]?.toString() ?: ""
            "file_search" -> "pattern: ${params["pattern"]}"
            "grep_search" -> "pattern: ${params["pattern"]}"
            "code_editing" -> params["path"]?.toString() ?: ""
            "create_new_file" -> params["path"]?.toString() ?: ""
            "multi_edit" -> "${(params["edits"] as? List<*>)?.size ?: 0} files"
            "multi_line_editor" -> params["path"]?.toString() ?: ""
            "advance_code_editing" -> params["path"]?.toString() ?: ""
            else -> ""
        }

        return if (keyInfo.isNotBlank()) {
            "${toolCall.name}: $keyInfo"
        } else {
            toolCall.name
        }
    }

    /**
     * Check if tool is allowed by profile.
     */
    fun isToolAllowedByProfile(toolName: String, profileOverrides: TurnProfileOverrides?): Boolean {
        if (profileOverrides == null) return true

        val normalizedName = toolName.lowercase()
        val allowed = profileOverrides.allowedTools?.map { it.lowercase() }?.toSet()
        val disallowed = profileOverrides.disallowedTools?.map { it.lowercase() }?.toSet()

        if (allowed != null) {
            return normalizedName in allowed
        }
        if (disallowed != null) {
            return normalizedName !in disallowed
        }
        return true
    }

    /**
     * Build a self-correcting error that tells the LLM which tools it actually has. Weak models
     * hallucinate tool names from conversation history — listing the real whitelist lets them
     * recover on the next iteration instead of repeating the same blocked call.
     */
    private fun buildProfileBlockedError(
        toolName: String,
        profileOverrides: TurnProfileOverrides?,
    ): String {
        val allowed = profileOverrides?.allowedTools?.takeIf { it.isNotEmpty() }
        val disallowed = profileOverrides?.disallowedTools?.takeIf { it.isNotEmpty() }
        val scope = profileOverrides?.subagentName?.let { "subagent '$it'" } ?: "current run profile"
        val details = when {
            allowed != null -> "Your available tools are: ${allowed.joinToString(", ")}. Pick one of these or produce a final response."
            disallowed != null -> "This tool is on the blocklist for this profile (${disallowed.joinToString(", ")}). Use a different approach."
            else -> "Check the <available_tools> section and use only tools listed there."
        }
        return "Error: Tool '$toolName' is not available to the $scope. $details"
    }

    /**
     * Count write tool calls in list.
     */
    fun countWriteToolCalls(toolCalls: List<ToolCallData>): Int {
        return toolCalls.count { isWriteTool(it.name) }
    }

    fun countVerificationToolCalls(toolCalls: List<ToolCallData>): Int {
        return toolCalls.count { it.name in setOf("run_terminal_command", "grep_search", "read_file", "view_diff") }
    }

    /**
     * Check if tool is WRITE type.
     */
    fun isWriteTool(toolName: String): Boolean {
        return toolRegistry.getTool(toolName)?.mode == ToolMode.WRITE
    }

    private fun injectNestedSubagentMetadata(
        args: MutableMap<String, Any>,
        toolCall: ToolCallData,
        taskId: String,
        mode: TaskMode,
        executionMode: ExecutionMode,
        runId: String,
        depth: Int,
        profileOverrides: TurnProfileOverrides?,
        listener: TurnEventListener? = null
    ) {
        if (!isDelegationTool(toolCall.name)) return

        val chain = (profileOverrides?.subagentChain.orEmpty() + listOfNotNull(profileOverrides?.subagentName))
            .distinct()

        args.putIfAbsent("_task_id", taskId)
        args.putIfAbsent("_mode", mode.name)
        args.putIfAbsent("_execution_mode", executionMode.name)
        args.putIfAbsent("_parent_run_id", runId)
        args.putIfAbsent("_parent_depth", depth)
        args.putIfAbsent("_subagent_chain", chain)

        // Inject the turn event listener so the subagent can stream progress back to the UI
        if (listener != null && !args.containsKey("_turn_event_listener")) {
            args["_turn_event_listener"] = listener
        }
    }

    /**
     * Build enriched context for coding tools (advance_code_editing, multi_line_editor).
     *
     * Combines multiple context sources to give the coding LLM a fuller picture:
     * 1. Task description (what the user asked for)
     * 2. Recent conversation messages (user requests + assistant reasoning, no tool outputs)
     * 3. Working memory (facts from previous tool executions)
     */
    private fun buildCodingToolContext(taskId: String, maxTokens: Int): String {
        val sb = StringBuilder()
        var tokensUsed = 0

        // 1. Task description — gives the coding LLM the "big picture"
        val taskDescBudget = maxTokens / 4  // ~25% for task description
        if (taskRepository != null) {
            try {
                val task = transaction { taskRepository.findById(taskId) }
                if (task != null && task.name.isNotBlank()) {
                    val taskSection = "<TASK_DESCRIPTION>\n${task.name}\n</TASK_DESCRIPTION>\n\n"
                    val truncated = ContextTokenEstimator.truncateToTokens(taskSection, taskDescBudget)
                    sb.append(truncated)
                    tokensUsed += ContextTokenEstimator.estimateTokens(truncated)
                }
            } catch (e: Exception) {
                logger.warn { "[CODING_CONTEXT] Failed to fetch task description: ${e.message}" }
            }
        }

        // 2. Recent conversation messages — user intent + assistant reasoning
        val conversationBudget = maxTokens / 2  // ~50% for conversation
        if (chatMessageRepository != null) {
            try {
                val messages = transaction { chatMessageRepository.findByTaskId(taskId) }
                // Filter: only USER and ASSISTANT messages with text content (skip tool results)
                val relevantMessages = messages.filter { msg ->
                    msg.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
                        msg.content.isNotBlank() &&
                        msg.toolCallId == null  // skip tool result messages
                }

                if (relevantMessages.isNotEmpty()) {
                    val conversationSb = StringBuilder("<CONVERSATION_HISTORY>\n")
                    // Take last N messages that fit in budget, newest first then reverse
                    val selected = mutableListOf<String>()
                    var conversationTokens = ContextTokenEstimator.estimateTokens(conversationSb.toString())

                    for (msg in relevantMessages.asReversed()) {
                        val roleLabel = when (msg.role) {
                            MessageRole.USER -> "User"
                            MessageRole.ASSISTANT -> "Assistant"
                            else -> continue
                        }
                        // For assistant messages, skip tool-call-only messages (no readable content)
                        val content = msg.content.trim()
                        if (content.isEmpty()) continue

                        // Truncate long assistant messages (they may contain verbose reasoning)
                        val maxMsgTokens = if (msg.role == MessageRole.ASSISTANT) 400 else 600
                        val truncatedContent = ContextTokenEstimator.truncateToTokens(content, maxMsgTokens)

                        val line = "$roleLabel: $truncatedContent"
                        val lineTokens = ContextTokenEstimator.estimateTokens(line)
                        if (conversationTokens + lineTokens > conversationBudget) break

                        selected.add(line)
                        conversationTokens += lineTokens
                    }

                    if (selected.isNotEmpty()) {
                        selected.reverse()  // chronological order
                        for (line in selected) {
                            conversationSb.appendLine(line)
                        }
                        conversationSb.append("</CONVERSATION_HISTORY>\n\n")
                        sb.append(conversationSb)
                        tokensUsed += conversationTokens
                    }
                }
            } catch (e: Exception) {
                logger.warn { "[CODING_CONTEXT] Failed to fetch conversation: ${e.message}" }
            }
        }

        // 3. Working memory — facts from tool executions (files read, changes made, search results)
        val workingMemoryBudget = maxTokens - tokensUsed
        if (workingMemoryIntegration != null && workingMemoryBudget > 100) {
            val wmSection = workingMemoryIntegration.buildWorkingMemorySection(
                taskId = taskId,
                _query = "",
                maxTokens = workingMemoryBudget
            )
            if (wmSection.isNotBlank()) {
                sb.append(wmSection)
            }
        }

        return sb.toString().trim()
    }

    private fun buildToolResultMetadata(
        toolName: String,
        argumentsMap: Map<String, Any>,
        toolMetadata: Map<String, Any>?
    ): Map<String, Any> {
        val merged = mutableMapOf<String, Any>()
        if (toolMetadata != null) {
            merged.putAll(toolMetadata)
        }

        merged.putIfAbsent("tool_name", toolName)

        if (toolName.equals("invoke_subagent", ignoreCase = true)) {
            val subagentName = argumentsMap["subagent_name"]?.toString()?.trim().orEmpty()
            if (subagentName.isNotBlank()) {
                merged.putIfAbsent("subagent_name", subagentName)
            }
        }

        return merged
    }

}
