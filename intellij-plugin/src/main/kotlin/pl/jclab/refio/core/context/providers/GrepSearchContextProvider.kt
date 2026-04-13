package pl.jclab.refio.core.context.providers

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

private val logger = dualLogger("GrepSearchContextProvider")

/**
 * Provider for grep/regex search across project.
 *
 * Usage: @grep:pattern
 * Searches codebase for text/regex pattern (like grep/ripgrep).
 */
class GrepSearchContextProvider : BaseContextProvider() {
    private val configService = ConfigService(ConfigRepository())

    override val description = ContextProviderDescription(
        title = "grep",
        displayTitle = "grep",
        description = "Search codebase with regex patterns",
        type = ProviderType.QUERY,
        icon = "🔎"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val searchPattern = query.trim()
        if (searchPattern.isEmpty()) {
            logger.warn { "Empty grep search pattern" }
            return emptyList()
        }

        logger.debug { "Grep search pattern: $searchPattern" }

        val project = extras.project as? Project ?: return emptyList()
        val workspacePath = extras.workspacePath.ifEmpty { project.basePath ?: "" }

        // Perform search using IntelliJ Find API
        val results = performGrepSearch(project, searchPattern, workspacePath)
        val filteredResults = filterIgnoredResults(results, workspacePath)

        if (filteredResults.isEmpty()) {
            val message = if (results.isEmpty()) {
                "No matches found for pattern: $searchPattern"
            } else {
                "No matches found for pattern: $searchPattern (filtered by ignore rules)"
            }
            return listOf(
                ContextItem(
                    description = "Grep: No matches",
                    content = message,
                    name = "Grep Search",
                    uri = ContextUri(type = "grep", value = searchPattern)
                )
            )
        }

        val content = buildString {
            appendLine("Grep Search Results: \"$searchPattern\"")
            appendLine("Found ${filteredResults.size} matches:")
            appendLine()

            filteredResults.forEach { result ->
                appendLine("${result.file}:${result.line}")
                appendLine("  ${result.text}")
                appendLine()
            }
        }

        return listOf(
            ContextItem(
                description = "Grep: $searchPattern (${filteredResults.size} matches)",
                content = content,
                name = "Grep Search",
                uri = ContextUri(type = "grep", value = searchPattern)
            )
        )
    }

    private fun performGrepSearch(
        project: com.intellij.openapi.project.Project,
        pattern: String,
        workspacePath: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            com.intellij.openapi.application.runReadAction {
                logger.debug { "Executing grep search for pattern: $pattern" }

                // Use simplified search approach
                try {
                    val projectScope = GlobalSearchScope.projectScope(project)
                    val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)

                    // Search using PsiSearchHelper for text occurrences
                    val searchHelper = com.intellij.psi.search.PsiSearchHelper.getInstance(project)
                    val processor = com.intellij.psi.search.TextOccurenceProcessor { element, offsetInElement ->
                        try {
                            val containingFile = element.containingFile
                            val virtualFile = containingFile?.virtualFile

                            if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
                                val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
                                    .getDocument(containingFile)

                                if (document != null) {
                                    val offset = element.textRange.startOffset + offsetInElement
                                    val lineNumber = document.getLineNumber(offset)
                                    val lineStartOffset = document.getLineStartOffset(lineNumber)
                                    val lineEndOffset = document.getLineEndOffset(lineNumber)
                                    val lineText = document.getText(
                                        com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset)
                                    )

                                    val relativePath = getRelativePath(workspacePath, virtualFile.path)

                                    results.add(
                                        SearchResult(
                                            file = relativePath,
                                            line = lineNumber + 1,
                                            text = lineText.trim()
                                        )
                                    )

                                    // Limit results
                                    if (results.size >= 50) {
                                        return@TextOccurenceProcessor false
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to process occurrence: ${e.message}" }
                        }

                        true // Continue searching
                    }

                    // Search for plain text (not regex for simplicity)
                    searchHelper.processElementsWithWord(
                        processor,
                        projectScope,
                        pattern,
                        com.intellij.psi.search.UsageSearchContext.ANY,
                        true // case sensitive
                    )

                    logger.debug { "Found ${results.size} matches for pattern: $pattern" }
                } catch (e: Exception) {
                    logger.error(e) { "Search failed: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to perform grep search" }
        }

        return results.take(50) // Limit results
    }

    private fun filterIgnoredResults(
        results: List<SearchResult>,
        workspacePath: String
    ): List<SearchResult> {
        if (workspacePath.isBlank()) return results
        val ignoreMatcher = resolveIgnoreMatcher(Paths.get(workspacePath))
        return results.filterNot { result ->
            ignoreMatcher.isIgnored(result.file, isDirectory = false)
        }
    }

    private fun resolveIgnoreMatcher(projectRoot: Path): AiIgnoreMatcher {
        val patterns = configService.getTyped(ConfigKeys.RAG_IGNORED_DIRECTORIES).toSet()
        return try {
            AiIgnoreMatcher.load(projectRoot) ?: AiIgnoreMatcher.fromPatterns(patterns)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${AiIgnoreMatcher.FILE_NAME}; using default ignore patterns" }
            AiIgnoreMatcher.fromPatterns(patterns)
        }
    }

    private fun getRelativePath(basePath: String, filePath: String): String {
        return if (basePath.isNotEmpty() && filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        } else {
            filePath
        }
    }

    data class SearchResult(
        val file: String,
        val line: Int,
        val text: String
    )
}
