package pl.jclab.refio.ui.components.logs

import com.intellij.openapi.project.Project
import com.intellij.ui.Gray
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
import java.awt.Dialog
import java.awt.Dimension
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
    private var autoRefreshJob: Job? = null

    init {
        // Toolbar with buttons
        val toolbar = JPanel(BorderLayout()).apply {
            add(JLabel("Plugin Logs"), BorderLayout.WEST)

            val buttonPanel = JPanel().apply {
                add(JButton("Refresh").apply {
                    addActionListener {
                        // Force UI refresh
                        SwingUtilities.invokeLater {
                            tableModel.updateEntries(logger.logEntries.value)
                        }
                    }
                })
                add(JToggleButton("Auto-refresh (1s)").apply {
                    isSelected = true
                    addActionListener { e ->
                        val button = e.source as JToggleButton
                        if (button.isSelected) {
                            startAutoRefresh()
                        } else {
                            stopAutoRefresh()
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

        // Start auto-refresh
        startAutoRefresh()
    }

    /**
     * Show dialog with full message content
     */
    private fun showMessageDialog(entry: LogEntry) {
        val dialog = JDialog(SwingUtilities.getWindowAncestor(this), "Log Entry Details", Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

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

        val scrollPane = JScrollPane(textArea)
        scrollPane.preferredSize = Dimension(800, 400)

        val buttonPanel = JPanel(BorderLayout()).apply {
            add(JButton("Copy Message").apply {
                addActionListener {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(entry.message), null)
                }
            }, BorderLayout.WEST)

            add(JButton("Close").apply {
                addActionListener { dialog.dispose() }
            }, BorderLayout.EAST)
        }

        dialog.layout = BorderLayout()
        dialog.add(scrollPane, BorderLayout.CENTER)
        dialog.add(buttonPanel, BorderLayout.SOUTH)
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    /**
     * Start auto-refresh every 1 second
     */
    private fun startAutoRefresh() {
        stopAutoRefresh()

        autoRefreshJob = cs.launch {
            while (isActive) {
                delay(1000) // 1 second
                // Force UI refresh
                SwingUtilities.invokeLater {
                    tableModel.updateEntries(logger.logEntries.value)
                }
            }
        }
    }

    /**
     * Stop auto-refresh
     */
    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun dispose() {
        stopAutoRefresh()
        cs.cancel()
    }
}

/**
 * Table model for log entries
 */
private class LogTableModel : AbstractTableModel() {
    private var entries = listOf<LogEntry>()

    private val columnNames = arrayOf("Time", "Level", "Component", "Message")

    fun updateEntries(newEntries: List<LogEntry>) {
        entries = newEntries
        fireTableDataChanged()
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
                LogLevel.DEBUG -> Gray._164  // Gray
                LogLevel.INFO -> Color(164, 255, 164)       // Green
                LogLevel.WARN -> Color(255, 165, 164)     // Orange
                LogLevel.ERROR -> Color(255, 164, 164)      // Red
                LogLevel.HTTP -> Color(164, 164, 255)       // Blue
            }
        }

        // Add tooltip for message column (column 3)
        if (column == 3 && value != null) {
            val message = value.toString()
            toolTipText = if (message.length > 50) {
                "<html>${message.replace("\n", "<br>")}</html>"
            } else {
                null
            }
        } else {
            toolTipText = null
        }

        return component
    }
}
