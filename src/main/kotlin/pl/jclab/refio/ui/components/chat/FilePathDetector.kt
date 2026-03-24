package pl.jclab.refio.ui.components.chat

/**
 * Represents a file path found in text
 */
data class FilePathMatch(
    val path: String,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Detects file paths in text content.
 *
 * Supports patterns:
 * - src/main/kotlin/File.kt
 * - agent/plugin/src/main.kt
 * - docs/readme.md
 * - path/to/file.java
 */
object FilePathDetector {

    // Regex pattern for file paths
    // Matches: word chars, slashes, dots, hyphens, underscores
    // Ends with common file extensions
    private val FILE_PATH_PATTERN = Regex(
        """(?:^|\s)([a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]{1,6})(?:\s|$|[,.:;)])""",
        RegexOption.MULTILINE
    )

    // Common source file extensions
    private val SOURCE_EXTENSIONS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "cpp", "c", "h", "hpp",
        "cs", "rb", "php", "swift", "scala", "clj", "sh", "bat", "ps1",
        "md", "txt", "xml", "json", "yaml", "yml", "toml", "properties",
        "gradle", "kts", "sql", "html", "css", "scss", "less"
    )

    /**
     * Find all file paths in given text.
     *
     * @param text Text to search
     * @param minPathSegments Minimum number of path segments (default 2, e.g., "src/File.kt")
     * @return List of file path matches
     */
    fun findFilePaths(text: String, minPathSegments: Int = 2): List<FilePathMatch> {
        val matches = mutableListOf<FilePathMatch>()

        FILE_PATH_PATTERN.findAll(text).forEach { matchResult ->
            val path = matchResult.groupValues[1]
            val extension = path.substringAfterLast('.', "")

            // Filter by extension and path structure
            if (SOURCE_EXTENSIONS.contains(extension.lowercase())) {
                val segments = path.count { it == '/' || it == '\\' } + 1

                if (segments >= minPathSegments) {
                    // Normalize path (use forward slashes)
                    val normalizedPath = path.replace('\\', '/')

                    matches.add(
                        FilePathMatch(
                            path = normalizedPath,
                            startIndex = matchResult.groups[1]!!.range.first,
                            endIndex = matchResult.groups[1]!!.range.last
                        )
                    )
                }
            }
        }

        return matches
    }

    /**
     * Check if a string looks like a file path
     */
    fun looksLikeFilePath(text: String): Boolean {
        if (!text.contains('/') && !text.contains('\\')) return false

        val extension = text.substringAfterLast('.', "")
        return SOURCE_EXTENSIONS.contains(extension.lowercase())
    }
}
