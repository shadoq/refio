package pl.jclab.refio.ui.toolwindow

import com.intellij.openapi.Disposable
import pl.jclab.refio.core.logging.dualLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.ui.components.chat.ChatView
import pl.jclab.refio.ui.components.chat.PromptInputPanel
import pl.jclab.refio.ui.components.toolbar.StatusBar
import pl.jclab.refio.ui.components.steps.StepsQueueView
import pl.jclab.refio.ui.components.logs.LogsPanel
import pl.jclab.refio.ui.components.debug.DebugPanel
import pl.jclab.refio.ui.components.context.ContextPanel
import pl.jclab.refio.ui.components.history.HistoryPanel
import pl.jclab.refio.ui.components.rag.RagViewPanel
import com.intellij.ui.OnePixelSplitter
import pl.jclab.refio.ui.execution.NowRunningBar
import pl.jclab.refio.ui.execution.TimelinePanel
import pl.jclab.refio.ui.execution.TimelineSteps
import pl.jclab.refio.ui.RefioScreen
import pl.jclab.refio.ui.rail.RefioRail
import pl.jclab.refio.ui.settings.ApiLogsPanel
import pl.jclab.refio.ui.settings.SettingsView
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Content panel for Refio tool window — built on EDT after SessionManager has
 * been created on a background thread. See [RefioMainPanel] for the wrapper
 * that handles the async initialization flow.
 */
