package pl.jclab.refio.ui.components.context

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextPane

private val logger = dualLogger("CollapsibleContextSection")

class CollapsibleContextSection(
    private val title: String,
    private val color: Color,
    private val collapsible: Boolean = true
) : JBPanel<CollapsibleContextSection>(BorderLayout()) {

    private var isExpanded = false
    private var rawContent: String = ""
    private var pendingHtml: String? = null
    private var tokenInfo: ContextSectionTokenInfo? = null

    private val toggleLabel = JBLabel(AllIcons.General.ArrowRight)
    private val titleLabel = JBLabel(title).apply {
        font = font.deriveFont(12f)
    }
    private val tokenLabel = JBLabel("").apply {
        foreground = LCATheme.labelDisabledForeground
    }
    private val colorIndicator = JPanel().apply {
        background = color
        preferredSize = Dimension(10, 10)
        minimumSize = Dimension(10, 10)
        maximumSize = Dimension(10, 10)
        border = BorderFactory.createLineBorder(LCATheme.borderColor, 1)
    }
    private val copyButton = JButton("Copy").apply {
        toolTipText = "Copy full section content to clipboard"
        isEnabled = false
        addActionListener { copySectionToClipboard() }
    }

    private val contentLabel = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = true
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        border = BorderFactory.createEmptyBorder(6, 10, 8, 10)
    }

    private val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(contentLabel, BorderLayout.CENTER)
        isVisible = false
    }

    private val headerPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        if (collapsible) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        add(toggleLabel)
        add(Box.createHorizontalStrut(6))
        add(colorIndicator)
        add(Box.createHorizontalStrut(6))
        add(titleLabel)
        add(Box.createHorizontalStrut(8))
        add(tokenLabel)
        add(Box.createHorizontalGlue())
        add(copyButton)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (collapsible) {
                    toggle()
                }
            }
        })
    }

    init {
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, LCATheme.borderColor),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        if (!collapsible) {
            toggleLabel.isVisible = false
            expand()
        }
    }

    fun setContent(raw: String, html: String) {
        rawContent = raw.trim()
        // Defer the expensive HTML parse: JEditorPane.setText runs HTMLEditorKit synchronously
        // on the EDT, which can freeze the UI for many seconds on multi-MB sections. Collapsed
        // sections - the common case for large ones like recent work - never pay that cost.
        pendingHtml = capHtmlForRendering(html)
        copyButton.isEnabled = rawContent.isNotBlank()
        refreshTokenLabel()
        if (isExpanded) {
            applyPendingHtml()
        }
    }

    /**
     * Apply the deferred HTML to the text pane. No-op when nothing is pending (e.g. the
     * section is still collapsed). Runs the synchronous HTMLEditorKit parse on the EDT, so
     * callers must keep [pendingHtml] within [MAX_RENDERED_HTML_CHARS] via [capHtmlForRendering].
     */
    private fun applyPendingHtml() {
        val html = pendingHtml ?: return
        pendingHtml = null
        try {
            // Reset document before setting new HTML to avoid EmptyStackException
            // in HTMLDocument's internal ElementBuffer (JDK bug with nested elements)
            contentLabel.document = contentLabel.editorKit.createDefaultDocument()
            contentLabel.text = html
        } catch (e: Exception) {
            logger.warn { "Failed to set HTML content for section '$title': ${e.message}" }
            contentLabel.document = contentLabel.editorKit.createDefaultDocument()
            contentLabel.text = capHtmlForRendering("<html><body>$rawContent</body></html>")
        }
    }

    /**
     * Bound the HTML handed to the text pane. Oversized content (e.g. a multi-MB tool result
     * captured into recent work) is replaced with a truncated plain-text view so the EDT parse
     * stays fast; the full content is still reachable via the Copy button (uncapped rawContent).
     */
    private fun capHtmlForRendering(html: String): String {
        if (html.length <= MAX_RENDERED_HTML_CHARS) return html
        val shown = rawContent.take(MAX_RENDERED_HTML_CHARS)
        val omitted = (rawContent.length - shown.length).coerceAtLeast(0)
        val escaped = shown
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        return buildString {
            append("<html><body style='padding:5px; font-family:monospace;'>")
            append("<div style='white-space:pre-wrap; word-wrap:break-word;'>")
            append(escaped)
            append("</div>")
            append("<p style='color:#")
            append(ColorUtil.toHex(LCATheme.labelDisabledForeground))
            append("'><i>[truncated for display - ")
            append(omitted)
            append(" more characters not shown. Use Copy for the full content.]</i></p>")
            append("</body></html>")
        }
    }

    fun clearContent() {
        rawContent = ""
        pendingHtml = null
        contentLabel.text = ""
        copyButton.isEnabled = false
        refreshTokenLabel()
        if (collapsible) {
            collapse()
        } else {
            expand()
        }
    }

    /**
     * Whether this section has meaningful data to display (tokens or content).
     * Sections with no tokens and no content should be hidden from the panel.
     */
    fun hasData(): Boolean {
        return (tokenInfo != null && tokenInfo!!.tokens > 0) || rawContent.isNotBlank()
    }

    /**
     * Whether the updater has set content (not just initial "Loading...").
     */
    fun hasContent(): Boolean {
        return rawContent.isNotBlank() && rawContent != "Loading..."
    }

    fun updateTokenInfo(info: ContextSectionTokenInfo?) {
        tokenInfo = info
        refreshTokenLabel()
    }

    private fun refreshTokenLabel() {
        val info = tokenInfo
        val tokens = when {
            info != null && info.tokens > 0 -> info.tokens
            rawContent.isNotBlank() -> (rawContent.length / 4).coerceAtLeast(1)
            else -> 0
        }
        if (tokens <= 0) {
            tokenLabel.text = ""
            tokenLabel.isVisible = false
        } else {
            val pctText = if (info != null && info.percentage > 0) " (${String.format("%.1f", info.percentage)}%)" else ""
            tokenLabel.text = "${formatTokens(tokens)}$pctText"
            tokenLabel.isVisible = true
            tokenLabel.foreground = LCATheme.labelDisabledForeground
        }
    }

    fun expand() {
        isExpanded = true
        toggleLabel.icon = AllIcons.General.ArrowDown
        contentPanel.isVisible = true
        // Parse any HTML that was deferred while collapsed, now that it will be visible.
        applyPendingHtml()
        revalidate()
        repaint()
    }

    fun collapse() {
        isExpanded = false
        toggleLabel.icon = AllIcons.General.ArrowRight
        contentPanel.isVisible = false
        revalidate()
        repaint()
    }

    private fun toggle() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }

    private fun copySectionToClipboard() {
        try {
            val tokenText = tokenInfo?.let { "${formatTokens(it.tokens)} tokens" } ?: "0 tokens"
            val header = "=== $title ($tokenText) ==="
            val content = if (rawContent.isBlank()) "(empty)" else rawContent
            val fullText = "$header\n\n$content"

            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(fullText), null)
        } catch (e: Exception) {
            logger.error(e) { "Failed to copy context section: $title" }
            Messages.showErrorDialog(
                "Failed to copy section: ${e.message}",
                "Copy Section"
            )
        }
    }

    private fun formatTokens(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }

    override fun getMaximumSize(): Dimension {
        val preferred = preferredSize
        return Dimension(Int.MAX_VALUE, preferred.height)
    }

    companion object {
        /**
         * Above this length, JEditorPane's synchronous HTMLEditorKit parse on the EDT can freeze
         * the UI for many seconds. Oversized sections fall back to a truncated plain-text view.
         */
        private const val MAX_RENDERED_HTML_CHARS = 200_000
    }
}
