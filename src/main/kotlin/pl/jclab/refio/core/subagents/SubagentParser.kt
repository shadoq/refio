package pl.jclab.refio.core.subagents

import org.yaml.snakeyaml.Yaml
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentExecutionMode
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("SubagentParser")

/**
 * Wyjątek parsowania definicji subagenta.
 */
class SubagentParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parser plików definicji subagentów (.md z YAML frontmatter).
 *
 * Format pliku:
 * ```markdown
 * ---
 * name: security-reviewer
 * description: Security audit specialist
 * tools: read_file, grep_search
 * model: haiku
 * ---
 *
 * You are a security expert...
 * ```
 */
class SubagentParser {
    private val yaml = Yaml()

    /**
     * Regex do wyodrębnienia YAML frontmatter i treści.
     * Obsługuje:
     * - Windows (CRLF) i Unix (LF) line endings
     * - Opcjonalne BOM na początku pliku
     */
    private val frontmatterRegex = Regex(
        "^\\s*---\\s*[\\r\\n]+(.+?)[\\r\\n]+---\\s*[\\r\\n]+(.*)$",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * Parsuje zawartość pliku .md na SubagentDefinition.
     *
     * @param content Zawartość pliku
     * @param sourcePath Ścieżka do pliku (dla metadanych)
     * @param scope Scope subagenta (PROJECT, USER, BUILTIN)
     * @return Sparsowana definicja
     * @throws SubagentParseException Jeśli format jest nieprawidłowy
     */
    fun parse(content: String, sourcePath: Path?, scope: SubagentScope): SubagentDefinition {
        // Usuń BOM jeśli jest
        val cleanContent = content.trimStart('\uFEFF')

        val match = frontmatterRegex.find(cleanContent)
            ?: throw SubagentParseException("Invalid format: missing YAML frontmatter. Expected ---\\n...\\n---\\n format")

        val yamlContent = match.groupValues[1]
        val systemPrompt = match.groupValues[2].trim()

        if (systemPrompt.isBlank()) {
            throw SubagentParseException("System prompt is empty (content after YAML frontmatter)")
        }

        val frontmatter: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            yaml.load(yamlContent) as? Map<String, Any?>
                ?: throw SubagentParseException("YAML frontmatter is not a valid map")
        } catch (e: Exception) {
            if (e is SubagentParseException) throw e
            throw SubagentParseException("Failed to parse YAML frontmatter: ${e.message}", e)
        }

        val name = frontmatter["name"] as? String
            ?: throw SubagentParseException("Missing required field: name")

        val description = frontmatter["description"] as? String
            ?: throw SubagentParseException("Missing required field: description")

        // Parsuj tools (może być string oddzielony przecinkami lub lista)
        val allowedTools = parseTools(frontmatter["tools"] ?: frontmatter["allowedTools"])
        val disallowedTools = parseTools(frontmatter["disallowedTools"])

        // Walidacja: nie można mieć obu naraz
        if (allowedTools != null && disallowedTools != null) {
            logger.warn { "Subagent '$name' has both allowedTools and disallowedTools - using allowedTools (whitelist)" }
        }

        return SubagentDefinition(
            name = name.lowercase().replace(" ", "-"),
            description = description,
            systemPrompt = systemPrompt,
            allowedTools = allowedTools,
            disallowedTools = if (allowedTools == null) disallowedTools else null,
            model = (frontmatter["model"] as? String) ?: "default",
            skills = parseStringList(frontmatter["skills"]),
            enabled = (frontmatter["enabled"] as? Boolean) ?: true,
            priority = parseIntSafe(frontmatter["priority"]) ?: 0,
            sourcePath = sourcePath,
            scope = scope,
            executionMode = parseExecutionMode(frontmatter["executionMode"]),
            maxSteps = parseIntSafe(frontmatter["maxSteps"]) ?: 10
        )
    }

    /**
     * Parsuje pole tools/allowedTools/disallowedTools.
     * Obsługuje:
     * - String oddzielony przecinkami: "read_file, grep_search"
     * - Lista YAML: [read_file, grep_search]
     * - null = inherit (brak ograniczeń)
     */
    private fun parseTools(value: Any?): List<String>? {
        return when (value) {
            null -> null
            is String -> {
                if (value.isBlank()) null
                else value.split(",").map { mapToolName(it.trim()) }.filter { it.isNotEmpty() }
            }
            is List<*> -> value.filterIsInstance<String>().map { mapToolName(it.trim()) }.filter { it.isNotEmpty() }
            else -> {
                logger.warn { "Unexpected tools format: ${value::class.simpleName}, ignoring" }
                null
            }
        }
    }

    /**
     * Mapuje nazwy narzędzi z formatu Claude Code na nazwy Refio.
     * Obsługuje oba formaty dla kompatybilności.
     */
    private fun mapToolName(claudeCodeName: String): String {
        return when (claudeCodeName.lowercase()) {
            // Claude Code names -> Refio names
            "read" -> "read_file"
            "grep" -> "grep_search"
            "glob" -> "file_search"
            "bash" -> "run_terminal_command"
            "edit" -> "code_editing"
            "write" -> "create_new_file"
            "multiedit", "multi_edit" -> "multi_edit"
            // Już poprawne nazwy Refio
            else -> claudeCodeName.lowercase()
        }
    }

    /**
     * Parsuje listę stringów (dla skills).
     */
    private fun parseStringList(value: Any?): List<String> {
        return when (value) {
            null -> emptyList()
            is String -> if (value.isBlank()) emptyList() else value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            is List<*> -> value.filterIsInstance<String>().filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    /**
     * Parsuje int bezpiecznie (obsługuje Number i String).
     */
    private fun parseIntSafe(value: Any?): Int? {
        return when (value) {
            null -> null
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    /**
     * Parsuje tryb wykonania.
     */
    private fun parseExecutionMode(value: Any?): SubagentExecutionMode {
        return when (value?.toString()?.lowercase()) {
            "multi_step", "multistep", "multi-step" -> SubagentExecutionMode.MULTI_STEP
            else -> SubagentExecutionMode.SINGLE_SHOT
        }
    }

    companion object {
        /**
         * Odwrotne mapowanie: Refio -> Claude Code (dla eksportu).
         */
        fun refioToClaudeCodeToolName(refioName: String): String {
            return when (refioName.lowercase()) {
                "read_file" -> "Read"
                "grep_search" -> "Grep"
                "file_search" -> "Glob"
                "run_terminal_command" -> "Bash"
                "code_editing" -> "Edit"
                "create_new_file" -> "Write"
                "multi_edit" -> "MultiEdit"
                else -> refioName
            }
        }
    }
}
