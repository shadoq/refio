package pl.jclab.refio.core.llm

import com.google.gson.JsonSyntaxException
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("JsonExtractor")

/**
 * Universal JSON extractor for parsing LLM responses that may contain JSON in various formats:
 * - Plain JSON
 * - JSON wrapped in markdown code blocks (```json ... ``` or ``` ... ```)
 * - JSON with text before/after
 * - JSON with extra whitespace
 * - Different response schemas that need normalization
 */
object JsonExtractor {

    /**
     * Extract and parse JSON from LLM response content.
     *
     * This method tries multiple strategies in order:
     * 0. Detect and extract content from full API response (Anthropic/OpenAI/OpenRouter format)
     * 1. Extract from ```json ... ``` markdown block
     * 2. Extract from ``` ... ``` generic code block
     * 3. Extract JSON object between first { and last }
     * 4. Parse as-is (plain JSON)
     *
     * @param content Raw content from LLM response (can be full API response or just content string)
     * @return Parsed Map representing the JSON object
     * @throws JsonParseException if all strategies fail
     */
    fun extractAndParse(content: String): Map<String, Any> {
        if (content.isBlank()) {
            throw JsonParseException("Empty content")
        }

        // Strategy 0: Check if this is a full API response and extract content first
        val actualContent = tryExtractContentFromApiResponse(content) ?: content

        // Strategy 1: Try extracting from ```json ... ``` block
        tryExtractFromMarkdownBlock(actualContent, "json")?.let { extracted ->
            tryParse(extracted, "markdown json block")?.let { return it }
        }

        // Strategy 2: Try extracting from ``` ... ``` generic block
        tryExtractFromMarkdownBlock(actualContent, null)?.let { extracted ->
            tryParse(extracted, "markdown generic block")?.let { return it }
        }

        // Strategy 3: Try extracting JSON object between first { and last }
        tryExtractJsonObject(actualContent)?.let { extracted ->
            tryParse(extracted, "extracted JSON object")?.let { return it }
        }

        // Strategy 4: Try parsing as-is
        tryParse(actualContent.trim(), "raw content")?.let { return it }

        // All strategies failed
        logger.error { "[JSON] All extraction strategies failed for content: ${actualContent.take(500)}" }
        throw JsonParseException(
            "Failed to extract valid JSON from response. Content preview: ${actualContent.take(200)}"
        )
    }

    /**
     * Extract and parse JSON for planning responses with automatic normalization.
     *
     * Different LLMs may use different schemas:
     * - Expected: { "plan": "...", "subtasks": [...] }
     * - Variant 1: { "steps": [...] } → normalized to subtasks
     * - Variant 2: { "execution_plan": { "step_1": {...}, "step_2": {...} } } → normalized to subtasks array
     * - Variant 3: { "tasks": [...] } → normalized to subtasks
     *
     * @param content Raw content from LLM response
     * @return Normalized Map with "plan" and "subtasks" keys
     * @throws JsonParseException if extraction or normalization fails
     */
    fun extractAndParsePlanningResponse(content: String): Map<String, Any> {
        // First extract raw JSON
        val rawJson = extractAndParse(content)

        // Normalize to expected structure
        return normalizePlanningResponse(rawJson)
    }

