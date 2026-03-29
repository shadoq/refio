package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern
import kotlin.io.path.isRegularFile
import kotlin.io.path.extension

private val logger = dualLogger("StandaloneGrepSearchContextProvider")

/**
 * Standalone version of GrepSearchContextProvider — uses Java regex instead of IntelliJ PsiSearchHelper.
 */
class StandaloneGrepSearchContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "grep",
        displayTitle = "grep",
        description = "Search codebase with regex patterns",
        type = ProviderType.QUERY,
        icon = "🔎"
    )

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> = withContext(Dispatchers.IO) {
        val searchPattern = query.trim()
        if (searchPattern.isEmpty()) return@withContext emptyList()

        val workspacePath = extras.workspacePath
        if (workspacePath.isEmpty()) return@withContext emptyList()

        val projectRoot = Paths.get(workspacePath)
        val results = performSearch(projectRoot, searchPattern)

        if (results.isEmpty()) {
            return@withContext listOf(ContextItem(
                description = "Grep: No matches",
                content = "No matches found for pattern: $searchPattern",
                name = "Grep Search",
                uri = ContextUri(type = "grep", value = searchPattern)
            ))
        }

        val content = buildString {
            appendLine("Grep Search Results: \"$searchPattern\"")
            appendLine("Found ${results.size} matches:")
            appendLine()
            results.forEach { (file, line, text) ->
                appendLine("$file:$line")
                appendLine("  $text")
                appendLine()
            }
        }

        listOf(ContextItem(
            description = "Grep: $searchPattern (${results.size} matches)",
            content = content,
            name = "Grep Search",
            uri = ContextUri(type = "grep", value = searchPattern)
        ))
    }

    private fun performSearch(projectRoot: Path, pattern: String): List<Triple<String, Int, String>> {
        val results = mutableListOf<Triple<String, Int, String>>()
        val regex = try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (_: Exception) {
            Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE)
        }

        try {
            Files.walk(projectRoot, 10).use { stream ->
                stream.filter { it.isRegularFile() }
                    .filter { !isIgnored(it) }
                    .filter { isTextFile(it) }
                    .forEach { file ->
                        if (results.size >= 50) return@forEach
                        try {
                            val lines = Files.readAllLines(file)
                            for ((idx, line) in lines.withIndex()) {
                                if (results.size >= 50) break
                                if (regex.matcher(line).find()) {
                                    val relativePath = projectRoot.relativize(file).toString()
                                    results.add(Triple(relativePath, idx + 1, line.trim()))
                                }
                            }
                        } catch (_: Exception) { /* skip binary/unreadable */ }
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Grep search failed" }
        }

        return results
    }

    private fun isIgnored(path: Path): Boolean {
        val parts = path.iterator().asSequence().map { it.toString() }.toList()
        return parts.any { it in IGNORED_DIRS || it.startsWith(".") }
    }

    private fun isTextFile(path: Path): Boolean {
        val ext = path.extension.lowercase()
        return ext !in BINARY_EXTS && Files.size(path) < 2 * 1024 * 1024
    }

    companion object {
        private val IGNORED_DIRS = setOf("node_modules", "__pycache__", "build", "target", ".idea", ".vscode", "dist", "out", ".gradle", ".venv", "venv", ".git")
        private val BINARY_EXTS = setOf("class", "jar", "war", "zip", "tar", "gz", "png", "jpg", "jpeg", "gif", "ico", "svg", "woff", "woff2", "ttf", "eot", "mp3", "mp4", "avi", "pdf", "exe", "dll", "so", "dylib", "o", "a")
    }
}
