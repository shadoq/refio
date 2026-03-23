package pl.jclab.refio.core.context.providers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths

private val logger = dualLogger("OpenFilesContextProvider")

/**
 * Provider for currently open files in editor.
 *
 * Usage: @open_files
 * Returns all files currently open in editor tabs.
 * Only includes files within project sandbox.
 */
class OpenFilesContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "open_files",
        displayTitle = "open_files",
        description = "Reference currently open files in editor",
        type = ProviderType.NORMAL,
        icon = "📂"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val project = extras.project
        if (project == null) {
            logger.warn { "No project provided in extras - cannot get open files" }
            return emptyList()
        }

        val workspacePath = extras.workspacePath.ifEmpty { project.basePath ?: "" }

        if (workspacePath.isEmpty()) {
            logger.warn { "Workspace path not available for project: ${project.name}" }
            return emptyList()
        }

        logger.debug { "Getting open files for project: ${project.name}, workspace: $workspacePath" }

        val pathSandbox = PathSandbox(Paths.get(workspacePath))

        // Get open files from IntelliJ (requires read action)
        val openFiles = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val files = fileEditorManager.openFiles.toList()
            logger.debug { "FileEditorManager returned ${files.size} open files" }
            files
        }

        if (openFiles.isEmpty()) {
            logger.debug { "No open files found in editor" }
            return emptyList()
        }

        logger.info { "Found ${openFiles.size} open files in editor" }

        // Filter only files within project sandbox
        val fileItems = openFiles
            .filter { file -> isPathInSandbox(pathSandbox, file.path) }
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
                    logger.warn(e) { "Failed to read open file: ${virtualFile.path}" }
                    null
                }
            }

        if (fileItems.isEmpty()) {
            logger.debug { "No open files found in editor after filtering" }
            return emptyList()
        }

        val headerItem = ContextItem(
            description = "Open files summary",
            content = "Open files count: ${fileItems.size}",
            name = "open_files",
            uri = ContextUri(
                type = "meta",
                value = "open_files"
            )
        )

        return listOf(headerItem) + fileItems
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
