package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val logger = dualLogger("FolderContextProvider")

/**
 * Provider for folder/directory contents.
 *
 * Usage: @folder (shows submenu with project folders)
 * Returns directory tree structure and optionally file contents.
 */
class FolderContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "folder",
        displayTitle = "Folder",
        description = "Include folder structure and contents",
        type = ProviderType.SUBMENU,
        icon = "📁"
    )

    override suspend fun loadSubmenuItems(
        args: LoadSubmenuItemsArgs
    ): List<ContextSubmenuItem> {
        val project = args.project
        val query = args.query.trim()
        val workspacePath = project.basePath ?: ""

        logger.debug { "Loading folder submenu items, query='$query'" }

        if (workspacePath.isEmpty()) {
            logger.warn { "Workspace path not available" }
            return emptyList()
        }

        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)

        // If query is empty, return top-level folders
        return if (query.isEmpty()) {
            getTopLevelFolders(projectRoot, workspacePath, pathSandbox)
        } else {
            // Search folders by pattern
            searchFoldersByPattern(projectRoot, query, workspacePath, pathSandbox)
        }
    }

    private fun getTopLevelFolders(
        projectRoot: Path,
        workspacePath: String,
        pathSandbox: PathSandbox
    ): List<ContextSubmenuItem> {
        logger.debug { "Getting top-level folders from: $projectRoot" }

        val result = mutableListOf<ContextSubmenuItem>()

        try {
            // Add project root as first option
            result.add(
                ContextSubmenuItem(
                    id = projectRoot.toString(),
                    title = ".",
                    description = "(project root)"
                )
            )

            // List immediate subdirectories
            Files.list(projectRoot).use { stream ->
                stream.filter { it.isDirectory() }
                    .filter { !isIgnoredFolder(it.fileName.toString()) }
                    .filter { folder -> isPathInSandbox(pathSandbox, folder) }
                    .sorted { a, b -> a.fileName.toString().compareTo(b.fileName.toString()) }
                    .forEach { folder ->
                        val relativePath = getRelativePath(workspacePath, folder.toString())
                        result.add(
                            ContextSubmenuItem(
                                id = folder.toString(),
                                title = folder.fileName.toString(),
                                description = relativePath
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list top-level folders" }
        }

        logger.debug { "Found ${result.size} top-level folders" }
        return result.take(30)
    }

    private fun searchFoldersByPattern(
        projectRoot: Path,
        pattern: String,
        workspacePath: String,
        pathSandbox: PathSandbox
    ): List<ContextSubmenuItem> {
        logger.debug { "Searching folders by pattern: $pattern" }

        val result = mutableListOf<ContextSubmenuItem>()
        val lowercasePattern = pattern.lowercase()

        try {
            Files.walk(projectRoot, 5).use { stream ->
                stream.filter { it.isDirectory() }
                    .filter { !isIgnoredFolder(it.fileName.toString()) }
                    .filter { folder -> isPathInSandbox(pathSandbox, folder) }
                    .filter { path ->
                        val relativePath = getRelativePath(workspacePath, path.toString()).lowercase()
                        relativePath.contains(lowercasePattern) ||
                        path.fileName.toString().lowercase().contains(lowercasePattern)
                    }
                    .limit(30)
                    .forEach { folder ->
                        val relativePath = getRelativePath(workspacePath, folder.toString())
                        result.add(
                            ContextSubmenuItem(
                                id = folder.toString(),
                                title = folder.fileName.toString(),
                                description = relativePath
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search folders by pattern: $pattern" }
        }

        logger.debug { "Found ${result.size} folders matching pattern: $pattern" }
        return result
    }

    private fun isPathInSandbox(sandbox: PathSandbox, path: Path): Boolean {
        return try {
            sandbox.validatePathWithWarning(path)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isIgnoredFolder(name: String): Boolean {
        return name.startsWith(".") ||
               name in setOf("node_modules", "__pycache__", "build", "target", ".idea",
                   ".vscode", "dist", "out", ".gradle", ".venv", "venv", ".git")
    }

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val folderPath = query.trim()
        if (folderPath.isEmpty()) {
            logger.warn { "Empty folder path provided" }
            return emptyList()
        }

        logger.debug { "Getting context for folder: $folderPath" }

        // Security: Validate path with PathSandbox
        val workspacePath = extras.workspacePath.ifEmpty { extras.project?.basePath ?: "" }
        if (workspacePath.isEmpty()) {
            logger.error { "Workspace path not available for PathSandbox validation" }
            return emptyList()
        }

        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)

        val requestedPath = if (Paths.get(folderPath).isAbsolute) {
            Paths.get(folderPath)
        } else {
            projectRoot.resolve(folderPath)
        }

        // Validate path is within project boundaries
        val validatedPath = try {
            pathSandbox.validatePathWithWarning(requestedPath)
        } catch (e: SecurityException) {
            logger.error(e) { "Security violation: Path outside project boundaries: $folderPath" }
            return emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Path validation failed: $folderPath" }
            return emptyList()
        }

        if (!Files.exists(validatedPath)) {
            logger.warn { "Folder not found: $validatedPath" }
            return emptyList()
        }

        if (!validatedPath.isDirectory()) {
            logger.warn { "Path is not a directory: $validatedPath" }
            return emptyList()
        }

        // Build directory tree
        val tree = buildDirectoryTree(validatedPath, maxDepth = 3, currentDepth = 0)
        val relativePath = getRelativePath(workspacePath, validatedPath.toString())

        return listOf(
            ContextItem(
                description = "Folder: $relativePath",
                content = "```\nFolder structure: $relativePath\n\n$tree\n```",
                name = validatedPath.name,
                uri = ContextUri(
                    type = "folder",
                    value = validatedPath.toString()
                )
            )
        )
    }

    private fun buildDirectoryTree(dir: Path, maxDepth: Int, currentDepth: Int): String {
        if (currentDepth >= maxDepth) {
            return ""
        }

        val indent = "  ".repeat(currentDepth)
        val sb = StringBuilder()

        try {
            val entries = Files.list(dir).use { stream ->
                stream.sorted { a, b ->
                    when {
                        a.isDirectory() && !b.isDirectory() -> -1
                        !a.isDirectory() && b.isDirectory() -> 1
                        else -> a.fileName.toString().compareTo(b.fileName.toString())
                    }
                }.toList()
            }

            for (entry in entries) {
                val name = entry.fileName.toString()

                // Skip hidden files and common ignored directories
                if (name.startsWith(".") && name !in setOf(".env", ".gitignore")) {
                    continue
                }
                if (name in setOf("node_modules", "__pycache__", "build", "target", ".idea", ".vscode", "dist", "out", ".gradle", ".venv")) {
                    continue
                }

                if (entry.isDirectory()) {
                    sb.append("$indent$name/\n")
                    if (currentDepth < maxDepth - 1) {
                        sb.append(buildDirectoryTree(entry, maxDepth, currentDepth + 1))
                    }
                } else if (entry.isRegularFile()) {
                    val size = Files.size(entry)
                    val sizeStr = formatFileSize(size)
                    sb.append("$indent$name ($sizeStr)\n")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to scan directory: $dir" }
            sb.append("$indent[Error scanning directory: ${e.message}]\n")
        }

        return sb.toString()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
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
