package pl.jclab.refio.ui.components.history

import com.intellij.openapi.project.Project
// Removed JBColor import
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.logging.dualLogger
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
class HistoryPanel(
    private val project: Project,
    private val autoLoadOnInit: Boolean = true
) : JBPanel<HistoryPanel>(BorderLayout()) {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val logger = dualLogger("HistoryPanel")

    private val searchField: SearchTextField
    private val filterTabs: JTabbedPane
    private val sortCombo: JComboBox<SortOrder>
    private val statusCombo: JComboBox<StatusFilter>
    private val groupPinnedCheckbox: JCheckBox
    private val sessionListPanel: JPanel
    private val sessionListScrollPane: JBScrollPane

    enum class SortOrder(val label: String) {
        UPDATED_DESC("Recently updated"),
        CREATED_DESC("Recently created"),
        CREATED_ASC("Oldest first"),
        NAME_ASC("Name A→Z"),
        NAME_DESC("Name Z→A"),
        DURATION_DESC("Duration (longest)"),
        GENERATION_DESC("Generation time (longest)"),
        TOKENS_DESC("Tokens (most)"),
        COST_DESC("Cost (highest)");

        override fun toString() = label
    }

    enum class StatusFilter(val label: String, val status: TaskStatus?) {
        ALL("All statuses", null),
        SUCCESS("✓ Success", TaskStatus.SUCCESS),
        FAILED("✗ Failed", TaskStatus.FAILED),
        RUNNING("⟳ Running", TaskStatus.RUNNING),
        CANCELED("⊘ Canceled", TaskStatus.CANCELED),
        NEW("New", TaskStatus.NEW),
        PENDING("Pending", TaskStatus.PENDING);

        override fun toString() = label
    }

    private var allSessions: List<Session> = emptyList()
    private var filteredSessions: List<Session> = emptyList()
    private val generationMillisBySession: MutableMap<String, Long> = mutableMapOf()
    @Volatile
    private var isLoading = false

    /** Callback invoked on EDT when the panel wants to navigate back to chat view. */
    var onNavigateToChat: (() -> Unit)? = null

    // Test data projectId - always included in results for demo purposes
    private val TEST_DATA_PROJECT_ID = "legacy_unknown"

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

        // Filter tabs (mode)
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

        // Sort + status filter row
        sortCombo = JComboBox(SortOrder.values()).apply {
            selectedItem = SortOrder.UPDATED_DESC
            toolTipText = "Sort order"
            addActionListener { applyFilters() }
        }

        statusCombo = JComboBox(StatusFilter.values()).apply {
            selectedItem = StatusFilter.ALL
            toolTipText = "Filter by status"
            addActionListener { applyFilters() }
        }

        groupPinnedCheckbox = JCheckBox("Group pinned", true).apply {
            toolTipText = "Show pinned sessions in a separate section at the top"
            addActionListener { applyFilters() }
        }

        val filterRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Sort:"))
            add(sortCombo)
            add(JBLabel("Status:"))
            add(statusCombo)
            add(groupPinnedCheckbox)
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

        // Top panel combining header, filter tabs, and sort/status row
        val topPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerPanel)
            add(filterTabs)
            add(filterRow)
        }

        // Layout
        add(topPanel, BorderLayout.NORTH)
        add(sessionListScrollPane, BorderLayout.CENTER)
        add(bottomToolbar, BorderLayout.SOUTH)

        // By default load lazily when user opens history.
        if (autoLoadOnInit) {
            loadSessions()
        } else {
            applyFilters()
        }
    }

    /**
     * Load sessions from embedded core
     * Includes both current project sessions and test data (legacy_unknown)
     */
    fun loadSessions() {
        if (isLoading) {
            logger.debug { "loadSessions skipped - already loading" }
            return
        }

        cs.launch {
            try {
                isLoading = true
                logger.debug { "Loading sessions..." }

                val router = sessionManager.apiRouter
                val projectId = sessionManager.currentProjectId()

                // Fetch sessions from current project AND test data project
                val currentProjectTasks = router.taskRouter.getTasksForProject(projectId)
                val testDataTasks = router.taskRouter.getTasksForProject(TEST_DATA_PROJECT_ID)

                // Combine both sets (test data is always visible for demo)
                val tasks = (currentProjectTasks + testDataTasks)
                    // Deduplicate by ID (in case same session exists in both)
                    .distinctBy { it.id }

                logger.debug {
                    "Converting ${tasks.size} tasks to Session models (project=${currentProjectTasks.size}, testData=${testDataTasks.size})"
                }

                // Convert from CoreApiRouter TaskResponse to Session model
                allSessions = tasks.map { task ->
                    Session(
                        id = task.id,
                        name = task.name,
                        mode = TaskMode.valueOf(task.mode),
                        status = TaskStatus.valueOf(task.status),
                        executionMode = ExecutionMode.valueOf(task.executionMode),
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                        model = extractModelFromUiState(task.uiState),
                        tokensIn = task.tokensIn,
                        tokensOut = task.tokensOut,
                        costUsd = task.costUsd,
                        pinned = task.pinned,
                        rate = task.rate
                    )
                }

                logger.debug { "Loaded ${allSessions.size} sessions" }

                // Compute generation time (LLM + tool execution) per session from message metadata.
                generationMillisBySession.clear()
                allSessions.forEach { session ->
                    try {
                        val messages = router.chatRouter.getMessages(session.id).messages
                        var total = 0L
                        messages.forEach { msg ->
                            val m = pl.jclab.refio.core.db.MessageMetrics.fromJson(msg.metadata) ?: return@forEach
                            total += m.latencyMs.toLong() + m.toolExecutionTimeMs.toLong()
                        }
                        if (total > 0) generationMillisBySession[session.id] = total
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to compute generation time for session ${session.id}" }
                    }
                }

                // Update UI on EDT thread
                SwingUtilities.invokeLater {
                    applyFilters()
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load sessions" }
                SwingUtilities.invokeLater {
                    showError("Failed to load sessions: ${e.message}")
                }
            } finally {
                isLoading = false
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
        val statusFilter = (statusCombo.selectedItem as? StatusFilter) ?: StatusFilter.ALL
        val sortOrder = (sortCombo.selectedItem as? SortOrder) ?: SortOrder.UPDATED_DESC
        val groupPinned = groupPinnedCheckbox.isSelected

        val primary = sortComparator(sortOrder)
        val comparator: Comparator<Session> = if (groupPinned) {
            compareByDescending<Session> { it.pinned }.then(primary)
        } else {
            primary
        }

        filteredSessions = allSessions
            .filter { session ->
                val matchesQuery = query.isEmpty() ||
                    session.name.lowercase().contains(query) ||
                    session.id.lowercase().contains(query)

                val matchesMode = when (selectedTab) {
                    0 -> true
                    1 -> session.mode == TaskMode.CHAT
                    2 -> session.mode == TaskMode.PLAN
                    3 -> session.mode == TaskMode.AGENT
                    else -> true
                }

                val matchesStatus = statusFilter.status == null || session.status == statusFilter.status

                matchesQuery && matchesMode && matchesStatus
            }
            .sortedWith(comparator)

        logger.debug { "applyFilters: filteredSessions.size=${filteredSessions.size}, sort=$sortOrder, status=$statusFilter" }

        refreshSessionList()
    }

    private fun sortComparator(order: SortOrder): Comparator<Session> = when (order) {
        SortOrder.UPDATED_DESC -> compareByDescending<Session> { it.updatedAt }
        SortOrder.CREATED_DESC -> compareByDescending<Session> { it.createdAt }
        SortOrder.CREATED_ASC -> compareBy<Session> { it.createdAt }
        SortOrder.NAME_ASC -> Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        SortOrder.NAME_DESC -> Comparator<Session> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }.reversed()
        SortOrder.DURATION_DESC -> compareByDescending<Session> { (it.updatedAt - it.createdAt).coerceAtLeast(0L) }
        SortOrder.GENERATION_DESC -> compareByDescending<Session> { generationMillisBySession[it.id] ?: 0L }
        SortOrder.TOKENS_DESC -> compareByDescending<Session> { it.tokensIn + it.tokensOut }
        SortOrder.COST_DESC -> compareByDescending<Session> { it.costUsd }
    }

    /**
     * Refresh session list UI
     * US-204: Group by pinned/recent with section headers
     */
    private fun refreshSessionList() {
        logger.debug { "refreshSessionList: rendering ${filteredSessions.size} sessions" }
        sessionListPanel.removeAll()

        if (filteredSessions.isEmpty()) {
            logger.debug { "refreshSessionList: no sessions to display" }
            val emptyLabel = JBLabel("No sessions found").apply {
                horizontalAlignment = SwingConstants.CENTER
                border = BorderFactory.createEmptyBorder(20, 0, 0, 0)
            }
            sessionListPanel.add(emptyLabel)
        } else if (groupPinnedCheckbox.isSelected) {
            // Group by pinned/recent, preserving chosen sort within each group
            val (pinned, recent) = filteredSessions.partition { it.pinned }

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
        } else {
            // Flat list using chosen sort order
            filteredSessions.forEach { session ->
                sessionListPanel.add(createSessionCard(session))
                sessionListPanel.add(Box.createVerticalStrut(8))
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

                add(JBLabel("Duration: ${formatDuration(session.updatedAt - session.createdAt)}").apply {
                    foreground = LCATheme.grayColor
                    font = font.deriveFont(11f)
                })

                generationMillisBySession[session.id]?.let { genMs ->
                    add(JBLabel("Generation: ${formatDuration(genMs)}").apply {
                        foreground = LCATheme.grayColor
                        font = font.deriveFont(11f)
                        toolTipText = "Sum of LLM latency and tool execution time"
                    })
                }

                session.model?.takeIf { it.isNotBlank() }?.let { modelName ->
                    add(JBLabel("Model: $modelName").apply {
                        foreground = LCATheme.grayColor
                        font = font.deriveFont(11f)
                        toolTipText = modelName
                    })
                }

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

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "—"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun extractModelFromUiState(uiStateJson: String?): String? {
        if (uiStateJson.isNullOrBlank()) return null
        return try {
            val map = com.google.gson.Gson().fromJson(
                uiStateJson,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null
            (map["selectedModel"] as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun onLoadSession(session: Session) {
        logger.info { "Loading session: ${session.id}" }

        cs.launch {
            try {
                // Load session via SessionManager
                sessionManager.loadSession(session.id)

                logger.info { "Session loaded successfully: ${session.id}" }

                // Navigate back to chat view
                SwingUtilities.invokeLater {
                    onNavigateToChat?.invoke()
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
                sessionManager.apiRouter.taskRouter.updateTask(
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
                    sessionManager.apiRouter.taskRouter.deleteTask(session.id)

                    logger.info { "Deleted session: ${session.id}" }
                    loadSessions()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete session" }
                    SwingUtilities.invokeLater {
                        showError("Failed to delete session: ${e.message}")
                    }
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

        // Navigate back to chat view
        SwingUtilities.invokeLater {
            onNavigateToChat?.invoke()
            firePropertyChange("backToChat", false, true)
        }
    }
}

