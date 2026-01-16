package pl.jclab.refio.ui.components.chat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.UserContextMetadata
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.chat.MetricsView
import pl.jclab.refio.ui.components.common.PromptDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet
import kotlin.math.min

/**
 * Custom JLabel with rounded corners for mode badge
 * Matching landing page design (.tool-banner style)
 */
private class RoundedModeBadge : JLabel() {
    init {
        horizontalAlignment = SwingConstants.CENTER
        border = LCATheme.paddedBorder(8, 14)
        font = font.deriveFont(Font.BOLD, 11f)
        isOpaque = false // Let paintComponent handle background
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Rounded rectangle with 8px radius (matching landing page)
        val radius = 8.0
        val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), radius, radius)

        // Background
        g2.color = background
        g2.fill(shape)

        // Border (subtle)
        g2.color = LCATheme.subtleSeparatorColor
        g2.stroke = BasicStroke(1f)
        g2.draw(shape)

        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * Chat view - only displays messages (no prompt input)
 */
class ChatView(private val project: Project) : JBPanel<ChatView>(BorderLayout()) {

    private val DEFAULT_SPACE = LCATheme.paddedBorder(4)
    private val SCROLL_BAR_AND_PADDING = 0
    private val MAX_BUUBLE = 24
    private val MAX_SUMMARY_FILES = 10

    /**
     * Flat message block panel (no rounded corners, matching landing page design)
     * Used for .tool-card and .tool-message style blocks
     */
    private class FlatMessageBlock(private val backgroundColor: Color) : JBPanel<FlatMessageBlock>() {

