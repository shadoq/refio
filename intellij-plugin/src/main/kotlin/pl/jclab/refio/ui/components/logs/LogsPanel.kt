package pl.jclab.refio.ui.components.logs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import pl.jclab.refio.services.logging.LogEntry
import pl.jclab.refio.services.logging.LogLevel
import pl.jclab.refio.services.logging.PluginLogger
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Logs panel for debugging plugin operations
 * Shows all log messages including API calls
 */
class LogsPanel(private val project: Project) : JBPanel<LogsPanel>(BorderLayout()) {

    private val cs = CoroutineScope(SupervisorJob())
    private val logger = PluginLogger.getInstance()

    private val tableModel = LogTableModel()
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private val table = JBTable(tableModel).apply {
        font = Font("Monospaced", Font.PLAIN, 12)
        setDefaultRenderer(Any::class.java, LogCellRenderer())
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        columnModel.getColumn(0).preferredWidth = 100  // Time
        columnModel.getColumn(1).preferredWidth = 60   // Level
        columnModel.getColumn(2).preferredWidth = 120  // Component
        columnModel.getColumn(3).preferredWidth = 600  // Message

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = rowAtPoint(e.point)
                    if (row >= 0) {
                        val entry = tableModel.getEntry(row)
                        showMessageDialog(entry)
                    }
                }
            }
        })
    }

    private val scrollPane = JBScrollPane(table)
    private var latestEntries: List<LogEntry> = emptyList()
    private var autoRefreshEnabled = true

    init {
        // Toolbar with buttons
        val toolbar = JPanel(BorderLayout()).apply {
            add(JLabel("Plugin Logs"), BorderLayout.WEST)

            val buttonPanel = JPanel().apply {
                add(JButton("Refresh").apply {
                    addActionListener {
                        refreshTable(latestEntries)
                    }
                })
                add(JButton("Copy Logs").apply {
                    addActionListener {
                        copyLogsToClipboard(latestEntries)
                    }
                })
                add(JToggleButton("Auto-refresh (1s)").apply {
                    isSelected = true
                    addActionListener { e ->
                        val button = e.source as JToggleButton
                        autoRefreshEnabled = button.isSelected
                        if (autoRefreshEnabled) {
                            refreshTable(latestEntries)
                        }
                    }
                })
                add(JButton("Clear").apply {
                    addActionListener { logger.clear() }
                })
            }
            add(buttonPanel, BorderLayout.EAST)
        }

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Listen to log entries
        cs.launch {
            logger.logEntries.collect { entries ->
                latestEntries = entries
                if (autoRefreshEnabled) {
                    SwingUtilities.invokeLater {
                        tableModel.updateEntries(entries)
                        // Auto-scroll to bottom
                        if (entries.isNotEmpty()) {
                            val lastRow = table.rowCount - 1
                            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true))
                        }
                    }
                }
            }
        }
    }

    /**
     * Show dialog with full message content
     */
    private fun showMessageDialog(entry: LogEntry) {
        LogEntryDetailsDialog(project, entry).show()
    }

    private fun refreshTable(entries: List<LogEntry>) {
        SwingUtilities.invokeLater {
            tableModel.updateEntries(entries)
            if (entries.isNotEmpty()) {
                val lastRow = table.rowCount - 1
                table.scrollRectToVisible(table.getCellRect(lastRow, 0, true))
            }
        }
    }

    private fun copyLogsToClipboard(entries: List<LogEntry>) {
        val content = buildString {
            entries.forEach { entry ->
                append(logTimeFormat.format(Date(entry.timestamp)))
                append(" [")
                append(entry.level)
                append("] ")
                append(entry.component)
                append(": ")
                appendLine(entry.message)
            }
        }
        // Logs can carry HTTP details - never export secrets via the clipboard
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(pl.jclab.refio.core.security.SecureLogger.redact(content)), null)
    }

    fun dispose() {
        cs.cancel()
    }
}

/**
 * Detail dialog for a single log entry. Uses DialogWrapper for proper modality,
 * parenting and ESC handling.
 */
