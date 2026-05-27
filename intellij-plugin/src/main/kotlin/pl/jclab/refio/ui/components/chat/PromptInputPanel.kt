package pl.jclab.refio.ui.components.chat

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.SlashPrompt
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.ContextSubmenuItem
import pl.jclab.refio.core.context.LoadSubmenuItemsArgs
import pl.jclab.refio.core.context.ProviderType
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.completion.RefioCompletionContributor
import pl.jclab.refio.ui.components.autocomplete.AutocompletePopup
import pl.jclab.refio.ui.components.autocomplete.PromptAutocompleteItem
import pl.jclab.refio.ui.components.autocomplete.ContextAutocompleteItem
import pl.jclab.refio.ui.components.autocomplete.ContextValidator
import pl.jclab.refio.ui.components.input.InputPanelContainer
import pl.jclab.refio.ui.components.input.SnippetsContainer
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.api.CoreApiRouter
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*

/**
 * Prompt input panel - separate component for user input
 * Located between ChatView and StatusBar
 *
 * Layout:
 * Row 0: [TextArea - full width, expandable]
 * Row 1: Mode [DropDown] | Model [DropDown] | ... | [Send Button]
 */
class PromptInputPanel(
    private val project: Project,
    private val chatView: ChatView? = null,
    private val coreApiClient: CoreApiRouter? = null
) : JBPanel<PromptInputPanel>(GridBagLayout()) {

    companion object {
        private val KEY_LISTENERS_INSTALLED = Key.create<Boolean>("refio.promptEditor.listenersInstalled")
        private val ENTER_HANDLER_INSTALLED = AtomicBoolean(false)
        private val SEND_ON_ENTER_KEY = Key.create<() -> Unit>("refio.promptEditor.sendOnEnter")
        private val IS_AUTOCOMPLETE_VISIBLE_KEY = Key.create<() -> Boolean>("refio.promptEditor.isAutocompleteVisible")
    }

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionManager = SessionManager.getInstance(project)
    private val globalMetrics = GlobalMetrics
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val logger = dualLogger("PromptInputPanel")

    private val addContextButton: JButton
    private val modeSelector: JComboBox<String>
    private val modelSelector: JComboBox<ModelItem>


    private val inputContainer: InputPanelContainer
    private val promptEditor: EditorTextField
    private val sendButton: JButton
    private val stopButton: JButton
    private var isOperationRunning = false
    private var lastPreferredEditorHeight: Int = -1
    private val editorShortcutsDisposable = Disposer.newDisposable(project, "refio.promptInputPanel.editorShortcuts")

    // Autocomplete
    private var contextAutocomplete: AutocompletePopup<ContextAutocompleteItem>
    private var promptAutocomplete: AutocompletePopup<PromptAutocompleteItem>

    // Current submenu provider ID (for tracking which provider's submenu is shown)
    private var currentSubmenuProviderId: String? = null
    private val docsPlaceholderId = "__no_docs__"

    // Autocomplete debouncing using Swing Timer (lazy initialization to avoid memory leaks)
    private val autocompleteTimer: Timer by lazy {
        Timer(AUTOCOMPLETE_DEBOUNCE_MS) {
            performAutocomplete()
        }.apply {
            isRepeats = false
        }
    }
    private val AUTOCOMPLETE_DEBOUNCE_MS = 150
    private val CONTEXT_LIMIT = 50
    private val continuePromptText = "Continue from where you left off."

    // Job for autocomplete coroutines (cancel previous when starting new)
    private var autocompleteJob: kotlinx.coroutines.Job? = null

    // Cached slash prompts (loaded once, used for prepending templates)
    private var cachedSlashPrompts: List<SlashPrompt> = emptyList()

    // Context references
    private val contextReferences = mutableListOf<ContextReference>()
    private val contextTagsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT))

    // Code snippets (Ctrl+J / Ctrl+Shift+J)
    private var snippetsContainer: SnippetsContainer
    private val inputBorderPanel = GradientBorderPanel().apply {
        layout = BorderLayout()
        isOpaque = false
        border = LCATheme.emptyBorder()
    }

    // Flag to prevent triggering API calls during initialization
    private var isInitializing = true
    private var isUpdatingModeSelectorProgrammatically = false
    private var isUpdatingModelSelectorProgrammatically = false

    init {
        border = LCATheme.paddedBorder(5)

        val gbc = GridBagConstraints()

        // Initialize autocomplete popups
        contextAutocomplete = AutocompletePopup { item ->
            handleContextSelection(item)
        }

        promptAutocomplete = AutocompletePopup { item ->
            insertSlashPrompt(item.slashPrompt)
        }

        promptEditor = createPromptEditor()
        promptEditor.border = LCATheme.emptyBorder()

        promptEditor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                triggerNativeCompletionIfNeeded(event)
                updatePromptEditorHeight()
                onPromptInputChanged()
            }
        }, editorShortcutsDisposable)

        cs.launch {
            try {
                loadSlashPrompts()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to preload slash prompts" }
            }
        }

        // Subagents "!" autocomplete is handled by native IntelliJ completion (RefioCompletionContributor)

        // === INPUT CONTAINER: Snippets + Context Tags + Editor ===
        snippetsContainer = SnippetsContainer { id ->
            removeCodeSnippet(id)
        }

        contextTagsPanel.apply {
            border = LCATheme.paddedBorder(5, 0, 0, 0)
            isOpaque = false
            isVisible = false
        }

        inputContainer = InputPanelContainer(
            snippetsContainer = snippetsContainer,
            contextTagsPanel = contextTagsPanel,
            editorComponent = promptEditor,
        ).apply {
            minimumSize = Dimension(200, 90)
        }

        inputBorderPanel.removeAll()
        inputBorderPanel.add(inputContainer, BorderLayout.CENTER)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                updatePromptEditorHeight()
            }
        })

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 8
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = LCATheme.insetsBottom(5)
        add(inputBorderPanel, gbc)

        // === LAYOUT: Row 1 - Buttons row ===
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weighty = 0.0
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = LCATheme.insetsRight(5)

        // Add Context button (+)
        addContextButton = JButton("+").apply {
            toolTipText = "Add Context to prompt"
            minimumSize = Dimension(32, 28)
            preferredSize = Dimension(32, 28)
            maximumSize = Dimension(32, 28)

            addActionListener {
                showContextMenu()
            }
        }
        gbc.gridx = 0
        add(addContextButton, gbc)

        // Mode dropdown
        modeSelector = JComboBox(arrayOf("💬 Chat", "📝 Plan", "🤖 Agent")).apply {
            selectedIndex = 0
            toolTipText = "Switch mode (Alt+M)"
            minimumSize = Dimension(100, 28)
            preferredSize = Dimension(100, 28)
            maximumSize = Dimension(110, 28)

            addActionListener {
                // Skip if UI is being updated programmatically
                if (isUpdatingModeSelectorProgrammatically) {
                    logger.info { "Mode selector update skipped - programmatic update in progress" }
                    return@addActionListener
                }

                val newMode = getSelectedMode()
                logger.info { "Mode selector changed to: $newMode" }

                cs.launch {
                    val currentSession = sessionManager.activeSession.value

                    if (currentSession == null) {
                        logger.info { "No active session - creating new session with mode $newMode" }
                        val executionMode = getCurrentExecutionMode()
                        sessionManager.createSession("Session (${newMode.name})", newMode, executionMode)
                    } else if (currentSession.mode != newMode) {
                        logger.info { "Switching mode from ${currentSession.mode} to $newMode" }
                        try {
                            sessionManager.switchMode(newMode)
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to switch mode" }
                        }
                    }
                }
            }
        }
        gbc.gridx = 1
        add(modeSelector, gbc)

        // Model dropdown
        modelSelector = JComboBox<ModelItem>().apply {
            minimumSize = Dimension(200, 28)
            preferredSize = Dimension(300, 28)
            maximumSize = Dimension(400, 28)

            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is ModelItem) {
                        text = value.fullDisplayName
                    }
                    return component
                }
            }

            addActionListener {
                if (isInitializing || isUpdatingModelSelectorProgrammatically) {
                    logger.info { "Model selector update skipped - programmatic update in progress" }
                    return@addActionListener
                }

                val selectedItem = selectedItem as? ModelItem ?: return@addActionListener

                logger.info { "Model MANUALLY changed to: ${selectedItem.modelId} (provider=${selectedItem.provider})" }

                // Handle "Auto" mode - use operation-specific defaults
                if (selectedItem.modelId == "auto") {
                    logger.info { "Auto mode selected - will use operation-specific models from Settings" }

                    // Update SessionManager state (includes persistence to current task + app defaults)
                    sessionManager.setSelectedModel("auto")

                    logger.info { "Auto mode saved" }
                    return@addActionListener
                }

                // Update SessionManager state (includes persistence to current task + app defaults)
                val selectedModel = "${selectedItem.provider.replaceFirstChar { it.uppercase() }}/${selectedItem.modelId}"
                sessionManager.setSelectedModel(selectedModel)
                logger.info { "Selected model saved: $selectedModel" }
            }
        }
        gbc.gridx = 2
        add(modelSelector, gbc)

        // Invisible glue component to push send/stop buttons right
        gbc.gridx = 3
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        add(Box.createHorizontalGlue(), gbc)

        // Send/Stop button (right side of row 1)
        // Transforms: Send → Stop during operation, Stop → Send when idle
        stopButton = JButton("🔴 Stop").apply {
            toolTipText = "Stop current operation"
            minimumSize = Dimension(80, 28)
            preferredSize = Dimension(80, 28)
            maximumSize = Dimension(80, 28)
            isVisible = false
            addActionListener { handleStopOperation() }
        }
        gbc.gridx = 5
        gbc.weightx = 0.0
        gbc.insets = LCATheme.insetsNone
        add(stopButton, gbc)

        sendButton = JButton("🚀 Send").apply {
            toolTipText = "Send prompt (Enter)"
            minimumSize = Dimension(90, 28)
            preferredSize = Dimension(90, 28)
            maximumSize = Dimension(90, 28)
            addActionListener { handleSendMessage() }
        }
        gbc.gridx = 6
        gbc.weightx = 0.0
        gbc.insets = LCATheme.insetsNone
        add(sendButton, gbc)

        // Load models from backend
        loadAvailableModels()

        // Listen to session changes
        cs.launch {
            var lastSessionId: String? = sessionManager.activeSession.value?.id
            sessionManager.activeSession.collect { session ->
                val newSessionId = session?.id
                val didSessionChange = lastSessionId != null && newSessionId != null && newSessionId != lastSessionId
                if (didSessionChange) {
                    SwingUtilities.invokeLater {
                        clearPrompt()
                        clearContext()
                        clearSnippets()
                        updatePromptEditorHeight()
                    }
                }

                lastSessionId = newSessionId

                if (session != null) {
                    logger.info { "Session changed: mode=${session.mode}, executionMode=${session.executionMode}" }
                    updateSession(session)
                }
            }
        }

        // Listen to BOTH global operation state AND step execution state AND user interaction state
        // Button should be "Stop" if:
        // - Operation is running (CHAT streaming, planning) OR
        // - Step execution is active (executing steps after planning) OR
        // - BUT NOT if waiting for user response (then show "Send")
        cs.launch {
            combine(
                globalMetrics.currentOperation,
                stepExecutionService.isExecuting,
                sessionManager.userInteraction.isWaitingForResponse,
                sessionManager.isGenerating
            ) { operation, isStepExecuting, isWaitingForInput, isGenerating ->
                if (isWaitingForInput) {
                    false
                } else {
                    val isOperationActive = operation !is OperationInfo.Idle
                    isOperationActive || isStepExecuting || isGenerating
                }
            }.collect { isRunning ->
                SwingUtilities.invokeLater {
                    updateOperationState(isRunning)
                }
            }
        }

        cs.launch {
            SwingUtilities.invokeLater {
                setPromptEditorEnabled(true, LCATheme.editorBackground)
            }
        }
    }

    private fun handleSendMessage() {
        val text = promptEditor.text.trim()
        if (text.isEmpty()) return

        // /goal control command — mutates per-task completion condition consumed by
        // NextSpeakerJudgeGuardian. Intercepted here (before isOperationRunning check)
        // so the user can set/clear/inspect a goal mid-execution without the input
        // being queued or sent as a chat message.
        if (text.startsWith("/goal") && (text.length == 5 || text[5].isWhitespace())) {
            handleGoalCommand(text.removePrefix("/goal").trim())
            promptEditor.text = ""
            return
        }

        if (isOperationRunning) {
            // Agent is running — queue message for next iteration
            val activeSession = sessionManager.activeSession.value
            if (activeSession != null) {
                val snippetRefs = snippetsContainer.getSnippets().map { it.toContextReference() }
                val allRefs = contextReferences + snippetRefs
                val messageText = buildMidExecutionMessage(text, allRefs)
                sessionManager.pendingUserMessageQueue.enqueue(activeSession.id, messageText)
                promptEditor.text = ""
                clearContext()
                sessionManager.notifyMidExecutionMessage(activeSession.id, messageText)
                logger.info { "Queued mid-execution message for taskId=${activeSession.id} (${allRefs.size} context refs)" }
            } else {
                logger.warn { "Operation running but no active session, ignoring send" }
            }
            return
        }

        // VALIDATE SLASH PROMPT FIRST (before clearing editor)
        // Expand slash prompt: prepend its template to the user text
        val slashProcessedText = processSlashPrompt(text)
        if (slashProcessedText == null) {
            // Validation failed (slash prompt not at start) - don't send, keep text in editor
            logger.warn { "Slash prompt validation failed, message not sent" }
            return
        }
        val processedText = applyPromptTemplateVariables(slashProcessedText)

        logger.info { "[CONTEXT_DEBUG] handleSendMessage: contextReferences.size=${contextReferences.size}, items=${contextReferences.map { "${it.type}:${it.path}" }}" }
        logger.info { "[CONTEXT_DEBUG] handleSendMessage: codeSnippets.size=${snippetsContainer.getSnippets().size}" }

        // Operation state is now managed entirely by the combine flow watching GlobalMetrics.
        // Setting it directly here caused flickering due to race with the flow.

        promptEditor.text = ""
        sessionManager.clearPendingUserInput()

        // Convert code snippets to context references
        val snippetRefs = snippetsContainer.getSnippets().map { it.toContextReference() }
        val inlineProviderRefs = extractInlineProviderContextRefs(processedText, promptForMissingQuery = true)
        val contextToSend = mergeContextRefs(contextReferences + snippetRefs, inlineProviderRefs)
        if (contextToSend.size > CONTEXT_LIMIT) {
            showContextLimitError(contextToSend.size)
            return
        }
        // NOTE: Don't clear context here! Clear it AFTER successful message processing
        // clearContext() was moved to after sendMessage() to fix context reference issue

        logger.info { "[CONTEXT_DEBUG] Sending ${contextToSend.size} context references" }

        cs.launch {
            try {
                // Check if orchestrator is waiting for response
                val questionId = sessionManager.userInteraction.currentQuestionId.value

                if (questionId != null) {
                    // User is responding to orchestrator question
                    logger.info { "User responding to question: $questionId" }
                    sessionManager.answerQuestion(questionId, processedText)
                    // Clear context and snippets after sending
                    clearContext()
                    clearSnippets()
                } else {
                    // Normal message flow
                    // Ensure we have an active session before sending message
                    val currentSession = sessionManager.activeSession.value

                    if (currentSession == null) {
                        logger.info { "No active session - creating new session before sending message" }
                        val currentMode = getSelectedMode()
                        val executionMode = getCurrentExecutionMode()
                        sessionManager.createSession("Session (${currentMode.name})", currentMode, executionMode)
                    }

                    // Get currently selected model from dropdown
                    val selectedItem = modelSelector.selectedItem as? ModelItem
                    val modelId = selectedItem?.modelId
                    val provider = selectedItem?.provider

                    // If "Auto" is selected, pass null to use default model from settings
                    // Otherwise, pass the selected model
                    val isAutoMode = modelId == "auto"

                    sessionManager.sendMessage(
                        input = processedText,
                        contextRefs = contextToSend,
                        model = if (!isAutoMode && modelId != null && provider != null) {
                            modelId
                        } else {
                            null  // Use default from Settings
                        },
                        provider = if (!isAutoMode) provider?.lowercase() else null
                    )

                    // Clear context and snippets after sending
                    clearContext()
                    clearSnippets()
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to send message or answer question" }
                // Don't clear context on error - user might want to retry with same context
                // Error handled by SessionManager
            }
        }
    }

    /**
     * Handle `/goal …` control commands. Three shapes:
     *   - `/goal`                 → show current condition (or "none")
     *   - `/goal clear|stop|off`  → clear condition
     *   - `/goal <text>`          → set condition (capped at 4000 chars upstream)
     *
     * Results surface as IntelliJ balloon notifications (group "Refio") — same channel
     * the rest of the plugin uses for non-modal status messages.
     */
    private fun handleGoalCommand(args: String) {
        val api = coreApiClient
        if (api == null) {
            notifyGoal("Refio core not connected — start a conversation first.", NotificationType.WARNING)
            return
        }
        val taskId = sessionManager.activeSession.value?.id
        if (taskId == null) {
            notifyGoal("No active session — start a conversation first, then set a goal.", NotificationType.WARNING)
            return
        }
        val isClear = args.equals("clear", ignoreCase = true) ||
            args.equals("stop", ignoreCase = true) ||
            args.equals("off", ignoreCase = true) ||
            args.equals("reset", ignoreCase = true) ||
            args.equals("none", ignoreCase = true) ||
            args.equals("cancel", ignoreCase = true)
        cs.launch(Dispatchers.IO) {
            try {
                when {
                    args.isEmpty() -> {
                        val current = api.taskRouter.getGoal(taskId)
                        notifyGoal(
                            if (current != null) "◎ goal: $current" else "(no goal set — use /goal <condition> to set one)",
                            NotificationType.INFORMATION
                        )
                    }
                    isClear -> {
                        val had = api.taskRouter.getGoal(taskId) != null
                        api.taskRouter.clearGoal(taskId)
                        notifyGoal(if (had) "goal cleared" else "no goal was set", NotificationType.INFORMATION)
                    }
                    else -> {
                        api.taskRouter.setGoal(taskId, args)
                        notifyGoal(
                            "◎ goal set: ${args.take(120)}${if (args.length > 120) "…" else ""}",
                            NotificationType.INFORMATION
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                notifyGoal("Failed to set goal: ${e.message}", NotificationType.WARNING)
            } catch (e: Exception) {
                logger.error(e) { "Failed to handle /goal command" }
                notifyGoal("Failed to handle /goal: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    private fun notifyGoal(content: String, type: NotificationType) {
        Notifications.Bus.notify(Notification("Refio", "Goal", content, type), project)
    }

    /**
     * Process all slash prompts in text.
     * Replaces each "/name" with its template, supporting multiple slash prompts.
     * Format: "text /cmd1 more text /cmd2 end" -> "text TEMPLATE1 more text TEMPLATE2 end"
     *
     * @return Processed text with all slash prompts replaced
     */
    /**
     * Build message text with inlined context refs for mid-execution messages.
     * Since context refs can't be passed through TurnRequest (already running),
     * we inline file contents directly into the message text.
     */
    private fun buildMidExecutionMessage(text: String, refs: List<ContextReference>): String {
        if (refs.isEmpty()) return text
        val sb = StringBuilder(text)
        for (ref in refs) {
            val content = ref.content ?: try {
                java.io.File(ref.path).takeIf { it.isFile && it.length() < 512_000 }?.readText()
            } catch (_: Exception) { null }
            if (content != null) {
                sb.append("\n\n--- ${ref.displayName} ---\n")
                sb.append(content)
            } else {
                sb.append("\n\n[Referenced: ${ref.displayName} (${ref.path})]")
            }
        }
        return sb.toString()
    }

    private fun processSlashPrompt(text: String): String? {
        // Find all slash prompts using regex.
        // Only match /name after whitespace or at start of text (not in URLs like https://example.com/path)
        val slashRegex = Regex("""(?<=\s|^)/([\w-]+)""")
        val matches = slashRegex.findAll(text).toList()

        if (matches.isEmpty()) {
            return text
        }

        var result = text
        var offset = 0  // Track position shift after replacements

        for (match in matches) {
            val promptName = match.groupValues[1]
            val slashPrompt = cachedSlashPrompts.find { it.name.equals(promptName, ignoreCase = true) }

            if (slashPrompt == null) {
                logger.warn { "Slash prompt not found: /$promptName, skipping" }
                continue
            }

            // Build template with variable substitution
            var template = slashPrompt.template

            // Replace {selection} variable if present
            if ("selection" in slashPrompt.variables) {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                val selection = editor?.selectionModel?.selectedText ?: ""
                template = template.replace("{selection}", selection)
            }

            // Replace the slash prompt with its template
            val originalStart = match.range.first + offset
            val originalEnd = match.range.last + 1 + offset

            result = result.substring(0, originalStart) + template + result.substring(originalEnd)

            // Update offset for next replacement
            offset += template.length - match.value.length

            logger.info { "Replaced slash prompt /$promptName at position ${match.range.first} with template" }
        }

        return result
    }

    private fun showContextLimitError(count: Int) {
        SwingUtilities.invokeLater {
            Messages.showErrorDialog(
                project,
                "Context limit exceeded: $count (max $CONTEXT_LIMIT)",
                "Context Limit"
            )
        }
    }

    /**
     * Load available models from embedded core via SessionManager
     */
    private fun loadAvailableModels() {
        // Treat model list refresh as initialization to prevent accidental persistence during dropdown rebuild.
        isInitializing = true

        // Save currently selected model BEFORE any async operations
        val previouslySelectedModel = (modelSelector.selectedItem as? ModelItem)?.modelId
        logger.info { "Previously selected model: $previouslySelectedModel" }

        cs.launch {
            try {
                logger.info { "Loading available models from embedded core..." }

                // Get models from SessionManager (uses embedded core, in-process)
                val modelStrings = sessionManager.getAvailableModels()

                // Build "provider/id" -> backend-provided display name map so dynamic
                // providers (OpenRouter, LM Studio) show friendly names like
                // "Google: Gemini 2.5 Flash (via OpenRouter)" instead of raw ids.
                val backendNames: Map<String, String> = try {
                    coreApiClient?.configRouter
                        ?.getModelsWithVisibility(fetchIfMissing = false)
                        ?.associate { "${it.provider.lowercase()}/${it.id}" to it.name }
                        ?: emptyMap()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to fetch ModelInfo names; falling back to ids" }
                    emptyMap()
                }

                SwingUtilities.invokeLater {
                    isUpdatingModelSelectorProgrammatically = true
                    try {

                        modelSelector.removeAllItems()

                        // Add "Auto" option at the beginning
                        modelSelector.addItem(ModelItem("auto", "Auto (from Settings)", "Auto (from Settings)", "auto"))
                        modelSelector.selectedIndex = 0

                        if (modelStrings.isEmpty()) {
                            logger.warn { "No models available from core, using fallback" }
                            modelSelector.selectedIndex = 0
                        } else {
                            // Parse and sort models: first by provider (alphabetically), then by model name
                            val modelItems = modelStrings.mapNotNull { modelString ->
                                // Model string format: "Ollama/qwen2.5:7b"
                                val parts = modelString.split("/", limit = 2)
                                if (parts.size == 2) {
                                    val provider = parts[0].lowercase()
                                    val modelId = parts[1]
                                    val displayName = modelString.replace("/", " / ")
                                    val backendName = backendNames["$provider/$modelId"]
                                    val fullDisplayName = if (backendName != null) {
                                        "${provider.replaceFirstChar { it.uppercase() }} - $backendName"
                                    } else {
                                        getModelDisplayName(modelId, provider)
                                    }
                                    ModelItem(modelId, displayName, fullDisplayName, provider)
                                } else null
                            }.sortedWith(compareBy<ModelItem> { it.provider }.thenBy { it.modelId })

                            // Add sorted models to dropdown
                            modelItems.forEach { modelSelector.addItem(it) }
                            logger.info { "Loaded ${modelStrings.size} models from core (sorted by provider, then model name)" }

                            // Try to restore previously selected model (if it still exists in list)
                            if (previouslySelectedModel != null) {
                                val restoredIndex = (0 until modelSelector.itemCount).firstOrNull { i ->
                                    modelSelector.getItemAt(i).modelId == previouslySelectedModel
                                }

                                if (restoredIndex != null) {
                                    modelSelector.selectedIndex = restoredIndex
                                    logger.info { "Restored previously selected model: $previouslySelectedModel" }
                                } else {
                                    logger.warn { "Previously selected model '$previouslySelectedModel' not found in new list, loading default" }
                                    loadDefaultModelFromCore()
                                }
                            } else {
                                // No previous selection - load default model from Settings
                                loadDefaultModelFromCore()
                            }
                        }
                    } finally {
                        isUpdatingModelSelectorProgrammatically = false
                    }

                    Timer(1000) {
                        isInitializing = false
                        logger.info { "Initialization complete - model changes will now sync to core" }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load models from core" }

                SwingUtilities.invokeLater {
                    isUpdatingModelSelectorProgrammatically = true
                    try {
                        // Even on error, try to preserve previously selected model if possible
                        if (previouslySelectedModel != null && previouslySelectedModel != "auto") {
                            logger.info { "Failed to load models, but preserving previously selected: $previouslySelectedModel" }
                            // Don't clear dropdown - keep current selection
                        } else {
                            // Only clear and reset if there was no previous selection
                            modelSelector.removeAllItems()

                            // Add "Auto" option at the beginning
                            modelSelector.addItem(ModelItem("auto", "Auto (from Settings)", "Auto (from Settings)", "auto"))

                            modelSelector.selectedIndex = 0
                        }
                    } finally {
                        isUpdatingModelSelectorProgrammatically = false
                    }

                    Timer(1000) {
                        isInitializing = false
                        logger.info { "Initialization complete (fallback) - model changes will now sync to core" }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }
        }
    }

    /**
     * Load selected model from embedded core config and select it in dropdown
     */
    private fun loadDefaultModelFromCore() {
        cs.launch {
            try {
                logger.info { "Loading selected model from embedded core config..." }

                val selectedModelString = sessionManager.selectedModel.value
                val resolvedModelString = if (selectedModelString.isNullOrBlank() || selectedModelString == "auto") {
                    sessionManager.getDefaultModelForMode()
                } else {
                    selectedModelString
                }

                logger.info { "Core selected model: $resolvedModelString" }

                // Parse model string: "Ollama/qwen2.5:7b"
                val parts = resolvedModelString.split("/", limit = 2)
                val defaultModelId = if (parts.size == 2) parts[1] else resolvedModelString

                SwingUtilities.invokeLater {
                    isUpdatingModelSelectorProgrammatically = true
                    try {
                        var foundIndex = -1
                        for (i in 0 until modelSelector.itemCount) {
                            val item = modelSelector.getItemAt(i)
                            if (item.modelId == defaultModelId) {
                                foundIndex = i
                                break
                            }
                        }

                        if (foundIndex >= 0) {
                            modelSelector.selectedIndex = foundIndex
                            logger.info { "Selected model from core: $defaultModelId" }
                        } else {
                            logger.warn { "Model $defaultModelId from core not found in dropdown, adding manually" }
                            val displayName = resolvedModelString.replace("/", " / ")
                            val provider = if (parts.size == 2) parts[0].lowercase() else "ollama"
                            val fullDisplayName = getModelDisplayName(defaultModelId, provider)
                            modelSelector.addItem(
                                ModelItem(
                                    defaultModelId, displayName, fullDisplayName, provider
                                )
                            )
                            modelSelector.selectedIndex = modelSelector.itemCount - 1
                        }
                    } finally {
                        isUpdatingModelSelectorProgrammatically = false
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to load selected model from core, using first model" }
                SwingUtilities.invokeLater {
                    if (modelSelector.itemCount > 0) {
                        modelSelector.selectedIndex = 0
                    }
                }
            }
        }
    }

    /**
     * Update UI with session data
     */
    private fun updateSession(session: pl.jclab.refio.api.models.Session) {
        logger.info { "updateSession called: mode=${session.mode}, executionMode=${session.executionMode}" }

        val expectedModeIndex = when (session.mode) {
            TaskMode.CHAT -> 0
            TaskMode.PLAN -> 1
            TaskMode.AGENT -> 2
        }

        if (modeSelector.selectedIndex != expectedModeIndex) {
            isUpdatingModeSelectorProgrammatically = true
            modeSelector.selectedIndex = expectedModeIndex
            isUpdatingModeSelectorProgrammatically = false
        }
    }

    /**
     * Get current execution mode from active session (falls back to AUTO).
     * Managed via General Settings, not inline toggle.
     */
    fun getCurrentExecutionMode(): ExecutionMode {
        return sessionManager.activeSession.value?.executionMode ?: ExecutionMode.AUTO
    }

    /**
     * Get selected mode
     */
    fun getSelectedMode(): TaskMode {
        return when (modeSelector.selectedIndex) {
            0 -> TaskMode.CHAT
            1 -> TaskMode.PLAN
            2 -> TaskMode.AGENT
            else -> TaskMode.CHAT
        }
    }

    /**
     * Cycle to next mode: Chat -> Plan -> Agent -> Chat
     */
    fun cycleMode() {
        val currentIndex = modeSelector.selectedIndex
        val nextIndex = (currentIndex + 1) % modeSelector.itemCount
        modeSelector.selectedIndex = nextIndex
    }

    fun getPromptText(): String = promptEditor.text

    fun sendContinuePrompt() {
        sendPrompt(continuePromptText)
    }

    fun sendPrompt(prompt: String) {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isEmpty()) return

        SwingUtilities.invokeLater {
            if (isOperationRunning) {
                logger.warn { "Cannot send prompt while operation is running" }
                return@invokeLater
            }

            promptEditor.text = normalizedPrompt
            handleSendMessage()
        }
    }

    /**
     * Replace TPL variables in prompt text.
     * Supported placeholders:
     * - {{MODEL_ID}} -> provider_model (safe for filesystem)
     * - {{MODEL_RAW}} -> provider/model
     * - {{PROVIDER}} -> provider id (safe)
     * - {{MODE}} -> CHAT | PLAN | AGENT
     * - {{EXECUTION_MODE}} -> AUTO | INTERACTIVE
     * - {{TIMESTAMP}} -> yyyyMMdd_HHmmss
     * - {{DATE}} -> yyyy-MM-dd
     * - {{TIME}} -> HHmmss
     * - {{PROJECT_NAME}} -> IntelliJ project name (safe)
     * - {{SESSION_ID}} -> current session id (safe)
     */
    private fun applyPromptTemplateVariables(text: String): String {
        if (!text.contains("{{")) return text

        val now = LocalDateTime.now()
        val mode = getSelectedMode().name
        val executionMode = getCurrentExecutionMode().name
        val projectNameSafe = sanitizeFileNameToken(project.name)
        val sessionIdSafe = sanitizeFileNameToken(sessionManager.activeSession.value?.id ?: "no_session")

        val selectedItem = modelSelector.selectedItem as? ModelItem
        val modelRaw = when {
            selectedItem != null && !selectedItem.modelId.equals("auto", ignoreCase = true) ->
                "${selectedItem.provider}/${selectedItem.modelId}"
            else -> sessionManager.selectedModel.value.ifBlank { "auto" }
        }

        val providerRaw = modelRaw.substringBefore("/", missingDelimiterValue = "auto")
        val modelIdSafe = sanitizeFileNameToken(modelRaw.replace("/", "_"))
        val providerSafe = sanitizeFileNameToken(providerRaw)

        val variables = mapOf(
            "MODEL_ID" to modelIdSafe,
            "MODEL_RAW" to modelRaw,
            "PROVIDER" to providerSafe,
            "MODE" to mode,
            "EXECUTION_MODE" to executionMode,
            "TIMESTAMP" to now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")),
            "DATE" to LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            "TIME" to LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss")),
            "PROJECT_NAME" to projectNameSafe,
            "SESSION_ID" to sessionIdSafe
        )

        val placeholderRegex = Regex("""\{\{([A-Za-z0-9_]+)}}""")
        return placeholderRegex.replace(text) { match ->
            val key = match.groupValues[1].uppercase()
            variables[key] ?: match.value
        }
    }

    private fun sanitizeFileNameToken(input: String): String {
        val sanitized = input
            .trim()
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_', '.')

        return sanitized.ifBlank { "unknown" }
    }

    fun clearPrompt() {
        promptEditor.text = ""
        sessionManager.clearPendingUserInput()
    }

    /**
     * Refresh the model list from core
     * Call this after settings changes to update dropdown
     */
    fun refreshModels() {
        logger.info { "Refreshing model list from Settings" }
        loadAvailableModels()
    }

    private fun createPromptEditor(): EditorTextField {
        ensureEnterActionHandlerInstalled()
        return EditorTextField(project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setPlaceholder("Type a message... (@context, /prompt, !subagent)")
            font = LCATheme.editorFont
            preferredSize = Dimension(0, 90)
            minimumSize = Dimension(0, 70)

            addSettingsProvider { editor ->
                val editorEx = editor ?: return@addSettingsProvider

                editorEx.settings.isUseSoftWraps = true
                editorEx.settings.isLineNumbersShown = false
                editorEx.settings.isLineMarkerAreaShown = false
                editorEx.settings.isFoldingOutlineShown = false
                editorEx.settings.isCaretRowShown = false

                editorEx.scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                editorEx.scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

                editorEx.backgroundColor = LCATheme.editorBackground
                editorEx.setBorder(LCATheme.emptyBorder())

                editorEx.putUserData(RefioCompletionContributor.PROMPT_EDITOR_KEY, true)
                editorEx.putUserData(SEND_ON_ENTER_KEY) { handleSendMessage() }
                editorEx.putUserData(IS_AUTOCOMPLETE_VISIBLE_KEY) { isAnyAutocompleteVisible() }
                editorEx.putUserData(RefioCompletionContributor.ADD_CONTEXT_REFERENCE_KEY) { ref ->
                    insertContextReference(
                        ref
                    )
                }
                editorEx.putUserData(RefioCompletionContributor.REPLACE_CONTEXT_PREFIX_KEY) { displayName ->
                    replaceContextPrefixInInput(displayName)
                }

                contextAutocomplete.attach(editorEx)
                promptAutocomplete.attach(editorEx)

                installEditorKeyBindings(editorEx)
                updatePromptEditorHeight()
            }
        }
    }

    private fun ensureEnterActionHandlerInstalled() {
        if (!ENTER_HANDLER_INSTALLED.compareAndSet(false, true)) return

        val actionManager = EditorActionManager.getInstance()
        val original = actionManager.getActionHandler(IdeActions.ACTION_EDITOR_ENTER)

        actionManager.setActionHandler(IdeActions.ACTION_EDITOR_ENTER, object : EditorActionHandler() {
            override fun doExecute(
                editor: com.intellij.openapi.editor.Editor,
                caret: Caret?,
                dataContext: DataContext
            ) {
                if (editor.getUserData(RefioCompletionContributor.PROMPT_EDITOR_KEY) != true) {
                    original.execute(editor, caret, dataContext)
                    return
                }

                val isAutocompleteVisible = editor.getUserData(IS_AUTOCOMPLETE_VISIBLE_KEY)?.invoke() ?: false
                if (isAutocompleteVisible) {
                    original.execute(editor, caret, dataContext)
                    return
                }

                val send = editor.getUserData(SEND_ON_ENTER_KEY)
                if (send == null) {
                    original.execute(editor, caret, dataContext)
                    return
                }

                val keyEvent = com.intellij.ide.IdeEventQueue.getInstance().trueCurrentEvent as? KeyEvent
                    ?: java.awt.EventQueue.getCurrentEvent() as? KeyEvent

                // Only send on a plain Enter key event without modifiers; otherwise fall back to normal editor behavior
                if (keyEvent == null) {
                    // If we cannot detect the key (unlikely for actual keyboard events), default to send to preserve Enter behavior
                    send.invoke()
                    return
                }

                val hasModifiers = (keyEvent.modifiersEx and (
                        KeyEvent.SHIFT_DOWN_MASK or KeyEvent.CTRL_DOWN_MASK or KeyEvent.META_DOWN_MASK or KeyEvent.ALT_DOWN_MASK
                        )) != 0
                if (hasModifiers || keyEvent.keyCode != KeyEvent.VK_ENTER) {
                    original.execute(editor, caret, dataContext)
                    return
                }

                send.invoke()
            }
        })
    }

    private fun installEditorKeyBindings(editorEx: EditorEx) {
        if (editorEx.getUserData(KEY_LISTENERS_INSTALLED) == true) return
        editorEx.putUserData(KEY_LISTENERS_INSTALLED, true)

        val component = editorEx.contentComponent

        val insertNewlineAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (isAnyAutocompleteVisible()) return
                ApplicationManager.getApplication().runWriteAction {
                    EditorModificationUtil.insertStringAtCaret(editorEx, "\n", false, true)
                }
            }
        }

        insertNewlineAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                    null
                )
            ),
            component,
            editorShortcutsDisposable,
        )

        insertNewlineAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
                    null
                )
            ),
            component,
            editorShortcutsDisposable,
        )

        insertNewlineAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.META_DOWN_MASK),
                    null
                )
            ),
            component,
            editorShortcutsDisposable,
        )
    }

    private fun updatePromptEditorHeight() {
        SwingUtilities.invokeLater {
            val editor = promptEditor.editor as? EditorEx
            val minHeight = 70
            val maxHeight = ((parent?.height ?: height).takeIf { it > 0 } ?: 600) / 2

            val desiredHeight = when {
                editor != null -> {
                    val lineCount = editor.document.lineCount.coerceAtLeast(1)
                    val contentInsets = editor.contentComponent.insets
                    (lineCount * editor.lineHeight) + contentInsets.top + contentInsets.bottom + 12
                }

                else -> {
                    val lineCount = promptEditor.text.count { it == '\n' } + 1
                    val lineHeight = promptEditor.getFontMetrics(promptEditor.font).height
                    (lineCount * lineHeight) + 12
                }
            }

            val clamped = desiredHeight.coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))
            if (clamped == lastPreferredEditorHeight) return@invokeLater
            lastPreferredEditorHeight = clamped

            promptEditor.preferredSize = Dimension(0, clamped)
            promptEditor.minimumSize = Dimension(0, minHeight)
            inputContainer.revalidate()
            revalidate()
        }
    }

    private fun isAnyAutocompleteVisible(): Boolean {
        val editor = promptEditor.editor
        val isNativeLookupVisible = editor != null && LookupManager.getActiveLookup(editor) != null

        return isNativeLookupVisible ||
                (contextAutocomplete.isVisible()) ||
                (promptAutocomplete.isVisible())
    }

    private fun getPromptCaretOffset(): Int {
        return promptEditor.editor?.let { editor ->
            com.intellij.openapi.application.runReadAction { editor.caretModel.offset }
        } ?: promptEditor.text.length
    }

    private fun setPromptEditorEnabled(isEnabled: Boolean, background: Color) {
        promptEditor.isEnabled = isEnabled
        promptEditor.editor?.let { editor ->
            editor.contentComponent.isEnabled = isEnabled
            (editor as? EditorEx)?.backgroundColor = background
        }
    }

    private fun triggerNativeCompletionIfNeeded(event: DocumentEvent) {
        val inserted = event.newFragment.toString()
        if (inserted != "/" && inserted != "@" && inserted != "!") return

        val editor = promptEditor.editor ?: return
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
    }

    private fun onPromptInputChanged() {
        sessionManager.updatePendingUserInput(promptEditor.text)
        sessionManager.updatePendingContextRefs(
            mergeContextRefs(
                contextReferences,
                extractInlineProviderContextRefs(promptEditor.text, promptForMissingQuery = false)
            )
        )
        checkAutocomplete()
    }

    /**
     * Check if autocomplete should be shown
     * DEBOUNCED: Waits 150ms before executing to avoid overwhelming EDT
     * Uses Swing Timer with lazy initialization to prevent memory leaks
     *
     * FIX: Bug #5 - Reuses single Timer instance instead of creating new ones
     */
    private fun checkAutocomplete() {
        // Stop and restart the timer to debounce (reuses same Timer instance)
        autocompleteTimer.apply {
            stop()
            restart()
        }
    }

    /**
     * Perform autocomplete check (called after debounce delay)
     */
    private fun performAutocomplete() {
        if (promptEditor.editor == null) {
            return
        }

        // Read text (already on EDT since Timer callback is on EDT)
        val text = promptEditor.text
        val caretPos = getPromptCaretOffset()

        // Find trigger character before caret
        val beforeCaret = text.substring(0, caretPos)

        when {
            // @ autocomplete
            beforeCaret.contains("@") -> {
                val lastAt = beforeCaret.lastIndexOf('@')
                val prefix = beforeCaret.substring(lastAt)

                // Space after @ - SUBMENU provider pattern like "@file query"
                // Use native IntelliJ completion instead of custom SubmenuPopup
                if (' ' in prefix) {
                    contextAutocomplete.hide()

                    val spaceIndex = prefix.indexOf(' ')
                    val providerNameWithSpace = prefix.substring(1, spaceIndex).lowercase()

                    // If this is a SUBMENU provider, trigger native completion
                    val provider = ContextProviderRegistry.getProvider(providerNameWithSpace)
                    if (provider?.description?.type == ProviderType.SUBMENU) {
                        promptEditor.editor?.let { editor ->
                            // Close any active lookup first
                            if (LookupManager.getActiveLookup(editor) != null) {
                                LookupManager.getInstance(project).hideActiveLookup()
                            }

                            SwingUtilities.invokeLater {
                                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                            }
                        }
                    }
                    return
                }

                // Trigger native IntelliJ completion (RefioCompletionContributor)
                // Shows providers + file search fallback
                contextAutocomplete.hide()
                promptEditor.editor?.let { editor ->
                    // Close any active lookup first to ensure fresh autocomplete
                    if (LookupManager.getActiveLookup(editor) != null) {
                        LookupManager.getInstance(project).hideActiveLookup()
                    }

                    // Schedule autocomplete with slight delay to ensure lookup is closed
                    SwingUtilities.invokeLater {
                        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                    }
                }
            }

            // / autocomplete - only when "/" is the FIRST character in input
            beforeCaret.startsWith("/") && beforeCaret.all { it.isLetterOrDigit() || it == '/' } -> {
                // Use native IntelliJ completion (RefioCompletionContributor) instead of custom popup
                promptAutocomplete.hide()
            }

            else -> {
                contextAutocomplete.hide()
                promptAutocomplete.hide()
            }
        }
    }

