package pl.jclab.refio.cli.tui.screens

import pl.jclab.refio.core.api.ToolDefinitionInfo
import pl.jclab.refio.core.models.api.ToolPermissionDto
import pl.jclab.refio.core.tools.base.ToolCategory

/**
 * Tools of the Tools settings tab grouped by the category the core gives each tool, in the order the core
 * declares the categories. A tool the core does not describe, or with a category this build does not know,
 * goes to "Other" at the end.
 */
object ToolGroups {

    data class Group(val label: String, val category: ToolCategory?, val tools: List<ToolPermissionDto>)

    const val OTHER = "Other"

    /** "DATA_PRODUCING" -> "Data producing". */
    fun label(category: ToolCategory): String =
        category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun group(permissions: List<ToolPermissionDto>, definitions: Map<String, ToolDefinitionInfo>): List<Group> {
        val known = ToolCategory.values().associateBy { it.name }
        val byCategory = permissions.groupBy { known[definitions[it.toolName]?.category] }
        val groups = ToolCategory.values().mapNotNull { category ->
            byCategory[category]?.let { Group(label(category), category, it.sortedBy { t -> t.toolName }) }
        }
        val other = byCategory[null]?.let { Group(OTHER, null, it.sortedBy { t -> t.toolName }) }
        return groups + listOfNotNull(other)
    }
}
