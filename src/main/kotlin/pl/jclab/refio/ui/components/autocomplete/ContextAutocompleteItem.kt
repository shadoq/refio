package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType

/**
 * Autocomplete item for context references (@mentions)
 */
data class ContextAutocompleteItem(
    val contextRef: ContextReference,
    private val iconValue: String = ""
) : AutocompleteItem {
    override fun getDisplayName(): String {
        return contextRef.displayName
    }

    override fun getDescription(): String {
        // Check if there's a custom description in metadata first
        val customDescription = contextRef.metadata["description"] as? String
        if (customDescription != null) {
            return customDescription
        }

        return when (contextRef.type) {
            ContextType.FILE -> "File: ${contextRef.path}"
            ContextType.FOLDER -> "Folder: ${contextRef.path}"
            ContextType.SELECTION -> "Current selection"
            ContextType.OPEN -> "All open files"
            ContextType.DOCS -> "Documentation: ${contextRef.path}"
            ContextType.RULES -> "Rules file"
            ContextType.PROVIDER -> {
                val providerId = contextRef.metadata["providerId"] as? String ?: "unknown"
                if (contextRef.path.isNotEmpty()) {
                    "Provider: $providerId (${contextRef.path})"
                } else {
                    "Provider: $providerId"
                }
            }
        }
    }

    override fun matchesPrefix(prefix: String): Boolean {
        val cleanPrefix = prefix.removePrefix("@").lowercase()
        return contextRef.displayName.lowercase().contains(cleanPrefix) ||
               contextRef.path.lowercase().contains(cleanPrefix) ||
               contextRef.type.name.lowercase().startsWith(cleanPrefix)
    }

    override fun getSortKey(): String {
        // Prioritize by type, then alphabetically
        val typePriority = when (contextRef.type) {
            ContextType.SELECTION -> "0"
            ContextType.OPEN -> "1"
            ContextType.FILE -> "2"
            ContextType.FOLDER -> "3"
            ContextType.RULES -> "4"
            ContextType.DOCS -> "5"
            ContextType.PROVIDER -> {
                // MCP servers go last, built-in providers before DOCS
                val providerId = contextRef.metadata["providerId"] as? String ?: "unknown"
                if (providerId.startsWith("mcp-")) "7" else "4.5"
            }
        }
        return "$typePriority:${contextRef.displayName}"
    }

    override fun getIcon(): String = iconValue
}
