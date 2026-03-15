package pl.jclab.refio.core.services

import pl.jclab.refio.core.services.analysis.CodeElements

enum class ChunkingMode {
    LINE_BASED,
    SEMANTIC;

    companion object {
        fun fromConfig(value: String): ChunkingMode {
            return when (value.lowercase()) {
                "line_based", "line-based", "line" -> LINE_BASED
                else -> SEMANTIC
            }
        }
    }
}

/**
 * Strategy for chunking text/code for RAG indexing.
 *
 * `createChunks` is the primary API. `chunkText` exists for legacy
 * documentation/indexing services and maps to `TextChunk`.
 */
interface ChunkingStrategy {
    fun createChunks(
        content: String,
        codeElements: CodeElements,
        language: String? = null,
        maxChunkChars: Int = DEFAULT_CHUNK_SIZE
    ): List<CodeChunk>

    @Deprecated("Use createChunks with CodeElements for richer metadata")
    fun chunkText(
        content: String,
        chunkSizeTokens: Int,
        overlapTokens: Int
    ): List<TextChunk> {
        val chunks = createChunks(
            content = content,
            codeElements = CodeElements(),
            language = null,
            maxChunkChars = chunkSizeTokens
        )
        return chunks.map {
            TextChunk(
                content = it.content,
                startLine = it.startLine,
                endLine = it.endLine
            )
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 2_000
    }
}

data class TextChunk(
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val startChar: Int? = null,
    val endChar: Int? = null
)

data class CodeChunk(
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val type: String,
    val metadata: ChunkMetadata
)

data class ChunkMetadata(
    val name: String? = null,
    val signature: String? = null,
    val annotations: List<String> = emptyList(),
    val documentation: String? = null
)

/**
 * Legacy line-based chunking (used by documentation + manual indexing flows).
 */
class DefaultChunkingStrategy : ChunkingStrategy {
    override fun createChunks(
        content: String,
        codeElements: CodeElements,
        language: String?,
        maxChunkChars: Int
    ): List<CodeChunk> {
        val legacyChunks = chunkText(content, maxChunkChars, maxChunkChars / 5)
        return legacyChunks.map {
            CodeChunk(
                content = it.content,
                startLine = it.startLine,
                endLine = it.endLine,
                type = "block",
                metadata = ChunkMetadata()
            )
        }
    }

    @Deprecated("Use createChunks for structured metadata")
    override fun chunkText(
        content: String,
        chunkSizeTokens: Int,
        overlapTokens: Int
    ): List<TextChunk> {
        val lines = content.lines()
        val chunkSizeChars = chunkSizeTokens
        val overlapChars = overlapTokens

        val chunks = mutableListOf<TextChunk>()
        var currentChunk = StringBuilder()
        var currentStartLine = 0
        var currentLine = 0

        for (line in lines) {
            var remaining = line

            while (remaining.isNotEmpty()) {
                // Account for newline only when chunk already has content
                val separatorLength = if (currentChunk.isNotEmpty()) 1 else 0
                val available = chunkSizeChars - (currentChunk.length + separatorLength)

                if (available <= 0 && currentChunk.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            content = currentChunk.toString().trimEnd(),
                            startLine = currentStartLine,
                            endLine = currentLine - 1
                        )
                    )

                    val overlapText = getOverlapText(currentChunk.toString(), overlapChars)
                    val overlapLines = calculateOverlapLines(currentStartLine, currentLine - 1, overlapText)

                    currentChunk = StringBuilder(overlapText)
                    currentStartLine = overlapLines
                    continue
                }

                val toTake = remaining.length.coerceAtMost(available)

                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n")
                }
                currentChunk.append(remaining.substring(0, toTake))
                remaining = remaining.drop(toTake)

                // Flush immediately if the line segment filled the chunk
                if (remaining.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            content = currentChunk.toString().trimEnd(),
                            startLine = currentStartLine,
                            endLine = currentLine
                        )
                    )

                    val overlapText = getOverlapText(currentChunk.toString(), overlapChars)
                    val overlapLines = calculateOverlapLines(currentStartLine, currentLine, overlapText)

