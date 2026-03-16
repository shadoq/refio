package pl.jclab.refio.services.session

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ToolCallContentSanitizer {
    private val gson = Gson()
    private val toolCallPatterns = listOf(
        Regex("""(?:\r?\n)?TOOL_CALL:\s*\w+\s*(?:\r?\n)?ARGUMENTS:\s*\{[\s\S]*?\}(?:\r?\n)?""", RegexOption.MULTILINE),
        Regex("""(?:\r?\n)?Tool calls:\s*(?:\r?\n)?TOOL_CALL:[\s\S]*?(?:\r?\n){2,}|(?:\r?\n)?Tool calls:\s*(?:\r?\n)?TOOL_CALL:[\s\S]*$""", RegexOption.MULTILINE),
        Regex("""(?:\r?\n)?<tool_call>[\s\S]*?</tool_call>(?:\r?\n)?"""),
        Regex("""(?:\r?\n)?```\s*(?:tool|tool_call)[\s\S]*?```(?:\r?\n)?""")
    )

    fun sanitize(content: String): String {
        extractAssistantJsonTextPayload(content)?.let { return it }
        if (isPlanJson(content)) return content

        var result = content
        toolCallPatterns.forEach { pattern ->
            result = result.replace(pattern, "\n")
        }
        return result
            .replace(Regex("""(?:\r?\n){2,}"""), "\n")
            .trim()
    }

    fun isToolProtocolBoundary(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        return candidate.contains("TOOL_CALL:") ||
            candidate.contains("ARGUMENTS:") ||
            candidate.contains("Tool calls:") ||
            candidate.contains("<tool_call>") ||
            candidate.contains("```tool") ||
            candidate.contains("```tool_call")
    }

    private fun extractAssistantJsonTextPayload(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        return try {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(trimmed) as? JsonObject ?: return null

            if (root.containsKey("plan") || root.containsKey("subtasks")) {
                return content
            }

            val contentField = (root["content"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (!contentField.isNullOrBlank()) {
                return contentField
            }

            val responseField = (root["response"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (!responseField.isNullOrBlank()) {
                return responseField
            }

            if (root["actions"] is JsonArray) {
                return ""
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isPlanJson(content: String): Boolean {
        if (!content.trim().startsWith("{")) return false
        return try {
            val json = gson.fromJson(
                content,
                TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return false

            json.containsKey("plan") || json.containsKey("subtasks")
        } catch (_: Exception) {
            false
        }
    }
}
