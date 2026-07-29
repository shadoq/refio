package pl.jclab.refio.ui.components.chat

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.StreamProgressFormat
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.AgentGrouping
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.MessageRenderHash
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.core.logging.dualLogger
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
import pl.jclab.refio.ui.components.chat.toolcall.ToolCallRow
import pl.jclab.refio.ui.components.chat.bubble.UserBubbleRenderer
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
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
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

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

    // IO, not Default: these collectors drive the live chat UI (message list, streaming char
    // counter). During a long tool stream the turn keeps the limited Default pool busy, which
    // starved the message/sample collectors here - the StateFlow kept emitting per-delta updates
    // but this scope never got scheduled to observe them, so the counter and bubble refresh froze
    // for the whole generation. The collector bodies are cheap (they marshal real work to the EDT
    // via invokeLater), so the elastic IO pool is the right home and cannot be starved by the turn.
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

            override fun isToolContentExpanded(messageId: String): Boolean {
                return toolContentSnapshotByMessageId.containsKey(messageId)
            }

            override fun getToolContentSnapshot(messageId: String): String? {
                return toolContentSnapshotByMessageId[messageId]
            }

            override fun setToolContentExpanded(messageId: String, snapshot: String?) {
                if (snapshot != null) {
                    toolContentSnapshotByMessageId[messageId] = snapshot
                } else {
                    toolContentSnapshotByMessageId.remove(messageId)
                }
            }

            override fun registerToolStreamCounter(messageId: String, label: JLabel) {
                streamingCounterLabels[messageId] = label
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

    private val toolCallRowCallbacks = object : ToolCallRow.Callbacks {
        override fun launch(block: suspend () -> Unit) {
            cs.launch { block() }
        }

        override suspend fun loadSnapshotContent(snapshotId: String, filePath: String): String? =
            sessionManager.apiRouter.snapshotRouter.getSnapshotFileContent(snapshotId, filePath)

        override fun isExpanded(messageId: String): Boolean =
            toolRowExpandedByMessageId.contains(messageId)

        override fun setExpanded(messageId: String, expanded: Boolean) {
            if (expanded) {
                toolRowExpandedByMessageId.add(messageId)
            } else {
                toolRowExpandedByMessageId.remove(messageId)
            }
        }

        override fun onHeightChanged() {
            revalidateMessagesArea()
        }

        override fun openPath(path: String) {
            fileNavigationService.openPathReference(path)
        }
    }

    private val toolBubbleRenderer by lazy(LazyThreadSafetyMode.NONE) {
        ToolBubbleRenderer(object : ToolBubbleRenderer.Context {
            override val project: Project = this@ChatView.project
            override val messages: List<Message>
                get() = sessionManager.messages.value
            override val bubbleContentContext: BaseBubbleRenderer.BubbleContentContext = this@ChatView.bubbleContentContext
            override val rowCallbacks: ToolCallRow.Callbacks = toolCallRowCallbacks
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

    private val toolApprovalPanel: ToolApprovalPanel
    private val busyIndicatorPanel: JPanel
    private val busyIndicatorLabel: JLabel
    private val toolCallProgressLabel: JLabel
    private val toolCallProgressPanel: JPanel
    private val busyIndicatorFrames = arrayOf("|", " ")
    private var busyIndicatorFrame = 0
    private var busyIndicatorTimer: Timer? = null
    private val thinkingExpandedByMessageId = mutableMapOf<String, Boolean>()

    // Per-message frozen snapshot of streamed tool content (presence == expanded). Captured at the
    // moment the user expands so the preview does not refresh as later chunks grow message.content.
    private val toolContentSnapshotByMessageId = mutableMapOf<String, String>()

    // Tool-call rows the user opened. Kept outside the row components so a transcript re-render
    // (new message, streaming update) does not silently collapse what the user was reading.
    private val toolRowExpandedByMessageId = mutableSetOf<String>()

    // Live "N chars" labels of currently-streaming tool bubbles, keyed by message id. While a tool
    // streams, only this label's text changes between chunks, so we patch it in place instead of
    // rebuilding the whole bubble - which would flicker and jump the layout several times a second.
    private val streamingCounterLabels = mutableMapOf<String, JLabel>()

    // Cache for rendered message panels to avoid recreating on every StateFlow update.
    // nonContentHash is the message hash excluding `content`, used to detect the streaming
    // counter-only fast path (everything identical except the growing generated content).
    private data class CachedMessagePanel(
        val contentHash: Int,
        val panel: JPanel,
        val nonContentHash: Int = 0,
        // Whether the message carried non-blank content when this panel was built. A code-editing
        // tool bubble only adds its "Generated content" affordance on a full render where content is
        // already present; if it was first rendered blank, the streaming counter fast-path must force
        // one more full render on the first non-blank chunk so that affordance appears (otherwise it
        // shows up only after a manual resize). See tryPatchStreamingCounter.
        val renderedWithContent: Boolean = false
    )

    private val messagePanelCache = mutableMapOf<String, CachedMessagePanel>()
    // Per-message "show the Agent: <name> header" decision for the CURRENT render, derived as a pure
    // function of the whole transcript (AgentGrouping) and refreshed at the top of updateMessages.
    // Folded into the render hash so a message whose header status flips (because a neighbour changed)
    // has its cached bubble rebuilt. EDT-confined: written and read only inside the render pipeline.
    private var agentHeaderById: Map<String, Boolean> = emptyMap()
    private var lastRenderedMessageIds = emptyList<String>()
    @Volatile
    private var lastReceivedMessages = emptyList<Message>()
    // Throttle, not a debounce: the render pipeline draws the first update immediately and then at
    // most once per this interval. A debounce would wait for the message stream to fall quiet, which
    // never happens while a tool generates, so the live char counter would stay frozen for the whole
    // generation - the bug this replaced.
    @Suppress("MagicNumber")
    private val uiUpdateThrottleMs = 300L

    // Debounces componentResized: during a drag-resize the event fires many times
    // per second, and each width change used to invalidate the whole bubble cache
    // and rebuild every bubble on the EDT. This timer waits until the resize
    // settles and then invalidates + rebuilds once. Fires on the EDT.
    @Suppress("MagicNumber")
    private val resizeDebounceTimer = Timer(300) {
        if (refreshAvailableWidth()) {
            invalidateMessageCacheForWidthChange()
            val messages = sessionManager.messages.value
            if (messages.isNotEmpty()) {
                updateMessages(messages)
            }
        }
    }.apply { isRepeats = false }

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

        toolApprovalPanel = ToolApprovalPanel(sessionManager.toolApprovalService, project)

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

        // Transient "model is building a native tool call" indicator. Visible only while
        // sessionManager.toolCallProgress is non-null (i.e. during native tool-call arg streaming).
        toolCallProgressLabel = JLabel("").apply {
            font = LCATheme.smallFont.deriveFont(Font.ITALIC)
            foreground = LCATheme.descriptionForeground
        }
        toolCallProgressPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 6, 0)
            border = LCATheme.paddedBorder(2, 8)
            isOpaque = false
            isVisible = false
            add(toolCallProgressLabel)
        }

        val busyAndToolCallPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(toolCallProgressPanel)
            add(busyIndicatorPanel)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolApprovalPanel, BorderLayout.NORTH)
            add(busyAndToolCallPanel, BorderLayout.SOUTH)
        }
        add(southPanel, BorderLayout.SOUTH)

        cs.launch {
            var previousTaskId: String? = null
            var previousTokenSig: Triple<Int, Int, Double>? = null
            sessionManager.activeSession.collect { session ->
                logger.info { "Received session update: mode=${session?.mode}" }
                val newTaskId = session?.id
                if (newTaskId != previousTaskId) {
                    previousTaskId = newTaskId
                    previousTokenSig = null
                    // Session switch: clear the current transcript immediately.
                    // The messages StateFlow may still momentarily hold the previous
                    // session's data, so rendering that snapshot here can leave the
                    // old chat visible after "New Session" is clicked.
                    lastReceivedMessages = emptyList()
                    SwingUtilities.invokeLater {
                        updateMessages(emptyList())
                    }
                } else if (session != null) {
                    // Same session, but task-row tokens/cost changed (e.g. auto-naming
                    // ran after the turn ended and bumped task.tokensIn/Out via the
                    // LLMClient centralization). Force a stats-bar re-render so the
                    // footer matches the header — without this, session token updates
                    // bypass the messages collector and the bar stays stale.
                    val sig = Triple(session.tokensIn, session.tokensOut, session.costUsd)
                    if (sig != previousTokenSig) {
                        previousTokenSig = sig
                        scheduleMessagesUpdate(lastReceivedMessages)
                    }
                }
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

        // Observe native tool-call streaming progress (Variant C).
        // Non-null only while the model streams a tool call's arguments; reset to null when done.
        cs.launch {
            sessionManager.toolCallProgress.collect { progress ->
                SwingUtilities.invokeLater {
                    updateToolCallProgress(progress)
                }
            }
        }

        showEmptyState()
        loadFormatMarkdownSetting()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                // Restart the debounce timer; the actual cache invalidation and
                // rebuild happen once, after the resize settles.
                resizeDebounceTimer.restart()
            }
        })

        // Single, non-lossy render pipeline. The previous two-stage hop (StateFlow -> tryEmit into a
        // MutableSharedFlow(extraBufferCapacity = 1) -> sample) could silently DROP updates: tryEmit
        // returns false as soon as that one buffer slot is taken, and when the dropped emission was
        // the last of a burst no sample tick ever followed, so the final state was never rendered -
        // bubbles stayed invisible until an unrelated event (a tool-window resize, which calls
        // updateMessages directly) rebuilt them.
        //
        // Collecting the StateFlow directly cannot lose the newest value: a StateFlow is inherently
        // conflated, so a slow collector skips intermediate values but is always resumed with the
        // latest one. The trailing delay throttles bursts (token streaming) to at most one rebuild per
        // uiUpdateThrottleMs while that conflation guarantees the final frame still lands - so the live
        // char counter keeps ticking during a long tool stream and the finished state always renders.
        cs.launch {
            sessionManager.messages
                .collect { messages ->
                    scheduleMessagesUpdate(messages)
                    delay(uiUpdateThrottleMs)
                }
        }

        // Observe tool approval requests
        cs.launch {
            sessionManager.toolApprovalService.pendingRequests.collect { requests ->
                SwingUtilities.invokeLater {
                    val first = requests.firstOrNull()
                    if (first != null) {
                        toolApprovalPanel.showRequest(first)
                    } else {
                        toolApprovalPanel.hidePanel()
                    }
                }
            }
        }
    }

    private fun showEmptyState() {
        messagesPanel.removeAll()
        messagesPanel.layout = GridBagLayout()
        revalidateMessagesArea()
    }

    /**
     * Revalidate the whole scroll chain, not just messagesPanel. messagesPanel sits inside the
     * JViewport of the enclosing JBScrollPane; a plain messagesPanel.revalidate() stops at that
     * JViewport validate-root, so the scroll pane never re-runs its ScrollPaneLayout - newly added
     * or grown bubbles stay clipped until an unrelated event (a window resize) validates the scroll
     * pane. Revalidating the scroll pane itself re-lays-out the viewport view and its scrollbars, so
     * structural changes and live streaming updates become visible immediately instead of on resize.
     */
    private fun revalidateMessagesArea() {
        val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, messagesPanel) as? JScrollPane
        if (scroll != null) {
            scroll.revalidate()
            scroll.repaint()
        } else {
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
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

    @Suppress("MagicNumber")
    private fun updateToolCallProgress(progress: pl.jclab.refio.core.api.ToolCallProgress?) {
        if (progress == null) {
            toolCallProgressLabel.text = ""
            toolCallProgressPanel.isVisible = false
        } else {
            val name = progress.name ?: "tool"
            val args = progress.accumulatedArguments
            val truncatedArgs = if (args.length > 80) args.take(80) + "…" else args
            toolCallProgressLabel.text = "⚙ $name($truncatedArgs)"
            toolCallProgressPanel.isVisible = true
        }

        toolCallProgressPanel.revalidate()
        toolCallProgressPanel.repaint()
    }

    // Render hashing lives in :core (MessageRenderHash) so it is unit-testable without a sandbox
    // IDE. It covers isStreaming + agent identity on top of the visible fields: without isStreaming
    // a finished stream (same content, isStreaming flipped false) kept a stale "Generating..." bubble.
    // The neighbour-derived agent-header decision is folded in here so a header flip rebuilds the bubble.
    private fun calculateMessageHash(message: Message): Int =
        withAgentHeader(MessageRenderHash.content(message), message)

    // Same as [calculateMessageHash] but without `content`. When this is unchanged between two
    // snapshots of a streaming tool message, the only difference is the growing generated content,
    // so the bubble can be left intact and only its char counter patched in place.
    private fun calculateNonContentMessageHash(message: Message): Int =
        withAgentHeader(MessageRenderHash.nonContent(message), message)

    private fun withAgentHeader(base: Int, message: Message): Int =
        31 * base + (agentHeaderById[message.id] == true).hashCode()

    private fun updateMessages(messages: List<Message>) {
        SwingUtilities.invokeLater {
            if (refreshAvailableWidth()) {
                invalidateMessageCacheForWidthChange()
            }

            val uniqueMessages = messages.distinctBy { it.id }
            val currentMessageIds = uniqueMessages.map { it.id }

            // Recompute the agent-header decisions for this exact ordering before any hashing, so the
            // hash (and thus the cache) reflects each bubble's current header status.
            agentHeaderById = currentMessageIds.zip(AgentGrouping.showHeaderFlags(uniqueMessages)).toMap()

            val hasStructuralChange = currentMessageIds != lastRenderedMessageIds
            val hasContentChange = uniqueMessages.any { msg ->
                val cached = messagePanelCache[msg.id]
                cached == null || cached.contentHash != calculateMessageHash(msg)
            }

            if (!hasStructuralChange && !hasContentChange) {
                return@invokeLater
            }

            if (uniqueMessages.isEmpty()) {
                disposeMessagePanels(messagePanelCache.values.map { it.panel })
                messagePanelCache.clear()
                streamingCounterLabels.clear()
                lastRenderedMessageIds = emptyList()
                showEmptyState()
                firePropertyChange("messagesUpdated", false, true)
                return@invokeLater
            }

            if (hasStructuralChange) {
                rebuildMessagesPanel(uniqueMessages)
            } else {
                updateMessagesInPlace(uniqueMessages)
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
            streamingCounterLabels.keys.removeAll { it !in currentIds }
            lastRenderedMessageIds = currentMessageIds

            revalidateMessagesArea()

            firePropertyChange("messagesUpdated", false, true)
        }
    }

    private fun rebuildMessagesPanel(messages: List<Message>) {
        val previousIds = lastRenderedMessageIds
        val newIds = messages.map { it.id }

        // If the new list is an append-only change (common case: new messages added at the end),
        // we can skip removing and re-adding existing messages.
        val isAppendOnly = previousIds.size <= newIds.size &&
            previousIds == newIds.subList(0, previousIds.size)

        if (isAppendOnly && previousIds.isNotEmpty()) {
            // Remove trailing toolbar and glue (last 2 components)
            val componentCount = messagesPanel.componentCount
            if (componentCount >= 2) {
                messagesPanel.remove(componentCount - 1) // glue
                messagesPanel.remove(componentCount - 2) // toolbar
            }

            // Update constraints on previously-last message if it exists
            if (previousIds.isNotEmpty()) {
                val prevLastIndex = previousIds.size - 1
                val prevLastComponent = messagesPanel.getComponent(prevLastIndex)
                val layout = messagesPanel.layout as GridBagLayout
                layout.setConstraints(
                    prevLastComponent,
                    createMessageConstraints(prevLastIndex, messages.lastIndex)
                )
            }

            // Add only new messages
            for (index in previousIds.size..messages.lastIndex) {
                val bubble = resolveBubble(messages[index])
                messagesPanel.add(bubble, createMessageConstraints(index, messages.lastIndex))
            }
        } else {
            // Full rebuild when message order changed or messages were removed.
            // Hide panel during rebuild to prevent flash of empty/intermediate state.
            messagesPanel.isVisible = false
            messagesPanel.removeAll()
            messagesPanel.layout = GridBagLayout()

            messages.forEachIndexed { index, message ->
                val bubble = resolveBubble(message)
                messagesPanel.add(bubble, createMessageConstraints(index, messages.lastIndex))
            }
            messagesPanel.isVisible = true
        }

        val toolbarRow = messages.size
        val toolbarWithStats = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(toolbarFactory.createConversationToolbar())
            add(SessionStatsBar.create(messages, sessionManager.activeSession.value))
        }
        messagesPanel.add(
            toolbarWithStats,
            GridBagConstraints().apply {
                gridx = 0
                gridy = toolbarRow
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                insets = Insets(TOOLBAR_TOP_GAP, 0, 0, 0)
            }
        )
        messagesPanel.add(
            Box.createVerticalGlue(),
            GridBagConstraints().apply {
                gridx = 0
                gridy = toolbarRow + 1
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
                anchor = GridBagConstraints.NORTH
                insets = Insets(0, 0, 0, 0)
            }
        )
    }

    private fun updateMessagesInPlace(messages: List<Message>) {
        if (messages.any { messagePanelCache[it.id] == null }) {
            rebuildMessagesPanel(messages)
            return
        }

        messages.forEachIndexed { index, message ->
            val contentHash = calculateMessageHash(message)
            val cached = messagePanelCache[message.id] ?: return@forEachIndexed
            if (cached.contentHash == contentHash) {
                return@forEachIndexed
            }

            if (tryPatchStreamingCounter(message, cached, contentHash)) {
                return@forEachIndexed
            }

            val newBubble = renderAndCache(message)
            val componentIndex = index.coerceAtMost(messagesPanel.componentCount - 1)
            messagesPanel.remove(componentIndex)
            messagesPanel.add(newBubble, createMessageConstraints(index, messages.lastIndex), componentIndex)
            disposeMessagePanels(listOf(cached.panel))
        }
    }

    /**
     * Streaming fast path: when a tool is still generating and the only thing that changed since the
     * last render is its growing content, patch just the live char counter and keep the existing
     * bubble. Avoids the full remove/add/render cycle that otherwise flickers and jumps the layout
     * several times a second while a code-editing tool streams. Returns true when handled.
     */
    private fun tryPatchStreamingCounter(message: Message, cached: CachedMessagePanel, contentHash: Int): Boolean {
        val stillStreaming = message.isToolStreaming &&
            message.toolCallInfo?.status == ToolCallStatus.EXECUTING
        if (!stillStreaming) return false
        val counterLabel = streamingCounterLabels[message.id] ?: return false
        if (cached.nonContentHash != calculateNonContentMessageHash(message)) return false
        // First non-blank chunk after a blank first render: fall back to a full render so the
        // "Generated content" affordance is actually added (patching only the counter would leave it
        // missing until a resize forced a relayout).
        if (!cached.renderedWithContent && message.content.isNotBlank()) return false

        counterLabel.text = StreamProgressFormat.counterSuffix(message.content.length)
        messagePanelCache[message.id] = cached.copy(contentHash = contentHash)
        counterLabel.revalidate()
        counterLabel.repaint()
        return true
    }

    private fun resolveBubble(message: Message): JPanel {
        val contentHash = calculateMessageHash(message)
        val cached = messagePanelCache[message.id]
        if (cached != null && cached.contentHash == contentHash) {
            return cached.panel
        }
        return renderAndCache(message)
    }

    // Render a bubble and store it in the cache together with both hashes. Clears any stale
    // streaming-counter label first; the render re-registers a fresh one only if the message is
    // still streaming (via [BubbleComponentDependencies.registerToolStreamCounter]).
    private fun renderAndCache(message: Message): JPanel {
        streamingCounterLabels.remove(message.id)
        val panel = messageBubbleRouter.render(message, agentHeaderById[message.id] == true)
        messagePanelCache[message.id] = CachedMessagePanel(
            contentHash = calculateMessageHash(message),
            panel = panel,
            nonContentHash = calculateNonContentMessageHash(message),
            renderedWithContent = message.content.isNotBlank()
        )
        return panel
    }

    private fun createMessageConstraints(index: Int, lastIndex: Int): GridBagConstraints {
        val isLastMessage = index == lastIndex
        return GridBagConstraints().apply {
            gridx = 0
            gridy = index
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
            insets = Insets(0, 0, if (isLastMessage) 0 else MESSAGE_VERTICAL_GAP, 0)
        }
    }

    private fun scheduleMessagesUpdate(messages: List<Message>) {
        lastReceivedMessages = messages
        updateMessages(messages)
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
                val config = sessionManager.apiRouter.configRouter.getConfig("general", "app")
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
        resizeDebounceTimer.stop()
        disposeMessagePanels(messagePanelCache.values.map { it.panel })
        messagePanelCache.clear()
        cs.cancel()
    }

    fun setContinuePromptHandler(handler: () -> Unit) {
        onContinueRequested = handler
    }

    fun clearForNewSession() {
        lastReceivedMessages = emptyList()
        disposeMessagePanels(messagePanelCache.values.map { it.panel })
        messagePanelCache.clear()
        streamingCounterLabels.clear()
        lastRenderedMessageIds = emptyList()
        showEmptyState()
        firePropertyChange("messagesUpdated", false, true)
    }

    private fun disposeMessagePanels(panels: List<JPanel>) {
        panels.forEach { panel ->
            disposeCodeBlockPanels(panel)
        }
    }

    private fun disposeCodeBlockPanels(component: Component) {
        when (component) {
            is CodeBlockPanel -> component.disposeEditor()
            is ToolCallRow -> component.dispose()
            is java.awt.Container -> component.components.forEach { child -> disposeCodeBlockPanels(child) }
        }
    }
}
