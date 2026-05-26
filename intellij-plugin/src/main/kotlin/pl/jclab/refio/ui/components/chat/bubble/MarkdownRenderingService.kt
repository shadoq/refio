package pl.jclab.refio.ui.components.chat.bubble

import com.intellij.openapi.project.Project
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import pl.jclab.refio.ui.components.chat.FilePathDetector
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

internal class MarkdownRenderingService(
    private val project: Project,
    private val formatMarkdownEnabledProvider: () -> Boolean,
    private val onFilePathClicked: (String) -> Unit
) {

    companion object {
        private const val BUBBLE_INNER_HORIZONTAL_PADDING = 16
        private const val MARKDOWN_RIGHT_GUTTER = 12
        private const val LARGE_MARKDOWN_THRESHOLD_CHARS = 8_000
    }

    private val markdownExtensions = listOf(TablesExtension.create())
    private val markdownParser = Parser.builder()
        .extensions(markdownExtensions)
        .build()
    private val htmlRenderer = HtmlRenderer.builder()
        .extensions(markdownExtensions)
        .escapeHtml(true)
        .softbreak("<br />")
        .build()

    private val thinkingTagRegex = Regex(
        """<(think(?:ing)?)>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val unclosedThinkingTagRegex = Regex(
        """<(think(?:ing)?)>.*$""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun stripThinkingTags(content: String): String {
        return content
            .replace(thinkingTagRegex, "")
            .replace(unclosedThinkingTagRegex, "")
            .trim()
    }

    fun normalizeMarkdownTablesForRendering(markdown: String): String {
        val lines = markdown.lines()
        if (lines.size < 2) return markdown

        val output = mutableListOf<String>()
        var i = 0

        while (i < lines.size) {
            if (i + 1 < lines.size && isMarkdownTableRow(lines[i]) && isMarkdownTableSeparator(lines[i + 1])) {
                val headers = parseMarkdownTableCells(lines[i])
                i += 2
                var rowIndex = 1

                while (i < lines.size && isMarkdownTableRow(lines[i])) {
                    val values = parseMarkdownTableCells(lines[i])
                    val cells = headers.indices.mapNotNull { index ->
                        val value = values.getOrNull(index)?.trim().orEmpty()
                        if (value.isBlank()) return@mapNotNull null
                        val header = headers.getOrNull(index)?.trim().orEmpty()
                        if (header.isBlank()) value else "**$header:** $value"
                    }

                    if (cells.isNotEmpty()) {
                        output.add("$rowIndex. ${cells.joinToString(" | ")}")
                        rowIndex++
                    }
                    i++
                }

                output.add("")
                continue
            }

            output.add(lines[i])
            i++
        }

        return output.joinToString("\n").trimEnd()
    }

    fun resolveMarkdownEditorWidth(maxBubbleWidth: Int): Int {
        return (maxBubbleWidth - BUBBLE_INNER_HORIZONTAL_PADDING - MARKDOWN_RIGHT_GUTTER).coerceAtLeast(160)
    }

    fun installResponsiveEditorSizing(container: JComponent, editorPane: JEditorPane, fallbackWidth: Int) {
        fun applyWidth() {
            val containerWidth = container.width.takeIf { it > 0 } ?: fallbackWidth
            val editorWidth = resolveMarkdownEditorWidth(containerWidth)
            editorPane.setSize(editorWidth, Short.MAX_VALUE.toInt())
            editorPane.preferredSize = Dimension(editorWidth, editorPane.preferredSize.height)
        }

        applyWidth()
        container.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                applyWidth()
                container.revalidate()
                container.repaint()
            }
        })
    }

    fun createMarkdownEditorPane(
        markdown: String,
        backgroundColor: Color,
        foregroundColor: Color,
        maxBubbleWidth: Int,
        preferPlainText: Boolean = false
    ): JEditorPane {
        val safeMarkdown = normalizeMarkdownTablesForRendering(markdown)
        val editorWidth = resolveMarkdownEditorWidth(maxBubbleWidth)
        val renderPlainText = !formatMarkdownEnabledProvider() ||
            (preferPlainText && safeMarkdown.length >= LARGE_MARKDOWN_THRESHOLD_CHARS)
        val htmlContent = if (!renderPlainText) {
            markdownToHtml(safeMarkdown)
        } else {
            safeMarkdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>")
        }

        return JTextPane().apply {
            contentType = "text/html"

            val kit = HTMLEditorKit()
            val styleSheet = StyleSheet()
            val isDarkTheme = LCATheme.isDark

            val bgColorHex = String.format(
                "#%02x%02x%02x", backgroundColor.red, backgroundColor.green, backgroundColor.blue
            )
            val fgColorHex = String.format(
                "#%02x%02x%02x", foregroundColor.red, foregroundColor.green, foregroundColor.blue
            )

            styleSheet.addRule(
                """
                body {
                    font-family: ${LCATheme.bodyFont.family};
                    font-size: ${LCATheme.bodyFont.size}pt;
                    color: $fgColorHex;
                    background-color: $bgColorHex;
                    margin: 2px;
                    padding: 2px;
                    word-wrap: break-word;
                }
                h1, h2, h3, h4, h5, h6 {
                    margin-top: 4px;
                    margin-bottom: 2px;
                    font-weight: bold;
                }
                h1 {
                    font-size: ${LCATheme.bodyFont.size + 6}pt;
                }
                h2 {
                    font-size: ${LCATheme.bodyFont.size + 4}pt;
                }
                h3 {
                    font-size: ${LCATheme.bodyFont.size + 2}pt;
                }
                h4 {
                    font-size: ${LCATheme.bodyFont.size + 1}pt;
                }
                h4 {
                    font-size: ${LCATheme.bodyFont.size}pt;
                }
                pre {
                    background-color: ${if (isDarkTheme) "#2B2B2B" else "#F5F5F5"};
                    padding: 8px;
                    margin-top: 8px;
                    margin-bottom: 8px;
                }
                code {
                    font-family: monospace;
                    background-color: ${if (isDarkTheme) "#3C3F41" else "#E8E8E8"};
                    padding: 2px 4px;
                }
                p {
                    margin-top: 2px;
                    margin-bottom: 2px;
                }
                ul, ol {
                    margin-top: 2px;
                    margin-bottom: 2px;
                    margin-left: 16px;
                    padding-left: 8px;
                }
                li {
                    margin-top: 1px;
                    margin-bottom: 1px;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    table-layout: fixed;
                }
                th, td {
                    text-align: left;
                    vertical-align: top;
                    padding: 2px 6px;
                    word-wrap: break-word;
                }
                strong {
                    font-weight: bold;
                }
                em {
                    font-style: italic;
                }
            """.trimIndent()
            )

            kit.styleSheet = styleSheet
            editorKit = kit

            text = "<html><body>$htmlContent</body></html>"
            isEditable = false
            isOpaque = true
            background = backgroundColor

            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)

            setSize(editorWidth, Short.MAX_VALUE.toInt())
            preferredSize = Dimension(editorWidth, preferredSize.height)

            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    val url = e.description
                    if (url.startsWith("file://")) {
                        val filePath = url.removePrefix("file://")
                        onFilePathClicked(filePath)
                    }
                }
            }
        }
    }

    private fun markdownToHtml(markdown: String): String {
        val document = markdownParser.parse(markdown)
        var html = htmlRenderer.render(document)

        val filePaths = FilePathDetector.findFilePaths(markdown)
        filePaths.forEach { match ->
            val link =
                "<a href=\"file://${match.path}\" style=\"color: #589df6; text-decoration: underline;\">${match.path}</a>"
            html = html.replace(match.path, link)
        }

        return html
    }

    private fun isMarkdownTableRow(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return false
        return normalized.count { it == '|' } >= 1
    }

    private fun isMarkdownTableSeparator(line: String): Boolean {
        val normalized = line.trim().trim('|')
        if (normalized.isBlank()) return false

        return normalized
            .split("|")
            .map { it.trim() }
            .all { part -> part.isNotBlank() && part.all { ch -> ch == '-' || ch == ':' } }
    }

    private fun parseMarkdownTableCells(line: String): List<String> {
        val normalized = line.trim().trim('|')
        if (normalized.isBlank()) return emptyList()
        return normalized.split("|").map { it.trim() }
    }
}
