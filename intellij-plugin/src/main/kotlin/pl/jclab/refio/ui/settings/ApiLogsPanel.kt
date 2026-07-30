package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import pl.jclab.refio.core.db.ApiLog
import pl.jclab.refio.core.db.ApiLogStatistics
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.DefaultTableModel

/**
 * API Logs Panel
 *
 * Displays API call logs with filtering, export, and management capabilities
 */
class ApiLogsPanel(
    private val coreApiClient: pl.jclab.refio.core.api.CoreApiRouter?,
    private val autoLoadOnInit: Boolean = true
) : JBPanel<ApiLogsPanel>(BorderLayout()) {

    private val logger = dualLogger("ApiLogsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    // UI Components
    private val statisticsPanel = JBPanel<JBPanel<*>>(GridLayout(1, 5, LCATheme.padding, 0))
    private val tableModel = DefaultTableModel()
    private val logsTable = JBTable(tableModel)
    private val filterProviderCombo = JComboBox<String>()
    private val filterModelCombo = JComboBox<String>()
    private val filterSourceCombo = JComboBox<String>()
    private val logCountLabel = JBLabel()
    private val loadMoreButton = JButton("Load more")

    // Pagination: the router only supports a row limit (no offset), so paging
    // is done by refetching with a larger limit. Ordering stays stable (DESC by createdAt).
    private val pageSize = 50
    private var currentLimit = pageSize

    // Data
    private var allLogs = listOf<ApiLog>()
    // Logs currently rendered in the table (used to preserve selection across a rebuild).
    private var tableLogs = listOf<ApiLog>()
    private var statistics: ApiLogStatistics? = null
    @Volatile
    private var isLoading = false
    @Volatile
    private var hasLoaded = false

    // Statistics labels
    private val totalCallsLabel = JBLabel()
    private val totalCostLabel = JBLabel()
    private val totalTokensLabel = JBLabel()
    private val avgLatencyLabel = JBLabel()
    private val errorCountLabel = JBLabel()

    init {
        border = JBUI.Borders.empty(LCATheme.margin)

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Statistics section
        mainPanel.add(createStatisticsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Filters section
        mainPanel.add(createFiltersSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Table section
        mainPanel.add(createTableSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Actions section
        mainPanel.add(createActionsSection())

        add(mainPanel, BorderLayout.CENTER)

        // Load lazily when API Logs tab is opened.
        if (autoLoadOnInit) {
            reload()
        }
    }

    private fun createStatisticsSection(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createTitledBorder("Global Statistics")

            statisticsPanel.apply {
                add(createStatBox("Total Calls", totalCallsLabel))
                add(createStatBox("Total Cost (USD)", totalCostLabel))
                add(createStatBox("Total Tokens", totalTokensLabel))
                add(createStatBox("Avg Latency (ms)", avgLatencyLabel))
                add(createStatBox("Errors", errorCountLabel))
            }

            add(statisticsPanel, BorderLayout.CENTER)
        }
    }

    private fun createStatBox(title: String, valueLabel: JBLabel): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(LCATheme.padding)

            add(JBLabel(title).apply {
                font = LCATheme.smallFont
                foreground = LCATheme.descriptionForeground
                alignmentX = Component.CENTER_ALIGNMENT
            })

            valueLabel.apply {
                font = LCATheme.largeBoldFont
                alignmentX = Component.CENTER_ALIGNMENT
                text = "-"
            }
            add(valueLabel)
        }
    }

    private fun createFiltersSection(): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
            border = LCATheme.createTitledBorder("Filters")

            // Provider filter
            add(JBLabel("Provider:"))
            filterProviderCombo.apply {
                addItem("All")
                addActionListener { applyFilters() }
            }
            add(filterProviderCombo)

            // Model filter
            add(JBLabel("Model:"))
            filterModelCombo.apply {
                addItem("All")
                addActionListener { applyFilters() }
            }
            add(filterModelCombo)

            // Source filter
            add(JBLabel("Source:"))
            filterSourceCombo.apply {
                addItem("All")
                addActionListener { applyFilters() }
            }
            add(filterSourceCombo)

            // Clear filters button
            add(JButton("Clear Filters").apply {
                addActionListener { clearFilters() }
            })
        }
    }

    private fun createTableSection(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createTitledBorder("Recent Logs")

            // Setup table model
            tableModel.setColumnIdentifiers(
                arrayOf(
                    "Timestamp",
                    "Provider",
                    "Model",
                    "Source",
                    "Input Tokens",
                    "Output Tokens",
                    "Cost (USD)",
                    "Latency (ms)",
                    "Status"
                )
            )

            logsTable.apply {
                setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                autoCreateRowSorter = true
                fillsViewportHeight = true

                // Double-click to view details
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) {
                            viewSelectedLogDetails()
                        }
                    }
                })

                fitColumns(
                    ColumnWidth(min = 50, preferred = 130),  // Timestamp
                    ColumnWidth(min = 36, preferred = 90),   // Provider
                    ColumnWidth(min = 44, preferred = 130),  // Model
                    ColumnWidth(min = 36, preferred = 90),   // Source
                    ColumnWidth(min = 30, preferred = 80),   // Input Tokens
                    ColumnWidth(min = 30, preferred = 80),   // Output Tokens
                    ColumnWidth(min = 30, preferred = 80),   // Cost
                    ColumnWidth(min = 30, preferred = 80),   // Latency
                    ColumnWidth(min = 30, preferred = 70)    // Status
                )
                showFullValueOnHover()
            }

            val scrollPane = JBScrollPane(logsTable).apply {
                // Height only: a fixed width here would decide how wide the whole page is.
                preferredSize = Dimension(0, JBUI.scale(320))
                border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
            }

            add(scrollPane, BorderLayout.CENTER)

            // Bottom bar: "Showing X of N" counter + Load more control.
            val footer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(LCATheme.gap)

                logCountLabel.apply {
                    font = LCATheme.smallFont
                    foreground = LCATheme.descriptionForeground
                    text = "-"
                }
                add(logCountLabel, BorderLayout.WEST)

                loadMoreButton.apply {
                    isEnabled = false
                    addActionListener { loadMore() }
                }
                add(loadMoreButton, BorderLayout.EAST)
            }
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun loadMore() {
        currentLimit += pageSize
        // applyFilters honors currentLimit and works for both filtered and unfiltered views.
        applyFilters()
    }

    private fun hasActiveFilter(): Boolean {
        return listOf(filterProviderCombo, filterModelCombo, filterSourceCombo).any {
            (it.selectedItem as? String)?.takeIf { s -> s != "All" } != null
        }
    }

    private fun createActionsSection(): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
            border = LCATheme.createTitledBorder("Actions")

            add(JButton("Refresh").apply {
                addActionListener { reload() }
            })

            add(JButton("View Details").apply {
                addActionListener { viewSelectedLogDetails() }
            })

            add(JButton("Export CSV").apply {
                addActionListener { exportToCsv() }
            })

            add(JButton("Export JSON").apply {
                addActionListener { exportToJson() }
            })

            add(JButton("Delete All Logs").apply {
                foreground = LCATheme.errorColor
                addActionListener { deleteAllLogs() }
            })
        }
    }

    fun reload() {
        if (isLoading) {
            logger.debug { "Reload skipped - API logs load already in progress" }
            return
        }

        coroutineScope.launch {
            try {
                isLoading = true
                logger.info { "Loading API logs..." }

                // Load statistics
                val stats = coreApiClient?.apiLogsRouter?.getApiLogStatistics()
                statistics = stats

                // Load logs
                val logs = coreApiClient?.apiLogsRouter?.getRecentApiLogs(currentLimit) ?: emptyList()
                allLogs = logs

                // Load filter options
                val providers = coreApiClient?.apiLogsRouter?.getDistinctProviders() ?: emptyList()
                val models = coreApiClient?.apiLogsRouter?.getDistinctModels() ?: emptyList()
                val sources = coreApiClient?.apiLogsRouter?.getDistinctSources() ?: emptyList()

                ApplicationManager.getApplication().invokeLater {
                    updateStatistics(stats)
                    updateFilterOptions(providers, models, sources)
                    updateTable(logs)
                }

                hasLoaded = true
                logger.info { "Loaded ${logs.size} API logs" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load API logs" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to load API logs: ${e.message}")
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun ensureLoaded() {
        if (!hasLoaded) {
            reload()
        }
    }

    private fun updateStatistics(stats: ApiLogStatistics?) {
        if (stats != null) {
            totalCallsLabel.text = formatNumber(stats.totalCalls)
            totalCostLabel.text = String.format("$%.6f", stats.totalCost)
            totalTokensLabel.text = formatNumber(stats.totalInputTokens + stats.totalOutputTokens)
            avgLatencyLabel.text = formatNumber(stats.avgLatencyMs)
            errorCountLabel.text = formatNumber(stats.errorCount)

            // Color error count if there are errors
            errorCountLabel.foreground = if (stats.errorCount > 0) {
                LCATheme.errorColor
            } else {
                LCATheme.successColor
            }
        } else {
            totalCallsLabel.text = "-"
            totalCostLabel.text = "-"
            totalTokensLabel.text = "-"
            avgLatencyLabel.text = "-"
            errorCountLabel.text = "-"
        }
    }

    private fun updateFilterOptions(providers: List<String>, models: List<String>, sources: List<String>) {
        // Remember current selections
        val selectedProvider = filterProviderCombo.selectedItem
        val selectedModel = filterModelCombo.selectedItem
        val selectedSource = filterSourceCombo.selectedItem

        // Update provider combo
        filterProviderCombo.removeAllItems()
        filterProviderCombo.addItem("All")
        providers.forEach { filterProviderCombo.addItem(it) }
        if (providers.contains(selectedProvider)) {
            filterProviderCombo.selectedItem = selectedProvider
        }

        // Update model combo
        filterModelCombo.removeAllItems()
        filterModelCombo.addItem("All")
        models.forEach { filterModelCombo.addItem(it) }
        if (models.contains(selectedModel)) {
            filterModelCombo.selectedItem = selectedModel
        }

        // Update source combo
        filterSourceCombo.removeAllItems()
        filterSourceCombo.addItem("All")
        sources.forEach { filterSourceCombo.addItem(it) }
        if (sources.contains(selectedSource)) {
            filterSourceCombo.selectedItem = selectedSource
        }
    }

    private fun updateTable(logs: List<ApiLog>) {
        // Preserve the selected row by log id across the full model rebuild.
        val selectedLogId = logsTable.selectedRow
            .takeIf { it >= 0 }
            ?.let { logsTable.convertRowIndexToModel(it) }
            ?.let { tableLogs.getOrNull(it)?.id }

        tableModel.rowCount = 0

        logs.forEach { log ->
            val statusText = when {
                log.errorMessage != null -> "Error"
                log.httpStatus != null && log.httpStatus in 200..299 -> "OK"
                log.httpStatus != null -> "HTTP ${log.httpStatus}"
                else -> "-"
            }

            tableModel.addRow(
                arrayOf(
                    dateFormat.format(Date(log.createdAt)),
                    log.provider,
                    log.model,
                    log.requestSource ?: "-",
                    formatNumber(log.inputTokens),
                    formatNumber(log.outputTokens),
                    String.format("%.6f", log.costUsd),
                    formatNumber(log.latencyMs),
                    statusText
                )
            )
        }

        tableLogs = logs

        // Restore selection if the previously selected log is still present.
        if (selectedLogId != null) {
            val modelIndex = logs.indexOfFirst { it.id == selectedLogId }
            if (modelIndex >= 0) {
                val viewIndex = logsTable.convertRowIndexToView(modelIndex)
                if (viewIndex >= 0) {
                    logsTable.setRowSelectionInterval(viewIndex, viewIndex)
                }
            }
        }

        updateLogCounter(logs.size)
    }

    private fun updateLogCounter(displayed: Int) {
        val total = statistics?.totalCalls ?: displayed.toLong()
        logCountLabel.text = if (hasActiveFilter()) {
            "Showing ${formatNumber(displayed)} filtered (of ${formatNumber(total)} total)"
        } else {
            "Showing ${formatNumber(displayed)} of ${formatNumber(total)}"
        }
        // A full page likely means there are older entries to load.
        loadMoreButton.isEnabled = displayed >= currentLimit
    }

    private fun applyFilters() {
        coroutineScope.launch {
            try {
                val selectedProvider = filterProviderCombo.selectedItem as? String
                val selectedModel = filterModelCombo.selectedItem as? String
                val selectedSource = filterSourceCombo.selectedItem as? String

                val provider = if (selectedProvider == "All") null else selectedProvider
                val model = if (selectedModel == "All") null else selectedModel
                val source = if (selectedSource == "All") null else selectedSource

                val filteredLogs = coreApiClient?.apiLogsRouter?.getFilteredApiLogs(
                    provider = provider,
                    model = model,
                    source = source,
                    limit = currentLimit
                ) ?: emptyList()

                allLogs = filteredLogs

                ApplicationManager.getApplication().invokeLater {
                    updateTable(filteredLogs)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to apply filters" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to apply filters: ${e.message}")
                }
            }
        }
    }

    private fun clearFilters() {
        filterProviderCombo.selectedItem = "All"
        filterModelCombo.selectedItem = "All"
        filterSourceCombo.selectedItem = "All"
        reload()
    }

    private fun viewSelectedLogDetails() {
        val selectedRow = logsTable.selectedRow
        if (selectedRow < 0) {
            showInfo("Please select a log entry to view details")
            return
        }

        val log = allLogs.getOrNull(selectedRow)
        if (log != null) {
            val dialog = ApiLogDetailsDialog(this, log)
            dialog.show()
        }
    }

    private fun exportToCsv() {
        coroutineScope.launch {
            try {
                logger.info { "Exporting all API logs to CSV..." }
                val csvContent = coreApiClient?.apiLogsRouter?.exportAllApiLogsToCsv()
                    ?: throw IllegalStateException("Failed to export logs")

                ApplicationManager.getApplication().invokeLater {
                    saveToFile(csvContent, "api-logs.csv", "CSV Files", "csv")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to export to CSV" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to export to CSV: ${e.message}")
                }
            }
        }
    }

    private fun exportToJson() {
        coroutineScope.launch {
            try {
                logger.info { "Exporting all API logs to JSON..." }
                val jsonContent = coreApiClient?.apiLogsRouter?.exportAllApiLogsToJson()
                    ?: throw IllegalStateException("Failed to export logs")

                ApplicationManager.getApplication().invokeLater {
                    saveToFile(jsonContent, "api-logs.json", "JSON Files", "json")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to export to JSON" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to export to JSON: ${e.message}")
                }
            }
        }
    }

    private fun saveToFile(content: String, defaultFileName: String, filterDescription: String, filterExtension: String) {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save Export"
            selectedFile = File(defaultFileName)
            fileFilter = FileNameExtensionFilter(filterDescription, filterExtension)
        }

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val targetFile = fileChooser.selectedFile
            // File IO off the EDT; UI feedback back on the EDT.
            coroutineScope.launch {
                try {
                    targetFile.writeText(content)
                    ApplicationManager.getApplication().invokeLater {
                        showInfo("Export saved to:\n${targetFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to save export file" }
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to save file:\n${e.message}")
                    }
                }
            }
        }
    }

    private fun deleteAllLogs() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to delete all API logs?\n" +
                    "This operation cannot be undone.\n\n" +
                    "A total of ${statistics?.totalCalls ?: 0} logs will be deleted.",
            "Confirm deletion",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Deleting all API logs..." }
                val deleted = coreApiClient?.apiLogsRouter?.deleteAllApiLogs()
                    ?: throw IllegalStateException("Failed to delete logs")

                ApplicationManager.getApplication().invokeLater {
                    showInfo("$deleted API logs have been deleted")
                    reload()
                }

                logger.info { "Deleted $deleted API logs" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete API logs" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to delete logs: ${e.message}")
                }
            }
        }
    }

    private fun formatNumber(number: Long): String {
        return String.format("%,d", number)
    }

    private fun formatNumber(number: Int): String {
        return String.format("%,d", number)
    }

    private fun showInfo(message: String) {
        JOptionPane.showMessageDialog(
            this,
            message,
            "Information",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(
            this,
            message,
            "Error",
            JOptionPane.ERROR_MESSAGE
        )
    }

    fun dispose() {
        coroutineScope.cancel()
        logger.debug { "ApiLogsPanel cleanup completed" }
    }
}