                    currentChunk = StringBuilder(overlapText)
                    currentStartLine = overlapLines
                }
            }

            currentLine++
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(
                TextChunk(
                    content = currentChunk.toString().trimEnd(),
                    startLine = currentStartLine,
                    endLine = lines.size - 1
                )
            )
        }

        if (chunks.isEmpty() && content.isNotBlank()) {
            chunks.add(
                TextChunk(
                    content = content,
                    startLine = 0,
                    endLine = maxOf(0, lines.size - 1)
                )
            )
        }

        return chunks
    }

    private fun getOverlapText(text: String, overlapChars: Int): String {
        if (text.length <= overlapChars) {
            return text
        }

        val startIndex = maxOf(0, text.length - overlapChars)
        val overlap = text.substring(startIndex)

        val lastNewline = overlap.indexOf('\n')
        return if (lastNewline >= 0 && lastNewline < overlap.length - 1) {
            overlap.substring(lastNewline + 1)
        } else {
            overlap
        }
    }

    private fun calculateOverlapLines(startLine: Int, endLine: Int, overlapText: String): Int {
        val overlapLineCount = overlapText.count { it == '\n' }
        return maxOf(startLine, endLine - overlapLineCount)
    }
}

/**
 * Semantic chunking aware of code structure.
 */
class SemanticChunkingStrategy(
    private val includeFullFileChunk: Boolean = true
) : ChunkingStrategy {

    override fun createChunks(
        content: String,
        codeElements: CodeElements,
        language: String?,
        maxChunkChars: Int
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        if (includeFullFileChunk) {
            // Clamp full-file chunk to avoid exceeding embedding context limits
            val fullContent = content.take(maxChunkChars)
            chunks.add(
                CodeChunk(
                    content = fullContent,
                    startLine = 1,
                    endLine = fullContent.lines().size,
                    type = "full_file",
                    metadata = ChunkMetadata()
                )
            )
        }

        codeElements.classes.forEach { cls ->
            val classContent = extractContent(lines, cls.startLine, cls.endLine, maxChunkChars)
            chunks.add(
                CodeChunk(
                    content = classContent,
                    startLine = cls.startLine,
                    endLine = cls.endLine,
                    type = cls.type.ifBlank { "class" },
                    metadata = ChunkMetadata(
                        name = cls.name,
                        annotations = cls.annotations,
                        documentation = cls.documentation
                    )
                )
            )

            cls.methods.forEach { method ->
                val methodContent = extractContent(lines, method.startLine, method.endLine, maxChunkChars)
                chunks.add(
                    CodeChunk(
                        content = methodContent,
                        startLine = method.startLine,
                        endLine = method.endLine,
                        type = "function",
                        metadata = ChunkMetadata(
                            name = "${cls.name}.${method.name}",
                            signature = method.signature,
                            annotations = method.annotations,
                            documentation = method.documentation
                        )
                    )
                )
            }
        }

        codeElements.functions.forEach { fn ->
            val fnContent = extractContent(lines, fn.startLine, fn.endLine, maxChunkChars)
            chunks.add(
                CodeChunk(
                    content = fnContent,
                    startLine = fn.startLine,
                    endLine = fn.endLine,
                    type = "function",
                    metadata = ChunkMetadata(
                        name = fn.name,
                        signature = fn.signature,
                        annotations = fn.annotations,
                        documentation = fn.documentation
                    )
                )
            )
        }

        return chunks
    }

    private fun extractContent(
        lines: List<String>,
        startLine: Int?,
        endLine: Int?,
        maxChunkChars: Int
    ): String {
        if (startLine == null || endLine == null) {
            return lines.joinToString("\n").take(maxChunkChars)
        }
        val safeEnd = endLine.coerceAtMost(lines.size)
        val slice = if (startLine <= safeEnd) {
            lines.subList(startLine - 1, safeEnd)
        } else {
            emptyList()
        }
        val text = slice.joinToString("\n")
        return if (text.length <= maxChunkChars) {
            text
        } else {
            text.take(maxChunkChars)
        }
    }
}
