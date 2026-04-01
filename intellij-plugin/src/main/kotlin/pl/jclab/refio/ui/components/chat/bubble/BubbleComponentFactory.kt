package pl.jclab.refio.ui.components.chat.bubble

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.api.models.UserContextMetadata
import pl.jclab.refio.ui.components.chat.CodeBlock
import pl.jclab.refio.ui.components.chat.CodeBlockPanel
import pl.jclab.refio.ui.components.chat.CodeChangesData
import pl.jclab.refio.ui.components.chat.ContentSegment
import pl.jclab.refio.ui.components.chat.ExecutionSummaryFile
import pl.jclab.refio.ui.components.chat.ExecutionSummaryStats
import pl.jclab.refio.ui.components.chat.extractCodeBlocks
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal interface BubbleComponentDependencies {
    val project: Project
    val availableWidthProvider: () -> Int
    val scrollBarAndPadding: Int
    val markdownService: MarkdownRenderingService
    fun openFileReference(path: String?)
    fun openCodeChangesDiff(changes: CodeChangesData)
    fun openContextReference(ref: ContextReference)
    fun copyToClipboard(text: String)
    fun showNotification(title: String, content: String, type: NotificationType)
    fun launch(block: suspend () -> Unit)
    fun findPreviousUserMessage(fromMessageId: String): Message?
    suspend fun deleteMessage(messageId: String)
    suspend fun rewindAndResend(messageId: String, content: String)
    fun isThinkingExpanded(messageId: String): Boolean
    fun setThinkingExpanded(messageId: String, expanded: Boolean)
}

internal class BubbleComponentFactory(
    private val deps: BubbleComponentDependencies,
    private val collapsibleCodePanelProvider: (content: String, language: String, filePath: String?) -> JPanel
) {

    companion object {
        private const val MAX_SUMMARY_FILES = 10
        private const val BUBBLE_COMPACT_GAP = 4
        private const val BUBBLE_WIDTH_SAFETY_MARGIN = 12
        private const val DEFAULT_PARAM_PREVIEW_LIMIT = 100
        private const val LARGE_PARAM_PREVIEW_LIMIT = 60
        private const val LARGE_BUBBLE_THRESHOLD_CHARS = 4096
        private const val COLLAPSED_BUBBLE_PREVIEW_CHARS = 2048
        private val PATH_PARAM_KEYS = setOf("path", "file", "file_path", "filepath", "target_path", "target_file")
        private val LARGE_PARAM_KEYS = setOf(
            "content",
            "context",
            "old_string",
            "new_string",
            "edit_description",
            "patch",
            "diff"
        )
        private val NESTED_PARAM_KEYS = listOf("arguments", "args", "params", "tool_args")
    }

    fun createBubbleHeader(
        icon: String,
        title: String,
        subtitle: String? = null,
        foregroundColor: Color = LCATheme.assistantBubbleForeground
    ): JPanel {
        val headerPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        headerPanel.add(JLabel(icon).apply {
            font = LCATheme.bodyFont
        })

        headerPanel.add(JLabel(title).apply {
            font = LCATheme.boldFont
            foreground = foregroundColor
        })

        if (subtitle != null) {
            headerPanel.add(JLabel(subtitle).apply {
                font = LCATheme.bodyFont
                foreground = LCATheme.mutedForeground
            })
        }

        return headerPanel
    }

    @Suppress("UNUSED_PARAMETER")
    fun createBubbleContentPanel(
        content: String, backgroundColor: Color, foregroundColor: Color, isUser: Boolean
    ): JPanel {
        val normalizedContent = deps.markdownService.normalizeMarkdownTablesForRendering(content)
        val maxBubbleWidth = (deps.availableWidthProvider() - deps.scrollBarAndPadding - BUBBLE_WIDTH_SAFETY_MARGIN).coerceAtLeast(200)
        val codeBlocks = extractCodeBlocks(normalizedContent)

        if (codeBlocks.isEmpty()) {
            return createMarkdownPanel(normalizedContent, backgroundColor, foregroundColor, maxBubbleWidth)
        } else {
            val panel = FlatMessageBlock(backgroundColor).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            var lastIndex = 0
            codeBlocks.forEach { codeBlock ->
                if (codeBlock.startIndex > lastIndex) {
                    val textBefore = normalizedContent.substring(lastIndex, codeBlock.startIndex)
                    if (textBefore.isNotBlank()) {
                        panel.add(createMixedTextSegmentPanel(textBefore, backgroundColor, foregroundColor, maxBubbleWidth))
                    }
                }

                val codePanel = CodeBlockPanel(codeBlock, deps.project).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    minimumSize.width = 0
                    preferredSize.width = maxBubbleWidth
                    maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                    border = LCATheme.paddedBorder(4)
                    background = LCATheme.editorBackground
                    foreground = LCATheme.editorForeground
                }

                panel.add(codePanel)
                panel.add(Box.createVerticalStrut(BUBBLE_COMPACT_GAP))
                lastIndex = codeBlock.endIndex + 1
            }

            if (lastIndex < normalizedContent.length) {
                val textAfter = normalizedContent.substring(lastIndex)
                if (textAfter.isNotBlank()) {
                    panel.add(createMixedTextSegmentPanel(textAfter, backgroundColor, foregroundColor, maxBubbleWidth))
                }
            }

            return panel
        }
    }

    fun createMessageActionsPanel(
        message: Message,
        onEdit: (() -> Unit)? = null
    ): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false

            add(createSmallIconButton(AllIcons.Actions.Copy, "Copy message") {
                deps.copyToClipboard(message.content)
            })

