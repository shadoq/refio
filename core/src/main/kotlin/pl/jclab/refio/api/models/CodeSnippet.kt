package pl.jclab.refio.api.models

import java.util.UUID

/**
 * Represents a code snippet attached to a prompt.
 *
 * Used for Ctrl+J / Ctrl+Shift+J functionality to add selected code
 * from editor to the current conversation.
 */
data class CodeSnippet(
    val id: String = UUID.randomUUID().toString(),
    val filepath: String,      // Full path to file
    val filename: String,      // Just the filename (for display)
    val startLine: Int,        // Start line (1-indexed)
    val endLine: Int,          // End line (1-indexed)
    val content: String,       // Code content
    val language: String? = null  // Optional language for syntax highlighting
) {
    /**
     * Display name shown in snippet card header.
     * Format: "filename:startLine-endLine"
     */
    val displayName: String
        get() = if (startLine == endLine) {
            "$filename:$startLine"
        } else {
            "$filename:$startLine-$endLine"
        }

    /**
     * Number of lines in the snippet.
     */
    val lineCount: Int
        get() = endLine - startLine + 1

    /**
     * Convert to ContextReference for sending with message.
     */
    fun toContextReference(): ContextReference {
        return ContextReference(
            type = ContextType.SELECTION,
            path = filepath,
            displayName = displayName,
            content = content,
            metadata = mapOf(
                "filepath" to filepath,
                "startLine" to startLine,
                "endLine" to endLine,
                "language" to (language ?: "")
            )
        )
    }
}
