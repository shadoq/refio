package pl.jclab.refio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.rag.BackgroundIndexingTask

class ReindexRagAction : AnAction(
    "Reindex RAG Database",
    "Rebuild the RAG index for the current project",
    null
) {

    private val logger = dualLogger("ReindexRagAction")

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.isVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath
        if (projectPath.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "Project path is not available. Open a project before reindexing.",
                "Reindex RAG Database"
            )
            return
        }

        val confirmed = Messages.showYesNoDialog(
            project,
            "This will re-index all project files for semantic search. Continue?",
            "Reindex RAG Database",
            Messages.getQuestionIcon()
        )

        if (confirmed != Messages.YES) {
            return
        }

        BackgroundIndexingTask(project, CoreConnectionManager.getInstance()).queue()
        logger.info { "Manual RAG reindex queued for project ${project.name}" }
    }
}
