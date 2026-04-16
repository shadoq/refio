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
import pl.jclab.refio.core.logging.dualLogger
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

    data class JsonEnvelopeInspection(
        val hasJsonEnvelope: Boolean,
        val isComplete: Boolean,
        val isFenced: Boolean,
        val firstBraceIndex: Int,
        val closingBraceIndex: Int
    )

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
                    return unwrapNestedTextPayload(contentElement.content)
                }
                val responseElement = jsonElement["response"]
                if (responseElement is JsonPrimitive && responseElement.isString) {
                    return unwrapNestedTextPayload(responseElement.content)
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
     * Unwrap nested JSON text payload from model response.
     *
     * Some models wrap their response in a double JSON envelope, e.g.:
     * {"actions":[],"response":"{\n  \"answer\": \"# Report\\n...\"}" }
     *
     * This method detects when extracted text is itself a JSON object with
     * a text payload field (answer, content, response, result, output, text)
     * and unwraps it recursively (up to 2 levels).
     */
    private fun unwrapNestedTextPayload(text: String, depth: Int = 0): String {
        if (depth > 1) return text
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return text

        return try {
            val inner = json.parseToJsonElement(trimmed)
            if (inner !is JsonObject) return text

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

    fun inspectJsonEnvelope(content: String): JsonEnvelopeInspection {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return JsonEnvelopeInspection(
                hasJsonEnvelope = false,
                isComplete = false,
                isFenced = false,
                firstBraceIndex = -1,
                closingBraceIndex = -1
            )
        }

        val isFenced = Regex("""^```(?:json)?\s*""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        val firstBraceIndex = trimmed.indexOf('{')
        if (firstBraceIndex == -1) {
            return JsonEnvelopeInspection(
                hasJsonEnvelope = false,
                isComplete = false,
                isFenced = isFenced,
                firstBraceIndex = -1,
                closingBraceIndex = -1
            )
        }

        var closingBraceIndex = findMatchingBrace(trimmed, firstBraceIndex)
        val recoveredFromClosedFence = if (closingBraceIndex == -1 && isFenced) {
            val fencedBody = extractClosedFencedJsonBody(trimmed)
            if (fencedBody != null && canParseJsonCandidate(fencedBody)) {
                closingBraceIndex = trimmed.lastIndexOf('}')
                true
            } else {
                false
            }
        } else {
            false
        }
        return JsonEnvelopeInspection(
            hasJsonEnvelope = true,
            isComplete = closingBraceIndex != -1 || recoveredFromClosedFence,
            isFenced = isFenced,
            firstBraceIndex = firstBraceIndex,
            closingBraceIndex = closingBraceIndex
        )
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
                val repairedJson = TurnJsonUtils.repairMalformedJson(jsonString)
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
        val closedFencedBody = extractClosedFencedJsonBody(trimmed)

        if (closedFencedBody != null) {
            try {
                json.parseToJsonElement(closedFencedBody)
                logger.info { "[EXTRACT_JSON] Strategy 0: Extracted valid JSON from fenced block body" }
                return closedFencedBody
            } catch (e: Exception) {
                logger.debug { "[EXTRACT_JSON] Strategy 0 failed: ${e.message}" }
            }

            val repairedFencedBody = TurnJsonUtils.repairMalformedJson(closedFencedBody)
            try {
                json.parseToJsonElement(repairedFencedBody)
                logger.info { "[EXTRACT_JSON] Strategy 0b: Repaired malformed fenced JSON body" }
                return repairedFencedBody
            } catch (e: Exception) {
                logger.debug { "[EXTRACT_JSON] Strategy 0b failed: ${e.message}" }
            }
        }

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
            } else {
                val repairedCandidate = TurnJsonUtils.repairMalformedJson(trimmed.substring(firstBraceIndex))
                try {
                    json.parseToJsonElement(repairedCandidate)
                    logger.info { "[EXTRACT_JSON] Strategy 2b: Repaired unmatched-brace JSON fragment" }
                    return repairedCandidate
                } catch (e: Exception) {
                    logger.debug { "[EXTRACT_JSON] Strategy 2b repair failed: ${e.message}" }
                }
            }
        }

        // Strategy 3: JSON in code block ```json ... ```
        val codeBlockStartPattern = Regex("""```(?:json)?\s*\n""", RegexOption.IGNORE_CASE)
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
                } else {
                    val repairedCandidate = TurnJsonUtils.repairMalformedJson(trimmed.substring(jsonStartInBlock))
                    try {
                        json.parseToJsonElement(repairedCandidate)
                        logger.info { "[EXTRACT_JSON] Strategy 3b: Repaired incomplete fenced JSON block" }
                        return repairedCandidate
                    } catch (e: Exception) {
                        logger.debug { "[EXTRACT_JSON] Strategy 3b repair failed: ${e.message}" }
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

        // Strategy 5: Try repairing malformed strings/escapes in the whole envelope
        try {
            val repaired = TurnJsonUtils.repairMalformedJson(trimmed)
            json.parseToJsonElement(repaired)
            logger.info { "[EXTRACT_JSON] Strategy 5: Repaired malformed JSON envelope" }
            return repaired
        } catch (e: Exception) {
            logger.debug { "[EXTRACT_JSON] Strategy 5 (repair) failed: ${e.message}" }
        }

        logger.warn { "[EXTRACT_JSON] All strategies failed, content preview: ${trimmed.take(200)}..." }
        return null
    }

    private fun extractClosedFencedJsonBody(content: String): String? {
        val startMatch = Regex("""^```(?:json)?\s*""", RegexOption.IGNORE_CASE).find(content) ?: return null
        val bodyStart = startMatch.range.last + 1
        val lastFenceIndex = content.lastIndexOf("```")
        if (lastFenceIndex <= bodyStart) {
            return null
        }
        return content.substring(bodyStart, lastFenceIndex).trim()
    }

    private fun canParseJsonCandidate(candidate: String): Boolean {
        return try {
            json.parseToJsonElement(candidate)
            true
        } catch (_: Exception) {
            try {
                json.parseToJsonElement(TurnJsonUtils.repairMalformedJson(candidate))
                true
            } catch (_: Exception) {
                false
            }
        }
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
                val message = toolCall.error ?: buildProfileBlockedError(toolCall.name, profileOverrides)
                toolCall.copy(error = message)
            }
        }
    }

    /** Mirror of [pl.jclab.refio.core.services.turn.TurnToolExecutor.buildProfileBlockedError]. */
    private fun buildProfileBlockedError(
        toolName: String,
        profileOverrides: TurnProfileOverrides,
    ): String {
        val allowed = profileOverrides.allowedTools?.takeIf { it.isNotEmpty() }
        val disallowed = profileOverrides.disallowedTools?.takeIf { it.isNotEmpty() }
        val scope = profileOverrides.subagentName?.let { "subagent '$it'" } ?: "current run profile"
        val details = when {
            allowed != null -> "Your available tools are: ${allowed.joinToString(", ")}. Pick one of these or produce a final response."
            disallowed != null -> "This tool is on the blocklist for this profile (${disallowed.joinToString(", ")}). Use a different approach."
            else -> "Check the <available_tools> section and use only tools listed there."
        }
        return "Tool '$toolName' is not available to the $scope. $details"
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
