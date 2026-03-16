package pl.jclab.refio.ui.components.chat.bubble

import com.intellij.openapi.project.Project
import pl.jclab.refio.ui.components.chat.CodeBlock
import pl.jclab.refio.ui.components.chat.CodeBlockPanel
import pl.jclab.refio.ui.components.chat.ContentSegment
import pl.jclab.refio.ui.components.chat.ContentSegmentParser
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import com.intellij.ui.components.JBPanel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border

internal abstract class BaseBubbleRenderer {

    internal interface BubbleContentContext {
        val project: Project
        val availableWidth: Int
        val scrollBarAndPadding: Int
        val bubbleCompactGap: Int
        val bubbleLargeGap: Int
        val defaultSpace: Border
        val componentFactory: BubbleComponentFactory
        val markdownService: MarkdownRenderingService

        fun createMessageBlock(backgroundColor: Color): JPanel
    }

    protected fun createOuterPanel(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
        }
    }

    protected fun addToOuter(
        outerPanel: JPanel,
        component: JComponent,
        anchor: Int = GridBagConstraints.WEST,
        fill: Int = GridBagConstraints.HORIZONTAL
    ): JPanel {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            this.anchor = anchor
            this.fill = fill
        }
        outerPanel.add(component, constraints)
        return outerPanel
    }

    internal fun createCollapsibleCodePanel(
        content: String,
        context: BubbleContentContext,
        language: String = "text",
        filePath: String? = null,
        collapseThreshold: Int = 24,
        previewLines: Int = 12
    ): JPanel {
        val lines = content.lines()
        val totalLines = lines.size
        val shouldCollapse = totalLines > collapseThreshold

        val (contentToDisplay, isPreview) = if (shouldCollapse && totalLines > previewLines * 2) {
            val firstLines = lines.take(previewLines)
            val lastLines = lines.takeLast(previewLines)
            val omittedCount = totalLines - previewLines * 2
            Pair((firstLines + listOf("// ... $omittedCount more lines ...") + lastLines).joinToString("\n"), true)
        } else {
            Pair(content, false)
        }

        val codeBlock = CodeBlock(
            language = language,
            filePath = filePath,
            content = contentToDisplay,
            startIndex = 0,
            endIndex = contentToDisplay.length,
            isPreview = isPreview,
            totalLineCount = if (isPreview) totalLines else 0,
            fullContent = if (isPreview) content else null
        )

        return CodeBlockPanel(codeBlock, context.project).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = LCATheme.paddedBorder(2, 0)
        }
    }

    internal fun createMixedContentPanel(
        content: String,
        codeBlocks: List<CodeBlock>,
        backgroundColor: Color,
        foregroundColor: Color,
        maxBubbleWidth: Int,
        context: BubbleContentContext
    ): JPanel {
        val panel = context.createMessageBlock(backgroundColor).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        var lastIndex = 0

        codeBlocks.forEach { codeBlock ->
            if (codeBlock.startIndex > lastIndex) {
                val textBefore = content.substring(lastIndex, codeBlock.startIndex)
                if (textBefore.isNotBlank()) {
                    panel.add(
                        context.componentFactory.createMixedTextSegmentPanel(
                            content = textBefore,
                            backgroundColor = backgroundColor,
                            foregroundColor = foregroundColor,
                            maxBubbleWidth = maxBubbleWidth
                        )
                    )
                }
            }

            val codePanel = CodeBlockPanel(codeBlock, context.project).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                minimumSize.width = 0
                preferredSize.width = maxBubbleWidth
                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                border = context.defaultSpace
                background = LCATheme.editorBackground
                foreground = LCATheme.editorForeground
            }

            panel.add(codePanel)
            panel.add(Box.createVerticalStrut(context.bubbleCompactGap))
            lastIndex = codeBlock.endIndex + 1
        }

        if (lastIndex < content.length) {
            val textAfter = content.substring(lastIndex)
            if (textAfter.isNotBlank()) {
                panel.add(
                    context.componentFactory.createMixedTextSegmentPanel(
                        content = textAfter,
                        backgroundColor = backgroundColor,
                        foregroundColor = foregroundColor,
                        maxBubbleWidth = maxBubbleWidth
                    )
                )
            }
        }

        return panel
    }

    internal fun createUniversalBubble(
        icon: String,
        title: String,
        subtitle: String? = null,
        content: String,
        messageId: String? = null,
        isStreaming: Boolean = false,
        preferPlainTextMarkdown: Boolean = false,
        backgroundColor: Color,
        foregroundColor: Color,
        context: BubbleContentContext,
        extras: (JPanel.() -> Unit)? = null
    ): JPanel {
        val outerPanel = createOuterPanel()
        val messageBlock = context.createMessageBlock(backgroundColor).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        messageBlock.add(context.componentFactory.createBubbleHeader(icon, title, subtitle, foregroundColor))

        val maxWidth = (context.availableWidth - context.scrollBarAndPadding).coerceAtLeast(200)
        val segments = ContentSegmentParser.parse(content, isStreaming = isStreaming)

        if (segments.isEmpty() && content.isNotBlank()) {
            val normalized = context.markdownService.normalizeMarkdownTablesForRendering(content)
            messageBlock.add(
                context.markdownService.createMarkdownEditorPane(
                    normalized,
                    backgroundColor,
                    foregroundColor,
                    maxWidth,
                    preferPlainText = preferPlainTextMarkdown
                )
            )
        } else {
            for (segment in segments) {
                when (segment) {
                    is ContentSegment.Thinking -> {
                        messageBlock.add(context.componentFactory.createThinkingPanel(segment.content, messageId))
                        messageBlock.add(Box.createVerticalStrut(context.bubbleLargeGap))
                    }
                    is ContentSegment.Code -> {
                        messageBlock.add(CodeBlockPanel(segment.codeBlock, context.project).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            border = LCATheme.paddedBorder(2, 0)
                        })
                    }
                    is ContentSegment.Json -> {
                        messageBlock.add(
                            createCollapsibleCodePanel(
                                content = segment.content,
                                context = context,
                                language = "json"
                            )
                        )
                    }
                    is ContentSegment.Plan -> {
                        context.componentFactory.renderPlanSegment(segment, messageBlock, backgroundColor, foregroundColor, maxWidth)
                    }
                    is ContentSegment.Markdown -> {
                        if (segment.content.isNotBlank()) {
                            val normalized = context.markdownService.normalizeMarkdownTablesForRendering(segment.content)
                            messageBlock.add(
                                context.markdownService.createMarkdownEditorPane(
                                    markdown = normalized,
                                    backgroundColor = backgroundColor,
                                    foregroundColor = foregroundColor,
                                    maxBubbleWidth = maxWidth,
                                    preferPlainText = preferPlainTextMarkdown
                                )
                            )
                        }
                    }
                }
            }
        }

        extras?.invoke(messageBlock)
        return addToOuter(outerPanel, messageBlock)
    }
}
