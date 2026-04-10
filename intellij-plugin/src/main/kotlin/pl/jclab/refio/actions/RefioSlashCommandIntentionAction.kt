package pl.jclab.refio.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.CodeSnippet
import pl.jclab.refio.api.models.SlashCommand
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.components.chat.PromptInputPanel
import java.awt.Container
import javax.swing.SwingUtilities

class RefioSlashCommandIntentionAction(
    private val command: SlashCommand
) : IntentionAction {

    private val logger = dualLogger("RefioSlashCommandIntentionAction")
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getText(): String = "Refio: /${command.name}"

    override fun getFamilyName(): String = "Refio"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return editor?.selectionModel?.hasSelection() == true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val ed = editor ?: return
        val vFile = file?.virtualFile ?: return

        val selectionModel = ed.selectionModel
        if (!selectionModel.hasSelection()) return

        val document = ed.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
        val content = selectionModel.selectedText ?: return

        val snippet = CodeSnippet(
            filepath = vFile.path,
            filename = vFile.name,
            startLine = startLine,
            endLine = endLine,
            content = content,
            language = vFile.extension
        )

        val targetMode = resolveTargetMode(command.category)

        cs.launch {
            try {
                val sessionManager = SessionManager.getInstance(project)
                sessionManager.createSession("/${command.name}", targetMode)

                SwingUtilities.invokeLater {
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Refio")
                    toolWindow?.show {
                        val panel = findPromptInputPanel(toolWindow.component)
                        if (panel != null) {
                            panel.addCodeSnippet(snippet)
                            panel.sendPrompt("/${command.name}")
                        } else {
                            logger.warn { "PromptInputPanel not found in Refio tool window" }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to invoke /${command.name} intention" }
            }
        }
    }

    override fun startInWriteAction(): Boolean = false

    private fun resolveTargetMode(category: String): TaskMode {
        return when (category.lowercase()) {
            "understanding", "analysis" -> TaskMode.PLAN
            else -> TaskMode.AGENT
        }
    }

    private fun findPromptInputPanel(component: java.awt.Component): PromptInputPanel? {
        if (component is PromptInputPanel) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findPromptInputPanel(child)
                if (found != null) return found
            }
        }
        return null
    }
}