//----------------------------------------
// ToDo: Test and fix working
//----------------------------------------

//            if (message.role == "user") {
//                add(createSmallIconButton(AllIcons.Actions.Edit, "Edit and rewind conversation") {
//                    onEdit?.invoke()
//                })
//            }

//            if (message.role == "assistant") {
//                add(createSmallIconButton(AllIcons.Actions.Refresh, "Regenerate (re-send previous user prompt)") {
//                    val prevUser = deps.findPreviousUserMessage(message.id)
//                        ?: throw IllegalStateException("No previous user message found to regenerate from")
//
//                    deps.launch {
//                        try {
//                            deps.rewindAndResend(prevUser.id, prevUser.content)
//                        } catch (e: Exception) {
//                            deps.showNotification(
//                                "Error",
//                                e.message ?: "Failed to regenerate response",
//                                NotificationType.ERROR
//                            )
//                        }
//                    }
//                })
//            }
//
//            add(createSmallIconButton(AllIcons.Actions.GC, "Delete message") {
//                deps.launch {
//                    try {
//                        deps.deleteMessage(message.id)
//                    } catch (e: Exception) {
//                        deps.showNotification("Error", e.message ?: "Failed to delete message", NotificationType.ERROR)
//                    }
//                }
//            })
        }
    }

    fun wrapRightAligned(component: JComponent): JComponent {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(component, BorderLayout.EAST)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height)
        }
    }

    fun createToolHeaderPanel(info: ToolCallDisplayInfo): JPanel {
        val icon = when (info.displayType) {
            ToolDisplayType.LLM_EDIT -> "\uD83E\uDD16"
            ToolDisplayType.CODE_EDIT -> "✏\uFE0F"
            ToolDisplayType.SIMPLE -> "\uD83D\uDD27"
            ToolDisplayType.TERMINAL -> "\uD83D\uDCBB"
        }

        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = LCATheme.toolHeaderBackground
            border = LCATheme.paddedBorder(2, 6, 2, 6)
            isOpaque = true

            add(JLabel(icon))
            add(JLabel(info.toolName).apply {
                font = LCATheme.boldFont
                foreground = LCATheme.toolNameForeground
            })
        }
    }

    fun createToolParamsPanel(params: Map<*, *>): JPanel {
        val normalized = params.entries.mapNotNull { (key, value) ->
            val keyString = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            keyString to (value?.toString() ?: "")
        }.toMap(LinkedHashMap())
        return createToolParamsPanelInternal(
            params = getDisplayableToolParams(normalized),
            backgroundColor = LCATheme.assistantBubbleBackground,
            labelFont = LCATheme.smallFont,
            valueFontSize = LCATheme.smallFont.size,
            valueColor = LCATheme.codeBlockHighlight1
        )
    }

    fun createToolParamsPanel(info: ToolCallDisplayInfo): JPanel {
        return createToolParamsPanelInternal(
            params = getDisplayableToolParams(info.parameters),
            backgroundColor = LCATheme.toolBubbleBackground,
            labelFont = LCATheme.bodyFont,
            valueFontSize = LCATheme.bodyFont.size,
            valueColor = LCATheme.assistantBubbleForeground
        )
    }

    fun getPrimaryPath(info: ToolCallDisplayInfo): String? {
        return getDisplayableToolParams(info.parameters).entries
            .firstOrNull { isPathKey(it.key) }
            ?.value
    }

    fun hasDisplayableToolParams(info: ToolCallDisplayInfo): Boolean {
        return getDisplayableToolParams(info.parameters).isNotEmpty()
    }

    fun getSecondaryToolParams(info: ToolCallDisplayInfo): LinkedHashMap<String, String> {
        return getDisplayableToolParams(info.parameters).entries
            .filterNot { isPathKey(it.key) }
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    fun createFileReferencePanel(path: String): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(createClickableFileNameLabel(path))
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    fun createToolStatusPanel(status: ToolCallStatus): JPanel {
        val (icon, text, color) = when (status) {
            ToolCallStatus.EXECUTING -> Triple("⟳", "Generating...", LCATheme.warningColor)
            ToolCallStatus.COMPLETED -> Triple("✓", "Done", LCATheme.successColor)
            ToolCallStatus.FAILED -> Triple("✗", "Failed", LCATheme.errorColor)
        }

        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = LCATheme.paddedBorder(0, 4, 2, 4)
            add(JLabel("$icon $text").apply {
                foreground = color
                font = LCATheme.italicFont
            })
        }
    }

    fun createToolResultPanel(summary: String): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = LCATheme.paddedBorder(0, 4, 2, 4)
            add(javax.swing.JTextArea(summary).apply {
                foreground = LCATheme.descriptionForeground
                font = LCATheme.italicFont
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
            }, BorderLayout.CENTER)
        }
    }

    fun createLLMEditPreviewPanel(content: String, parameters: Map<String, String>): JPanel {
        val filePath = parameters["path"] ?: parameters["file"]
        val language = inferLanguageFromPath(filePath)
        val totalLines = content.lines().size
        val isTruncated = totalLines > 24

        val metadataPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            background = LCATheme.codeMetadataBackground
            border = LCATheme.paddedBorder(4, 8, 4, 8)
            isOpaque = true

            add(JLabel("\uD83D\uDCDD").apply { font = LCATheme.smallFont })
            add(JLabel("diff").apply {
                foreground = LCATheme.accentColor
                font = LCATheme.smallBoldFont
            })

            add(JLabel("•").apply { foreground = LCATheme.mutedForeground; font = LCATheme.smallFont })
            add(createClickablePathLabel(filePath ?: "Unknown"))
            add(JLabel("•").apply { foreground = LCATheme.mutedForeground; font = LCATheme.smallFont })
            add(JLabel(language.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }).apply {
                foreground = LCATheme.labelForeground
                font = LCATheme.smallBoldFont
            })
            add(JLabel("•").apply { foreground = LCATheme.mutedForeground; font = LCATheme.smallFont })
            add(JLabel("$totalLines lines").apply {
                foreground = LCATheme.descriptionForeground
                font = LCATheme.smallFont
            })

            if (isTruncated) {
                add(JLabel("•").apply { foreground = LCATheme.mutedForeground; font = LCATheme.smallFont })
                add(JLabel("Preview").apply {
                    foreground = LCATheme.infoColor
                    font = LCATheme.smallFont.deriveFont(Font.ITALIC)
                })
            }
        }

        val container = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        metadataPanel.alignmentX = Component.LEFT_ALIGNMENT
        container.add(metadataPanel)
        container.add(collapsibleCodePanelProvider(content, language, filePath))

        return container
    }

    fun createChangesBadge(changes: CodeChangesData): JPanel {
        val containerPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false
        }

        val badgePanel = object : JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = LCATheme.infoHighlightBackground
                g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.color = LCATheme.infoHighlightBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = LCATheme.paddedBorder(6, 10)
        }

        val fileName = changes.filePath.substringAfterLast('/')
        val action = if (changes.removedLines == 0 && changes.addedLines > 0) "Created" else "Edit"
        val changesText = buildString {
            append("$action $fileName ")
            if (changes.addedLines > 0) append("+${changes.addedLines} ")
            if (changes.removedLines > 0) append("-${changes.removedLines}")
        }.trim()

        val editIcon = JLabel("✏\uFE0F").apply { font = font.deriveFont(12f) }
        val changesLabel = JLabel(changesText).apply {
            foreground = LCATheme.infoHighlightForeground
            font = LCATheme.smallFont
        }

        badgePanel.add(editIcon)
        badgePanel.add(changesLabel)

        badgePanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                deps.openCodeChangesDiff(changes)
            }

            override fun mouseEntered(e: MouseEvent) {
                changesLabel.font = changesLabel.font.deriveFont(Font.BOLD)
                badgePanel.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                changesLabel.font = changesLabel.font.deriveFont(Font.PLAIN)
                badgePanel.repaint()
            }
        })

        containerPanel.add(badgePanel)
        return containerPanel
    }

    fun createSummaryMetricsRow(stats: ExecutionSummaryStats): JComponent {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(createSummaryStatChip("Tokens", stats.totalTokens.toString()))
        panel.add(createSummaryStatChip("Cost", "$${"%.4f".format(stats.totalCostUsd)}"))
        if (stats.failedSteps > 0) {
            panel.add(createSummaryStatChip("Failed", stats.failedSteps.toString(), isError = true))
        }
        return panel
    }

    fun createChangedFilesPanel(files: List<ExecutionSummaryFile>): JComponent {
        val container = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        if (files.isEmpty()) {
            container.add(JLabel("No file changes detected").apply {
                font = LCATheme.italicFont
                foreground = LCATheme.descriptionForeground
            })
            return container
        }

        files.take(MAX_SUMMARY_FILES).forEachIndexed { index, file ->
            val badge = createChangesBadge(
                CodeChangesData(
                    filePath = file.filePath,
                    addedLines = file.addedLines,
                    removedLines = file.removedLines,
                    snapshotId = file.snapshotId
                )
            )
            badge.alignmentX = Component.LEFT_ALIGNMENT
            container.add(badge)
            if (index < MAX_SUMMARY_FILES - 1) {
                container.add(Box.createVerticalStrut(LCATheme.spacingXs))
            }
        }

        if (files.size > MAX_SUMMARY_FILES) {
            val remaining = files.size - MAX_SUMMARY_FILES
            container.add(Box.createVerticalStrut(LCATheme.spacingXs))
            container.add(JLabel("+$remaining more").apply {
                font = LCATheme.italicFont
                foreground = LCATheme.descriptionForeground
            })
        }

        return container
    }

    fun createMarkdownPanel(
        content: String, backgroundColor: Color, foregroundColor: Color, maxBubbleWidth: Int
    ): JPanel {
        if (content.length > LARGE_BUBBLE_THRESHOLD_CHARS) {
            return createExpandableMarkdownPanel(content, backgroundColor, foregroundColor, maxBubbleWidth)
        }

        return createMarkdownPanelInternal(
            content = content,
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            maxBubbleWidth = maxBubbleWidth
        )
    }

    private fun createMarkdownPanelInternal(
        content: String,
        backgroundColor: Color,
        foregroundColor: Color,
        maxBubbleWidth: Int,
        preferPlainText: Boolean = false
    ): JPanel {
        val panel = FlatMessageBlock(backgroundColor).apply {
            layout = BorderLayout()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val editorPane = deps.markdownService.createMarkdownEditorPane(
            content,
            backgroundColor,
            foregroundColor,
            maxBubbleWidth,
            preferPlainText = preferPlainText
        )
        panel.add(editorPane, BorderLayout.CENTER)
        deps.markdownService.installResponsiveEditorSizing(panel, editorPane, maxBubbleWidth)

        return panel
    }

    private fun createExpandableMarkdownPanel(
        content: String,
        backgroundColor: Color,
        foregroundColor: Color,
        maxBubbleWidth: Int
    ): JPanel {
        val preview = buildCollapsedBubblePreview(content)
        val hiddenChars = (content.length - preview.length).coerceAtLeast(0)

        val container = FlatMessageBlock(backgroundColor).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val previewPanel = createMarkdownPanelInternal(
            content = preview,
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            maxBubbleWidth = maxBubbleWidth,
            preferPlainText = true
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val infoLabel = JLabel("+$hiddenChars more chars").apply {
            foreground = LCATheme.mutedForeground
            font = LCATheme.smallFont.deriveFont(Font.ITALIC)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val toggleButton = JButton("Expand").apply {
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            foreground = LCATheme.accentColor
            font = LCATheme.smallBoldFont
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            margin = Insets(0, 0, 0, 0)
            border = BorderFactory.createEmptyBorder()
        }

        val actionRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(infoLabel)
            add(toggleButton)
        }

        var expandedPanel: JPanel? = null
        var expanded = false

        toggleButton.addActionListener {
            expanded = !expanded
            if (expanded) {
                if (expandedPanel == null) {
                    expandedPanel = createMarkdownPanelInternal(
                        content = content,
                        backgroundColor = backgroundColor,
                        foregroundColor = foregroundColor,
                        maxBubbleWidth = maxBubbleWidth
                    ).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                }
                container.remove(previewPanel)
                container.add(expandedPanel, 0)
                infoLabel.text = "Large bubble expanded"
                toggleButton.text = "Collapse"
            } else {
                expandedPanel?.let(container::remove)
                container.add(previewPanel, 0)
                infoLabel.text = "+$hiddenChars more chars"
                toggleButton.text = "Expand"
            }
            container.revalidate()
            container.repaint()
        }

        container.add(previewPanel)
        container.add(Box.createVerticalStrut(BUBBLE_COMPACT_GAP))
        container.add(actionRow)
        return container
    }

    fun createMixedTextSegmentPanel(
        content: String, backgroundColor: Color, foregroundColor: Color, maxBubbleWidth: Int
    ): JPanel {
        val normalizedContent = deps.markdownService.normalizeMarkdownTablesForRendering(content)
        return createMarkdownPanel(normalizedContent, backgroundColor, foregroundColor, maxBubbleWidth).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    fun createThinkingPanel(thinking: String, messageId: String? = null): JPanel {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        }

        val headerPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 6, 0)
            isOpaque = false
        }

        headerPanel.add(JLabel("\uD83D\uDCAD").apply { font = LCATheme.smallFont })
        headerPanel.add(JLabel("Thinking process").apply {
            font = LCATheme.smallBoldFont
            foreground = LCATheme.descriptionForeground
        })

        val toggleButton = JButton("Show").apply {
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            font = LCATheme.smallFont
            foreground = LCATheme.accentColor
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(0, 4, 0, 4)
            border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
            alignmentY = Component.CENTER_ALIGNMENT
        }
        headerPanel.add(toggleButton)

        val maxThinkingWidth = (deps.availableWidthProvider() - deps.scrollBarAndPadding).coerceAtLeast(300)

        val thinkingContentPanel = FlatMessageBlock(LCATheme.codeBlockBackground).apply {
            layout = BorderLayout()

            val thinkingEditorPane = deps.markdownService.createMarkdownEditorPane(
                thinking,
                LCATheme.codeBlockBackground,
                LCATheme.codeBlockForeground,
                maxThinkingWidth
            ).apply { border = null }

            add(thinkingEditorPane, BorderLayout.CENTER)
            minimumSize = Dimension(100, 60)

            deps.markdownService.installResponsiveEditorSizing(
                container = this,
                editorPane = thinkingEditorPane,
                fallbackWidth = maxThinkingWidth
            )
        }

        val initialExpanded = messageId?.let { deps.isThinkingExpanded(it) } ?: false
        thinkingContentPanel.isVisible = initialExpanded
        toggleButton.text = if (initialExpanded) "Hide" else "Show"

        toggleButton.addActionListener {
            val expanded = !thinkingContentPanel.isVisible
            thinkingContentPanel.isVisible = expanded
            toggleButton.text = if (expanded) "Hide" else "Show"
            if (messageId != null) {
                deps.setThinkingExpanded(messageId, expanded)
            }
            panel.revalidate()
            panel.repaint()
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(thinkingContentPanel, BorderLayout.CENTER)

        return panel
    }

    fun renderPlanSegment(
        plan: ContentSegment.Plan,
        container: JPanel,
        backgroundColor: Color,
        foregroundColor: Color,
        maxWidth: Int
    ) {
        val hasSubtasks = plan.subtasks.isNotEmpty()
        if (!plan.description.isNullOrBlank()) {
            container.add(deps.markdownService.createMarkdownEditorPane(plan.description, backgroundColor, foregroundColor, maxWidth))
            if (hasSubtasks) {
                container.add(Box.createVerticalStrut(BUBBLE_COMPACT_GAP))
            }
        }

        plan.subtasks.forEachIndexed { index, stepMap ->
            val rawKind = stepMap["kind"]?.toString()
                ?: stepMap["tool"]?.toString()
                ?: "unknown"
            val rawToolArgs = stepMap["tool_args"] as? Map<*, *>
                ?: stepMap["args"] as? Map<*, *>
                ?: stepMap["arguments"] as? Map<*, *>
                ?: extractInlineToolArgs(stepMap)
            val normalizedStep = normalizePlanStepForDisplay(rawKind, rawToolArgs)
            val kind = normalizedStep.kind
            val toolArgs = normalizedStep.toolArgs
            val hasToolArgs = toolArgs != null && toolArgs.isNotEmpty()
            val rawDescription = stepMap["description"]?.toString() ?: stepMap["name"]?.toString()
            val description = when {
                rawDescription.isNullOrBlank() -> "Execute $kind"
                rawDescription == "Execute $rawKind" && rawKind != kind -> "Execute $kind"
                else -> rawDescription
            }

            val stepPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = LCATheme.paddedBorder(4, 8, if (hasToolArgs && index < plan.subtasks.lastIndex) 4 else 0, 8)
            }

            val topRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false
            }

            topRow.add(JLabel("${index + 1}.").apply {
                font = LCATheme.monoFont
                foreground = LCATheme.mutedForeground
            })

            topRow.add(JLabel(kind).apply {
                font = Font(LCATheme.monoFont.family, Font.BOLD, LCATheme.smallFont.size)
                foreground = LCATheme.accentColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(LCATheme.accentColor.darker(), 1),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)
                )
                isOpaque = true
                background = LCATheme.accentColor.darker().darker()
            })

            topRow.add(JLabel(description).apply {
                font = LCATheme.bodyFont
                foreground = foregroundColor
            })

            stepPanel.add(topRow, BorderLayout.NORTH)

            if (hasToolArgs) {
                stepPanel.add(createToolParamsPanel(requireNotNull(toolArgs)), BorderLayout.CENTER)
            }

            container.add(stepPanel)
        }
    }

    private fun normalizePlanStepForDisplay(kind: String, toolArgs: Map<*, *>?): PlanStepDisplayData {
        var currentKind = kind
        var currentArgs = toolArgs

        repeat(3) {
            val argsMap = currentArgs ?: return PlanStepDisplayData(currentKind, currentArgs)
            val nestedKind = (argsMap["tool"] ?: argsMap["kind"] ?: argsMap["name"])
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: return PlanStepDisplayData(currentKind, currentArgs)
            val nestedArgs = (
                argsMap["arguments"]
                    ?: argsMap["args"]
                    ?: argsMap["params"]
                    ?: argsMap["tool_args"]
                ) as? Map<*, *>
                    ?: return PlanStepDisplayData(currentKind, currentArgs)

            val keySet = argsMap.keys.map { it.toString() }.toSet()
            val canUnwrap = currentKind.equals("invoke_subagent", ignoreCase = true) ||
                currentKind.equals("run", ignoreCase = true) ||
                nestedKind.equals(currentKind, ignoreCase = true) ||
                keySet.all { it in setOf("tool", "kind", "name", "arguments", "args", "params", "tool_args") }

            if (!canUnwrap) {
                return PlanStepDisplayData(currentKind, currentArgs)
            }

            currentKind = nestedKind
            currentArgs = nestedArgs
        }

        return PlanStepDisplayData(currentKind, currentArgs)
    }

    private data class PlanStepDisplayData(
        val kind: String,
        val toolArgs: Map<*, *>?
    )

    private val PLAN_STEP_META_KEYS = setOf("kind", "tool", "name", "description", "tool_args", "args", "arguments")

    /**
     * Extract tool parameters placed directly at the step level (not nested under tool_args/args/arguments).
     * Some LLMs produce: {"tool": "read_file", "path": "build.gradle"} instead of nesting args.
     * Returns null if no known parameter keys are found.
     */
    private fun extractInlineToolArgs(stepMap: Map<String, Any?>): Map<String, Any?>? {
        val inlineArgs = stepMap.filterKeys { it !in PLAN_STEP_META_KEYS }
        return inlineArgs.takeIf { it.isNotEmpty() }
    }

    fun createContextBadge(metadata: UserContextMetadata): JComponent {
        val card = FlatMessageBlock(LCATheme.systemBubbleBackground).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        val summaryText = metadata.contextSummary ?: "Added ${metadata.contextRefs.size} items"
        val summaryLabel = JLabel("\uD83D\uDCCE $summaryText").apply {
            font = LCATheme.smallFont
            foreground = LCATheme.descriptionForeground
        }
        headerPanel.add(summaryLabel, BorderLayout.WEST)

        val detailsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            isOpaque = false
        }

        metadata.contextRefs.forEachIndexed { _, ref ->
            detailsPanel.add(JLabel(getContextIcon(ref)).apply {
                foreground = LCATheme.descriptionForeground
            })

            val displayName = ref.displayName.ifBlank { ref.path }
            val nameLabel = JLabel("<html><u>${displayName}</u></html>").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = LCATheme.accentColor
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        deps.openContextReference(ref)
                    }
                })
            }
            detailsPanel.add(nameLabel)
        }

        card.add(headerPanel)
        card.add(detailsPanel)

        return card
    }

    fun createClickablePathLabel(path: String): JLabel {
        return JLabel("<html><u>${path}</u></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = LCATheme.accentColor
            font = Font(LCATheme.monoFont.family, Font.PLAIN, LCATheme.bodyFont.size)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    deps.openFileReference(path)
                }
            })
        }
    }

    fun inferLanguageFromPath(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "ts", "tsx" -> "typescript"
            "js", "jsx" -> "javascript"
            "json" -> "json"
            "yml", "yaml" -> "yaml"
            "xml" -> "xml"
            "html" -> "html"
            "css" -> "css"
            "sql" -> "sql"
            "md" -> "markdown"
            else -> "text"
        }
    }

    private fun createSmallIconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(4, 8, 4, 8)
            preferredSize = Dimension(18, 18)
            minimumSize = Dimension(18, 18)
            addActionListener {
                try {
                    action()
                } catch (e: Exception) {
                    deps.showNotification("Error", e.message ?: "Action failed", NotificationType.ERROR)
                }
            }
        }
    }

    fun createClickableFileNameLabel(path: String): JLabel {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
        return JLabel("<html><u>$fileName</u></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = LCATheme.accentColor
            font = Font(LCATheme.monoFont.family, Font.PLAIN, LCATheme.bodyFont.size)
            toolTipText = path
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    deps.openFileReference(path)
                }
            })
        }
    }

    private fun createSummaryStatChip(label: String, value: String, isError: Boolean = false): JComponent {
        val panel = object : JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isError) LCATheme.errorHighlightBackground else LCATheme.infoHighlightBackground
                g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = LCATheme.paddedBorder(6, 10)
        }

        panel.add(JLabel(label).apply {
            font = LCATheme.smallFont.deriveFont(Font.BOLD)
            foreground = if (isError) LCATheme.errorHighlightForeground else LCATheme.infoHighlightForeground
        })

        panel.add(JLabel(value).apply {
            font = LCATheme.smallFont
            foreground = if (isError) LCATheme.errorHighlightForeground else LCATheme.infoHighlightForeground
        })

        return panel
    }

    private fun createToolParamValueLabel(
        key: String,
        value: String,
        fontSize: Int,
        color: Color
    ): JLabel {
        return if (isPathKey(key)) {
            createClickablePathLabel(value)
        } else {
            val limit = if (isLargeParamKey(key)) LARGE_PARAM_PREVIEW_LIMIT else DEFAULT_PARAM_PREVIEW_LIMIT
            JLabel(formatParamValueForDisplay(value, limit)).apply {
                foreground = color
                font = Font(LCATheme.monoFont.family, Font.PLAIN, fontSize)
            }
        }
    }

    private fun createToolParamsPanelInternal(
        params: LinkedHashMap<String, String>,
        backgroundColor: Color,
        labelFont: Font,
        valueFontSize: Int,
        valueColor: Color
    ): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = backgroundColor
            border = LCATheme.paddedBorder(2, 8, 2, 8)

            params.entries.toList().forEachIndexed { index, entry ->
                val key = entry.key
                val value = entry.value
                val row = JBPanel<JBPanel<*>>().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                }

                row.add(JLabel("$key:").apply {
                    foreground = LCATheme.mutedForeground
                    font = labelFont
                    alignmentX = Component.LEFT_ALIGNMENT
                })

                row.add(Box.createVerticalStrut(2))

                val valueComponent = if (isPathKey(key)) {
                    createFileReferencePanel(value)
                } else {
                    JBPanel<JBPanel<*>>(BorderLayout()).apply {
                        isOpaque = false
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                        add(
                            createToolParamValueLabel(
                                key = key,
                                value = value,
                                fontSize = valueFontSize,
                                color = valueColor
                            ),
                            BorderLayout.WEST
                        )
                    }
                }

                valueComponent.alignmentX = Component.LEFT_ALIGNMENT
                row.add(valueComponent)

                add(row)
                if (index < params.size - 1) {
                    add(Box.createVerticalStrut(BUBBLE_COMPACT_GAP))
                }
            }
        }
    }

    private fun orderToolParamsForDisplay(params: Map<String, String>): LinkedHashMap<String, String> {
        if (params.isEmpty()) return linkedMapOf()

        val merged = LinkedHashMap(params)
        extractNestedParams(params).forEach { (nestedKey, nestedValue) ->
            merged.putIfAbsent(nestedKey, nestedValue)
        }

        val ordered = linkedMapOf<String, String>()
        merged.entries
            .filter { isPathKey(it.key) }
            .forEach { ordered[it.key] = it.value }

        merged.entries
            .filterNot { ordered.containsKey(it.key) }
            .forEach { ordered[it.key] = it.value }

        return ordered
    }

    private fun getDisplayableToolParams(params: Map<String, String>): LinkedHashMap<String, String> {
        return orderToolParamsForDisplay(params).entries
            .filter { (_, value) -> value.isNotBlank() }
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    private fun extractNestedParams(params: Map<String, String>): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        NESTED_PARAM_KEYS.forEach { nestedKey ->
            val rawValue = params[nestedKey] ?: return@forEach
            val parsed = parseJsonObject(rawValue) ?: return@forEach
            parsed.forEach { (key, value) ->
                merged.putIfAbsent(key, value)
            }
        }
        return merged
    }

    private fun parseJsonObject(raw: String): Map<String, String>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        return try {
            val parsed = Gson().fromJson(trimmed, TypeToken.get(Map::class.java).type) as? Map<*, *> ?: return null
            parsed.entries.mapNotNull { (key, value) ->
                val keyString = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                keyString to (value?.toString() ?: "")
            }.toMap(LinkedHashMap())
        } catch (_: Exception) {
            null
        }
    }

    private fun isPathKey(key: String): Boolean {
        return key.trim().lowercase() in PATH_PARAM_KEYS
    }

    private fun isLargeParamKey(key: String): Boolean {
        return key.trim().lowercase() in LARGE_PARAM_KEYS
    }

    private fun formatParamValueForDisplay(value: String, limit: Int = DEFAULT_PARAM_PREVIEW_LIMIT): String {
        val normalized = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (normalized.length > limit) "${normalized.take(limit)}... (${normalized.length} chars)" else normalized
    }

    private fun buildCollapsedBubblePreview(content: String): String {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        return if (normalized.length <= COLLAPSED_BUBBLE_PREVIEW_CHARS) {
            normalized
        } else {
            normalized.take(COLLAPSED_BUBBLE_PREVIEW_CHARS).trimEnd() + "\n\n[...]"
        }
    }

    private fun getContextIcon(ref: ContextReference): String = when (ref.type) {
        ContextType.FILE -> "\uD83D\uDCC4"
        ContextType.FOLDER -> "\uD83D\uDCC1"
        ContextType.SELECTION -> "✂\uFE0F"
        ContextType.PROVIDER -> "\uD83D\uDD0C"
        ContextType.DOCS -> "\uD83D\uDCDA"
        ContextType.RULES -> "\uD83D\uDCCB"
        ContextType.OPEN -> "\uD83D\uDC41\uFE0F"
    }
}
