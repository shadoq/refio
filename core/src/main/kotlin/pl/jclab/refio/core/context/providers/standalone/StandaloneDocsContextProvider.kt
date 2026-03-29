package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.db.DocIndexingStatus
import pl.jclab.refio.core.db.DocSourceType
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

private val logger = dualLogger("StandaloneDocsContextProvider")

/**
 * Standalone DocsContextProvider — no IntelliJ dependency.
 * Uses workspacePath directly from extras instead of Project.basePath.
 */
class StandaloneDocsContextProvider(
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

    override suspend fun loadSubmenuItems(args: LoadSubmenuItemsArgs): List<ContextSubmenuItem> {
        val projectRoot = args.project as? String ?: return emptyList()
        if (projectRoot.isEmpty()) return emptyList()
        val sources = getDocSources(projectRoot).filter { it.status == DocIndexingStatus.INDEXED }
        return sources.map { source ->
            val name = if (source.sourceType == DocSourceType.FILE) source.filePath?.let { Paths.get(it).fileName?.toString() } ?: source.url else source.title ?: source.url
            ContextSubmenuItem(id = source.id.toString(), title = name, description = "${source.pagesIndexed} pages | ${source.url}")
        }
    }

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> = withContext(Dispatchers.IO) {
        val projectRoot = extras.workspacePath
        if (projectRoot.isEmpty()) return@withContext listOf(errorItem("No project root found"))

        val docSources = getDocSources(projectRoot)
        if (docSources.isEmpty()) return@withContext listOf(errorItem("No documentation indexed. Add sources in Settings."))

        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() && extras.fullInput.isBlank()) return@withContext listOf(errorItem("Type a search phrase."))

        val finalQuery = trimmedQuery.ifBlank { extras.fullInput.trim() }

        try {
            val ragComponents = createRagSearch() ?: return@withContext listOf(errorItem("Embedding provider unavailable."))
            val (ragSearchService, modelId) = ragComponents
            val config = RagSearchConfig.forDocumentation().copy(topK = 5)
            val results = ragSearchService.search(projectRoot = projectRoot, query = finalQuery, model = modelId, config = config)

            if (results.isEmpty()) return@withContext listOf(ContextItem(
                description = "No Results", content = "No documentation found for \"$finalQuery\".",
                name = "Documentation Search", uri = ContextUri(type = "docs-search", value = finalQuery)
            ))

            results.map { result ->
                ContextItem(
                    description = "${result.filePath} (similarity: ${String.format("%.2f", result.similarity)})",
                    content = "Source: ${result.filePath}\nSimilarity: ${String.format("%.2f", result.similarity)}\n\n${result.content}",
                    name = result.filePath.substringAfterLast('/').substringBefore('?'),
                    uri = ContextUri(type = "docs-search", value = result.filePath)
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Documentation search failed" }
            listOf(errorItem("Error: ${e.message}"))
        }
    }

    private fun getDocSources(projectRoot: String): List<pl.jclab.refio.core.db.DocumentationSource> {
        val variants = buildSet { add(projectRoot); add(projectRoot.trimEnd('\\', '/')); add(Paths.get(projectRoot).normalize().toString()) }
        val results = mutableMapOf<Int, pl.jclab.refio.core.db.DocumentationSource>()
        variants.forEach { root -> documentationRepository.getDocSources(root).forEach { results[it.id] = it } }
        return results.values.sortedByDescending { it.createdAt }
    }

    private fun createRagSearch(): Pair<RagSearchService, String>? = try {
        val model = configService.getEmbeddingModel() ?: "ollama/nomic-embed-text"
        val parts = model.split("/", limit = 2)
        val providerId = if (parts.size == 2) parts[0] else "openai"
        val modelId = if (parts.size == 2) parts[1] else parts[0]
        val provider = when (providerId.lowercase()) {
            "ollama" -> OllamaEmbeddingProvider(configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT))
            "openai" -> OpenAIEmbeddingProvider()
            else -> OllamaEmbeddingProvider(configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT))
        }
        RagSearchService(ragRepository, provider) to model
    } catch (e: Exception) { null }

    private fun errorItem(msg: String) = ContextItem(description = msg.take(60), content = msg, name = "Documentation", uri = ContextUri(type = "error", value = "docs"))
}
