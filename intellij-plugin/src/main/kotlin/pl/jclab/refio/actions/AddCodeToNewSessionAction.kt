package pl.jclab.refio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.chat.PromptInputPanel
import pl.jclab.refio.ui.toolwindow.RefioMainPanel
import java.awt.Container
import javax.swing.SwingUtilities

/**
 * Action to add selected code to a new Refio session.
 *
 * Triggered by Ctrl+J (Windows/Linux) or Cmd+J (macOS).
 * Creates a new session and adds the selected code as a snippet.
 */
class AddCodeToNewSessionAction : AnAction(
    "Add to Refio (New Session)",
    "Add selected code to a new Refio session",
    null
) {

    private val logger = dualLogger("AddCodeToNewSessionAction")
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

        logger.info { "Creating new session and adding code snippet: ${snippet.displayName}" }

        // Create new session first
        cs.launch {
            try {
                val sessionManager = SessionManager.getInstance(project)
                sessionManager.createSession("Code Review", TaskMode.CHAT)

                // Open tool window and add snippet on EDT
                SwingUtilities.invokeLater {
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Refio")
                    toolWindow?.show {
                        findPromptInputPanel(toolWindow.component)?.addCodeSnippet(snippet)
                    }
                }
            } catch (ex: Exception) {
                logger.error(ex) { "Failed to create new session" }
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
