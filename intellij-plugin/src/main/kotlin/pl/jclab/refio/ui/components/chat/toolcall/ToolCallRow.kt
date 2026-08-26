package pl.jclab.refio.ui.components.chat.toolcall

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * One tool call in the transcript: a 24 px line that expands on click.
 *
 * The detail panel - an IDE diff when a pre-write snapshot exists, the raw output otherwise - is
 * built on first expansion, so a session with fifty calls holds fifty labels rather than fifty
 * editors. Expansion is reported back to the owner so a re-render of the transcript restores it.
 */
class ToolCallRow(
    private val project: Project,
    private val view: ToolCallRowView,
    private val callbacks: Callbacks
) : JPanel(BorderLayout()), Disposable {

    /** Wiring the row needs from the chat view; kept as an interface so the row stays testable. */
    interface Callbacks {
        fun launch(block: suspend () -> Unit)
        suspend fun loadSnapshotContent(snapshotId: String, filePath: String): String?
        fun isExpanded(messageId: String): Boolean
        fun setExpanded(messageId: String, expanded: Boolean)
        fun onHeightChanged()

        /** Reveals the tool's target in the IDE - editor for a file, project view for a folder. */
        fun openPath(path: String)
    }

    private val head = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.insets(0, 4)
    }

    private var detail: JComponent? = null

    /** Index of the path fragment inside [head], or -1 when the row has no target path. */
    private var pathFragmentIndex = -1

    init {
        isOpaque = false
        border = JBUI.Borders.customLineBottom(
            JBColor.namedColor("Group.separatorColor", JBColor.border())
        )
        add(head, BorderLayout.NORTH)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        head.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1) return
                // Clicking the path opens the target; clicking anywhere else on the line is the
                // expand toggle.
                val path = view.subtitle
                if (path != null && pathFragmentIndex >= 0 && head.findFragmentAt(e.x) == pathFragmentIndex) {
                    callbacks.openPath(path)
                } else {
                    toggle()
                }
            }
        })

        collapse(notifyOwner = false)
        if (callbacks.isExpanded(view.messageId)) {
            expand(notifyOwner = false)
        }
    }

    private val isExpanded get() = detail != null

    private fun renderHead() {
        head.clear()
        pathFragmentIndex = -1
        var fragments = 0

        head.append(if (isExpanded) "▾ " else "▸ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        fragments++
        head.icon = when (view.state) {
            ToolCallRowView.State.OK -> AllIcons.RunConfigurations.TestPassed
            ToolCallRowView.State.FAILED -> AllIcons.RunConfigurations.TestFailed
            ToolCallRowView.State.RUNNING -> AnimatedIcon.Default.INSTANCE
        }
        head.append(view.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        fragments++

        view.subtitle?.takeIf { it.isNotBlank() }?.let {
            pathFragmentIndex = fragments
            head.append(
                "  " + ToolCallRowView.shortenPath(it),
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
            )
            fragments++
            head.toolTipText = it
        }

        if (view.added != null || view.removed != null) {
            head.append(
                "  +${view.added ?: 0}",
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GREEN)
            )
            head.append(
                " −${view.removed ?: 0}",
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED)
            )
        }

        view.durationText?.let {
            head.append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private fun toggle() {
        if (isExpanded) collapse(notifyOwner = true) else expand(notifyOwner = true)
    }

    private fun collapse(notifyOwner: Boolean) {
        detail?.let { remove(it) }
        detail = null
        val height = JBUI.scale(ROW_HEIGHT)
        preferredSize = Dimension(0, height)
        maximumSize = Dimension(Int.MAX_VALUE, height)
        renderHead()
        if (notifyOwner) {
            callbacks.setExpanded(view.messageId, false)
            callbacks.onHeightChanged()
        }
        revalidate()
        repaint()
    }

    private fun expand(notifyOwner: Boolean) {
        val d = buildDetail()
        detail = d
        add(d, BorderLayout.CENTER)
        preferredSize = null
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        renderHead()
        if (notifyOwner) {
            callbacks.setExpanded(view.messageId, true)
        }
        callbacks.onHeightChanged()
        revalidate()
        repaint()
    }

    private fun buildDetail(): JComponent {
        if (!view.canDiff) return textPanel(view.output)

        // The "before" side lives in the snapshot store, so the diff starts as a placeholder and
        // fills in once the content has been read off the EDT.
        val holder = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(DIFF_HEIGHT))
            add(JBLabel("Loading snapshot…").apply {
                border = JBUI.Borders.empty(4, 8)
            }, BorderLayout.NORTH)
        }

        val snapshotId = view.snapshotId!!
        val filePath = view.filePath!!

        callbacks.launch {
            val before = try {
                callbacks.loadSnapshotContent(snapshotId, filePath)
            } catch (_: Exception) {
                null
            }

            ApplicationManager.getApplication().invokeLater {
                if (detail !== holder) return@invokeLater
                holder.removeAll()
                val panel = buildDiffPanel(before, filePath) ?: textPanel(view.output)
                holder.add(panel, BorderLayout.CENTER)
                holder.revalidate()
                holder.repaint()
                callbacks.onHeightChanged()
            }
        }

        return holder
    }

    private fun buildDiffPanel(before: String?, filePath: String): JComponent? {
        if (before == null) return null
        val file = resolveFile(filePath) ?: return null

        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            null,
            factory.create(before, file.fileType),
            factory.create(project, file),
            "Before",
            "After"
        )

        val panel = DiffManager.getInstance().createRequestPanel(project, this, null)
        panel.setRequest(request)
        return panel.component.apply {
            preferredSize = Dimension(0, JBUI.scale(DIFF_HEIGHT))
        }
    }

    private fun resolveFile(filePath: String) =
        LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: project.basePath?.let { base ->
                LocalFileSystem.getInstance().findFileByPath("$base/${filePath.replace('\\', '/')}")
            }

    private fun textPanel(text: String): JComponent =
        JBScrollPane(
            JBTextArea(text.ifBlank { "(no output)" }).apply {
                isEditable = false
                font = JBUI.Fonts.create(Font.MONOSPACED, 11)
                lineWrap = false
            }
        ).apply {
            preferredSize = Dimension(0, JBUI.scale(TEXT_HEIGHT))
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }

    override fun dispose() {
        detail = null
    }

    private companion object {
        const val ROW_HEIGHT = 24
        const val DIFF_HEIGHT = 160
        const val TEXT_HEIGHT = 140
    }
}
