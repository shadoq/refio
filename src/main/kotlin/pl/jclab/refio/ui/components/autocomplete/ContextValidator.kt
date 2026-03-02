package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.ProviderType

/**
 * Validator for context references.
 *
 * Validates size limits to prevent sending too much context to the LLM.
 *
 * Limits (can be configured in Settings):
 * - Max single file: 100KB (or ~25k tokens)
 * - Max total context: 500KB (or ~125k tokens)
 * - Max folder depth: 3 levels
 */
object ContextValidator {
    // Default limits (can be overridden via Settings)
    const val MAX_SINGLE_FILE_SIZE_BYTES: Long = 100 * 1024 // 100KB
    const val MAX_TOTAL_CONTEXT_SIZE_BYTES: Long = 500 * 1024 // 500KB
    const val MAX_SINGLE_FILE_TOKENS: Int = 25_000
    const val MAX_TOTAL_CONTEXT_TOKENS: Int = 125_000
    const val MAX_FOLDER_DEPTH: Int = 3

    /**
     * Validation result with error details.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val warnings: List<String> = emptyList()
    )

    /**
     * Validate a single context reference.
     *
     * NOTE: Content may be null for unresolved references (e.g., @folder, @file before resolution).
     * Content is loaded later by ContextService.resolveUserContextReferences().
     * We only validate size/token limits for resolved references (content != null).
     *
     * @param ref Context reference to validate
     * @return Validation result
     */
    fun validateSingle(ref: ContextReference): ValidationResult {
        if (ref.type == pl.jclab.refio.api.models.ContextType.PROVIDER) {
            val providerId = ref.metadata["providerId"]?.toString()
            if (!providerId.isNullOrBlank()) {
                val provider = ContextProviderRegistry.getProvider(providerId)
                if (provider?.description?.type == ProviderType.QUERY && ref.path.isBlank()) {
                    return ValidationResult(
                        isValid = false,
                        errorMessage = "Provider @$providerId requires a query (use @${providerId}:query)"
                    )
                }
            }
        }

        // Skip size/token checks for unresolved references (content will be loaded later)
        if (ref.content == null) {
            // Unresolved reference - valid at this stage, will be resolved when sending
            return ValidationResult(isValid = true)
        }

        // Check single file size
        if (ref.sizeBytes > MAX_SINGLE_FILE_SIZE_BYTES) {
            return ValidationResult(
                isValid = false,
                errorMessage = "File ${ref.displayName} is too large: ${formatBytes(ref.sizeBytes)} " +
                        "(max: ${formatBytes(MAX_SINGLE_FILE_SIZE_BYTES)})"
            )
        }

        // Check single file tokens
        if (ref.estimatedTokens > MAX_SINGLE_FILE_TOKENS) {
            return ValidationResult(
                isValid = false,
                errorMessage = "File ${ref.displayName} has too many tokens: ${ref.estimatedTokens} " +
                        "(max: $MAX_SINGLE_FILE_TOKENS)"
            )
        }

        // Check folder depth
        if (ref.type == pl.jclab.refio.api.models.ContextType.FOLDER) {
            val depth = (ref.metadata["depth"] as? Int) ?: 1
            if (depth > MAX_FOLDER_DEPTH) {
                return ValidationResult(
                    isValid = false,
                    errorMessage = "Folder depth $depth exceeds maximum of $MAX_FOLDER_DEPTH"
                )
            }
        }

        // Warnings for large files
        val warnings = mutableListOf<String>()
        if (ref.sizeBytes > MAX_SINGLE_FILE_SIZE_BYTES / 2) {
            warnings.add("${ref.displayName} is large (${formatBytes(ref.sizeBytes)}), consider using specific sections")
        }

        return ValidationResult(
            isValid = true,
            warnings = warnings
        )
    }

    /**
     * Validate a list of context references.
     *
     * @param refs List of context references
     * @return Validation result with total size check
     */
    fun validateList(refs: List<ContextReference>): ValidationResult {
        // Validate each reference individually
        for (ref in refs) {
            val result = validateSingle(ref)
            if (!result.isValid) {
                return result
            }
        }

        // Check total size
        val totalBytes = refs.sumOf { it.sizeBytes }
        if (totalBytes > MAX_TOTAL_CONTEXT_SIZE_BYTES) {
            return ValidationResult(
                isValid = false,
                errorMessage = "Total context size ${formatBytes(totalBytes)} exceeds maximum " +
                        "${formatBytes(MAX_TOTAL_CONTEXT_SIZE_BYTES)}"
            )
        }

        // Check total tokens
        val totalTokens = refs.sumOf { it.estimatedTokens }
        if (totalTokens > MAX_TOTAL_CONTEXT_TOKENS) {
            return ValidationResult(
                isValid = false,
                errorMessage = "Total context tokens $totalTokens exceeds maximum $MAX_TOTAL_CONTEXT_TOKENS"
            )
        }

        // Collect warnings
        val warnings = refs.flatMap { ref ->
            validateSingle(ref).warnings
        }

        // Warning for approaching limits
        val sizeWarnings = mutableListOf<String>()
        if (totalBytes > MAX_TOTAL_CONTEXT_SIZE_BYTES * 0.8) {
            sizeWarnings.add("Approaching total size limit: ${formatBytes(totalBytes)} / ${formatBytes(MAX_TOTAL_CONTEXT_SIZE_BYTES)}")
        }
        if (totalTokens > MAX_TOTAL_CONTEXT_TOKENS * 0.8) {
            sizeWarnings.add("Approaching total token limit: $totalTokens / $MAX_TOTAL_CONTEXT_TOKENS")
        }

        return ValidationResult(
            isValid = true,
            warnings = warnings + sizeWarnings
        )
    }

    /**
     * Format bytes as human-readable string.
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
