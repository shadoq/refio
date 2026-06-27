package pl.jclab.refio.core.session

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ToolCallContentSanitizer {
    private val gson = Gson()
    private val lenientJson = Json { ignoreUnknownKeys = true }
    private val toolCallPatterns = listOf(
        Regex("""(?:\r?\n)?TOOL_CALL:\s*\w+\s*(?:\r?\n)?ARGUMENTS:\s*\{[\s\S]*?\}(?:\r?\n)?""", RegexOption.MULTILINE),
        Regex("""(?:\r?\n)?Tool calls:\s*(?:\r?\n)?TOOL_CALL:[\s\S]*?(?:\r?\n){2,}|(?:\r?\n)?Tool calls:\s*(?:\r?\n)?TOOL_CALL:[\s\S]*$""", RegexOption.MULTILINE),
        Regex("""(?:\r?\n)?<tool_call>[\s\S]*?</tool_call>(?:\r?\n)?"""),
        Regex("""(?:\r?\n)?```\s*(?:tool|tool_call)[\s\S]*?```(?:\r?\n)?""")
    )

    fun sanitize(content: String): String {
        // Models without native function-calling sometimes wrap the real payload in an ad-hoc
        // tool-call envelope: a leading "[" / "[TOOL]" marker before a {response,actions} object
        // (the calls are already lifted into tool_calls_json upstream). Peel that off first so the
        // {...} payload extraction below can reach the actual answer text.
        val deprefixed = stripLeadingToolMarker(content)

        extractAssistantJsonTextPayload(deprefixed)?.let { return it }
        if (isPlanJson(deprefixed)) return deprefixed

        var result = deprefixed
        toolCallPatterns.forEach { pattern ->
            result = result.replace(pattern, "\n")
        }
        result = result
            .replace(Regex("""(?:\r?\n){2,}"""), "\n")
            .trim()

        // A residue of nothing but JSON structural punctuation (e.g. a lone "[" left after the
        // tool-call object was lifted into tool_calls_json) is not user-facing text — hide it so it
        // does not render as a stray bubble.
        return if (isStructuralResidueOnly(result)) "" else result
    }

    private val leadingToolMarker = Regex("""^\s*\[?\s*\[(?:TOOL|TOOL_CALL)\]\s*""", RegexOption.IGNORE_CASE)

    private fun stripLeadingToolMarker(content: String): String {
        val stripped = content.replaceFirst(leadingToolMarker, "")
        if (stripped == content) {
            return content
        }
        // Only treat it as a wrapper if a JSON object payload remains — don't eat real prose that
        // merely happens to open with a "[TOOL]" token.
        val candidate = stripped.trim().removeSuffix("]").trim()
        return if (candidate.startsWith("{")) candidate else content
    }

    private fun isStructuralResidueOnly(text: String): Boolean {
        if (text.isEmpty()) {
            return false
        }
        return text.all { it == '[' || it == ']' || it == '{' || it == '}' || it == ',' || it.isWhitespace() }
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
            val root = lenientJson.parseToJsonElement(trimmed) as? JsonObject ?: return null

            if (root.containsKey("plan") || root.containsKey("subtasks")) {
                return content
            }

            val contentField = (root["content"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (!contentField.isNullOrBlank()) {
                return unwrapNestedTextPayload(contentField)
            }

            val responseField = (root["response"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (!responseField.isNullOrBlank()) {
                return unwrapNestedTextPayload(responseField)
            }

            if (root["actions"] is JsonArray) {
                return ""
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Unwrap nested JSON text payload. Models sometimes double-wrap responses:
     * {"response": "{\"answer\": \"actual content\"}"}
     */
    fun unwrapNestedPayload(text: String): String = unwrapNestedTextPayload(text)

    private fun unwrapNestedTextPayload(text: String, depth: Int = 0): String {
        if (depth > 1) return text
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return text

        return try {
            val inner = lenientJson.parseToJsonElement(trimmed) as? JsonObject ?: return text
            val payloadKeys = listOf("answer", "content", "response", "result", "output", "text")
            for (key in payloadKeys) {
                val field = inner[key]
                if (field is JsonPrimitive && field.isString && field.content.isNotBlank()) {
                    return unwrapNestedTextPayload(field.content, depth + 1)
                }
            }
            text
        } catch (_: Exception) {
            text
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
