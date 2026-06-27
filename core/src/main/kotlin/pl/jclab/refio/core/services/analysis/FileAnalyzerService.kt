package pl.jclab.refio.core.services.analysis

import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ChunkingStrategy
import pl.jclab.refio.core.services.CodeChunk
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.text.Charsets
import kotlin.math.min

class FileAnalyzerService(
    private val configService: ConfigService,
    private val ragRepository: RagRepository,
    private val chunkingStrategy: ChunkingStrategy,
    private val embeddingsService: EmbeddingsService,
    private val analyzers: List<LanguageAnalyzer>,
    private val scope: CoroutineScope
) {
    private val logger = dualLogger("FileAnalyzerService")
    private val cache = ConcurrentHashMap<String, CachedAnalysis>()
    private val digest = MessageDigest.getInstance("SHA-256")
    private val semaphore = Semaphore(configService.getTyped<Int>(ConfigKeys.RAG_MAX_CONCURRENT_JOBS))
    private val genericAnalyzer = GenericLanguageAnalyzer()

    suspend fun analyze(
        projectRoot: Path,
        filePath: Path,
        autoIndex: Boolean = true
    ): FileAnalysis {

        logger.debug { "Analyze file $filePath in root: $projectRoot" }

        val absoluteProject = projectRoot.toAbsolutePath().normalize()
        val absoluteFile = resolveFile(absoluteProject, filePath)
        val cacheKey = cacheKey(absoluteProject, absoluteFile)
        val lastModified = Files.getLastModifiedTime(absoluteFile).toMillis()
        val ttl = configService.getTyped<Long>(ConfigKeys.RAG_CACHE_TTL_MS)

        cache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.cachedAt < ttl && it.analysis.lastModified == lastModified) {
                return it.analysis
            }
        }

        val analysis = analyzeInternal(absoluteProject, absoluteFile, lastModified)
        cache[cacheKey] = CachedAnalysis(analysis, System.currentTimeMillis())

        val shouldIndex = autoIndex &&
                configService.getTyped<Boolean>(ConfigKeys.RAG_ENABLED) &&
                configService.getTyped<Boolean>(ConfigKeys.RAG_AUTO_INDEX_ON_CONTEXT) &&
                shouldIndexFile(analysis, absoluteProject)

        if (shouldIndex) {
            scheduleIndexing(analysis, absoluteProject)
        }

        return analysis
    }

    suspend fun analyzeOnly(projectRoot: Path, filePath: Path): FileAnalysis {
        return analyze(projectRoot, filePath, autoIndex = false)
    }

    suspend fun reanalyze(projectRoot: Path, filePath: Path): FileAnalysis {
        val absoluteProject = projectRoot.toAbsolutePath().normalize()
        val absoluteFile = resolveFile(absoluteProject, filePath)
        val lastModified = Files.getLastModifiedTime(absoluteFile).toMillis()
        val analysis = analyzeInternal(absoluteProject, absoluteFile, lastModified)
        cache[cacheKey(absoluteProject, absoluteFile)] = CachedAnalysis(analysis, System.currentTimeMillis())
        scheduleIndexing(analysis, absoluteProject)
        return analysis
    }

    fun getCachedAnalysis(projectRoot: Path, filePath: Path): FileAnalysis? {
        val absoluteProject = projectRoot.toAbsolutePath().normalize()
        val absoluteFile = resolveFile(absoluteProject, filePath)
        return cache[cacheKey(absoluteProject, absoluteFile)]?.analysis
    }

    suspend fun analyzeBatch(
        projectRoot: Path,
        filePaths: List<Path>,
        autoIndex: Boolean = true
    ): List<FileAnalysis> {
        return filePaths.mapNotNull { path ->
            try {
                analyze(projectRoot, path, autoIndex)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to analyze file ${path.normalize()}" }
                null
            }
        }
    }

    suspend fun removeFromIndex(projectRoot: Path, filePath: Path) {
        val absoluteProject = projectRoot.toAbsolutePath().normalize()
        val absoluteFile = resolveFile(absoluteProject, filePath)
        val relativePath = safeRelativePath(absoluteProject, absoluteFile)
        val existing = ragRepository.getIndexedFileByPath(absoluteProject.toString(), relativePath)
        existing?.let {
            ragRepository.deleteChunksForFile(it.id)
            ragRepository.deleteIndexedFile(it.id)
        }
        cache.remove(cacheKey(absoluteProject, absoluteFile))
    }

    fun indexToRAGAsync(analysis: FileAnalysis) {
        scheduleIndexing(analysis, Path.of(analysis.projectRoot))
    }

    private suspend fun analyzeInternal(
        projectRoot: Path,
        file: Path,
        lastModified: Long
    ): FileAnalysis {
        if (!file.exists() || file.isDirectory()) {
            throw IllegalArgumentException("Cannot analyze directory or missing file: $file")
        }

        val fileSize = Files.size(file)
        val content = withContext(Dispatchers.IO) { file.readText() }
        val analyzer = analyzers.firstOrNull { it.matches(file) } ?: genericAnalyzer
        val elements = analyzer.analyze(file, content)
        val contentHash = sha256(content)
        val lineCount = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        val relativePath = safeRelativePath(projectRoot, file)
        val existing = ragRepository.getIndexedFileByPath(projectRoot.toString(), relativePath)

        return FileAnalysis(
            projectRoot = projectRoot.toString(),
            filePath = relativePath,
            language = analyzer.languageId,
            fileSize = fileSize,
            lastModified = lastModified,
            codeElements = elements,
            contentHash = contentHash,
            fileId = existing?.id,
            lineCount = lineCount
        )
    }

    private fun resolveFile(projectRoot: Path, filePath: Path): Path {
        return if (filePath.isAbsolute) filePath.normalize() else projectRoot.resolve(filePath).normalize()
    }

    private fun safeRelativePath(projectRoot: Path, file: Path): String {
        return try {
            file.relativeTo(projectRoot).toString().ifBlank { file.fileName.toString() }
        } catch (_: Exception) {
            file.fileName.toString()
        }
    }

    private fun shouldIndexFile(analysis: FileAnalysis, projectRoot: Path): Boolean {
        if (analysis.fileSize > configService.getTyped(ConfigKeys.RAG_MAX_FILE_SIZE_MB) * 1024L * 1024L) return false
        return !resolveIgnoreMatcher(projectRoot).isIgnored(analysis.filePath, isDirectory = false)
    }

    private fun scheduleIndexing(analysis: FileAnalysis, projectRoot: Path) {
        scope.launch(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    indexFileInternal(analysis, projectRoot)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index ${analysis.filePath}" }
                }
            }
        }
    }

    private suspend fun indexFileInternal(analysis: FileAnalysis, projectRoot: Path) {
        val absoluteFile = projectRoot.resolve(analysis.filePath).normalize()
        if (!absoluteFile.exists()) {
            logger.warn { "Skipping indexing for missing file ${analysis.filePath}" }
            return
        }

        val content = withContext(Dispatchers.IO) { absoluteFile.readText() }
        val chunks = chunkingStrategy.createChunks(
            content = content,
            codeElements = analysis.codeElements,
            language = analysis.language
        )

        if (chunks.isEmpty()) {
            logger.info { "No chunks produced for ${analysis.filePath}, skipping indexing" }
            return
        }

        val embeddings = embeddingsService.generateBatch(chunks.map(CodeChunk::content))

        // docs/0060 Faza 2: previously persisted gson.toJson(analysis.codeElements) into the
        // IndexFiles.metadata column, but that blob was never deserialized by anyone — search
        // ranking uses cosine + keyword only, and every codeElements consumer reads it in-memory
        // from a fresh FileAnalysis, never from this column. Dropping it removes per-index CPU +
        // I/O for zero behavioural change. The nullable column is kept to avoid a schema migration.
        val existing = ragRepository.getIndexedFileByPath(analysis.projectRoot, analysis.filePath)
        val fileId = if (existing != null) {
            ragRepository.updateIndexedFile(
                fileId = existing.id,
                fileHash = analysis.contentHash ?: sha256(content),
                fileSize = analysis.fileSize,
                lastModified = analysis.lastModified,
                metadata = null
            )
            ragRepository.deleteChunksForFile(existing.id)
            existing.id
        } else {
            ragRepository.createIndexedFile(
                projectRoot = analysis.projectRoot,
                filePath = analysis.filePath,
                fileHash = analysis.contentHash ?: sha256(content),
                fileSize = analysis.fileSize,
                mimeType = inferMimeType(analysis.filePath),
                lastModified = analysis.lastModified,
                metadata = null
            )
        }

        chunks.take(configService.getTyped<Int>(ConfigKeys.RAG_MAX_CHUNKS_PER_FILE)).forEachIndexed { index, chunk ->
            val chunkId = ragRepository.createChunk(
                fileId = fileId,
                chunkIndex = index,
                content = chunk.content,
                startLine = chunk.startLine,
                endLine = chunk.endLine,
                metadata = gson.toJson(chunk.metadata)
            )

            val embeddingVector = embeddings.getOrNull(index)
            if (embeddingVector != null && embeddingVector.isNotEmpty()) {
                ragRepository.createEmbedding(
                    chunkId = chunkId,
                    model = embeddingModelId(),
                    vector = serializeVector(embeddingVector),
                    dimensions = embeddingVector.size
                )
            }
        }

        logger.info {
            "Indexed ${analysis.filePath} with ${
                min(
                    chunks.size,
                    configService.getTyped<Int>(ConfigKeys.RAG_MAX_CHUNKS_PER_FILE)
                )
            } chunks"
        }
        cache[cacheKey(Path.of(analysis.projectRoot), absoluteFile)] = CachedAnalysis(
            analysis.copy(fileId = fileId),
            System.currentTimeMillis()
        )
    }

    private fun serializeVector(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun sha256(content: String): String {
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun cacheKey(projectRoot: Path, file: Path): String {
        return "${projectRoot}:${file.toAbsolutePath()}"
    }

    private fun inferMimeType(filePath: String): String? {
        val lower = filePath.lowercase()
        return when {
            lower.endsWith(".kt") -> "text/x-kotlin"
            lower.endsWith(".java") -> "text/x-java"
            lower.endsWith(".py") -> "text/x-python"
            lower.endsWith(".ts") || lower.endsWith(".tsx") -> "text/x-typescript"
            lower.endsWith(".js") || lower.endsWith(".jsx") -> "text/javascript"
            else -> null
        }
    }

    private data class CachedAnalysis(
        val analysis: FileAnalysis,
        val cachedAt: Long
    )

    private fun resolveIgnoreMatcher(projectRoot: Path): AiIgnoreMatcher {
        val patterns = configService.getTyped<List<String>>(ConfigKeys.RAG_IGNORED_DIRECTORIES).toSet()
        return try {
            AiIgnoreMatcher.load(projectRoot) ?: AiIgnoreMatcher.fromPatterns(patterns)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${AiIgnoreMatcher.FILE_NAME}; using default ignore patterns" }
            AiIgnoreMatcher.fromPatterns(patterns)
        }
    }

    private fun embeddingModelId(): String {
        val configured = configService.getEmbeddingModel()
        return if (configured.contains("/")) {
            configured.split("/", limit = 2)[1]
        } else {
            configured
        }
    }
}
