package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.api.models.SlashPrompt

/**
 * Autocomplete item for slash prompts
 */
data class PromptAutocompleteItem(
    val slashPrompt: SlashPrompt
) : AutocompleteItem {
    override fun getDisplayName(): String {
        return "/${slashPrompt.name}"
    }

    override fun getDescription(): String {
        return slashPrompt.description
    }

    override fun matchesPrefix(prefix: String): Boolean {
        val cleanPrefix = prefix.removePrefix("/").lowercase()
        return slashPrompt.name.lowercase().startsWith(cleanPrefix) ||
               slashPrompt.description.lowercase().contains(cleanPrefix)
    }

    override fun getSortKey(): String {
        // Builtin prompts first, then alphabetically
        val prefix = if (slashPrompt.isBuiltin) "0" else "1"
        return "$prefix:${slashPrompt.name}"
    }
}