    /**
     * Normalize different planning response schemas to expected format.
     */
    private fun normalizePlanningResponse(rawJson: Map<String, Any>): Map<String, Any> {
        // Check if already in expected format
        if (rawJson.containsKey("subtasks")) {
            logger.debug { "[JSON] Response already in expected format with 'subtasks'" }
            return rawJson
        }

        // Helper function to find key case-insensitively
        fun findKey(vararg keys: String): Pair<String, Any?>? {
            for (key in keys) {
                rawJson.keys.find { it.equals(key, ignoreCase = true) }?.let { actualKey ->
                    return actualKey to rawJson[actualKey]
                }
            }
            return null
        }

        // Try to extract subtasks from various possible keys
        val subtasks = when {
            // Variant 1: { "steps": [...] }
            findKey("steps")?.let { (key, value) ->
                logger.info { "[JSON] Normalizing: found '$key' array, converting to 'subtasks'" }
                normalizeStepsArray(value)
            } != null -> findKey("steps")!!.second.let { normalizeStepsArray(it) }

            // Variant 2a: { "execution_plan": [...] } (array) - case insensitive
            findKey("execution_plan", "executionplan")?.let { (key, value) ->
                when (value) {
                    is List<*> -> {
                        logger.info { "[JSON] Normalizing: found '$key' array, converting to 'subtasks'" }
                        normalizeStepsArray(value)
                    }
                    is Map<*, *> -> {
                        logger.info { "[JSON] Normalizing: found '$key' object, converting to 'subtasks'" }
                        normalizeExecutionPlanObject(value)
                    }
                    else -> null
                }
            } != null -> findKey("execution_plan", "executionplan")!!.second.let {
                when (it) {
                    is List<*> -> normalizeStepsArray(it)
                    is Map<*, *> -> normalizeExecutionPlanObject(it)
                    else -> emptyList()
                }
            }

            // Variant 3: { "tasks": [...] }
            findKey("tasks")?.let { (key, value) ->
                logger.info { "[JSON] Normalizing: found '$key' array, converting to 'subtasks'" }
                normalizeTasksArray(value)
            } != null -> findKey("tasks")!!.second.let { normalizeTasksArray(it) }

            // Variant 4: Root array (no wrapping object)
            rawJson.containsKey("0") || rawJson.values.firstOrNull() is List<*> -> {
                logger.info { "[JSON] Normalizing: found root-level array, converting to 'subtasks'" }
                rawJson.values.firstOrNull() as? List<*> ?: emptyList<Any>()
            }

            // Variant 5: Nested structure { "plan": { "steps": [...] } }
            rawJson.containsKey("plan") && rawJson["plan"] is Map<*, *> -> {
                logger.info { "[JSON] Normalizing: found nested 'plan' object, checking for steps inside" }
                val planObj = rawJson["plan"] as Map<*, *>

                when {
                    planObj.containsKey("steps") -> {
                        logger.info { "[JSON] Found 'steps' inside 'plan' object" }
                        normalizeStepsArray(planObj["steps"])
                    }
                    planObj.containsKey("subtasks") -> {
                        logger.info { "[JSON] Found 'subtasks' inside 'plan' object" }
                        normalizeStepsArray(planObj["subtasks"])  // Use same normalizer as steps
                    }
                    planObj.containsKey("tasks") -> {
                        logger.info { "[JSON] Found 'tasks' inside 'plan' object" }
                        normalizeTasksArray(planObj["tasks"])
                    }
                    else -> {
                        logger.error { "[JSON] 'plan' object doesn't contain expected keys. Keys: ${planObj.keys}" }
                        throw JsonParseException(
                            "Nested 'plan' object found but missing 'steps', 'subtasks', or 'tasks'. " +
                            "Found keys: ${planObj.keys.joinToString(", ")}"
                        )
                    }
                }
            }

            else -> {
                logger.error { "[JSON] Unrecognized plan structure. Available keys: ${rawJson.keys}" }
                throw JsonParseException(
                    "Unrecognized planning response structure. Expected 'subtasks', 'steps', 'execution_plan', or 'tasks'. " +
                    "Found keys: ${rawJson.keys.joinToString(", ")}"
                )
            }
        }

        // Extract plan description if available
        val planDescription = when (val planValue = rawJson["plan"]) {
            is Map<*, *> -> planValue["description"]?.toString() ?: "Execution plan"
            is String -> planValue
            else -> rawJson["description"]?.toString()
                ?: rawJson["summary"]?.toString()
                ?: "Execution plan"
        }

        logger.info { "[JSON] Normalized to ${(subtasks as? List<*>)?.size ?: 0} subtasks" }

        return mapOf(
            "plan" to planDescription,
            "subtasks" to subtasks
        )
    }

    /**
     * Normalize steps array to subtasks format.
     * Input: [ { "description": "...", "files_to_read": [...], ... }, ... ]
     * Output: [ { "name": "...", "description": "...", "kind": "...", "tool_args": {...} }, ... ]
     */
    private fun normalizeStepsArray(steps: Any?): List<Map<String, Any>> {
        val stepsList = steps as? List<*> ?: return emptyList()

        return stepsList.mapIndexed { index, step ->
            val stepMap = step as? Map<*, *> ?: return@mapIndexed null

            // Extract description from various possible fields
            val description = stepMap["description"]?.toString()
                ?: stepMap["step"]?.toString()
                ?: stepMap["summary"]?.toString()
                ?: "Step ${index + 1}"

            // Extract name from various possible fields
            val name = stepMap["name"]?.toString()
                ?: stepMap["step"]?.toString()
                ?: stepMap["title"]?.toString()
                ?: description.take(50)

            // Try to infer tool kind from step data
            val kind = inferToolKind(stepMap)

            // Extract tool arguments
            val toolArgs = extractToolArgs(stepMap)

            mapOf(
                "name" to name,
                "description" to description,
                "kind" to kind,
                "tool_args" to toolArgs
            )
        }.filterNotNull()
    }

