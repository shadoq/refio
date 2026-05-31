package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.ui.components.chat.CodeBlockPanel
import pl.jclab.refio.ui.components.chat.ContentSegment
import pl.jclab.refio.ui.components.chat.ContentSegmentParser
import pl.jclab.refio.ui.components.chat.MetricsView
import pl.jclab.refio.ui.components.chat.QuestionData
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class AssistantBubbleRenderer(
    private val context: Context
) : BaseBubbleRenderer() {

    internal interface Context {
        val bubbleCompactGap: Int
        val bubbleContentContext: BubbleContentContext
        fun isInteractiveMode(): Boolean
        fun launch(block: suspend () -> Unit)
        suspend fun answerQuestion(questionId: String, answer: String)
        suspend fun approveSubtask(subtaskId: String)
        suspend fun skipSubtask(subtaskId: String)
    }

    fun render(message: Message): JPanel {
        return tryCreateExecutionSummaryBubble(message)
            ?: tryCreatePlanBubble(message)
            ?: tryCreateToolCallBubble(message)
            ?: tryCreateQuestionBubble(message)
            ?: tryCreateApprovalBubble(message)
            ?: createRegularAssistantBubble(message)
    }

    private val factory get() = context.bubbleContentContext.componentFactory

    private fun tryCreateExecutionSummaryBubble(message: Message): JPanel? {
        val metadata = MessageMetadataExtractor.extractExecutionSummaryMetadata(message) ?: return null
        val statsSubtitle = buildString {
            metadata.stats?.let { append("${it.completedSteps}/${it.totalSteps} steps") }
            metadata.model?.let { model ->
                val provider = metadata.provider?.let { "$it/" } ?: ""
                if (isNotEmpty()) append(" \u2022 ")
                append("$provider$model")
            }
        }.ifBlank { null }

        return createUniversalBubble(
            icon = "\u2713",
            title = "Done",
            subtitle = statsSubtitle,
            content = message.content.ifBlank { "No execution summary available." },
            backgroundColor = LCATheme.assistantBubbleBackground,
            foregroundColor = LCATheme.assistantBubbleForeground,
            context = context.bubbleContentContext
        ) {
            metadata.stats?.let {
                add(Box.createVerticalStrut(LCATheme.spacingSm))
                add(factory.createSummaryMetricsRow(it))
                add(Box.createVerticalStrut(context.bubbleCompactGap))
            }
            val filesHeader = buildString {
                append("Changed Files")
                if (metadata.changedFiles.isNotEmpty()) append(" (${metadata.changedFiles.size})")
            }
            add(JLabel(filesHeader).apply {
                font = LCATheme.headerFont.deriveFont(Font.BOLD)
                foreground = LCATheme.labelForeground
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(factory.createChangedFilesPanel(metadata.changedFiles))
        }
    }

    private fun tryCreatePlanBubble(message: Message): JPanel? {
        // Skip plan bubble when actual tool calls exist — they are rendered
        // individually as tool-call bubbles with the real parameters.
        if (message.toolCallInfo != null) return null
        if (message.content.isBlank() || !MessageMetadataExtractor.isPlanJson(message.content)) return null
        val planData = MessageMetadataExtractor.parsePlanJson(message.content)
        val subtasks = planData["subtasks"] as? List<*> ?: emptyList<Any>()
        val planSubtitle = if (subtasks.isNotEmpty()) {
            "\u2022 ${subtasks.size} ${if (subtasks.size == 1) "step" else "steps"}"
        } else {
            null
        }
        return createUniversalBubble(
            icon = "\uD83D\uDCCB",
            title = "Plan",
            subtitle = planSubtitle,
            content = message.content,
            backgroundColor = LCATheme.assistantBubbleBackground,
            foregroundColor = LCATheme.assistantBubbleForeground,
            context = context.bubbleContentContext
        )
    }

    private fun tryCreateToolCallBubble(message: Message): JPanel? {
        val info = MessageMetadataExtractor.extractToolCallInfo(message) ?: return null
        return createToolCallBubble(message, info)
    }

    private fun tryCreateQuestionBubble(message: Message): JPanel? {
        val questionData = MessageMetadataExtractor.extractQuestionData(message) ?: return null
        return createQuestionBubble(message, questionData)
    }

    private fun tryCreateApprovalBubble(message: Message): JPanel? {
        val subtaskId = MessageMetadataExtractor.extractSubtaskId(message.content) ?: return null
        if (!context.isInteractiveMode()) return null
        return createApprovalBubble(message, subtaskId)
    }

    private fun createRegularAssistantBubble(message: Message): JPanel {
        val content = buildAssistantContent(message)
        val outerPanel = createOuterPanel()
        val backgroundColor = LCATheme.assistantBubbleBackground
        val foregroundColor = LCATheme.assistantBubbleForeground
        val messageBlock = context.bubbleContentContext.createMessageBlock(backgroundColor).apply {
            layout = GridBagLayout()
        }
        var row = 0

        fun addRow(component: JComponent, topInset: Int = 0) {
            messageBlock.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(topInset, 0, 0, 0)
                }
            )
        }

        val subagentName = extractSubagentName(message.metadata)
        val headerTitle = if (subagentName != null) "Subagent \u2022 $subagentName" else "Assistant"
        addRow(
            factory.createBubbleHeader(
                icon = "\uD83E\uDD16",
                title = headerTitle,
                foregroundColor = foregroundColor
            )
        )

        val segments = ContentSegmentParser.parse(content, isStreaming = message.isStreaming)
        val maxWidth = (context.bubbleContentContext.availableWidth - context.bubbleContentContext.scrollBarAndPadding).coerceAtLeast(200)

        if (segments.isEmpty() && content.isNotBlank()) {
            addRow(
                factory.createBubbleContentPanel(
                    content = content,
                    backgroundColor = backgroundColor,
                    foregroundColor = foregroundColor,
                    isUser = false
                )
            )
        } else {
            for (segment in segments) {
                when (segment) {
                    is ContentSegment.Thinking -> {
                        addRow(factory.createThinkingPanel(segment.content, message.id))
                    }
                    is ContentSegment.Code -> {
                        addRow(CodeBlockPanel(segment.codeBlock, context.bubbleContentContext.project).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            border = LCATheme.paddedBorder(2, 0)
                        })
                    }
                    is ContentSegment.Json -> {
                        addRow(createCollapsibleCodePanel(
                            content = segment.content,
                            context = context.bubbleContentContext,
                            language = "json"
                        ))
                    }
                    is ContentSegment.Plan -> {
                        val planWrapper = JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            isOpaque = false
                        }
                        factory.renderPlanSegment(segment, planWrapper, backgroundColor, foregroundColor, maxWidth)
                        addRow(planWrapper)
                    }
                    is ContentSegment.Markdown -> {
                        if (segment.content.isNotBlank()) {
                            val normalized = context.bubbleContentContext.markdownService.normalizeMarkdownTablesForRendering(segment.content)
                            addRow(factory.createMarkdownPanel(normalized, backgroundColor, foregroundColor, maxWidth))
                        }
                    }
                }
            }
        }

        if (message.isStreaming) {
            addRow(
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(JLabel("Generating...").apply {
                        font = LCATheme.smallFont.deriveFont(Font.ITALIC)
                        foreground = LCATheme.mutedForeground
                    })
                },
                topInset = context.bubbleCompactGap
            )
        }

        val codeChanges = MessageMetadataExtractor.extractCodeChanges(message)
        if (codeChanges != null) {
            addRow(factory.createChangesBadge(codeChanges), topInset = context.bubbleCompactGap)
        }
        val metrics = message.metrics
        if (metrics != null) {
            addRow(MetricsView(metrics), topInset = context.bubbleCompactGap)
        }
        addRow(
            factory.wrapRightAligned(factory.createMessageActionsPanel(message = message)),
            topInset = context.bubbleCompactGap
        )

        return addToOuter(outerPanel, messageBlock)
    }

    private fun extractSubagentName(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(metadata).asJsonObject
            json.get("subagent_name")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun buildAssistantContent(message: Message): String {
        val markdownService = context.bubbleContentContext.markdownService
        return buildString {
            if (!message.thinking.isNullOrEmpty()) {
                append("<thinking>${message.thinking}</thinking>\n\n")
                append(markdownService.stripThinkingTags(message.content))
            } else {
                append(message.content)
            }
        }
    }

    private fun createToolCallBubble(message: Message, info: pl.jclab.refio.api.models.ToolCallDisplayInfo): JPanel {
        return when (info.displayType) {
            ToolDisplayType.LLM_EDIT -> createLlmEditToolBubble(message, info)
            ToolDisplayType.CODE_EDIT -> createDetailedToolBubble(message, info, LCATheme.toolBubbleBackground)
            ToolDisplayType.SIMPLE -> createSimpleToolBubble(message, info)
            ToolDisplayType.TERMINAL -> createDetailedToolBubble(message, info, LCATheme.toolBubbleBackground)
        }
    }

    private fun createLlmEditToolBubble(message: Message, info: pl.jclab.refio.api.models.ToolCallDisplayInfo): JPanel {
        val outerPanel = createOuterPanel()
        val messageBlock = context.bubbleContentContext.createMessageBlock(LCATheme.toolBubbleBackground).apply {
            layout = GridBagLayout()
        }
        var row = 0

        fun addRow(component: JComponent, topInset: Int = 0) {
            messageBlock.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(topInset, 0, 0, 0)
                }
            )
        }

        addRow(factory.createToolHeaderPanel(info))
        val primaryPath = factory.getPrimaryPath(info)
        if (!primaryPath.isNullOrBlank()) {
            addRow(factory.createFileReferencePanel(primaryPath), topInset = context.bubbleCompactGap)
        }
        val secondaryParams = factory.getSecondaryToolParams(info)
        if (secondaryParams.isNotEmpty()) {
            addRow(factory.createToolParamsPanel(secondaryParams), topInset = context.bubbleCompactGap)
        }

        message.content.takeIf { it.isNotBlank() }?.let {
            addRow(factory.createLLMEditPreviewPanel(it, info.parameters), topInset = context.bubbleCompactGap)
        }
        info.result?.summary?.let {
            addRow(factory.createToolResultPanel(it), topInset = context.bubbleCompactGap)
        }
        addRow(factory.createToolStatusPanel(info.status), topInset = context.bubbleCompactGap)
        return addToOuter(outerPanel, messageBlock)
    }

    private fun createDetailedToolBubble(
        message: Message,
        info: pl.jclab.refio.api.models.ToolCallDisplayInfo,
        background: java.awt.Color
    ): JPanel {
        val outerPanel = createOuterPanel()
        val messageBlock = context.bubbleContentContext.createMessageBlock(background).apply {
            layout = GridBagLayout()
        }
        var row = 0

        fun addRow(component: JComponent, topInset: Int = 0) {
            messageBlock.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(topInset, 0, 0, 0)
                }
            )
        }

        addRow(factory.createToolHeaderPanel(info))
        addToolCallNarrative(::addRow, message, background)
        val primaryPath = factory.getPrimaryPath(info)
        if (!primaryPath.isNullOrBlank()) {
            addRow(factory.createFileReferencePanel(primaryPath), topInset = context.bubbleCompactGap)
        }
        val secondaryParams = factory.getSecondaryToolParams(info)
        if (secondaryParams.isNotEmpty()) {
            addRow(factory.createToolParamsPanel(secondaryParams), topInset = context.bubbleCompactGap)
        }
        info.result?.summary?.let {
            addRow(factory.createToolResultPanel(it), topInset = context.bubbleCompactGap)
        }
        addRow(factory.createToolStatusPanel(info.status), topInset = context.bubbleCompactGap)
        return addToOuter(outerPanel, messageBlock)
    }

    private fun createSimpleToolBubble(message: Message, info: pl.jclab.refio.api.models.ToolCallDisplayInfo): JPanel {
        val outerPanel = createOuterPanel()
        val background = LCATheme.toolInlineBackground
        val messageBlock = context.bubbleContentContext.createMessageBlock(background).apply {
            layout = GridBagLayout()
        }
        var row = 0

        fun addRow(component: JComponent, topInset: Int = 0) {
            messageBlock.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(topInset, 0, 0, 0)
                }
            )
        }
        val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }
        headerRow.add(JLabel("\uD83D\uDD27").apply { font = LCATheme.bodyFont })
        headerRow.add(JLabel(info.toolName).apply {
            font = LCATheme.boldFont
            foreground = LCATheme.toolNameForeground
        })
        val statusIcon = when (info.status) {
            ToolCallStatus.EXECUTING -> "\u27F3"
            ToolCallStatus.COMPLETED -> "\u2713"
            ToolCallStatus.FAILED -> "\u2717"
        }
        headerRow.add(JLabel(statusIcon).apply { font = LCATheme.bodyFont })
        addRow(headerRow)

        // Main param (path, query, pattern, etc.) on its own row so long values
        // stay visible instead of wrapping or getting truncated in the header.
        val mainParam = info.parameters["path"]
            ?: info.parameters["file"]
            ?: info.parameters["pattern"]
            ?: info.parameters["url"]
            ?: info.parameters["query"]
            ?: info.parameters["command"]
            ?: info.parameters["subagent_name"]
            ?: info.parameters.values.firstOrNull { it.isNotBlank() }
        if (!mainParam.isNullOrBlank()) {
            val shortValue = if (mainParam.length > 200) "${mainParam.take(200)}\u2026" else mainParam
            addRow(
                JLabel(shortValue).apply {
                    foreground = LCATheme.monoTextColor
                    font = java.awt.Font(LCATheme.monoFont.family, java.awt.Font.PLAIN, LCATheme.bodyFont.size)
                    toolTipText = mainParam
                    border = LCATheme.paddedBorder(0, 4, 0, 4)
                },
                topInset = context.bubbleCompactGap
            )
        }

        // Show tool result summary inline (compact form)
        info.result?.summary?.let { summary ->
            if (summary.isNotBlank()) {
                addRow(
                    javax.swing.JTextArea(summary).apply {
                        foreground = LCATheme.descriptionForeground
                        font = LCATheme.smallFont
                        isEditable = false
                        isOpaque = false
                        lineWrap = true
                        wrapStyleWord = true
                        border = LCATheme.paddedBorder(0, 4, 0, 4)
                        rows = summary.length.coerceAtMost(200) / 80 + 1
                    },
                    topInset = context.bubbleCompactGap
                )
            }
        }

        addToolCallNarrative(::addRow, message, background)
        return addToOuter(outerPanel, messageBlock)
    }

    private fun addToolCallNarrative(
        addRow: (JComponent, Int) -> Unit,
        message: Message,
        backgroundColor: java.awt.Color
    ) {
        val content = buildAssistantContent(message).trim()
        if (content.isBlank()) return

        addRow(
            factory.createBubbleContentPanel(
                content = content,
                backgroundColor = backgroundColor,
                foregroundColor = LCATheme.assistantBubbleForeground,
                isUser = false
            ),
            context.bubbleCompactGap
        )
    }

    private fun createQuestionBubble(message: Message, questionData: QuestionData): JPanel {
        val outerPanel = createOuterPanel()
        val messageContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        messageContainer.add(factory.createBubbleHeader("\u2753", "Question"))
        messageContainer.add(
            factory.createBubbleContentPanel(
                content = message.content,
                backgroundColor = LCATheme.questionBubbleBackground,
                foregroundColor = LCATheme.assistantBubbleForeground,
                isUser = false
            )
        )

        val statusLabel = JLabel("").apply {
            foreground = LCATheme.grayColor
            font = font.deriveFont(Font.ITALIC)
        }
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false
            if (questionData.options.isNotEmpty()) {
                questionData.options.forEachIndexed { index, option ->
                    val optionLabel = ('A' + index).toString()
                    add(JButton("$optionLabel. ${option.take(30)}${if (option.length > 30) "..." else ""}").apply {
                        toolTipText = option
                        preferredSize = Dimension(200, 32)
                        addActionListener {
                            context.launch {
                                runCatching {
                                    (parent as? JPanel)?.components?.forEach { (it as? JButton)?.isEnabled = false }
                                    statusLabel.text = "⏳ Sending answer..."
                                    context.answerQuestion(questionData.questionId, option)
                                    statusLabel.text = "✓ Answer sent, orchestration resuming..."
                                }.onFailure { ex ->
                                    statusLabel.text = "✗ Failed: ${ex.message}"
                                }
                            }
                        }
                    })
                }
            } else {
                add(JLabel("\uD83D\uDCAC Type your answer in the input field below").apply {
                    foreground = LCATheme.grayColor
                    font = LCATheme.bodyFont.deriveFont(Font.ITALIC)
                })
            }
            add(statusLabel)
        }

        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(messageContainer, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }
        return addToOuter(outerPanel, container)
    }

    private fun createApprovalBubble(message: Message, subtaskId: String): JPanel {
        val outerPanel = createOuterPanel()
        val messageContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        messageContainer.add(factory.createBubbleHeader("\u26A1", "Approval Required"))
        messageContainer.add(
            factory.createBubbleContentPanel(
                content = message.content,
                backgroundColor = LCATheme.approvalBubbleBackground,
                foregroundColor = LCATheme.assistantBubbleForeground,
                isUser = false
            )
        )
        val questionMetrics = message.metrics
        if (questionMetrics != null) {
            messageContainer.add(Box.createVerticalStrut(context.bubbleCompactGap))
            messageContainer.add(MetricsView(questionMetrics))
        }

        val statusLabel = JLabel("").apply {
            foreground = LCATheme.grayColor
            font = font.deriveFont(Font.ITALIC)
        }

        lateinit var approveBtn: JButton
        lateinit var skipBtn: JButton
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false
            approveBtn = JButton("✓ Approve").apply {
                toolTipText = "Approve and execute step"
                font = font.deriveFont(Font.BOLD)
                preferredSize = Dimension(120, 32)
                addActionListener {
                    context.launch {
                        runCatching {
                            approveBtn.isEnabled = false
                            skipBtn.isEnabled = false
                            statusLabel.text = "⏳ Executing step..."
                            context.approveSubtask(subtaskId)
                            statusLabel.text = "✓ Step completed"
                        }.onFailure { ex ->
                            statusLabel.text = "✗ Execution failed: ${ex.message}"
                        }
                    }
                }
            }
            skipBtn = JButton("⏭ Skip").apply {
                toolTipText = "Skip this step"
                preferredSize = Dimension(100, 32)
                addActionListener {
                    context.launch {
                        runCatching {
                            approveBtn.isEnabled = false
                            skipBtn.isEnabled = false
                            statusLabel.text = "⏭ Step skipped"
                            context.skipSubtask(subtaskId)
                        }.onFailure { ex ->
                            statusLabel.text = "✗ Skip failed: ${ex.message}"
                        }
                    }
                }
            }
            add(approveBtn)
            add(skipBtn)
            add(statusLabel)
        }

        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(messageContainer, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }
        return addToOuter(outerPanel, container)
    }
}
