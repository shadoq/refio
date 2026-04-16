package pl.jclab.refio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.components.chat.PromptInputPanel
import pl.jclab.refio.ui.toolwindow.RefioMainPanel
import java.awt.Container

/**
 * Action to add selected code to the current Refio session.
 *
 * Triggered by Ctrl+Shift+J (Windows/Linux) or Cmd+Shift+J (macOS).
 * Adds the selected code as a snippet to the PromptInputPanel.
 */
class AddCodeToCurrentSessionAction : AnAction(
    "Add to Refio",
    "Add selected code to current Refio session",
    null
) {

    private val logger = dualLogger("AddCodeToCurrentSessionAction")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            logger.warn { "No selection in editor" }
            return
        }

        val document = editor.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
        val content = selectionModel.selectedText ?: return

        val snippet = CodeSnippet(
            filepath = file.path,
            filename = file.name,
            startLine = startLine,
            endLine = endLine,
            content = content,
            language = file.extension
        )

        logger.info { "Adding code snippet: ${snippet.displayName}" }

        // Open tool window and add snippet
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Refio")
        if (toolWindow == null) {
            logger.error { "Refio tool window not found!" }
            return
        }

        toolWindow.show {
            val panel = findPromptInputPanel(toolWindow.component)
            if (panel != null) {
                logger.info { "Found PromptInputPanel, adding snippet" }
                panel.addCodeSnippet(snippet)
            } else {
                logger.error { "PromptInputPanel not found in tool window!" }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }

    /**
     * Recursively find PromptInputPanel in the component tree.
     */
    private fun findPromptInputPanel(component: java.awt.Component): PromptInputPanel? {
        if (component is PromptInputPanel) {
            return component
        }

        if (component is Container) {
            for (child in component.components) {
                val found = findPromptInputPanel(child)
                if (found != null) return found
            }
        }

        // Check RefioMainPanel which contains PromptInputPanel
        if (component is RefioMainPanel) {
            for (child in component.components) {
                val found = findPromptInputPanel(child)
                if (found != null) return found
            }
        }

        return null
    }
}
