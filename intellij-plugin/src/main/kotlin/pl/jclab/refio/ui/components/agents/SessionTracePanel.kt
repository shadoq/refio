package pl.jclab.refio.ui.components.agents

import pl.jclab.refio.core.agents.events.AgentEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Hierarchical trace view for a single session with subagent nesting support:
 *
 *   Session <id>   (N turns · X tokens · $Y · Zs)
 *   ├── Turn 1  (mode=AGENT · 1200ms)
 *   │     ├── [LLM]  qwen3:9b  12345 in / 321 out  $0.0012  820ms
 *   │     ├── [Tool] read_file  45ms  OK
 *   │     └── [Tool] grep_search  120ms  OK
 *   ├── Subagent: code-reviewer  (run abc12345 · depth=1)
 *   │     ├── Turn 1  (mode=AGENT · 500ms)
 *   │     │     ├── [LLM]  qwen3:9b  …
 *   │     │     └── [Tool] file_search  …
 *   │     └── Turn 2  …
 *   └── Models: qwen3:9b — 3 calls · 34k in / 2k out · $0.0123
 *
 * Built by consuming AgentEvent.TurnStarted / TurnEnded / LLMCallCompleted / ToolCalled.
 * Events carrying runId/parentRunId/depth are grouped into sub-trees per runId.
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
                        TraceKind.SUBAGENT -> Color(0, 140, 180)
                    }
                }
                return c
            }
        }
    }

    private val copyButton = JButton("Copy").apply {
        font = font.deriveFont(10f)
        toolTipText = "Copy trace to clipboard"
        addActionListener { copyTraceToClipboard() }
    }

    private val header = JPanel(BorderLayout()).apply {
        val label = JLabel("  Session Trace").apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(copyButton)
        }
        add(label, BorderLayout.WEST)
        add(buttons, BorderLayout.EAST)
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

    // Map "runId:iteration" → tree node so child events attach to correct turn
    private val turnNodes = mutableMapOf<String, DefaultMutableTreeNode>()
    // Map runId → subagent container node (for depth > 0 runs)
    private val runNodes = mutableMapOf<String, DefaultMutableTreeNode>()
    // Track which runId is the top-level one (depth == 0)
    private var primaryRunId: String? = null
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
            runNodes.clear()
            primaryRunId = null
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
                    val runId = event.runId ?: event.correlationId
                    val depth = event.depth
                    val turnKey = "$runId:${event.iteration}"

                    // Determine the parent container for this turn
                    val parentContainer = if (depth > 0) {
                        getOrCreateRunNode(runId, depth, event.correlationId)
                    } else {
                        if (primaryRunId == null) primaryRunId = runId
                        root
                    }

                    val label = "Turn ${event.iteration}/${event.maxIterations}  (${event.mode})  …"
                    val turnNode = DefaultMutableTreeNode(TraceNode(label, TraceKind.TURN))
                    turnNodes[turnKey] = turnNode

                    if (parentContainer === root) {
                        insertBeforeModels(turnNode)
                    } else {
                        parentContainer.add(turnNode)
                        treeModel.nodeStructureChanged(parentContainer)
                    }
                    tree.expandPath(javax.swing.tree.TreePath(turnNode.path))
                    totalTurns++
                    updateRootLabel()
                }
            }
            is AgentEvent.TurnEnded -> {
                SwingUtilities.invokeLater {
                    val runId = event.runId ?: event.correlationId
                    val turnKey = "$runId:${event.iteration}"
                    val node = turnNodes[turnKey] ?: return@invokeLater
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
                    val runId = event.runId ?: event.correlationId
                    val turnKey = "$runId:${event.iteration}"
                    val parent = turnNodes[turnKey] ?: root
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
                    val runId = event.runId ?: event.correlationId
                    val turnKey = "$runId:${event.iteration}"
                    val parent = turnNodes[turnKey] ?: root
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
            is AgentEvent.StreamAborted -> {
                SwingUtilities.invokeLater {
                    val runId = event.runId ?: event.correlationId
                    val turnKey = "$runId:${event.iteration}"
                    val parent = turnNodes[turnKey] ?: root
                    val label = "[Stream aborted] ${event.code}  — ${event.reason.take(80)}  " +
                        "(partial=${event.partialLength} chars)"
                    parent.add(DefaultMutableTreeNode(TraceNode(label, TraceKind.TOOL_ERR)))
                    treeModel.nodeStructureChanged(parent)
                    tree.expandPath(javax.swing.tree.TreePath(parent.path))
                    updateRootLabel()
                }
            }
            else -> { /* ignore non-trace events */ }
        }
    }

    /**
     * Get or create a container node for a subagent run (depth > 0).
     * The node label includes the correlationId (which equals runId) so the user
     * can identify different subagent invocations.
     */
    private fun getOrCreateRunNode(runId: String, depth: Int, correlationId: String): DefaultMutableTreeNode {
        return runNodes.getOrPut(runId) {
            val label = "Subagent run ${runId.take(8)}  (depth=$depth)"
            val node = DefaultMutableTreeNode(TraceNode(label, TraceKind.SUBAGENT))
            insertBeforeModels(node)
            tree.expandPath(javax.swing.tree.TreePath(node.path))
            node
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

    fun toText(): String {
        val sb = StringBuilder()
        fun walk(node: DefaultMutableTreeNode, indent: String) {
            val isRoot = node === root
            val prefix = if (isRoot) "" else indent
            sb.appendLine("$prefix${node.userObject}")
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChildAt(i) as DefaultMutableTreeNode
                val isLast = i == childCount - 1
                val branch = if (isLast) "└── " else "├── "
                val nextIndent = indent + if (isLast) "    " else "│   "
                if (isRoot) {
                    sb.appendLine("$branch${child.userObject}")
                    walkChildren(child, if (isLast) "    " else "│   ", sb)
                } else {
                    sb.appendLine("$indent$branch${child.userObject}")
                    walkChildren(child, nextIndent, sb)
                }
            }
        }
        walk(root, "")
        return sb.toString().trimEnd()
    }

    private fun copyTraceToClipboard() {
        val sel = StringSelection(toText())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
    }

    private fun walkChildren(node: DefaultMutableTreeNode, indent: String, sb: StringBuilder) {
        val gc = node.childCount
        for (j in 0 until gc) {
            val child = node.getChildAt(j) as DefaultMutableTreeNode
            val isLast = j == gc - 1
            val branch = if (isLast) "└── " else "├── "
            sb.appendLine("$indent$branch${child.userObject}")
            walkChildren(child, indent + if (isLast) "    " else "│   ", sb)
        }
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

    private enum class TraceKind { SESSION, TURN, LLM, TOOL_OK, TOOL_ERR, MODELS, SUBAGENT }
}
