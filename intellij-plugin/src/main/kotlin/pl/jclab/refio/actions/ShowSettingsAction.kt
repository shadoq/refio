package pl.jclab.refio.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.toolwindow.RefioMainPanel
import java.awt.Container

/**
 * Toolbar action to show Refio settings.
 * Shows the Refio tool window and opens the settings view.
 */
class ShowSettingsAction : AnAction(
    "Settings",
    "Show Refio settings",
    AllIcons.General.Settings
) {

    private val logger = dualLogger("ShowSettingsAction")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            logger.warn { "No project available" }
            return
        }

        logger.info { "Show Settings action triggered" }

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
