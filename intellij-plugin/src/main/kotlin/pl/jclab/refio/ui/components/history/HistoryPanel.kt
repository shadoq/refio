package pl.jclab.refio.ui.components.history

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Session history browser.
 *
 * Sessions are shown as a flat two-line list rather than cards, so a full screen shows a dozen
 * entries instead of three. Row actions (load, pin, delete) live in the toolbar above the list
 * and in the context menu.
 */
class HistoryPanel(
    private val project: Project,
    private val autoLoadOnInit: Boolean = true
) : JBPanel<HistoryPanel>(BorderLayout()), Disposable {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val logger = dualLogger("HistoryPanel")

    private val searchField: SearchTextField
    private val listModel = CollectionListModel<SessionRow>()
    private val sessionList: JBList<SessionRow>
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var modeFilter: TaskMode? = null
    private var statusFilter: StatusFilter = StatusFilter.ALL
    private var sortOrder: SortOrder = SortOrder.UPDATED_DESC
    private var groupPinned: Boolean = true

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
        SUCCESS("Success", TaskStatus.SUCCESS),
        FAILED("Failed", TaskStatus.FAILED),
        RUNNING("Running", TaskStatus.RUNNING),
        CANCELED("Canceled", TaskStatus.CANCELED),
        INCOMPLETE("Incomplete", TaskStatus.INCOMPLETE),
        NEW("New", TaskStatus.NEW),
        PENDING("Pending", TaskStatus.PENDING);

        override fun toString() = label
    }

    private var allSessions: List<Session> = emptyList()
    private val generationMillisBySession: MutableMap<String, Long> = mutableMapOf()
    @Volatile
    private var isLoading = false

    /** Callback invoked on EDT when the panel wants to navigate back to chat view. */
    var onNavigateToChat: (() -> Unit)? = null

    // Test data projectId - always included in results for demo purposes
    private val TEST_DATA_PROJECT_ID = "legacy_unknown"

    init {
        // No preferred size and no box around the panel: this is a full card in the tool window's
        // CardLayout, like the chat and settings views, so it fills the dock and sits flush with
        // it. A line border made it read as an inset sub-panel, and a fixed 350x600 also made the
        // card stack report that width as its own preferred size.
        border = LCATheme.emptyBorder()

        searchField = SearchTextField().apply {
            textEditor.emptyText.setText("Search sessions...")
            textEditor.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = scheduleFilter()
                override fun removeUpdate(e: DocumentEvent?) = scheduleFilter()
                override fun changedUpdate(e: DocumentEvent?) = scheduleFilter()
            })
        }

        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6)

            val topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JButton("Back to Chat", AllIcons.Actions.Back).apply {
                    toolTipText = "Return to chat view"
                    addActionListener { onBackToChat() }
                }, BorderLayout.WEST)

                add(JBLabel("Session History").apply {
                    horizontalAlignment = SwingConstants.CENTER
                }, BorderLayout.CENTER)
            }

            add(topRow, BorderLayout.NORTH)
            add(searchField, BorderLayout.CENTER)
        }

        sessionList = JBList(listModel).apply {
            cellRenderer = SessionListRenderer()
            fixedCellHeight = SessionListRenderer.rowHeight(width)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No sessions found"

            // The renderer stacks or inlines the row depending on how wide it is, and the cell
            // height has to follow, otherwise a resize leaves single-line rows in double-height
            // cells. fixedCellHeight is what keeps the list cheap for long histories, so it is
            // re-set on resize rather than dropped.
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    val next = SessionListRenderer.rowHeight(width)
                    if (next == fixedCellHeight) return
                    fixedCellHeight = next
                    revalidate()
                    repaint()
                }
            })
        }

        ListSpeedSearch.installOn(sessionList) { it.title }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = sessionList.selectedValue ?: return false
                onLoadSession(row)
                return true
            }
        }.installOn(sessionList)

        PopupHandler.installPopupMenu(sessionList, contextActions(), "Refio.History.Popup")

        val listPanel = ToolbarDecorator.createDecorator(sessionList)
            .setToolbarPosition(com.intellij.openapi.actionSystem.ActionToolbarPosition.TOP)
            .disableAddAction()
            .disableUpDownActions()
            .setRemoveAction { deleteSelected() }
            .addExtraAction(loadAction())
            .addExtraAction(pinAction())
            .addExtraAction(Separator())
            .addExtraAction(modeFilterAction())
            .addExtraAction(sortAction())
            .addExtraAction(refreshAction())
            .createPanel()

        add(headerPanel, BorderLayout.NORTH)
        add(listPanel, BorderLayout.CENTER)

        // By default load lazily when user opens history.
        if (autoLoadOnInit) {
            loadSessions()
        } else {
            applyFilters()
        }
    }

    // ==================== toolbar / context actions ====================

    private fun loadAction(): AnAction = object : DumbAwareAction("Load", "Load selected session", AllIcons.Actions.Download) {
        override fun actionPerformed(e: AnActionEvent) {
            sessionList.selectedValue?.let { onLoadSession(it) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = sessionList.selectedValue != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun pinAction(): AnAction = object : DumbAwareAction("Pin", "Pin or unpin selected session", AllIcons.Nodes.Favorite) {
        override fun actionPerformed(e: AnActionEvent) {
            sessionList.selectedValue?.let { onTogglePin(it) }
        }

        override fun update(e: AnActionEvent) {
            val row = sessionList.selectedValue
            e.presentation.isEnabled = row != null
            e.presentation.text = if (row?.pinned == true) "Unpin" else "Pin"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun refreshAction(): AnAction = object : DumbAwareAction("Refresh", "Reload sessions", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = loadSessions()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * Mode and status filters live behind one popup button instead of a row of toggles, so the
     * toolbar still fits a narrow dock.
     */
    private fun modeFilterAction(): ActionGroup = object : ActionGroup("Filter", "Filter sessions", AllIcons.General.Filter), DumbAware {
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val modes = listOf<TaskMode?>(null) + TaskMode.entries
            val modeActions = modes.map { mode ->
                object : ToggleAction(mode?.name ?: "All modes"), DumbAware {
                    override fun isSelected(e: AnActionEvent) = modeFilter == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        modeFilter = mode
                        applyFilters()
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                }
            }
            val statusActions = StatusFilter.entries.map { filter ->
                object : ToggleAction(filter.label), DumbAware {
                    override fun isSelected(e: AnActionEvent) = statusFilter == filter
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        statusFilter = filter
                        applyFilters()
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                }
            }
            return (modeActions + listOf(Separator()) + statusActions).toTypedArray()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }.apply { isPopup = true }

    private fun sortAction(): ActionGroup = object : ActionGroup("Sort", "Sort order", AllIcons.ObjectBrowser.Sorted), DumbAware {
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val orders = SortOrder.entries.map { order ->
                object : ToggleAction(order.label), DumbAware {
                    override fun isSelected(e: AnActionEvent) = sortOrder == order
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        sortOrder = order
                        applyFilters()
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                }
            }
            val pinnedToggle = object : ToggleAction("Pinned first"), DumbAware {
                override fun isSelected(e: AnActionEvent) = groupPinned
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    groupPinned = state
                    applyFilters()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            }
            return (orders + listOf(Separator(), pinnedToggle)).toTypedArray()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }.apply { isPopup = true }

    private fun contextActions(): ActionGroup = DefaultActionGroup().apply {
        add(loadAction())
        add(pinAction())
        addSeparator()
        add(object : DumbAwareAction("Delete", "Delete selected session", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) = deleteSelected()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = sessionList.selectedValue != null
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })
    }

    // ==================== data ====================

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

    /** Typing must not rebuild the list on every keystroke. */
    private fun scheduleFilter() {
        searchAlarm.cancelAllRequests()
        searchAlarm.addRequest({ applyFilters() }, SEARCH_DEBOUNCE_MS)
    }

    /**
     * Apply search and filter to session list
     */
    private fun applyFilters() {
        val query = searchField.text.lowercase()

        val primary = sortComparator(sortOrder)
        val comparator: Comparator<Session> = if (groupPinned) {
            compareByDescending<Session> { it.pinned }.then(primary)
        } else {
            primary
        }

        val filtered = allSessions
            .filter { session ->
                val matchesQuery = query.isEmpty() ||
                    session.name.lowercase().contains(query) ||
                    session.id.lowercase().contains(query)

                val matchesMode = modeFilter == null || session.mode == modeFilter

                val matchesStatus = statusFilter.status == null || session.status == statusFilter.status

                matchesQuery && matchesMode && matchesStatus
            }
            .sortedWith(comparator)

        logger.debug { "applyFilters: ${filtered.size} sessions, sort=$sortOrder, status=$statusFilter" }

        val selectedId = sessionList.selectedValue?.id
        listModel.replaceAll(filtered.map { SessionRow.from(it, generationMillisBySession[it.id]) })
        if (selectedId != null) {
            val index = listModel.items.indexOfFirst { it.id == selectedId }
            if (index >= 0) sessionList.selectedIndex = index
        }
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

    // ==================== row actions ====================

    private fun onLoadSession(row: SessionRow) {
        logger.info { "Loading session: ${row.id}" }

        cs.launch {
            try {
                sessionManager.loadSession(row.id)

                logger.info { "Session loaded successfully: ${row.id}" }

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

    private fun onTogglePin(row: SessionRow) {
        cs.launch {
            try {
                val newPinned = !row.pinned
                logger.info { "Toggling pin for session ${row.id}: $newPinned" }

                sessionManager.apiRouter.taskRouter.updateTask(
                    row.id,
                    pl.jclab.refio.core.api.UpdateTaskRequest(pinned = newPinned)
                )

                loadSessions()
            } catch (e: Exception) {
                logger.error(e) { "Failed to toggle pin" }
                SwingUtilities.invokeLater {
                    showError("Failed to pin/unpin session: ${e.message}")
                }
            }
        }
    }

    private fun deleteSelected() {
        val row = sessionList.selectedValue ?: return

        val confirmed = MessageDialogBuilder
            .yesNo("Delete Session", "Delete session '${row.title}'?\n\nThis action cannot be undone.")
            .asWarning()
            .ask(this)

        if (!confirmed) return

        cs.launch {
            try {
                sessionManager.apiRouter.taskRouter.deleteTask(row.id)

                logger.info { "Deleted session: ${row.id}" }
                loadSessions()
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete session" }
                SwingUtilities.invokeLater {
                    showError("Failed to delete session: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        Messages.showErrorDialog(this, message, "Error")
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

        SwingUtilities.invokeLater {
            onNavigateToChat?.invoke()
            firePropertyChange("backToChat", false, true)
        }
    }

    override fun dispose() {
        searchAlarm.cancelAllRequests()
        cs.cancel()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200
    }
}
