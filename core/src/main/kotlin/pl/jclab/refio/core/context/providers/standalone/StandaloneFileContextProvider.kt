package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.extension

private val logger = dualLogger("StandaloneFileContextProvider")

/**
 * Standalone version of FileContextProvider — no IntelliJ dependency.
 * Uses Files.walk() instead of FilenameIndex for file discovery.
 */
class StandaloneFileContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "file",
        displayTitle = "Files",
        description = "Search and select files from project",
        type = ProviderType.SUBMENU,
        icon = "📄"
    )

    override suspend fun loadSubmenuItems(args: LoadSubmenuItemsArgs): List<ContextSubmenuItem> {
        val workspacePath = args.project as? String ?: return emptyList()
        if (workspacePath.isEmpty()) return emptyList()
        val query = args.query.trim()
        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)

        return if (query.isEmpty()) {
            getAllFiles(projectRoot, workspacePath, pathSandbox, 100)
        } else {
            searchFiles(projectRoot, query, workspacePath, pathSandbox, 20)
        }
    }

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> {
        val filePath = query.trim()
        if (filePath.isEmpty()) return emptyList()

        val workspacePath = extras.workspacePath
        if (workspacePath.isEmpty()) return emptyList()

        val projectRoot = Paths.get(workspacePath)
        val pathSandbox = PathSandbox(projectRoot)
        val requestedPath = if (Paths.get(filePath).isAbsolute) Paths.get(filePath) else projectRoot.resolve(filePath)

        val validatedPath = try {
            pathSandbox.validatePathWithWarning(requestedPath)
        } catch (e: SecurityException) {
            return emptyList()
        }

        if (!Files.exists(validatedPath) || !Files.isRegularFile(validatedPath)) return emptyList()

        val content = try {
            Files.readString(validatedPath)
        } catch (e: Exception) {
            return emptyList()
        }

        val fileName = validatedPath.fileName.toString()
        val extension = fileName.substringAfterLast('.', "")
        val relativePath = relativize(workspacePath, validatedPath.toString())

        return listOf(ContextItem(
            description = "$fileName  $relativePath",
            content = "```$extension\n$content\n```",
            name = fileName,
            uri = ContextUri(type = "file", value = validatedPath.toString())
        ))
    }

    private fun getAllFiles(projectRoot: java.nio.file.Path, workspacePath: String, pathSandbox: PathSandbox, limit: Int): List<ContextSubmenuItem> {
        val result = mutableListOf<ContextSubmenuItem>()
        try {
            Files.walk(projectRoot, 10).use { stream ->
                stream.filter { it.isRegularFile() }
                    .filter { !isIgnored(it) }
                    .filter { isInSandbox(pathSandbox, it) }
                    .limit(limit.toLong())
                    .forEach { file ->
                        result.add(ContextSubmenuItem(
                            id = file.toString(),
                            title = file.fileName.toString(),
                            description = relativize(workspacePath, file.toString())
                        ))
                    }
            }
        } catch (e: Exception) { logger.error(e) { "Failed to list files" } }
        return result
    }

    private fun searchFiles(projectRoot: java.nio.file.Path, pattern: String, workspacePath: String, pathSandbox: PathSandbox, limit: Int): List<ContextSubmenuItem> {
        val result = mutableListOf<ContextSubmenuItem>()
        val lc = pattern.lowercase()
        try {
            Files.walk(projectRoot, 10).use { stream ->
                stream.filter { it.isRegularFile() }
                    .filter { !isIgnored(it) }
                    .filter { isInSandbox(pathSandbox, it) }
                    .filter { it.fileName.toString().lowercase().contains(lc) }
                    .limit(limit.toLong())
                    .forEach { file ->
                        result.add(ContextSubmenuItem(
                            id = file.toString(),
                            title = file.fileName.toString(),
                            description = relativize(workspacePath, file.toString())
                        ))
                    }
            }
        } catch (e: Exception) { logger.error(e) { "Failed to search files" } }
        return result
    }

    private fun isIgnored(path: java.nio.file.Path): Boolean {
        val parts = path.iterator().asSequence().map { it.toString() }.toList()
        return parts.any { it in IGNORED_DIRS || it.startsWith(".") } ||
               path.extension in IGNORED_EXTS
    }

    private fun isInSandbox(sandbox: PathSandbox, path: java.nio.file.Path): Boolean {
        return try { sandbox.validatePathWithWarning(path); true } catch (_: Exception) { false }
    }

    private fun relativize(base: String, path: String) = if (base.isNotEmpty() && path.startsWith(base)) path.removePrefix(base).trimStart('/', '\\') else path

    companion object {
        private val IGNORED_DIRS = setOf("node_modules", "__pycache__", "build", "target", ".idea", ".vscode", "dist", "out", ".gradle", ".venv", "venv", ".git")
        private val IGNORED_EXTS = setOf("class", "jar", "war", "zip", "tar", "gz", "png", "jpg", "jpeg", "gif", "ico", "svg", "woff", "woff2", "ttf", "eot", "mp3", "mp4", "avi")
    }
}
