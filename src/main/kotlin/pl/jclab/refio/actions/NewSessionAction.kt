package pl.jclab.refio.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.toolwindow.RefioMainPanel
import java.awt.Container

/**
 * Toolbar action to create a new Refio session.
 * Shows the Refio tool window and triggers new session creation.
 */
class NewSessionAction : AnAction(
    "New Session",
    "Create a new Refio session",
    AllIcons.Actions.AddMulticaret
) {

    private val logger = dualLogger("NewSessionAction")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            logger.warn { "No project available" }
            return
        }

        logger.info { "New Session action triggered" }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Refio")
        if (toolWindow == null) {
            logger.warn { "Refio tool window not found" }
            return
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
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
}
