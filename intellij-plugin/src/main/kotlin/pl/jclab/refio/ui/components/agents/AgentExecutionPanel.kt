package pl.jclab.refio.ui.components.agents

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Main panel for agent execution visualization (single session OR multi-agent).
 *
 * Layout:
 * - NORTH: Header with title
 * - CENTER: Tabs
 *      • Trace   — hierarchical Turn/LLM/Tool tree for the current session
 *      • Graph   — legacy DAG + event timeline (for multi-agent runs)
 * - SOUTH: Control bar (Clear button)
 *
 * Trace tab is always useful for single-agent sessions because AgentTurnLoop emits
 * TurnStarted / LLMCallCompleted / ToolCalled / TurnEnded events via AgentEventBus.
 */
class AgentExecutionPanel : JPanel(BorderLayout()), Disposable {

    private val tracePanel = SessionTracePanel()
    private val graphPanel = AgentGraphPanel()
    private val timelinePanel = EventTimelinePanel()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val agentNames = mutableMapOf<String, String>() // sourceAgentId → human name

    private var eventBus: AgentEventBus? = null
    private var subscriptionJob: Job? = null
    private var currentSessionId: String? = null

    init {
        // Multi-agent view: graph (top) + timeline (bottom)
        val graphAndTimeline = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(graphPanel),
            timelinePanel
        ).apply {
            resizeWeight = 0.35
            dividerLocation = 180
        }

        val copyGraphEventsButton = JButton("Copy").apply {
            font = font.deriveFont(10f)
            toolTipText = "Copy graph & events to clipboard"
            addActionListener { copyGraphAndEventsToClipboard() }
        }
        val graphEventsPanel = JPanel(BorderLayout()).apply {
            val topBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
                add(copyGraphEventsButton)
            }
            add(topBar, BorderLayout.NORTH)
            add(graphAndTimeline, BorderLayout.CENTER)
        }

        val tabs = JTabbedPane().apply {
            addTab("Trace", tracePanel)
            addTab("Graph + Events", graphEventsPanel)
        }
        add(tabs, BorderLayout.CENTER)
        // No Clear button by design — the panel state is driven entirely by the
        // currently loaded session, so switching sessions (including loading from
        // history) resets it automatically via subscribeToSession().
    }

    /**
     * Subscribe to events from AgentEventBus for a given session.
     * Safe to call multiple times — cancels any previous subscription.
     *
     * On first subscription for a given session the panel will also replay any
     * persisted events so that reloading a session from history reconstructs
     * the Trace / Timeline / Graph state it had when the session ended.
     */
    fun subscribeToSession(eventBus: AgentEventBus, sessionId: String) {
        if (currentSessionId == sessionId && subscriptionJob?.isActive == true) return

        subscriptionJob?.cancel()
        this.eventBus = eventBus
        currentSessionId = sessionId
        agentNames.clear()

        subscriptionJob = scope.launch {
            // Clear panels on EDT and wait for it to complete before replaying
            // persisted events. Without this, the clear and replay invokeLater
            // calls can interleave, causing the clear to wipe replayed data.
            val latch = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeLater {
                tracePanel.setSession(sessionId)
                timelinePanel.setSession(sessionId)
                graphPanel.clear()
                timelinePanel.clear()
                tracePanel.clear()
                latch.countDown()
            }
            latch.await()

            // Replay persisted events so the panels reflect the saved state
            // before we start consuming live events. Any live events with the same
            // ids arriving later are effectively idempotent because they'd just
            // update the same tree nodes / table rows.
            //
            // IMPORTANT: replay is best-effort. A repository failure must NOT stop
            // us from starting the live collect — that was the cause of the "nothing
            // shows up" regression after the repo was first wired in.
            try {
                val persisted = eventBus.loadPersistedEvents(sessionId)
                persisted.forEach { handleEvent(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // swallow and continue to live subscription
            }

            eventBus.sessionEvents(sessionId).collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        // Always feed the trace panel — it knows which events to render
        tracePanel.handleEvent(event)
        // Timeline shows everything
        timelinePanel.addEvent(event)

        SwingUtilities.invokeLater {
            // Auto-register a graph node for any new sourceAgentId so single-agent
            // sessions also get a visible node (not just multi-agent runs).
            if (!agentNames.containsKey(event.sourceAgentId)) {
                val name = when (event) {
                    is AgentEvent.AgentStarted -> event.agentName
                    else -> "Session ${event.sourceAgentId.take(8)}"
                }
                val eventDepth = when (event) {
                    is AgentEvent.TurnStarted -> event.depth
                    is AgentEvent.LLMCallCompleted -> event.depth
                    is AgentEvent.ToolCalled -> event.depth
                    else -> 0
                }
                agentNames[event.sourceAgentId] = name
                graphPanel.addOrUpdateAgent(
                    agentId = event.sourceAgentId,
                    name = name,
                    depth = eventDepth,
                    status = AgentNodeStatus.RUNNING
                )
            }

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

    fun toText(): String = buildString {
        appendLine("### Session Trace")
        appendLine()
        appendLine(tracePanel.toText())
        appendLine()
        appendLine("### Agents Graph")
        appendLine()
        appendLine(graphPanel.toText())
        appendLine()
        appendLine("### Events Timeline")
        appendLine()
        appendLine(timelinePanel.toText())
    }

    private fun copyGraphAndEventsToClipboard() {
        val sb = StringBuilder()
        sb.appendLine("## Agents")
        sb.appendLine()
        sb.append(graphPanel.toText())
        sb.appendLine()
        sb.appendLine("## Events")
        sb.appendLine()
        sb.append(timelinePanel.toText())
        val sel = StringSelection(sb.toString().trimEnd())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
    }

    override fun dispose() {
        subscriptionJob?.cancel()
        scope.cancel()
    }
}
