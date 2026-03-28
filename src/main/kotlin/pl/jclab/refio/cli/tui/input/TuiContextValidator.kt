package pl.jclab.refio.cli.tui.input

import java.io.File

/**
 * Validates context references (@file, @folder, @codebase, @docs, @git_diff, etc.)
 * before adding to prompt.
 * Adapted from plugin's ContextValidator (ui/components/autocomplete/ContextValidator.kt).
 *
 * Checks file size, folder depth, and availability of RAG/git features.
 */
object TuiContextValidator {

    const val MAX_SINGLE_FILE_SIZE_BYTES: Long = 100 * 1024 // 100KB
    const val MAX_FOLDER_DEPTH: Int = 3

    data class ValidationResult(
        val isValid: Boolean,
        val warning: String? = null
    )

    /**
     * Validate a context reference from user input.
     * Extracts path from @file:path or @folder:path format.
     */
    fun validate(reference: String, projectRoot: String): ValidationResult {
        return when {
            reference.startsWith("@file:") -> validateFile(reference.removePrefix("@file:"), projectRoot)
            reference.startsWith("@folder:") -> validateFolder(reference.removePrefix("@folder:"), projectRoot)
            reference.startsWith("@codebase:") -> validateCodebase(reference.removePrefix("@codebase:"), projectRoot)
            reference.startsWith("@docs:") -> validateDocs(reference.removePrefix("@docs:"), projectRoot)
            reference == "@git_diff" -> validateGitDiff(projectRoot)
            reference.startsWith("@git_commit:") -> validateGitCommit(reference.removePrefix("@git_commit:"), projectRoot)
            reference.startsWith("@url:") -> validateUrl(reference.removePrefix("@url:"))
            reference.startsWith("@grep:") -> validateGrep(reference.removePrefix("@grep:"))
            // Simple refs that don't need validation
            reference in listOf("@selection", "@current", "@open_files", "@recent", "@problems", "@terminal") ->
                ValidationResult(isValid = true)
            else -> ValidationResult(isValid = true)
        }
    }

    private fun validateFile(path: String, projectRoot: String): ValidationResult {
        val file = resolveFile(path, projectRoot)

        if (!file.exists()) {
            return ValidationResult(isValid = false, warning = "File not found: $path")
        }

        if (!file.isFile) {
            return ValidationResult(isValid = false, warning = "Not a file: $path (use @folder: for directories)")
        }

        val size = file.length()
        if (size > MAX_SINGLE_FILE_SIZE_BYTES) {
            return ValidationResult(
                isValid = false,
                warning = "File too large: ${formatBytes(size)} (max ${formatBytes(MAX_SINGLE_FILE_SIZE_BYTES)})"
            )
        }

        if (size > MAX_SINGLE_FILE_SIZE_BYTES / 2) {
            return ValidationResult(
                isValid = true,
                warning = "Large file: ${formatBytes(size)} — consider using specific sections"
            )
        }

        return ValidationResult(isValid = true)
    }

    private fun validateFolder(path: String, projectRoot: String): ValidationResult {
        val folder = resolveFile(path, projectRoot)

        if (!folder.exists()) {
            return ValidationResult(isValid = false, warning = "Folder not found: $path")
        }

        if (!folder.isDirectory) {
            return ValidationResult(isValid = false, warning = "Not a directory: $path (use @file: for files)")
        }

        // Check depth
        val depth = path.count { it == '/' || it == '\\' } + 1
        if (depth > MAX_FOLDER_DEPTH) {
            return ValidationResult(
                isValid = false,
                warning = "Folder depth $depth exceeds maximum of $MAX_FOLDER_DEPTH"
            )
        }

        return ValidationResult(isValid = true)
    }

    private fun validateCodebase(query: String, projectRoot: String): ValidationResult {
        if (query.isBlank()) {
            return ValidationResult(isValid = false, warning = "@codebase: requires a search query")
        }
        // Check if RAG index exists (look for database file)
        val dbFile = File(System.getProperty("user.home"), ".refio/data/database.sqlite")
        if (!dbFile.exists()) {
            return ValidationResult(isValid = true, warning = "RAG index may not be available. Run reindex first.")
        }
        return ValidationResult(isValid = true)
    }

    private fun validateDocs(query: String, projectRoot: String): ValidationResult {
        if (query.isBlank()) {
            return ValidationResult(isValid = false, warning = "@docs: requires a search query")
        }
        return ValidationResult(isValid = true)
    }

    private fun validateGitDiff(projectRoot: String): ValidationResult {
        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            return ValidationResult(isValid = true, warning = "Not a git repository — @git_diff may not work")
        }
        return ValidationResult(isValid = true)
    }

    private fun validateGitCommit(ref: String, projectRoot: String): ValidationResult {
        if (ref.isBlank()) {
            return ValidationResult(isValid = false, warning = "@git_commit: requires a commit reference (hash, branch, tag)")
        }
        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            return ValidationResult(isValid = true, warning = "Not a git repository — @git_commit may not work")
        }
        return ValidationResult(isValid = true)
    }

    private fun validateUrl(url: String): ValidationResult {
        if (url.isBlank()) {
            return ValidationResult(isValid = false, warning = "@url: requires a URL")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ValidationResult(isValid = false, warning = "@url: must start with http:// or https://")
        }
        return ValidationResult(isValid = true)
    }

    private fun validateGrep(pattern: String): ValidationResult {
        if (pattern.isBlank()) {
            return ValidationResult(isValid = false, warning = "@grep: requires a search pattern")
        }
        // Validate regex
        try {
            Regex(pattern)
        } catch (_: Exception) {
            return ValidationResult(isValid = true, warning = "Invalid regex, will use literal search")
        }
        return ValidationResult(isValid = true)
    }

    private fun resolveFile(path: String, projectRoot: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(projectRoot, path)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
