package pl.jclab.refio.ui.components.input

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane

/**
 * Compact expandable card for displaying code snippets.
 * Style inspired by Continue IDE plugin.
 *
 * Collapsed: [▶ filename:lines (N lines)        👁 ✕]
 * Expanded:  [▼ filename:lines (N lines)        👁 ✕]
 *            [  code preview...                    ]
 */
class CodeSnippetCard(
    private val snippet: CodeSnippet,
    private val onRemove: (String) -> Unit
) : JBPanel<CodeSnippetCard>(BorderLayout()) {

    private var isExpanded = true  // Start expanded to show code by default
    private val headerPanel: JBPanel<*>
    private val codePanel: JBScrollPane
    private val expandIcon: JLabel
    private val codeTextArea: JBTextArea

    companion object {
        private const val MAX_VISIBLE_LINES = 8
        private const val LINE_HEIGHT = 14
        private const val HEADER_HEIGHT = 24
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(LCATheme.borderColor),
            LCATheme.emptyBorder()
        )
        background = LCATheme.editorBackground

        // Header panel - compact, single line
        headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            background = LCATheme.headerBackground
            border = LCATheme.paddedBorder(2, 6, 2, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(0, HEADER_HEIGHT)
            minimumSize = Dimension(0, HEADER_HEIGHT)
            maximumSize = Dimension(Int.MAX_VALUE, HEADER_HEIGHT)

            // Left side: expand icon + filename:lines
            val leftPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false

                expandIcon = JLabel("▶").apply {
                    font = font.deriveFont(9f)
                    foreground = LCATheme.descriptionForeground
                }
                add(expandIcon)

                // File icon
                add(JLabel(AllIcons.FileTypes.Any_type).apply {
                    preferredSize = Dimension(12, 12)
                })

                // Filename:lines
                add(JLabel(snippet.displayName).apply {
                    font = LCATheme.smallFont
                    foreground = LCATheme.labelForeground
                })

                // Line count in parentheses
                add(JLabel("(${snippet.lineCount} lines)").apply {
                    font = LCATheme.smallFont
                    foreground = LCATheme.descriptionForeground
                })
            }
            add(leftPanel, BorderLayout.WEST)

            // Right side: eye toggle + remove button
            val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false

                // Eye button (toggle visibility)
                val eyeButton = JButton(AllIcons.General.InspectionsEye).apply {
                    preferredSize = Dimension(18, 18)
                    border = LCATheme.emptyBorder()
                    isContentAreaFilled = false
                    isFocusPainted = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Toggle code preview"

                    addActionListener {
                        toggleExpanded()
                    }
                }
                add(eyeButton)

                // Remove button (X)
                val removeButton = JButton("✕").apply {
                    preferredSize = Dimension(18, 18)
                    font = font.deriveFont(10f)
                    border = LCATheme.emptyBorder()
                    isContentAreaFilled = false
                    isFocusPainted = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Remove snippet"

                    addActionListener {
                        onRemove(snippet.id)
                    }
                }
                add(removeButton)
            }
            add(rightPanel, BorderLayout.EAST)

            // Click header to toggle (except buttons)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    toggleExpanded()
                }
            })
        }
        add(headerPanel, BorderLayout.NORTH)

        // Code panel - compact, hidden by default
        codeTextArea = JBTextArea(snippet.content).apply {
            isEditable = false
            font = LCATheme.monoFont.deriveFont(11f)
            background = LCATheme.codeBlockBackground
            foreground = LCATheme.codeBlockForeground
            border = LCATheme.paddedBorder(4, 8, 4, 8)
            lineWrap = false
        }

        codePanel = JBScrollPane(codeTextArea).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED

            // Limit height to MAX_VISIBLE_LINES
            val visibleLines = minOf(snippet.lineCount, MAX_VISIBLE_LINES)
            preferredSize = Dimension(0, visibleLines * LINE_HEIGHT + 8)
            isVisible = false
        }
        add(codePanel, BorderLayout.CENTER)

        updateExpandState()
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        updateExpandState()
    }

    private fun updateExpandState() {
        codePanel.isVisible = isExpanded
        expandIcon.text = if (isExpanded) "▼" else "▶"

        // Recalculate preferred size
        val codeHeight = if (isExpanded) {
            val visibleLines = minOf(snippet.lineCount, MAX_VISIBLE_LINES)
            visibleLines * LINE_HEIGHT + 8
        } else {
            0
        }
        preferredSize = Dimension(0, HEADER_HEIGHT + codeHeight + (if (isExpanded) 2 else 0))

        revalidate()
        repaint()

        // Notify parent to revalidate
        parent?.revalidate()
        parent?.repaint()
    }

    fun getSnippet(): CodeSnippet = snippet
}
