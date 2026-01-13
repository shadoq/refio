package pl.jclab.refio.ui.toolwindow

import pl.jclab.refio.services.logging.dualLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.ui.components.toolbar.ToolbarComponent
import pl.jclab.refio.ui.components.chat.ChatView
import pl.jclab.refio.ui.components.chat.PromptInputPanel
import pl.jclab.refio.ui.components.toolbar.StatusBar
import pl.jclab.refio.ui.components.steps.StepsQueueView
import pl.jclab.refio.ui.components.logs.LogsPanel
import pl.jclab.refio.ui.components.debug.DebugPanel
import pl.jclab.refio.ui.components.context.ContextPanel
import pl.jclab.refio.ui.components.history.HistoryPanel
import pl.jclab.refio.ui.components.rag.RagViewPanel
import pl.jclab.refio.ui.settings.ApiLogsPanel
import pl.jclab.refio.ui.settings.SettingsView
import pl.jclab.refio.api.CoreApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * Main panel for Refio tool window
 * Layout:
 * - Toolbar (top) - simplified, only "New Session" and overflow menu
 * - Tabbed Pane (Chat, Steps, Debug panels)
 * - Prompt Input Panel (mode/model selectors, execution toggles, text area, send button)
 * - Status Bar (bottom)
 */
class RefioMainPanel(private val project: Project) : JBPanel<RefioMainPanel>(BorderLayout()) {

    private val logger = dualLogger("RefioMainPanel")

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val coreApiClient = pl.jclab.refio.api.CoreApiClient(sessionManager.apiRouter)

    private val toolbar: ToolbarComponent
    private val chatView: ChatView
    private val promptInputPanel: PromptInputPanel
    private val chatScrollPane: JBScrollPane
    private val statusBar: StatusBar

    //    private val splitPane: JSplitPane
    private val stepsQueueView: StepsQueueView
    private val contextPanel: ContextPanel
    private val logsPanel: LogsPanel
    private val debugPanel: DebugPanel
    private val apiLogsPanel: ApiLogsPanel
    private val historyPanel: HistoryPanel
    private val ragViewPanel: RagViewPanel

    private val tabbedPane: JTabbedPane
    private val middlePanel: JPanel
    private val cardLayout: CardLayout

    // Cache SettingsView to avoid creating new instance each time
    private var settingsView: SettingsView? = null

