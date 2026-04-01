package pl.jclab.refio.core.prompts

import org.yaml.snakeyaml.Yaml
import pl.jclab.refio.core.registry.DefinitionScope
import java.nio.file.Path

class PromptParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parser for prompt definition files (.md with YAML frontmatter).
 *
 * Format:
 * ```markdown
 * ---
 * name: system-agent
 * type: system
 * description: Main system prompt for AGENT execution mode
 * variables:
 *   - tool_descriptions
 * ---
 *
 * You are a helpful AI coding assistant...
 * ```
 */
class PromptParser {
    private val yaml = Yaml()

    private val frontmatterRegex = Regex(
        "^\\s*---\\s*[\\r\\n]+(.+?)[\\r\\n]+---\\s*[\\r\\n]+(.*)$",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(content: String, sourcePath: Path?, scope: DefinitionScope): PromptDefinition {
        val cleanContent = content.trimStart('\uFEFF')

        val match = frontmatterRegex.find(cleanContent)
            ?: throw PromptParseException("Invalid format: missing YAML frontmatter")

        val yamlContent = match.groupValues[1]
        val promptContent = match.groupValues[2].trim()

        val frontmatter: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            yaml.load(yamlContent) as? Map<String, Any?>
                ?: throw PromptParseException("YAML frontmatter is not a valid map")
        } catch (e: Exception) {
            if (e is PromptParseException) throw e
            throw PromptParseException("Failed to parse YAML frontmatter: ${e.message}", e)
        }

        val name = (frontmatter["name"] as? String)?.lowercase()?.replace(" ", "-")
            ?: throw PromptParseException("Missing required field: name")

        val description = frontmatter["description"] as? String
            ?: throw PromptParseException("Missing required field: description")

        return PromptDefinition(
            name = name,
            type = parseContentType(frontmatter["type"] as? String),
            description = description,
            content = promptContent,
            variables = parseStringList(frontmatter["variables"]),
            mode = frontmatter["mode"] as? String,
            role = frontmatter["role"] as? String ?: "system",
            enabled = frontmatter["enabled"] as? Boolean ?: true,
            scope = scope,
            sourcePath = sourcePath
        )
    }

    private fun parseContentType(value: String?): PromptContentType {
        return when (value?.lowercase()) {
            "tool" -> PromptContentType.TOOL
            else -> PromptContentType.SYSTEM
        }
    }

    private fun parseStringList(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.filterIsInstance<String>()
            is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }
}
