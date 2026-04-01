package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

private val logger = dualLogger("StandaloneCodebaseContextProvider")

/**
 * Standalone CodebaseContextProvider — identical logic to original but
 * without com.intellij.openapi.project.Project import.
 * Uses workspacePath directly from extras.
 */
class StandaloneCodebaseContextProvider : BaseContextProvider() {
    private val configService = ConfigService(ConfigRepository())

    override val description = ContextProviderDescription(
        title = "codebase",
        displayTitle = "codebase",
        description = "Semantic search across codebase",
        type = ProviderType.QUERY,
        icon = "🔍"
    )

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> = withContext(Dispatchers.IO) {
        val searchQuery = query.trim()
        if (searchQuery.isEmpty()) return@withContext emptyList()

        try {
            val ragRepository = RagRepository()
            val embeddingModel = configService.getEmbeddingModel()
            val providerId = if (embeddingModel.contains("/")) embeddingModel.substringBefore("/").lowercase() else "ollama"
            val embeddingProvider = getEmbeddingProvider(providerId) ?: return@withContext listOf(
                errorItem("No embedding provider available for model: $embeddingModel")
            )
            val ragSearchService = RagSearchService(ragRepository, PrefixStrippingProvider(embeddingProvider))

            val projectRoot = extras.workspacePath
            if (projectRoot.isEmpty()) return@withContext listOf(errorItem("No project root found"))

            val stats = ragRepository.getStatistics(projectRoot)
            if (stats.embeddingsCount == 0) return@withContext listOf(
                errorItem("No codebase index found. Build index first (files: ${stats.filesCount}, chunks: ${stats.chunksCount}).")
            )

            val config = RagSearchConfig.forCodeSearch().copy(topK = 5)
            val results = ragSearchService.search(projectRoot = projectRoot, query = searchQuery, model = embeddingModel, config = config)
            val ignoreMatcher = resolveIgnoreMatcher(Paths.get(projectRoot))
            val filtered = results.filterNot { ignoreMatcher.isIgnored(it.filePath, isDirectory = false) }

            if (filtered.isEmpty()) return@withContext listOf(ContextItem(
                description = "No Results", content = "No relevant code found for: \"$searchQuery\"",
                name = "Codebase Search", uri = ContextUri(type = "codebase-search", value = searchQuery)
            ))

            filtered.map { result ->
                ContextItem(
                    description = "${result.filePath} (similarity: ${String.format("%.2f", result.similarity)})",
                    content = buildString {
                        appendLine("File: ${result.filePath}")
                        if (result.startLine != null && result.endLine != null) appendLine("Lines: ${result.startLine}-${result.endLine}")
                        appendLine("Similarity: ${String.format("%.2f", result.similarity)}")
                        appendLine()
                        appendLine(result.content)
                    },
                    name = result.filePath.substringAfterLast('/'),
                    uri = ContextUri(type = "codebase-search", value = result.filePath)
                )
            }
        } catch (e: CircuitBreakerOpenException) {
            listOf(errorItem("Embedding provider temporarily unavailable. Retry in ${(e.retryAfterMs / 1000).coerceAtLeast(1)}s."))
        } catch (e: Exception) {
            logger.error(e) { "Codebase search failed" }
            listOf(errorItem("Error: ${e.message}"))
        }
    }

    private fun getEmbeddingProvider(providerId: String): EmbeddingProvider? = when (providerId) {
        "openai" -> System.getProperty("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { OpenAIEmbeddingProvider() }
        "ollama" -> OllamaEmbeddingProvider(configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT))
        else -> null
    }

    private fun resolveIgnoreMatcher(projectRoot: java.nio.file.Path): AiIgnoreMatcher {
        val patterns = configService.getTyped(ConfigKeys.RAG_IGNORED_DIRECTORIES).toSet()
        return try { AiIgnoreMatcher.load(projectRoot) ?: AiIgnoreMatcher.fromPatterns(patterns) } catch (_: Exception) { AiIgnoreMatcher.fromPatterns(patterns) }
    }

    private fun errorItem(msg: String) = ContextItem(description = msg.take(60), content = msg, name = "Codebase Search", uri = ContextUri(type = "error", value = "codebase"))

    private class PrefixStrippingProvider(private val delegate: EmbeddingProvider) : EmbeddingProvider {
        override suspend fun generateEmbedding(text: String, model: String) = delegate.generateEmbedding(text, model.substringAfter("/"))
        override suspend fun generateBatch(texts: List<String>, model: String) = delegate.generateBatch(texts, model.substringAfter("/"))
        override fun getEmbeddingDimensions(model: String) = delegate.getEmbeddingDimensions(model.substringAfter("/"))
    }
}
