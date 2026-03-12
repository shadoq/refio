package pl.jclab.refio.ui.components.chat

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.chat.bubble.AssistantBubbleRenderer
import pl.jclab.refio.ui.components.chat.bubble.BaseBubbleRenderer
import pl.jclab.refio.ui.components.chat.bubble.BubbleComponentDependencies
import pl.jclab.refio.ui.components.chat.bubble.BubbleComponentFactory
import pl.jclab.refio.ui.components.chat.bubble.ChatMessageBubbleRouter
import pl.jclab.refio.ui.components.chat.bubble.FlatMessageBlock
import pl.jclab.refio.ui.components.chat.bubble.MarkdownRenderingService
import pl.jclab.refio.ui.components.chat.bubble.OtherBubbleRenderer
import pl.jclab.refio.ui.components.chat.bubble.ToolBubbleRenderer
import pl.jclab.refio.ui.components.chat.bubble.UserBubbleRenderer
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.util.Objects

/**
 * Custom JLabel with rounded corners for mode badge
 * Matching landing page design (.tool-banner style)
 */
private class RoundedModeBadge : JLabel() {
    init {
        horizontalAlignment = SwingConstants.CENTER
        border = LCATheme.paddedBorder(6, 12)
        font = LCATheme.smallBoldFont
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val radius = 8.0
        val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), radius, radius)

        g2.color = background
        g2.fill(shape)

        g2.color = LCATheme.subtleSeparatorColor
        g2.stroke = java.awt.BasicStroke(1f)
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
    private val BUBBLE_COMPACT_GAP = 4
    private val BUBBLE_LARGE_GAP = 8
    private val MESSAGE_VERTICAL_GAP = 6
    private val TOOLBAR_TOP_GAP = 10
    private val SCROLL_BAR_AND_PADDING = 0

    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val globalMetrics = GlobalMetrics
    private val logger = dualLogger("ChatView")
    @Volatile
    private var onContinueRequested: (() -> Unit)? = null

    // Format markdown setting (loaded from config)
    @Volatile
    private var formatMarkdownEnabled: Boolean = true

    // Extracted services
    private val fileNavigationService = FileNavigationService(project)

    private val markdownService = MarkdownRenderingService(
        project = project,
        formatMarkdownEnabledProvider = { formatMarkdownEnabled },
        onFilePathClicked = { filePath -> fileNavigationService.showFileChangesDialog(filePath) }
    )

    private val bubbleRenderSupport = object : BaseBubbleRenderer() {}

    private val bubbleContentContext: BaseBubbleRenderer.BubbleContentContext = object : BaseBubbleRenderer.BubbleContentContext {
        override val project: Project = this@ChatView.project
        override val availableWidth: Int get() = this@ChatView.availableWidth
        override val scrollBarAndPadding: Int = SCROLL_BAR_AND_PADDING
        override val bubbleCompactGap: Int = BUBBLE_COMPACT_GAP
        override val bubbleLargeGap: Int = BUBBLE_LARGE_GAP
        override val defaultSpace = DEFAULT_SPACE
        override val componentFactory: BubbleComponentFactory get() = this@ChatView.componentFactory
        override val markdownService: MarkdownRenderingService = this@ChatView.markdownService

        override fun createMessageBlock(backgroundColor: Color): JPanel {
            return FlatMessageBlock(backgroundColor)
        }
    }

    private val componentFactory = BubbleComponentFactory(
        deps = object : BubbleComponentDependencies {
            override val project: Project = this@ChatView.project
            override val availableWidthProvider: () -> Int = { this@ChatView.availableWidth }
            override val scrollBarAndPadding: Int = SCROLL_BAR_AND_PADDING
            override val markdownService: MarkdownRenderingService = this@ChatView.markdownService

            override fun openFileReference(path: String?) {
                fileNavigationService.openFileReference(path)
            }

            override fun openCodeChangesDiff(changes: CodeChangesData) {
                fileNavigationService.openCodeChangesDiff(changes)
            }

            override fun openContextReference(ref: pl.jclab.refio.api.models.ContextReference) {
                fileNavigationService.openContextReference(ref)
            }

            override fun copyToClipboard(text: String) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                showNotification("Copied", "Copied to clipboard", NotificationType.INFORMATION)
            }

            override fun showNotification(title: String, content: String, type: NotificationType) {
                this@ChatView.showNotification(title, content, type)
            }

            override fun launch(block: suspend () -> Unit) {
                cs.launch { block() }
            }

            override fun findPreviousUserMessage(fromMessageId: String): Message? {
                val messages = sessionManager.messages.value
                val index = messages.indexOfFirst { it.id == fromMessageId }
                if (index <= 0) return null
                return messages.subList(0, index).lastOrNull { it.role == "user" }
            }

            override suspend fun deleteMessage(messageId: String) {
                sessionManager.deleteChatMessage(messageId)
            }

            override suspend fun rewindAndResend(messageId: String, content: String) {
                sessionManager.rewindAndResendFromMessage(messageId, content)
            }

            override fun isThinkingExpanded(messageId: String): Boolean {
                return thinkingExpandedByMessageId[messageId] == true
            }

            override fun setThinkingExpanded(messageId: String, expanded: Boolean) {
                if (expanded) {
                    thinkingExpandedByMessageId[messageId] = true
                } else {
                    thinkingExpandedByMessageId.remove(messageId)
                }
            }
        },
        collapsibleCodePanelProvider = { content, language, filePath ->
            bubbleRenderSupport.createCollapsibleCodePanel(
                content = content,
                context = bubbleContentContext,
                language = language,
                filePath = filePath
            )
        }
    )

    private val toolbarFactory = ConversationToolbarFactory(
        project = project,
        sessionManager = sessionManager,
        scope = cs,
        parentComponent = this,
        onContinueRequested = { onContinueRequested?.invoke() }
    )

    private val userBubbleRenderer by lazy(LazyThreadSafetyMode.NONE) {
        UserBubbleRenderer(object : UserBubbleRenderer.Context {
            override val project: Project = this@ChatView.project
            override val bubbleCompactGap: Int = BUBBLE_COMPACT_GAP
            override val bubbleContentContext: BaseBubbleRenderer.BubbleContentContext = this@ChatView.bubbleContentContext

            override fun rewindAndResendFromMessage(messageId: String, newContent: String) {
                cs.launch {
                    try {
                        sessionManager.rewindAndResendFromMessage(messageId, newContent)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to rewind and resend from message $messageId" }
                        showNotification("Error", e.message ?: "Failed to rewind conversation", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private val assistantBubbleRenderer by lazy(LazyThreadSafetyMode.NONE) {
        AssistantBubbleRenderer(object : AssistantBubbleRenderer.Context {
            override val bubbleCompactGap: Int = BUBBLE_COMPACT_GAP
            override val bubbleContentContext: BaseBubbleRenderer.BubbleContentContext = this@ChatView.bubbleContentContext

            override fun isInteractiveMode(): Boolean {
                return sessionManager.activeSession.value?.executionMode == ExecutionMode.INTERACTIVE
            }

            override fun launch(block: suspend () -> Unit) {
                cs.launch { block() }
            }

            override suspend fun answerQuestion(questionId: String, answer: String) {
                sessionManager.answerQuestion(questionId, answer)
            }

            override suspend fun approveSubtask(subtaskId: String) {
                sessionManager.approveSubtask(subtaskId)
            }

            override suspend fun skipSubtask(subtaskId: String) {
                sessionManager.skipSubtask(subtaskId)
            }
        })
    }

    private val toolBubbleRenderer by lazy(LazyThreadSafetyMode.NONE) {
        ToolBubbleRenderer(object : ToolBubbleRenderer.Context {
            override val messages: List<Message>
                get() = sessionManager.messages.value
            override val bubbleContentContext: BaseBubbleRenderer.BubbleContentContext = this@ChatView.bubbleContentContext
        })
    }

    private val otherBubbleRenderer by lazy(LazyThreadSafetyMode.NONE) {
        OtherBubbleRenderer(object : OtherBubbleRenderer.Context {
            override val bubbleContentContext: BaseBubbleRenderer.BubbleContentContext = this@ChatView.bubbleContentContext
        })
    }

    private val messageBubbleRouter by lazy(LazyThreadSafetyMode.NONE) {
        ChatMessageBubbleRouter(
            userBubbleRenderer = userBubbleRenderer,
            assistantBubbleRenderer = assistantBubbleRenderer,
            toolBubbleRenderer = toolBubbleRenderer,
            otherBubbleRenderer = otherBubbleRenderer
        )
    }

    private val messagesPanel: JPanel

    // Width for bubble calculations (updated on resize)
    private var availableWidth: Int = 400

    private val busyIndicatorPanel: JPanel
    private val busyIndicatorLabel: JLabel
    private val busyIndicatorFrames = arrayOf("|", " ")
    private var busyIndicatorFrame = 0
    private var busyIndicatorTimer: Timer? = null
    private val thinkingExpandedByMessageId = mutableMapOf<String, Boolean>()

    // Cache for rendered message panels to avoid recreating on every StateFlow update
    private data class CachedMessagePanel(
        val contentHash: Int,
        val panel: JPanel
    )

    private val messagePanelCache = mutableMapOf<String, CachedMessagePanel>()
    private var lastRenderedMessageIds = emptyList<String>()
    private var lastReceivedMessages = emptyList<Message>()
    private var pendingMessages: List<Message>? = null
    private var updateMessagesTimer: Timer? = null
    private val uiUpdateDebounceMs = 200
    private var lastUiFlushAtMs = 0L

    private fun resolveAvailableWidth(): Int {
        val viewportWidth = (SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport)?.width ?: 0
        val baseWidth = when {
            viewportWidth > 0 -> viewportWidth
            width > 0 -> width
            messagesPanel.width > 0 -> messagesPanel.width
            else -> availableWidth
        }
        val horizontalInsets = insets.left + insets.right
        return (baseWidth - horizontalInsets).coerceAtLeast(200)
    }

    private fun refreshAvailableWidth(): Boolean {
        val newWidth = resolveAvailableWidth()
        if (newWidth == availableWidth) return false
        availableWidth = newWidth
        return true
    }

    private fun invalidateMessageCacheForWidthChange() {
        if (messagePanelCache.isNotEmpty()) {
            disposeMessagePanels(messagePanelCache.values.map { it.panel })
            messagePanelCache.clear()
            lastRenderedMessageIds = emptyList()
        }
    }

    init {
        messagesPanel = JPanel(GridBagLayout()).apply {
            background = LCATheme.backgroundColor
            border = null
        }

        add(messagesPanel, BorderLayout.CENTER)

        busyIndicatorLabel = JLabel("Working ${busyIndicatorFrames[0]}").apply {
            font = LCATheme.smallFont.deriveFont(Font.ITALIC)
            foreground = LCATheme.descriptionForeground
        }
        busyIndicatorPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 6, 0)
            border = LCATheme.paddedBorder(2, 8)
            isOpaque = false
            isVisible = false
            add(busyIndicatorLabel)
        }
        add(busyIndicatorPanel, BorderLayout.SOUTH)

        cs.launch {
            sessionManager.activeSession.collect { session ->
                logger.info { "Received session update: mode=${session?.mode}" }
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

        showEmptyState()
        loadFormatMarkdownSetting()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (refreshAvailableWidth()) {
                    invalidateMessageCacheForWidthChange()
                    val messages = sessionManager.messages.value
                    if (messages.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            updateMessages(messages)
                        }
                    }
                }
            }
        })

        cs.launch {
            sessionManager.messages.collect { messages ->
                scheduleMessagesUpdate(messages)
            }
        }
    }

    private fun showEmptyState() {
        messagesPanel.removeAll()
        messagesPanel.layout = GridBagLayout()
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

    private fun calculateMessageHash(message: Message): Int {
        return Objects.hash(
            message.content,
            message.isStreaming,
            message.lastChunkAt,
            message.metadata,
            message.toolCallInfo
        )
    }

    private fun updateMessages(messages: List<Message>) {
        SwingUtilities.invokeLater {
            if (refreshAvailableWidth()) {
                invalidateMessageCacheForWidthChange()
            }

            val uniqueMessages = messages.distinctBy { it.id }
            val currentMessageIds = uniqueMessages.map { it.id }

            val hasStructuralChange = currentMessageIds != lastRenderedMessageIds
            val hasContentChange = uniqueMessages.any { msg ->
                val cached = messagePanelCache[msg.id]
                cached == null || cached.contentHash != calculateMessageHash(msg)
            }

            if (!hasStructuralChange && !hasContentChange) {
                return@invokeLater
            }

            messagesPanel.removeAll()
            messagesPanel.layout = GridBagLayout()

            if (uniqueMessages.isEmpty()) {
                disposeMessagePanels(messagePanelCache.values.map { it.panel })
                messagePanelCache.clear()
                lastRenderedMessageIds = emptyList()
                showEmptyState()
                firePropertyChange("messagesUpdated", false, true)
                return@invokeLater
            }

            val gbc = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                insets = Insets(0, 0, 0, 0)
            }

            uniqueMessages.forEachIndexed { index, message ->
                gbc.gridy = index
                gbc.weighty = 0.0
                val isLastMessage = index == uniqueMessages.lastIndex
                gbc.insets = Insets(0, 0, if (isLastMessage) 0 else MESSAGE_VERTICAL_GAP, 0)

                val contentHash = calculateMessageHash(message)
                val cached = messagePanelCache[message.id]

                val bubble = if (cached != null && cached.contentHash == contentHash) {
                    cached.panel
                } else {
                    val newPanel = messageBubbleRouter.render(message)
                    messagePanelCache[message.id] = CachedMessagePanel(contentHash, newPanel)
                    newPanel
                }

                messagesPanel.add(bubble, gbc)
            }

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

            gbc.gridy = uniqueMessages.size
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = Insets(TOOLBAR_TOP_GAP, 0, 0, 0)
            messagesPanel.add(toolbarFactory.createConversationToolbar(), gbc)

            gbc.gridy = uniqueMessages.size + 1
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = Insets(0, 0, 0, 0)
            messagesPanel.add(Box.createVerticalGlue(), gbc)

            messagesPanel.revalidate()
            messagesPanel.repaint()

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

    private fun loadFormatMarkdownSetting() {
        cs.launch {
            try {
                val config = sessionManager.apiRouter.getConfig("general", "app")
                val enabled = config.settings["format_markdown"]?.toString()?.toBoolean() ?: true

                if (enabled != formatMarkdownEnabled) {
                    formatMarkdownEnabled = enabled
                    logger.info { "Format markdown setting loaded: $formatMarkdownEnabled" }

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

    fun setContinuePromptHandler(handler: () -> Unit) {
        onContinueRequested = handler
    }

    private fun disposeMessagePanels(panels: List<JPanel>) {
        panels.forEach { panel ->
            disposeCodeBlockPanels(panel)
        }
    }

    private fun disposeCodeBlockPanels(component: Component) {
        when (component) {
            is CodeBlockPanel -> component.disposeEditor()
            is java.awt.Container -> component.components.forEach { child -> disposeCodeBlockPanels(child) }
        }
    }
}
