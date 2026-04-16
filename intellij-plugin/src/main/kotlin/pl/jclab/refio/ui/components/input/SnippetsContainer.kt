package pl.jclab.refio.ui.components.input

import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JScrollPane

/**
 * Container for code snippets with internal scrolling.
 *
 * Features:
 * - Fixed maximum height (doesn't expand infinitely)
 * - Internal scroll when content exceeds max height
 * - Compact layout for multiple snippets
 */
class SnippetsContainer(
    private val onRemoveSnippet: (String) -> Unit
) : JBPanel<SnippetsContainer>(BorderLayout()) {

    private val logger = dualLogger("SnippetsContainer")

    companion object {
        /** Maximum height before scrolling kicks in */
        private const val MAX_HEIGHT = 400

        /** Height of a single snippet header (when collapsed) */
        private const val SNIPPET_HEADER_HEIGHT = 24

        /** Height per line of code in expanded snippet */
        private const val CODE_LINE_HEIGHT = 14

        /** Maximum visible lines per snippet when expanded */
        private const val MAX_VISIBLE_LINES = 8

        /** Expanded snippet base height (header + padding) */
        private const val EXPANDED_BASE_HEIGHT = SNIPPET_HEADER_HEIGHT + 8 + 2

        /** Gap between snippets */
        private const val GAP = 4
    }

    private val snippets = mutableListOf<CodeSnippet>()
    private val contentPanel: JBPanel<*>
    private val scrollPane: JBScrollPane

    init {
        isOpaque = false
        border = LCATheme.emptyBorder()

        // Content panel with GridBagLayout for full-width cards
        contentPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }

        // Scroll pane with no visible border
        scrollPane = JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        isVisible = false
    }

    /**
     * Add a code snippet.
     */
    fun addSnippet(snippet: CodeSnippet) {
        logger.info { "addSnippet called: ${snippet.displayName}, total snippets: ${snippets.size + 1}" }
        snippets.add(snippet)
        rebuildUI()
    }

    /**
     * Remove a snippet by ID.
     */
    fun removeSnippet(id: String) {
        snippets.removeIf { it.id == id }
        rebuildUI()
    }

    /**
     * Clear all snippets.
     */
    fun clear() {
        snippets.clear()
        rebuildUI()
    }

    /**
     * Get all snippets.
     */
    fun getSnippets(): List<CodeSnippet> = snippets.toList()

    /**
     * Check if container has any snippets.
     */
    fun isEmpty(): Boolean = snippets.isEmpty()

    /**
     * Rebuild the UI after changes.
     */
    private fun rebuildUI() {
        contentPanel.removeAll()

        val gbc = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
            insets = Insets(0, 0, GAP, 0)
        }

        snippets.forEachIndexed { index, snippet ->
            gbc.gridy = index
            val card = CodeSnippetCard(snippet) { id ->
                onRemoveSnippet(id)
            }
            contentPanel.add(card, gbc)
        }

        // Add vertical glue to push cards to top
        gbc.gridy = snippets.size
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        contentPanel.add(javax.swing.Box.createVerticalGlue(), gbc)

        // Calculate preferred height for expanded snippets (capped at MAX_HEIGHT)
        val contentHeight = if (snippets.isEmpty()) {
            0
        } else {
            // Each snippet: header + code lines (up to MAX_VISIBLE_LINES) + gaps
            snippets.sumOf { snippet ->
                val visibleLines = minOf(snippet.lineCount, MAX_VISIBLE_LINES)
                EXPANDED_BASE_HEIGHT + (visibleLines * CODE_LINE_HEIGHT)
            } + (snippets.size * GAP) + 8
        }
        val actualHeight = minOf(contentHeight, MAX_HEIGHT)

        // Set sizes
        preferredSize = Dimension(preferredSize.width, actualHeight)
        minimumSize = Dimension(0, if (snippets.isEmpty()) 0 else SNIPPET_HEADER_HEIGHT)
        maximumSize = Dimension(Int.MAX_VALUE, MAX_HEIGHT)

        // Update visibility
        isVisible = snippets.isNotEmpty()

        // Force layout update
        contentPanel.revalidate()
        contentPanel.repaint()
        scrollPane.revalidate()
        scrollPane.repaint()
        revalidate()
        repaint()

        // Notify parent to update layout
        javax.swing.SwingUtilities.invokeLater {
            parent?.revalidate()
            parent?.repaint()
        }
    }
}
