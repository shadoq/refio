package pl.jclab.refio.core.api.routers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.DocIndexingProgress
import pl.jclab.refio.core.services.DocumentationIndexingService
import pl.jclab.refio.core.db.DocumentationSource
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.DocIndexingStatus
import pl.jclab.refio.core.db.DocSourceType
import pl.jclab.refio.core.db.DocStatistics
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.RagSearchService
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.context.providers.CodebaseContextProvider
import pl.jclab.refio.core.services.ChunkingMode
import pl.jclab.refio.core.services.DefaultChunkingStrategy
import pl.jclab.refio.core.services.RagIndexingService
import pl.jclab.refio.core.services.RagEmbeddingService
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.IndexingProgress
import pl.jclab.refio.core.services.EmbeddingProgress
import pl.jclab.refio.core.services.SemanticChunkingStrategy
import pl.jclab.refio.core.services.rag.RagSearchConfig
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("RagRouter")

/**
 * Router for RAG (Retrieval-Augmented Generation) operations.
 * Handles RAG indexing, search, and embedding generation.
 *
 * This router is responsible for:
 * - Project code indexing with semantic chunking
 * - Documentation indexing
 * - Similarity search
 * - RAG statistics and management
 *
 * Note: All RAG operations require projectRoot context.
 *
 * @property ragRepository RAG data storage repository
 * @property documentationRepository Documentation sources repository
 * @property ragSearchService RAG similarity search service (nullable)
 * @property embeddingsService Embedding generation service (nullable)
 * @property fileAnalyzerService File analysis and chunking service (nullable)
 * @property projectRoot Project root path (nullable - required for RAG operations)
 * @property configService Configuration service (for RAG settings)
 * @property embeddingProviderFactory Factory function to create embedding providers
 */
