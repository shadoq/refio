package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.core.subagents.models.SubagentInfo

/**
 * Autocomplete item for subagents (triggered by "!")
 */
data class SubagentAutocompleteItem(
    val subagent: SubagentInfo
) : AutocompleteItem {
    override fun getDisplayName(): String {
        return "!${subagent.name}"
    }

    override fun getDescription(): String {
        return subagent.description
    }

    override fun matchesPrefix(prefix: String): Boolean {
        val cleanPrefix = prefix.removePrefix("!").lowercase()
        return subagent.name.lowercase().startsWith(cleanPrefix) ||
               subagent.description.lowercase().contains(cleanPrefix)
    }

    override fun getSortKey(): String {
        // Sort by priority (higher priority first), then alphabetically
        // Use inverted priority so higher values come first
        val priorityKey = String.format("%05d", Int.MAX_VALUE - subagent.priority)
        return "$priorityKey:${subagent.name}"
    }

    override fun getIcon(): String {
        return when (subagent.scope) {
            "builtin" -> "\uD83D\uDCE6" // package icon
            "user" -> "\uD83D\uDC64"    // user icon
            "project" -> "\uD83D\uDCC1" // folder icon
            else -> "\uD83E\uDD16"      // robot icon
        }
    }
}
