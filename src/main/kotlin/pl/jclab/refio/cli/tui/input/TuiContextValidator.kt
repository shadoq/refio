package pl.jclab.refio.cli.tui.input

import java.io.File

/**
 * Validates context references (@file, @folder) before adding to prompt.
 * Adapted from plugin's ContextValidator (ui/components/autocomplete/ContextValidator.kt).
 *
 * Checks file size and folder depth to prevent sending too much context to the LLM.
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
            else -> ValidationResult(isValid = true) // other context types don't need validation
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
