package pl.jclab.refio.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Factory for creating the Refio tool window
 */
class RefioToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val mainPanel = RefioMainPanel(project)
        val content = contentManager.factory.createContent(mainPanel, "", false)
        contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