class RefioContentPanel(
    private val project: Project,
    private val sessionManager: SessionManager,
    private val stepExecutionService: StepExecutionService
) : JBPanel<RefioContentPanel>(BorderLayout()), Disposable {

    private val logger = dualLogger("RefioContentPanel")

    private val cs = CoroutineScope(SupervisorJob())
    private val coreApiClient: pl.jclab.refio.core.api.CoreApiRouter = sessionManager.apiRouter

    private val chatView: ChatView
    private val promptInputPanel: PromptInputPanel
    private val chatScrollPane: JBScrollPane
    private val chatSplitter: OnePixelSplitter
    private val timelinePanel: TimelinePanel
    private val statusBar: StatusBar

    private val stepsQueueView: StepsQueueView
    // Advanced tabs are created lazily on first entry into their tab (see LazyTab).
    // Fields stay null until instantiated, so dispose() must null-check each one.
    private var contextPanel: ContextPanel? = null
    private var logsPanel: LogsPanel? = null
    private var debugPanel: DebugPanel? = null
    private var apiLogsPanel: ApiLogsPanel? = null
    private val historyPanel: HistoryPanel
    private var ragViewPanel: RagViewPanel? = null

    private val nowRunningBar: NowRunningBar

    private val rail: RefioRail
    private val screenCards: JPanel
    private val screenCardLayout: CardLayout
    private val screenComponents: Map<RefioScreen, java.awt.Component>
    private var advancedViewEnabled = false
    private val middlePanel: JPanel
    private val cardLayout: CardLayout
    private val agentExecutionPanel: pl.jclab.refio.ui.components.agents.AgentExecutionPanel

    private var settingsView: SettingsView? = null
    private val chatMessagesUpdatedListener = PropertyChangeListener {
        scrollChatToBottom()
        refreshTimeline()
    }
    private val stopExecutionListener = PropertyChangeListener { evt ->
        if (evt.newValue == true) {
            stepExecutionService.stopExecution()
        }
    }
    private val historySessionLoadedListener = PropertyChangeListener { evt ->
        if (evt.newValue == true) {
            showChatView()
        }
    }
    private val historyBackToChatListener = PropertyChangeListener { evt ->
        if (evt.newValue == true) {
            showChatView()
        }
    }
    private val advancedViewChangedListener = PropertyChangeListener { evt ->
        if (evt.newValue is Boolean) {
            setAdvancedViewEnabled(evt.newValue as Boolean)
        }
    }

    init {

        logger.info { "Initialize main plugin content panel" }

        chatView = ChatView(project)
        promptInputPanel = PromptInputPanel(project, chatView, coreApiClient)
        chatView.setContinuePromptHandler {
            promptInputPanel.sendContinuePrompt()
        }
        statusBar = StatusBar(project)
        stepsQueueView = StepsQueueView(project)
        historyPanel = HistoryPanel(project, autoLoadOnInit = false)
        // nowRunningBar and agentExecutionPanel stay eager: both are updated by
        // session collectors below (turn-state stream / agent-event subscription) before
        // their screen is ever shown, so they must exist up front.
        nowRunningBar = NowRunningBar(this) { promptInputPanel.stopCurrentOperation() }
        agentExecutionPanel = pl.jclab.refio.ui.components.agents.AgentExecutionPanel()
        // Built here, before the turn-state collector below captures it, and mounted into the
        // splitter only once the layout reaches the wide band.
        timelinePanel = TimelinePanel(
            onStop = { promptInputPanel.stopCurrentOperation() },
            onStepSelected = { messageId -> chatView.scrollToMessage(messageId) }
        )

        cs.launch {
            var turnStateJob: kotlinx.coroutines.Job? = null
            sessionManager.activeSession.collect { _ ->
                turnStateJob?.cancel()
                val turnStateFlow = sessionManager.turnState
                if (turnStateFlow != null) {
                    turnStateJob = cs.launch {
                        turnStateFlow.collect { snapshot ->
                            SwingUtilities.invokeLater {
                                nowRunningBar.update(snapshot)
                                timelinePanel.setRunning(snapshot)
                            }
                        }
                    }
                }
            }
        }

        chatScrollPane = JBScrollPane(chatView).apply {
            border = null
            verticalScrollBarPolicy = javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            background = LCATheme.backgroundColor
            viewport.background = LCATheme.backgroundColor
        }

        chatView.addPropertyChangeListener("messagesUpdated", chatMessagesUpdatedListener)

        // Proportion is remembered under this key across IDE restarts (handoff 07B).
        chatSplitter = OnePixelSplitter(false, "reflo.chat.timeline", CHAT_TIMELINE_PROPORTION).apply {
            firstComponent = chatScrollPane
            secondComponent = null
        }

        val chatPanel = JPanel(BorderLayout()).apply {
            background = LCATheme.backgroundColor
            add(nowRunningBar, BorderLayout.NORTH)
            add(chatSplitter, BorderLayout.CENTER)
            add(promptInputPanel, BorderLayout.SOUTH)
        }

        val stepsPanel = JPanel(BorderLayout()).apply {
            add(stepsQueueView, BorderLayout.CENTER)
        }

        // Each heavy advanced screen is wrapped in a LazyTab and only built the first time
        // it is shown. agentExecutionPanel is added directly because it must stay eager.
        screenComponents = mapOf(
            RefioScreen.CHAT to chatPanel,
            RefioScreen.EXECUTION to stepsPanel,
            RefioScreen.CONTEXT to LazyTab({ ContextPanel(project).also { contextPanel = it } }),
            RefioScreen.AGENTS to agentExecutionPanel,
            RefioScreen.RAG to LazyTab({ RagViewPanel(project).also { ragViewPanel = it } }),
            RefioScreen.DEBUG to LazyTab({
                DebugPanel(project).also {
                    debugPanel = it
                    it.agentTraceProvider = { agentExecutionPanel.toText() }
                }
            }),
            RefioScreen.LOGS to LazyTab({ LogsPanel(project).also { logsPanel = it } }),
            RefioScreen.API to LazyTab(
                create = { ApiLogsPanel(coreApiClient, autoLoadOnInit = false).also { apiLogsPanel = it } },
                onShow = { apiLogsPanel?.ensureLoaded() }
            )
        )

        screenCardLayout = CardLayout()
        screenCards = JPanel(screenCardLayout).apply {
            screenComponents.forEach { (screen, component) -> add(component, screen.name) }
        }

        rail = RefioRail({ RefioScreen.visibleFor(advancedViewEnabled) }) { showScreen(it) }

        val normalContentPanel = JPanel(BorderLayout()).apply {
            add(rail, BorderLayout.WEST)
            add(screenCards, BorderLayout.CENTER)
            border = LCATheme.paddedBorder(0, 0, 2, 0)
        }

        cardLayout = CardLayout()
        middlePanel = JPanel(cardLayout).apply {
            add(normalContentPanel, "NORMAL")
            add(historyPanel, "HISTORY")
        }

        add(middlePanel, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        installResponsiveness()

        cs.launch {
            try {
                val config = coreApiClient.configRouter.getConfig(section = "general", scope = "app")
                val advancedView = (config.settings["advanced_view"] as? String).toBoolean()

                javax.swing.SwingUtilities.invokeLater {
                    setAdvancedViewEnabled(advancedView)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load advanced view setting, using default: false" }
                javax.swing.SwingUtilities.invokeLater {
                    setAdvancedViewEnabled(false)
                }
            }
        }

        sessionManager.setStatusBar(statusBar)

        statusBar.addPropertyChangeListener("stopExecution", stopExecutionListener)

        historyPanel.onNavigateToChat = { showChatView() }

        historyPanel.addPropertyChangeListener("sessionLoaded", historySessionLoadedListener)

        historyPanel.addPropertyChangeListener("backToChat", historyBackToChatListener)

        cs.launch {
            sessionManager.activeSession.collect { session ->
                session?.let { updateStepsQueueVisibility(it.mode) }
                session?.let {
                    agentExecutionPanel.subscribeToSession(sessionManager.apiRouter.agentEventBus, it.id)
                }
            }
        }

        cs.launch {
            sessionManager.subtasks.collect {
                sessionManager.activeSession.value?.let { session ->
                    updateStepsQueueVisibility(session.mode)
                }
            }
        }
    }

    fun cycleMode() {
        promptInputPanel.cycleMode()
    }

    fun createNewSession() {
        logger.info { "Creating new session" }

        cs.launch {
            try {
                sessionManager.cancelStreaming()
                sessionManager.cancelExecution()
                stepExecutionService.stopExecution()
                logger.info { "Canceled running operations before creating new session" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cancel operations (may not be running)" }
            }

            val currentMode = promptInputPanel.getSelectedMode()
            val executionMode = promptInputPanel.getCurrentExecutionMode()
            sessionManager.createSession("Session (${currentMode.name})", currentMode, executionMode)

            SwingUtilities.invokeLater {
                chatView.clearForNewSession()
                showChatView()
            }
        }
    }

    fun showHistory() {
        toggleHistoryPanel()
    }

    fun showSettings() {
        openSettings()
    }

    fun showHelp() {
        logger.info { "Show help requested" }
        com.intellij.ide.BrowserUtil.browse("https://github.com/shadoq/refio/blob/main/docs/overview.md")
    }

    private fun scrollChatToBottom() {
        javax.swing.SwingUtilities.invokeLater {
            chatScrollPane.validate()
            val scrollBar = chatScrollPane.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        }
    }

    private fun openSettings() {
        logger.info { "Opening settings view" }

        if (settingsView == null) {
            settingsView = SettingsView(project, coreApiClient) {
                closeSettings()
            }

            settingsView!!.addPropertyChangeListener("advancedViewChanged", advancedViewChangedListener)

            middlePanel.add(settingsView, "SETTINGS")
        }

        cardLayout.show(middlePanel, "SETTINGS")

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    private fun closeSettings() {
        logger.info { "Closing settings view" }

        cardLayout.show(middlePanel, "NORMAL")

        openScreen(RefioScreen.CHAT)

        middlePanel.revalidate()
        middlePanel.repaint()

        promptInputPanel.refreshModels()
    }

    private fun toggleHistoryPanel() {
        logger.info { "Toggling history panel" }

        cardLayout.show(middlePanel, "HISTORY")

        historyPanel.showHistory()

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    private fun showChatView() {
        logger.info { "Showing Chat view after new session creation" }

        cardLayout.show(middlePanel, "NORMAL")

        openScreen(RefioScreen.CHAT)

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    private fun updateStepsQueueVisibility(@Suppress("UNUSED_PARAMETER") mode: TaskMode) {
    }

    /**
     * Width bands the panel adapts to. Docked at 300 px and undocked at 1200 px are the same
     * component, so the layout is chosen by band rather than by pixel.
     */
    private enum class Width { NARROW, NORMAL, WIDE }

    private var currentWidth: Width? = null

    private fun widthClassOf(px: Int): Width = when {
        px < JBUI.scale(360) -> Width.NARROW
        px < JBUI.scale(560) -> Width.NORMAL
        else -> Width.WIDE
    }

    /**
     * One listener on the root, not one per component: rebuilding on every pixel of a drag was
     * what made resizing stutter, so the layout is only reapplied when the band actually changes.
     */
    private fun installResponsiveness() {
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val next = widthClassOf(width)
                if (next == currentWidth) return
                currentWidth = next
                applyWidthClass(next)
            }
        })
    }

    private fun applyWidthClass(width: Width) {
        logger.debug { "Applying width class: $width" }
        rail.setCompact(width == Width.NARROW)
        statusBar.setLevel(
            if (width == Width.NARROW) StatusBar.Level.MINIMAL else StatusBar.Level.NORMAL
        )
        promptInputPanel.setSendCompact(width == Width.NARROW)
        setTimelineVisible(width == Width.WIDE)
    }

    /**
     * The timeline column and the "now running" bar carry the same information, so exactly one of
     * them is on screen: the column where there is width for it, the bar everywhere else.
     */
    private fun setTimelineVisible(visible: Boolean) {
        if (visible == (chatSplitter.secondComponent != null)) return
        chatSplitter.secondComponent = if (visible) timelinePanel else null
        nowRunningBar.setSuppressed(visible)
        if (visible) {
            refreshTimeline()
        }
    }

    /** Rebuilds the timeline rows from the transcript the chat view currently holds. */
    private fun refreshTimeline() {
        timelinePanel.setSteps(TimelineSteps.from(chatView.currentMessages()))
    }

    /** Switches the rail and the card stack to [screen]. Safe to call from anywhere on EDT. */
    fun openScreen(screen: RefioScreen) {
        if (rail.selected == screen) {
            showScreen(screen)
        } else {
            rail.select(screen)
        }
    }

    private fun showScreen(screen: RefioScreen) {
        screenCardLayout.show(screenCards, screen.name)
        (screenComponents[screen] as? LazyTab)?.ensureShown()
    }

    fun setAdvancedViewEnabled(enabled: Boolean) {
        logger.info { "Setting advanced view: $enabled" }

        javax.swing.SwingUtilities.invokeLater {
            if (advancedViewEnabled == enabled) return@invokeLater
            advancedViewEnabled = enabled
            // Leaving advanced mode while an advanced screen is open would strand the user on a
            // screen with no rail button, so fall back to Chat.
            if (!enabled && rail.selected.advancedOnly) {
                rail.select(RefioScreen.CHAT)
            }
            rail.refresh()
        }
    }

    override fun dispose() {
        cs.cancel()
        chatView.removePropertyChangeListener("messagesUpdated", chatMessagesUpdatedListener)
        statusBar.removePropertyChangeListener("stopExecution", stopExecutionListener)
        historyPanel.removePropertyChangeListener("sessionLoaded", historySessionLoadedListener)
        historyPanel.removePropertyChangeListener("backToChat", historyBackToChatListener)
        settingsView?.removePropertyChangeListener("advancedViewChanged", advancedViewChangedListener)
        settingsView?.dispose()
        chatView.dispose()
        stepsQueueView.dispose()
        historyPanel.dispose()
        nowRunningBar.dispose()
        // Lazy advanced panels: dispose only the ones actually instantiated.
        contextPanel?.dispose()
        promptInputPanel.dispose()
        statusBar.dispose()
        logsPanel?.dispose()
        debugPanel?.dispose()
    }

    /**
     * A lightweight tab placeholder that builds its real content the first time the tab is
     * shown, then caches it. Keeps tool-window startup cheap by deferring heavy panel creation.
     */
    private inner class LazyTab(
        private val create: () -> java.awt.Component,
        private val onShow: (() -> Unit)? = null
    ) : JPanel(BorderLayout()) {

        private var component: java.awt.Component? = null

        init {
            background = LCATheme.backgroundColor
            isOpaque = true
        }

        fun ensureShown() {
            if (component == null) {
                val created = create()
                component = created
                add(created, BorderLayout.CENTER)
                revalidate()
                repaint()
            }
            onShow?.invoke()
        }
    }

    private companion object {
        /** Transcript keeps the bulk of the width; the timeline is a margin column, not a second pane. */
        const val CHAT_TIMELINE_PROPORTION = 0.72f
    }
}
