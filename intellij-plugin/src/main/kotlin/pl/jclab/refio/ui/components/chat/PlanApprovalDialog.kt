package pl.jclab.refio.ui.components.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import pl.jclab.refio.core.api.PlanSummaryResponse
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class PlanApprovalDialog(
    project: Project,
    private val summary: PlanSummaryResponse
) : DialogWrapper(project, true) {

    init {
        title = "Plan Approval"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val summaryPanel = JPanel(GridLayout(0, 2, 8, 4)).apply {
            add(JBLabel("Total steps:"))
            add(JBLabel(summary.totalSteps.toString()))
            add(JBLabel("Read-only steps:"))
            add(JBLabel(summary.readOnlySteps.toString()))
            add(JBLabel("Write steps:"))
            add(JBLabel(summary.writeSteps.toString()))
        }

        val steps = summary.steps.map { step ->
            val label = if (step.isWrite) "WRITE" else "READ"
            "[$label] ${step.tool}: ${step.description}"
        }

        val list = JBList(steps)
        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(700, 300)
        }

        panel.add(summaryPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        okAction.putValue(Action.NAME, "Approve & Execute")
        cancelAction.putValue(Action.NAME, "Modify Plan")
        return arrayOf(okAction, cancelAction)
    }
}
