package pl.jclab.refio.ui.components.agents

import pl.jclab.refio.core.agents.events.AgentEvent
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

/**
 * Scrollable timeline of agent events (lifecycle, data exchange, etc.)
 */
class EventTimelinePanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<String>()
    private val eventList = JList(listModel)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    init {
        eventList.font = eventList.font.deriveFont(11f)
        eventList.cellRenderer = DefaultListCellRenderer()
        add(JScrollPane(eventList), BorderLayout.CENTER)
    }

    fun addEvent(event: AgentEvent) {
        val time = dateFormat.format(Date(event.timestamp))
        val summary = when (event) {
            is AgentEvent.AgentStarted -> "[START] ${event.agentName} — ${event.task.take(60)}"
            is AgentEvent.AgentCompleted -> "[DONE] agent ${event.sourceAgentId.take(8)} — ${event.durationMs}ms, ${event.tokensUsed}t"
            is AgentEvent.AgentFailed -> "[FAIL] agent ${event.sourceAgentId.take(8)} — ${event.error.take(60)}"
            is AgentEvent.DataRequest -> "[MSG] ${event.sourceAgentId.take(8)} → ${event.targetAgentId?.take(8) ?: "parent"}: ${event.query.take(60)}"
            is AgentEvent.DataResponse -> "[RSP] ${event.sourceAgentId.take(8)} → ${event.targetAgentId.take(8)}: ${event.response.take(60)}"
            is AgentEvent.ArtifactProduced -> "[ART] ${event.sourceAgentId.take(8)}: ${event.artifact.name}"
            is AgentEvent.SpawnAgentRequest -> "[SPAWN] ${event.requestedProfile} — ${event.task.take(60)}"
            else -> "[EVT] ${event::class.simpleName}"
        }
        SwingUtilities.invokeLater {
            listModel.addElement("$time $summary")
            eventList.ensureIndexIsVisible(listModel.size - 1)
        }
    }

    fun clear() {
        listModel.clear()
    }
}
