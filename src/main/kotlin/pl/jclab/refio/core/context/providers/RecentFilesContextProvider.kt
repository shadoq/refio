package pl.jclab.refio.core.context.providers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths

private val logger = dualLogger("RecentFilesContextProvider")

/**
 * Provider for recently edited files.
 *
 * Usage: @recent
 * Returns recently edited files (from IDE history).
 * Only includes files within project sandbox.
 */
class RecentFilesContextProvider : BaseContextProvider() {

    override val environment = ContextProviderEnvironment.IDE_ONLY

    override val description = ContextProviderDescription(
        title = "recent",
        displayTitle = "recent",
        description = "Recently edited files",
        type = ProviderType.SUBMENU,
        icon = "🕐"
    )

    override suspend fun loadSubmenuItems(
        args: LoadSubmenuItemsArgs
    ): List<ContextSubmenuItem> {
        val project = args.project as? Project ?: return emptyList()
        val workspacePath = project.basePath ?: ""

        logger.debug { "Loading recent files submenu" }

        if (workspacePath.isEmpty()) {
            logger.warn { "Workspace path not available" }
            return emptyList()
        }

        val pathSandbox = PathSandbox(Paths.get(workspacePath))

        // Use EditorHistoryManager to get truly recent edited files (requires read action)
        val recentFiles = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
            val editorHistoryManager = com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project)
            editorHistoryManager.fileList
                .asReversed() // Most recent first
                .filter { it.isValid && !it.isDirectory }
        }

        // Filter only files within project sandbox
        return recentFiles
            .filter { file -> isPathInSandbox(pathSandbox, file.path) }
            .take(15)
            .map { file ->
                val relativePath = getRelativePath(workspacePath, file.path)
                ContextSubmenuItem(
                    id = file.path,
                    title = file.name,
                    description = relativePath
                )
            }
    }

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val filePath = query.trim()
        if (filePath.isEmpty()) {
            // If no specific file, return all recent files
            return getAllRecentFiles(extras)
        }

        logger.debug { "Getting context for recent file: $filePath" }

        // Security: Validate path with PathSandbox
        val workspacePath = extras.workspacePath.ifEmpty { (extras.project as? Project)?.basePath ?: "" }
        if (workspacePath.isEmpty()) {
            logger.error { "Workspace path not available for PathSandbox validation" }
            return emptyList()
        }

        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)

        val requestedPath = if (Paths.get(filePath).isAbsolute) {
            Paths.get(filePath)
        } else {
            projectRoot.resolve(filePath)
        }

        // Validate path is within project boundaries
        val validatedPath = try {
            pathSandbox.validatePathWithWarning(requestedPath)
        } catch (e: SecurityException) {
            logger.error(e) { "Security violation: Path outside project boundaries: $filePath" }
            return emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Path validation failed: $filePath" }
            return emptyList()
        }

        if (!Files.exists(validatedPath)) {
            logger.warn { "File not found: $filePath" }
            return emptyList()
        }

        val content = try {
            Files.readString(validatedPath)
        } catch (e: Exception) {
            logger.error(e) { "Failed to read file: $filePath" }
            return emptyList()
        }

        val fileName = validatedPath.fileName.toString()
        val extension = fileName.substringAfterLast('.', "")
        val relativePath = getRelativePath(workspacePath, validatedPath.toString())

        return listOf(
            ContextItem(
                description = "$fileName  $relativePath",
                content = "```$extension\n$content\n```",
                name = fileName,
                uri = ContextUri(type = "file", value = validatedPath.toString())
            )
        )
    }

    private suspend fun getAllRecentFiles(extras: ContextProviderExtras): List<ContextItem> {
        val project = extras.project as? Project ?: return emptyList()
        val workspacePath = extras.workspacePath.ifEmpty { project.basePath ?: "" }

        if (workspacePath.isEmpty()) {
            logger.warn { "Workspace path not available" }
            return emptyList()
        }

        logger.debug { "Getting all recent files" }

        val pathSandbox = PathSandbox(Paths.get(workspacePath))

        // Use EditorHistoryManager to get truly recent edited files (requires read action)
        val recentFiles = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
            val editorHistoryManager = com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project)
            editorHistoryManager.fileList
                .asReversed() // Most recent first
                .filter { it.isValid && !it.isDirectory }
        }

        // Filter only files within project sandbox
        return recentFiles
            .filter { file -> isPathInSandbox(pathSandbox, file.path) }
            .take(10)
            .mapNotNull { virtualFile ->
                try {
                    val validatedPath = pathSandbox.validatePathWithWarning(Paths.get(virtualFile.path))
                    val relativePath = getRelativePath(workspacePath, validatedPath.toString())
                    val content = Files.readString(validatedPath)
                    val extension = virtualFile.extension ?: ""

                    ContextItem(
                        description = "${virtualFile.name}  $relativePath",
                        content = "```$extension\n$content\n```",
                        name = virtualFile.name,
                        uri = ContextUri(
                            type = "file",
                            value = validatedPath.toString()
                        )
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to read recent file: ${virtualFile.path}" }
                    null
                }
            }
    }

    private fun isPathInSandbox(sandbox: PathSandbox, filePath: String): Boolean {
        return try {
            sandbox.validatePathWithWarning(Paths.get(filePath))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getRelativePath(basePath: String, filePath: String): String {
        return if (basePath.isNotEmpty() && filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        } else {
            filePath
        }
    }
}
