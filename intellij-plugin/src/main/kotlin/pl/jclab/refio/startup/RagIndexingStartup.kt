package pl.jclab.refio.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.core.logging.dualLogger
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

        if (!configService.getTyped<Boolean>(ConfigKeys.RAG_ENABLED) || !configService.getTyped<Boolean>(ConfigKeys.RAG_INDEX_ON_STARTUP)) {
            // Startup auto-indexing is OFF by default (grep-first navigation). Make the
            // re-enable path discoverable instead of silently doing nothing.
            logger.info {
                "Startup RAG indexing disabled (grep/file search is the default navigation path). " +
                    "Enable with rag.index_on_startup=true (and rag.enabled=true) to restore auto-indexing."
            }
            return
        }

        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    BackgroundIndexingTask(project, coreManager).queue()
                    logger.info { "Queued RAG indexing task for project ${project.name}" }
                }
            },
            ModalityState.nonModal()
        )
    }
}
