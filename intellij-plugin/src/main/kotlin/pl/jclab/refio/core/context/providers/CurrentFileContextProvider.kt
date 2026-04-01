package pl.jclab.refio.core.context.providers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths

private val logger = dualLogger("CurrentFileContextProvider")

/**
 * Provider for currently active file in editor.
 *
 * Usage: @current
 * Returns the file currently focused in the editor.
 * Only returns file if within project sandbox.
 */
class CurrentFileContextProvider : BaseContextProvider() {

    override val environment = ContextProviderEnvironment.IDE_ONLY

    override val description = ContextProviderDescription(
        title = "current",
        displayTitle = "current",
        description = "Currently active file in editor",
        type = ProviderType.NORMAL,
        icon = "📝"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val project = extras.project as? Project ?: return emptyList()
        val workspacePath = extras.workspacePath.ifEmpty { project.basePath ?: "" }

        if (workspacePath.isEmpty()) {
            logger.warn { "Workspace path not available" }
            return emptyList()
        }

        logger.debug { "Getting current file for project: ${project.name}" }

        val pathSandbox = PathSandbox(Paths.get(workspacePath))

        // Get currently selected file (requires read action)
        val currentFile = ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.selectedFiles.firstOrNull()
        }

        if (currentFile == null) {
            logger.debug { "No file currently open" }
            return emptyList()
        }

        logger.debug { "Current file: ${currentFile.path}" }

        // Validate path is within project sandbox
        val validatedPath = try {
            pathSandbox.validatePathWithWarning(Paths.get(currentFile.path))
        } catch (e: SecurityException) {
            logger.warn { "Current file is outside project sandbox: ${currentFile.path}" }
            return emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Path validation failed: ${currentFile.path}" }
            return emptyList()
        }

        val relativePath = getRelativePath(workspacePath, validatedPath.toString())
        val content = try {
            Files.readString(validatedPath)
        } catch (e: Exception) {
            logger.error(e) { "Failed to read file: ${validatedPath}" }
            return emptyList()
        }
        val extension = currentFile.extension ?: ""

        return listOf(
            ContextItem(
                description = "${currentFile.name}  $relativePath",
                content = "```$extension\n$content\n```",
                name = currentFile.name,
                uri = ContextUri(
                    type = "file",
                    value = validatedPath.toString()
                )
            )
        )
    }

    private fun getRelativePath(basePath: String, filePath: String): String {
        return if (basePath.isNotEmpty() && filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        } else {
            filePath
        }
    }
}