    /**
     * Normalize execution_plan object to subtasks format.
     * Input: { "step_1": { "action": "read_file", ... }, "step_2": {...} }
     * Output: [ { "name": "...", "description": "...", "kind": "...", "tool_args": {...} }, ... ]
     */
    private fun normalizeExecutionPlanObject(executionPlan: Map<*, *>?): List<Map<String, Any>> {
        if (executionPlan == null) return emptyList()

        return executionPlan.entries
            .sortedBy { it.key.toString() } // Sort by step_1, step_2, etc.
            .mapIndexed { index, entry ->
                val stepMap = entry.value as? Map<*, *> ?: return@mapIndexed null

                val action = stepMap["action"]?.toString() ?: "unknown"
                val description = stepMap["description"]?.toString() ?: "Step ${index + 1}"

                val kind = when (action) {
                    "read_file" -> "read_file"
                    "create_new_file" -> "create_new_file"
                    "advance_code_editing" -> "advance_code_editing"
                    "multi_line_editor" -> "multi_line_editor"
                    "code_editing" -> "code_editing"
                    "multi_edit" -> "multi_edit"
                    "grep_search" -> "grep_search"
                    "file_search" -> "file_search"
                    "run_terminal_command" -> "run_terminal_command"
                    "view_diff" -> "view_diff"
                    else -> "plan_step"
                }

                // Extract tool args from step data
                val toolArgs = (stepMap - "action" - "description").toMutableMap()

                mapOf(
                    "name" to (stepMap["name"]?.toString() ?: description.take(50)),
                    "description" to description,
                    "kind" to kind,
                    "tool_args" to toolArgs
                )
            }.filterNotNull()
    }

    /**
     * Normalize tasks array to subtasks format.
     */
    private fun normalizeTasksArray(tasks: Any?): List<Map<String, Any>> {
        // Same logic as steps array
        return normalizeStepsArray(tasks)
    }

    /**
     * Infer tool kind from step data.
     */
    private fun inferToolKind(stepMap: Map<*, *>): String {
        return when {
            // Explicit tool/kind/action fields
            stepMap.containsKey("action") -> stepMap["action"]?.toString() ?: "plan_step"
            stepMap.containsKey("tool") -> stepMap["tool"]?.toString() ?: "plan_step"
            stepMap.containsKey("kind") -> stepMap["kind"]?.toString() ?: "plan_step"

            // "tools" array - take first element
            stepMap.containsKey("tools") -> {
                val toolsList = stepMap["tools"] as? List<*>
                toolsList?.firstOrNull()?.toString() ?: "plan_step"
            }

            // Infer from other fields
            stepMap.containsKey("files_to_read") -> "read_file"
            stepMap.containsKey("changes_needed") -> "code_editing"
            else -> "plan_step"
        }
    }

    /**
     * Extract tool arguments from step data.
     */
    private fun extractToolArgs(stepMap: Map<*, *>): Map<String, Any> {
        val toolArgs = mutableMapOf<String, Any>()

        // Common direct mappings
        stepMap["path"]?.let { toolArgs["path"] = it }
        stepMap["file"]?.let { toolArgs["path"] = it }
        stepMap["file_path"]?.let { toolArgs["path"] = it }
        stepMap["file_name"]?.let { toolArgs["path"] = it }
        stepMap["pattern"]?.let { toolArgs["pattern"] = it }
        stepMap["search_pattern"]?.let { toolArgs["pattern"] = it }
        stepMap["content"]?.let { toolArgs["content"] = it }
        stepMap["old_string"]?.let { toolArgs["old_string"] = it }
        stepMap["new_string"]?.let { toolArgs["new_string"] = it }
        stepMap["command"]?.let { toolArgs["command"] = it }
        stepMap["file1"]?.let { toolArgs["file1"] = it }
        stepMap["file2"]?.let { toolArgs["file2"] = it }

        // Extract from nested "tool_args" if present
        (stepMap["tool_args"] as? Map<*, *>)?.let { nested ->
            toolArgs.putAll(nested.mapKeys { it.key.toString() }.mapValues { it.value ?: "" })
        }

        // Extract from nested "parameters" if present (common in some LLMs)
        (stepMap["parameters"] as? Map<*, *>)?.let { params ->
            toolArgs.putAll(params.mapKeys { it.key.toString() }.mapValues { it.value ?: "" })
        }

        // Extract from nested "args" if present
        (stepMap["args"] as? Map<*, *>)?.let { args ->
            toolArgs.putAll(args.mapKeys { it.key.toString() }.mapValues { it.value ?: "" })
        }

        // Extract from "template" if present
        (stepMap["template"] as? Map<*, *>)?.let { template ->
            toolArgs.putAll(template.mapKeys { it.key.toString() }.mapValues { it.value ?: "" })
        }

        return toolArgs
    }

