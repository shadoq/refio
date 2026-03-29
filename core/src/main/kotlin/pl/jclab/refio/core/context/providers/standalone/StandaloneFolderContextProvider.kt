package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val logger = dualLogger("StandaloneFolderContextProvider")

/**
 * Standalone version of FolderContextProvider — no IntelliJ dependency.
 * Uses workspacePath from extras instead of Project.basePath.
 */
class StandaloneFolderContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "folder",
        displayTitle = "Folder",
        description = "Include folder structure and contents",
        type = ProviderType.SUBMENU,
        icon = "📁"
    )

    override suspend fun loadSubmenuItems(args: LoadSubmenuItemsArgs): List<ContextSubmenuItem> {
        val query = args.query.trim()
        // In standalone mode, project is a String (workspace path) or null
        val workspacePath = args.project as? String ?: return emptyList()
        if (workspacePath.isEmpty()) return emptyList()

        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)

        return if (query.isEmpty()) {
            getTopLevelFolders(projectRoot, workspacePath, pathSandbox)
        } else {
            searchFoldersByPattern(projectRoot, query, workspacePath, pathSandbox)
        }
    }

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> {
        val folderPath = query.trim()
        if (folderPath.isEmpty()) return emptyList()

        val workspacePath = extras.workspacePath
        if (workspacePath.isEmpty()) return emptyList()

        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)

        val requestedPath = if (Paths.get(folderPath).isAbsolute) Paths.get(folderPath) else projectRoot.resolve(folderPath)
        val validatedPath = try {
            pathSandbox.validatePathWithWarning(requestedPath)
        } catch (e: SecurityException) {
            return emptyList()
        }

        if (!Files.exists(validatedPath) || !validatedPath.isDirectory()) return emptyList()

        val tree = buildDirectoryTree(validatedPath, 3, 0)
        val relativePath = relativize(workspacePath, validatedPath.toString())

        return listOf(ContextItem(
            description = "Folder: $relativePath",
            content = "```\nFolder structure: $relativePath\n\n$tree\n```",
            name = validatedPath.name,
            uri = ContextUri(type = "folder", value = validatedPath.toString())
        ))
    }

    private fun getTopLevelFolders(projectRoot: Path, workspacePath: String, pathSandbox: PathSandbox): List<ContextSubmenuItem> {
        val result = mutableListOf(ContextSubmenuItem(id = projectRoot.toString(), title = ".", description = "(project root)"))
        try {
            Files.list(projectRoot).use { stream ->
                stream.filter { it.isDirectory() }
                    .filter { !IGNORED.contains(it.fileName.toString()) && !it.fileName.toString().startsWith(".") }
                    .sorted(Comparator.comparing { it.fileName.toString() })
                    .forEach { folder ->
                        result.add(ContextSubmenuItem(id = folder.toString(), title = folder.fileName.toString(), description = relativize(workspacePath, folder.toString())))
                    }
            }
        } catch (e: Exception) { logger.error(e) { "Failed to list folders" } }
        return result.take(30)
    }

    private fun searchFoldersByPattern(projectRoot: Path, pattern: String, workspacePath: String, pathSandbox: PathSandbox): List<ContextSubmenuItem> {
        val result = mutableListOf<ContextSubmenuItem>()
        val lc = pattern.lowercase()
        try {
            Files.walk(projectRoot, 5).use { stream ->
                stream.filter { it.isDirectory() }
                    .filter { !IGNORED.contains(it.fileName.toString()) }
                    .filter { relativize(workspacePath, it.toString()).lowercase().contains(lc) || it.fileName.toString().lowercase().contains(lc) }
                    .limit(30)
                    .forEach { folder -> result.add(ContextSubmenuItem(id = folder.toString(), title = folder.fileName.toString(), description = relativize(workspacePath, folder.toString()))) }
            }
        } catch (e: Exception) { logger.error(e) { "Failed to search folders" } }
        return result
    }

    private fun buildDirectoryTree(dir: Path, maxDepth: Int, depth: Int): String {
        if (depth >= maxDepth) return ""
        val indent = "  ".repeat(depth)
        val sb = StringBuilder()
        try {
            val entries = Files.list(dir).use { s -> s.sorted { a, b -> when { a.isDirectory() && !b.isDirectory() -> -1; !a.isDirectory() && b.isDirectory() -> 1; else -> a.fileName.toString().compareTo(b.fileName.toString()) } }.toList() }
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name.startsWith(".") || IGNORED.contains(name)) continue
                if (entry.isDirectory()) { sb.append("$indent$name/\n"); if (depth < maxDepth - 1) sb.append(buildDirectoryTree(entry, maxDepth, depth + 1)) }
                else if (entry.isRegularFile()) sb.append("$indent$name (${Files.size(entry) / 1024} KB)\n")
            }
        } catch (e: Exception) { sb.append("$indent[Error: ${e.message}]\n") }
        return sb.toString()
    }

    private fun relativize(base: String, path: String) = if (base.isNotEmpty() && path.startsWith(base)) path.removePrefix(base).trimStart('/', '\\') else path

    companion object {
        private val IGNORED = setOf("node_modules", "__pycache__", "build", "target", ".idea", ".vscode", "dist", "out", ".gradle", ".venv", "venv", ".git")
    }
}
