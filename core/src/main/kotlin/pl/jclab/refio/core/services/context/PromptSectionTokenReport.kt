package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ContextService")

/**
 * Prompt-section token accounting extracted from ContextService.
 * Parses the XML-tagged sections of a generated LLM context prompt and
 * produces per-section token estimates for the UI breakdown.
 */
internal object PromptSectionTokenReport {

    /**
     * Parse XML-tagged sections from an LLM context prompt and calculate
     * per-section token estimates. Returns a map with UI-friendly keys
     * (e.g. "recent_work", "key_components") suitable for the color palette.
     */
    fun parsePromptSectionTokens(llmPrompt: String): Map<String, ContextSectionTokenInfo> {
        if (llmPrompt.isBlank()) {
            logger.debug { "[CONTEXT_TOKENS] Empty LLM prompt, skipping section token calculation" }
            return emptyMap()
        }

        // Section patterns that are expected in the generated prompt
        val sectionPatterns = listOf(
            "PROJECT_CONTEXT" to "project_overview",
            "PROJECT_INSTRUCTIONS" to "project_instructions",
            "CURRENT_TASK" to "current_task",
            "USER_REQUIREMENTS" to "user_requirements",
            "USER_PROVIDED_CONTEXT" to "user_context",
            "WORKING_MEMORY" to "working_memory",
            "MCP_RESOURCES" to "mcp_resources",
            "CONVERSATION_HISTORY" to "conversation",
            "RECENT_WORK" to "recent_work",
            "SUBTASKS_STATUS" to "subtasks",
            "KEY_COMPONENTS" to "key_components",
            "PROJECT_DEPENDENCIES" to "dependencies",
            "PROJECT_ARCHITECTURE" to "architecture",
            "FRAMEWORK_ANALYSIS" to "framework_analysis",
            "TYPESCRIPT_ANALYSIS" to "typescript_analysis",
            "HTML_ANALYSIS" to "html_analysis",
            "CSS_ANALYSIS" to "css_analysis",
            "PATTERNS" to "patterns",
            "NAVIGATION_MAP" to "navigation_map",
            "CODE_ANALYSIS" to "code_analysis"
        )

        val sectionNames = mapOf(
            "project_overview" to "Project Context",
            "project_instructions" to "Project Instructions",
            "current_task" to "Current Task",
            "user_requirements" to "User Requirements",
            "user_context" to "User Context",
            "working_memory" to "Working Memory",
            "mcp_resources" to "MCP Resources",
            "conversation" to "Conversation History",
            "recent_work" to "Recent Work",
            "subtasks" to "Subtasks",
            "key_components" to "Key Components",
            "dependencies" to "Dependencies",
            "architecture" to "Architecture",
            "framework_analysis" to "Framework Analysis",
            "typescript_analysis" to "TypeScript Analysis",
            "html_analysis" to "HTML Analysis",
            "css_analysis" to "CSS Analysis",
            "patterns" to "Patterns",
            "navigation_map" to "Navigation Map",
            "code_analysis" to "Code Analysis"
        )

        // Parse explicit tagged sections from the final generated prompt.
        // Robust to truncated sections where closing tag was cut by token budget.
        // IMPORTANT: parse each section independently (from the whole prompt),
        // because prompt section order is not guaranteed to match sectionPatterns order.
        val parsedContents = mutableMapOf<String, Pair<String, Boolean>>() // key -> (content, hasClosingTag)

        for ((tag, key) in sectionPatterns) {
            val openTag = "<$tag>"
            val closeTag = "</$tag>"

            val openIndex = findTagAtLineStart(llmPrompt, openTag, 0)
            if (openIndex == -1) continue

            val contentStart = openIndex + openTag.length
            val closeIndex = findTagAtLineStart(llmPrompt, closeTag, contentStart)
            val nextSectionIndex = findNextSectionStart(llmPrompt, sectionPatterns, contentStart)

            val hasClosingTag = closeIndex != -1 && (nextSectionIndex == null || closeIndex <= nextSectionIndex)
            val contentEnd = when {
                hasClosingTag -> closeIndex
                nextSectionIndex != null -> nextSectionIndex
                else -> llmPrompt.length
            }

            if (contentEnd < contentStart) continue

            val content = llmPrompt.substring(contentStart, contentEnd)
            parsedContents[key] = content to hasClosingTag
        }

        val totalPromptChars = llmPrompt.length.coerceAtLeast(1)
        val result = mutableMapOf<String, ContextSectionTokenInfo>()

        // Process parsed sections only (no fallback estimation).
        for ((key, parsed) in parsedContents) {
            val (content, hasClosingTag) = parsed
            val tagName = sectionPatterns.firstOrNull { it.second == key }?.first ?: key
            val openTag = "<$tagName>"
            val closeTag = "</$tagName>"
            val sectionChars = content.length + openTag.length + if (hasClosingTag) closeTag.length else 0
            val tokens = (sectionChars / 4).coerceAtLeast(1)

            result[key] = ContextSectionTokenInfo(
                name = sectionNames[key] ?: key,
                tokens = tokens,
                chars = sectionChars,
                percentage = (sectionChars.toDouble() / totalPromptChars * 100)
            )
        }

        return result
    }

    private fun findNextSectionStart(
        prompt: String,
        sectionPatterns: List<Pair<String, String>>,
        fromIndex: Int
    ): Int? {
        var nextIndex: Int? = null
        for ((tag, _) in sectionPatterns) {
            val candidate = findTagAtLineStart(prompt, "<$tag>", fromIndex)
            if (candidate != -1 && (nextIndex == null || candidate < nextIndex)) {
                nextIndex = candidate
            }
        }
        return nextIndex
    }

    private fun findTagAtLineStart(prompt: String, tag: String, fromIndex: Int): Int {
        var index = prompt.indexOf(tag, fromIndex.coerceAtLeast(0))
        while (index != -1) {
            val lineStart = prompt.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            if (prompt.substring(lineStart, index).isBlank()) {
                return index
            }
            index = prompt.indexOf(tag, index + 1)
        }
        return -1
    }
}
