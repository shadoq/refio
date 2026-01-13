package pl.jclab.refio.services.rag

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.notification.NotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

class BackgroundIndexingTask(
    project: Project,
    private val coreConnectionManager: CoreConnectionManager,
    private val ignorePatterns: Set<String> = emptySet()
) : Task.Backgroundable(project, "Indexing Project for RAG", true) {

    private var job: Job? = null
    private val progressService = RagProgressService.getInstance(project)

    override fun run(indicator: ProgressIndicator) {
        val projectPath = project?.basePath
        if (projectPath == null) {
            logger.warn { "Project path is null, skipping RAG indexing task" }
            return
        }

        val router = coreConnectionManager.getOrCreateProjectRouter(Paths.get(projectPath), project)

        indicator.isIndeterminate = false
        indicator.text = "Preparing RAG indexing..."
        indicator.text2 = ""
        indicator.fraction = 0.0

        runBlocking {
            job = launch(Dispatchers.IO) {
                runIndexingStage(router, indicator)

                if (indicator.isCanceled) {
                    return@launch
                }

                runEmbeddingStage(router, indicator)
            }

            try {
                job?.join()
                if (indicator.isCanceled) {
                    NotificationService.showWarning(project, "RAG Indexing Cancelled", "Indexing was cancelled")
                }
            } catch (_: CancellationException) {
                NotificationService.showWarning(project, "RAG Indexing Cancelled", "Indexing was cancelled")
                throw ProcessCanceledException()
            }
        }
    }

    override fun onCancel() {
        logger.info { "RAG indexing cancelled by user" }
        job?.cancel()
    }

    override fun onThrowable(error: Throwable) {
        if (error is ProcessCanceledException) {
            return
        }
        logger.error(error) { "Background indexing failed" }
        NotificationService.showError(project, "RAG Indexing Failed", error.message ?: "Unknown error")
    }

    companion object {
        private val logger = dualLogger("BackgroundIndexingTask")
        private const val INDEXING_FRACTION = 0.75
        private const val EMBEDDING_FRACTION = 0.25
    }

    private suspend fun runIndexingStage(
        router: pl.jclab.refio.core.api.CoreApiRouter,
        indicator: ProgressIndicator
    ) {
        indicator.text = "Indexing project files..."
        indicator.text2 = ""

        router.indexProjectForRag(ignorePatterns = ignorePatterns) { progress ->
            val fraction = INDEXING_FRACTION * (progress.progressPercent.coerceIn(0, 100) / 100.0)
            indicator.fraction = fraction
            indicator.text = progress.statusMessage
            indicator.text2 = progress.currentFile ?: ""

            // Update progress service for UI synchronization
            progressService.updateIndexingProgress(progress.progressPercent, progress.statusMessage)

            if (indicator.isCanceled) {
                job?.cancel()
            }
        }
    }

    private suspend fun runEmbeddingStage(
        router: pl.jclab.refio.core.api.CoreApiRouter,
        indicator: ProgressIndicator
    ) {
        val embeddingModel = router.configService.getEmbeddingModel()
        indicator.text = "Generating embeddings (${embeddingModel})..."
        indicator.text2 = ""

        router.generateEmbeddings(
            model = embeddingModel,
            failFastOnUnavailable = true
        ) { progress ->
            val progressFraction = EMBEDDING_FRACTION * (progress.progressPercent.coerceIn(0, 100) / 100.0)
            indicator.fraction = INDEXING_FRACTION + progressFraction
            indicator.text = "Embedding: ${progress.statusMessage}"
            indicator.text2 = ""

            // Update progress service for UI synchronization
            progressService.updateEmbeddingProgress(progress.progressPercent, progress.statusMessage)

            if (indicator.isCanceled) {
                job?.cancel()
            }
        }
    }
}
