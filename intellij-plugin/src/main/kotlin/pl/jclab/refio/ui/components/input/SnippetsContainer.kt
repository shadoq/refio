package pl.jclab.refio.ui.components.input

import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
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
        /** Maximum height before scrolling kicks in (unscaled, DIPs) */
        private const val MAX_HEIGHT = 400

        /** Height of a single snippet header (matches CodeSnippetCard.HEADER_HEIGHT, unscaled) */
        private const val SNIPPET_HEADER_HEIGHT = 24

        /** Maximum visible lines per snippet when expanded */
        private const val MAX_VISIBLE_LINES = 8

        /** Code preview vertical padding (matches CodeSnippetCard code panel, unscaled) */
        private const val CODE_PANEL_PADDING = 8

        /** Extra height added when a snippet is expanded (unscaled) */
        private const val EXPANDED_GAP = 2

        /** Bottom padding of the content area (unscaled) */
        private const val CONTENT_PADDING = 8

        /** Gap between snippets (unscaled) */
        private const val GAP = 4
    }

    private val snippets = mutableListOf<CodeSnippet>()
    private val cards = LinkedHashMap<String, CodeSnippetCard>()
    private val glue = Box.createVerticalGlue()
    private val contentPanel: JBPanel<*>
    private val scrollPane: JBScrollPane

    init {
        isOpaque = false
        border = LCATheme.emptyBorder()

        // Content panel with GridBagLayout for full-width cards
        contentPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
            // Trailing glue pushes cards to the top; kept for the container's lifetime
            add(glue, glueConstraints(0))
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
        refresh()
    }

    /**
     * Remove a snippet by ID.
     */
    fun removeSnippet(id: String) {
        snippets.removeIf { it.id == id }
        refresh()
    }

    /**
     * Clear all snippets.
     */
    fun clear() {
        snippets.clear()
        refresh()
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
     * Refresh the UI after changes: incrementally sync cards, then update sizing.
     */
    private fun refresh() {
        syncCards()
        updateSizing()

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

    /**
     * Reconcile the card components with the current snippet list, creating cards only
     * for newly added snippets and removing cards for snippets that are gone. Existing
     * cards are reused and only repositioned - no full clear/rebuild.
     */
    private fun syncCards() {
        val layout = contentPanel.layout as GridBagLayout
        val desiredIds = snippets.mapTo(HashSet()) { it.id }

        // Remove cards whose snippet is no longer present
        val iterator = cards.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in desiredIds) {
                contentPanel.remove(entry.value)
                iterator.remove()
            }
        }

        // Add cards for new snippets and (re)assign row positions in snippet order
        snippets.forEachIndexed { index, snippet ->
            val card = cards.getOrPut(snippet.id) {
                CodeSnippetCard(snippet) { id -> onRemoveSnippet(id) }
                    .also { contentPanel.add(it, cardConstraints(index)) }
            }
            layout.setConstraints(card, cardConstraints(index))
        }

        // Keep the trailing glue below the last card
        layout.setConstraints(glue, glueConstraints(snippets.size))
    }

    private fun cardConstraints(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.NORTH
        insets = Insets(0, 0, JBUI.scale(GAP), 0)
    }

    private fun glueConstraints(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        weighty = 1.0
        fill = GridBagConstraints.BOTH
    }

    /**
     * Recompute the container height from font metrics (code line height scales with the
     * mono font / HiDPI) plus scaled chrome constants, capped at the scaled maximum.
     */
    private fun updateSizing() {
        // Genuinely font-dependent: one preview line is a full mono-font line height.
        val lineHeight = getFontMetrics(LCATheme.monoFont.deriveFont(11f)).height
        val headerHeight = JBUI.scale(SNIPPET_HEADER_HEIGHT)
        val perSnippetChrome = headerHeight + JBUI.scale(CODE_PANEL_PADDING) + JBUI.scale(EXPANDED_GAP)

        val contentHeight = if (snippets.isEmpty()) {
            0
        } else {
            // Each snippet: header + code lines (up to MAX_VISIBLE_LINES) + gaps
            snippets.sumOf { snippet ->
                val visibleLines = minOf(snippet.lineCount, MAX_VISIBLE_LINES)
                perSnippetChrome + (visibleLines * lineHeight)
            } + (snippets.size * JBUI.scale(GAP)) + JBUI.scale(CONTENT_PADDING)
        }
        val maxHeight = JBUI.scale(MAX_HEIGHT)
        val actualHeight = minOf(contentHeight, maxHeight)

        preferredSize = Dimension(preferredSize.width, actualHeight)
        minimumSize = Dimension(0, if (snippets.isEmpty()) 0 else headerHeight)
        maximumSize = Dimension(Int.MAX_VALUE, maxHeight)

        // Update visibility
        isVisible = snippets.isNotEmpty()
    }
}
