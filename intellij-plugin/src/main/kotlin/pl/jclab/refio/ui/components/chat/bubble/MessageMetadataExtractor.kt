package pl.jclab.refio.ui.components.chat.bubble

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.api.models.UserContextMetadata
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.ui.components.chat.CodeChangesData
import pl.jclab.refio.ui.components.chat.ConversationSummaryMetadata
import pl.jclab.refio.ui.components.chat.ExecutionSummaryFile
import pl.jclab.refio.ui.components.chat.ExecutionSummaryMetadata
import pl.jclab.refio.ui.components.chat.ExecutionSummaryStats
import pl.jclab.refio.ui.components.chat.QuestionData

internal object MessageMetadataExtractor {

    private val logger = dualLogger("MessageMetadataExtractor")

    private val knownToolNames = setOf(
        "read_file",
        "read_directory",
        "file_search",
        "grep_search",
        "code_editing",
        "create_new_file",
        "multi_edit",
        "advance_code_editing",
        "multi_line_editor",
        "run_terminal_command"
    )

    fun extractSubtaskId(content: String): String? {
        val regex = Regex("\\*\\*Subtask ID:\\*\\*\\s*`([^`]+)`")
        val match = regex.find(content)
        val subtaskId = match?.groupValues?.get(1)

        if (subtaskId != null) {
            logger.debug { "Extracted subtask ID: $subtaskId from message" }
        } else {
            logger.debug { "No subtask ID found in message content" }
        }

        return subtaskId
    }

    fun extractQuestionData(message: Message): QuestionData? {
        if (message.metadata == null) return null

        try {
            val metadata = com.google.gson.Gson().fromJson(
                message.metadata, com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val type = metadata["type"] as? String
            if (type != "orchestrator_question") return null

            val questionId = metadata["question_id"] as? String ?: return null
            val awaitingResponse = metadata["awaiting_response"] as? Boolean ?: false

            if (!awaitingResponse) return null

            val options = (metadata["options"] as? List<*>)?.mapNotNull { it as? String }

            logger.info { "Extracted question data: questionId=$questionId, options=$options" }

            return QuestionData(questionId, options ?: emptyList())

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse question metadata" }
            return null
        }
    }

    fun extractToolCallInfo(message: Message): ToolCallDisplayInfo? {
        message.toolCallInfo?.let { return it }
        val metadata = message.metadata
        if (!metadata.isNullOrBlank()) {
            ToolCallDisplayInfo.fromMetadataJson(metadata)?.let { return it }
        }

        if (message.content.isNotBlank()) {
            return extractToolCallInfoFromContent(message.content)
        }

        return null
    }

    fun extractCodeChanges(message: Message): CodeChangesData? {
        logger.debug { "[EXTRACT] Extracting code changes from message ${message.id}" }

        val metadata = message.metadata ?: run {
            logger.debug { "[EXTRACT] No metadata in message ${message.id}" }
            return null
        }
        logger.debug { "[EXTRACT] Raw metadata: $metadata" }

        try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata, com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: run {
                logger.warn { "[EXTRACT] Failed to parse metadata JSON: $metadata" }
                return null
            }
            logger.debug { "[EXTRACT] Parsed metadata map: $metadataMap" }

            val type = metadataMap["type"] as? String
            logger.debug { "[EXTRACT] Metadata type: $type" }
            if (type != "code_changes") {
                logger.info { "[EXTRACT] Type is not 'code_changes', skipping" }
                return null
            }

            val filePath = metadataMap["file_path"] as? String ?: run {
                logger.warn { "[EXTRACT] Missing file_path in code_changes metadata" }
                return null
            }
            val addedLines = (metadataMap["added_lines"] as? Number)?.toInt() ?: 0
            val removedLines = (metadataMap["removed_lines"] as? Number)?.toInt() ?: 0
            val snapshotId = metadataMap["snapshot_id"] as? String

            logger.info { "[EXTRACT] Extracted code changes: path=$filePath, +$addedLines -$removedLines, snapshot=$snapshotId" }

            return CodeChangesData(filePath, addedLines, removedLines, snapshotId)

        } catch (e: Exception) {
            logger.error(e) { "[EXTRACT] Failed to parse code changes metadata: ${message.metadata}" }
            return null
        }
    }

