package pl.jclab.refio.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Factory for creating the Refio tool window
 */
class RefioToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        // Must match the id attribute in plugin.xml <toolWindow id="..."/>.
        const val TOOL_WINDOW_ID = "RefIo"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val mainPanel = RefioMainPanel(project)
        Disposer.register(contentManager, mainPanel)
        val content = contentManager.factory.createContent(mainPanel, "", false)
        contentManager.addContent(content)

        // Add actions to tool window title bar (left-aligned)
        setupTitleBarActions(toolWindow)
    }

    private fun setupTitleBarActions(toolWindow: ToolWindow) {
        val actionManager = ActionManager.getInstance()

        // Get registered action instances
        val newSessionAction = actionManager.getAction("refio.ToolWindow.NewSession")
        val historyAction = actionManager.getAction("refio.ToolWindow.ShowHistory")
        val settingsAction = actionManager.getAction("refio.ToolWindow.ShowSettings")
        val helpAction = actionManager.getAction("refio.ToolWindow.ShowHelp")

        // Set icon-only display with tooltips
        newSessionAction?.templatePresentation?.apply {
            text = "New Session"
            description = "New Session"
        }
        historyAction?.templatePresentation?.apply {
            text = "History"
            description = "History"
        }
        settingsAction?.templatePresentation?.apply {
            text = "Settings"
            description = "Settings"
        }
        helpAction?.templatePresentation?.apply {
            text = "Help"
            description = "Help"
        }

        // Build action list for title bar
        val actions = mutableListOf<com.intellij.openapi.actionSystem.AnAction>()

        // Add new session button (first, after title)
        newSessionAction?.let { actions.add(it) }

        // Add separator
        actions.add(Separator())

        // Add history, settings, help buttons
        historyAction?.let { actions.add(it) }
        settingsAction?.let { actions.add(it) }
        helpAction?.let { actions.add(it) }

        // Set actions on the title bar (left-aligned)
        toolWindow.setTitleActions(actions)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
