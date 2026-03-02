package pl.jclab.refio.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.rag.BackgroundIndexingTask

class RagIndexingStartup : ProjectActivity {
    private val logger = dualLogger("RagIndexingStartup")

    override suspend fun execute(project: Project) {
        val projectPath = project.basePath
        if (projectPath.isNullOrBlank()) {
            logger.warn { "Project path unavailable, skipping startup indexing" }
            return
        }

        val coreManager = CoreConnectionManager.getInstance()
        val configService = coreManager.getApiRouter().configService

        if (!configService.isRagEnabled() || !configService.shouldIndexRagOnStartup()) {
            logger.info { "Startup RAG indexing disabled via configuration" }
            return
        }

        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    BackgroundIndexingTask(project, coreManager).queue()
                    logger.info { "Queued RAG indexing task for project ${project.name}" }
                }
            },
            ModalityState.NON_MODAL
        )
    }
}