class RagRouter(
    private val ragRepository: RagRepository,
    private val documentationRepository: DocumentationRepository,
    private val ragSearchService: RagSearchService?,
    private val embeddingsService: EmbeddingsService?,
    private val fileAnalyzerService: FileAnalyzerService?,
    private val projectRoot: java.nio.file.Path?,
    private val configService: ConfigService,
    private val embeddingProviderFactory: (String) -> EmbeddingProvider
) : Router {

    private val embeddingsMutex = Mutex()

    override suspend fun initialize() {
        if (projectRoot != null) {
            logger.info { "[RagRouter] Initialized with projectRoot=$projectRoot" }
        } else {
            logger.warn { "[RagRouter] Initialized without projectRoot - RAG operations will not be available" }
        }
    }

    override suspend fun shutdown() {
        logger.info { "[RagRouter] Shutting down" }
    }

    // ===== RAG Query Operations =====

    /**
     * Search RAG for relevant chunks.
     *
     * @param query Search query
     * @param model Embedding model (must match indexed embeddings)
     * @param topK Number of results to return
     * @param contentType Filter by content type (optional)
     * @return List of search results with similarity scores
     * @throws IllegalStateException if projectRoot not available
     */
    suspend fun searchRag(
        query: String,
        model: String = "ollama/nomic-embed-text",
        topK: Int = 5,
        contentType: RagContentType? = null
    ): List<RagSearchResultDto> {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        logger.info { "[RagRouter] Searching RAG: project=$projectRoot, query='${query.take(50)}...'" }

        val searchService = ragSearchService
            ?: throw IllegalStateException("RAG search service not available")

        val config = RagSearchConfig(
            similarityThreshold = configService.getTyped(ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD),
            topK = topK,
            contentType = contentType,
            includeContextChunks = configService.getTyped(ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)
        )

        val results = searchService.search(
            projectRoot = projectRoot.toString(),
            query = query,
            model = model,
            config = config
        )

        return results.map { result ->
            RagSearchResultDto(
                chunkId = result.chunkId,
                fileId = result.fileId,
                filePath = result.filePath,
                content = result.content,
                startLine = result.startLine,
                endLine = result.endLine,
                similarity = result.similarity,
                contentType = result.contentType.name
            )
        }
    }

    // ===== RAG Index Management =====

    /**
     * Get RAG indexed files for current project.
     * Includes both project files AND documentation files associated with this project.
     *
     * @return List of indexed files with chunks/embeddings count
     * @throws IllegalStateException if projectRoot not available
     */
    suspend fun getRagIndexedFiles(): List<pl.jclab.refio.ui.components.rag.RagIndexedFileDto> {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        logger.debug { "[RagRouter] Getting RAG indexed files for project=$projectRoot" }

        return try {
            // 1. Get project files
            val projectFiles = ragRepository.getIndexedFiles(projectRoot.toString())
            logger.debug { "[RagRouter] Found ${projectFiles.size} project files" }

            // 2. Get documentation sources for this project
            val docSources = documentationRepository.getDocSources(projectRoot.toString())
            logger.debug { "[RagRouter] Found ${docSources.size} documentation sources" }

            // 3. Get documentation files
            val docFiles = if (docSources.isNotEmpty()) {
                val sourceUrls = docSources.map { it.url }
                ragRepository.getIndexedFilesBySourceUrls(sourceUrls)
            } else {
                emptyList()
            }
            logger.debug { "[RagRouter] Found ${docFiles.size} documentation files" }

            // 4. Combine and convert to DTOs
            val allFiles = projectFiles + docFiles

            allFiles.map { file ->
                val chunks = ragRepository.getChunksForFile(file.id)
                val embeddings = ragRepository.getEmbeddingsForFile(file.id)

                pl.jclab.refio.ui.components.rag.RagIndexedFileDto(
                    id = file.id,
                    filePath = file.filePath,
                    chunksCount = chunks.size,
                    embeddingsCount = embeddings.size,
                    fileSize = file.fileSize,
                    contentType = file.contentType,
                    indexedAt = file.indexedAt
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to get RAG indexed files" }
            throw e
        }
    }

    /**
     * Get RAG statistics for current project.
     * Includes both project files AND documentation files associated with this project.
     *
     * @return Statistics (files, chunks, embeddings count)
     * @throws IllegalStateException if projectRoot not available
     */
    suspend fun getRagStatistics(): pl.jclab.refio.ui.components.rag.RagStatisticsDto {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        logger.debug { "[RagRouter] Getting RAG statistics for project=$projectRoot" }

        return try {
            val allFiles = getRagIndexedFiles()

            val filesCount = allFiles.size
            val chunksCount = allFiles.sumOf { it.chunksCount }
            val embeddingsCount = allFiles.sumOf { it.embeddingsCount }

            pl.jclab.refio.ui.components.rag.RagStatisticsDto(
                filesCount = filesCount,
                chunksCount = chunksCount,
                embeddingsCount = embeddingsCount
            )
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to get RAG statistics" }
            throw e
        }
    }

    /**
     * Get RAG chunks for file.
     *
     * @param filePath File path (relative)
     * @return List of chunks for file
     * @throws IllegalStateException if projectRoot not available
     * @throws IllegalArgumentException if file not found in index
     */
    suspend fun getRagChunksForFile(filePath: String): List<pl.jclab.refio.ui.components.rag.RagChunkDto> {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        logger.debug { "[RagRouter] Getting RAG chunks for file: $filePath" }

        return try {
            val file = ragRepository.getIndexedFileByPath(projectRoot.toString(), filePath)
                ?: throw IllegalArgumentException("File not found in index: $filePath")

            val chunks = ragRepository.getChunksForFile(file.id)

            chunks.map { chunk ->
                pl.jclab.refio.ui.components.rag.RagChunkDto(
                    id = chunk.id,
                    chunkIndex = chunk.chunkIndex,
                    content = chunk.content,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to get RAG chunks for file" }
            throw e
        }
    }

    /**
     * Clear RAG index for current project (delete all indexed files, chunks, embeddings).
     *
     * @throws IllegalStateException if projectRoot not available
     */
    suspend fun clearRagIndex() {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        logger.info { "[RagRouter] Clearing RAG index for project=$projectRoot" }

        try {
            ragRepository.deleteIndexedFilesForProject(projectRoot.toString())
            ragRepository.deleteChunksForProject(projectRoot.toString())
            ragRepository.deleteEmbeddingsForProject(projectRoot.toString())
            CodebaseContextProvider.invalidateCache(projectRoot.toString())

            logger.info { "[RagRouter] RAG index cleared for project=$projectRoot" }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to clear RAG index" }
            throw e
        }
    }

    /**
     * Index project files for RAG.
     *
     * @param ignorePatterns Additional ignore patterns (optional)
     * @param onProgress Progress callback
     * @throws IllegalStateException if projectRoot not available
     */
    suspend fun indexProjectForRag(
        ignorePatterns: Set<String> = emptySet(),
        onProgress: ((IndexingProgress) -> Unit)? = null
    ) {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        logger.info { "[RagRouter] Indexing project for RAG: project=$projectRoot" }

        try {
            val indexingService = RagIndexingService(
                ragRepository = ragRepository,
                configService = configService,
                chunkingStrategy = when (ChunkingMode.fromConfig(configService.getTyped(ConfigKeys.RAG_CHUNKING_MODE))) {
                    ChunkingMode.LINE_BASED -> DefaultChunkingStrategy()
                    ChunkingMode.SEMANTIC -> SemanticChunkingStrategy()
                }
            )

            indexingService.indexProject(
                projectRoot = projectRoot,
                additionalIgnorePatterns = ignorePatterns,
                contentType = RagContentType.PROJECT_CODE
            ).collect { progress ->
                onProgress?.invoke(progress)
                logger.debug { "[RagRouter] Indexing progress: ${progress.percentage}% - ${progress.message}" }
            }

            CodebaseContextProvider.invalidateCache(projectRoot.toString())
            logger.info { "[RagRouter] Project indexing completed for project=$projectRoot" }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Project indexing failed" }
            throw e
        }
    }

    /**
     * Generate embeddings for indexed chunks in current project.
     *
     * @param model Embedding model to use (format: "provider/modelId" or "modelId")
     * @param failFastOnUnavailable Fail immediately if embedding service unavailable
     * @param onProgress Progress callback
     * @throws IllegalStateException if projectRoot not available
     */
    suspend fun generateEmbeddings(
        model: String = "ollama/nomic-embed-text",
        failFastOnUnavailable: Boolean = false,
        onProgress: ((EmbeddingProgress) -> Unit)? = null
    ) {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - RAG operations require project context")
        }

        if (embeddingsMutex.isLocked) {
            logger.info { "[RagRouter] Embedding generation already in progress; waiting for current run to finish." }
        }

        embeddingsMutex.withLock {
            logger.info { "[RagRouter] Generating embeddings for project=$projectRoot, model=$model" }

            try {
                val embeddingProvider = embeddingProviderFactory(model)
                val embeddingService = RagEmbeddingService(ragRepository, embeddingProvider, configService)

                // Extract modelId from "provider/modelId" format
                val modelId = if (model.contains("/")) model.split("/", limit = 2)[1] else model

                embeddingService.generateEmbeddings(
                    projectRoot = projectRoot.toString(),
                    model = modelId,  // Pass only modelId, not "provider/modelId"
                    failFastOnUnavailable = failFastOnUnavailable
                ).collect { progress ->
                    onProgress?.invoke(progress)
                    logger.debug { "[RagRouter] Embedding progress: ${progress.progressPercent}% - ${progress.statusMessage}" }
                }

                CodebaseContextProvider.invalidateCache(projectRoot.toString())
                logger.info { "[RagRouter] Embeddings generated for project=$projectRoot" }
            } catch (e: Exception) {
                logger.error(e) { "[RagRouter] Embedding generation failed" }
                throw e
            }
        }
    }

    // ===== Documentation Indexing =====

    /**
     * Get all documentation sources for current project.
     *
     * @return List of documentation sources with indexing status
     * @throws IllegalStateException if projectRoot not available
     */
    fun getDocumentationSources(): List<DocumentationSource> {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - documentation is per-project")
        }

        logger.debug { "[RagRouter] Getting documentation sources for project=$projectRoot" }

        return try {
            documentationRepository.getDocSources(projectRoot.toString())
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to get documentation sources" }
            throw e
        }
    }

    /**
     * Add documentation source for current project (create but don't index yet).
     *
     * @param url Documentation base URL
     * @param crawlDepth Maximum crawl depth (default: 2)
     * @return Created documentation source
     * @throws IllegalStateException if projectRoot not available
     */
    fun addDocumentationSource(
        url: String,
        crawlDepth: Int = 2
    ): DocumentationSource {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - documentation is per-project")
        }

        logger.info { "[RagRouter] Adding documentation source: url=$url, depth=$crawlDepth, projectRoot=$projectRoot" }

        return try {
            documentationRepository.createOrGetDocSource(projectRoot.toString(), url, crawlDepth)
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to add documentation source" }
            throw e
        }
    }

    /**
     * Add local documentation file for current project (create but don't index yet).
     *
     * @param filePath Absolute file path
     * @return Created documentation source
     * @throws IllegalStateException if projectRoot not available
     */
    fun addDocumentationFile(filePath: String): DocumentationSource {
        if (projectRoot == null) {
            throw IllegalStateException("Project root not available - documentation is per-project")
        }

        logger.info { "[RagRouter] Adding documentation file: path=$filePath, projectRoot=$projectRoot" }

        return try {
            documentationRepository.createOrGetFileSource(projectRoot.toString(), filePath)
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to add documentation file" }
            throw e
        }
    }

    /**
     * Delete documentation source and all indexed pages.
     *
     * @param docId Documentation source ID
     */
    fun deleteDocumentationSource(docId: Int) {
        logger.info { "[RagRouter] Deleting documentation source: $docId" }

        try {
            documentationRepository.deleteDocSource(docId)
            logger.info { "[RagRouter] Deleted documentation source: $docId" }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to delete documentation source" }
            throw e
        }
    }

    /**
     * Index documentation from a documentation source.
     *
     * @param docId Documentation source ID
     * @return Flow of indexing progress events
     * @throws IllegalArgumentException if documentation source not found
     */
    fun indexDocumentation(docId: Int): Flow<DocIndexingProgress> {
        logger.info { "[RagRouter] Starting documentation indexing: docId=$docId" }

        return try {
            val docSource = documentationRepository.getDocSource(docId)
                ?: throw IllegalArgumentException("Documentation source not found: $docId")

            val indexingService = DocumentationIndexingService(
                documentationRepository = documentationRepository,
                ragRepository = ragRepository
            )

            if (docSource.sourceType == DocSourceType.FILE) {
                val path = docSource.filePath
                    ?: throw IllegalArgumentException("Documentation file path missing for source: $docId")
                indexingService.indexLocalFile(
                    projectRoot = docSource.projectRoot,
                    docId = docSource.id,
                    filePath = path
                )
            } else {
                indexingService.indexDocumentation(
                    projectRoot = docSource.projectRoot,
                    url = docSource.url,
                    crawlDepth = docSource.crawlDepth
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to start documentation indexing" }
            throw e
        }
    }

    /**
     * Delete documentation index for a documentation source.
     * Deletes all indexed files but keeps the source definition.
     *
     * @param docId Documentation source ID
     */
    fun deleteDocumentationIndex(docId: Int) {
        logger.info { "[RagRouter] Deleting documentation index: $docId" }

        try {
            val docSource = documentationRepository.getDocSource(docId)
                ?: throw IllegalArgumentException("Documentation source not found: $docId")

            val sourceKey = docSourceKey(docSource)
            ragRepository.deleteIndexedFilesBySourceUrl(sourceKey)

            // Reset counters
            documentationRepository.updateDocSource(
                docId = docId,
                status = DocIndexingStatus.PENDING,
                pagesIndexed = 0,
                totalPages = 0
            )

            logger.info { "[RagRouter] Deleted documentation index for: ${docSource.url}" }
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to delete documentation index" }
            throw e
        }
    }

    /**
     * Get documentation statistics for a project.
     *
     * @param taskId Task ID (used to determine project context)
     * @return Documentation statistics
     */
    fun getDocumentationStatistics(taskId: String): DocStatistics {
        logger.debug { "[RagRouter] Getting documentation statistics for task=$taskId" }

        return try {
            documentationRepository.getDocStatistics(taskId)
        } catch (e: Exception) {
            logger.error(e) { "[RagRouter] Failed to get documentation statistics" }
            throw e
        }
    }

    private fun docSourceKey(source: DocumentationSource): String {
        return if (source.sourceType == DocSourceType.FILE) {
            source.filePath ?: source.url
        } else {
            source.url
        }
    }
}