    fun extractExecutionSummaryMetadata(message: Message): ExecutionSummaryMetadata? {
        val metadata = message.metadata ?: return null
        return try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val type = metadataMap["type"] as? String ?: return null
            if (type != "execution_summary") {
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val filesRaw = metadataMap["changed_files"] as? List<Any>
            val changedFiles = filesRaw
                ?.mapNotNull { entry -> parseChangedFileEntry(entry) }
                ?: emptyList()

            val statsMap = metadataMap["stats"] as? Map<*, *>
            val stats = statsMap?.let {
                ExecutionSummaryStats(
                    totalSteps = it["total_steps"].toSafeInt(defaultValue = 0),
                    completedSteps = it["completed_steps"].toSafeInt(defaultValue = 0),
                    failedSteps = it["failed_steps"].toSafeInt(defaultValue = 0),
                    totalTokens = it["total_tokens"].toSafeInt(defaultValue = 0),
                    totalCostUsd = it["total_cost_usd"].toSafeDouble(defaultValue = 0.0)
                )
            }

            ExecutionSummaryMetadata(
                changedFiles = changedFiles,
                stats = stats,
                generatedAt = (metadataMap["generated_at"] as? Number)?.toLong(),
                model = metadataMap["model"] as? String,
                provider = metadataMap["provider"] as? String
            )
        } catch (e: Exception) {
            logger.error(e) { "[EXTRACT] Failed to parse execution summary metadata" }
            null
        }
    }