//    private fun showDocsAutocomplete(prefix: String) {
//        autocompleteJob?.cancel()
//
//        autocompleteJob = cs.launch {
//            try {
//                kotlinx.coroutines.delay(100)
//                val filterPrefix = normalizeDocsFilter(prefix)
//                val items = withContext(Dispatchers.IO) {
//                    buildDocsAutocompleteItems(prefix)
//                }
//
//                SwingUtilities.invokeLater {
//                    if (items.isNotEmpty()) {
//                        // Use contextAutocomplete for consistent appearance
//                        contextAutocomplete.show(items, filterPrefix)
//                    } else {
//                        contextAutocomplete.hide()
//                    }
//                }
//            } catch (e: kotlinx.coroutines.CancellationException) {
//                // ignore
//            } catch (e: Exception) {
//                logger.error(e) { "Failed to build docs autocomplete" }
//            }
//        }
//    }

    /**
     * Build context autocomplete items
     *
     * Uses ContextProviderRegistry to dynamically load all available providers
     * including built-in providers and MCP servers.
     */
    private suspend fun buildContextAutocompleteItems(prefix: String): List<ContextAutocompleteItem> {
        val items = mutableListOf<ContextAutocompleteItem>()
        val cleanPrefix = prefix.removePrefix("@").lowercase()

        // Legacy plugin-specific context types (not in provider registry)
        // @selection (if editor has selection)
        if ("selection".startsWith(cleanPrefix)) {
            // Editor API requires read action
            ApplicationManager.getApplication().runReadAction {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                if (editor?.selectionModel?.hasSelection() == true) {
                    val selection = editor.selectionModel.selectedText ?: ""
                    val fileName = editor.virtualFile?.name ?: "unknown"
                    items.add(
                        ContextAutocompleteItem(
                            ContextReference.selection(selection, fileName),
                            iconValue = "✂️"
                        )
                    )
                }
            }
        }

        // @rules
        if ("rules".startsWith(cleanPrefix)) {
            items.add(ContextAutocompleteItem(ContextReference.rules(), iconValue = "📋"))
        }

        // Get all registered context providers
        val allProviders = ContextProviderRegistry.getAllProviders()

        // Filter providers matching the prefix
        val matchingProviders = allProviders.filter { provider ->
            val title = provider.description.title.lowercase()
            cleanPrefix.isEmpty() || title.startsWith(cleanPrefix)
        }

        // Create autocomplete items from matching providers
        matchingProviders.forEach { provider ->
            val desc = provider.description
            val providerIcon = desc.icon ?: ""

            // Create ContextReference based on provider type
            val contextRef = when (desc.type) {
                ProviderType.NORMAL -> {
                    // For NORMAL providers, create simple provider reference
                    // Examples: @open, @current, @clipboard, @diff, @problems
                    ContextReference.provider(
                        providerId = desc.title,
                        query = "",
                        displayName = desc.displayTitle
                    )
                }

                ProviderType.QUERY -> {
                    // For QUERY providers, show with colon to indicate query needed
                    // Examples: @grep:pattern, @codebase:query
                    // Note: This is just the autocomplete item, actual query will be typed by user
                    ContextReference.provider(
                        providerId = desc.title,
                        query = "",
                        displayName = "${desc.displayTitle} (type query)"
                    )
                }

                ProviderType.SUBMENU -> {
                    // For SUBMENU providers, mark for submenu loading
                    // Examples: @file, @recent (will show submenu popup)
                    ContextReference.provider(
                        providerId = desc.title,
                        query = "",
                        displayName = "${desc.displayTitle} ↓",
                        additionalMetadata = mapOf("needsSubmenu" to true)
                    )
                }
            }

            items.add(ContextAutocompleteItem(contextRef, iconValue = providerIcon))
        }

        // Fallback: if prefix doesn't match any provider, search files by name
        if (cleanPrefix.isNotEmpty() && matchingProviders.isEmpty()) {
            val fileItems = searchFilesForAutocomplete(cleanPrefix)
            items.addAll(fileItems)
        }

        // Sort items: use existing getSortKey() logic from ContextAutocompleteItem
        return items.sortedBy { it.getSortKey() }
    }

    /**
     * Search files by name pattern for autocomplete fallback.
     * Used when @prefix doesn't match any provider.
     */
    private fun searchFilesForAutocomplete(pattern: String): List<ContextAutocompleteItem> {
        val result = mutableListOf<ContextAutocompleteItem>()
        try {
            // FilenameIndex requires read action
            ApplicationManager.getApplication().runReadAction {
                val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)

                com.intellij.psi.search.FilenameIndex.processAllFileNames(
                    { fileName ->
                        if (fileName.contains(pattern, ignoreCase = true)) {
                            com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(fileName, scope)
                                .forEach { file ->
                                    if (result.size < 15) {
                                        val contextRef = ContextReference.provider(
                                            providerId = "file",
                                            query = file.path,
                                            displayName = file.name  // Use file name only (consistent with context menu)
                                        )
                                        result.add(ContextAutocompleteItem(contextRef, iconValue = "📄"))
                                    }
                                }
                        }
                        result.size < 15
                    },
                    scope,
                    null
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search files for autocomplete: $pattern" }
        }

        return result
    }

    /**
     * Load slash prompts from backend and cache them
     */
    private suspend fun loadSlashPrompts(): List<SlashPrompt> = withContext(Dispatchers.IO) {
        val fallback = SlashPrompt.BUILTINS

        return@withContext try {
            val client = coreApiClient ?: sessionManager.apiRouter
            val response = client.promptsRouter.getPromptsByType(pl.jclab.refio.core.db.PromptType.SLASH_PROMPT)

            val slashPrompts = response.prompts
                .filter { it.isEnabled }
                .map { prompt ->
                    SlashPrompt(
                        id = prompt.id,
                        name = prompt.name.removePrefix("/"),
                        description = prompt.description ?: "Custom prompt",
                        template = prompt.content,
                        variables = extractVariablesFromTemplate(prompt.content),
                        category = "custom",
                        isBuiltin = false
                    )
                }

            val resolved = if (slashPrompts.isEmpty()) {
                fallback
            } else {
                slashPrompts
            }

            cachedSlashPrompts = resolved
            logger.info { "Loaded ${slashPrompts.size} slash prompts from database (enabled only)" }
            resolved
        } catch (e: Exception) {
            logger.error(e) { "Failed to load slash prompts from database, using built-ins" }
            cachedSlashPrompts = fallback
            fallback
        }
    }

    /**
     * Extract template variables from a template string.
     * Variables are in the format {variable_name}
     */
    private fun extractVariablesFromTemplate(template: String): List<String> {
        val regex = Regex("""\{(\w+)\}""")
        return regex.findAll(template).map { it.groupValues[1] }.toList()
    }

    // Subagent completion for "!" is provided by IntelliJ completion (RefioCompletionContributor).

    /**
     * Insert context reference
     */
    private fun insertContextReference(ref: ContextReference) {
        logger.info { "[CONTEXT_DEBUG] insertContextReference called: type=${ref.type}, path=${ref.path}, displayName=${ref.displayName}" }

        // Validate context before adding
        val newList = contextReferences + ref
        val validationResult = ContextValidator.validateList(newList)

        if (!validationResult.isValid) {
            // Show error dialog
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(
                    this,
                    validationResult.errorMessage,
                    "Context Validation Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
            logger.warn { "Context validation failed: ${validationResult.errorMessage}" }
            return
        }

        // Show warnings if any
        if (validationResult.warnings.isNotEmpty()) {
            SwingUtilities.invokeLater {
                val warningMessage = validationResult.warnings.joinToString("\n")
                JOptionPane.showMessageDialog(
                    this,
                    warningMessage,
                    "Context Warning",
                    JOptionPane.WARNING_MESSAGE
                )
            }
        }

        // Add to context references FIRST
        contextReferences.add(ref)
        updateContextTags()

        // Update SessionManager for live preview in ContextPanel
        sessionManager.updatePendingContextRefs(contextReferences.toList())

        logger.info { "Added context reference: ${ref.type}:${ref.path}" }
    }

    /**
     * Handle context autocomplete item selection
     *
     * For SUBMENU providers: load and show submenu
     * For docs placeholder: show info message
     * For others: insert context reference directly
     */
    private fun handleContextSelection(item: ContextAutocompleteItem) {
        val contextRef = item.contextRef
        logger.info { "[CONTEXT_DEBUG] handleContextSelection: type=${contextRef.type}, providerId=${contextRef.metadata["providerId"]}, displayName=${contextRef.displayName}" }

        // Check if this is a docs placeholder
        val providerId = contextRef.metadata["providerId"] as? String
        if (providerId == "docs" && contextRef.path == docsPlaceholderId) {
            SwingUtilities.invokeLater {
                Messages.showInfoMessage(
                    this,
                    "Brak zindeksowanej dokumentacji dla tego projektu.\nDodaj URL lub plik lokalny w Settings → Refio → Documentation, a następnie uruchom indeksowanie.",
                    "Documentation"
                )
            }
            return
        }

        // Check if this provider needs a submenu
        val needsSubmenu = contextRef.metadata["needsSubmenu"] as? Boolean ?: false

        if (needsSubmenu) {
            val submenuProviderId = contextRef.metadata["providerId"] as? String ?: return

            // Load and show submenu for this provider
            // Store providerId for later use in handleSubmenuSelection
            currentSubmenuProviderId = submenuProviderId

            // Hide context autocomplete before showing submenu
            contextAutocomplete.hide()
        } else {
            // No submenu needed, insert directly
            insertContextReference(contextRef)
            // Replace @... prefix with full display name in input
            replaceContextPrefixInInput(contextRef.displayName)
        }
    }

    /**
     * Handle submenu item selection
     *
     * Creates a final ContextReference from the submenu item and inserts it
     */
    private fun handleSubmenuSelection(item: ContextSubmenuItem) {
        // Use stored provider ID from handleContextSelection
        val providerId = currentSubmenuProviderId
        if (providerId == null) {
            logger.error { "No provider ID stored for submenu selection" }
            return
        }

        logger.info { "Submenu item selected: ${item.title} (id=${item.id}) for provider: $providerId" }

        // Create final context reference using stored provider ID
        // item.id contains the path/value (e.g., "/home/user/project/src/main.kt")
        val contextRef = ContextReference.provider(
            providerId = providerId,
            query = item.id,  // Use full item.id as the query/path
            displayName = item.title,
            additionalMetadata = item.metadata
        )

        // Clear stored provider ID
        currentSubmenuProviderId = null

        insertContextReference(contextRef)
        // Replace @... prefix with full display name in input
        replaceContextPrefixInInput(contextRef.displayName)
    }


    /**
     * Insert slash prompt name (not template).
     * Template will be prepended when sending the message.
     */
    private fun insertSlashPrompt(slashPrompt: SlashPrompt) {
        // Replace typed prefix with full prompt name + space
        val promptText = "/${slashPrompt.name} "
        promptEditor.text = promptText
        promptEditor.editor?.caretModel?.moveToOffset(promptText.length)

        logger.info { "Inserted slash prompt: /${slashPrompt.name}" }
    }

    /**
     * Replace @... prefix with short name in input after selecting context item
     * Finds the last "@" before caret and replaces it with @shortName
     * Extracts short name from displayName (text before parentheses)
     */
    private fun replaceContextPrefixInInput(displayName: String) {
        val text = promptEditor.text
        val caretPos = getPromptCaretOffset()
        val beforeCaret = text.substring(0, caretPos)

        val lastAt = beforeCaret.lastIndexOf('@')
        if (lastAt < 0) return

        // Replace everything typed from the last '@' up to caret.
        // This intentionally clears provider filters like "@file snake34.html" so we don't end up with
        // "@snake34.html  snake34.html" after selecting from submenu.
        val afterCaret = text.substring(caretPos)

        // Extract short name (before parentheses) for clean display in input
        // e.g. "snake03.html (snake03.html)" -> "snake03.html"
        val shortName = displayName.substringBefore(" (").trim()

        // Keep exactly one separator: add trailing space only if needed.
        val shouldAppendSpace = afterCaret.isEmpty() || !afterCaret.first().isWhitespace()
        val replacement = buildString {
            append("@")
            append(shortName)
            if (shouldAppendSpace) append(" ")
        }

        val newText = text.substring(0, lastAt) + replacement + afterCaret
        val newCaretPos = lastAt + replacement.length

        promptEditor.text = newText
        promptEditor.editor?.caretModel?.moveToOffset(newCaretPos)

        logger.info { "Replaced context prefix with: $replacement" }
    }

    /**
     * Update context tags display
     */
    private fun updateContextTags() {
        contextTagsPanel.removeAll()

        contextReferences.forEach { ref ->
            val tag = createContextTag(ref)
            contextTagsPanel.add(tag)
        }

        contextTagsPanel.isVisible = contextReferences.isNotEmpty()
        contextTagsPanel.revalidate()
        contextTagsPanel.repaint()
    }

    /**
     * Create visual tag for context reference
     *
     * Shows provider icon, display name, and remove button
     */
    private fun createContextTag(ref: ContextReference): JComponent {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LCATheme.grayColor),
                LCATheme.paddedBorder(2, 6, 2, 6)
            )
            background = LCATheme.lightGrayColor

            // Get provider icon if this is a PROVIDER type
            val icon = if (ref.type == pl.jclab.refio.api.models.ContextType.PROVIDER) {
                val providerId = ref.metadata["providerId"] as? String
                providerId?.let {
                    ContextProviderRegistry.getProvider(it)?.description?.icon
                } ?: ""
            } else {
                // Default icons for legacy types
                when (ref.type) {
                    pl.jclab.refio.api.models.ContextType.FILE -> "📄"
                    pl.jclab.refio.api.models.ContextType.FOLDER -> "📁"
                    pl.jclab.refio.api.models.ContextType.SELECTION -> "✂️"
                    pl.jclab.refio.api.models.ContextType.OPEN -> "📂"
                    pl.jclab.refio.api.models.ContextType.DOCS -> "📚"
                    pl.jclab.refio.api.models.ContextType.RULES -> "📋"
                    else -> ""
                }
            }

            // Add icon label if icon exists
            if (icon.isNotEmpty()) {
                add(JLabel("$icon ").apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                })
            }

            // Add display name
            add(JLabel(ref.displayName).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
            })

            // Add remove button
            add(JButton("✕").apply {
                preferredSize = Dimension(16, 16)
                font = font.deriveFont(9f)
                border = LCATheme.emptyBorder()
                isContentAreaFilled = false

                addActionListener {
                    contextReferences.remove(ref)
                    updateContextTags()
                    // Update SessionManager for live preview in ContextPanel
                    sessionManager.updatePendingContextRefs(contextReferences.toList())
                }
            })
        }
    }

    /**
     * Clear context references
     */
    fun clearContext() {
        contextReferences.clear()
        updateContextTags()
        // Clear pending context refs in SessionManager for ContextPanel live preview
        sessionManager.clearPendingContextRefs()
    }

    /**
     * Get current context references
     */
    fun getContextReferences(): List<ContextReference> {
        return contextReferences.toList()
    }

    // ==================== CODE SNIPPETS METHODS ====================

    /**
     * Add a code snippet to the input panel.
     */
    fun addCodeSnippet(snippet: CodeSnippet) {
        logger.info { "Adding code snippet: ${snippet.displayName}" }
        snippetsContainer.addSnippet(snippet)
    }

    /**
     * Remove a code snippet by ID.
     */
    fun removeCodeSnippet(id: String) {
        logger.info { "Removing code snippet: $id" }
        snippetsContainer.removeSnippet(id)
    }

    /**
     * Clear all code snippets.
     */
    fun clearSnippets() {
        logger.info { "Clearing all code snippets" }
        snippetsContainer.clear()
    }

    /**
     * Get current code snippets.
     */
    fun getCodeSnippets(): List<CodeSnippet> {
        return snippetsContainer.getSnippets()
    }

    /**
     * Update UI based on operation state (running/idle)
     * Called when streaming starts/stops or any operation begins/ends
     */
    private fun updateOperationState(isRunning: Boolean) {
        if (isOperationRunning == isRunning) return

        isOperationRunning = isRunning
        inputBorderPanel.isLoading = isRunning

        if (isRunning) {
            // Operation started — prompt stays active for mid-execution input (Enter sends)
            sendButton.isVisible = false
            stopButton.isVisible = true

            // Keep prompt editor enabled for mid-execution messages
            setPromptEditorEnabled(true, LCATheme.editorBackground)

            // Disable mode/model selectors (can't change mid-execution)
            addContextButton.isEnabled = false
            modeSelector.isEnabled = false
            modelSelector.isEnabled = false

            logger.info { "Operation started - prompt stays active for mid-execution input" }
        } else {
            // Operation finished
            sendButton.text = "🚀 Send"
            sendButton.toolTipText = "Send prompt (Enter)"
            sendButton.isVisible = true
            stopButton.isVisible = false

            // Re-enable prompt input
            setPromptEditorEnabled(true, LCATheme.editorBackground)

            // Re-enable all controls
            addContextButton.isEnabled = true
            modeSelector.isEnabled = true
            modelSelector.isEnabled = true

            logger.info { "Operation finished - all controls enabled" }
        }

        revalidate()
        repaint()
    }

    /**
     * Handle Stop button click - cancel current operation
     * Works for all modes: CHAT (streaming), PLAN, AGENT
     */
    private fun handleStopOperation() {
        logger.info { "Stop button clicked - canceling operation" }

        // Set global cancellation flag FIRST (checked by adapters, executors, tools)
        globalMetrics.requestCancellation()

        cs.launch {
            try {
                val currentSession = sessionManager.activeSession.value

                if (currentSession != null) {
                    // Cancel streaming if in CHAT mode
                    sessionManager.cancelStreaming()

                    // Cancel orchestration/auto execution coroutine (if running)
                    sessionManager.cancelExecution()

                    // Stop step execution (cancels execution job and resets state)
                    stepExecutionService.stopExecution()
                    logger.info { "Step execution stopped" }

                    // Cancel task execution via embedded core (works for all modes)
                    try {
                        sessionManager.apiRouter.taskRouter.updateTask(
                            currentSession.id,
                            pl.jclab.refio.core.api.UpdateTaskRequest(
                                status = pl.jclab.refio.core.db.TaskStatus.CANCELED
                            )
                        )
                        logger.info { "Task execution canceled: ${currentSession.id}" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to cancel task via core (may already be complete)" }
                    }
                }

                logger.info { "Operation canceled successfully" }
                // UI will update automatically via globalMetrics.currentOperation listener
            } catch (e: Exception) {
                logger.error(e) { "Failed to cancel operation" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@PromptInputPanel,
                        "Failed to cancel operation: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    /**
     * Show context menu with available providers and recently added files
     */
    private fun showContextMenu() {
        val popup = JPopupMenu()

        // 1. Add "Available Providers" section at the top
        popup.add(JLabel("Available Providers:")).apply {
            isEnabled = false
            font = font.deriveFont(font.style or Font.BOLD)
        }
        popup.addSeparator()

        // Add @selection if there's a selection
        try {
            ApplicationManager.getApplication().runReadAction {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                if (editor?.selectionModel?.hasSelection() == true) {
                    val selection = editor.selectionModel.selectedText ?: ""
                    val fileName = editor.virtualFile?.name ?: "unknown"
                    val menuItem = JMenuItem("✂️ @selection - Selected text from $fileName")
                    menuItem.addActionListener {
                        insertContextReference(ContextReference.selection(selection, fileName))
                    }
                    popup.add(menuItem)
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not check for selection" }
        }

        // Add @rules
        val rulesItem = JMenuItem("📋 @rules - Project rules")
        rulesItem.addActionListener {
            insertContextReference(ContextReference.rules())
        }
        popup.add(rulesItem)

        // Add all registered context providers (excluding @recent since we show recently edited files separately)
        val allProviders = ContextProviderRegistry.getAllProviders()
        allProviders.sortedBy { it.description.title }
            .forEach { provider ->
                val desc = provider.description
                val icon = desc.icon?.ifEmpty { "" }
                val menuItem = JMenuItem("$icon @${desc.title} - ${desc.displayTitle}")

                menuItem.addActionListener {
                    when (desc.type) {
                        ProviderType.NORMAL -> {
                            // For NORMAL providers, add directly to context
                            val contextRef = ContextReference.provider(
                                providerId = desc.title,
                                query = "",
                                displayName = desc.displayTitle
                            )
                            insertContextReference(contextRef)
                        }

                        ProviderType.QUERY -> {
                            // For QUERY providers, show input dialog
                            val query = JOptionPane.showInputDialog(
                                this@PromptInputPanel,
                                "Enter query for ${desc.displayTitle}:",
                                desc.title,
                                JOptionPane.PLAIN_MESSAGE
                            )
                            if (query != null && query.isNotEmpty()) {
                                val contextRef = ContextReference.provider(
                                    providerId = desc.title,
                                    query = query,
                                    displayName = "${desc.displayTitle}: $query"
                                )
                                insertContextReference(contextRef)
                            }
                        }

                        ProviderType.SUBMENU -> {
                            // Insert provider into input and trigger native completion
                            // This provides consistent UX - all providers use native IntelliJ completion
                            SwingUtilities.invokeLater {
                                val currentText = promptEditor.text
                                val newText = if (currentText.isEmpty() || currentText.endsWith(" ")) {
                                    "${currentText}@${desc.title} "
                                } else {
                                    "${currentText} @${desc.title} "
                                }
                                promptEditor.text = newText
                                promptEditor.editor?.let { editor ->
                                    editor.caretModel.moveToOffset(newText.length)
                                    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                                }
                                promptEditor.requestFocus()
                            }
                        }
                    }
                }
                popup.add(menuItem)
            }

        popup.addSeparator()

        // 2. Get recently added files from context references
        val recentFiles = contextReferences.takeLast(5) // Show last 5 added files

        if (recentFiles.isNotEmpty()) {
            popup.add(JLabel("Recently added context:")).apply {
                isEnabled = false
            }
            popup.addSeparator()

            recentFiles.forEach { ref ->
                val menuItem = JMenuItem(ref.displayName)
                menuItem.addActionListener {
                    insertContextReference(ref)
                }
                popup.add(menuItem)
            }

            popup.addSeparator()
        }

        // Add recently opened files from IntelliJ history (last 10 edited files)
        try {
            val editorHistoryManager = com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project)
            // Reverse the list to get most recently opened files first, filter out invalid files
            val recentlyOpenedFiles = editorHistoryManager.fileList
                .asReversed()
                .filter { it.isValid && !it.isDirectory }
                .take(10)

            if (recentlyOpenedFiles.isNotEmpty()) {
                popup.add(JLabel("Recently edited files:")).apply {
                    isEnabled = false
                    font = font.deriveFont(font.style or Font.BOLD)
                }
                popup.addSeparator()

                recentlyOpenedFiles.forEach { vFile ->
                    val menuItem = JMenuItem(vFile.name)
                    menuItem.toolTipText = vFile.path
                    menuItem.addActionListener {
                        val ref = ContextReference.file(vFile.path, vFile.name)
                        insertContextReference(ref)
                    }
                    popup.add(menuItem)
                }

                popup.addSeparator()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not get recently opened files" }
        }

        // Add "Add current file" option if there's an active editor
        try {
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val selectedFiles = fileEditorManager.selectedFiles
            if (selectedFiles.isNotEmpty()) {
                val currentFile = selectedFiles[0]
                val addCurrentItem = JMenuItem("Add current file: ${currentFile.name}")
                addCurrentItem.addActionListener {
                    val ref = ContextReference.file(currentFile.path, currentFile.name)
                    insertContextReference(ref)
                }
                popup.add(addCurrentItem)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not get current file" }
        }

        // Show popup menu
        val addContextButton = this.components.find { it is JButton && (it).text == "+" } as? JButton
        addContextButton?.let { button ->
            // Calculate popup preferred size
            val popupSize = popup.preferredSize

            // Get button location on screen
            val buttonLocation = button.locationOnScreen
            val screenSize = Toolkit.getDefaultToolkit().screenSize

            // Calculate available space below and above the button
            val spaceBelow = screenSize.height - buttonLocation.y - button.height
            val spaceAbove = buttonLocation.y

            // Show popup above if there's not enough space below
            val yOffset = if (spaceBelow < popupSize.height && spaceAbove > popupSize.height) {
                -popupSize.height
            } else {
                button.height
            }

            popup.show(button, 0, yOffset)
        }
    }

    /**
     * Show more options menu with toggle buttons
     */
    fun dispose() {
        // Timer is lazy-initialized, so we can safely stop it (it will be created if not already)
        autocompleteTimer.stop()
        Disposer.dispose(editorShortcutsDisposable)
        cs.cancel()
    }

    private fun extractInlineProviderContextRefs(
        text: String,
        promptForMissingQuery: Boolean
    ): List<ContextReference> {
        val refs = mutableListOf<ContextReference>()
        val pattern = Regex("""(?<!\w)@([a-zA-Z0-9_]+)(?::([^\n@]+))?""")

        pattern.findAll(text).forEach { match ->
            val providerId = match.groupValues[1].lowercase()
            val rawQuery = match.groupValues.getOrNull(2)?.trim().orEmpty()

            when (providerId) {
                "open", "open_files", "open_file" -> {
                    refs.add(ContextReference.openFiles())
                    return@forEach
                }
                "rules" -> {
                    refs.add(ContextReference.rules())
                    return@forEach
                }
                "selection" -> {
                    extractSelectionContextRef()?.let { refs.add(it) }
                    return@forEach
                }
            }

            val provider = ContextProviderRegistry.getProvider(providerId) ?: return@forEach
            val desc = provider.description

            var query = rawQuery
            if (desc.type == ProviderType.QUERY && query.isBlank()) {
                if (!promptForMissingQuery) {
                    return@forEach
                }
                val input = JOptionPane.showInputDialog(
                    this@PromptInputPanel,
                    "Enter query for ${desc.displayTitle}:",
                    desc.title,
                    JOptionPane.PLAIN_MESSAGE
                )
                if (input.isNullOrBlank()) {
                    return@forEach
                }
                query = input.trim()
            }

            val displayName = if (query.isNotBlank()) {
                "${desc.displayTitle}: $query"
            } else {
                desc.displayTitle
            }

            refs.add(
                ContextReference.provider(
                    providerId = desc.title,
                    query = query,
                    displayName = displayName
                )
            )
        }

        return refs
    }

    private fun extractSelectionContextRef(): ContextReference? {
        return try {
            ApplicationManager.getApplication().runReadAction<ContextReference?> {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                val selection = editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() } ?: return@runReadAction null
                val fileName = editor.virtualFile?.name ?: "unknown"
                ContextReference.selection(selection, fileName)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read editor selection for @selection" }
            null
        }
    }

    private fun mergeContextRefs(
        base: List<ContextReference>,
        extras: List<ContextReference>
    ): List<ContextReference> {
        if (extras.isEmpty()) return base.toList()
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<ContextReference>()
        (base + extras).forEach { ref ->
            val providerId = ref.metadata["providerId"]?.toString() ?: ""
            val key = "${ref.type}:${ref.path}:$providerId"
            if (seen.add(key)) {
                merged.add(ref)
            }
        }
        return merged
    }
}

/**
 * Get human-readable display name for a model.
 * First tries to get the name from ModelDefinitions (provider + model name),
 * then falls back to dynamically generated display name.
 */
private fun getModelDisplayName(modelId: String, provider: String): String {
    // First, try to get the name from ModelDefinitions
    val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition(provider, modelId)
    if (definition != null) {
        // Use the provider name from definition + model name for consistent formatting
        val providerName = definition.provider.replaceFirstChar { it.uppercase() }
        return "$providerName - ${definition.name}"
    }

    // Fallback: dynamically generate display name for unknown models
    return when {
        // OpenAI models
        modelId == "gpt-4o" -> "OpenAI - GPT-4o"
        modelId == "gpt-4o-mini" -> "OpenAI - GPT-4o Mini"
        modelId == "gpt-4-turbo" -> "OpenAI - GPT-4 Turbo"
        modelId == "gpt-3.5-turbo" -> "OpenAI - GPT-3.5 Turbo"
        modelId.startsWith("o1") -> "OpenAI - $modelId"

        // Anthropic models
        modelId == "claude-sonnet-4-5" -> "Anthropic - Claude Sonnet 4.5"
        modelId == "claude-opus-4-5" -> "Anthropic - Claude Opus 4.5"
        modelId == "claude-sonnet-4-20250514" -> "Anthropic - Claude Sonnet 4 (20250514)"
        modelId == "claude-3-5-sonnet-20241022" -> "Anthropic - Claude 3.5 Sonnet"
        modelId == "claude-3-5-haiku" -> "Anthropic - Claude 3.5 Haiku"
        modelId.startsWith("claude-") -> "Anthropic - ${modelId.replace("-", " ").replaceFirstChar { it.uppercase() }}"

        // Google models
        modelId.startsWith("gemini") -> "Google - ${modelId.replaceFirstChar { it.uppercase() }}"

        // For other models, use a cleaned-up version of the ID
        else -> {
            // Remove version tags and common suffixes, then capitalize
            val cleaned = modelId
                .replace(Regex("-v[0-9.]+"), "") // Remove -v1.0
                .replace("-", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

            // Add provider prefix for clarity
            val providerName = provider.replaceFirstChar { it.uppercase() }
            "$providerName - $cleaned"
        }
    }
}

/**
 * Model item for dropdown
 */
data class ModelItem(
    val modelId: String, val displayName: String, val fullDisplayName: String, val provider: String
) {
    override fun toString(): String = displayName
}
