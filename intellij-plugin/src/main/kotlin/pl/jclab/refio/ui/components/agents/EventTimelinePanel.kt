package pl.jclab.refio.ui.components.agents

import pl.jclab.refio.core.agents.events.AgentEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Scrollable timeline of agent events rendered as a table.
 *
 * Columns: Time | Kind | Iter | Name/Summary | Model | Tokens | Cost | Duration | Status
 * A footer shows per-session aggregate totals pulled from [GlobalMetrics.forAgent].
 */
class EventTimelinePanel : JPanel(BorderLayout()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    private val columns = arrayOf(
        "Time", "Kind", "Iter", "Name / Summary",
        "Model", "Tokens (in/out)", "Cost", "Duration", "Status"
    )
    private val model = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JTable(model)

    // Footer labels
    private val footerLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    // Current session id
    private var currentSessionId: String? = null

    // Session totals aggregated locally from received events.
    // We deliberately do NOT read GlobalMetrics here because when a session is
    // reloaded from history its GlobalMetrics entry starts empty, but the
    // persisted events still carry the correct totals.
    private var sessionTokensIn: Long = 0
    private var sessionTokensOut: Long = 0
    private var sessionCost: Double = 0.0

    init {
        table.font = table.font.deriveFont(11f)
        table.rowHeight = 20
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.columnModel.getColumn(0).preferredWidth = 90   // Time
        table.columnModel.getColumn(1).preferredWidth = 70   // Kind
        table.columnModel.getColumn(2).preferredWidth = 40   // Iter
        table.columnModel.getColumn(3).preferredWidth = 340  // Name
        table.columnModel.getColumn(4).preferredWidth = 140  // Model
        table.columnModel.getColumn(5).preferredWidth = 110  // Tokens
        table.columnModel.getColumn(6).preferredWidth = 70   // Cost
        table.columnModel.getColumn(7).preferredWidth = 80   // Duration
        table.columnModel.getColumn(8).preferredWidth = 70   // Status

        // Right-align numeric columns
        val rightRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.RIGHT }
        }
        listOf(2, 5, 6, 7).forEach {
            table.columnModel.getColumn(it).cellRenderer = rightRenderer
        }

        // Color status column by success/failure
        table.columnModel.getColumn(8).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
                row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                val str = value?.toString() ?: ""
                if (!isSelected) {
                    foreground = when {
                        str.startsWith("OK") || str == "DONE" -> Color(34, 139, 34)
                        str == "FAIL" || str == "ERR" -> Color(200, 50, 50)
                        else -> UIManager.getColor("Table.foreground") ?: Color.WHITE
                    }
                }
                horizontalAlignment = SwingConstants.CENTER
                return c
            }
        }

        add(JScrollPane(table), BorderLayout.CENTER)

        val footer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(footerLabel)
        }
        add(footer, BorderLayout.SOUTH)
        updateFooter()
    }

    /**
     * Bind this panel to a session id. The footer aggregates totals locally from
     * LLMCallCompleted events so it works for both live and replayed sessions.
     */
    fun setSession(sessionId: String?) {
        currentSessionId = sessionId
        SwingUtilities.invokeLater { updateFooter() }
    }

    fun addEvent(event: AgentEvent) {
        val time = dateFormat.format(Date(event.timestamp))
        val row: Array<Any?> = when (event) {
            is AgentEvent.AgentStarted -> arrayOf(
                time, "START", "-", "${event.agentName} — ${event.task.take(80)}",
                event.model ?: "-", "-", "-", "-", "…"
            )
            is AgentEvent.AgentCompleted -> arrayOf(
                time, "DONE", "-", "agent ${event.sourceAgentId.take(8)}",
                "-", "${event.tokensUsed}", formatCost(event.costUsd), formatDuration(event.durationMs), "OK"
            )
            is AgentEvent.AgentFailed -> arrayOf(
                time, "FAIL", "-", "${event.sourceAgentId.take(8)}: ${event.error.take(80)}",
                "-", "-", "-", "-", "FAIL"
            )
            is AgentEvent.TurnStarted -> arrayOf(
                time, "TURN", "${event.iteration}/${event.maxIterations}",
                "turn started (${event.mode})", "-", "-", "-", "-", "…"
            )
            is AgentEvent.TurnEnded -> arrayOf(
                time, "TURN-END", "${event.iteration}",
                "turn ended", "-", "-", "-", formatDuration(event.durationMs), "OK"
            )
            is AgentEvent.LLMCallCompleted -> arrayOf(
                time, "LLM", "${event.iteration}",
                "gen (${event.finishReason ?: "-"})",
                shortenModel(event.model),
                "${event.tokensIn} / ${event.tokensOut}",
                formatCost(event.costUsd),
                formatDuration(event.durationMs),
                "OK"
            )
            is AgentEvent.ToolCalled -> arrayOf(
                time, "TOOL", "${event.iteration}",
                "${event.toolName} — ${event.argumentsPreview.take(70)}",
                "-", "-", "-",
                formatDuration(event.durationMs),
                if (event.success) "OK" else "ERR"
            )
            is AgentEvent.DataRequest -> arrayOf(
                time, "MSG", "-",
                "${event.sourceAgentId.take(8)} → ${event.targetAgentId?.take(8) ?: "parent"}: ${event.query.take(80)}",
                "-", "-", "-", "-", "…"
            )
            is AgentEvent.DataResponse -> arrayOf(
                time, "RSP", "-",
                "${event.sourceAgentId.take(8)} → ${event.targetAgentId.take(8)}: ${event.response.take(80)}",
                "-", "-", "-", "-", "OK"
            )
            is AgentEvent.ArtifactProduced -> arrayOf(
                time, "ART", "-",
                "${event.sourceAgentId.take(8)}: ${event.artifact.name}",
                "-", "-", "-", "-", "OK"
            )
            is AgentEvent.SpawnAgentRequest -> arrayOf(
                time, "SPAWN", "-",
                "${event.requestedProfile} — ${event.task.take(80)}",
                "-", "-", "-", "-", "…"
            )
            else -> arrayOf(time, event::class.simpleName ?: "EVT", "-", "", "-", "-", "-", "-", "-")
        }

        // Accumulate session totals from token-bearing events
        when (event) {
            is AgentEvent.LLMCallCompleted -> {
                sessionTokensIn += event.tokensIn
                sessionTokensOut += event.tokensOut
                sessionCost += event.costUsd
            }
            is AgentEvent.AgentCompleted -> {
                sessionCost += event.costUsd
            }
            else -> { /* no token accounting */ }
        }

        SwingUtilities.invokeLater {
            model.addRow(row)
            val lastRow = model.rowCount - 1
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true))
            updateFooter()
        }
    }

    fun clear() {
        sessionTokensIn = 0
        sessionTokensOut = 0
        sessionCost = 0.0
        SwingUtilities.invokeLater {
            model.rowCount = 0
            updateFooter()
        }
    }

    /**
     * Render session totals in the footer using locally-aggregated event data.
     */
    private fun updateFooter() {
        val sid = currentSessionId
        if (sid == null) {
            footerLabel.text = "  No active session"
            return
        }
        footerLabel.text = "  Session: ${sid.take(8)} · " +
            "tokens in/out: $sessionTokensIn / $sessionTokensOut · " +
            "cost: ${formatCost(sessionCost)} · " +
            "events: ${model.rowCount}"
    }

    private fun formatCost(cost: Double): String =
        if (cost <= 0.0) "-" else "$%.4f".format(cost)

    private fun formatDuration(ms: Long): String =
        when {
            ms <= 0 -> "-"
            ms < 1000 -> "${ms}ms"
            else -> "%.2fs".format(ms / 1000.0)
        }

    private fun shortenModel(model: String): String =
        model.substringAfter('/').take(22)
}