private class LogEntryDetailsDialog(
    project: Project,
    private val entry: LogEntry
) : DialogWrapper(project) {

    init {
        title = "Log Entry Details"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val textArea = JTextArea().apply {
            text = buildString {
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(entry.timestamp))}")
                appendLine("Level: ${entry.level}")
                appendLine("Component: ${entry.component}")
                appendLine()
                appendLine("Message:")
                appendLine(entry.message)
            }
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font("Monospaced", Font.PLAIN, 12)
            caretPosition = 0
        }
        return JBScrollPane(textArea).apply {
            preferredSize = Dimension(800, 400)
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        val copyAction = object : AbstractAction("Copy Message") {
            override fun actionPerformed(e: ActionEvent?) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(
                    StringSelection(pl.jclab.refio.core.security.SecureLogger.redact(entry.message)),
                    null
                )
            }
        }
        return arrayOf(copyAction)
    }

    override fun createActions(): Array<Action> {
        okAction.putValue(Action.NAME, "Close")
        return arrayOf(okAction)
    }
}

/**
 * Table model for log entries
 */
private class LogTableModel : AbstractTableModel() {
    private var entries = listOf<LogEntry>()

    private val columnNames = arrayOf("Time", "Level", "Component", "Message")

    /**
     * Apply the latest log snapshot incrementally so the table keeps selection/scroll
     * and does not thrash. The underlying buffer only appends at the tail and drops the
     * oldest prefix once capped, so a change is expressed as a front deletion plus a tail
     * insertion. Anything that does not fit that shape (clear/replace) falls back to a
     * full reset.
     */
    fun updateEntries(newEntries: List<LogEntry>) {
        val old = entries
        if (old == newEntries) return

        val dropped = computeDroppedPrefix(old, newEntries)
        if (dropped < 0) {
            entries = newEntries
            fireTableDataChanged()
            return
        }

        val retained = old.size - dropped
        if (dropped > 0) {
            entries = old.subList(dropped, old.size).toList()
            fireTableRowsDeleted(0, dropped - 1)
        }
        entries = newEntries
        if (newEntries.size > retained) {
            fireTableRowsInserted(retained, newEntries.size - 1)
        }
    }

    /**
     * Returns how many leading rows were dropped if [new] equals [old] with a front prefix
     * removed and a tail appended, otherwise -1 (not a clean append/drop transition).
     */
    private fun computeDroppedPrefix(old: List<LogEntry>, new: List<LogEntry>): Int {
        for (dropped in 0..old.size) {
            val retained = old.size - dropped
            if (retained > new.size) continue
            var match = true
            for (i in 0 until retained) {
                if (old[dropped + i] != new[i]) {
                    match = false
                    break
                }
            }
            if (match) return dropped
        }
        return -1
    }

    fun getEntry(row: Int): LogEntry = entries[row]

    override fun getRowCount(): Int = entries.size

    override fun getColumnCount(): Int = columnNames.size

    override fun getColumnName(column: Int): String = columnNames[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = entries[rowIndex]
        return when (columnIndex) {
            0 -> SimpleDateFormat("HH:mm:ss.SSS").format(Date(entry.timestamp))
            1 -> entry.level
            2 -> entry.component
            3 -> entry.message
            else -> ""
        }
    }
}

/**
 * Custom cell renderer for log entries with color coding and tooltips
 */
private class LogCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

        // Color code log levels
        if (!isSelected && column == 1 && value is LogLevel) {
            component.foreground = when (value) {
                LogLevel.DEBUG -> JBColor(Gray._110, Gray._164)  // Gray
                LogLevel.INFO -> JBColor(Color(0, 128, 0), Color(164, 255, 164))       // Green
                LogLevel.WARN -> JBColor(Color(160, 90, 0), Color(255, 165, 164))      // Orange
                LogLevel.ERROR -> JBColor(Color(178, 34, 34), Color(255, 164, 164))    // Red
                LogLevel.HTTP -> JBColor(Color(0, 0, 200), Color(164, 164, 255))       // Blue
            }
        }

        // Add tooltip for message column (column 3)
        if (column == 3 && value != null) {
            val message = value.toString()
            toolTipText = if (message.length > 50) {
                val escaped = message
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;")
                "<html>${escaped.replace("\n", "<br>")}</html>"
            } else {
                null
            }
        } else {
            toolTipText = null
        }

        return component
    }
}
