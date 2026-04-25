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
import pl.jclab.refio.ui.execution.TurnStateStatusBar
import pl.jclab.refio.ui.settings.ApiLogsPanel
import pl.jclab.refio.ui.settings.SettingsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import javax.swing.JTabbedPane
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
    private val statusBar: StatusBar

    private val stepsQueueView: StepsQueueView
    private val contextPanel: ContextPanel
    private val logsPanel: LogsPanel
    private val debugPanel: DebugPanel
    private val apiLogsPanel: ApiLogsPanel
    private val historyPanel: HistoryPanel
    private val ragViewPanel: RagViewPanel

    private val turnStateStatusBar: TurnStateStatusBar

    private val tabbedPane: JTabbedPane
    private val middlePanel: JPanel
    private val cardLayout: CardLayout
    private val agentExecutionPanel: pl.jclab.refio.ui.components.agents.AgentExecutionPanel

    private var settingsView: SettingsView? = null
    private val chatMessagesUpdatedListener = PropertyChangeListener {
        scrollChatToBottom()
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
        contextPanel = ContextPanel(project)
        logsPanel = LogsPanel(project)
        debugPanel = DebugPanel(project)
        apiLogsPanel = ApiLogsPanel(coreApiClient, autoLoadOnInit = false)
        historyPanel = HistoryPanel(project, autoLoadOnInit = false)
        ragViewPanel = RagViewPanel(project)
        turnStateStatusBar = TurnStateStatusBar()
        agentExecutionPanel = pl.jclab.refio.ui.components.agents.AgentExecutionPanel()
        debugPanel.agentTraceProvider = { agentExecutionPanel.toText() }

        cs.launch {
            var turnStateJob: kotlinx.coroutines.Job? = null
            sessionManager.activeSession.collect { _ ->
                turnStateJob?.cancel()
                val turnStateFlow = sessionManager.turnState
                if (turnStateFlow != null) {
                    turnStateJob = cs.launch {
                        turnStateFlow.collect { snapshot ->
                            SwingUtilities.invokeLater { turnStateStatusBar.update(snapshot) }
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

        val chatPanel = JPanel(BorderLayout()).apply {
            background = LCATheme.backgroundColor
            add(chatScrollPane, BorderLayout.CENTER)
            add(promptInputPanel, BorderLayout.SOUTH)
        }

        val stepsPanel = JPanel(BorderLayout()).apply {
            add(turnStateStatusBar, BorderLayout.NORTH)
            add(stepsQueueView, BorderLayout.CENTER)
        }

        tabbedPane = JTabbedPane().apply {
            addTab("Chat", chatPanel)
            addTab("Execution", stepsPanel)
            addTab("Context", contextPanel)
            addTab("Agents", agentExecutionPanel)
            addTab("RAG", ragViewPanel)
            addTab("Debug", debugPanel)
            addTab("Logs", logsPanel)
            addTab("API", apiLogsPanel)
            addChangeListener {
                if (selectedIndex >= 0 && getComponentAt(selectedIndex) === apiLogsPanel) {
                    apiLogsPanel.ensureLoaded()
                }
            }
        }

        val normalContentPanel = JPanel(BorderLayout()).apply {
            add(tabbedPane, BorderLayout.CENTER)
            border = LCATheme.paddedBorder(0, 0, 2, 0)
        }

        cardLayout = CardLayout()
        middlePanel = JPanel(cardLayout).apply {
            add(normalContentPanel, "NORMAL")
            add(historyPanel, "HISTORY")
        }

        add(middlePanel, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

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
        com.intellij.ide.BrowserUtil.browse("https://github.com/jclab-joseph/refio")
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

        tabbedPane.selectedIndex = 0

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

        tabbedPane.selectedIndex = 0

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    private fun updateStepsQueueVisibility(@Suppress("UNUSED_PARAMETER") mode: TaskMode) {
    }

    fun setAdvancedViewEnabled(enabled: Boolean) {
        logger.info { "Setting advanced view: $enabled" }

        javax.swing.SwingUtilities.invokeLater {
            if (enabled) {
                if (tabbedPane.tabCount == 1) {
                    tabbedPane.addTab("Steps", stepsQueueView)
                    tabbedPane.addTab("Context", contextPanel)
                    tabbedPane.addTab("RAG", ragViewPanel)
                    tabbedPane.addTab("Logs", logsPanel)
                    tabbedPane.addTab("Debug", debugPanel)
                    tabbedPane.addTab("API Logs", apiLogsPanel)
                }
                tabbedPane.tabPlacement = JTabbedPane.TOP
            } else {
                while (tabbedPane.tabCount > 1) {
                    tabbedPane.removeTabAt(1)
                }
                tabbedPane.selectedIndex = 0
            }

            statusBar.setAdvancedViewEnabled(enabled)

            tabbedPane.revalidate()
            tabbedPane.repaint()
        }
    }

    override fun dispose() {
        cs.cancel()
        chatView.removePropertyChangeListener("messagesUpdated", chatMessagesUpdatedListener)
        statusBar.removePropertyChangeListener("stopExecution", stopExecutionListener)
        historyPanel.removePropertyChangeListener("sessionLoaded", historySessionLoadedListener)
        historyPanel.removePropertyChangeListener("backToChat", historyBackToChatListener)
        settingsView?.removePropertyChangeListener("advancedViewChanged", advancedViewChangedListener)
        chatView.dispose()
        stepsQueueView.dispose()
        contextPanel.dispose()
        promptInputPanel.dispose()
        statusBar.dispose()
        logsPanel.dispose()
        debugPanel.dispose()
    }
}
