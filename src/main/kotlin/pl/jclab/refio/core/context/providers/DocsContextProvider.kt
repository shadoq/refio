package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.BaseContextProvider
import pl.jclab.refio.core.context.ContextItem
import pl.jclab.refio.core.context.ContextProviderDescription
import pl.jclab.refio.core.context.ContextProviderExtras
import pl.jclab.refio.core.context.ContextSubmenuItem
import pl.jclab.refio.core.context.ContextUri
import pl.jclab.refio.core.context.LoadSubmenuItemsArgs
import pl.jclab.refio.core.context.ProviderType
import pl.jclab.refio.core.db.DocIndexingStatus
import pl.jclab.refio.core.db.DocSourceType
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.OpenAIEmbeddingProvider
import pl.jclab.refio.core.services.OllamaEmbeddingProvider
import pl.jclab.refio.core.services.RagSearchService
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

private val logger = dualLogger("DocsContextProvider")
private const val MAX_INLINE_DOC_CHARS = 8_192

/**
 * Provider for documentation search and selection.
 *
 * Usage:
 * - @docs <query>  -> semantic search across indexed docs
 * - @docs (submenu) -> pick a specific doc source to attach top chunks
 */
class DocsContextProvider(
    private val configService: ConfigService = ConfigService(ConfigRepository()),
    private val ragRepository: RagRepository = RagRepository(),
    private val documentationRepository: DocumentationRepository = DocumentationRepository()
) : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "docs",
        displayTitle = "docs",
        description = "Search indexed documentation",
        type = ProviderType.SUBMENU,
        icon = null
    )

    override suspend fun loadSubmenuItems(
        args: LoadSubmenuItemsArgs
    ): List<ContextSubmenuItem> {
        val projectRoot = args.project.basePath ?: return emptyList()
        val docSources = getDocSourcesForProject(projectRoot)
            .filter { it.status == DocIndexingStatus.INDEXED }

        if (docSources.isEmpty()) {
            return emptyList()
        }

        return docSources.map { source ->
            val displayName = displayNameFor(source)
            val description = if (source.sourceType == DocSourceType.FILE) {
                "${source.pagesIndexed} pages | ${source.filePath ?: source.url}"
            } else {
                "${source.pagesIndexed} pages | ${source.url}"
            }
            ContextSubmenuItem(
                id = source.id.toString(),
                title = displayName,
                description = description,
                metadata = buildMap<String, Any> {
                    put("url", source.url)
                    put("sourceType", source.sourceType.name)
                    source.filePath?.let { put("filePath", it) }
                }
            )
        }
    }

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> = withContext(Dispatchers.IO) {
        val projectRoot = extras.workspacePath.ifEmpty { extras.project?.basePath ?: "" }
        if (projectRoot.isEmpty()) {
            logger.warn { "No project root found" }
            return@withContext listOf(
                errorItem(
                    title = "No Project Root",
                    content = "Could not determine project root directory"
                )
            )
        }

        val docSources = getDocSourcesForProject(projectRoot)
        if (docSources.isEmpty()) {
            return@withContext listOf(
                errorItem(
                    title = "No Documentation Indexed",
                    content = "Add documentation in Settings -> Refio -> Documentation and run indexing."
                )
            )
        }

        val trimmedQuery = query.trim()
        val selectedSource = docSources.find {
            it.id.toString() == trimmedQuery ||
                    it.url == trimmedQuery ||
                    (it.title?.equals(trimmedQuery, ignoreCase = true) == true) ||
                    sourceKey(it) == trimmedQuery ||
                    displayNameFor(it).equals(trimmedQuery, ignoreCase = true)
        }

        if (selectedSource != null) {
            if (selectedSource.status != DocIndexingStatus.INDEXED) {
                return@withContext listOf(
                    errorItem(
                        title = "Documentation Not Indexed",
                        content = "Selected source ${selectedSource.url} is not indexed yet."
                    )
                )
            }

            val sourceKey = sourceKey(selectedSource)
            val files = ragRepository.getIndexedFilesBySourceUrls(listOf(sourceKey))
            if (files.isEmpty()) {
                return@withContext listOf(
                    errorItem(
                        title = "No Indexed Pages",
                        content = "No chunks available for ${displayNameFor(selectedSource)}. Reindex the source."
                    )
                )
            }

            val chunks = files.flatMap { file ->
                ragRepository.getChunksForFile(file.id).map { chunk -> chunk to file }
            }

            if (chunks.isEmpty()) {
                return@withContext listOf(
                    errorItem(
                        title = "No Chunks",
                        content = "No indexed chunks found for ${selectedSource.url}."
                    )
                )
            }

            val totalChars = chunks.sumOf { (chunk, _) -> chunk.content.length }
            if (totalChars <= MAX_INLINE_DOC_CHARS) {
                val displayName = displayNameFor(selectedSource)
                val combined = buildString {
                    appendLine("Source: $displayName")
                    chunks.sortedBy { it.first.chunkIndex }.forEach { (chunk, file) ->
                        appendLine("--- ${file.filePath} (chunk ${chunk.chunkIndex}) ---")
                        appendLine(chunk.content)
                        appendLine()
                    }
                }.trim()

                return@withContext listOf(
                    ContextItem(
                        description = "${displayName} (${chunks.size} chunk(s))",
                        content = combined,
                        name = displayName,
                        uri = ContextUri(type = "docs", value = sourceKey)
                    )
                )
            }

            val ragComponents = createRagSearchService()
            if (ragComponents == null) {
                return@withContext listOf(
                    errorItem(
                        title = "Embedding Provider Unavailable",
                        content = "Configure embedding provider or check API keys before using documentation search."
                    )
                )
            }
            val (ragSearchService, modelId) = ragComponents

            val ragQuery = extras.fullInput.ifBlank { selectedSource.title ?: selectedSource.url }
            val ragResults = try {
                val config = RagSearchConfig.forDocumentation().copy(topK = 5)
                ragSearchService.search(
                    projectRoot = projectRoot,
                    query = ragQuery,
                    model = modelId,
                    config = config
                )
            } catch (e: Exception) {
                logger.error(e) { "RAG search failed for documentation ${selectedSource.url}" }
                emptyList()
            }

            val filtered = ragResults.filter { result ->
                ragRepository.getIndexedFile(result.fileId)?.sourceUrl == sourceKey
            }

            if (filtered.isEmpty()) {
                return@withContext listOf(
                    errorItem(
                        title = "No Relevant Content",
                        content = "No relevant sections found for ${displayNameFor(selectedSource)}. Try reindexing or refining your query."
                    )
                )
            }

            return@withContext filtered.map { result ->
                ContextItem(
                    description = "${result.filePath} (similarity: ${String.format("%.2f", result.similarity)})",
                    content = result.content,
                    name = result.filePath.substringAfterLast('/').substringBefore('?'),
                    uri = ContextUri(type = "docs-search", value = result.filePath)
                )
            }
        }

        val finalQuery = trimmedQuery.ifBlank { extras.fullInput.trim() }
        if (finalQuery.isEmpty()) {
            return@withContext listOf(
                errorItem(
                    title = "Missing Query",
                    content = "Type a search phrase or pick a documentation source from the submenu."
                )
            )
        }

        logger.info { "Searching documentation with query: $finalQuery" }

        return@withContext try {
            val ragComponents = createRagSearchService()
            if (ragComponents == null) {
                return@withContext listOf(
                    errorItem(
                        title = "Embedding Provider Unavailable",
                        content = "Configure embedding provider or check API keys before using documentation search."
                    )
                )
            }
            val (ragSearchService, modelId) = ragComponents

            val config = RagSearchConfig.forDocumentation().copy(topK = 5)
            val results = ragSearchService.search(
                projectRoot = projectRoot,
                query = finalQuery,
                model = modelId,
                config = config
            )

            if (results.isEmpty()) {
                logger.warn { "No results found for query: $finalQuery" }
                listOf(
                    ContextItem(
                        description = "No Results",
                        content = "No relevant documentation found for \"$finalQuery\".",
                        name = "Documentation Search",
                        uri = ContextUri(type = "docs-search", value = finalQuery)
                    )
                )
            } else {
                logger.info { "Found ${results.size} documentation results" }
                results.map { result ->
                    ContextItem(
                        description = "${result.filePath} (similarity: ${String.format("%.2f", result.similarity)})",
                        content = buildString {
                            appendLine("Source: ${result.filePath}")
                            if (result.startLine != null && result.endLine != null) {
                                appendLine("Section: lines ${result.startLine}-${result.endLine}")
                            }
                            appendLine("Similarity: ${String.format("%.2f", result.similarity)}")
                            appendLine()
                            appendLine(result.content)
                        },
                        name = result.filePath.substringAfterLast('/').substringBefore('?'),
                        uri = ContextUri(
                            type = "docs-search",
                            value = result.filePath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to perform documentation search" }
            listOf(
                errorItem(
                    title = "Search Error",
                    content = "Error performing documentation search: ${e.message}"
                )
            )
        }
    }

    private fun getDocSourcesForProject(projectRoot: String): List<pl.jclab.refio.core.db.DocumentationSource> {
        if (projectRoot.isBlank()) return emptyList()

        // Try multiple normalized variants to avoid case/slash mismatches on Windows
        val variants = buildSet {
            add(projectRoot)
            add(projectRoot.trimEnd('\\', '/'))
            add(Paths.get(projectRoot).normalize().toString())
            add(Paths.get(projectRoot).normalize().toString().trimEnd('\\', '/'))
            add(projectRoot.lowercase())
            add(projectRoot.uppercase())
        }.filter { it.isNotBlank() }

        val results = mutableMapOf<Int, pl.jclab.refio.core.db.DocumentationSource>()
        variants.forEach { root ->
            documentationRepository.getDocSources(root).forEach { source ->
                results[source.id] = source
            }
        }
        return results.values.sortedByDescending { it.createdAt }
    }

    private fun createRagSearchService(): Pair<RagSearchService, String>? {
        return try {
            val (providerId, modelId) = parseEmbeddingModel(configService.getEmbeddingModel())
            val embeddingProvider = embeddingProviderFor(providerId)
            RagSearchService(ragRepository, embeddingProvider) to modelId
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize RAG search service for docs" }
            null
        }
    }

    private fun parseEmbeddingModel(model: String?): Pair<String, String> {
        val value = model?.takeIf { it.isNotBlank() } ?: "ollama/nomic-embed-text"
        val parts = value.split("/", limit = 2)
        return if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            "openai" to parts[0]
        }
    }

    private fun embeddingProviderFor(providerId: String): EmbeddingProvider {
        return when (providerId.lowercase()) {
            "ollama" -> OllamaEmbeddingProvider(configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT))
            "openai" -> OpenAIEmbeddingProvider()
            else -> OllamaEmbeddingProvider(configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT))
        }
    }

    private fun sourceKey(source: pl.jclab.refio.core.db.DocumentationSource): String {
        return if (source.sourceType == DocSourceType.FILE) {
            source.filePath ?: source.url
        } else {
            source.url
        }
    }

    private fun displayNameFor(source: pl.jclab.refio.core.db.DocumentationSource): String {
        if (source.sourceType == DocSourceType.FILE) {
            return source.filePath?.let { Paths.get(it).fileName?.toString() } ?: source.url
        }
        return source.title ?: source.url
    }

    private fun errorItem(title: String, content: String) = ContextItem(
        description = title,
        content = content,
        name = title,
        uri = ContextUri(type = "error", value = title.lowercase().replace(" ", "-"))
    )
}
