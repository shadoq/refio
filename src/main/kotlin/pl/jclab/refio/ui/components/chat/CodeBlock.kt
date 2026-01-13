package pl.jclab.refio.ui.components.chat

/**
 * Represents a code block extracted from markdown content.
 *
 * Format: ```language:filepath
 * content
 * ```
 *
 * Example:
 * ```kotlin:src/main/kotlin/com/example/Service.kt
 * class UserService { }
 * ```
 */
data class CodeBlock(
    val language: String,        // "kotlin", "java", "python", etc.
    val filePath: String?,       // "src/main/kotlin/com/example/Service.kt" (optional)
    val content: String,         // code content
    val startIndex: Int,         // position in message where code block starts
    val endIndex: Int            // position in message where code block ends
)

/**
 * Extract all code blocks from markdown content.
 *
 * Supports format:
 * - ```language:filepath
 * - ```language
 *
 * @param markdown Markdown content
 * @return List of extracted code blocks
 */
fun extractCodeBlocks(markdown: String): List<CodeBlock> {
    val blocks = mutableListOf<CodeBlock>()

    // Regex to match code blocks with optional file path, optional language, and optional whitespace
    // Pattern: ```language:filepath\ncontent```
    // or: ```language\ncontent```
    // or: ```language{content}``` (no newline)
    // or: ```\ncontent``` (no language)
    val codeBlockRegex = Regex(
        """```(\w*)(?::([^\n]+))?\s*(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    )

    codeBlockRegex.findAll(markdown).forEach { match ->
        val language = match.groupValues[1].takeIf { it.isNotBlank() } ?: "text"
        val filePath = match.groupValues[2].takeIf { it.isNotBlank() }?.trim()
        // Normalize line endings to \n for consistent JTextArea rendering
        val content = match.groupValues[3].trim().replace("\r\n", "\n").replace("\r", "\n")

        blocks.add(
            CodeBlock(
                language = language,
                filePath = filePath,
                content = content,
                startIndex = match.range.first,
                endIndex = match.range.last
            )
        )
    }

    return blocks
}
