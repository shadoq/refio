package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.api.models.SlashCommand

/**
 * Autocomplete item for slash commands
 */
data class CommandAutocompleteItem(
    val command: SlashCommand
) : AutocompleteItem {
    override fun getDisplayName(): String {
        return "/${command.name}"
    }

    override fun getDescription(): String {
        return command.description
    }

    override fun matchesPrefix(prefix: String): Boolean {
        val cleanPrefix = prefix.removePrefix("/").lowercase()
        return command.name.lowercase().startsWith(cleanPrefix) ||
               command.description.lowercase().contains(cleanPrefix)
    }

    override fun getSortKey(): String {
        // Builtin commands first, then alphabetically
        val prefix = if (command.isBuiltin) "0" else "1"
        return "$prefix:${command.name}"
    }
}
