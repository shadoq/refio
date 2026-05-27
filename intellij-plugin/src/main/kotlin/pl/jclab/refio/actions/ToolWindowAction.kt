package pl.jclab.refio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.toolwindow.RefioMainPanel
import pl.jclab.refio.ui.toolwindow.RefioToolWindowFactory
import java.awt.Container

/**
 * Base class for Refio tool window actions
 * Finds the main panel through the tool window component tree
 */
abstract class ToolWindowAction(
    text: String,
    description: String,
    icon: javax.swing.Icon?
) : AnAction(text, description, icon) {

    protected val logger = dualLogger(javaClass.simpleName)

    /**
     * Find RefioMainPanel in the tool window component tree
     */
    protected fun findMainPanel(e: AnActionEvent): RefioMainPanel? {
        val project = e.project ?: return null

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(RefioToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow == null) {
            logger.warn { "${RefioToolWindowFactory.TOOL_WINDOW_ID} tool window not found" }
            return null
        }

        return findRefioMainPanel(toolWindow.component)
    }

    /**
     * Recursively find RefioMainPanel in the component tree.
     */
    private fun findRefioMainPanel(component: java.awt.Component): RefioMainPanel? {
        if (component is RefioMainPanel) {
            return component
        }

        if (component is Container) {
            for (child in component.components) {
                val found = findRefioMainPanel(child)
                if (found != null) return found
            }
        }

        return null
    }

    override fun update(e: AnActionEvent) {
        // Always enable and visible - these are title bar actions
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * True while a turn / step execution is in flight (mirrors PromptInputPanel's
     * "Stop" state) — so actions that tear down or replace the session can grey
     * themselves out instead of letting the user kill a running agent mid-run.
     */
    protected fun isAgentRunning(project: Project): Boolean {
        val sessionManager = SessionManager.getInstance(project)
        if (sessionManager.userInteraction.isWaitingForResponse.value) return false

        val operation = GlobalMetrics.currentOperation.value
        val isStepExecuting = StepExecutionService.getInstance(project).isExecuting.value
        val isGenerating = sessionManager.isGenerating.value
        return operation !is OperationInfo.Idle || isStepExecuting || isGenerating
    }
}

/**
 * New Session action.
 *
 * Disabled while an agent / step execution is running so the user can't
 * accidentally tear down the active session mid-turn. Matches the "Stop"
 * state shown on PromptInputPanel's send button.
 */
class NewSessionToolWindowAction : ToolWindowAction(
    "New Session",
    "Create a new Refio session",
    com.intellij.icons.AllIcons.General.Add
) {
    override fun actionPerformed(e: AnActionEvent) {
        logger.info { "New Session action triggered" }
        findMainPanel(e)?.createNewSession()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        if (isAgentRunning(project)) {
            e.presentation.isEnabled = false
            e.presentation.description = "New Session (disabled while agent is running)"
        } else {
            e.presentation.description = "Create a new Refio session"
        }
    }
}

/**
 * Show History action.
 *
 * Disabled while an agent / step execution is running — switching to history
 * mid-run would swap out the active session view. See [NewSessionToolWindowAction].
 */
class ShowHistoryToolWindowAction : ToolWindowAction(
    "History",
    "Show Refio session history",
    com.intellij.icons.AllIcons.Vcs.History
) {
    override fun actionPerformed(e: AnActionEvent) {
        logger.info { "Show History action triggered" }
        findMainPanel(e)?.showHistory()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        if (isAgentRunning(project)) {
            e.presentation.isEnabled = false
            e.presentation.description = "History (disabled while agent is running)"
        } else {
            e.presentation.description = "Show Refio session history"
        }
    }
}

/**
 * Show Settings action
 */
class ShowSettingsToolWindowAction : ToolWindowAction(
    "Settings",
    "Show Refio settings",
    com.intellij.icons.AllIcons.General.Settings
) {
    override fun actionPerformed(e: AnActionEvent) {
        logger.info { "Show Settings action triggered" }
        findMainPanel(e)?.showSettings()
    }
}

/**
 * Show Help action
 */
class ShowHelpToolWindowAction : ToolWindowAction(
    "Help",
    "Show Refio help",
    com.intellij.icons.AllIcons.Actions.Help
) {
    override fun actionPerformed(e: AnActionEvent) {
        logger.info { "Show Help action triggered" }
        findMainPanel(e)?.showHelp()
    }
}
