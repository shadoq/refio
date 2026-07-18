package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mu.KotlinLogging
import pl.jclab.refio.api.models.SlashPrompt
import pl.jclab.refio.cli.tui.input.TuiContextValidator
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.db.TaskMode as CoreTaskMode
import pl.jclab.refio.core.db.ExecutionMode as CoreExecutionMode
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Chat/messaging and autocomplete state and methods, extracted from TuiViewModel.
 */
class TuiChatViewModel(
    val scope: CoroutineScope,
    internal val getRouter: () -> CoreApiRouter?,
    internal val getTaskId: () -> String?,
    internal val mode: StateFlow<String>,
    internal val model: StateFlow<String?>,
    internal val executionMode: StateFlow<String>,
    internal val agentEventBus: AgentEventBus
) {
    /** Set by coordinator after construction (breaks circular dependency with TuiWorkflowListener). */
    internal lateinit var workflowListener: TuiWorkflowListener

    // --- StateFlow fields (moved from TuiViewModel) ---

    val _messages = MutableStateFlow<List<TuiChatMessage>>(emptyList())
    val _isStreaming = MutableStateFlow(false)
    val _inputBuffer = MutableStateFlow("")
    val _cursorPosition = MutableStateFlow(0)
    val _scrollOffset = MutableStateFlow(0)
    val _selectedMessageIndex = MutableStateFlow(-1)
    val _pastedContent = MutableStateFlow<String?>(null)
    val _pendingQuestionId = MutableStateFlow<String?>(null)
    val _pendingQuestionOptions = MutableStateFlow<List<String>>(emptyList())
    val _pendingApprovals = MutableStateFlow<List<TuiPendingApproval>>(emptyList())
    val _pendingToolApproval = MutableStateFlow<TuiToolApprovalRequest?>(null)
    val _agentFilter = MutableStateFlow<String?>(null)
    val _agents = MutableStateFlow<List<TuiAgentState>>(emptyList())
    val _panelFocused = MutableStateFlow(false)
    val _autocompleteVisible = MutableStateFlow(false)
    val _autocompleteCandidates = MutableStateFlow<List<String>>(emptyList())
    val _autocompleteSelectedIndex = MutableStateFlow(0)

    // --- Paste detection fields ---
    @Volatile private var lastInsertTime = 0L
    @Volatile private var rapidInsertCount = 0
    private val PASTE_THRESHOLD_CHARS = 20 // chars in rapid succession to detect paste
    private val PASTE_THRESHOLD_MS = 100L // time window for rapid insertion

    // --- Agent trust (auto-approve) ---
    private val trustedAgents = mutableSetOf<String>()

    // --- Current operation job ---
    internal var currentJob: kotlinx.coroutines.Job? = null

    // --- Internal callbacks for state that lives in TuiViewModel ---
    // These must be wired by TuiViewModel after construction.
    internal var projectPath: Path = Path.of(".")
    internal var onUpdateTotalTokens: (Long) -> Unit = {}
    internal var onUpdateTotalCost: (Double) -> Unit = {}
    // Absolute (not additive): the task's cumulative cache-read tokens at turn end.
    internal var onUpdateCachedTokens: (Int) -> Unit = {}
    internal var onUpdateDebugInfo: (Int) -> Unit = {} // messageCount
    internal var onUpdateExecutionStatus: (String) -> Unit = {}
    internal var onLoadMessagesFromDb: (CoreApiRouter, String) -> Unit = { _, _ -> }
    internal var onLoadSubtasksFromDb: (CoreApiRouter, String) -> Unit = { _, _ -> }
    internal var onRefreshApiLogs: (CoreApiRouter) -> Unit = {}
    internal var onCreateNewTaskInDb: (CoreApiRouter) -> String = { "" }
    internal var onSetTaskId: (String) -> Unit = {}

    // --- Time formatter for export ---
    private val apiLogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    // --- Input buffer methods ---

    fun updateInputBuffer(input: String) {
        _inputBuffer.value = input
        _cursorPosition.value = input.length
    }

    fun moveCursorLeft() {
        _cursorPosition.value = (_cursorPosition.value - 1).coerceAtLeast(0)
    }

    fun moveCursorRight() {
        _cursorPosition.value = (_cursorPosition.value + 1).coerceAtMost(_inputBuffer.value.length)
    }

    fun insertAtCursor(char: Char) {
        val buf = _inputBuffer.value
        val pos = _cursorPosition.value.coerceIn(0, buf.length)
        _inputBuffer.value = buf.substring(0, pos) + char + buf.substring(pos)
        _cursorPosition.value = pos + 1

        // Detect paste: many chars in quick succession
        val now = System.currentTimeMillis()
        if (now - lastInsertTime < PASTE_THRESHOLD_MS) {
            rapidInsertCount++
        } else {
            rapidInsertCount = 1
        }
        lastInsertTime = now
    }

    /**
     * Insert a string at cursor (used for paste operations).
     * For large pastes (>100 chars), stores as pastedContent for marker display.
     */
    fun insertStringAtCursor(text: String) {
        if (text.isEmpty()) return
        val buf = _inputBuffer.value
        val pos = _cursorPosition.value.coerceIn(0, buf.length)
        _inputBuffer.value = buf.substring(0, pos) + text + buf.substring(pos)
        _cursorPosition.value = pos + text.length

        // Large paste marker
        if (text.length > 200) {
            _pastedContent.value = text
        }
    }

    /** Clear paste marker (called before sending message) */
    fun clearPasteMarker() {
        _pastedContent.value = null
    }

    fun deleteAtCursor() {
        val buf = _inputBuffer.value
        val pos = _cursorPosition.value.coerceIn(0, buf.length)
        if (pos > 0) {
            _inputBuffer.value = buf.substring(0, pos - 1) + buf.substring(pos)
            _cursorPosition.value = pos - 1
        }
    }

    // --- Chat scroll ---

    fun chatScrollUp() {
        _scrollOffset.value = (_scrollOffset.value + 3).coerceAtLeast(0)
    }

    fun chatScrollDown() {
        _scrollOffset.value = (_scrollOffset.value - 3).coerceAtLeast(0)
    }

    fun chatScrollReset() {
        _scrollOffset.value = 0
    }

    // --- System message ---

    fun addSystemMessage(content: String) {
        _messages.update { it + TuiChatMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            role = "system",
            content = content
        )}
    }

    // --- Message selection ---

    fun messageSelectionUp() {
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val current = _selectedMessageIndex.value
        _selectedMessageIndex.value = if (current <= 0) msgs.lastIndex else current - 1
    }

    fun messageSelectionDown() {
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val current = _selectedMessageIndex.value
        _selectedMessageIndex.value = if (current < 0 || current >= msgs.lastIndex) 0 else current + 1
    }

    fun clearMessageSelection() {
        _selectedMessageIndex.value = -1
    }

    // --- Answer question ---

    fun answerQuestion(answer: String) {
        val questionId = _pendingQuestionId.value ?: return
        val r = getRouter() ?: return
        scope.launch {
            try {
                r.userInteraction.provideResponse(questionId, answer)
                _pendingQuestionId.value = null
                _pendingQuestionOptions.value = emptyList()
                // Add user answer to chat for display
                _messages.update { it + TuiChatMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = "user",
                    content = answer
                )}
            } catch (e: Exception) {
                logger.error(e) { "Failed to answer question" }
                addSystemMessage("Error answering question: ${e.message}")
            }
        }
    }

    // --- Slash prompts (prompt templates) ---

    fun getSlashPrompts(): List<SlashPrompt> {
        return SlashPrompt.BUILTINS
    }

    /**
     * Expand slash prompts inline (same as plugin's PromptInputPanel.processSlashPrompt).
     * Replaces each "/name" with its template, supporting multiple occurrences anywhere in text.
     * Only matches /name after whitespace or at start (not in URLs like https://example.com).
     */
    fun processSlashPrompts(text: String): String {
        val slashRegex = Regex("""(?<=\s|^)/([\w-]+)""")
        val matches = slashRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val slashPrompts = getSlashPrompts()
        var result = text
        var offset = 0

        for (match in matches) {
            val promptName = match.groupValues[1]
            val sp = slashPrompts.find { it.name.equals(promptName, ignoreCase = true) } ?: continue

            var template = sp.template

            // Substitute template variables
            template = template
                .replace("{{MODEL_ID}}", model.value ?: "default")
                .replace("{{PROJECT_NAME}}", projectPath.fileName?.toString() ?: "project")
                .replace("{{MODE}}", mode.value)
                .replace("{{EXECUTION_MODE}}", executionMode.value)

            val originalStart = match.range.first + offset
            val originalEnd = match.range.last + 1 + offset

            result = result.substring(0, originalStart) + template + result.substring(originalEnd)
            offset += template.length - match.value.length
        }

        return result
    }

    // --- Approve / Reject ---

    fun approve(approvalId: String) {
        scope.launch {
            agentEventBus.emit(
                AgentEvent.ApprovalDecision(
                    id = UUID.randomUUID().toString(),
                    sessionId = "",
                    sourceAgentId = "user",
                    timestamp = System.currentTimeMillis(),
                    correlationId = approvalId,
                    approvalId = approvalId,
                    approved = true,
                    reason = null
                )
            )
        }
    }

    fun reject(approvalId: String) {
        scope.launch {
            agentEventBus.emit(
                AgentEvent.ApprovalDecision(
                    id = UUID.randomUUID().toString(),
                    sessionId = "",
                    sourceAgentId = "user",
                    timestamp = System.currentTimeMillis(),
                    correlationId = approvalId,
                    approvalId = approvalId,
                    approved = false,
                    reason = "Rejected by user"
                )
            )
        }
    }

    // --- Agent trust (auto-approve) ---

    fun trustAgent(agentId: String) {
        trustedAgents.add(agentId)
        val agentName = _agents.value.find { it.id == agentId }?.name ?: agentId
        addSystemMessage("Trusted agent: $agentName (future approvals auto-approved)")
    }

    fun isAgentTrusted(agentId: String): Boolean = agentId in trustedAgents

    // --- Agent filter ---

    fun cycleAgentFilter() {
        val agents = _agents.value
        if (agents.isEmpty()) {
            _agentFilter.value = null
            return
        }
        val currentFilter = _agentFilter.value
        val agentNames = agents.map { it.name }
        val currentIdx = if (currentFilter == null) -1 else agentNames.indexOf(currentFilter)
        _agentFilter.value = if (currentIdx >= agentNames.size - 1) null
            else agentNames.getOrNull(currentIdx + 1)
        val label = _agentFilter.value ?: "All"
        addSystemMessage("Agent filter: $label")
    }

    // --- Panel focus toggle ---

    fun togglePanelFocus() {
        if (true) { // activeTab check deferred to caller
            _panelFocused.update { !it }
        }
    }

    // --- Copy to clipboard ---

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            addSystemMessage("Copied to clipboard.")
        } catch (_: Exception) {
            try {
                val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                print("\u001b]52;c;$encoded\u0007")
                addSystemMessage("Copied to clipboard (OSC 52).")
            } catch (e: Exception) {
                addSystemMessage("Clipboard not available: ${e.message}")
            }
        }
    }

    fun copyLastMessageToClipboard() {
        val msgs = _messages.value
        val idx = _selectedMessageIndex.value
        val msg = if (idx in msgs.indices) {
            msgs[idx]
        } else {
            msgs.lastOrNull { it.role == "assistant" } ?: msgs.lastOrNull()
        }
        if (msg == null) return
        copyToClipboard(msg.content)
        if (idx in msgs.indices) {
            addSystemMessage("Copied message ${idx + 1}/${msgs.size} to clipboard.")
        }
    }

    fun copyAllConversation() {
        val messages = _messages.value
        if (messages.isEmpty()) {
            addSystemMessage("No messages to copy")
            return
        }
        val text = messages.joinToString("\n\n") { msg ->
            "[${msg.role.uppercase()}] ${msg.content}"
        }
        copyToClipboard(text)
        addSystemMessage("Copied ${messages.size} messages to clipboard")
    }

    // --- Show current prompt ---

    fun showCurrentPrompt() {
        scope.launch {
            val r = getRouter() ?: return@launch
            try {
                val prompts = r.promptsRouter.getSystemPrompts()
                val text = buildString {
                    appendLine("=== System Prompts (${prompts.count} total) ===")
                    for (p in prompts.prompts.take(5)) {
                        appendLine()
                        appendLine("--- ${p.type} ${if (p.isEnabled) "✓" else "✗"} ---")
                        val content = p.content
                        appendLine(content.take(500))
                        if (content.length > 500) appendLine("... (${content.length} chars)")
                    }
                }
                addSystemMessage(text)
            } catch (e: Exception) {
                addSystemMessage("Cannot load prompts: ${e.message}")
            }
        }
    }

    // --- Rewind, resend, edit ---

    fun rewindToMessage(messageIndex: Int) {
        val messages = _messages.value
        if (messageIndex < 0 || messageIndex >= messages.size) return
        val targetMsg = messages[messageIndex]
        if (targetMsg.role != "user") {
            addSystemMessage("Can only rewind to user messages")
            return
        }
        // Keep messages up to (not including) the target, then resend it
        _messages.value = messages.take(messageIndex)
        sendMessage(targetMsg.content)
    }

    fun resendLastMessage() {
        val lastUserMsg = _messages.value.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            sendMessage(lastUserMsg.content)
        }
    }

    fun editMessage(messageIndex: Int?) {
        val messages = _messages.value
        if (messages.isEmpty()) {
            addSystemMessage("No messages to edit.")
            return
        }
        // Find the target user message
        val idx = if (messageIndex != null) {
            (messageIndex - 1).coerceIn(0, messages.lastIndex) // 1-based to 0-based
        } else {
            // Find last user message
            messages.indexOfLast { it.role == "user" }
        }
        if (idx < 0) {
            addSystemMessage("No user message found to edit.")
            return
        }
        val targetMsg = messages[idx]
        if (targetMsg.role != "user") {
            addSystemMessage("Can only edit user messages. Message $messageIndex is a ${targetMsg.role} message.")
            return
        }
        // Truncate conversation to before this message and pre-fill input
        _messages.value = messages.take(idx)
        _inputBuffer.value = targetMsg.content
        addSystemMessage("Editing message ${idx + 1}. Modify and press Enter to resend.")
    }

    // --- Continue / Summarize ---

    fun continueConversation() {
        sendMessage("Continue from where you left off")
    }

    fun summarizeConversation() {
        if (_messages.value.isEmpty()) return
        addSystemMessage("Summarizing conversation...")
        // Trigger summarization via chat router if available
        scope.launch {
            try {
                val r = getRouter() ?: return@launch
                val tid = getTaskId() ?: return@launch
                r.chatRouter.summarizeConversation(tid)
                addSystemMessage("Conversation summarized.")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to summarize conversation" }
                addSystemMessage("Failed to summarize: ${e.message}")
            }
        }
    }

    // --- Export conversation ---

    fun exportConversation(path: String): Boolean {
        val messages = _messages.value
        if (messages.isEmpty()) return false

        return try {
            val sb = StringBuilder()
            sb.appendLine("# Refio Conversation Export")
            sb.appendLine("# Mode: ${mode.value}, Model: ${model.value ?: "default"}")
            sb.appendLine("# Messages: ${messages.size}")
            sb.appendLine()

            for (msg in messages) {
                val role = msg.agentName ?: msg.role.replaceFirstChar { it.uppercase() }
                val time = apiLogTimeFormatter.format(Instant.ofEpochMilli(msg.timestamp))
                sb.appendLine("## [$role] $time")
                sb.appendLine()
                sb.appendLine(msg.content)
                sb.appendLine()
            }

            java.io.File(path).writeText(sb.toString())
            true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to export conversation to: $path" }
            false
        }
    }

    // --- Cancel current operation ---

    fun cancelCurrentOperation() {
        // Set global cancellation flag FIRST -- checked by LLM adapters, executors, tools
        GlobalMetrics.requestCancellation()
        currentJob?.cancel()
        currentJob = null
        _isStreaming.value = false
        onUpdateExecutionStatus("Cancelled")
        workflowListener.reset()
        addSystemMessage("Operation cancelled.")
        scope.launch {
            delay(2000)
            // Only reset if still "Cancelled" (no new operation started)
            onUpdateExecutionStatus("Idle") // TuiViewModel checks current value
        }
    }

    // --- Validate context references ---

    /**
     * Validate @file: and @folder: references in user input.
     * Returns warning message if invalid, null if OK.
     */
    private fun validateContextReferences(input: String): String? {
        val allRefRegex = Regex("""@\w+[:\S]*""")
        val allRefs = allRefRegex.findAll(input).toList()

        // P12: Context ref count limit
        if (allRefs.size > 50) {
            return "Too many context references (${allRefs.size}/50). Remove some to continue."
        }

        val fileRefRegex = Regex("""@(file|folder):(\S+)""")
        val root = projectPath.toAbsolutePath().toString()
        for (match in fileRefRegex.findAll(input)) {
            val fullRef = match.value
            val result = TuiContextValidator.validate(fullRef, root)
            if (!result.isValid) return result.warning
            if (result.warning != null) {
                addSystemMessage("\u26A0 ${result.warning}")
            }
        }
        return null
    }

    // --- Send message (main method) ---

    fun sendMessage(input: String) {
        if (input.isBlank()) return

        if (_isStreaming.value) {
            // Agent is running — queue message for next iteration
            val router = getRouter()
            val taskId = getTaskId()
            if (router != null && taskId != null) {
                router.pendingUserMessageQueue.enqueue(taskId, input)
                _inputBuffer.value = ""
                addSystemMessage("Message queued for next iteration")
            }
            return
        }

        _scrollOffset.value = 0 // auto-scroll to bottom on new message

        // If orchestrator is waiting for a question response, route as answer
        if (_pendingQuestionId.value != null) {
            _inputBuffer.value = ""
            answerQuestion(input)
            return
        }

        // Expand slash prompts inline (like the plugin does)
        val processedInput = processSlashPrompts(input)

        // Validate context references before sending
        val contextWarning = validateContextReferences(processedInput)
        if (contextWarning != null) {
            addSystemMessage("\u26A0 $contextWarning")
            return
        }

        val userMsg = TuiChatMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            role = "user",
            content = processedInput
        )
        _messages.update { it + userMsg }
        _inputBuffer.value = ""

        currentJob = scope.launch {
            val r = getRouter() ?: run {
                addSystemMessage("Core not initialized")
                return@launch
            }

            try {
                workflowListener.reset()
                GlobalMetrics.resetCancellation()
                _isStreaming.value = true

                // Ensure we have a valid DB-persisted task
                val tid = getTaskId() ?: onCreateNewTaskInDb(r).also { onSetTaskId(it) }

                val taskMode = try {
                    CoreTaskMode.valueOf(mode.value)
                } catch (_: Exception) {
                    CoreTaskMode.CHAT
                }

                val execMode = try {
                    CoreExecutionMode.valueOf(executionMode.value)
                } catch (_: Exception) {
                    CoreExecutionMode.AUTO
                }

                // Split "provider/model" string into separate provider and model
                // e.g. "ollama/qwen2.5-coder:7b" -> provider="ollama", model="qwen2.5-coder:7b"
                val (selectedProvider, selectedModel) = splitProviderModel(model.value)

                // Route through proper router API based on mode
                when (taskMode) {
                    CoreTaskMode.CHAT -> {
                        onUpdateExecutionStatus("Chatting...")
                        val chatRequest = ChatRequest(
                            taskId = tid,
                            mode = taskMode,
                            input = processedInput,
                            params = LLMParams(
                                model = selectedModel,
                                provider = selectedProvider
                            )
                        )
                        val response = r.chatRouter.chat(chatRequest, stream = true) { chunk ->
                            workflowListener.onStreamChunk(chunk.delta)
                        }
                        // Finalize stream with the full response
                        workflowListener.onStreamComplete(response.output)
                        // Update metrics
                        response.costs.let { costs ->
                            onUpdateTotalTokens((costs.tokensIn + costs.tokensOut).toLong())
                            onUpdateTotalCost(costs.usdEst)
                            onUpdateCachedTokens(costs.cachedTokens)
                        }
                    }

                    CoreTaskMode.PLAN, CoreTaskMode.AGENT -> {
                        onUpdateExecutionStatus(if (taskMode == CoreTaskMode.PLAN) "Planning..." else "Agent executing...")

                        val turnListener = TuiToolCallListener(
                            scope = scope,
                            messagesState = _messages,
                            onToolStarted = { toolName -> workflowListener.onToolStarted(toolName) },
                            onReloadSubtasks = {
                                try { onLoadSubtasksFromDb(r, tid) } catch (_: Exception) {}
                            },
                        )

                        val turnRequest = TurnRequest(
                            taskId = tid,
                            userInput = processedInput,
                            mode = taskMode,
                            executionMode = execMode,
                            model = selectedModel,
                            provider = selectedProvider
                        )
                        val result: TurnResult = r.agentRouter.runTurn(
                            turnRequest,
                            streamCallback = { chunk -> workflowListener.onStreamChunk(chunk.delta) },
                            listener = turnListener
                        )
                        // Finalize stream
                        workflowListener.onStreamComplete(result.response)
                        // Update metrics
                        onUpdateTotalTokens((result.tokensIn + result.tokensOut).toLong())
                        onUpdateTotalCost(result.cost)
                        onUpdateCachedTokens(result.cachedTokens)
                        // Reload subtasks from DB -- plan/agent may have created new ones
                        onLoadSubtasksFromDb(r, tid)
                    }
                }

                _isStreaming.value = false
                onUpdateExecutionStatus("Idle")

                // Reload messages from DB (authoritative source)
                onLoadMessagesFromDb(r, tid)

                onUpdateDebugInfo(_messages.value.size)

                // Auto-name session if still using default name
                autoNameSessionIfNeeded(r, tid, processedInput)

                // Refresh API logs from database
                onRefreshApiLogs(r)
            } catch (e: pl.jclab.refio.core.errors.RefioError.MalformedResponse) {
                logger.error(e) {
                    "Malformed response from provider=${e.provider}/${e.model}: reason=${e.reason}, " +
                            "bodyPreview=${e.bodyPreview.take(500)}"
                }
                _isStreaming.value = false
                onUpdateExecutionStatus("Error")
                _messages.update { messages ->
                    messages + TuiChatMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        role = "system",
                        content = "Provider ${e.provider} returned an invalid response — check CLI logs for details.",
                        messageType = TuiMessageType.AGENT_FAILED
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Workflow error" }
                _isStreaming.value = false
                onUpdateExecutionStatus("Error")
                _messages.update { messages ->
                    messages + TuiChatMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        role = "system",
                        content = "Error: ${e.message}",
                        messageType = TuiMessageType.AGENT_FAILED
                    )
                }
            }
        }
    }

    /**
     * Split a combined "provider/model" string into (provider, model).
     * e.g. "ollama/qwen2.5-coder:7b" -> ("ollama", "qwen2.5-coder:7b")
     * If no "/" is present, infers provider via LLMClient.
     */
    private fun splitProviderModel(combined: String?): Pair<String?, String?> {
        if (combined.isNullOrBlank()) return Pair(null, null)
        val slashIdx = combined.indexOf('/')
        return if (slashIdx > 0) {
            val provider = combined.substring(0, slashIdx)
            val model = combined.substring(slashIdx + 1)
            Pair(provider, model)
        } else {
            // No provider prefix -- try to infer
            Pair(null, combined)
        }
    }

    // --- Auto-naming ---

    private fun autoNameSessionIfNeeded(router: CoreApiRouter, taskId: String, userInput: String) {
        scope.launch {
            try {
                val task = router.taskRouter.getTask(taskId) ?: return@launch
                if (task.name != "New Session") return@launch

                val rawTitle = router.chatRouter.generateSessionTitle(taskId, userInput)
                val name = rawTitle.trim()
                    .trim('"', '\'', '\u201C', '\u201D')
                    .replace(Regex("[\\r\\n]+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("[.!?:;]+$"), "")
                    .take(60)
                    .ifBlank { userInput.trim().replace(Regex("\\s+"), " ").take(50) }
                    .ifBlank { "Chat" }
                router.taskRouter.updateTask(taskId, pl.jclab.refio.core.api.UpdateTaskRequest(name = name))
                logger.info { "Auto-named session: '$name'" }
            } catch (e: Exception) {
                logger.warn(e) { "Auto-naming failed, using fallback" }
                try {
                    val fallback = userInput.trim().replace(Regex("\\s+"), " ").take(50).ifBlank { "Chat" }
                    router.taskRouter.updateTask(taskId, pl.jclab.refio.core.api.UpdateTaskRequest(name = fallback))
                } catch (_: Exception) { }
            }
        }
    }

    // --- Autocomplete (@context, !subagent, /command) ---

    private val allContextPrefixes = listOf(
        "@file:", "@folder:", "@selection", "@current", "@open_files",
        "@recent", "@problems", "@terminal", "@git_diff", "@git_commit:",
        "@grep:", "@codebase:", "@docs:", "@url:"
    )

    /** Which trigger char started the autocomplete: '@', '!', '/' */
    private var autocompleteTrigger: Char = '@'

    /** Cache for subagent names (refreshed on trigger) */
    private var cachedSubagentNames: List<String> = emptyList()
    private var subagentCacheTime: Long = 0

    /** Built-in slash prompts for autocomplete */
    private val builtinCommandNames: List<String> by lazy {
        try {
            pl.jclab.refio.api.models.SlashPrompt.BUILTINS.map { "/${it.name}" }
        } catch (_: Exception) {
            listOf("/explain", "/fix", "/test", "/refactor", "/optimize", "/simplify",
                "/document", "/security-review", "/translate", "/implement")
        }
    }

    fun triggerAutocomplete() {
        autocompleteTrigger = '@'
        // Include agent names when multi-agent session is active
        val agentNames = _agents.value.map { "@${it.name}" }
        _autocompleteCandidates.value = allContextPrefixes + agentNames
        _autocompleteSelectedIndex.value = 0
        _autocompleteVisible.value = true
    }

    fun triggerSubagentAutocomplete() {
        autocompleteTrigger = '!'
        refreshSubagentCache()
        _autocompleteCandidates.value = cachedSubagentNames.ifEmpty { listOf("(no subagents available)") }
        _autocompleteSelectedIndex.value = 0
        _autocompleteVisible.value = true
    }

    fun triggerCommandAutocomplete() {
        autocompleteTrigger = '/'
        _autocompleteCandidates.value = builtinCommandNames
        _autocompleteSelectedIndex.value = 0
        _autocompleteVisible.value = true
    }

    private fun refreshSubagentCache() {
        val now = System.currentTimeMillis()
        if (now - subagentCacheTime < 5000 && cachedSubagentNames.isNotEmpty()) return
        try {
            val r = getRouter() ?: return
            val subagents = r.subagentRouter?.listSubagents() ?: emptyList()
            cachedSubagentNames = subagents.map { "!${it.name}" }
            subagentCacheTime = now
        } catch (e: Exception) {
            logger.debug(e) { "Failed to list subagents" }
        }
    }

    /**
     * Provide file/folder listing for @file: or @folder: autocomplete.
     */
    private fun listFilesForAutocomplete(prefix: String, isFolder: Boolean): List<String> {
        return try {
            val root = projectPath.toFile()
            val partial = prefix.substringAfter(if (isFolder) "@folder:" else "@file:")
            val searchDir = if (partial.contains('/') || partial.contains('\\')) {
                val parentPath = partial.substringBeforeLast('/')
                java.io.File(root, parentPath)
            } else {
                root
            }
            if (!searchDir.isDirectory) return emptyList()

            val parentPrefix = if (partial.contains('/')) partial.substringBeforeLast('/') + "/" else ""
            val nameFilter = if (partial.contains('/')) partial.substringAfterLast('/') else partial

            searchDir.listFiles()
                ?.filter { file ->
                    if (isFolder) file.isDirectory else true
                }
                ?.filter { it.name.startsWith(nameFilter, ignoreCase = true) }
                ?.filter { !it.name.startsWith(".") } // hide hidden files
                ?.sortedWith(compareBy<java.io.File> { !it.isDirectory }.thenBy { it.name })
                ?.take(15)
                ?.map { file ->
                    val tag = if (isFolder) "@folder:" else "@file:"
                    val suffix = if (file.isDirectory) "/" else ""
                    "$tag$parentPrefix${file.name}$suffix"
                } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updateAutocompleteFilter() {
        val input = _inputBuffer.value
        when (autocompleteTrigger) {
            '@' -> {
                val atIdx = input.lastIndexOf('@')
                if (atIdx < 0) { autocompleteDismiss(); return }
                val filter = input.substring(atIdx).lowercase()

                // If user is typing @file:path or @folder:path, show file listing
                if (filter.startsWith("@file:") && filter.length > 6) {
                    val files = listFilesForAutocomplete(filter, isFolder = false)
                    if (files.isNotEmpty()) {
                        _autocompleteCandidates.value = files
                        _autocompleteSelectedIndex.value = 0
                        return
                    }
                }
                if (filter.startsWith("@folder:") && filter.length > 8) {
                    val folders = listFilesForAutocomplete(filter, isFolder = true)
                    if (folders.isNotEmpty()) {
                        _autocompleteCandidates.value = folders
                        _autocompleteSelectedIndex.value = 0
                        return
                    }
                }

                val filtered = allContextPrefixes.filter { it.lowercase().startsWith(filter) }
                if (filtered.isEmpty()) { autocompleteDismiss() }
                else {
                    _autocompleteCandidates.value = filtered
                    _autocompleteSelectedIndex.value = _autocompleteSelectedIndex.value.coerceIn(0, filtered.size - 1)
                }
            }
            '!' -> {
                val bangIdx = input.lastIndexOf('!')
                if (bangIdx < 0) { autocompleteDismiss(); return }
                val filter = input.substring(bangIdx).lowercase()
                val filtered = cachedSubagentNames.filter { it.lowercase().startsWith(filter) }
                if (filtered.isEmpty()) { autocompleteDismiss() }
                else {
                    _autocompleteCandidates.value = filtered
                    _autocompleteSelectedIndex.value = _autocompleteSelectedIndex.value.coerceIn(0, filtered.size - 1)
                }
            }
            '/' -> {
                val slashIdx = input.lastIndexOf('/')
                if (slashIdx < 0) { autocompleteDismiss(); return }
                val filter = input.substring(slashIdx).lowercase()
                val filtered = builtinCommandNames.filter { it.lowercase().startsWith(filter) }
                if (filtered.isEmpty()) { autocompleteDismiss() }
                else {
                    _autocompleteCandidates.value = filtered
                    _autocompleteSelectedIndex.value = _autocompleteSelectedIndex.value.coerceIn(0, filtered.size - 1)
                }
            }
        }
    }

    fun autocompleteNext() {
        val max = _autocompleteCandidates.value.size
        if (max > 0) _autocompleteSelectedIndex.value = (_autocompleteSelectedIndex.value + 1) % max
    }

    fun autocompletePrev() {
        val max = _autocompleteCandidates.value.size
        if (max > 0) _autocompleteSelectedIndex.value = (_autocompleteSelectedIndex.value - 1 + max) % max
    }

    fun autocompleteAccept() {
        val candidates = _autocompleteCandidates.value
        val idx = _autocompleteSelectedIndex.value
        if (idx in candidates.indices) {
            val selected = candidates[idx]
            if (selected.startsWith("(")) return // placeholder like "(no subagents available)"
            val input = _inputBuffer.value
            val triggerIdx = when (autocompleteTrigger) {
                '@' -> input.lastIndexOf('@')
                '!' -> input.lastIndexOf('!')
                '/' -> input.lastIndexOf('/')
                else -> -1
            }
            if (triggerIdx >= 0) {
                _inputBuffer.value = input.substring(0, triggerIdx) + selected + " "
            }
        }
        autocompleteDismiss()
    }

    fun autocompleteDismiss() {
        _autocompleteVisible.value = false
        _autocompleteCandidates.value = emptyList()
        _autocompleteSelectedIndex.value = 0
    }
}
