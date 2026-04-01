package pl.jclab.refio.core.api.routers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.DocIndexingStatus
import pl.jclab.refio.core.db.DocSourceType
import pl.jclab.refio.core.db.DocumentationSource
import pl.jclab.refio.core.db.Embedding
import pl.jclab.refio.core.db.IndexChunk
import pl.jclab.refio.core.db.IndexFile
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.RagSearchService
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import java.nio.file.Paths

class RagRouterTest {

    private lateinit var ragRepository: RagRepository
    private lateinit var ragSearchService: RagSearchService
    private lateinit var embeddingsService: EmbeddingsService
    private lateinit var documentationRepository: DocumentationRepository
    private lateinit var fileAnalyzerService: pl.jclab.refio.core.services.analysis.FileAnalyzerService
    private lateinit var configService: ConfigService
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var ragRouter: RagRouter

    @BeforeEach
    fun setup() {
        ragRepository = mockk()
        documentationRepository = mockk()
        ragSearchService = mockk()
        embeddingsService = mockk()
        fileAnalyzerService = mockk()
        configService = mockk()
        embeddingProvider = mockk()
        every { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD) } returns 0.7f
        every { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS) } returns true
        every { ragRepository.deleteIndexedFilesForProject(any()) } returns 0
        every { ragRepository.deleteChunksForProject(any()) } just Runs
        every { ragRepository.deleteEmbeddingsForProject(any()) } just Runs
        ragRouter = RagRouter(
            ragRepository = ragRepository,
            documentationRepository = documentationRepository,
            ragSearchService = ragSearchService,
            embeddingsService = embeddingsService,
            fileAnalyzerService = fileAnalyzerService,
            projectRoot = Paths.get("D:/test/project"),
            configService = configService,
            embeddingProviderFactory = { embeddingProvider }
        )
    }

    @Test
    fun `getRagIndexedFiles returns all indexed files`() = runBlocking {
        // Given
        val projectRoot = Paths.get("D:/test/project").toString()
        val projectFile = IndexFile(
            id = 1,
            projectRoot = projectRoot,
            filePath = "src/App.kt",
            fileHash = "hash-1",
            checksum = null,
            fileSize = 10,
            mimeType = "text/plain",
            metadata = null,
            contentType = RagContentType.PROJECT_CODE,
            sourceUrl = null,
            indexedAt = 1L,
            lastModified = 1L
        )
        val docSource = DocumentationSource(
            id = 10,
            projectRoot = projectRoot,
            url = "https://docs.example",
            sourceType = DocSourceType.URL,
            filePath = null,
            title = null,
            description = null,
            status = DocIndexingStatus.INDEXED,
            errorMessage = null,
            crawlDepth = 2,
            pagesIndexed = 1,
            totalPages = 1,
            lastIndexed = null,
            createdAt = 1L,
            updatedAt = 1L
        )
        val docFile = IndexFile(
            id = 2,
            projectRoot = projectRoot,
            filePath = "https://docs.example/page",
            fileHash = "hash-2",
            checksum = null,
            fileSize = 12,
            mimeType = "text/html",
            metadata = null,
            contentType = RagContentType.DOCUMENTATION,
            sourceUrl = docSource.url,
            indexedAt = 2L,
            lastModified = 2L
        )
        every { ragRepository.getIndexedFiles(projectRoot) } returns listOf(projectFile)
        every { documentationRepository.getDocSources(projectRoot) } returns listOf(docSource)
        every { ragRepository.getIndexedFilesBySourceUrls(listOf(docSource.url)) } returns listOf(docFile)
        every { ragRepository.getChunksForFile(1) } returns listOf(
            IndexChunk(
                id = 101,
                fileId = 1,
                chunkIndex = 0,
                content = "content",
                contentHash = "hash",
                metadata = null,
                startLine = 1,
                endLine = 2,
                startChar = null,
                endChar = null,
                createdAt = 1L
            )
        )
        every { ragRepository.getChunksForFile(2) } returns listOf(
            IndexChunk(
                id = 102,
                fileId = 2,
                chunkIndex = 0,
                content = "doc",
                contentHash = "hash",
                metadata = null,
                startLine = 1,
                endLine = 2,
                startChar = null,
                endChar = null,
                createdAt = 1L
            )
        )
        every { ragRepository.getEmbeddingsForFile(1) } returns listOf(
            Embedding(
                id = 201,
                chunkId = 101,
                model = "model",
                vector = byteArrayOf(1, 2),
                dimensions = 2,
                createdAt = 1L
            )
        )
        every { ragRepository.getEmbeddingsForFile(2) } returns listOf(
            Embedding(
                id = 202,
                chunkId = 102,
                model = "model",
                vector = byteArrayOf(3, 4),
                dimensions = 2,
                createdAt = 1L
            )
        )

        // When
        val files = ragRouter.getRagIndexedFiles()

        // Then
        assertEquals(2, files.size)
        assertEquals(1, files.first { it.id == 1 }.chunksCount)
        assertEquals(1, files.first { it.id == 2 }.embeddingsCount)
    }

    @Test
    fun `getRagStatistics returns index statistics`() = runBlocking {
        // Given
        val projectRoot = Paths.get("D:/test/project").toString()
        every { ragRepository.getIndexedFiles(projectRoot) } returns listOf(
            IndexFile(
                id = 1,
                projectRoot = projectRoot,
                filePath = "src/App.kt",
                fileHash = "hash-1",
                checksum = null,
                fileSize = 10,
                mimeType = "text/plain",
                metadata = null,
                contentType = RagContentType.PROJECT_CODE,
                sourceUrl = null,
                indexedAt = 1L,
                lastModified = 1L
            )
        )
        every { documentationRepository.getDocSources(projectRoot) } returns emptyList()
        every { ragRepository.getIndexedFilesBySourceUrls(any()) } returns emptyList()
        every { ragRepository.getChunksForFile(1) } returns listOf(
            IndexChunk(
                id = 101,
                fileId = 1,
                chunkIndex = 0,
                content = "content",
                contentHash = "hash",
                metadata = null,
                startLine = 1,
                endLine = 2,
                startChar = null,
                endChar = null,
                createdAt = 1L
            )
        )
        every { ragRepository.getEmbeddingsForFile(1) } returns listOf(
            Embedding(
                id = 201,
                chunkId = 101,
                model = "model",
                vector = byteArrayOf(1, 2),
                dimensions = 2,
                createdAt = 1L
            )
        )

        // When
        val stats = ragRouter.getRagStatistics()

        // Then
        assertEquals(1, stats.filesCount)
        assertEquals(1, stats.chunksCount)
        assertEquals(1, stats.embeddingsCount)
    }

    @Test
    fun `clearRagIndex removes all RAG data`() = runBlocking {
        // When
        ragRouter.clearRagIndex()

        // Then
        val projectRoot = Paths.get("D:/test/project").toString()
        verify { ragRepository.deleteIndexedFilesForProject(projectRoot) }
        verify { ragRepository.deleteChunksForProject(projectRoot) }
        verify { ragRepository.deleteEmbeddingsForProject(projectRoot) }
    }
}