    init {

        logger.info { "Initialize main plugin panel" }

        // Create components - note: order matters for cross-references
        chatView = ChatView(project)
        promptInputPanel = PromptInputPanel(project, chatView, coreApiClient)
        statusBar = StatusBar(project)
        stepsQueueView = StepsQueueView(project)
        contextPanel = ContextPanel(project)
        logsPanel = LogsPanel(project)
        debugPanel = DebugPanel(project)
        apiLogsPanel = ApiLogsPanel(coreApiClient)
        historyPanel = HistoryPanel(project)
        ragViewPanel = RagViewPanel(project)

        // Scroll pane for chat messages only
        chatScrollPane = JBScrollPane(chatView).apply {
            border = null
            verticalScrollBarPolicy = javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            background = LCATheme.backgroundColor
            viewport.background = LCATheme.backgroundColor
        }

        // Listen for messages update to scroll to bottom
        chatView.addPropertyChangeListener("messagesUpdated") {
            scrollChatToBottom()
        }

        // Chat panel: modeBadge (sticky top) + scroll(messages) + promptInput (sticky bottom)
        val chatPanel = JPanel(BorderLayout()).apply {
            background = LCATheme.backgroundColor
            add(chatView.modeBadge, BorderLayout.NORTH)
            add(chatScrollPane, BorderLayout.CENTER)
            add(promptInputPanel, BorderLayout.SOUTH)
        }

        // Create tabbed pane
        tabbedPane = JTabbedPane().apply {
            addTab("Chat", chatPanel)
            addTab("Steps", JBScrollPane(stepsQueueView))
            addTab("Context", contextPanel)
            addTab("RAG", ragViewPanel)
            addTab("Logs", logsPanel)
            addTab("Debug", debugPanel)
            addTab("API Logs", apiLogsPanel)
        }

        // Create normal content panel
        val normalContentPanel = JPanel(BorderLayout()).apply {
            add(tabbedPane, BorderLayout.CENTER)
            border = LCATheme.paddedBorder(0, 0, 10, 0)
        }

        // Create middle panel with CardLayout for switching between views
        cardLayout = CardLayout()
        middlePanel = JPanel(cardLayout).apply {
            add(normalContentPanel, "NORMAL")
            add(historyPanel, "HISTORY")
            // Settings will be added lazily when first opened
        }

        // Create toolbar with settings callback
        toolbar = ToolbarComponent(project, promptInputPanel) {
            openSettings()
        }

        add(toolbar, BorderLayout.NORTH)
        add(middlePanel, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        // Load advanced view setting from config on startup
        cs.launch {
            try {
                val config = coreApiClient.getConfig(section = "general", scope = "app")
                val advancedView = (config.settings["advanced_view"] as? String).toBoolean()

                // Apply setting on EDT
                javax.swing.SwingUtilities.invokeLater {
                    setAdvancedViewEnabled(advancedView)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load advanced view setting, using default: false" }
                // Start in simple mode by default if loading fails
                javax.swing.SwingUtilities.invokeLater {
                    setAdvancedViewEnabled(false)
                }
            }
        }

        // Register statusBar with SessionManager for execution progress updates
        sessionManager.setStatusBar(statusBar)

        // Listen to status bar stop execution event
        statusBar.addPropertyChangeListener("stopExecution") { evt ->
            if (evt.newValue == true) {
                stepExecutionService.stopExecution()
            }
        }

        // Listen to toolbar property changes for history toggle
        toolbar.addPropertyChangeListener("showHistory") { evt ->
            if (evt.newValue == true) {
                toggleHistoryPanel()
            }
        }

        // Listen to toolbar property changes for new session creation
        toolbar.addPropertyChangeListener("newSessionCreated") { evt ->
            if (evt.newValue == true) {
                showChatView()
            }
        }

        // Listen to historyPanel session loaded event to return to Chat view
        historyPanel.addPropertyChangeListener("sessionLoaded") { evt ->
            if (evt.newValue == true) {
                showChatView()
            }
        }

        // Listen to historyPanel back to chat event
        historyPanel.addPropertyChangeListener("backToChat") { evt ->
            if (evt.newValue == true) {
                showChatView()
            }
        }

        // Listen to mode changes to show/hide steps queue
        cs.launch {
            sessionManager.activeSession.collect { session ->
                session?.let { updateStepsQueueVisibility(it.mode) }
            }
        }

        // Listen to subtasks changes to update steps queue visibility
        cs.launch {
            sessionManager.subtasks.collect {
                sessionManager.activeSession.value?.let { session ->
                    updateStepsQueueVisibility(session.mode)
                }
            }
    }
    }

    /**
     * Cycle to next mode (triggered by Alt+M action)
     */
    fun cycleMode() {
        promptInputPanel.cycleMode()
    }

    /**
     * Scroll chat to bottom (show latest messages)
     */
    private fun scrollChatToBottom() {
        javax.swing.SwingUtilities.invokeLater {
            val viewport = chatScrollPane.viewport
            val view = viewport.view ?: return@invokeLater

            val contentHeight = view.preferredSize.height
            val viewportHeight = viewport.extentSize.height
            if (contentHeight <= viewportHeight) {
                viewport.viewPosition = java.awt.Point(0, 0)
                return@invokeLater
            }

            chatScrollPane.verticalScrollBar.value = chatScrollPane.verticalScrollBar.maximum
        }
    }

    /**
     * Open Settings view (switches to SETTINGS card in CardLayout)
     */
    private fun openSettings() {
        logger.info { "Opening settings view" }

        // Create settings view only once (lazy initialization)
        if (settingsView == null) {
            settingsView = SettingsView(project, coreApiClient) {
                closeSettings()
            }

            // Listen to advanced view changes
            settingsView!!.addPropertyChangeListener("advancedViewChanged") { evt ->
                if (evt.newValue is Boolean) {
                    setAdvancedViewEnabled(evt.newValue as Boolean)
                }
            }

            // Add settings to CardLayout (only first time)
            middlePanel.add(settingsView, "SETTINGS")
        }

        // Switch to settings card
        cardLayout.show(middlePanel, "SETTINGS")

        // Refresh UI
        middlePanel.revalidate()
        middlePanel.repaint()
    }

    /**
     * Close Settings view and return to normal content (Chat view)
     */
    private fun closeSettings() {
        logger.info { "Closing settings view" }

        // Switch back to normal card (Chat view)
        cardLayout.show(middlePanel, "NORMAL")

        // Switch to Chat tab (index 0)
        tabbedPane.selectedIndex = 0

        // Refresh UI
        middlePanel.revalidate()
        middlePanel.repaint()

        // Refresh model list in dropdown (visibility may have changed)
        // Note: refreshModels() will preserve user's selected model, not override with default
        promptInputPanel.refreshModels()
    }

    /**
     * Toggle history panel visibility
     * US-204: Show history panel instead of normal content (not as overlay)
     */
    private fun toggleHistoryPanel() {
        logger.info { "Toggling history panel" }

        // Switch to history card
        cardLayout.show(middlePanel, "HISTORY")

        // Load sessions when showing history
        historyPanel.loadSessions()

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    /**
     * Show normal content (hide history panel)
     */
    private fun showNormalContent() {
        logger.info { "Showing normal content" }

        // Switch to normal card
        cardLayout.show(middlePanel, "NORMAL")

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    /**
     * Show Chat view (used when creating new session from History)
     */
    private fun showChatView() {
        logger.info { "Showing Chat view after new session creation" }

        // Switch to normal card (hide history)
        cardLayout.show(middlePanel, "NORMAL")

        // Switch to Chat tab (index 0)
        tabbedPane.selectedIndex = 0

        middlePanel.revalidate()
        middlePanel.repaint()
    }

    private fun updateStepsQueueVisibility(@Suppress("UNUSED_PARAMETER") mode: TaskMode) {
        // TODO: Implement steps queue visibility logic when split pane is re-enabled
        // Currently using tabbed pane, so steps are always accessible via Steps tab
    }

    /**
     * Enable or disable advanced view
     * - Simple mode: Show only Chat tab, hide tab bar
     * - Advanced mode: Show all tabs with tab bar
     */
    fun setAdvancedViewEnabled(enabled: Boolean) {
        logger.info { "Setting advanced view: $enabled" }

        javax.swing.SwingUtilities.invokeLater {
            if (enabled) {
                // Advanced mode: Show all tabs
                // Add tabs back if they were removed (check if tab count is only 1)
                if (tabbedPane.tabCount == 1) {
                    tabbedPane.addTab("Steps", JBScrollPane(stepsQueueView))
                    tabbedPane.addTab("Context", contextPanel)
                    tabbedPane.addTab("RAG", ragViewPanel)
                    tabbedPane.addTab("Logs", logsPanel)
                    tabbedPane.addTab("Debug", debugPanel)
                    tabbedPane.addTab("API Logs", apiLogsPanel)
                }
                // Show tab bar by setting tab placement
                tabbedPane.tabPlacement = JTabbedPane.TOP
            } else {
                // Simple mode: Remove all tabs except Chat (index 0)
                while (tabbedPane.tabCount > 1) {
                    tabbedPane.removeTabAt(1)
                }
                // Hide tab bar by setting tab placement to hidden (hack: set height to 0)
                // Note: There's no direct way to hide tabs in Swing, so we remove them instead
                tabbedPane.selectedIndex = 0
            }

            // Update status bar as well
            statusBar.setAdvancedViewEnabled(enabled)

            tabbedPane.revalidate()
            tabbedPane.repaint()
        }
    }

    /**
     * Dispose resources when tool window is closed
     */
    fun dispose() {
        cs.cancel()
        chatView.dispose()
        stepsQueueView.dispose()
        contextPanel.dispose()
        promptInputPanel.dispose()
        statusBar.dispose()
        logsPanel.dispose()
        debugPanel.dispose()
        // historyPanel doesn't have dispose() yet - no resources to clean up
    }
}
