package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.jclab.refio.core.workflow.WorkflowEventListener
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
    private val scope: CoroutineScope,
    private var viewModel: TuiViewModel? = null
) : WorkflowEventListener {

    fun setViewModel(vm: TuiViewModel) {
        viewModel = vm
    }

    companion object {
        /**
         * Extract display text from LLM JSON envelope.
         * Handles: {"actions":[],"response":"text","intent":"..."}
         * Returns the "response" or "content" field if present, otherwise the original content.
         * This mirrors ToolCallParser.extractTextResponse without kotlinx.serialization/Gson dependency.
         */
        fun extractResponseFromJson(content: String): String {
            val trimmed = content.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return content

            // Don't extract from plan/subtasks JSON — keep it for plan rendering
            if ("\"plan\"" in trimmed || "\"subtasks\"" in trimmed) return content

            return try {
                // Extract "response" field value using simple pattern matching
                val responseText = extractJsonStringField(trimmed, "response")
                    ?: extractJsonStringField(trimmed, "content")

                if (!responseText.isNullOrBlank()) {
                    // Unescape JSON string escapes
                    responseText
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } else {
                    content
                }
            } catch (_: Exception) {
                content
            }
        }

        /**
         * Extract a string field value from a JSON object using regex.
         * Handles escaped quotes within the value.
         */
        private fun extractJsonStringField(json: String, fieldName: String): String? {
            // Match "fieldName":"value" with proper escape handling
            val pattern = """"$fieldName"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
            val match = pattern.find(json) ?: return null
            return match.groupValues[1]
        }
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

            val rawContent = accumulatedContent.toString()
            // Try to extract text from JSON envelope during streaming for cleaner display
            val content = extractResponseFromJson(rawContent)
            updateStreamMessage(content, isStreaming = true)
        }
    }

    override fun onStreamComplete(content: String) {
        synchronized(accumulatedContent) {
            completed = true
            val rawContent = content.ifBlank { accumulatedContent.toString() }
            // Extract text payload from JSON envelope (e.g., {"actions":[],"response":"...","intent":"analysis"})
            // This mirrors what the plugin's MessageDispatcher/ToolCallContentSanitizer does.
            val finalContent = extractResponseFromJson(rawContent)

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
        // Update subtask execution status if subtasks are being tracked
        viewModel?.let { vm ->
            val subtasks = vm.stateFlow.value.subtasks
            val running = subtasks.indexOfFirst { it.status == "RUNNING" }
            if (running >= 0) {
                val total = subtasks.size
                val completed = subtasks.count { it.status in listOf("COMPLETED", "SKIPPED", "FAILED") }
                vm.updateExecutionStatus("Executing step ${completed + 1}/$total: $toolName")
            }
        }
    }

    override fun onStepStarted(subtaskId: String) {
        viewModel?.updateSubtaskStatus(subtaskId, "RUNNING")
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