    fun extractConversationSummaryMetadata(message: Message): ConversationSummaryMetadata? {
        val metadata = message.metadata ?: return null
        return try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val type = metadataMap["type"] as? String ?: return null
            if (type != "conversation_summary") {
                return null
            }

            ConversationSummaryMetadata(
                summarizedCount = metadataMap["summarized_count"].toSafeInt(defaultValue = 0),
                summaryIndex = metadataMap["summary_index"].toSafeInt(defaultValue = 1),
                timestamp = (metadataMap["timestamp"] as? Number)?.toLong(),
                firstMessageId = metadataMap["first_message_id"] as? String,
                lastMessageId = metadataMap["last_message_id"] as? String
            )
        } catch (e: Exception) {
            logger.error(e) { "[EXTRACT] Failed to parse conversation summary metadata" }
            null
        }
    }

    /**
     * True when the SYSTEM message is an internal guardian re-entry nudge (tagged with
     * `{"type":"guardian_nudge"}` by AgentTurnLoop). The nudge body is a model-facing
     * "STOP — the turn is NOT finished" instruction; the UI renders these gently as an
     * "agent guidance" note rather than showing the raw alarming text.
     */
    fun isGuardianNudge(message: Message): Boolean {
        val metadata = message.metadata ?: return false
        return try {
            val metadataMap = com.google.gson.Gson().fromJson(
                metadata,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return false
            (metadataMap["type"] as? String) == "guardian_nudge"
        } catch (e: Exception) {
            false
        }
    }

    fun extractUserContextMetadata(message: Message): UserContextMetadata? {
        val metadata = message.metadata ?: return null
        if (metadata.isBlank()) return null

        val result = UserContextMetadata.fromJson(metadata)
        if (result == null) {
            logger.debug { "[CONTEXT_BADGE] Metadata parse returned null for message ${message.id}" }
        }
        return result
    }

    fun isPlanJson(content: String): Boolean {
        if (!content.trim().startsWith("{")) return false
        return try {
            val json = com.google.gson.Gson().fromJson(
                content,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return false

            json.containsKey("plan") ||
                    json.containsKey("subtasks") ||
                    json.containsKey("actions")
        } catch (e: Exception) {
            false
        }
    }

    fun parsePlanJson(content: String): Map<String, Any?> {
        return try {
            val json = com.google.gson.Gson().fromJson(
                content,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return emptyMap()

            val planDescription = json["plan"] as? String ?: json["response"] as? String
            val steps = json["subtasks"] as? List<*> ?: json["actions"] as? List<*> ?: emptyList<Map<*, *>>()

            mapOf(
                "plan" to planDescription,
                "subtasks" to steps
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse plan JSON" }
            emptyMap()
        }
    }

    private fun extractToolCallInfoFromContent(content: String): ToolCallDisplayInfo? {
        val trimmed = content.trim()

        val rawPattern = Regex(
            """TOOL_CALL:\s*([A-Za-z0-9_]+)\s*(?:\r?\n)?ARGUMENTS:\s*(\{[\s\S]*\})""",
            RegexOption.MULTILINE
        )
        val rawMatch = rawPattern.find(trimmed)
        if (rawMatch != null) {
            val toolName = rawMatch.groupValues[1]
            val argsJson = rawMatch.groupValues[2]
            return ToolCallDisplayInfo(
                toolName = toolName,
                toolCallId = "",
                displayType = inferToolDisplayType(toolName),
                parameters = parseToolArgumentsJson(argsJson),
                status = ToolCallStatus.EXECUTING
            )
        }

        val markdownPattern = Regex(
            """\*\*([A-Za-z0-9_]+)\*\*\s*\n```([\s\S]*?)```""",
            RegexOption.MULTILINE
        )
        val markdownMatch = markdownPattern.find(trimmed)
        if (markdownMatch != null) {
            val toolName = markdownMatch.groupValues[1]
            if (toolName !in knownToolNames) return null
            val paramsBlock = markdownMatch.groupValues[2]
            val params = parseToolParametersBlock(paramsBlock)
            if (params.isEmpty()) return null
            return ToolCallDisplayInfo(
                toolName = toolName,
                toolCallId = "",
                displayType = inferToolDisplayType(toolName),
                parameters = params,
                status = ToolCallStatus.EXECUTING
            )
        }

        return null
    }

    private fun inferToolDisplayType(toolName: String): ToolDisplayType {
        return when (toolName) {
            "advance_code_editing", "multi_line_editor" -> ToolDisplayType.LLM_EDIT
            "code_editing", "create_new_file", "multi_edit" -> ToolDisplayType.CODE_EDIT
            "run_terminal_command" -> ToolDisplayType.TERMINAL
            else -> ToolDisplayType.SIMPLE
        }
    }

    private fun parseToolArgumentsJson(argumentsJson: String): Map<String, String> {
        return try {
            val args = com.google.gson.Gson().fromJson(
                argumentsJson, com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return emptyMap()

            args.entries.mapNotNull { (key, value) ->
                val keyString = key as? String ?: return@mapNotNull null
                keyString to (value?.toString() ?: "")
            }.toMap()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse tool arguments JSON" }
            emptyMap()
        }
    }

    private fun parseToolParametersBlock(paramsBlock: String): Map<String, String> {
        return paramsBlock
            .lines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || !trimmed.contains(":")) return@mapNotNull null
                val parts = trimmed.split(":", limit = 2)
                val key = parts[0].trim()
                val value = parts.getOrNull(1)?.trim() ?: ""
                if (key.isBlank()) null else key to value
            }.toMap()
    }

    private fun parseChangedFileEntry(entry: Any?): ExecutionSummaryFile? {
        val map = entry as? Map<*, *> ?: return null
        val filePath = map["file_path"] as? String ?: return null
        val added = map["added_lines"].toSafeInt(defaultValue = 0)
        val removed = map["removed_lines"].toSafeInt(defaultValue = 0)
        val snapshotId = map["snapshot_id"] as? String
        return ExecutionSummaryFile(
            filePath = filePath,
            addedLines = added,
            removedLines = removed,
            snapshotId = snapshotId
        )
    }
}

internal fun Any?.toSafeInt(defaultValue: Int): Int {
    return when (this) {
        is Number -> this.toInt()
        is String -> this.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }
}

internal fun Any?.toSafeDouble(defaultValue: Double): Double {
    return when (this) {
        is Number -> this.toDouble()
        is String -> this.toDoubleOrNull() ?: defaultValue
        else -> defaultValue
    }
}
