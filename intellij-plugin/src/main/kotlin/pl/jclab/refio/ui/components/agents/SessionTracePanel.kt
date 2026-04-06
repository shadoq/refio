package pl.jclab.refio.ui.components.agents

import pl.jclab.refio.core.agents.events.AgentEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Hierarchical trace view for a single session:
 *
 *   Session <id>   (N turns · X tokens · $Y · Zs)
 *   ├── Turn 1  (mode=AGENT · 1200ms)
 *   │     ├── [LLM]  qwen3:9b  12345 in / 321 out  $0.0012  820ms
 *   │     ├── [Tool] read_file  45ms  OK
 *   │     └── [Tool] grep_search  120ms  OK
 *   ├── Turn 2  …
 *   └── Models: qwen3:9b — 3 calls · 34k in / 2k out · $0.0123
 *
 * Built by consuming AgentEvent.TurnStarted / TurnEnded / LLMCallCompleted / ToolCalled.
 */
class SessionTracePanel : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("Session —")
    private val treeModel = DefaultTreeModel(root)
    private val tree = JTree(treeModel).apply {
        isRootVisible = true
        showsRootHandles = true
        font = font.deriveFont(11f)
        cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                t: JTree, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val c = super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode
                val payload = node?.userObject as? TraceNode
                if (payload != null && !sel) {
                    foreground = when (payload.kind) {
                        TraceKind.TURN -> Color(70, 120, 200)
                        TraceKind.LLM -> Color(140, 90, 200)
                        TraceKind.TOOL_OK -> Color(40, 140, 40)
                        TraceKind.TOOL_ERR -> Color(200, 50, 50)
                        TraceKind.SESSION -> Color(200, 160, 40)
                        TraceKind.MODELS -> Color(120, 120, 120)
                    }
                }
                return c
            }
        }
    }

    private val header = JLabel("  Session Trace").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
    }

    // Per-session aggregates
    private var sessionId: String? = null
    private var totalTurns = 0
    private var totalLlmCalls = 0
    private var totalToolCalls = 0
    private var totalTokensIn = 0L
    private var totalTokensOut = 0L
    private var totalCost = 0.0
    private var totalDurationMs = 0L

    // Per-model aggregates
    private data class ModelStats(
        var calls: Int = 0,
        var tokensIn: Long = 0,
        var tokensOut: Long = 0,
        var cost: Double = 0.0,
        var durationMs: Long = 0
    )
    private val modelStats = linkedMapOf<String, ModelStats>()

    // Map iteration → tree node so child events attach to correct turn
    private val turnNodes = mutableMapOf<Int, DefaultMutableTreeNode>()
    private var modelsNode: DefaultMutableTreeNode? = null

    init {
        add(header, BorderLayout.NORTH)
        add(JScrollPane(tree), BorderLayout.CENTER)
        updateRootLabel()
    }

    fun setSession(id: String?) {
        sessionId = id
        SwingUtilities.invokeLater { updateRootLabel() }
    }

    fun clear() {
        SwingUtilities.invokeLater {
            root.removeAllChildren()
            turnNodes.clear()
            modelsNode = null
            modelStats.clear()
            totalTurns = 0
            totalLlmCalls = 0
            totalToolCalls = 0
            totalTokensIn = 0
            totalTokensOut = 0
            totalCost = 0.0
            totalDurationMs = 0
            treeModel.reload()
            updateRootLabel()
        }
    }

    fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnStarted -> {
                SwingUtilities.invokeLater {
                    val label = "Turn ${event.iteration}/${event.maxIterations}  (${event.mode})  …"
                    val turnNode = DefaultMutableTreeNode(TraceNode(label, TraceKind.TURN))
                    turnNodes[event.iteration] = turnNode
                    insertBeforeModels(turnNode)
                    tree.expandPath(javax.swing.tree.TreePath(turnNode.path))
                    totalTurns++
                    updateRootLabel()
                }
            }
            is AgentEvent.TurnEnded -> {
                SwingUtilities.invokeLater {
                    val node = turnNodes[event.iteration] ?: return@invokeLater
                    val existing = node.userObject as? TraceNode
                    val mode = existing?.label?.substringAfter("(")?.substringBefore(")") ?: ""
                    node.userObject = TraceNode(
                        "Turn ${event.iteration}  ($mode)  ${formatDuration(event.durationMs)}",
                        TraceKind.TURN
                    )
                    totalDurationMs += event.durationMs
                    treeModel.nodeChanged(node)
                    updateRootLabel()
                }
            }
            is AgentEvent.LLMCallCompleted -> {
                SwingUtilities.invokeLater {
                    val parent = turnNodes[event.iteration] ?: root
                    val label = "[LLM] ${shortenModel(event.model)}  " +
                        "${event.tokensIn} in / ${event.tokensOut} out  " +
                        "${formatCost(event.costUsd)}  ${formatDuration(event.durationMs)}"
                    parent.add(DefaultMutableTreeNode(TraceNode(label, TraceKind.LLM)))
                    treeModel.nodeStructureChanged(parent)
                    tree.expandPath(javax.swing.tree.TreePath(parent.path))

                    totalLlmCalls++
                    totalTokensIn += event.tokensIn
                    totalTokensOut += event.tokensOut
                    totalCost += event.costUsd

                    val stats = modelStats.getOrPut(event.model) { ModelStats() }
                    stats.calls++
                    stats.tokensIn += event.tokensIn
                    stats.tokensOut += event.tokensOut
                    stats.cost += event.costUsd
                    stats.durationMs += event.durationMs
                    rebuildModelsNode()
                    updateRootLabel()
                }
            }
            is AgentEvent.ToolCalled -> {
                SwingUtilities.invokeLater {
                    val parent = turnNodes[event.iteration] ?: root
                    val status = if (event.success) "OK" else "ERR"
                    val label = "[Tool] ${event.toolName}  ${formatDuration(event.durationMs)}  $status  — ${event.argumentsPreview.take(60)}"
                    val kind = if (event.success) TraceKind.TOOL_OK else TraceKind.TOOL_ERR
                    parent.add(DefaultMutableTreeNode(TraceNode(label, kind)))
                    treeModel.nodeStructureChanged(parent)
                    tree.expandPath(javax.swing.tree.TreePath(parent.path))

                    totalToolCalls++
                    updateRootLabel()
                }
            }
            else -> { /* ignore non-trace events */ }
        }
    }

    /**
     * Keep the "Models" summary node last so it looks like a summary footer in the tree.
     */
    private fun insertBeforeModels(node: DefaultMutableTreeNode) {
        val m = modelsNode
        if (m == null) {
            root.add(node)
        } else {
            val index = root.getIndex(m)
            root.insert(node, index)
        }
        treeModel.nodeStructureChanged(root)
    }

    private fun rebuildModelsNode() {
        val m = modelsNode ?: run {
            val created = DefaultMutableTreeNode(TraceNode("Models", TraceKind.MODELS))
            modelsNode = created
            root.add(created)
            created
        }
        m.removeAllChildren()
        for ((name, stats) in modelStats) {
            val line = "${shortenModel(name)} — ${stats.calls} call(s) · " +
                "${stats.tokensIn} in / ${stats.tokensOut} out · " +
                "${formatCost(stats.cost)} · " +
                formatDuration(stats.durationMs)
            m.add(DefaultMutableTreeNode(TraceNode(line, TraceKind.MODELS)))
        }
        m.userObject = TraceNode("Models (${modelStats.size})", TraceKind.MODELS)
        treeModel.nodeStructureChanged(m)
    }

    private fun updateRootLabel() {
        val sid = sessionId?.take(8) ?: "—"
        val label = "Session $sid · turns=$totalTurns · llm=$totalLlmCalls · tools=$totalToolCalls · " +
            "tokens ${totalTokensIn}/${totalTokensOut} · ${formatCost(totalCost)} · ${formatDuration(totalDurationMs)}"
        root.userObject = TraceNode(label, TraceKind.SESSION)
        treeModel.nodeChanged(root)
    }

    private fun formatCost(cost: Double): String =
        if (cost <= 0.0) "$0" else "$%.4f".format(cost)

    private fun formatDuration(ms: Long): String =
        when {
            ms <= 0 -> "-"
            ms < 1000 -> "${ms}ms"
            else -> "%.2fs".format(ms / 1000.0)
        }

    private fun shortenModel(model: String): String =
        model.substringAfter('/').take(32)

    private data class TraceNode(val label: String, val kind: TraceKind) {
        override fun toString(): String = label
    }

    private enum class TraceKind { SESSION, TURN, LLM, TOOL_OK, TOOL_ERR, MODELS }
}
