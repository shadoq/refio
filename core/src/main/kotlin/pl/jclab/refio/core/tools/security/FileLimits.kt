package pl.jclab.refio.core.tools.security

/**
 * File size and operation limits for security and performance
 */
data class FileLimits(
    /**
     * Maximum file size to read (default: 2 MB)
     */
    val maxFileSize: Long = 2_097_152, // 2 MB

    /**
     * Maximum number of files in directory listing (default: 1000)
     */
    val maxFilesInDirectory: Int = 1000,

    /**
     * Maximum search depth for file search (default: 10)
     */
    val maxSearchDepth: Int = 10,

    /**
     * Maximum number of search results (default: 100)
     */
    val maxSearchResults: Int = 100,

    /**
     * Maximum grep results (default: 500)
     */
    val maxGrepResults: Int = 500,

    /**
     * Directories to exclude from search operations (glob-style patterns)
     * Common build artifacts, dependencies, and version control directories
     */
    val excludedDirectories: Set<String> = DEFAULT_EXCLUDED_DIRECTORIES,

    /**
     * File extensions to exclude from search operations
     * Binary files, compiled code, and other non-text formats
     */
    val excludedExtensions: Set<String> = DEFAULT_EXCLUDED_EXTENSIONS
) {
    companion object {
        /**
         * Directories commonly excluded from searches (version control, dependencies, build artifacts)
         */
        val DEFAULT_EXCLUDED_DIRECTORIES = setOf(
            // Version control
            ".git", ".svn", ".hg", ".bzr",

            // Python
            ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".tox", "*.egg-info",

            // Node.js / JavaScript
            "node_modules", ".npm", ".yarn",

            // Java / Kotlin / Gradle
            "build", ".gradle", "target", "out", ".idea",

            // IDE
            ".vscode", ".eclipse", ".settings",

            // OS
            ".DS_Store", "Thumbs.db",

            // Other
            "dist", "bin", "obj", ".cache"
        )

        /**
         * File extensions commonly excluded from searches (binary, compiled, media)
         */
        val DEFAULT_EXCLUDED_EXTENSIONS = setOf(
            // Python bytecode
            "pyc", "pyo", "pyd",

            // Java / JVM
            "class", "jar", "war", "ear",

            // Compiled binaries
            "exe", "dll", "so", "dylib", "o", "obj",

            // Archives
            "zip", "tar", "gz", "bz2", "7z", "rar",

            // Media
            "jpg", "jpeg", "png", "gif", "bmp", "ico", "svg",
            "mp3", "mp4", "avi", "mov", "wav", "flac",

            // Documents (binary formats)
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",

            // Database
            "db", "sqlite", "sqlite3",

            // Other
            "woff", "woff2", "ttf", "eot", "lock"
        )

        /**
         * Default file limits
         */
        val DEFAULT = FileLimits()

        /**
         * Strict limits for untrusted operations
         */
        val STRICT = FileLimits(
            maxFileSize = 524_288, // 512 KB
            maxFilesInDirectory = 100,
            maxSearchDepth = 5,
            maxSearchResults = 50,
            maxGrepResults = 200
        )
    }

    /**
     * Check if directory should be excluded from search based on name
     */
    fun shouldExcludeDirectory(dirName: String): Boolean {
        return excludedDirectories.any { pattern ->
            if (pattern.contains("*")) {
                // Simple glob matching
                val regex = pattern.replace(".", "\\.").replace("*", ".*")
                dirName.matches(Regex(regex))
            } else {
                dirName == pattern
            }
        }
    }

    /**
     * Check if file should be excluded from search based on extension
     */
    fun shouldExcludeFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && excludedExtensions.contains(extension)
    }
}

/**
 * Exception thrown when file size exceeds limit
 */
class FileTooLargeException(message: String) : Exception(message)

/**
 * Exception thrown when operation exceeds limits
 */
class LimitExceededException(message: String) : Exception(message)
