package pl.jclab.refio.core.context.providers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths

private val logger = dualLogger("FileContextProvider")

/**
 * Provider for file selection with optional search.
 *
 * Usage: @file or @file:pattern
 * Shows file picker with search capability.
 */
class FileContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "file",
        displayTitle = "Files",
        description = "Search and select files from project",
        type = ProviderType.SUBMENU,
        icon = "📄"
    )

    override suspend fun loadSubmenuItems(
        args: LoadSubmenuItemsArgs
    ): List<ContextSubmenuItem> {
        val project = args.project
        val query = args.query.trim()
        val workspacePath = project.basePath ?: ""

        logger.debug { "Loading file submenu items, query='$query'" }

        if (workspacePath.isEmpty()) {
            logger.warn { "Workspace path not available" }
            return emptyList()
        }

        val pathSandbox = PathSandbox(Paths.get(workspacePath))

        // If query is empty, return all project files; otherwise filter by pattern
        return if (query.isEmpty()) {
            getAllProjectFiles(project, workspacePath, pathSandbox)
        } else {
            searchFilesByPattern(project, query, workspacePath, pathSandbox)
        }
    }

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val filePath = query.trim()
        if (filePath.isEmpty()) {
            return emptyList()
        }

        logger.debug { "Getting context for file: $filePath" }

        // Security: Validate path with PathSandbox
        val workspacePath = extras.workspacePath.ifEmpty { extras.project?.basePath ?: "" }
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

        if (!Files.isRegularFile(validatedPath)) {
            logger.warn { "Not a regular file: $filePath" }
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

    private fun getAllProjectFiles(
        project: com.intellij.openapi.project.Project,
        workspacePath: String,
        pathSandbox: PathSandbox
    ): List<ContextSubmenuItem> {
        logger.debug { "Getting all project files" }

        val result = mutableListOf<ContextSubmenuItem>()

        try {
            ApplicationManager.getApplication().runReadAction {
                val scope = GlobalSearchScope.projectScope(project)

                FilenameIndex.processAllFileNames(
                    { fileName ->
                        FilenameIndex.getVirtualFilesByName(fileName, scope).forEach { file ->
                            if (result.size < 100 && isPathInSandbox(pathSandbox, file.path)) {
                                result.add(
                                    ContextSubmenuItem(
                                        id = file.path,
                                        title = file.name,
                                        description = getRelativePath(workspacePath, file.path)
                                    )
                                )
                            }
                        }
                        result.size < 100
                    },
                    scope,
                    null
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all project files" }
        }

        logger.debug { "Found ${result.size} project files" }
        return result
    }

    private fun searchFilesByPattern(
        project: com.intellij.openapi.project.Project,
        pattern: String,
        workspacePath: String,
        pathSandbox: PathSandbox
    ): List<ContextSubmenuItem> {
        logger.debug { "Searching files by pattern: $pattern" }

        val result = mutableListOf<ContextSubmenuItem>()

        try {
            ApplicationManager.getApplication().runReadAction {
                val scope = GlobalSearchScope.projectScope(project)

                // Use partial matching - search all filenames and filter by pattern
                FilenameIndex.processAllFileNames(
                    { fileName ->
                        if (fileName.contains(pattern, ignoreCase = true)) {
                            FilenameIndex.getVirtualFilesByName(fileName, scope).forEach { file ->
                                if (result.size < 20 && isPathInSandbox(pathSandbox, file.path)) {
                                    result.add(
                                        ContextSubmenuItem(
                                            id = file.path,
                                            title = file.name,
                                            description = getRelativePath(workspacePath, file.path)
                                        )
                                    )
                                }
                            }
                        }
                        result.size < 20 // Continue processing until we have 20 results
                    },
                    scope,
                    null
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search files by pattern: $pattern" }
        }

        logger.debug { "Found ${result.size} files matching pattern: $pattern" }
        return result.take(20)
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
