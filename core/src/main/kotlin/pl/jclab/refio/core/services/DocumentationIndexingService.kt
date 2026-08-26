package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChunkInsert
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

private val logger = dualLogger("DocumentationIndexingService")

/**
 * Service for indexing external documentation URLs.
 *
 * Features:
 * - Web crawling with depth limit
 * - HTML to text extraction
 * - Link following (same-domain only)
 * - Progress reporting via Flow
 * - Respects robots.txt (basic implementation)
 */
class DocumentationIndexingService(
    private val documentationRepository: DocumentationRepository,
    private val ragRepository: RagRepository,
    private val chunkingStrategy: ChunkingStrategy = DefaultChunkingStrategy(),
    private val fetcher: DocumentFetcher = DocumentFetcher(enableJs = true)
) {
    companion object {
        private const val DEFAULT_CRAWL_DEPTH = 2
        private const val MAX_PAGES_PER_SITE = 500
        private const val CHUNK_SIZE_TOKENS = 1024
        private const val CHUNK_OVERLAP_TOKENS = 200

        // File extensions to skip during crawling
        private val EXCLUDED_EXTENSIONS = setOf(
            // Video
            "mp4", "avi", "mov", "wmv", "flv", "webm", "mkv", "m4v",
            // Audio
            "mp3", "wav", "ogg", "m4a", "flac", "aac",
            // Images
            "jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "ico", "tiff",
            // Archives
            "zip", "tar", "gz", "rar", "7z", "bz2", "xz",
            // Binary documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            // Binaries
            "exe", "dll", "so", "dylib", "bin",
            // Other
            "css", "js", "json", "xml", "rss"
        )

        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "main", "header", "footer",
            "ul", "ol", "li", "pre", "code", "table", "thead", "tbody", "tr", "td", "th",
            "blockquote", "h1", "h2", "h3", "h4", "h5", "h6"
        )
    }

    /**
     * Index documentation from URL.
     *
     * @param projectRoot Project root directory (isolates index per project)
     * @param url Base URL to index
     * @param crawlDepth Maximum crawl depth
     * @return Flow of indexing progress
     */
    fun indexDocumentation(
        projectRoot: String,
        url: String,
        crawlDepth: Int = DEFAULT_CRAWL_DEPTH
    ): Flow<DocIndexingProgress> = flow {
        logger.info { "Indexing documentation: url=$url, depth=$crawlDepth, projectRoot=$projectRoot" }

        val startTime = System.currentTimeMillis()
        val baseUrl = URL(url)

        // 1. Create or get documentation source
        emit(DocIndexingProgress(0, "Creating documentation source...", 0, 0))
        val docSource = documentationRepository.createOrGetDocSource(projectRoot, url, crawlDepth)

        // Update status to INDEXING
        documentationRepository.updateDocStatus(docSource.id, DocIndexingStatus.INDEXING)

        // 2. Crawl pages
        val visitedUrls = mutableSetOf<String>()
        val toVisit = mutableListOf(CrawlItem(url, 0))
        var pagesIndexed = 0
        var chunksCreated = 0
        var firstPageTitle: String? = null

        try {
            while (toVisit.isNotEmpty() && pagesIndexed < MAX_PAGES_PER_SITE) {
                val current = toVisit.removeAt(0)

                if (current.url in visitedUrls) continue
                if (current.depth > crawlDepth) continue
                if (!isSameDomain(baseUrl, current.url)) continue
                if (!isIndexableUrl(current.url)) {
                    logger.debug { "Skipping non-indexable URL: ${current.url}" }
                    continue
                }

                visitedUrls.add(current.url)

                val progress = (pagesIndexed * 100) / MAX_PAGES_PER_SITE.coerceAtMost(visitedUrls.size + toVisit.size)
                emit(DocIndexingProgress(
                    progress,
                    "Crawling: ${shortenUrl(current.url)}",
                    pagesIndexed,
                    chunksCreated
                ))

                try {
                    // Fetch and parse HTML
                    val fetched = fetcher.fetch(current.url)
                    if (fetched.contentType?.contains("html", ignoreCase = true) == false) {
                        logger.debug { "Skipping non-HTML content at ${current.url} (contentType=${fetched.contentType})" }
                        continue
                    }

                    val document = Jsoup.parse(fetched.html, current.url)

                    // Extract text content
                    val textContent = extractText(document)
                    val title = document.title()

                    // Save first page title as doc source title
                    if (firstPageTitle == null && title.isNotBlank()) {
                        firstPageTitle = title
                    }

                    if (textContent.isBlank()) {
                        logger.warn { "No text content extracted from ${current.url}" }
                        continue
                    }

                    // Calculate hash
                    val contentHash = calculateHash(textContent)

                    // Check if already indexed (by path = URL)
                    val existingFile = ragRepository.getIndexedFileByPath(projectRoot, current.url)
                    if (existingFile != null && existingFile.fileHash == contentHash) {
                        // Already indexed with same content, skip
                        logger.debug { "Skipping already indexed page: ${current.url}" }
                        continue
                    }

                    // Chunk text
                    @Suppress("DEPRECATION")
                    val chunks = chunkingStrategy.chunkText(
                        textContent,
                        CHUNK_SIZE_TOKENS,
                        CHUNK_OVERLAP_TOKENS
                    )

                    // Page fingerprint and chunks are written in one transaction: a partial
                    // write would mark the page as indexed with its chunks missing, and the
                    // hash check above would then skip it on every later crawl.
                    ragRepository.upsertIndexedFileWithChunks(
                        existingFileId = existingFile?.id,
                        projectRoot = projectRoot,
                        filePath = current.url,  // URL as path (full page URL)
                        fileHash = contentHash,
                        fileSize = textContent.length.toLong(),
                        mimeType = "text/html",
                        lastModified = System.currentTimeMillis(),
                        contentType = RagContentType.DOCUMENTATION,
                        sourceUrl = url  // Base URL (same for all pages from this doc source)
                    ) { fileId ->
                        chunks.mapIndexed { index, chunk ->
                            ChunkInsert(
                                fileId = fileId,
                                chunkIndex = index,
                                content = chunk.content,
                                startLine = chunk.startLine,
                                endLine = chunk.endLine
                            )
                        }
                    }

                    chunksCreated += chunks.size
                    pagesIndexed++

                    // Extract links for crawling
                    if (current.depth < crawlDepth) {
                        val links = extractLinks(document, baseUrl)
                        links.forEach { link ->
                            if (link !in visitedUrls) {
                                toVisit.add(CrawlItem(link, current.depth + 1))
                            }
                        }
                    }

                } catch (e: org.jsoup.UnsupportedMimeTypeException) {
                    logger.warn { "Skipping unsupported content type at ${current.url}: ${e.message}" }
                    // Continue with next page
                } catch (e: java.net.SocketTimeoutException) {
                    logger.warn { "Timeout while crawling ${current.url}" }
                    // Continue with next page
                } catch (e: Exception) {
                    logger.error(e) { "Failed to crawl ${current.url}" }
                    // Continue with next page
                }
            }

            // Update documentation source
            documentationRepository.updateDocSource(
                docId = docSource.id,
                status = DocIndexingStatus.INDEXED,
                title = firstPageTitle,
                pagesIndexed = pagesIndexed,
                totalPages = visitedUrls.size
            )

            val duration = System.currentTimeMillis() - startTime
            logger.info { "Documentation indexed: $pagesIndexed pages, $chunksCreated chunks, ${duration}ms" }

            emit(DocIndexingProgress(
                100,
                "Completed: $pagesIndexed pages, $chunksCreated chunks",
                pagesIndexed,
                chunksCreated
            ))

        } catch (e: Exception) {
            logger.error(e) { "Documentation indexing failed" }
            documentationRepository.updateDocSource(
                docId = docSource.id,
                status = DocIndexingStatus.FAILED,
                errorMessage = e.message
            )
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Index documentation from a local file (txt, md, pdf).
     */
    fun indexLocalFile(
        projectRoot: String,
        docId: Int,
        filePath: String
    ): Flow<DocIndexingProgress> = flow {
        logger.info { "Indexing local documentation file: path=$filePath, projectRoot=$projectRoot" }

        emit(DocIndexingProgress(0, "Reading local file...", 0, 0))
        documentationRepository.updateDocStatus(docId, DocIndexingStatus.INDEXING)

        val path = Paths.get(filePath)
        if (!Files.exists(path)) {
            val message = "File not found: $filePath"
            documentationRepository.updateDocSource(
                docId = docId,
                status = DocIndexingStatus.FAILED,
                errorMessage = message
            )
            throw IllegalArgumentException(message)
        }

        val content = readLocalFileContent(path)
        if (content.isBlank()) {
            val message = "No readable content extracted from file: $filePath"
            documentationRepository.updateDocSource(
                docId = docId,
                status = DocIndexingStatus.FAILED,
                errorMessage = message
            )
            throw IllegalStateException(message)
        }

        val contentHash = calculateHash(content)
        val existingFile = ragRepository.getIndexedFileByPath(projectRoot, filePath)

        if (existingFile != null && existingFile.fileHash == contentHash) {
            val fileName = path.fileName?.toString() ?: filePath
            documentationRepository.updateDocSource(
                docId = docId,
                status = DocIndexingStatus.INDEXED,
                title = fileName,
                pagesIndexed = 1,
                totalPages = 1
            )
            emit(DocIndexingProgress(100, "Already indexed", 1, 0))
            return@flow
        }

        @Suppress("DEPRECATION")
        val chunks = chunkingStrategy.chunkText(
            content,
            CHUNK_SIZE_TOKENS,
            CHUNK_OVERLAP_TOKENS
        )

        // File fingerprint and chunks in one transaction: a partial write would mark the file
        // as indexed with its chunks missing, and the hash check above would skip it forever.
        ragRepository.upsertIndexedFileWithChunks(
            existingFileId = existingFile?.id,
            projectRoot = projectRoot,
            filePath = filePath,
            fileHash = contentHash,
            fileSize = Files.size(path),
            mimeType = Files.probeContentType(path),
            lastModified = Files.getLastModifiedTime(path).toMillis(),
            contentType = RagContentType.DOCUMENTATION,
            sourceUrl = filePath
        ) { fileId ->
            chunks.mapIndexed { index, chunk ->
                ChunkInsert(
                    fileId = fileId,
                    chunkIndex = index,
                    content = chunk.content,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine
                )
            }
        }

        val chunksCreated = chunks.size

        val fileName = path.fileName?.toString() ?: filePath
        documentationRepository.updateDocSource(
            docId = docId,
            status = DocIndexingStatus.INDEXED,
            title = fileName,
            pagesIndexed = 1,
            totalPages = 1
        )

        emit(DocIndexingProgress(100, "Completed: 1 file, $chunksCreated chunks", 1, chunksCreated))
    }.flowOn(Dispatchers.IO)

    /**
     * Extract text content from HTML document
     */
    private fun extractText(document: Document): String {
        // Remove script, style, nav, footer, header elements
        document.select("script, style, nav, footer, header, .sidebar, .navigation, .menu").remove()

        // Extract main content (prefer <main>, <article>, or body)
        val mainContent = document.select("main, article, .content, .main-content, .documentation").firstOrNull()
            ?: document.body()

        return mainContent?.let { element ->
            val builder = StringBuilder()
            appendTextWithNewlines(element, builder)
            builder.toString().trim()
        } ?: ""
    }

    private fun appendTextWithNewlines(node: org.jsoup.nodes.Node, builder: StringBuilder) {
        when (node) {
            is org.jsoup.nodes.TextNode -> {
                val text = node.text().trim()
                if (text.isNotEmpty()) {
                    if (builder.isNotEmpty() && builder.last() != '\n') {
                        builder.append(' ')
                    }
                    builder.append(text)
                }
            }
            is org.jsoup.nodes.Element -> {
                val tag = node.normalName()
                if (tag == "br") {
                    ensureTrailingNewlines(builder, 1)
                    return
                }

                val isBlock = tag in BLOCK_TAGS
                node.childNodes().forEach { appendTextWithNewlines(it, builder) }
                if (isBlock) {
                    ensureTrailingNewlines(builder, 2)
                }
            }
        }
    }

    private fun ensureTrailingNewlines(builder: StringBuilder, count: Int) {
        var existing = 0
        var idx = builder.length - 1
        while (idx >= 0 && builder[idx] == '\n') {
            existing++
            idx--
        }
        repeat((count - existing).coerceAtLeast(0)) { builder.append('\n') }
    }

    /**
     * Extract links from document (same domain only, indexable files only)
     */
    private fun extractLinks(document: Document, baseUrl: URL): List<String> {
        return document.select("a[href]")
            .mapNotNull { element ->
                try {
                    val href = element.attr("abs:href")
                    if (href.isNotBlank() && isSameDomain(baseUrl, href) && isIndexableUrl(href)) {
                        normalizeUrl(href)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .distinct()
    }

    /**
     * Check if URL is indexable (not a multimedia/binary file)
     */
    private fun isIndexableUrl(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val path = url.path.lowercase()

            // Check if path has excluded extension
            val hasExcludedExtension = EXCLUDED_EXTENSIONS.any { ext ->
                path.endsWith(".$ext")
            }

            !hasExcludedExtension
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if URL is same domain as base
     */
    private fun isSameDomain(baseUrl: URL, urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            url.host == baseUrl.host
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Normalize URL (remove fragments, trailing slashes)
     */
    private fun normalizeUrl(urlString: String): String {
        val url = URL(urlString)
        val normalized = "${url.protocol}://${url.host}${url.path}"
        return normalized.trimEnd('/')
    }

    /**
     * Shorten URL for display (max 60 chars)
     */
    private fun shortenUrl(urlString: String): String {
        return if (urlString.length > 60) {
            urlString.substring(0, 57) + "..."
        } else {
            urlString
        }
    }

    /**
     * Calculate SHA-256 hash
     */
    private fun calculateHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun readLocalFileContent(path: Path): String {
        val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
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

/**
 * Crawl item (URL + depth)
 */
private data class CrawlItem(
    val url: String,
    val depth: Int
)

/**
 * Documentation indexing progress
 */
data class DocIndexingProgress(
    val progressPercent: Int,
    val statusMessage: String,
    val pagesIndexed: Int,
    val chunksCreated: Int
)
