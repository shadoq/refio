package pl.jclab.refio.ui.components.chat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import pl.jclab.refio.core.services.turn.ProposedChange
import pl.jclab.refio.core.services.turn.ToolApprovalService
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalRequest
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Inline panel displayed in ChatView when a tool requires user approval (ASK permission).
 * Shows tool name, arguments, and three action buttons: Approve, Trust, Reject.
 */
class ToolApprovalPanel(
    private val approvalService: ToolApprovalService,
    private val project: Project? = null
) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel(BorderLayout())
    private val toolNameLabel = JBLabel()
    private val descriptionLabel = JBLabel()
    private val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    private val showDiffButton = JButton("Show diff")

    private var currentRequest: ApprovalRequest? = null

    init {
        isVisible = false
        isOpaque = true
        background = JBColor(0xFFF8E1, 0x3E3522) // warm yellow tint
        border = BorderFactory.createCompoundBorder(
            LCATheme.customLineBorder(JBColor.ORANGE, 1),
            JBUI.Borders.empty(8, 12)
        )

        val headerLabel = JBLabel("Tool requires approval").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = JBColor.ORANGE
        }

        toolNameLabel.font = toolNameLabel.font.deriveFont(Font.BOLD)
        descriptionLabel.foreground = LCATheme.descriptionForeground

        val infoPanel = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(toolNameLabel, BorderLayout.NORTH)
            add(descriptionLabel, BorderLayout.CENTER)
        }

        val approveButton = JButton("Approve").apply {
            toolTipText = "Execute this tool once (will ask again next time)"
            addActionListener { resolve(ApprovalDecision.Approved) }
        }

        val trustButton = JButton("Trust").apply {
            toolTipText = "Execute and remember for this session"
            addActionListener {
                val req = currentRequest ?: return@addActionListener
                resolve(ApprovalDecision.Trusted(req.toolName))
            }
        }

        val rejectButton = JButton("Reject").apply {
            toolTipText = "Do not execute — stops agent loop"
            foreground = JBColor.RED
            addActionListener { resolve(ApprovalDecision.Rejected()) }
        }

        showDiffButton.toolTipText = "Preview the proposed file change before deciding"
        showDiffButton.isVisible = false
        showDiffButton.addActionListener {
            currentRequest?.proposedChange?.let { showProposedChange(it) }
        }

        buttonsPanel.isOpaque = false
        buttonsPanel.add(approveButton)
        buttonsPanel.add(trustButton)
        buttonsPanel.add(rejectButton)
        buttonsPanel.add(showDiffButton)

        contentPanel.isOpaque = false
        contentPanel.add(headerLabel, BorderLayout.NORTH)
        contentPanel.add(infoPanel, BorderLayout.CENTER)
        contentPanel.add(buttonsPanel, BorderLayout.SOUTH)

        add(contentPanel, BorderLayout.CENTER)
    }

    fun showRequest(request: ApprovalRequest) {
        currentRequest = request
        toolNameLabel.text = "Tool: ${request.toolName}"
        descriptionLabel.text = request.description
        showDiffButton.isVisible = request.proposedChange != null
        isVisible = true
        revalidate()
        repaint()
    }

    /**
     * Open the proposed change BEFORE the user decides: native IntelliJ diff when both
     * contents are available, otherwise (large file, diff-only payload) a scrollable
     * text dialog with the unified diff.
     */
    private fun showProposedChange(change: ProposedChange) {
        val oldContent = change.oldContent
        val newContent = change.newContent
        if (project != null && oldContent != null && newContent != null) {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.filePath)
            val contentFactory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                "Proposed Change: ${change.filePath}",
                contentFactory.create(oldContent, fileType),
                contentFactory.create(newContent, fileType),
                "Current",
                "Proposed"
            )
            DiffManager.getInstance().showDiff(project, request)
        } else {
            showDiffTextDialog(change)
        }
    }

    private fun showDiffTextDialog(change: ProposedChange) {
        val textArea = javax.swing.JTextArea(change.unifiedDiff).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }
        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = JBUI.size(800, 500)
        }
        DialogBuilder().apply {
            setTitle("Proposed Change: ${change.filePath}")
            setCenterPanel(scrollPane)
            addOkAction()
        }.show()
    }

    fun hidePanel() {
        currentRequest = null
        isVisible = false
        revalidate()
        repaint()
    }

    private fun resolve(decision: ApprovalDecision) {
        val req = currentRequest ?: return
        hidePanel()
        approvalService.resolveApproval(req.requestId, decision)
    }
}
