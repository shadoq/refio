package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.RagSearchService
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.OpenAIEmbeddingProvider
import pl.jclab.refio.core.services.OllamaEmbeddingProvider
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

private val logger = dualLogger("CodebaseContextProvider")

/**
 * Provider for semantic codebase search.
 *
 * Usage: @codebase:query
 * Performs semantic search across codebase using RAG/embeddings.
 *
 * Note: Requires RAG index to be built. Integration with existing RagRepository.
 */
class CodebaseContextProvider : BaseContextProvider() {
    private val configService = ConfigService(ConfigRepository())

    override val description = ContextProviderDescription(
        title = "codebase",
        displayTitle = "codebase",
        description = "Semantic search across codebase",
        type = ProviderType.QUERY,
        icon = "🔍"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> = withContext(Dispatchers.IO) {
        val searchQuery = query.trim()
        if (searchQuery.isEmpty()) {
            logger.warn { "Empty codebase search query" }
            return@withContext emptyList()
        }

        logger.debug { "Codebase search query: $searchQuery" }

        try {
            // 1. Create service instances
            val ragRepository = RagRepository()
            val embeddingProvider = extras.project?.let { getEmbeddingProvider(it) }
            if (embeddingProvider == null) {
                logger.warn { "No embedding provider available (no project)" }
                return@withContext listOf(
                    ContextItem(
                        description = "No Embedding Provider",
                        content = "Could not initialize embedding provider - project context required",
                        name = "Codebase Search Error",
                        uri = ContextUri(type = "error", value = "no-embedding-provider")
                    )
                )
            }
            val ragSearchService = RagSearchService(ragRepository, embeddingProvider)

            // 2. Determine project root
            val projectRoot = extras.workspacePath.ifEmpty {
                extras.project.basePath ?: ""
            }

            if (projectRoot.isEmpty()) {
                logger.warn { "No project root found" }
                return@withContext listOf(
                    ContextItem(
                        description = "No Project Root",
                        content = "Could not determine project root directory",
                        name = "Codebase Search Error",
                        uri = ContextUri(type = "error", value = "no-project-root")
                    )
                )
            }

            // 3. Check if index exists
            val stats = ragRepository.getStatistics(projectRoot)
            if (stats.embeddingsCount == 0) {
                logger.warn { "No embeddings found for project: $projectRoot" }
                return@withContext listOf(
                    ContextItem(
                        description = "Codebase Index Not Built",
                        content = buildString {
                            appendLine("No codebase index found.")
                            appendLine()
                            appendLine("To enable semantic search:")
                            appendLine("1. Go to Settings → Refio → Index")
                            appendLine("2. Click 'Build Index'")
                            appendLine("3. Wait for indexing to complete")
                            appendLine()
                            appendLine("Current stats:")
                            appendLine("- Files indexed: ${stats.filesCount}")
                            appendLine("- Chunks: ${stats.chunksCount}")
                            appendLine("- Embeddings: ${stats.embeddingsCount}")
                        },
                        name = "Codebase Search",
                        uri = ContextUri(type = "error", value = "no-index")
                    )
                )
            }

            // 4. Perform semantic search
            logger.info { "Searching codebase with ${stats.embeddingsCount} embeddings" }

            val config = RagSearchConfig.forCodeSearch().copy(topK = 5)
            val results = ragSearchService.search(
                projectRoot = projectRoot,
                query = searchQuery,
                model = "ollama/nomic-embed-text",  // TODO: Get from config
                config = config
            )
            val ignoreMatcher = resolveIgnoreMatcher(Paths.get(projectRoot))
            val filteredResults = results.filterNot { result ->
                ignoreMatcher.isIgnored(result.filePath, isDirectory = false)
            }

            // 5. Convert results to ContextItems
            if (filteredResults.isEmpty()) {
                logger.warn { "No results found for query: $searchQuery" }
                return@withContext listOf(
                    ContextItem(
                        description = "No Results",
                        content = "No relevant code found for query: \"$searchQuery\"",
                        name = "Codebase Search",
                        uri = ContextUri(type = "codebase-search", value = searchQuery)
                    )
                )
            }

            logger.info { "Found ${filteredResults.size} results" }

            return@withContext filteredResults.map { result ->
                ContextItem(
                    description = "${result.filePath} (similarity: ${String.format("%.2f", result.similarity)})",
                    content = buildString {
                        appendLine("File: ${result.filePath}")
                        if (result.startLine != null && result.endLine != null) {
                            appendLine("Lines: ${result.startLine}-${result.endLine}")
                        }
                        appendLine("Similarity: ${String.format("%.2f", result.similarity)}")
                        appendLine()
                        appendLine(result.content)
                    },
                    name = result.filePath.substringAfterLast('/'),
                    uri = ContextUri(
                        type = "codebase-search",
                        value = result.filePath
                    )
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to perform codebase search" }
            return@withContext listOf(
                ContextItem(
                    description = "Search Error",
                    content = "Error performing codebase search: ${e.message}",
                    name = "Error",
                    uri = ContextUri(type = "error", value = e.message ?: "unknown")
                )
            )
        }
    }

    private fun resolveIgnoreMatcher(projectRoot: Path): AiIgnoreMatcher {
        val patterns = configService.getRagIgnoredDirectories()
        return try {
            AiIgnoreMatcher.load(projectRoot) ?: AiIgnoreMatcher.fromPatterns(patterns)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${AiIgnoreMatcher.FILE_NAME}; using default ignore patterns" }
            AiIgnoreMatcher.fromPatterns(patterns)
        }
    }

    /**
     * Get embedding provider from config.
     * Falls back to Ollama (local) if OpenAI key is not available.
     */
    private fun getEmbeddingProvider(project: com.intellij.openapi.project.Project): EmbeddingProvider {
        // Check config for embedding provider setting
        val openAiKey = System.getProperty("OPENAI_API_KEY")

        return if (!openAiKey.isNullOrBlank()) {
            logger.info { "Using OpenAI embedding provider" }
            OpenAIEmbeddingProvider()
        } else {
            logger.info { "Using Ollama embedding provider (local)" }
            OllamaEmbeddingProvider(configService.getOllamaEndpoint())
        }
    }
}
