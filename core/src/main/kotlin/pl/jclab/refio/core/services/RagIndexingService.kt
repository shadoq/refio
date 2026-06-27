package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.repositories.ChunkInsert
import pl.jclab.refio.core.db.repositories.IndexingProgressRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.analysis.CodeElements
import pl.jclab.refio.core.services.analysis.CppLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.CssLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.GoLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.HtmlLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.JavaLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.KotlinLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.LanguageAnalyzer
import pl.jclab.refio.core.services.analysis.PythonLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.RustLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.TypeScriptLanguageAnalyzer
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.io.path.exists
import kotlin.io.path.extension

private val logger = dualLogger("RagIndexingService")

class RagIndexingService(
    private val ragRepository: RagRepository,
    private val configService: ConfigService,
    private val chunkingStrategy: ChunkingStrategy = DefaultChunkingStrategy(),
    private val semanticChunkingStrategy: ChunkingStrategy = SemanticChunkingStrategy(),
    private val progressRepository: IndexingProgressRepository = IndexingProgressRepository(),
    private val analyzers: List<LanguageAnalyzer> = listOf(
        KotlinLanguageAnalyzer(),
        JavaLanguageAnalyzer(),
        PythonLanguageAnalyzer(),
        TypeScriptLanguageAnalyzer(),
        GoLanguageAnalyzer(),
        RustLanguageAnalyzer(),
        HtmlLanguageAnalyzer(),
        CppLanguageAnalyzer(),
        CssLanguageAnalyzer()
    )
) {

    companion object {
        private const val CHUNK_SIZE_TOKENS = 1024
        private const val CHUNK_OVERLAP_TOKENS = 256
        private const val BATCH_DELAY_MS = 100L

        private val CODE_FILE_EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
            "c", "cpp", "cc", "cxx", "h", "hpp", "hh",
            "go", "rs", "cs", "rb", "swift", "scala", "groovy"
        )

        private val TEXT_FILE_EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "cpp", "h", "hpp",
            "cs", "php", "rb", "swift", "m", "mm", "scala", "groovy", "clj", "cljs",
            "md", "txt", "yaml", "yml", "json", "xml", "html", "htm", "css", "scss", "sass",
            "gradle", "properties", "conf", "config", "env", "sh", "bash", "zsh",
            "sql", "graphql", "proto", "thrift", "dockerfile", "makefile", "cmake", "pdf"
        )
    }

    fun indexProject(
        projectRoot: Path,
        additionalIgnorePatterns: Set<String> = emptySet(),
        contentType: RagContentType = RagContentType.PROJECT_CODE
    ): Flow<IndexingProgress> = flow {
        val absoluteRoot = projectRoot.toAbsolutePath().normalize()
        val projectKey = absoluteRoot.toString()
        val ignoreMatcher = resolveIgnoreMatcher(absoluteRoot, additionalIgnorePatterns)
        val maxFileSize = configService.getTyped(ConfigKeys.RAG_MAX_FILE_SIZE_MB) * 1024L * 1024L

        logger.info { "Starting RAG indexing for $projectKey" }

        emit(
            IndexingProgress(
                status = IndexingStatus.SCANNING,
                totalFiles = 0,
                processedFiles = 0,
                message = "Scanning project files..."
            )
        )

        val scannedFiles = scanProjectFiles(absoluteRoot, ignoreMatcher, maxFileSize)

        emit(
            IndexingProgress(
                status = IndexingStatus.SCANNING,
                totalFiles = scannedFiles.size,
                processedFiles = 0,
                message = "Found ${scannedFiles.size} files to evaluate"
            )
        )

        val existingFiles = ragRepository
            .getIndexedFiles(projectKey, contentType)
            .associateBy { it.filePath }

        val classification = classifyFiles(scannedFiles, existingFiles)
        deleteRemovedFiles(existingFiles.values, scannedFiles.map { it.relativePath }.toSet())

        val filesToIndex = classification.newFiles + classification.modifiedFiles
        var filesProcessed = 0
        var totalFiles = filesToIndex.size

        val summaryMessage = buildString {
            append("Indexing ${filesToIndex.size} files")
            append(" (${classification.newFiles.size} new, ${classification.modifiedFiles.size} modified)")
        }

        progressRepository.markStarted(projectKey, totalFiles, summaryMessage)

        emit(
            IndexingProgress(
                status = IndexingStatus.INDEXING,
                totalFiles = totalFiles,
                processedFiles = 0,
                message = summaryMessage
            )
        )

        if (filesToIndex.isEmpty()) {
            progressRepository.markCompleted(projectKey, 0, "RAG index already up-to-date")
            emit(
                IndexingProgress(
                    status = IndexingStatus.COMPLETED,
                    totalFiles = 0,
                    processedFiles = 0,
                    message = "RAG index already up-to-date"
                )
            )
            return@flow
        }

        try {
            val batchSize = configService.getTyped<Int>(ConfigKeys.RAG_INDEX_BATCH_SIZE).coerceAtLeast(1)

            filesToIndex.chunked(batchSize).forEach { batch ->
                coroutineContext.ensureActive()
                batch.forEach { file ->
                    coroutineContext.ensureActive()
                    // RAG is secondary: yield the SQLite writer-lock to any active agent
                    // turn before touching the DB, so background indexing can't stall tool
                    // subtask-status writes.
                    if (GlobalMetrics.isAgentTurnActive()) {
                        logger.info { "[RAG_PAUSE] Agent turn active — pausing indexing before ${file.relativePath}" }
                        GlobalMetrics.awaitAgentTurnIdle()
                    }
                    try {
                        indexSingleFile(absoluteRoot, file, existingFiles[file.relativePath], contentType)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to index ${file.relativePath}" }
                    }

                    filesProcessed++
                    val message = "Indexing ${file.relativePath} ($filesProcessed/$totalFiles)"

                    progressRepository.updateProgress(
                        projectRoot = projectKey,
                        indexedFiles = filesProcessed,
                        message = message,
                        lastIndexedFile = file.relativePath
                    )

                    emit(
                        IndexingProgress(
                            status = IndexingStatus.INDEXING,
                            totalFiles = totalFiles,
                            processedFiles = filesProcessed,
                            currentFile = file.relativePath,
                            message = message
                        )
                    )
                }

                delay(BATCH_DELAY_MS)
            }

            progressRepository.markCompleted(projectKey, filesProcessed, "Indexed $filesProcessed files")
            emit(
                IndexingProgress(
                    status = IndexingStatus.COMPLETED,
                    totalFiles = totalFiles,
                    processedFiles = filesProcessed,
                    message = "Indexed $filesProcessed files"
                )
            )
        } catch (cancelled: CancellationException) {
            logger.warn { "Indexing cancelled for $projectKey" }
            progressRepository.markCancelled(projectKey, filesProcessed, "Indexing cancelled by user")
            emit(
                IndexingProgress(
                    status = IndexingStatus.CANCELLED,
                    totalFiles = totalFiles,
                    processedFiles = filesProcessed,
                    message = "Indexing cancelled"
                )
            )
            throw cancelled
        } catch (e: Exception) {
            logger.error(e) { "Indexing failed for $projectKey" }
            val message = "Indexing failed: ${e.message ?: "Unknown error"}"
            progressRepository.markFailed(projectKey, filesProcessed, message)
            emit(
                IndexingProgress(
                    status = IndexingStatus.FAILED,
                    totalFiles = totalFiles,
                    processedFiles = filesProcessed,
                    message = message
                )
            )
            throw e
        }
    }.flowOn(RagDispatchers.background)

    private fun resolveIgnoredDirectories(additionalIgnorePatterns: Set<String>): Set<String> {
        val configured = configService.getTyped<List<String>>(ConfigKeys.RAG_IGNORED_DIRECTORIES).toSet()
        return (configured + additionalIgnorePatterns)
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun scanProjectFiles(
        projectRoot: Path,
        ignoreMatcher: AiIgnoreMatcher,
        maxFileSize: Long
    ): List<ScannedFile> {
        return try {
            val files = mutableListOf<ScannedFile>()
            Files.walk(projectRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { path -> allowPath(projectRoot, path, ignoreMatcher, maxFileSize) }
                    .forEach { path ->
                        files += ScannedFile(
                            relativePath = projectRoot.relativize(path).toString(),
                            absolutePath = path,
                            fileSize = Files.size(path),
                            lastModified = Files.getLastModifiedTime(path).toMillis()
                        )
                    }
            }
            files
        } catch (e: Exception) {
            logger.error(e) { "Failed to scan $projectRoot" }
            emptyList()
        }
    }

    private fun allowPath(
        projectRoot: Path,
        path: Path,
        ignoreMatcher: AiIgnoreMatcher,
        maxFileSize: Long
    ): Boolean {
        if (!Files.isReadable(path)) return false

        val relativePath = projectRoot.relativize(path).toString()
        if (ignoreMatcher.isIgnored(relativePath, isDirectory = false)) {
            return false
        }

        return try {
            val fileSize = Files.size(path)
            fileSize <= maxFileSize && isTextFile(path)
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveIgnoreMatcher(
        projectRoot: Path,
        additionalIgnorePatterns: Set<String>
    ): AiIgnoreMatcher {
        return try {
            AiIgnoreMatcher.load(projectRoot)
                ?: AiIgnoreMatcher.fromPatterns(resolveIgnoredDirectories(additionalIgnorePatterns))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${AiIgnoreMatcher.FILE_NAME}; using default ignore patterns" }
            AiIgnoreMatcher.fromPatterns(resolveIgnoredDirectories(additionalIgnorePatterns))
        }
    }

    private fun classifyFiles(
        scannedFiles: List<ScannedFile>,
        existingFiles: Map<String, pl.jclab.refio.core.db.IndexFile>
    ): ClassificationResult {
        val newFiles = mutableListOf<ProjectFile>()
        val modifiedFiles = mutableListOf<ProjectFile>()
        val unchangedFiles = mutableListOf<ProjectFile>()

        scannedFiles.forEach { scanned ->
            try {
                val checksum = computeChecksum(scanned.absolutePath)
                val projectFile = ProjectFile(
                    relativePath = scanned.relativePath,
                    absolutePath = scanned.absolutePath,
                    fileSize = scanned.fileSize,
                    lastModified = scanned.lastModified,
                    checksum = checksum
                )

                val existing = existingFiles[scanned.relativePath]
                when {
                    existing == null -> newFiles.add(projectFile)
                    existing.checksum == null || existing.checksum != checksum -> modifiedFiles.add(projectFile)
                    else -> unchangedFiles.add(projectFile)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to classify ${scanned.relativePath}" }
            }
        }

        logger.info {
            "File classification: ${newFiles.size} new, ${modifiedFiles.size} modified, ${unchangedFiles.size} unchanged"
        }

        return ClassificationResult(newFiles, modifiedFiles, unchangedFiles)
    }

    private fun deleteRemovedFiles(
        indexedFiles: Collection<pl.jclab.refio.core.db.IndexFile>,
        currentPaths: Set<String>
    ) {
        indexedFiles
            .filter { it.filePath !in currentPaths }
            .forEach { removed ->
                ragRepository.deleteIndexedFile(removed.id)
                logger.info { "Removed stale RAG entry ${removed.filePath}" }
            }
    }

    private fun indexSingleFile(
        projectRoot: Path,
        projectFile: ProjectFile,
        existing: pl.jclab.refio.core.db.IndexFile?,
        contentType: RagContentType
    ) {
        if (!projectFile.absolutePath.exists()) {
            logger.warn { "Skipping missing file ${projectFile.relativePath}" }
            return
        }

        val content = readFileContent(projectFile.absolutePath)
        val chunkMode = ChunkingMode.fromConfig(configService.getTyped<String>(ConfigKeys.RAG_CHUNKING_MODE))
        val language = projectFile.absolutePath.extension.lowercase().ifBlank { null }
        val codeElements = if (chunkMode == ChunkingMode.SEMANTIC) {
            analyzeContent(projectFile.absolutePath, content)
        } else {
            CodeElements()
        }
        val effectiveStrategy = if (language != null && language in CODE_FILE_EXTENSIONS) {
            semanticChunkingStrategy
        } else {
            chunkingStrategy
        }
        val chunks = effectiveStrategy.createChunks(
            content = content,
            codeElements = codeElements,
            language = language,
            maxChunkChars = CHUNK_SIZE_TOKENS
        )
        val maxChunks = configService.getTyped<Int>(ConfigKeys.RAG_MAX_CHUNKS_PER_FILE)
        val mimeType = try {
            Files.probeContentType(projectFile.absolutePath)
        } catch (_: Exception) {
            null
        }

        val fileId = if (existing != null) {
            ragRepository.updateIndexedFile(
                fileId = existing.id,
                fileHash = projectFile.checksum,
                checksum = projectFile.checksum,
                fileSize = projectFile.fileSize,
                lastModified = projectFile.lastModified
            )
            ragRepository.deleteChunksForFile(existing.id)
            existing.id
        } else {
            ragRepository.createIndexedFile(
                projectRoot = projectRoot.toString(),
                filePath = projectFile.relativePath,
                fileHash = projectFile.checksum,
                checksum = projectFile.checksum,
                fileSize = projectFile.fileSize,
                mimeType = mimeType,
                lastModified = projectFile.lastModified,
                contentType = contentType
            )
        }

        // Single batched insert (one writer-lock acquisition) instead of one transaction
        // per chunk — see RagRepository.createChunksBatch.
        val chunkInserts = chunks.take(maxChunks).mapIndexed { index, chunk ->
            ChunkInsert(
                fileId = fileId,
                chunkIndex = index,
                content = chunk.content,
                startLine = chunk.startLine,
                endLine = chunk.endLine,
                metadata = chunk.metadata
                    .takeIf { it != ChunkMetadata() }
                    ?.let { metadata -> gson.toJson(metadata) }
            )
        }
        ragRepository.createChunksBatch(chunkInserts)
    }

    private fun analyzeContent(path: Path, content: String): CodeElements {
        val analyzer = analyzers.firstOrNull { analyzer -> analyzer.matches(path) } ?: return CodeElements()

        return runCatching { analyzer.analyze(path, content) }
            .onFailure { error -> logger.warn(error) { "Semantic chunk analysis failed for path=$path" } }
            .getOrElse { CodeElements() }
    }

    private fun isTextFile(path: Path): Boolean {
        val extension = path.extension.lowercase()
        if (extension in TEXT_FILE_EXTENSIONS) {
            return true
        }

        return try {
            val mimeType = Files.probeContentType(path) ?: return false
            mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/xml" ||
                mimeType == "application/yaml"
        } catch (_: Exception) {
            false
        }
    }

    private fun computeChecksum(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readFileContent(path: Path): String {
        val extension = path.extension.lowercase()
        return if (extension == "pdf") {
            extractPdfText(path)
        } else {
            Files.readString(path)
        }
    }

    private fun extractPdfText(path: Path): String {
        PDDocument.load(path.toFile()).use { document ->
            val stripper = PDFTextStripper()
            return stripper.getText(document).trim()
        }
    }
}

data class IndexingProgress(
    val status: IndexingStatus,
    val totalFiles: Int,
    val processedFiles: Int,
    val currentFile: String? = null,
    val message: String
) {
    val percentage: Int
        get() = if (totalFiles == 0) 0 else (processedFiles * 100 / totalFiles)

    val progressPercent: Int
        get() = percentage

    val statusMessage: String
        get() = message
}

enum class IndexingStatus {
    SCANNING,
    INDEXING,
    COMPLETED,
    CANCELLED,
    FAILED
}

private data class ScannedFile(
    val relativePath: String,
    val absolutePath: Path,
    val fileSize: Long,
    val lastModified: Long
)

private data class ProjectFile(
    val relativePath: String,
    val absolutePath: Path,
    val fileSize: Long,
    val lastModified: Long,
    val checksum: String
)

private data class ClassificationResult(
    val newFiles: List<ProjectFile>,
    val modifiedFiles: List<ProjectFile>,
    val unchangedFiles: List<ProjectFile>
)

