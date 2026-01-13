package pl.jclab.refio.core.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.*
import java.nio.file.Paths

/**
 * ProjectStartupActivity - Runs background tasks when project is opened
 *
 * Tasks performed:
 * 1. Analyze project structure and cache results (10-minute cache)
 *
 * This ensures that project analysis is ready before user needs it,
 * improving responsiveness of context-aware features.
 *
 * The analysis is cached and automatically invalidated when files are modified,
 * so subsequent requests (e.g., from ContextPanel) can use cached results.
 */
class ProjectStartupActivity : ProjectActivity {
    private val logger = dualLogger("ProjectStartupActivity")

    override suspend fun execute(project: Project) {
        val projectPath = project.basePath
        if (projectPath == null) {
            logger.warn { "Project path is null, skipping background analysis" }
            return
        }

        logger.info { "Starting background project analysis for: $projectPath" }

        try {
            // Launch background analysis (don't block IDE startup)
            // Use IO dispatcher for file operations
            withContext(Dispatchers.IO) {
                // Get or create cached project router
                val coreManager = CoreConnectionManager.getInstance()
                val projectRoot = Paths.get(projectPath)
                val projectRouter = coreManager.getOrCreateProjectRouter(projectRoot)

                // 1. Analyze project structure (will be cached for 10 minutes)
                logger.info { "Analyzing project structure in background..." }
                val analyzerService = projectRouter.getProjectAnalyzerService()
                val analysis = analyzerService?.analyzeProject(
                    projectRoot = projectRoot,
                    includeContent = false // Don't include full file content for startup analysis
                )

                if (analysis != null) {
                    logger.info {
                        "Project analysis completed: " +
                                "${analysis.structure.totalFiles} files, " +
                                "${analysis.technologies.size} technologies, " +
                                "type=${analysis.projectType}"
                    }
                } else {
                    logger.warn { "Project analyzer service not available" }
                }

                // 2. RAG index updates can be added here in the future
                // For now, project analysis caching is the main optimization
                logger.debug { "Background startup analysis completed" }
            }

            logger.info { "Background startup tasks completed for: $projectPath" }
        } catch (e: Exception) {
            // Don't fail IDE startup if background tasks fail
            logger.error(e) { "Failed to complete background startup tasks" }
        }
    }
}
