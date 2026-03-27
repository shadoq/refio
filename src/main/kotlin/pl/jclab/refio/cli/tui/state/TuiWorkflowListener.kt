package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.jclab.refio.core.workflow.WorkflowEventListener
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.core.models.api.ChatCosts
import java.util.UUID

/**
 * WorkflowEventListener implementation for TUI.
 * Bridges core workflow events to TUI state updates.
 *
 * Adopts the same debounce strategy as SwingWorkflowListener:
 * - Stream chunks are rate-limited to max 2 updates/sec (500ms gate)
 * - Content is always accumulated, but UI updates are throttled
 * - onStreamComplete bypasses debounce for immediate final render
 *
 * IMPORTANT: onStreamChunk is called from the LLM response thread —
 * accumulatedContent access is synchronized to avoid race conditions.
 */
class TuiWorkflowListener(
    private val agentId: String,
    private val agentName: String,
    private val agentColorIndex: Int,
    private val messagesState: MutableStateFlow<List<TuiChatMessage>>,
    private val streamingState: MutableStateFlow<Boolean>,
    private val stepsState: MutableStateFlow<List<TuiStep>>,
    private val scope: CoroutineScope,
    private var viewModel: TuiViewModel? = null
) : WorkflowEventListener {

    fun setViewModel(vm: TuiViewModel) {
        viewModel = vm
    }

    private val streamId = "$agentId-stream"
    private val accumulatedContent = StringBuilder()
    @Volatile private var completed = false

    /** Debounce gate: max 2 UI updates per second during streaming (matches SwingWorkflowListener). */
    @Volatile private var lastUiUpdate = 0L
    private val UI_UPDATE_INTERVAL_MS = 500L

    /** Reset state before starting a new request. */
    fun reset() {
        synchronized(accumulatedContent) {
            completed = false
            accumulatedContent.clear()
            lastUiUpdate = 0L
            streamingState.value = false
            messagesState.update { messages ->
                messages.filter { it.id != streamId }
            }
        }
    }

    private var currentPhase = ""

    override fun onChatStarted() {
        viewModel?.updateExecutionStatus("Chatting...")
        currentPhase = "chat"
        startStreaming("")
    }

    override fun onPlanningStarted() {
        viewModel?.updateExecutionStatus("Planning...")
        currentPhase = "planning"
        startStreaming("Planning...")
    }

    override fun onDecisionPhase() {
        viewModel?.updateExecutionStatus("Deciding next action...")
        currentPhase = "decision"
    }

    override fun onReflectionPhase() {
        viewModel?.updateExecutionStatus("Reflecting on results...")
        currentPhase = "reflection"
    }

    override fun onSubagentStarted(subagentName: String) {
        viewModel?.updateExecutionStatus("Subagent: $subagentName")
        startStreaming("[$subagentName] ...")
    }

    override fun onStreamChunk(chunk: String) {
        if (completed) return

        synchronized(accumulatedContent) {
            accumulatedContent.append(chunk)

            // Debounce: throttle UI updates to max 2/sec (same as SwingWorkflowListener)
            val now = System.currentTimeMillis()
            if (now - lastUiUpdate < UI_UPDATE_INTERVAL_MS) return
            lastUiUpdate = now

            val content = accumulatedContent.toString()
            updateStreamMessage(content, isStreaming = true)
        }
    }

    override fun onStreamComplete(content: String) {
        synchronized(accumulatedContent) {
            completed = true
            val finalContent = content.ifBlank { accumulatedContent.toString() }

            // Finalize: replace streaming message with final version (new ID, isStreaming=false)
            messagesState.update { messages ->
                val existing = messages.indexOfLast { it.id == streamId }
                if (existing >= 0) {
                    messages.toMutableList().also {
                        it[existing] = it[existing].copy(
                            content = finalContent,
                            isStreaming = false,
                            id = UUID.randomUUID().toString()
                        )
                    }
                } else {
                    messages + TuiChatMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        role = "assistant",
                        content = finalContent,
                        agentId = agentId,
                        agentName = agentName,
                        agentColorIndex = agentColorIndex,
                        isStreaming = false
                    )
                }
            }

            streamingState.value = false
            accumulatedContent.clear()
        }
    }

    override fun onToolStarted(toolName: String) {
        viewModel?.updateExecutionStatus("Tool: $toolName")
        stepsState.update { steps ->
            val existing = steps.indexOfLast { it.id == "tool-$toolName" && it.status == "RUNNING" }
            if (existing >= 0) steps // already tracking this tool
            else steps + TuiStep(
                id = "tool-$toolName-${System.currentTimeMillis()}",
                name = toolName,
                status = "RUNNING"
            )
        }
        // Also update subtask status if we're tracking subtasks
        viewModel?.let { vm ->
            val subtasks = vm.stateFlow.value.subtasks
            val running = subtasks.indexOfFirst { it.status == "RUNNING" }
            if (running >= 0) {
                // Currently executing subtask — update execution status with step count
                val total = subtasks.size
                val completed = subtasks.count { it.status in listOf("COMPLETED", "SKIPPED", "FAILED") }
                vm.updateExecutionStatus("Executing step ${completed + 1}/$total: $toolName")
            }
        }
    }

    override fun onStepStarted(subtaskId: String) {
        stepsState.update { steps ->
            val existing = steps.indexOfLast { it.id == subtaskId }
            if (existing >= 0) {
                steps.toMutableList().also { it[existing] = it[existing].copy(status = "RUNNING") }
            } else {
                steps + TuiStep(id = subtaskId, name = subtaskId, status = "RUNNING")
            }
        }
        // Update corresponding subtask status
        viewModel?.updateSubtaskStatus(subtaskId, "RUNNING")
    }

    override fun onIntentCompleted(intent: WorkflowIntent, result: IntentResult) {
        // Mark last RUNNING step as completed
        stepsState.update { steps ->
            val running = steps.indexOfLast { it.status == "RUNNING" }
            if (running >= 0) {
                steps.toMutableList().also { it[running] = it[running].copy(status = "COMPLETED") }
            } else steps
        }

        // In INTERACTIVE mode, auto-switch to Steps tab for next approval
        val vm = viewModel ?: return
        val state = vm.stateFlow.value
        if (state.executionMode == "INTERACTIVE") {
            val subtasks = state.subtasks
            val nextPending = subtasks.indexOfFirst { it.status in listOf("NEW", "PENDING") }
            if (nextPending >= 0) {
                vm.setActiveTab(TuiTab.STEPS)
                vm.selectStep(nextPending)
                vm.addSystemMessage("Step completed. Next step awaiting approval: ${subtasks[nextPending].name}")
            }
        }
    }

    override fun onWorkflowComplete(result: IntentResult) {
        viewModel?.updateExecutionStatus("Idle")
        streamingState.value = false
        // Mark all remaining RUNNING steps as completed
        stepsState.update { steps ->
            steps.map { if (it.status == "RUNNING") it.copy(status = "COMPLETED") else it }
        }
        // Extract per-message metrics from result and update last assistant message
        val costs = extractCosts(result)
        if (costs != null) {
            messagesState.update { messages ->
                val lastAssistant = messages.indexOfLast { it.role == "assistant" && !it.isStreaming }
                if (lastAssistant >= 0) {
                    messages.toMutableList().also {
                        it[lastAssistant] = it[lastAssistant].copy(
                            tokensIn = costs.tokensIn,
                            tokensOut = costs.tokensOut,
                            costUsd = costs.usdEst
                        )
                    }
                } else messages
            }
        }

        // Generate execution completion summary for PLAN/AGENT mode
        val vm = viewModel ?: return
        val subtasks = vm.stateFlow.value.subtasks
        if (subtasks.isNotEmpty()) {
            val completed = subtasks.count { it.status == "COMPLETED" }
            val failed = subtasks.count { it.status == "FAILED" }
            val skipped = subtasks.count { it.status == "SKIPPED" }
            val total = subtasks.size
            val totalCost = subtasks.sumOf { it.costUsd }
            val totalTokens = subtasks.sumOf { it.tokensIn + it.tokensOut }
            val summary = buildString {
                append("Execution completed: $completed/$total successful")
                if (failed > 0) append(", $failed failed")
                if (skipped > 0) append(", $skipped skipped")
                append(" | ${totalTokens} tokens | \$${String.format("%.4f", totalCost)}")
            }
            messagesState.update { messages ->
                messages + TuiChatMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = "assistant",
                    content = summary,
                    messageType = TuiMessageType.EXECUTION_SUMMARY
                )
            }
        }
    }

    private fun extractCosts(result: IntentResult): ChatCosts? {
        return when (result) {
            is IntentResult.ChatResult -> result.response.costs
            else -> null
        }
    }

    override fun onWorkflowError(error: Exception) {
        viewModel?.updateExecutionStatus("Error")
        synchronized(accumulatedContent) {
            completed = true
            streamingState.value = false
            accumulatedContent.clear()

            messagesState.update { messages ->
                val filtered = messages.filter { it.id != streamId }
                filtered + TuiChatMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = "system",
                    content = "Error: ${error.message}",
                    messageType = TuiMessageType.AGENT_FAILED
                )
            }
        }
    }

    private fun startStreaming(initialContent: String) {
        synchronized(accumulatedContent) {
            completed = false
            accumulatedContent.clear()
            lastUiUpdate = 0L
            if (initialContent.isNotEmpty()) {
                accumulatedContent.append(initialContent)
            }
            streamingState.value = true

            // Show initial streaming message immediately
            updateStreamMessage(initialContent, isStreaming = true)
        }
    }

    private fun updateStreamMessage(content: String, isStreaming: Boolean) {
        messagesState.update { messages ->
            val existing = messages.indexOfLast { it.id == streamId }
            val msg = TuiChatMessage(
                id = streamId,
                timestamp = System.currentTimeMillis(),
                role = "assistant",
                content = content,
                agentId = agentId,
                agentName = agentName,
                agentColorIndex = agentColorIndex,
                isStreaming = isStreaming
            )
            if (existing >= 0) {
                messages.toMutableList().also { it[existing] = msg }
            } else {
                messages + msg
            }
        }
    }
}
