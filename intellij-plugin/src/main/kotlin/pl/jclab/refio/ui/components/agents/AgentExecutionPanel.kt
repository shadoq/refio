package pl.jclab.refio.ui.components.agents

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import java.awt.BorderLayout
import javax.swing.*

/**
 * Main panel for multi-agent execution visualization.
 *
 * Layout:
 * - NORTH: Header with title
 * - CENTER: Split between agent graph (top) and event timeline (bottom)
 * - SOUTH: Control bar (Clear button)
 */
class AgentExecutionPanel : JPanel(BorderLayout()), Disposable {

    private val graphPanel = AgentGraphPanel()
    private val timelinePanel = EventTimelinePanel()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val agentNames = mutableMapOf<String, String>() // agentId → human name

    private var eventBus: AgentEventBus? = null

    init {
        val header = JLabel("  Multi-Agent Execution").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
        }
        add(header, BorderLayout.NORTH)

        val splitPane = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(graphPanel),
            timelinePanel
        ).apply {
            resizeWeight = 0.5
            dividerLocation = 200
        }
        add(splitPane, BorderLayout.CENTER)

        val controlBar = JPanel().apply {
            val clearBtn = JButton("Clear").apply {
                addActionListener {
                    graphPanel.clear()
                    timelinePanel.clear()
                }
            }
            add(clearBtn)
        }
        add(controlBar, BorderLayout.SOUTH)
    }

    /**
     * Subscribe to events from AgentEventBus for a given session.
     */
    fun subscribeToSession(eventBus: AgentEventBus, sessionId: String) {
        this.eventBus = eventBus
        scope.launch {
            eventBus.sessionEvents(sessionId).collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        SwingUtilities.invokeLater {
            timelinePanel.addEvent(event)

            when (event) {
                is AgentEvent.AgentStarted -> {
                    agentNames[event.sourceAgentId] = event.agentName
                    graphPanel.addOrUpdateAgent(
                        agentId = event.sourceAgentId,
                        name = event.agentName,
                        depth = 0,
                        status = AgentNodeStatus.RUNNING
                    )
                }
                is AgentEvent.AgentCompleted -> {
                    val name = agentNames[event.sourceAgentId] ?: event.sourceAgentId.take(8)
                    graphPanel.addOrUpdateAgent(
                        agentId = event.sourceAgentId,
                        name = name,
                        depth = 0,
                        status = AgentNodeStatus.COMPLETED
                    )
                    graphPanel.updateMetrics(
                        agentId = event.sourceAgentId,
                        iterations = 0,
                        tokens = event.tokensUsed,
                        durationMs = event.durationMs
                    )
                }
                is AgentEvent.AgentFailed -> {
                    val name = agentNames[event.sourceAgentId] ?: event.sourceAgentId.take(8)
                    graphPanel.addOrUpdateAgent(
                        agentId = event.sourceAgentId,
                        name = name,
                        depth = 0,
                        status = AgentNodeStatus.FAILED
                    )
                }
                else -> {}
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
