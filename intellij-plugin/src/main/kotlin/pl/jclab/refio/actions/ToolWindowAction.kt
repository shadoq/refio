package pl.jclab.refio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.toolwindow.RefioMainPanel
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

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Refio")
        if (toolWindow == null) {
            logger.warn { "Refio tool window not found" }
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
}

/**
 * New Session action
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
}

/**
 * Show History action
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
