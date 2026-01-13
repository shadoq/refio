package pl.jclab.refio.ui.components.history

import com.intellij.openapi.project.Project
// Removed JBColor import
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * History Panel - Slide-in overlay for browsing session history
 *
 * Features (US-101):
 * - Search by session name
 * - Filter by mode (Chat/Plan/Agent)
 * - Session cards with metadata
 * - Pin/unpin sessions
 * - Load session on click
 * - Export/delete actions
 */
class HistoryPanel(private val project: Project) : JBPanel<HistoryPanel>(BorderLayout()) {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val coreManager = CoreConnectionManager.getInstance()
    private val logger = dualLogger("HistoryPanel")

    private val searchField: SearchTextField
    private val filterTabs: JTabbedPane
    private val sessionListPanel: JPanel
    private val sessionListScrollPane: JBScrollPane

    private var allSessions: List<Session> = emptyList()
    private var filteredSessions: List<Session> = emptyList()

    init {
        preferredSize = Dimension(350, 600)
        border = BorderFactory.createLineBorder(LCATheme.borderColor)

        // Header with back button and title
        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            // Top row: Back button, title, close button
            val topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                // Back button
                val backButton = JButton("< Back to Chat").apply {
                    toolTipText = "Return to chat view"
                    addActionListener { onBackToChat() }
                }
                add(backButton, BorderLayout.WEST)

                // Title
                val titleLabel = JBLabel("Session History").apply {
                    font = font.deriveFont(16f)
                    horizontalAlignment = SwingConstants.CENTER
                }
                add(titleLabel, BorderLayout.CENTER)

                // Close button
                val closeButton = JButton("✕").apply {
                    preferredSize = Dimension(32, 28)
                    toolTipText = "Close history"
                    addActionListener { onBackToChat() }
                }
                add(closeButton, BorderLayout.EAST)
            }