    /**
     * Try to extract content from full API response.
     * Handles different provider formats:
     * - Anthropic: {"content":[{"type":"text","text":"..."}],...}
     * - OpenAI: {"choices":[{"message":{"content":"..."}}],...}
     * - OpenRouter: {"choices":[{"message":{"content":"..."}}],...}
     *
     * @param content Potentially full API response JSON
     * @return Extracted content string, or null if not a recognized API response format
     */
    private fun tryExtractContentFromApiResponse(content: String): String? {
        return try {
            // Try to parse as JSON first
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(content, Map::class.java) as? Map<String, Any> ?: return null

            // Strategy 1: Anthropic format - {"content":[{"type":"text","text":"..."}],...}
            (parsed["content"] as? List<*>)?.let { contentBlocks ->
                logger.debug { "[JSON] Detected Anthropic API response format" }
                val textContent = StringBuilder()
                for (block in contentBlocks) {
                    val blockMap = block as? Map<*, *> ?: continue
                    when (blockMap["type"]) {
                        "text" -> textContent.append(blockMap["text"]?.toString() ?: "")
                        // Ignore thinking blocks
                    }
                }
                if (textContent.isNotEmpty()) {
                    logger.info { "[JSON] Extracted content from Anthropic format: ${textContent.length} chars" }
                    return textContent.toString()
                }
            }

            // Strategy 2: OpenAI/OpenRouter format - {"choices":[{"message":{"content":"..."}}],...}
            (parsed["choices"] as? List<*>)?.let { choices ->
                if (choices.isEmpty()) return null
                logger.debug { "[JSON] Detected OpenAI/OpenRouter API response format" }
                val choice = choices[0] as? Map<*, *> ?: return null
                val message = choice["message"] as? Map<*, *> ?: return null
                val extractedContent = message["content"]?.toString()
                if (extractedContent != null) {
                    logger.info { "[JSON] Extracted content from OpenAI/OpenRouter format: ${extractedContent.length} chars" }
                    return extractedContent
                }
            }

            // Not a recognized API response format
            logger.debug { "[JSON] Not a recognized API response format, treating as content string" }
            null
        } catch (e: Exception) {
            // Not valid JSON or parsing failed - assume it's already content string
            logger.debug { "[JSON] Failed to parse as API response (not JSON): ${e.message}" }
            null
        }
    }

    /**
     * Try to extract content from markdown code block.
     *
     * @param content Full response content
     * @param language Language identifier (e.g., "json"), or null for any code block
     * @return Extracted content or null if not found
     */
    private fun tryExtractFromMarkdownBlock(content: String, language: String?): String? {
        return try {
            val pattern = if (language != null) {
                // Match: ```json ... ```
                // Allow optional whitespace after opening and before closing
                Regex("""```$language\s*\n?(.*?)\n?```""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            } else {
                // Match: ``` ... ```
                Regex("""```\s*\n?(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
            }

            pattern.find(content)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            logger.debug { "[JSON] Markdown extraction failed for language=$language: ${e.message}" }
            null
        }
    }

    /**
     * Try to extract JSON object by finding first { and last }.
     * Useful when response has text before/after JSON.
     */
    private fun tryExtractJsonObject(content: String): String? {
        return try {
            val firstBrace = content.indexOf('{')
            val lastBrace = content.lastIndexOf('}')

            if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
                content.substring(firstBrace, lastBrace + 1).trim()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug { "[JSON] Object extraction failed: ${e.message}" }
            null
        }
    }

    /**
     * Try to parse extracted content as JSON.
     *
     * @param jsonString String to parse
     * @param strategy Description of which extraction strategy was used (for logging)
     * @return Parsed Map or null if parsing failed
     */
    private fun tryParse(jsonString: String, strategy: String): Map<String, Any>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(jsonString, Map::class.java) as Map<String, Any>
            logger.debug { "[JSON] Successfully parsed JSON using strategy: $strategy" }
            parsed
        } catch (e: JsonSyntaxException) {
            logger.debug { "[JSON] Parse failed for strategy '$strategy': ${e.message}" }
            null
        } catch (e: Exception) {
            logger.debug { "[JSON] Unexpected error for strategy '$strategy': ${e.message}" }
            null
        }
    }

    /**
     * Clean common JSON formatting issues (for future use if needed).
     */
    fun cleanJson(content: String): String {
        var cleaned = content.trim()

        // Remove BOM if present
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1)
        }

        // Remove common prefixes that some models add
        val prefixes = listOf(
            "Here's the JSON:",
            "Here is the JSON:",
            "JSON:",
            "Response:"
        )

        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length).trim()
            }
        }

        return cleaned
    }
}

/**
 * Exception thrown when JSON extraction/parsing fails.
 */
class JsonParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
