package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
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
        val messageBlock = context.bubbleContentContext.createMessageBlock(LCATheme.assistantBubbleBackground).apply {
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
                foregroundColor = LCATheme.assistantBubbleForeground
            )
        )
        addRow(
            factory.createBubbleContentPanel(
                content = content,
                backgroundColor = LCATheme.assistantBubbleBackground,
                foregroundColor = LCATheme.assistantBubbleForeground,
                isUser = false
            )
        )

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
        if (message.metrics != null) {
            addRow(MetricsView(message.metrics), topInset = context.bubbleCompactGap)
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

        val mainParam = info.parameters["path"]
            ?: info.parameters["file"]
            ?: info.parameters["pattern"]
            ?: info.parameters.values.firstOrNull()
        if (!mainParam.isNullOrBlank()) {
            val shortValue = if (mainParam.length > 100) "${mainParam.take(100)}..." else mainParam
            headerRow.add(JLabel(shortValue).apply {
                foreground = LCATheme.monoTextColor
                font = java.awt.Font(LCATheme.monoFont.family, java.awt.Font.PLAIN, LCATheme.bodyFont.size)
            })
        }
        val statusIcon = when (info.status) {
            ToolCallStatus.EXECUTING -> "⟳"
            ToolCallStatus.COMPLETED -> "✓"
            ToolCallStatus.FAILED -> "✗"
        }
        headerRow.add(JLabel(statusIcon).apply { font = LCATheme.bodyFont })
        addRow(headerRow)
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
        if (message.metrics != null) {
            messageContainer.add(Box.createVerticalStrut(context.bubbleCompactGap))
            messageContainer.add(MetricsView(message.metrics))
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
