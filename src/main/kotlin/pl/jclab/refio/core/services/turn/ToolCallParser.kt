package pl.jclab.refio.core.services.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.llm.JsonExtractor
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.services.logging.dualLogger
import java.util.UUID

private val logger = dualLogger("ToolCallParser")

/**
 * Parser for tool calls from LLM JSON response.
 *
 * LLM returns JSON with "actions" key containing tool calls.
 * Parser handles:
 * - Actions array format: {"actions": [{"tool": "read_file", "args": {...}}]}
 * - Subtasks array format (PLAN mode): {"subtasks": [...]}
 * - Malformed JSON (repair + recovery)
 * - Legacy format (tool_calls with code)
 * - Filtering tools by profile/permissions
 *
 * Stateless - same input always produces same output.
 */
class ToolCallParser(
    private val toolRegistry: ToolRegistry,
    private val toolPermissionsService: pl.jclab.refio.core.services.ToolPermissionsService?,
    private val getJsonThinkingXmlTags: (String) -> List<String> = { emptyList() }
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val toolArgumentKeys = listOf("arguments", "args", "tool_args", "toolArgs", "parameters", "params")

    /**
     * Main entry point - parses LLM content into ToolCallData list.
     */
    fun extractToolCalls(
        content: String,
        mode: TaskMode,
        profileOverrides: TurnProfileOverrides? = null
    ): List<ToolCallData> {
        // Try JSON format first (preferred)
        val jsonToolCalls = extractToolCallsFromJson(content, mode)
        if (jsonToolCalls.isNotEmpty()) {
            logger.info { "[TOOL_CALLS] Extracted ${jsonToolCalls.size} tool calls from JSON format" }
            return filterToolCallsByProfile(jsonToolCalls, profileOverrides)
        }

        // Fallback to legacy TOOL_CALL format
        val legacyToolCalls = extractToolCallsLegacy(content)
        if (legacyToolCalls.isNotEmpty()) {
            logger.info { "[TOOL_CALLS] Extracted ${legacyToolCalls.size} tool calls from legacy format" }
        }
        return filterToolCallsByProfile(legacyToolCalls, profileOverrides)
    }

    /**
     * Preprocess content by removing configured XML thinking sections.
     */
    fun preprocessContent(content: String, taskId: String): String {
        if (content.isBlank()) return content

        val tags = getJsonThinkingXmlTags(taskId)
        if (tags.isEmpty()) return content

        var sanitized = content
        var removedAny = false

        for (tag in tags) {
            val escapedTag = Regex.escape(tag)
            val regex = Regex("(?is)<$escapedTag\\b[^>]*>.*?</$escapedTag>")
            val updated = regex.replace(sanitized, "")
            if (updated != sanitized) {
                removedAny = true
                sanitized = updated
            }
        }

        if (removedAny) {
            logger.info {
                "[TOOL_CALL_JSON] Stripped configured XML thinking sections before JSON extraction " +
                    "(tags=${tags.joinToString(",")}, before=${content.length}, after=${sanitized.length})"
            }
        }

        return sanitized.trim()
    }

    /**
     * Check if content looks like tool calls but is malformed.
     */
    fun shouldRequestRetry(content: String, mode: TaskMode): Boolean {
        if (mode == TaskMode.CHAT) return false

        val jsonString = extractJsonFromContent(content) ?: return false
        return try {
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? JsonObject ?: return false
            if (obj["error"] != null) return false

            // Check if JSON has NON-EMPTY tool arrays that failed to parse
            val actionsArray = obj["actions"] as? JsonArray
            val toolCallsArray = obj["tool_calls"] as? JsonArray
            val stepsArray = obj["steps"] as? JsonArray
            val subtasksArray = obj["subtasks"] as? JsonArray

            val hasNonEmptyToolArrays =
                (actionsArray?.isNotEmpty() == true) ||
                (toolCallsArray?.isNotEmpty() == true) ||
                (stepsArray?.isNotEmpty() == true) ||
                (subtasksArray?.isNotEmpty() == true)

            hasNonEmptyToolArrays
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract text response from JSON (for display).
     */
    fun extractTextResponse(content: String): String {
        val jsonString = extractJsonFromContent(content) ?: return content

        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            if (jsonElement is JsonObject) {
                // Plan JSON should be preserved for dedicated plan bubble rendering.
                val isPlanJson = jsonElement.containsKey("plan") || jsonElement.containsKey("subtasks")
                if (isPlanJson) {
                    return content // Return full JSON for plan bubble rendering
                }

                // Prefer explicit human-facing payload fields.
                val contentElement = jsonElement["content"]
                if (contentElement is JsonPrimitive && contentElement.isString) {
                    return contentElement.content
                }
                val responseElement = jsonElement["response"]
                if (responseElement is JsonPrimitive && responseElement.isString) {
                    return responseElement.content
                }

                // JSON with actions and no textual payload
                if (jsonElement["actions"] is JsonArray) {
                    val actions = jsonElement["actions"] as JsonArray
                    if (actions.isEmpty()) {
                        "Task completed. No further actions required."
                    } else {
                        ""
                    }
                } else {
                    content // Not our JSON envelope format
                }
            } else {
                content
            }
        } catch (e: Exception) {
            content // Failed to parse, return original
        }
    }

    /**
     * Check if content has explicit empty actions array.
     */
    fun hasExplicitEmptyActionsArray(content: String): Boolean {
        return try {
            val jsonString = extractJsonFromContent(content) ?: return false
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? JsonObject ?: return false
            val actionsArray = obj["actions"] as? JsonArray
            actionsArray != null && actionsArray.isEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract assistant intent (IMPLEMENTATION/ANALYSIS/UNKNOWN).
     */
    fun extractAssistantIntent(content: String): TurnGuardrails.AssistantIntent {
        return try {
            val jsonString = extractJsonFromContent(content) ?: return TurnGuardrails.AssistantIntent.UNKNOWN
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? JsonObject ?: return TurnGuardrails.AssistantIntent.UNKNOWN

            val rawIntent = (obj["intent"] as? JsonPrimitive)?.content
                ?: (obj["task_intent"] as? JsonPrimitive)?.content
                ?: return TurnGuardrails.AssistantIntent.UNKNOWN

            when (rawIntent.trim().lowercase()) {
                "implementation",
                "implement" -> TurnGuardrails.AssistantIntent.IMPLEMENTATION
                "analysis",
                "analyze" -> TurnGuardrails.AssistantIntent.ANALYSIS
                else -> TurnGuardrails.AssistantIntent.UNKNOWN
            }
        } catch (e: Exception) {
            TurnGuardrails.AssistantIntent.UNKNOWN
        }
    }

    /**
     * Check if content declares NO_CHANGES_NEEDED.
     */
    fun declaresNoChangesNeeded(content: String): Boolean {
        val marker = "NO_CHANGES_NEEDED"
        val upper = content.uppercase()
        if (marker in upper) return true

        return try {
            val jsonString = extractJsonFromContent(content) ?: return false
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? JsonObject ?: return false
            val responseText = (obj["response"] as? JsonPrimitive)?.content.orEmpty()
            val thinkingText = (obj["thinking"] as? JsonPrimitive)?.content.orEmpty()
            marker in responseText.uppercase() || marker in thinkingText.uppercase()
        } catch (e: Exception) {
            false
        }
    }

    // ===== Private methods =====

    private fun extractToolCallsFromJson(content: String, mode: TaskMode): List<ToolCallData> {
        logger.info { "[TOOL_CALL_JSON] Attempting to extract tool calls, content length=${content.length}, starts with '{': ${content.trim().startsWith("{")}" }

        val jsonString = extractJsonWithStrategies(content)
        if (jsonString == null) {
            logger.warn { "[TOOL_CALL_JSON] No JSON found in content (length=${content.length})" }
            return emptyList()
        }

        logger.info { "[TOOL_CALL_JSON] Extracted JSON (length=${jsonString.length}): ${jsonString.take(200)}..." }

        val jsonElement = try {
            json.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            logger.warn { "[TOOL_CALL_JSON_PARSE_FAILED] kotlinx.serialization failed: ${e.message}" }

            // Fallback: Try JsonExtractor with Gson (more lenient)
            try {
                val parsedMap = JsonExtractor.extractAndParse(jsonString)
                logger.info { "[TOOL_CALL_JSON] JsonExtractor (Gson) successfully parsed JSON" }
                TurnJsonUtils.parseMapToJsonElement(parsedMap)
            } catch (e2: Exception) {
                logger.warn { "[TOOL_CALL_JSON_PARSE_FAILED] JsonExtractor also failed: ${e2.message}" }

                // Last resort: Try repair and retry
                val repairedJson = TurnJsonUtils.repairInvalidJsonEscapes(jsonString)
                try {
                    json.parseToJsonElement(repairedJson)
                } catch (e3: Exception) {
                    logger.error { "[TOOL_CALL_JSON_PARSE_FAILED] All parsing strategies failed" }
                    logger.debug { "[TOOL_CALL_JSON_CONTENT] Original: $jsonString" }
                    logger.debug { "[TOOL_CALL_JSON_CONTENT] Repaired: $repairedJson" }
                    return emptyList()
                }
            }
        }

        return when (jsonElement) {
            is JsonObject -> {
                val actionsElement = jsonElement["actions"] as? JsonArray
                val toolCallsElement = jsonElement["tool_calls"] as? JsonArray
                val stepsElement = jsonElement["steps"] as? JsonArray
                val subtasksElement = jsonElement["subtasks"] as? JsonArray

                val toolCalls = when {
                    actionsElement != null -> extractToolCallsFromActionsArray(actionsElement)
                    toolCallsElement != null -> extractToolCallsFromActionsArray(toolCallsElement)
                    stepsElement != null -> extractToolCallsFromActionsArray(stepsElement)
                    subtasksElement != null -> extractToolCallsFromSubtasksArray(subtasksElement)
                    else -> emptyList()
                }

                if (toolCalls.isEmpty()) {
                    logger.debug { "[TOOL_CALL_JSON] No tool calls extracted (mode=$mode)" }
                }

                toolCalls
            }
            is JsonArray -> extractToolCallsFromActionsArray(jsonElement)
            else -> {
                logger.debug { "[TOOL_CALL_JSON] Unsupported JSON root: ${jsonElement::class.simpleName}" }
                emptyList()
            }
        }
    }

    private fun extractToolCallsFromActionsArray(actionsElement: JsonArray): List<ToolCallData> {
        if (actionsElement.isEmpty()) {
            logger.debug { "[TOOL_CALL_JSON] Empty actions array" }
            return emptyList()
        }

        val toolCalls = mutableListOf<ToolCallData>()
        for (actionElement in actionsElement) {
            val actionObj = actionElement as? JsonObject ?: continue

            val rawToolName = extractToolName(actionObj) ?: continue
            val recovered = recoverMalformedToolCall(rawToolName, actionObj)
            val toolName = recovered?.first ?: rawToolName
            val arguments = recovered?.second ?: extractArgumentsFromAction(actionObj)

            toolCalls.add(
                ToolCallData(
                    id = UUID.randomUUID().toString(),
                    name = toolName,
                    arguments = arguments
                )
            )
            if (recovered != null) {
                logger.warn {
                    "[TOOL_CALL_RECOVERED] '$rawToolName' -> '$toolName' (argsLength=${arguments.length})"
                }
            }
            logger.info { "[TOOL_CALL_JSON] Parsed: name=$toolName, argsLength=${arguments.length}" }
        }

        return toolCalls
    }

    private fun extractToolCallsFromSubtasksArray(subtasksElement: JsonArray): List<ToolCallData> {
        if (subtasksElement.isEmpty()) {
            logger.debug { "[TOOL_CALL_JSON] Empty subtasks array" }
            return emptyList()
        }

        val toolCalls = mutableListOf<ToolCallData>()
        for (subtaskElement in subtasksElement) {
            val subtaskObj = subtaskElement as? JsonObject ?: continue
            val toolName = (subtaskObj["kind"] as? JsonPrimitive)?.content
                ?: (subtaskObj["tool"] as? JsonPrimitive)?.content
                ?: continue
            val arguments = extractArgumentsFromKeys(subtaskObj, listOf("tool_args", "arguments", "args", "params"))

            toolCalls.add(
                ToolCallData(
                    id = UUID.randomUUID().toString(),
                    name = toolName,
                    arguments = arguments
                )
            )
            logger.info { "[TOOL_CALL_JSON] Parsed subtask: name=$toolName, argsLength=${arguments.length}" }
        }

        return toolCalls
    }

    private fun extractToolName(actionElement: JsonObject): String? {
        val candidates = listOf("tool", "name", "kind")
        for (key in candidates) {
            val value = actionElement[key] as? JsonPrimitive
            if (value != null) {
                val content = value.content
                if (content.isNotBlank()) return content
            }
        }
        return null
    }

    private fun extractArgumentsFromAction(actionElement: JsonObject): String {
        return extractArgumentsFromKeys(actionElement, toolArgumentKeys)
    }

    private fun extractArgumentsFromKeys(element: JsonObject, keys: List<String>): String {
        val raw = keys.firstNotNullOfOrNull { key -> element[key] }
        return normalizeArguments(raw)
    }

    private fun recoverMalformedToolCall(toolName: String, actionElement: JsonObject): Pair<String, String>? {
        val rawArgs = extractArgumentsElement(actionElement) as? JsonObject ?: return null
        val nestedToolName = extractToolName(rawArgs)
        val nestedArgs = extractArgumentsElement(rawArgs)
        val nestedArgsObject = nestedArgs as? JsonObject

        if (nestedToolName != null && nestedArgsObject != null) {
            val shouldReassignTool = toolName.equals("run", ignoreCase = true) ||
                nestedToolName.equals(toolName, ignoreCase = true) ||
                (toolName.equals("invoke_subagent", ignoreCase = true) && !rawArgs.containsKey("subagent_name"))

            if (shouldReassignTool) {
                return nestedToolName to nestedArgsObject.toString()
            }
        }

        if (nestedArgsObject != null && rawArgs.keys.all { key ->
                key in toolArgumentKeys || key in setOf("tool", "name", "kind")
            }) {
            return toolName to nestedArgsObject.toString()
        }

        return null
    }

    private fun extractArgumentsElement(element: JsonObject): JsonElement? {
        return toolArgumentKeys.firstNotNullOfOrNull { key -> element[key] }
    }

    private fun normalizeArguments(element: JsonElement?): String {
        return when (element) {
            is JsonObject -> element.toString()
            is JsonPrimitive -> {
                if (element.isString) {
                    element.content
                } else {
                    element.toString()
                }
            }
            is JsonArray -> element.toString()
            else -> "{}"
        }
    }

    private fun extractJsonWithStrategies(content: String): String? {
        val trimmed = content.trim()

        // Strategy 1: Try parsing entire content as JSON directly
        if (trimmed.startsWith("{")) {
            try {
                json.parseToJsonElement(trimmed)
                logger.info { "[EXTRACT_JSON] Strategy 1: Entire content is valid JSON" }
                return trimmed
            } catch (e: Exception) {
                logger.debug { "[EXTRACT_JSON] Strategy 1 failed: ${e.message}" }
            }
        }

        // Strategy 2: Find first { and match braces
        val firstBraceIndex = trimmed.indexOf('{')
        if (firstBraceIndex != -1) {
            val endIndex = findMatchingBrace(trimmed, firstBraceIndex)
            if (endIndex != -1) {
                val extracted = trimmed.substring(firstBraceIndex, endIndex + 1)
                try {
                    json.parseToJsonElement(extracted)
                    logger.info { "[EXTRACT_JSON] Strategy 2: Extracted via brace matching (length=${extracted.length})" }
                    return extracted
                } catch (e: Exception) {
                    logger.debug { "[EXTRACT_JSON] Strategy 2 extraction invalid: ${e.message}" }
                }
            }
        }

        // Strategy 3: JSON in code block ```json ... ```
        val codeBlockStartPattern = Regex("""```(?:json)?\s*\n""")
        val codeBlockMatch = codeBlockStartPattern.find(trimmed)
        if (codeBlockMatch != null) {
            val afterFence = codeBlockMatch.range.last + 1
            val jsonStartInBlock = trimmed.indexOf('{', afterFence)
            if (jsonStartInBlock != -1) {
                val endIndex = findMatchingBrace(trimmed, jsonStartInBlock)
                if (endIndex != -1) {
                    val afterJson = trimmed.substring(endIndex + 1).trim()
                    if (afterJson.startsWith("```")) {
                        val extracted = trimmed.substring(jsonStartInBlock, endIndex + 1)
                        try {
                            json.parseToJsonElement(extracted)
                            logger.info { "[EXTRACT_JSON] Strategy 3: Extracted from code block" }
                            return extracted
                        } catch (e: Exception) {
                            logger.debug { "[EXTRACT_JSON] Strategy 3 extraction invalid: ${e.message}" }
                        }
                    }
                }
            }
        }

        // Strategy 4: Try Gson as last resort
        try {
            val entireContent = trimmed
            com.google.gson.JsonParser.parseString(entireContent)
            logger.info { "[EXTRACT_JSON] Strategy 4: Gson successfully parsed entire content" }
            return entireContent
        } catch (e: Exception) {
            logger.debug { "[EXTRACT_JSON] Strategy 4 (Gson) failed: ${e.message}" }

            if (firstBraceIndex != -1) {
                for (endIndex in (firstBraceIndex + 100)..trimmed.length step 100) {
                    if (endIndex > trimmed.length) break
                    val candidate = trimmed.substring(firstBraceIndex, endIndex.coerceAtMost(trimmed.length))
                    try {
                        com.google.gson.JsonParser.parseString(candidate)
                        logger.info { "[EXTRACT_JSON] Strategy 4b: Gson found valid JSON at length ${candidate.length}" }
                        return candidate
                    } catch (e: Exception) {
                        // Continue trying
                    }
                }
            }
        }

        logger.warn { "[EXTRACT_JSON] All strategies failed, content preview: ${trimmed.take(200)}..." }
        return null
    }

    private fun extractJsonFromContent(content: String): String? {
        return extractJsonWithStrategies(content)
    }

    private fun findMatchingBrace(text: String, startIndex: Int): Int {
        var depth = 0
        var inString = false
        var escape = false

        for (i in startIndex until text.length) {
            val c = text[i]

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\' && inString) {
                escape = true
                continue
            }

            if (c == '"' && !escape) {
                inString = !inString
                continue
            }

            if (!inString) {
                when (c) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            logger.debug { "[FIND_BRACE] Found matching brace at position $i" }
                            return i
                        }
                    }
                }
            }
        }

        logger.warn { "[FIND_BRACE] No matching brace found starting at $startIndex, depth=$depth, inString=$inString" }
        return -1
    }

    private fun extractToolCallsLegacy(content: String): List<ToolCallData> {
        val toolCalls = mutableListOf<ToolCallData>()

        val toolCallPattern = Regex(
            """TOOL_CALL:\s*(\w+)\s*\nARGUMENTS:\s*(\{[\s\S]*?\})(?=\n(?:TOOL_CALL:|$)|$)""",
            RegexOption.MULTILINE
        )

        val matches = toolCallPattern.findAll(content)
        for (match in matches) {
            val toolName = match.groupValues[1].trim()
            val arguments = match.groupValues[2].trim()
            var validatedArguments = arguments

            try {
                json.parseToJsonElement(validatedArguments)
                toolCalls.add(
                    ToolCallData(
                        id = UUID.randomUUID().toString(),
                        name = toolName,
                        arguments = validatedArguments
                    )
                )
                logger.debug { "[TOOL_CALL_LEGACY] name=$toolName, args=$arguments" }
            } catch (e: Exception) {
                val repaired = TurnJsonUtils.attemptRepairJsonArguments(arguments)
                if (repaired != null) {
                    validatedArguments = repaired
                    toolCalls.add(
                        ToolCallData(
                            id = UUID.randomUUID().toString(),
                            name = toolName,
                            arguments = validatedArguments
                        )
                    )
                    logger.warn { "[TOOL_CALL_JSON_REPAIRED] name=$toolName, args=$validatedArguments" }
                    continue
                }

                logger.warn { "[TOOL_CALL_INVALID_JSON] name=$toolName, error=${e.message}\nJSON input: $arguments" }
                toolCalls.add(
                    ToolCallData(
                        id = UUID.randomUUID().toString(),
                        name = toolName,
                        arguments = arguments,
                        error = "Invalid JSON: ${e.message}. Ensure proper escaping (use \\\\ for backslash in regex patterns)."
                    )
                )
            }
        }

        return toolCalls
    }

    private fun filterToolCallsByProfile(
        toolCalls: List<ToolCallData>,
        profileOverrides: TurnProfileOverrides?
    ): List<ToolCallData> {
        if (profileOverrides == null) {
            return toolCalls
        }

        val blocked = toolCalls.filterNot { isToolAllowedByProfile(it.name, profileOverrides) }
        if (blocked.isNotEmpty()) {
            logger.warn {
                "[TOOL_FILTER] Blocked ${blocked.size} tool calls by profile '${profileOverrides.subagentName ?: "unknown"}': " +
                    blocked.joinToString(", ") { it.name }
            }
        }
        return toolCalls.map { toolCall ->
            if (isToolAllowedByProfile(toolCall.name, profileOverrides)) {
                toolCall
            } else {
                val message = toolCall.error
                    ?: "Tool '${toolCall.name}' is not allowed for current run profile"
                toolCall.copy(error = message)
            }
        }
    }

    private fun isToolAllowedByProfile(toolName: String, profileOverrides: TurnProfileOverrides): Boolean {
        val normalizedName = toolName.lowercase()
        val allowed = profileOverrides.allowedTools?.map { it.lowercase() }?.toSet()
        val disallowed = profileOverrides.disallowedTools?.map { it.lowercase() }?.toSet()

        if (allowed != null) {
            return normalizedName in allowed
        }
        if (disallowed != null) {
            return normalizedName !in disallowed
        }
        return true
    }
}
