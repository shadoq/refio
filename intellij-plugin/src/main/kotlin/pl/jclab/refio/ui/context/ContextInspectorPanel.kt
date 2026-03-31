package pl.jclab.refio.ui.context

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.jclab.refio.core.services.context.ContextPriority
import pl.jclab.refio.core.services.turn.ContextSectionRecord
import pl.jclab.refio.core.services.turn.PromptSnapshot
import pl.jclab.refio.services.session.SessionManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Context Inspector panel — shows what entered the LLM prompt,
 * token usage per section, and what was dropped.
 */
class ContextInspectorPanel(
    sessionManager: SessionManager,
    scope: CoroutineScope
) : JPanel(BorderLayout()) {

    private val viewModel = ContextInspectorViewModel(sessionManager, scope)
    private val tableModel = ContextSectionsTableModel()
    private val table = JBTable(tableModel)

    private val totalLabel = JBLabel("No prompt data yet")
    private val includedLabel = JBLabel("")
    private val droppedLabel = JBLabel("")

    init {
        // Summary bar
        val summaryPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 4))
        summaryPanel.add(totalLabel)
        summaryPanel.add(includedLabel)
        summaryPanel.add(droppedLabel)
        add(summaryPanel, BorderLayout.NORTH)

        // Table
        table.setShowGrid(true)
        table.rowHeight = 24
        val scrollPane = JBScrollPane(table)
        add(scrollPane, BorderLayout.CENTER)

        // Start observing
        viewModel.start()
        scope.launch(Dispatchers.IO) {
            viewModel.snapshot.collect { snapshot ->
                SwingUtilities.invokeLater { updateUI(snapshot) }
            }
        }
    }

    private fun updateUI(snapshot: PromptSnapshot?) {
        if (snapshot == null) {
            totalLabel.text = "No prompt data yet"
            includedLabel.text = ""
            droppedLabel.text = ""
            tableModel.updateSections(emptyList())
            return
        }

        val trace = snapshot.contextTrace
        totalLabel.text = "Tokens: ${trace.totalUsed} / ${trace.totalBudget}"
        includedLabel.text = "Included: ${trace.includedSections.size}"
        droppedLabel.text = "Dropped: ${trace.droppedCount}"
        droppedLabel.foreground = if (trace.droppedCount > 0) JBColor.ORANGE else JBColor.foreground()

        tableModel.updateSections(trace.sections)
    }
}

/**
 * Table model for context section records.
 */
data class ContextSectionTableRow(
    val section: String,
    val priority: String,
    val tokens: String,
    val status: String,
    val reason: String
)

class ContextSectionsTableModel : AbstractTableModel() {

    private val columns = arrayOf("Section", "Priority", "Tokens", "Status", "Reason")
    private var rows: List<ContextSectionTableRow> = emptyList()

    fun updateRows(newRows: List<ContextSectionTableRow>) {
        rows = newRows
        fireTableDataChanged()
    }

    fun updateSections(newSections: List<ContextSectionRecord>) {
        updateRows(newSections.map { it.toTableRow() })
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val record = rows[rowIndex]
        return when (columnIndex) {
            0 -> record.section
            1 -> record.priority
            2 -> record.tokens
            3 -> record.status
            4 -> record.reason
            else -> ""
        }
    }
}

fun ContextSectionRecord.toTableRow(): ContextSectionTableRow {
    val status = when {
        !included -> "DROPPED"
        dropReason != null -> dropReason?.name ?: ""
        else -> "INCLUDED"
    }

    return ContextSectionTableRow(
        section = section.name,
        priority = priority.name,
        tokens = (actualTokens ?: estimatedTokens).toString(),
        status = status,
        reason = dropReason?.name ?: ""
    )
}

fun ContextPriority.asDisplayName(): String = name