            // Search field
            searchField = SearchTextField().apply {
                textEditor.emptyText.setText("Search sessions...")
                textEditor.document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = applyFilters()
                    override fun removeUpdate(e: DocumentEvent?) = applyFilters()
                    override fun changedUpdate(e: DocumentEvent?) = applyFilters()
                })
            }

            add(topRow, BorderLayout.NORTH)
            add(searchField, BorderLayout.CENTER)
        }

        // Filter tabs
        filterTabs = JTabbedPane().apply {
            addTab("All", JPanel())
            addTab("Chat", JPanel())
            addTab("Plan", JPanel())
            addTab("Agent", JPanel())

            // Listen to tab changes
            addChangeListener {
                applyFilters()
            }
        }

        // Session list
        sessionListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        sessionListScrollPane = JBScrollPane(sessionListPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Bottom toolbar
        val bottomToolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("Refresh").apply {
                addActionListener { loadSessions() }
            })
        }

        // Top panel combining header and filter tabs
        val topPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerPanel)
            add(filterTabs)
        }

        // Layout
        add(topPanel, BorderLayout.NORTH)
        add(sessionListScrollPane, BorderLayout.CENTER)
        add(bottomToolbar, BorderLayout.SOUTH)

        // Load sessions on init
        loadSessions()
    }

    /**
     * Load sessions from embedded core
     */
    fun loadSessions() {
        cs.launch {
            try {
                logger.info { "Loading sessions..." }

                // Fetch all sessions from embedded core (use project router for consistency)
                val projectId = sessionManager.currentProjectId()
                val tasks = sessionManager.apiRouter.getTasksForProject(projectId)

                // Clean up empty sessions (sessions with no messages)
                val router = sessionManager.apiRouter
                val emptySessionIds = mutableListOf<String>()

                tasks.forEach { task ->
                    try {
                        val messagesResponse = router.getMessages(task.id)
                        if (messagesResponse.messages.isEmpty() && !task.pinned) {
                            // This session has no messages and is not pinned - mark for deletion
                            emptySessionIds.add(task.id)
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to check messages for task ${task.id}" }
                    }
                }

                // Delete empty sessions
                if (emptySessionIds.isNotEmpty()) {
                    logger.info { "Deleting ${emptySessionIds.size} empty sessions" }
                    emptySessionIds.forEach { taskId ->
                        try {
                            router.deleteTask(taskId)
                            logger.debug { "Deleted empty session: $taskId" }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to delete empty session: $taskId" }
                        }
                    }
                }

                // Reload sessions after cleanup
                val refreshedTasks = sessionManager.apiRouter.getTasksForProject(projectId)

                // Convert from CoreApiRouter TaskResponse to Session model
                allSessions = refreshedTasks.map { task ->
                    Session(
                        id = task.id,
                        name = task.name,
                        mode = TaskMode.valueOf(task.mode),
                        status = TaskStatus.valueOf(task.status),
                        executionMode = ExecutionMode.valueOf(task.executionMode),
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                        tokensIn = task.tokensIn,
                        tokensOut = task.tokensOut,
                        costUsd = task.costUsd,
                        pinned = task.pinned,
                        rate = task.rate
                    )
                }
                applyFilters()

                logger.info { "Loaded ${allSessions.size} sessions (deleted ${emptySessionIds.size} empty)" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load sessions" }
                showError("Failed to load sessions: ${e.message}")
            }
        }
    }

    /**
     * Apply search and filter to session list
     * US-204: Sort by pinned first, then by recency
     */
    private fun applyFilters() {
        val query = searchField.text.lowercase()
        val selectedTab = filterTabs.selectedIndex

        filteredSessions = allSessions
            .filter { session ->
                // Filter by search query
                val matchesQuery = query.isEmpty() ||
                    session.name.lowercase().contains(query) ||
                    session.id.lowercase().contains(query)

                // Filter by mode tab
                val matchesMode = when (selectedTab) {
                    0 -> true // All
                    1 -> session.mode == TaskMode.CHAT
                    2 -> session.mode == TaskMode.PLAN
                    3 -> session.mode == TaskMode.AGENT
                    else -> true
                }

                matchesQuery && matchesMode
            }
            .sortedWith(
                compareByDescending<Session> { it.pinned }  // Pinned first
                    .thenByDescending { it.updatedAt }      // Then by recency
            )

        refreshSessionList()
    }

    /**
     * Refresh session list UI
     * US-204: Group by pinned/recent with section headers
     */
    private fun refreshSessionList() {
        sessionListPanel.removeAll()

        if (filteredSessions.isEmpty()) {
            val emptyLabel = JBLabel("No sessions found").apply {
                horizontalAlignment = SwingConstants.CENTER
                border = BorderFactory.createEmptyBorder(20, 0, 0, 0)
            }
            sessionListPanel.add(emptyLabel)
        } else {
            // Group by pinned/recent
            val (pinned, recent) = filteredSessions.partition { it.pinned }

            // Pinned section
            if (pinned.isNotEmpty()) {
                sessionListPanel.add(JBLabel("📌 Pinned").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, 12f)
                    foreground = LCATheme.grayColor
                    border = BorderFactory.createEmptyBorder(8, 4, 4, 0)
                })
                pinned.forEach { session ->
                    sessionListPanel.add(createSessionCard(session))
                    sessionListPanel.add(Box.createVerticalStrut(8))
                }
            }

            // Recent section
            if (recent.isNotEmpty()) {
                sessionListPanel.add(JBLabel("🕒 Recent").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, 12f)
                    foreground = LCATheme.grayColor
                    border = BorderFactory.createEmptyBorder(8, 4, 4, 0)
                })
                recent.forEach { session ->
                    sessionListPanel.add(createSessionCard(session))
                    sessionListPanel.add(Box.createVerticalStrut(8))
                }
            }
        }

        sessionListPanel.revalidate()
        sessionListPanel.repaint()
    }

    /**
     * Create session card component
     */
    private fun createSessionCard(session: Session): JComponent {
        val card = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LCATheme.borderColor),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
            )

            // Header: Name + mode badge
            val headerPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                add(JBLabel(session.name).apply {
                    font = font.deriveFont(font.style or java.awt.Font.BOLD)
                })
                add(createModeBadge(session.mode))
                add(createStatusBadge(session.status))
            }

            // Metadata
            val metadataPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                add(JBLabel("Created: ${formatTimestamp(session.createdAt)}").apply {
                    foreground = LCATheme.grayColor
                    font = font.deriveFont(11f)
                })

                if (session.tokensIn > 0 || session.tokensOut > 0) {
                    add(JBLabel("Tokens: ${session.tokensIn}↓ ${session.tokensOut}↑").apply {
                        foreground = LCATheme.grayColor
                        font = font.deriveFont(11f)
                    })
                }

                if (session.costUsd > 0) {
                    add(JBLabel("Cost: $${String.format("%.4f", session.costUsd)}").apply {
                        foreground = LCATheme.grayColor
                        font = font.deriveFont(11f)
                    })
                }

                // Display rating
                session.rate?.let { rating ->
                    val ratingIcon = if (rating > 0) "👍" else "👎"
                    val ratingText = if (rating > 0) "Positive" else "Negative"
                    add(JBLabel("$ratingIcon Rating: $ratingText").apply {
                        foreground = if (rating > 0) LCATheme.statusGreen else LCATheme.statusRed
                        font = font.deriveFont(java.awt.Font.BOLD, 11f)
                    })
                }
            }

            // Actions
            val actionsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                add(JButton("Load").apply {
                    addActionListener {
                        onLoadSession(session)
                    }
                })
                add(JButton(if (session.pinned) "⭐" else "☆").apply {
                    toolTipText = if (session.pinned) "Unpin session" else "Pin session"
                    preferredSize = Dimension(32, 28)
                    addActionListener {
                        onTogglePin(session)
                    }
                })
                add(JButton("🗑️").apply {
                    toolTipText = "Delete session"
                    preferredSize = Dimension(32, 28)
                    addActionListener {
                        onDeleteSession(session)
                    }
                })
            }

            add(headerPanel, BorderLayout.NORTH)
            add(metadataPanel, BorderLayout.CENTER)
            add(actionsPanel, BorderLayout.SOUTH)

            // Hover effect
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    background = LCATheme.lightGrayColor
                }

                override fun mouseExited(e: MouseEvent?) {
                    background = null
                }
            })
        }

        return card
    }

    private fun createModeBadge(mode: TaskMode): JComponent {
        val color = when (mode) {
            TaskMode.CHAT -> LCATheme.statusBlue
            TaskMode.PLAN -> LCATheme.statusGreen
            TaskMode.AGENT -> LCATheme.statusOrange
        }

        return JBLabel(mode.name).apply {
            foreground = color
            font = font.deriveFont(10f)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
            )
        }
    }

    private fun createStatusBadge(status: TaskStatus): JComponent {
        val (icon, color) = when (status) {
            TaskStatus.SUCCESS -> "✓" to LCATheme.statusGreen
            TaskStatus.FAILED -> "✗" to LCATheme.statusRed
            TaskStatus.RUNNING -> "⟳" to LCATheme.statusBlue
            TaskStatus.CANCELED -> "⊘" to LCATheme.grayColor
            else -> "" to LCATheme.grayColor
        }

        return JBLabel(icon).apply {
            foreground = color
            font = font.deriveFont(12f)
            toolTipText = status.name
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        val date = java.util.Date(epochMs)
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        return formatter.format(date)
    }

    private fun onLoadSession(session: Session) {
        logger.info { "Loading session: ${session.id}" }

        cs.launch {
            try {
                // Load session via SessionManager
                sessionManager.loadSession(session.id)

                logger.info { "Session loaded successfully: ${session.id}" }

                // Notify RefioMainPanel to switch to Chat view
                SwingUtilities.invokeLater {
                    firePropertyChange("sessionLoaded", false, true)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load session" }
                SwingUtilities.invokeLater {
                    showError("Failed to load session: ${e.message}")
                }
            }
        }
    }

    private fun onTogglePin(session: Session) {
        cs.launch {
            try {
                val newPinned = !session.pinned
                logger.info { "Toggling pin for session ${session.id}: $newPinned" }

                // Update via project router
                sessionManager.apiRouter.updateTask(
                    session.id,
                    pl.jclab.refio.core.api.UpdateTaskRequest(pinned = newPinned)
                )

                // Reload sessions to reflect change
                loadSessions()

            } catch (e: Exception) {
                logger.error(e) { "Failed to toggle pin" }
                SwingUtilities.invokeLater {
                    showError("Failed to pin/unpin session: ${e.message}")
                }
            }
        }
    }

    private fun onDeleteSession(session: Session) {
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            "Delete session '${session.name}'?\n\nThis action cannot be undone.",
            "Delete Session",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (confirmed == JOptionPane.YES_OPTION) {
            cs.launch {
                try {
                    // Delete via project router
                    sessionManager.apiRouter.deleteTask(session.id)

                    logger.info { "Deleted session: ${session.id}" }
                    loadSessions()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete session" }
                    showError("Failed to delete session: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(
            this,
            message,
            "Error",
            JOptionPane.ERROR_MESSAGE
        )
    }

    /**
     * Show history panel and load sessions
     */
    fun showHistory() {
        isVisible = true
        loadSessions()
    }

    /**
     * Hide history panel
     */
    fun hideHistory() {
        isVisible = false
    }

    /**
     * Handle back to chat action
     */
    private fun onBackToChat() {
        logger.info { "Back to Chat clicked" }

        // Notify RefioMainPanel to switch to Chat view
        SwingUtilities.invokeLater {
            firePropertyChange("backToChat", false, true)
        }
    }
}