        init {
            background = backgroundColor
            isOpaque = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color(255, 255, 255, 10)),
                LCATheme.paddedBorder(6,2)
            )
        }
    }

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val globalMetrics = GlobalMetrics
    private val logger = dualLogger("ChatView")
    private val coreManager = pl.jclab.refio.services.core.CoreConnectionManager.getInstance()

    // Format markdown setting (loaded from config)
    @Volatile
    private var formatMarkdownEnabled: Boolean = true

    // Markdown parser and renderer
    private val markdownParser = Parser.builder().build()
    private val htmlRenderer = HtmlRenderer.builder().escapeHtml(true).build()

    private val messagesPanel: JPanel

    /** Mode badge - exposed for parent panel to place outside scroll */
    val modeBadge: JLabel

    // Width for bubble calculations (updated on resize)
    private var availableWidth: Int = 400

    private val busyIndicatorPanel: JPanel
    private val busyIndicatorLabel: JLabel
    private val busyIndicatorFrames = arrayOf("|", " ")
    private var busyIndicatorFrame = 0
    private var busyIndicatorTimer: Timer? = null

    // Cache for rendered message panels to avoid recreating on every StateFlow update
    // Key: messageId, Value: CachedMessagePanel with contentHash and rendered panel
    private data class CachedMessagePanel(
        val contentHash: Int,  // Hash of content + isStreaming to detect changes
        val panel: JPanel
    )

    private val messagePanelCache = mutableMapOf<String, CachedMessagePanel>()
    private var lastRenderedMessageIds = emptyList<String>()
    private var lastReceivedMessages = emptyList<Message>()
    private var pendingMessages: List<Message>? = null
    private var updateMessagesTimer: Timer? = null
    private val uiUpdateDebounceMs = 200
    private var lastUiFlushAtMs = 0L

    init {
        // Mode badge - created here but NOT added to this panel
        // Parent panel should place it outside the scroll area
        // Using RoundedModeBadge for landing page style
        modeBadge = RoundedModeBadge()

        // Messages panel with GridBagLayout for better control
        // Note: No scroll here - parent handles scrolling
        // No border/padding - messages go edge-to-edge like landing page
        messagesPanel = JPanel(GridBagLayout()).apply {
            background = LCATheme.backgroundColor
            border = null
        }

        add(messagesPanel, BorderLayout.CENTER)

        busyIndicatorLabel = JLabel("Working ${busyIndicatorFrames[0]}").apply {
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = LCATheme.descriptionForeground
        }
        busyIndicatorPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 8, 2)
            border = LCATheme.paddedBorder(4, 8)
            isOpaque = false
            isVisible = false
            add(busyIndicatorLabel)
        }
        add(busyIndicatorPanel, BorderLayout.SOUTH)

        // Observe active session for mode changes
        cs.launch {
            sessionManager.activeSession.collect { session ->
                logger.info { "Received session update: mode=${session?.mode}" }
                updateModeBadge(session?.mode, session?.executionMode)
            }
        }

        cs.launch {
            combine(
                globalMetrics.currentOperation,
                stepExecutionService.isExecuting,
                sessionManager.userInteraction.isWaitingForResponse
            ) { operation, isStepExecuting, isWaitingForInput ->
                if (isWaitingForInput) {
                    false
                } else {
                    (operation !is OperationInfo.Idle) || isStepExecuting
                }
            }.collect { isRunning ->
                SwingUtilities.invokeLater {
                    updateBusyIndicator(isRunning)
                }
            }
        }

        // Show empty state
        showEmptyState()

        // Load format markdown setting from config
        loadFormatMarkdownSetting()

        // Listen for panel resize to recalculate bubble widths
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val newWidth = width
                if (newWidth > 0 && newWidth != availableWidth) {
                    availableWidth = newWidth
                    // Clear cache on resize - panels need new width
                    disposeMessagePanels(messagePanelCache.values.map { it.panel })
                    messagePanelCache.clear()
                    lastRenderedMessageIds = emptyList()
                    // Recreate messages with new width
                    val messages = sessionManager.messages.value
                    if (messages.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            updateMessages(messages)
                        }
                    }
                }
            }
        })

        // Observe messages
        cs.launch {
            sessionManager.messages.collect { messages ->
                scheduleMessagesUpdate(messages)
            }
        }
    }

    fun updateModeBadge(
        mode: TaskMode?,
        executionMode: ExecutionMode?,
        thinkingEnabled: Boolean = false,
        noEgressEnabled: Boolean = false,
        orchestrationEnabled: Boolean = true,
        intentClassificationEnabled: Boolean = false
    ) {
        logger.info {
            "Updating mode badge to: $mode, execution: $executionMode, thinking: $thinkingEnabled, " +
                "noEgress: $noEgressEnabled, orchestration: $orchestrationEnabled, " +
                "intentClassification: $intentClassificationEnabled"
        }
        SwingUtilities.invokeLater {
            logger.info { "Executing badge update on EDT" }

            val togglesText = buildString {
                if (orchestrationEnabled) append(" | Orchestration")
                if (thinkingEnabled) append(" | Thinking")
                if (noEgressEnabled) append(" | No egress️")
                if (intentClassificationEnabled) append(" | Intent classification")
            }

            when (mode) {
                TaskMode.CHAT -> {
                    modeBadge.text = "Chat | ${executionMode?.name?.lowercase() ?: ""}$togglesText"
                    modeBadge.background = LCATheme.chatModeBadgeBackground
                    modeBadge.foreground = LCATheme.chatModeBadgeForeground
                }

                TaskMode.PLAN -> {
                    modeBadge.text = "Plan (Read-Only) | ${executionMode?.name?.lowercase() ?: ""}$togglesText"
                    modeBadge.background = LCATheme.planModeBadgeBackground
                    modeBadge.foreground = LCATheme.planModeBadgeForeground
                }

                TaskMode.AGENT -> {
                    modeBadge.text = "Agent (Read/Write) | ${executionMode?.name?.lowercase() ?: ""}$togglesText"
                    modeBadge.background = LCATheme.agentModeBadgeBackground
                    modeBadge.foreground = LCATheme.agentModeBadgeForeground
                }

                null -> {
                    modeBadge.text = ""
                    modeBadge.background = LCATheme.backgroundColor
                }
            }
            logger.info { "Badge updated: text='${modeBadge.text}', visible=${modeBadge.isVisible}" }
            modeBadge.revalidate()
            modeBadge.repaint()
        }
    }

    private fun showEmptyState() {
        messagesPanel.removeAll()
        messagesPanel.layout = GridBagLayout()

        // Minimal empty state - no content, just placeholder for mode badge
        // This allows PromptInputPanel to stay at top in ReverseChatPanel
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    private fun updateBusyIndicator(isRunning: Boolean) {
        if (isRunning) {
            if (!busyIndicatorPanel.isVisible) {
                busyIndicatorPanel.isVisible = true
            }
            if (busyIndicatorTimer == null) {
                busyIndicatorTimer = Timer(450) {
                    busyIndicatorFrame = (busyIndicatorFrame + 1) % busyIndicatorFrames.size
                    busyIndicatorLabel.text = "Working ${busyIndicatorFrames[busyIndicatorFrame]}"
                }.apply {
                    isRepeats = true
                    start()
                }
            }
        } else {
            busyIndicatorTimer?.stop()
            busyIndicatorTimer = null
            busyIndicatorFrame = 0
            busyIndicatorLabel.text = "Working ${busyIndicatorFrames[0]}"
            busyIndicatorPanel.isVisible = false
        }

        busyIndicatorPanel.revalidate()
        busyIndicatorPanel.repaint()
    }

    /**
     * Calculate content hash for a message to detect changes.
     * Includes content, isStreaming state, lastChunkAt, and metadata.
     */
    private fun calculateMessageHash(message: Message): Int {
        return (message.content.hashCode() * 31 +
                message.isStreaming.hashCode() * 17 +
                (message.lastChunkAt ?: 0L).hashCode() * 13 +
                (message.metadata?.hashCode() ?: 0))
    }

    private fun updateMessages(messages: List<Message>) {
        SwingUtilities.invokeLater {
            // Deduplicate messages by ID before rendering
            val uniqueMessages = messages.distinctBy { it.id }
            val currentMessageIds = uniqueMessages.map { it.id }

            // Fast path: check if anything changed
            val hasStructuralChange = currentMessageIds != lastRenderedMessageIds
            val hasContentChange = uniqueMessages.any { msg ->
                val cached = messagePanelCache[msg.id]
                cached == null || cached.contentHash != calculateMessageHash(msg)
            }

            if (!hasStructuralChange && !hasContentChange) {
                // Nothing changed - skip expensive rebuild
                return@invokeLater
            }

            // Clear panel for rebuild
            messagesPanel.removeAll()
            messagesPanel.layout = GridBagLayout()

            if (uniqueMessages.isEmpty()) {
                disposeMessagePanels(messagePanelCache.values.map { it.panel })
                messagePanelCache.clear()
                lastRenderedMessageIds = emptyList()
                showEmptyState()
                // Notify parent about update
                firePropertyChange("messagesUpdated", false, true)
                return@invokeLater
            }

            val gbc = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                // No bottom insets - messages stack directly (like landing page)
                insets = Insets(0, 0, 0, 0)
            }

            // Add each message - using cache where possible
            uniqueMessages.forEachIndexed { index, message ->
                gbc.gridy = index
                gbc.weighty = 0.0

                val contentHash = calculateMessageHash(message)
                val cached = messagePanelCache[message.id]

                val bubble = if (cached != null && cached.contentHash == contentHash) {
                    // Reuse cached panel - no expensive markdown parsing needed
                    cached.panel
                } else {
                    // Create new panel (expensive - markdown parsing)
                    val newPanel = createMessageBubble(message)
                    messagePanelCache[message.id] = CachedMessagePanel(contentHash, newPanel)
                    newPanel
                }

                messagesPanel.add(bubble, gbc)
            }

            // Clean up cache - remove entries for deleted messages
            val currentIds = uniqueMessages.map { it.id }.toSet()
            val removedPanels = messagePanelCache
                .filterKeys { it !in currentIds }
                .values
                .map { it.panel }
            if (removedPanels.isNotEmpty()) {
                disposeMessagePanels(removedPanels)
            }
            messagePanelCache.keys.removeAll { it !in currentIds }
            lastRenderedMessageIds = currentMessageIds

            // Add conversation actions toolbar
            gbc.gridy = uniqueMessages.size
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsTop(8)
            messagesPanel.add(createConversationToolbar(), gbc)

            // Add vertical glue to push messages to top (weighty = 1.0 absorbs extra space)
            gbc.gridy = uniqueMessages.size + 1
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = Insets(0, 0, 0, 0)
            messagesPanel.add(Box.createVerticalGlue(), gbc)

            messagesPanel.revalidate()
            messagesPanel.repaint()

            // Notify parent (ReverseChatPanel) about update for scroll adjustment
            firePropertyChange("messagesUpdated", false, true)
        }
    }

    private fun scheduleMessagesUpdate(messages: List<Message>) {
        val previous = lastReceivedMessages
        lastReceivedMessages = messages
        pendingMessages = messages

        val now = System.currentTimeMillis()
        val flushNow = shouldFlushImmediately(previous, messages)
        if (flushNow) {
            lastUiFlushAtMs = now
            updateMessagesTimer?.stop()
            updateMessagesTimer = null
            updateMessages(messages)
            return
        }

        if (messages.any { it.isStreaming } && now - lastUiFlushAtMs >= uiUpdateDebounceMs) {
            lastUiFlushAtMs = now
            updateMessagesTimer?.stop()
            updateMessagesTimer = null
            updateMessages(messages)
            return
        }

        val timer = updateMessagesTimer ?: Timer(uiUpdateDebounceMs) {
            val pending = pendingMessages ?: return@Timer
            updateMessages(pending)
            lastUiFlushAtMs = System.currentTimeMillis()
        }.also { newTimer ->
            newTimer.isRepeats = false
            updateMessagesTimer = newTimer
        }

        timer.restart()
    }

    private fun shouldFlushImmediately(previous: List<Message>, current: List<Message>): Boolean {
        if (previous.isEmpty()) return true
        if (current.none { it.isStreaming }) return true

        val prevById = previous.associateBy { it.id }
        return current.any { msg ->
            val prev = prevById[msg.id] ?: return@any false
            prev.isStreaming && !msg.isStreaming
        }
    }

    private fun createMessageBubble(message: Message): JPanel {
        return when (message.role) {
            "user" -> createUserBubble(message)
            "assistant" -> {
                val executionSummaryMetadata = extractExecutionSummaryMetadata(message)
                if (executionSummaryMetadata != null) {
                    return createExecutionSummaryBubble(message, executionSummaryMetadata)
                }

                // Check if this message is an orchestrator question (higher priority)
                val questionData = extractQuestionData(message)
                if (questionData != null) {
                    return createQuestionBubble(message, questionData)
                }

                // Check if this message is an approval request (contains subtask ID pattern)
                // Show approval buttons only in INTERACTIVE mode
                val subtaskId = extractSubtaskId(message.content)
                val currentSession = sessionManager.activeSession.value
                val isInteractiveMode =
                    currentSession?.executionMode == pl.jclab.refio.api.models.ExecutionMode.INTERACTIVE

                if (subtaskId != null && isInteractiveMode) {
                    createApprovalBubble(message, subtaskId)
                } else {
                    createAssistantBubble(message)
                }
            }

            else -> {
                val summaryMetadata = extractConversationSummaryMetadata(message)
                if (summaryMetadata != null) {
                    createConversationSummaryBubble(message, summaryMetadata)
                } else {
                    createSystemBubble(message)
                }
            }
        }
    }

    private fun extractSubtaskId(content: String): String? {
        // Extract subtask ID from content like "**Subtask ID:** `abc-123-def`"
        val regex = Regex("\\*\\*Subtask ID:\\*\\*\\s*`([^`]+)`")
        val match = regex.find(content)
        val subtaskId = match?.groupValues?.get(1)

        if (subtaskId != null) {
            logger.debug { "Extracted subtask ID: $subtaskId from message" }
        } else {
            logger.debug { "No subtask ID found in message content" }
        }

        return subtaskId
    }

    /**
     * Extract question data from orchestrator question message.
     * Returns QuestionData if message contains orchestrator question, null otherwise.
     */
    private fun extractQuestionData(message: Message): QuestionData? {
        // Parse metadata to check if this is an orchestrator question
        if (message.metadata == null) return null

        try {
            val metadata = com.google.gson.Gson().fromJson(
                message.metadata, com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val type = metadata["type"] as? String
            if (type != "orchestrator_question") return null

            val questionId = metadata["question_id"] as? String ?: return null
            val awaitingResponse = metadata["awaiting_response"] as? Boolean ?: false

            // Only show buttons if awaiting response
            if (!awaitingResponse) return null

            // Parse options (list of strings)
            val options = (metadata["options"] as? List<*>)?.mapNotNull { it as? String }

            logger.info { "Extracted question data: questionId=$questionId, options=$options" }

            return QuestionData(questionId, options ?: emptyList())

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse question metadata" }
            return null
        }
    }

    /**
     * Data class for question information
     */
    private data class QuestionData(
        val questionId: String, val options: List<String>
    )

    /**
     * Extract code changes metadata from message.
     * Returns CodeChangesData if message contains code changes info, null otherwise.
     */
    private fun extractCodeChanges(message: Message): CodeChangesData? {
        logger.debug { "[EXTRACT] Extracting code changes from message ${message.id}" }

        val metadata = message.metadata ?: run {
            logger.debug { "[EXTRACT] No metadata in message ${message.id}" }
            return null
        }
        logger.debug { "[EXTRACT] Raw metadata: $metadata" }

        try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata, com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: run {
                logger.warn { "[EXTRACT] Failed to parse metadata JSON: $metadata" }
                return null
            }
            logger.debug { "[EXTRACT] Parsed metadata map: $metadataMap" }

            val type = metadataMap["type"] as? String
            logger.debug { "[EXTRACT] Metadata type: $type" }
            if (type != "code_changes") {
                logger.info { "[EXTRACT] Type is not 'code_changes', skipping" }
                return null
            }

            val filePath = metadataMap["file_path"] as? String ?: run {
                logger.warn { "[EXTRACT] Missing file_path in code_changes metadata" }
                return null
            }
            val addedLines = (metadataMap["added_lines"] as? Number)?.toInt() ?: 0
            val removedLines = (metadataMap["removed_lines"] as? Number)?.toInt() ?: 0
            val snapshotId = metadataMap["snapshot_id"] as? String

            logger.info { "[EXTRACT] Extracted code changes: path=$filePath, +$addedLines -$removedLines, snapshot=$snapshotId" }

            return CodeChangesData(filePath, addedLines, removedLines, snapshotId)

        } catch (e: Exception) {
            logger.error(e) { "[EXTRACT] Failed to parse code changes metadata: ${message.metadata}" }
            return null
        }
    }

    private fun extractExecutionSummaryMetadata(message: Message): ExecutionSummaryMetadata? {
        val metadata = message.metadata ?: return null
        return try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val type = metadataMap["type"] as? String ?: return null
            if (type != "execution_summary") {
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val filesRaw = metadataMap["changed_files"] as? List<Any>
            val changedFiles = filesRaw
                ?.mapNotNull { entry -> parseChangedFileEntry(entry) }
                ?: emptyList()

            val statsMap = metadataMap["stats"] as? Map<*, *>
            val stats = statsMap?.let {
                ExecutionSummaryStats(
                    totalSteps = it["total_steps"].toSafeInt(defaultValue = 0),
                    completedSteps = it["completed_steps"].toSafeInt(defaultValue = 0),
                    failedSteps = it["failed_steps"].toSafeInt(defaultValue = 0),
                    totalTokens = it["total_tokens"].toSafeInt(defaultValue = 0),
                    totalCostUsd = it["total_cost_usd"].toSafeDouble(defaultValue = 0.0)
                )
            }

            ExecutionSummaryMetadata(
                changedFiles = changedFiles,
                stats = stats,
                generatedAt = (metadataMap["generated_at"] as? Number)?.toLong(),
                model = metadataMap["model"] as? String,
                provider = metadataMap["provider"] as? String
            )
        } catch (e: Exception) {
            logger.error(e) { "[EXTRACT] Failed to parse execution summary metadata" }
            null
        }
    }

    private fun extractConversationSummaryMetadata(message: Message): ConversationSummaryMetadata? {
        val metadata = message.metadata ?: return null
        return try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val type = metadataMap["type"] as? String ?: return null
            if (type != "conversation_summary") {
                return null
            }

            ConversationSummaryMetadata(
                summarizedCount = metadataMap["summarized_count"].toSafeInt(defaultValue = 0),
                summaryIndex = metadataMap["summary_index"].toSafeInt(defaultValue = 1),
                timestamp = (metadataMap["timestamp"] as? Number)?.toLong(),
                firstMessageId = metadataMap["first_message_id"] as? String,
                lastMessageId = metadataMap["last_message_id"] as? String
            )
        } catch (e: Exception) {
            logger.error(e) { "[EXTRACT] Failed to parse conversation summary metadata" }
            null
        }
    }

    private fun parseChangedFileEntry(entry: Any?): ExecutionSummaryFile? {
        val map = entry as? Map<*, *> ?: return null
        val filePath = map["file_path"] as? String ?: return null
        val added = map["added_lines"].toSafeInt(defaultValue = 0)
        val removed = map["removed_lines"].toSafeInt(defaultValue = 0)
        val snapshotId = map["snapshot_id"] as? String
        return ExecutionSummaryFile(
            filePath = filePath,
            addedLines = added,
            removedLines = removed,
            snapshotId = snapshotId
        )
    }

    private fun Any?.toSafeInt(defaultValue: Int): Int {
        return when (this) {
            is Number -> this.toInt()
            is String -> this.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun Any?.toSafeDouble(defaultValue: Double): Double {
        return when (this) {
            is Number -> this.toDouble()
            is String -> this.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    /**
     * Data class for code changes information
     */
    private data class CodeChangesData(
        val filePath: String,
        val addedLines: Int,
        val removedLines: Int,
        val snapshotId: String?
    )

    private data class ExecutionSummaryMetadata(
        val changedFiles: List<ExecutionSummaryFile>,
        val stats: ExecutionSummaryStats?,
        val generatedAt: Long?,
        val model: String?,
        val provider: String?
    )

    private data class ConversationSummaryMetadata(
        val summarizedCount: Int,
        val summaryIndex: Int,
        val timestamp: Long?,
        val firstMessageId: String?,
        val lastMessageId: String?
    )

    private data class ExecutionSummaryFile(
        val filePath: String,
        val addedLines: Int,
        val removedLines: Int,
        val snapshotId: String?
    )

    private data class ExecutionSummaryStats(
        val totalSteps: Int,
        val completedSteps: Int,
        val failedSteps: Int,
        val totalTokens: Int,
        val totalCostUsd: Double
    )

    /**
     * Create clickable badge showing code changes.
     * Badge displays "Edit filename.ext +X -Y" and opens diff dialog on click.
     */
    private fun createChangesBadge(changes: CodeChangesData): JPanel {
        // Container with rounded background
        val containerPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false
        }

        // Badge panel with round box background
        val badgePanel = object : JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )

                // More prominent blue-tinted background
                g2.color = LCATheme.infoHighlightBackground
                g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)

                // Subtle border with blue tint
                g2.color = LCATheme.infoHighlightBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)

                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = LCATheme.paddedBorder(6, 10)
        }

        // Extract filename from path
        val fileName = changes.filePath.substringAfterLast('/')

        // Build changes text in June style: "Edit filename.ext +X -Y"
        val action = if (changes.removedLines == 0 && changes.addedLines > 0) "Created" else "Edit"
        val changesText = buildString {
            append("$action $fileName ")
            if (changes.addedLines > 0) append("+${changes.addedLines} ")
            if (changes.removedLines > 0) append("-${changes.removedLines}")
        }.trim()

        // Pencil icon
        val editIcon = JLabel("✏️").apply {
            font = font.deriveFont(12f)
        }

        // Create clickable label
        val changesLabel = JLabel(changesText).apply {
            foreground = LCATheme.infoHighlightForeground
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        badgePanel.add(editIcon)
        badgePanel.add(changesLabel)

        // Make entire badge clickable
        badgePanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                openCodeChangesDiff(changes)
            }

            override fun mouseEntered(e: MouseEvent) {
                // Slightly darken background on hover
                changesLabel.font = changesLabel.font.deriveFont(Font.BOLD)
                badgePanel.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                changesLabel.font = changesLabel.font.deriveFont(Font.PLAIN)
                badgePanel.repaint()
            }
        })

        containerPanel.add(badgePanel)
        return containerPanel
    }

    /**
     * Open IntelliJ native diff viewer for code changes.
     * Uses DiffManager.showDiff() instead of modal dialog.
     */
    private fun openCodeChangesDiff(changes: CodeChangesData) {
        logger.info { "[DIFF] Opening diff viewer for: ${changes.filePath}" }
        logger.info { "[DIFF] Changes: +${changes.addedLines} -${changes.removedLines}, snapshotId=${changes.snapshotId}" }

        ApplicationManager.getApplication().invokeLater {
            try {
                val basePath = project.basePath ?: run {
                    logger.error { "[DIFF] Project basePath is null!" }
                    showNotification("Error", "Project path not found", NotificationType.ERROR)
                    return@invokeLater
                }
                logger.info { "[DIFF] Project basePath: $basePath" }

                // Construct full path
                val fullPath = Paths.get(basePath, changes.filePath)
                logger.info { "[DIFF] Full path resolved: $fullPath" }
                logger.info { "[DIFF] File exists: ${Files.exists(fullPath)}" }

                if (!Files.exists(fullPath)) {
                    logger.warn { "[DIFF] File not found at: $fullPath" }
                    showNotification("Error", "File not found: ${changes.filePath}", NotificationType.ERROR)
                    return@invokeLater
                }

                // Get VirtualFile
                logger.info { "[DIFF] Attempting to load VirtualFile from: $fullPath" }
                val vFile = VirtualFileManager.getInstance().findFileByNioPath(fullPath) ?: run {
                    logger.error { "[DIFF] VirtualFileManager could not find file: $fullPath" }
                    showNotification("Error", "Could not load file: ${changes.filePath}", NotificationType.ERROR)
                    return@invokeLater
                }
                logger.info { "[DIFF] VirtualFile loaded: ${vFile.path}, fileType=${vFile.fileType.name}" }

                val diffManager = DiffManager.getInstance()
                val contentFactory = DiffContentFactory.getInstance()

                // Try to load snapshot if available
                val snapshotContent = if (!changes.snapshotId.isNullOrBlank()) {
                    logger.info { "[DIFF] Loading snapshot: ${changes.snapshotId}" }
                    val content = loadSnapshotContent(changes.snapshotId, changes.filePath)
                    if (content != null) {
                        logger.info { "[DIFF] Snapshot loaded successfully: ${content.length} chars" }
                    } else {
                        logger.warn { "[DIFF] Snapshot content is null for: ${changes.snapshotId}" }
                    }
                    content
                } else {
                    logger.info { "[DIFF] No snapshot ID provided - will show empty vs current" }
                    null
                }

                val diffRequest = if (snapshotContent != null) {
                    logger.info { "[DIFF] Creating diff request: Before (snapshot) vs After (current)" }
                    // Show diff: snapshot (before) vs current file (after)
                    val beforeContent = contentFactory.create(snapshotContent, vFile.fileType)
                    val afterContent = contentFactory.create(project, vFile)

                    SimpleDiffRequest(
                        "Changes: ${changes.filePath}",
                        beforeContent,
                        afterContent,
                        "Before",
                        "After"
                    )
                } else {
                    logger.info { "[DIFF] Creating diff request: Empty vs Current (no snapshot)" }
                    // No snapshot - show diff with empty content (new/modified file)
                    val emptyContent = contentFactory.create("")
                    val currentContent = contentFactory.create(project, vFile)

                    SimpleDiffRequest(
                        if (changes.removedLines == 0 && changes.addedLines > 0) "Created: ${changes.filePath}" else "Changes: ${changes.filePath}",
                        emptyContent,
                        currentContent,
                        "Empty",
                        "Current"
                    )
                }

                // Open native IntelliJ diff viewer (not modal)
                logger.info { "[DIFF] Opening IntelliJ diff viewer" }
                diffManager.showDiff(project, diffRequest)
                logger.info { "[DIFF] Diff viewer opened successfully" }

            } catch (e: Exception) {
                logger.error(e) { "Error opening diff for file: ${changes.filePath}" }
                showNotification("Error", "Could not open diff: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    /**
     * Load snapshot content from database
     */
    private fun loadSnapshotContent(snapshotId: String, filePath: String): String? {
        return try {
            logger.info { "[SNAPSHOT] Loading snapshot content for: snapshotId=$snapshotId, filePath=$filePath" }
            val router = coreManager.getApiRouter()
            val content = runBlocking {
                router.getSnapshotFileContent(snapshotId, filePath)
            }
            if (content != null) {
                logger.info { "[SNAPSHOT] Loaded successfully: ${content.length} chars" }
            } else {
                logger.warn { "[SNAPSHOT] Router returned null for: snapshotId=$snapshotId, filePath=$filePath" }
            }
            content
        } catch (e: Exception) {
            logger.error(e) { "[SNAPSHOT] Failed to load snapshot: $snapshotId for file: $filePath" }
            null
        }
    }

    private fun showNotification(
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        Notifications.Bus.notify(
            Notification("Refio", title, content, type),
            project
        )
    }

    private fun createUserBubble(message: Message): JPanel {
        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        val messageContainer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Create message block with actions inside
        val messageBlock = FlatMessageBlock(LCATheme.userBubbleBackground).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Content panel (editable)
        val contentPanel = createBubbleContentPanel(
            message.content,
            LCATheme.userBubbleBackground,
            LCATheme.userBubbleForeground,
            isUser = true
        )
        val editableBubble = EditableUserBubble(
            project = project,
            initialText = message.content,
            contentComponent = contentPanel,
            onSubmit = { newContent ->
                cs.launch {
                    try {
                        sessionManager.rewindAndResendFromMessage(message.id, newContent)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to rewind and resend from message ${message.id}" }
                        showNotification("Error", e.message ?: "Failed to rewind conversation", NotificationType.ERROR)
                    }
                }
            }
        )

        // Actions panel (icons) - positioned at bottom right
        val userActions = wrapRightAligned(
            createMessageActionsPanel(
                message = message,
                onEdit = { editableBubble.beginEditing() }
            )
        )

        // Stack content and actions vertically
        messageBlock.add(editableBubble)
        messageBlock.add(Box.createVerticalStrut(6))
        messageBlock.add(userActions)

        messageContainer.add(messageBlock)

        //
        // Then: context badge below message (if exists)
        //
        val contextMetadata = extractUserContextMetadata(message)
        if (contextMetadata != null && contextMetadata.contextRefs.isNotEmpty()) {
            val contextBadge = createContextBadge(contextMetadata)
            messageContainer.add(contextBadge)
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
        }

        outerPanel.add(messageContainer, gbc)
        return outerPanel
    }

    private fun createExecutionSummaryBubble(
        message: Message,
        metadata: ExecutionSummaryMetadata
    ): JPanel {
        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply { isOpaque = false }
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
        }

        val summaryCard = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = LCATheme.assistantBubbleBackground
            border = LCATheme.compoundBorder(
                LCATheme.customLineBorder(LCATheme.borderColor, 1),
                LCATheme.paddedBorder(LCATheme.spacingLg)
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }

        summaryCard.add(createSummaryHeader(metadata))

        metadata.stats?.let {
            summaryCard.add(Box.createVerticalStrut(LCATheme.spacingSm))
            summaryCard.add(createSummaryMetricsRow(it))
        }

        val maxBubbleWidth = (availableWidth - SCROLL_BAR_AND_PADDING).coerceAtLeast(200)
        val summaryBubble = createMarkdownEditorPane(
            message.content.ifBlank { "No execution summary available." },
            LCATheme.assistantBubbleBackground,
            LCATheme.assistantBubbleForeground,
            maxBubbleWidth
        )
        summaryBubble.let {
            it.alignmentX = Component.LEFT_ALIGNMENT
            summaryCard.add(it)
        }

        val filesHeader = buildString {
            append("Changed Files")
            if (metadata.changedFiles.isNotEmpty()) {
                append(" (${metadata.changedFiles.size})")
            }
        }
        summaryCard.add(JLabel(filesHeader).apply {
            font = LCATheme.headerFont.deriveFont(Font.BOLD)
            foreground = LCATheme.labelForeground
            alignmentX = Component.LEFT_ALIGNMENT
        })
        summaryCard.add(createChangedFilesPanel(metadata.changedFiles))

        outerPanel.add(summaryCard, gbc)
        return summaryCard
    }

    private fun createSummaryHeader(metadata: ExecutionSummaryMetadata): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Główny tytuł z większym fontem
        val titlePanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        titlePanel.add(JLabel("✓ Done").apply {
            font = LCATheme.largeBoldFont
            foreground = LCATheme.successColor
        })

        panel.add(titlePanel)
        panel.add(Box.createVerticalStrut(6))

        // Drugi rząd: statystyki i model
        val infoPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        metadata.stats?.let { stats ->
            infoPanel.add(JLabel("${stats.completedSteps}/${stats.totalSteps} steps").apply {
                font = LCATheme.bodyFont.deriveFont(Font.BOLD)
                foreground = LCATheme.labelForeground
            })
        }

        metadata.model?.let { model ->
            val provider = metadata.provider?.let { "$it/" } ?: ""
            infoPanel.add(JLabel("•").apply {
                foreground = LCATheme.labelForeground
            })
            infoPanel.add(JLabel("$provider$model").apply {
                font = LCATheme.bodyFont
                foreground = LCATheme.labelForeground
            })
        }

        panel.add(infoPanel)
        return panel
    }

    private fun createSummaryMetricsRow(stats: ExecutionSummaryStats): JComponent {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(createSummaryStatChip("Tokens", stats.totalTokens.toString()))
        panel.add(createSummaryStatChip("Cost", "$${"%.4f".format(stats.totalCostUsd)}"))
        if (stats.failedSteps > 0) {
            panel.add(createSummaryStatChip("Failed", stats.failedSteps.toString(), isError = true))
        }
        return panel
    }

    private fun createSummaryStatChip(label: String, value: String, isError: Boolean = false): JComponent {
        val panel = object : JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.color = if (isError) LCATheme.errorHighlightBackground else LCATheme.infoHighlightBackground
                g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)

                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = LCATheme.paddedBorder(6, 10)
        }

        panel.add(JLabel(label).apply {
            font = LCATheme.smallFont.deriveFont(Font.BOLD)
            foreground = if (isError) LCATheme.errorHighlightForeground else LCATheme.infoHighlightForeground
        })

        panel.add(JLabel(value).apply {
            font = LCATheme.smallFont
            foreground = if (isError) LCATheme.errorHighlightForeground else LCATheme.infoHighlightForeground
        })

        return panel
    }

    private fun createChangedFilesPanel(files: List<ExecutionSummaryFile>): JComponent {
        val container = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        if (files.isEmpty()) {
            container.add(JLabel("No file changes detected").apply {
                font = LCATheme.italicFont
                foreground = LCATheme.descriptionForeground
            })
            return container
        }

        files.take(MAX_SUMMARY_FILES).forEachIndexed { index, file ->
            val badge = createChangesBadge(
                CodeChangesData(
                    filePath = file.filePath,
                    addedLines = file.addedLines,
                    removedLines = file.removedLines,
                    snapshotId = file.snapshotId
                )
            )
            badge.alignmentX = Component.LEFT_ALIGNMENT
            container.add(badge)
            if (index < MAX_SUMMARY_FILES - 1) {
                container.add(Box.createVerticalStrut(LCATheme.spacingXs))
            }
        }

        if (files.size > MAX_SUMMARY_FILES) {
            val remaining = files.size - MAX_SUMMARY_FILES
            container.add(Box.createVerticalStrut(LCATheme.spacingXs))
            container.add(JLabel("+$remaining more").apply {
                font = LCATheme.italicFont
                foreground = LCATheme.descriptionForeground
            })
        }

        return container
    }

    private fun createAssistantBubble(message: Message): JPanel {
        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        val messageContainer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Create message block with actions inside
        val messageBlock = FlatMessageBlock(LCATheme.assistantBubbleBackground).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Message content
        val contentPanel = createBubbleContentPanel(
            message.content,
            LCATheme.assistantBubbleBackground,
            LCATheme.assistantBubbleForeground,
            isUser = false
        )
        messageBlock.add(contentPanel)

        // Code changes badge (if message contains code changes metadata)
        val codeChanges = extractCodeChanges(message)
        if (codeChanges != null) {
            messageBlock.add(Box.createVerticalStrut(4))
            val badge = createChangesBadge(codeChanges)
            messageBlock.add(badge)
        }

        // Note: Stop button moved to PromptInputPanel (Send button transforms to Stop)
        if (message.isStreaming) {
            logger.debug { "Message ${message.id} is streaming, adding indicator" }

            val streamingPanel = JBPanel<JBPanel<*>>().apply {
                layout = FlowLayout(FlowLayout.LEFT, 8, 4)
                isOpaque = false
            }

            // Streaming indicator (blinking cursor)
            val streamingLabel = JLabel("Generating...").apply {
                font = font.deriveFont(Font.ITALIC, 11f)
            }
            streamingPanel.add(streamingLabel)

            messageBlock.add(streamingPanel)
        }

        // Metrics view
        logger.debug { "Assistant message ${message.id}: hasMetrics=${message.metrics != null}, metrics=${message.metrics}" }
        if (message.metrics != null) {
            logger.info { "Adding MetricsView for message ${message.id} metrics=${message.metrics}" }
            messageBlock.add(Box.createVerticalStrut(4))
            messageBlock.add(MetricsView(message.metrics))
        }

        // Actions panel (icons) - positioned at bottom right
        messageBlock.add(Box.createVerticalStrut(6))
        val assistantActions = wrapRightAligned(createMessageActionsPanel(message = message))
        messageBlock.add(assistantActions)

        messageContainer.add(messageBlock)

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        outerPanel.add(messageContainer, gbc)
        return outerPanel
    }

    private fun createMessageActionsPanel(
        message: Message,
        onEdit: (() -> Unit)? = null
    ): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false

            if (message.role == "user") {
                add(createSmallIconButton(AllIcons.Actions.Edit, "Edit and rewind conversation") {
                    onEdit?.invoke()
                })
            }

            add(createSmallIconButton(AllIcons.Actions.Copy, "Copy message") {
                copyToClipboard(message.content)
            })

            if (message.role == "assistant") {
                add(createSmallIconButton(AllIcons.Actions.Refresh, "Regenerate (re-send previous user prompt)") {
                    val prevUser = findPreviousUserMessage(message.id)
                        ?: throw IllegalStateException("No previous user message found to regenerate from")

                    cs.launch {
                        try {
                            sessionManager.rewindAndResendFromMessage(prevUser.id, prevUser.content)
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to regenerate response from user message ${prevUser.id}" }
                            showNotification(
                                "Error",
                                e.message ?: "Failed to regenerate response",
                                NotificationType.ERROR
                            )
                        }
                    }
                })
            }

            add(createSmallIconButton(AllIcons.Actions.GC, "Delete message") {
                cs.launch {
                    try {
                        sessionManager.deleteChatMessage(message.id)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to delete message ${message.id}" }
                        showNotification("Error", e.message ?: "Failed to delete message", NotificationType.ERROR)
                    }
                }
            })
        }
    }

    private fun wrapRightAligned(component: JComponent): JComponent {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(component, BorderLayout.EAST)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height)
        }
    }

    private fun findPreviousUserMessage(fromMessageId: String): Message? {
        val messages = sessionManager.messages.value
        val index = messages.indexOfFirst { it.id == fromMessageId }
        if (index <= 0) return null
        return messages.subList(0, index).lastOrNull { it.role == "user" }
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        showNotification("Copied", "Copied to clipboard", NotificationType.INFORMATION)
    }

    private fun createSmallIconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(4, 8, 4, 8)
            preferredSize = Dimension(18, 18)
            minimumSize = Dimension(18, 18)
            addActionListener {
                try {
                    action()
                } catch (e: Exception) {
                    logger.error(e) { "Response action failed" }
                    showNotification("Error", e.message ?: "Action failed", NotificationType.ERROR)
                }
            }
        }
    }

    /**
     * Create question bubble with answer buttons for orchestrator questions.
     */
    private fun createQuestionBubble(message: Message, questionData: QuestionData): JPanel {
        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        // Container for content + buttons
        val messageContainer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Content panel with message (purple/violet background for questions)
        val contentPanel = createBubbleContentPanel(
            message.content,
            LCATheme.questionBubbleBackground,
            LCATheme.assistantBubbleForeground,
            isUser = false
        )
        messageContainer.add(contentPanel)

        // Wrapper for content + buttons
        val wrapperPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = DEFAULT_SPACE
            add(messageContainer, BorderLayout.CENTER)
        }

        // Status label
        val statusLabel = JLabel("").apply {
            foreground = LCATheme.grayColor
            font = font.deriveFont(Font.ITALIC)
        }

        // Buttons panel
        val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false

            if (questionData.options.isNotEmpty()) {
                // Show option buttons (A, B, C, etc.)
                questionData.options.forEachIndexed { index, option ->
                    val optionLabel = ('A' + index).toString()
                    val button =
                        JButton("$optionLabel. ${option.take(30)}${if (option.length > 30) "..." else ""}").apply {
                            toolTipText = option
                            preferredSize = Dimension(200, 32)
                            addActionListener {
                                cs.launch {
                                    try {
                                        logger.info { "User selected option $optionLabel for question ${questionData.questionId}" }

                                        // Disable all buttons
                                        (parent as? JPanel)?.components?.forEach {
                                            (it as? JButton)?.isEnabled = false
                                        }

                                        statusLabel.text = "⏳ Sending answer..."

                                        // Answer question with selected option
                                        sessionManager.answerQuestion(questionData.questionId, option)

                                        statusLabel.text = "✓ Answer sent, orchestration resuming..."
                                        logger.info { "Answered question: ${questionData.questionId}" }

                                    } catch (e: Exception) {
                                        statusLabel.text = "✗ Failed: ${e.message}"
                                        logger.error(e) { "Failed to answer question" }
                                    }
                                }
                            }
                        }
                    add(button)
                }
            } else {
                // No options - user can type free-form answer
                // Show hint that user should type in PromptInput
                val hintLabel = JLabel("💬 Type your answer in the input field below").apply {
                    foreground = LCATheme.grayColor
                    font = font.deriveFont(Font.ITALIC, 12f)
                }
                add(hintLabel)
            }

            add(statusLabel)
        }

        // Vertical layout: content + buttons
        val container = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(wrapperPanel, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        outerPanel.add(container, gbc)
        return outerPanel
    }

    private fun createApprovalBubble(message: Message, subtaskId: String): JPanel {
        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        // Container for content + metrics with vertical layout
        val messageContainer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Content panel with message (yellow/orange background for approval)
        val contentPanel = createBubbleContentPanel(
            message.content,
            LCATheme.approvalBubbleBackground,
            LCATheme.assistantBubbleForeground,
            isUser = false
        )
        messageContainer.add(contentPanel)

        // Metrics view for planning phase (US-027)
        if (message.metrics != null) {
            messageContainer.add(Box.createVerticalStrut(4))
            messageContainer.add(MetricsView(message.metrics))
        }

        // Wrapper for content + buttons
        val wrapperPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(messageContainer, BorderLayout.CENTER)
        }

        // Buttons panel below content
        val statusLabel = JLabel("").apply {
            foreground = LCATheme.grayColor
            font = font.deriveFont(Font.ITALIC)
        }

        // Forward declarations for cross-reference
        lateinit var approveBtn: JButton
        lateinit var skipBtn: JButton

        val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false

            approveBtn = JButton("✓ Approve").apply {
                toolTipText = "Approve and execute step"
                font = font.deriveFont(Font.BOLD)
                preferredSize = Dimension(120, 32)
                addActionListener {
                    cs.launch {
                        try {
                            logger.info { "Approving subtask: $subtaskId" }

                            // Disable buttons and show executing status
                            approveBtn.isEnabled = false
                            skipBtn.isEnabled = false
                            statusLabel.text = "⏳ Executing step..."

                            sessionManager.approveSubtask(subtaskId)

                            // Update status
                            statusLabel.text = "✓ Step completed"
                            logger.info { "Approved subtask: $subtaskId" }
                        } catch (e: Exception) {
                            statusLabel.text = "✗ Execution failed: ${e.message}"
                            logger.error(e) { "Failed to approve subtask" }
                        }
                    }
                }
            }

            skipBtn = JButton("⏭ Skip").apply {
                toolTipText = "Skip this step"
                preferredSize = Dimension(100, 32)
                addActionListener {
                    cs.launch {
                        try {
                            logger.info { "Skipping subtask: $subtaskId" }

                            // Disable buttons and show skipping status
                            approveBtn.isEnabled = false
                            skipBtn.isEnabled = false
                            statusLabel.text = "⏭ Step skipped"

                            sessionManager.skipSubtask(subtaskId)

                            logger.info { "Skipped subtask: $subtaskId" }
                        } catch (e: Exception) {
                            statusLabel.text = "✗ Skip failed: ${e.message}"
                            logger.error(e) { "Failed to skip subtask" }
                        }
                    }
                }
            }

            add(approveBtn)
            add(skipBtn)
            add(statusLabel)
        }

        // Vertical layout: content + buttons
        val container = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(wrapperPanel, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        outerPanel.add(container, gbc)
        return outerPanel
    }

    private fun createConversationSummaryBubble(
        message: Message,
        metadata: ConversationSummaryMetadata
    ): JPanel {

        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        val maxBubbleWidth = (availableWidth - SCROLL_BAR_AND_PADDING).coerceAtLeast(200)

        val summaryContent = if (message.content.isBlank()) {
            "No summary content."
        } else {
            message.content
        }

        val cleanedSummary = if (summaryContent.startsWith("**Conversation summary")) {
            summaryContent.substringAfter("\n\n", summaryContent).ifBlank { summaryContent }
        } else {
            summaryContent
        }

        val summaryPanel = createMarkdownEditorPane(
            cleanedSummary,
            LCATheme.summaryBubbleBackground,
            LCATheme.summaryBubbleForeground,
            maxBubbleWidth
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = LCATheme.emptyBorder()
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.BOTH
        }

        outerPanel.add(summaryPanel, gbc)
        return outerPanel
    }

    private fun createSystemBubble(message: Message): JPanel {
        val outerPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        val contentPanel = createBubbleContentPanel(
            message.content, LCATheme.systemBubbleBackground, LCATheme.systemBubbleForeground, isUser = false
        )

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        outerPanel.add(contentPanel, gbc)
        return outerPanel
    }


    private fun createBubbleContentPanel(
        content: String, backgroundColor: Color, foregroundColor: Color, isUser: Boolean
    ): JPanel {
        val maxBubbleWidth = (availableWidth - SCROLL_BAR_AND_PADDING).coerceAtLeast(200)
        val codeBlocks = extractCodeBlocks(content)

        if (codeBlocks.isEmpty()) {
            // No code blocks - render as before (markdown → HTML)
            return createMarkdownPanel(content, backgroundColor, foregroundColor, maxBubbleWidth)
        } else {
            // Has code blocks - render parts separately
            return createMixedContentPanel(content, codeBlocks, backgroundColor, foregroundColor, maxBubbleWidth)
        }
    }

    /**
     * Create panel with markdown-only content (no code blocks)
     */
    private fun createMarkdownPanel(
        content: String, backgroundColor: Color, foregroundColor: Color, maxBubbleWidth: Int
    ): JPanel {
        val panel = FlatMessageBlock(backgroundColor).apply {
            layout = BorderLayout()
        }

        val editorPane = createMarkdownEditorPane(content, backgroundColor, foregroundColor, maxBubbleWidth)

        panel.add(editorPane, BorderLayout.CENTER)

        panel.minimumSize = Dimension(maxBubbleWidth, panel.preferredSize.height)
        panel.preferredSize = Dimension(maxBubbleWidth, panel.preferredSize.height)
        panel.maximumSize = Dimension(maxBubbleWidth, Int.MAX_VALUE)

        return panel
    }

    /**
     * Create panel with mixed content (text + code blocks)
     */
    private fun createMixedContentPanel(
        content: String,
        codeBlocks: List<CodeBlock>,
        backgroundColor: Color,
        foregroundColor: Color,
        maxBubbleWidth: Int
    ): JPanel {
        val panel = FlatMessageBlock(backgroundColor).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        var lastIndex = 0

        codeBlocks.forEach { codeBlock ->
            // Add text before code block
            if (codeBlock.startIndex > lastIndex) {
                val textBefore = content.substring(lastIndex, codeBlock.startIndex)
                if (textBefore.isNotBlank()) {
                    val textPanel =
                        createMarkdownEditorPane(textBefore, backgroundColor, foregroundColor, maxBubbleWidth)
                    panel.add(textPanel)
                }
            }

            // Add code block panel with actions
            val codePanel = CodeBlockPanel(codeBlock, project).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                minimumSize.width = maxBubbleWidth
                preferredSize.width = maxBubbleWidth
                maximumSize.width = maxBubbleWidth
                border = DEFAULT_SPACE
                background = LCATheme.editorBackground
                foreground = LCATheme.editorForeground
            }

            panel.add(codePanel)
            panel.add(Box.createVerticalStrut(8))

            lastIndex = codeBlock.endIndex + 1
        }

        // Add text after last code block
        if (lastIndex < content.length) {
            val textAfter = content.substring(lastIndex)
            if (textAfter.isNotBlank()) {
                val textPanel = createMarkdownEditorPane(textAfter, backgroundColor, foregroundColor, maxBubbleWidth)
                panel.add(textPanel)
            }
        }

        return panel
    }

    /**
     * Create JEditorPane with markdown → HTML rendering (or plain text if disabled)
     */
    private fun createMarkdownEditorPane(
        markdown: String, backgroundColor: Color, foregroundColor: Color, maxBubbleWidth: Int
    ): JEditorPane {
        val htmlContent = if (formatMarkdownEnabled) {
            markdownToHtml(markdown)
        } else {
            // Plain text mode - escape HTML entities and preserve line breaks
            markdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>")
        }

        return JTextPane().apply {
            contentType = "text/html"

            val kit = HTMLEditorKit()
            val styleSheet = StyleSheet()
            val isDarkTheme = LCATheme.isDark

            val bgColorHex = String.format(
                "#%02x%02x%02x", backgroundColor.red, backgroundColor.green, backgroundColor.blue
            )
            val fgColorHex = String.format(
                "#%02x%02x%02x", foregroundColor.red, foregroundColor.green, foregroundColor.blue
            )

            // Note: Java's StyleSheet only supports a limited subset of CSS properties.
            // Unsupported properties like border-radius, overflow-x, white-space
            // can cause NullPointerException on some platforms (especially macOS).
            // word-wrap and max-width are basic CSS properties that should be safe.
            styleSheet.addRule(
                """
                body {
                    font-family: ${LCATheme.bodyFont.family};
                    font-size: ${LCATheme.bodyFont.size}pt;
                    color: $fgColorHex;
                    background-color: $bgColorHex;
                    margin: 4px;
                    padding: 4px;
                    max-width: ${maxBubbleWidth - MAX_BUUBLE}px;
                }
                h1, h2, h3, h4, h5, h6 {
                    margin-top: 8px;
                    margin-bottom: 4px;
                    font-weight: bold;
                }
                h1 {
                    font-size: ${LCATheme.bodyFont.size + 6}pt;
                }
                h2 {
                    font-size: ${LCATheme.bodyFont.size + 4}pt;
                }
                h3 {
                    font-size: ${LCATheme.bodyFont.size + 2}pt;
                }
                h4 { 
                    font-size: ${LCATheme.bodyFont.size + 1}pt; 
                }
                h4 { 
                    font-size: ${LCATheme.bodyFont.size}pt; 
                }
                pre {
                    background-color: ${if (isDarkTheme) "#2B2B2B" else "#F5F5F5"};
                    padding: 8px;
                    margin-top: 8px;
                    margin-bottom: 8px;
                }
                code {
                    font-family: monospace;
                    background-color: ${if (isDarkTheme) "#3C3F41" else "#E8E8E8"};
                    padding: 2px 4px;
                }
                p {
                    margin-top: 4px;
                    margin-bottom: 4px;
                }
                ul, ol {
                    margin-top: 4px;
                    margin-bottom: 4px;
                }
                li {
                    margin-top: 2px;
                    margin-bottom: 2px;
                }
                strong {
                    font-weight: bold;
                }
                em {
                    font-style: italic;
                }
            """.trimIndent()
            )

            kit.styleSheet = styleSheet
            editorKit = kit

            text = "<html><body>$htmlContent</body></html>"
            isEditable = false
            isOpaque = true
            background = backgroundColor

            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)

            // Force width and let height be calculated (account for margins/padding: 8px * 2 + 8px * 2 = 32px)
            val editorWidth = maxBubbleWidth - MAX_BUUBLE
            setSize(editorWidth, Short.MAX_VALUE.toInt())
            preferredSize = Dimension(editorWidth, preferredSize.height)

            // Add hyperlink listener for clickable file paths
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    val url = e.description
                    if (url.startsWith("file://")) {
                        val filePath = url.removePrefix("file://")
                        showFileChangesDialog(filePath)
                    }
                }
            }
        }
    }

    /**
     * Show dialog with file changes
     */
    private fun showFileChangesDialog(filePath: String, snapshotId: String? = null) {
        ApplicationManager.getApplication().invokeLater {
            logger.info { "Showing changes dialog: file=$filePath, snapshot=$snapshotId" }
            val dialog = ChangesDialog(project, filePath, snapshotId = snapshotId)
            dialog.show()
        }
    }

    private fun markdownToHtml(markdown: String): String {
        val document = markdownParser.parse(markdown)
        var html = htmlRenderer.render(document)

        // Detect file paths and make them clickable
        val filePaths = FilePathDetector.findFilePaths(markdown)
        filePaths.forEach { match ->
            // Create clickable link with custom scheme
            val link =
                "<a href=\"file://${match.path}\" style=\"color: #589df6; text-decoration: underline;\">${match.path}</a>"
            html = html.replace(match.path, link)
        }

        return html
    }

    // scrollToBottom removed - ReverseChatPanel handles scrolling now

    /**
     * Create conversation toolbar with action buttons
     */
    private fun createConversationToolbar(): JPanel {
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            border = LCATheme.compoundBorder(
                LCATheme.customLineBorder(LCATheme.subtleSeparatorColor, 1, 0, 0, 0),
                LCATheme.paddedBorder(4, 0, 4, 0)  // Minimal vertical padding, no horizontal
            )
            background = LCATheme.backgroundColor
        }

        // Rate Up button
        toolbar.add(createIconButton("👍", "Rate conversation positive") {
            rateConversation(1)
        })

        // Rate Down button
        toolbar.add(createIconButton("👎", "Rate conversation negative") {
            rateConversation(-1)
        })

        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 16)
        })

        // Compact button
        toolbar.add(createIconButton("📦", "Summarize and compact conversation") {
            compactConversation()
        })

        // Copy All button
        toolbar.add(createIconButton("📋", "Copy entire conversation") {
            copyConversation()
        })

        // Export button
        toolbar.add(createIconButton("💾", "Export conversation to file") {
            exportConversation()
        })

        return toolbar
    }

    /**
     * Create icon-only button for conversation toolbar
     */
    private fun createIconButton(
        icon: String, tooltip: String, action: () -> Unit
    ): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(28, 24)
            font = font.deriveFont(14f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                cs.launch {
                    try {
                        action()
                    } catch (e: Exception) {
                        logger.error(e) { "Toolbar action failed" }
                        showToolbarNotification("Error", e.message ?: "Action failed", NotificationType.ERROR)
                    }
                }
            }
        }
    }

    /**
     * Rate conversation (save to DB)
     */
    private fun rateConversation(rating: Int) {
        val session = sessionManager.activeSession.value ?: return

        cs.launch {
            try {
                logger.info { "Rating conversation ${session.id}: $rating" }

                // Save rating via project router
                sessionManager.apiRouter.updateTask(
                    session.id, pl.jclab.refio.core.api.UpdateTaskRequest(rate = rating)
                )

                val message = if (rating > 0) {
                    "👍 Thanks for positive feedback!"
                } else {
                    "👎 Thanks for feedback. We'll improve!"
                }

                showToolbarNotification("Feedback", message)
                logger.info { "Successfully saved rating for task ${session.id}" }

            } catch (e: Exception) {
                logger.error(e) { "Failed to rate conversation" }
                showToolbarNotification("Error", "Failed to save rating: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    /**
     * Compact conversation (generate inline summary message).
     */
    private fun compactConversation() {
        val session = sessionManager.activeSession.value ?: return
        val messages = sessionManager.messages.value

        logger.info { "Compacting conversation for session ${session.id}" }
        showToolbarNotification("Summary", "Generating conversation summary...", NotificationType.INFORMATION)

        cs.launch {
            try {
                sessionManager.generateSummary()
                showToolbarNotification("Success", "Conversation summary created", NotificationType.INFORMATION)
            } catch (e: Exception) {
                logger.error(e) { "Failed to compact conversation" }
                showToolbarNotification(
                    "Error",
                    "Failed to generate conversation summary: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }
    }

    /**
     * Copy entire conversation to clipboard
     */
    private fun copyConversation() {
        val messages = sessionManager.messages.value

        if (messages.isEmpty()) {
            showToolbarNotification("Info", "No messages to copy", NotificationType.INFORMATION)
            return
        }

        // Format conversation as markdown
        val conversationText = buildString {
            appendLine("# Conversation")
            appendLine()
            appendLine("**Session ID:** ${sessionManager.activeSession.value?.id}")
            appendLine("**Created:** ${formatTimestamp(sessionManager.activeSession.value?.createdAt)}")
            appendLine()
            appendLine("---")
            appendLine()

            messages.forEach { msg ->
                when (msg.role) {
                    "user" -> appendLine("## 👤 User")
                    "assistant" -> appendLine("## 🤖 Assistant")
                    "system" -> appendLine("## ⚙️ System")
                }
                appendLine()
                appendLine(msg.content)
                appendLine()

                // Add metrics if present
                msg.metrics?.let { metrics ->
                    appendLine("*Tokens: ${metrics.inputTokens}/${metrics.outputTokens}, Cost: $${metrics.costUsd}, Latency: ${metrics.latencyMs}ms*")
                    appendLine()
                }

                appendLine("---")
                appendLine()
            }
        }

        // Copy to clipboard
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = java.awt.datatransfer.StringSelection(conversationText)
        clipboard.setContents(stringSelection, null)

        showToolbarNotification("Success", "Conversation copied to clipboard")
    }

    /**
     * Export conversation to file (MD/JSON/TXT)
     */
    private fun exportConversation() {
        val session = sessionManager.activeSession.value ?: return
        val messages = sessionManager.messages.value

        if (messages.isEmpty()) {
            showToolbarNotification("Info", "No messages to export", NotificationType.INFORMATION)
            return
        }

        // Show file chooser
        ApplicationManager.getApplication().invokeLater {
            val fileChooser = JFileChooser().apply {
                dialogTitle = "Export Conversation"
                fileSelectionMode = JFileChooser.FILES_ONLY

                // Add file filters
                addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Markdown (*.md)", "md"))
                addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("JSON (*.json)", "json"))
                addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Plain Text (*.txt)", "txt"))

                // Default filename
                selectedFile = java.io.File("conversation-${session.id.take(8)}.md")
            }

            val result = fileChooser.showSaveDialog(this)

            if (result == JFileChooser.APPROVE_OPTION) {
                val file = fileChooser.selectedFile
                val extension = file.extension.lowercase()

                try {
                    when (extension) {
                        "md" -> exportAsMarkdown(file, session, messages)
                        "json" -> exportAsJson(file, session, messages)
                        "txt" -> exportAsText(file, session, messages)
                        else -> {
                            showToolbarNotification("Error", "Unsupported format: $extension", NotificationType.ERROR)
                            return@invokeLater
                        }
                    }

                    showToolbarNotification("Success", "Conversation exported to ${file.name}")

                } catch (e: Exception) {
                    logger.error(e) { "Failed to export conversation" }
                    showToolbarNotification("Error", "Export failed: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    /**
     * Export as Markdown
     */
    private fun exportAsMarkdown(
        file: java.io.File, session: pl.jclab.refio.api.models.Session, messages: List<Message>
    ) {
        file.writeText(buildString {
            appendLine("# Conversation Export")
            appendLine()
            appendLine("**Session ID:** ${session.id}")
            appendLine("**Mode:** ${session.mode}")
            appendLine("**Created:** ${formatTimestamp(session.createdAt)}")
            appendLine("**Updated:** ${formatTimestamp(session.updatedAt)}")
            appendLine()
            appendLine("---")
            appendLine()

            messages.forEach { msg ->
                when (msg.role) {
                    "user" -> appendLine("## 👤 User")
                    "assistant" -> appendLine("## 🤖 Assistant")
                    "system" -> appendLine("## ⚙️ System")
                }
                appendLine()
                appendLine(msg.content)
                appendLine()

                msg.metrics?.let { metrics ->
                    appendLine("**Metrics:**")
                    appendLine("- Model: ${metrics.model} (${metrics.provider})")
                    appendLine("- Tokens: ${metrics.inputTokens} in / ${metrics.outputTokens} out")
                    appendLine("- Cost: $${"%.4f".format(metrics.costUsd)}")
                    appendLine("- Latency: ${metrics.latencyMs}ms")
                    appendLine()
                }

                appendLine("---")
                appendLine()
            }
        })
    }

    /**
     * Export as JSON
     */
    private fun exportAsJson(
        file: java.io.File, session: pl.jclab.refio.api.models.Session, messages: List<Message>
    ) {
        val gson = com.google.gson.Gson()

        val exportData = mapOf(
            "session" to mapOf(
                "id" to session.id,
                "name" to session.name,
                "mode" to session.mode.name,
                "status" to session.status.name,
                "createdAt" to session.createdAt,
                "updatedAt" to session.updatedAt
            ), "messages" to messages.map { msg ->
                mapOf(
                    "id" to msg.id,
                    "role" to msg.role,
                    "content" to msg.content,
                    "createdAt" to msg.createdAt,
                    "metrics" to msg.metrics
                )
            })

        val json = gson.toJson(exportData)
        file.writeText(json)
    }

    /**
     * Export as plain text
     */
    private fun exportAsText(
        file: java.io.File, session: pl.jclab.refio.api.models.Session, messages: List<Message>
    ) {
        file.writeText(buildString {
            appendLine("=".repeat(80))
            appendLine("CONVERSATION EXPORT")
            appendLine("=".repeat(80))
            appendLine()
            appendLine("Session ID: ${session.id}")
            appendLine("Mode: ${session.mode}")
            appendLine("Created: ${formatTimestamp(session.createdAt)}")
            appendLine()
            appendLine("=".repeat(80))
            appendLine()

            messages.forEach { msg ->
                appendLine("[${msg.role.uppercase()}]")
                appendLine(msg.content)
                appendLine()
                appendLine("-".repeat(80))
                appendLine()
            }
        })
    }

    /**
     * Format timestamp for display
     */
    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null) return "Unknown"
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
        return formatter.format(instant)
    }

    /**
     * Show notification
     */
    private fun showToolbarNotification(
        title: String, content: String, type: NotificationType = NotificationType.INFORMATION
    ) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification("Refio", title, content, type), project
            )
        }
    }

    /**
     * Load format markdown setting from config
     */
    private fun loadFormatMarkdownSetting() {
        cs.launch {
            try {
                val config = sessionManager.apiRouter.getConfig("general", "app")
                val enabled = config.settings["format_markdown"]?.toString()?.toBoolean() ?: true

                if (enabled != formatMarkdownEnabled) {
                    formatMarkdownEnabled = enabled
                    logger.info { "Format markdown setting loaded: $formatMarkdownEnabled" }

                    // Refresh messages to apply new setting
                    val messages = sessionManager.messages.value
                    SwingUtilities.invokeLater {
                        updateMessages(messages)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load format_markdown setting" }
            }
        }
    }

    fun dispose() {
        disposeMessagePanels(messagePanelCache.values.map { it.panel })
        messagePanelCache.clear()
        cs.cancel()
    }

    private fun disposeMessagePanels(panels: List<JPanel>) {
        panels.forEach { panel ->
            disposeCodeBlockPanels(panel)
        }
    }

    private fun disposeCodeBlockPanels(component: Component) {
        when (component) {
            is CodeBlockPanel -> component.disposeEditor()
            is Container -> component.components.forEach { child -> disposeCodeBlockPanels(child) }
        }
    }

    private fun extractUserContextMetadata(message: Message): UserContextMetadata? {
        logger.info {
            "[CONTEXT_BADGE] Extracting metadata from message ${message.id}: metadata=${
                message.metadata?.take(
                    200
                )
            }"
        }
        val result = UserContextMetadata.fromJson(message.metadata)
        logger.info { "[CONTEXT_BADGE] Parsed metadata: ${result != null}, refs=${result?.contextRefs?.size}" }
        return result
    }

    private fun createContextBadge(metadata: UserContextMetadata): JComponent {

        val card = FlatMessageBlock(LCATheme.systemBubbleBackground).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        val summaryText = metadata.contextSummary ?: "Added ${metadata.contextRefs.size} items"
        val summaryLabel = JLabel("📎 $summaryText").apply {
            font = LCATheme.smallFont
            foreground = LCATheme.descriptionForeground
        }
        headerPanel.add(summaryLabel, BorderLayout.WEST)

        val detailsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            isOpaque = false
        }

        metadata.contextRefs.forEachIndexed { index, ref ->
            // Add icon
            detailsPanel.add(JLabel(getContextIcon(ref)).apply {
                foreground = LCATheme.descriptionForeground
            })

            // Add clickable file name
            val displayName = ref.displayName.ifBlank { ref.path }
            val nameLabel = JLabel("<html><u>${displayName}</u></html>").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = LCATheme.accentColor
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        openContextReference(ref)
                    }
                })
            }
            detailsPanel.add(nameLabel)
        }

        card.add(headerPanel)
        card.add(detailsPanel)

        return card
    }

    private fun getContextIcon(ref: ContextReference): String = when (ref.type) {
        ContextType.FILE -> "📄"
        ContextType.FOLDER -> "📁"
        ContextType.SELECTION -> "✂️"
        ContextType.PROVIDER -> "🔌"
        ContextType.DOCS -> "📚"
        ContextType.RULES -> "📋"
        ContextType.OPEN -> "👁️"
    }

    private fun openContextReference(ref: ContextReference) {
        when (ref.type) {
            ContextType.FILE, ContextType.RULES, ContextType.OPEN -> openFileReference(ref.path)
            ContextType.FOLDER -> selectFolderReference(ref.path)
            ContextType.DOCS -> openDocsReference(ref.path)
            else -> {
                Notifications.Bus.notify(
                    Notification(
                        "Refio",
                        "Preview unavailable",
                        "Cannot open ${ref.type.name.lowercase()} references yet.",
                        NotificationType.INFORMATION
                    ),
                    project
                )
            }
        }
    }

    private fun openDocsReference(path: String?) {
        if (path.isNullOrBlank()) {
            Notifications.Bus.notify(
                Notification(
                    "Refio",
                    "Invalid URL",
                    "Documentation reference has no URL.",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }
        BrowserUtil.browse(path)
    }

    private fun openFileReference(path: String?) {
        val resolved = resolveAbsolutePath(path)
        if (resolved == null) {
            Notifications.Bus.notify(
                Notification(
                    "Refio",
                    "File not found",
                    "Cannot resolve path ${path ?: "(empty)"}",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolved)
        if (virtualFile == null) {
            Notifications.Bus.notify(
                Notification(
                    "Refio",
                    "File not found",
                    "Cannot open $resolved",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }

        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    private fun selectFolderReference(path: String?) {
        val resolved = resolveAbsolutePath(path)
        if (resolved == null) {
            Notifications.Bus.notify(
                Notification(
                    "Refio",
                    "Folder not found",
                    "Cannot resolve path ${path ?: "(empty)"}",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolved)
        if (virtualFile == null) {
            Notifications.Bus.notify(
                Notification(
                    "Refio",
                    "Folder not found",
                    "Cannot open $resolved",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }

        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project).select(null, virtualFile, false)
        }
    }

    private fun resolveAbsolutePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val candidate = Paths.get(path)
            if (candidate.isAbsolute) {
                candidate.normalize().toString()
            } else {
                val base = project.basePath ?: return null
                Paths.get(base, path).normalize().toString()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to resolve path for reference: $path" }
            null
        }
    }
}
