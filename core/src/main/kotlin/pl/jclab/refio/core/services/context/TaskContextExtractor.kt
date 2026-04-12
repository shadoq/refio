package pl.jclab.refio.core.services.context

import com.google.gson.reflect.TypeToken
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.models.context.ExecutedStepDTO
import pl.jclab.refio.core.utils.GsonInstance
import java.nio.file.Path
import java.time.Instant

private val logger = dualLogger("TaskContextExtractor")

/**
 * Extracts task & subtask data from DB entities into context DTOs.
 *
 * Stateless helper used by [pl.jclab.refio.core.services.ContextService].
 */
internal class TaskContextExtractor {

    /**
     * Extract user requirements from task description.
     * Parses lines like "Technologies: X, Y, Z" or "Services: A, B".
     * Based on Python context_service.py lines 1052-1085
     */
    fun extractUserRequirements(description: String): Map<String, Any> {
        val requirements = mutableMapOf<String, Any>()
        if (description.isBlank()) return requirements

        val lines = description.lines().map { it.trim() }.filter { it.isNotBlank() }
        val tech = mutableListOf<String>()
        val services = mutableListOf<String>()
        val notes = mutableListOf<String>()

        for (line in lines) {
            val lower = line.lowercase()
            when {
                lower.startsWith("technologies:") || lower.contains(" technologies ") -> {
                    val value = if (":" in line) line.split(":", limit = 2)[1] else line
                    tech.addAll(value.split(Regex("[,;|]")).map { it.trim() }.filter { it.isNotBlank() })
                }

                lower.startsWith("services:") || lower.contains(" services ") -> {
                    val value = if (":" in line) line.split(":", limit = 2)[1] else line
                    services.addAll(value.split(Regex("[,;|]")).map { it.trim() }.filter { it.isNotBlank() })
                }

                lower.startsWith("use ") -> notes.add(line)
            }
        }

        if (tech.isNotEmpty()) requirements["technologies"] = tech.distinct().sorted()
        if (services.isNotEmpty()) requirements["services"] = services.distinct().sorted()
        if (notes.isNotEmpty()) requirements["notes"] = notes

        return requirements
    }

    /**
     * Build previous subtasks data for context.
     * Returns pair of (subtask summaries, completed file paths).
     * Based on Python context_service.py lines 1087-1105
     */
    fun buildPreviousSubtasksData(
        subtasks: List<Subtask>,
        limit: Int = 10
    ): Pair<List<String>, List<String>> {
        val completedFiles = mutableSetOf<String>()
        val previousSubtasks = mutableSetOf<String>()

        val completed = subtasks.filter { it.status == TaskStatus.SUCCESS }
        val gson = GsonInstance.gson

        for (prevSubtask in completed.takeLast(limit)) {
            val summary = prevSubtask.result ?: "No summary available."
            previousSubtasks.add("- ${prevSubtask.description}: $summary")

            // Extract file paths from tool arguments (try multiple field names)
            val filePaths = mutableSetOf<String>()

            // Try paramsJson first
            prevSubtask.paramsJson?.let { json ->
                try {
                    val params = gson.fromJson(json, Map::class.java)
                    // Try various common field names for file paths
                    val possibleKeys = listOf("path", "file_path", "file", "target", "source", "files")
                    for (key in possibleKeys) {
                        when (val value = params?.get(key)) {
                            is String -> filePaths.add(value)
                            is List<*> -> value.filterIsInstance<String>().forEach { filePaths.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }

            // Also try stepPlanJson (might contain file references)
            prevSubtask.stepPlanJson?.let { json ->
                try {
                    val plan = gson.fromJson(json, Map::class.java)
                    // Look for files in tool_calls
                    @Suppress("UNCHECKED_CAST")
                    val toolCalls = plan?.get("tool_calls") as? List<Map<*, *>>
                    toolCalls?.forEach { call ->
                        @Suppress("UNCHECKED_CAST")
                        val args = call["args"] as? Map<*, *>
                        val possibleKeys = listOf("path", "file_path", "file", "target", "source")
                        for (key in possibleKeys) {
                            when (val value = args?.get(key)) {
                                is String -> filePaths.add(value)
                                is List<*> -> value.filterIsInstance<String>().forEach { filePaths.add(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }

            completedFiles.addAll(filePaths)
        }

        return Pair(previousSubtasks.toList(), completedFiles.toList())
    }

    /**
     * Build structured executed steps for RECENT_WORK (ADR 0041).
     * Parses subtask result JSON to extract tool runs with parameters and outputs.
     *
     * Returns ALL terminal subtasks (both SUCCESS and FAILED) without any count cap —
     * selection and compression are the RECENT_WORK section's job (budget-driven via
     * [pl.jclab.refio.core.services.context.ContextFormatter.buildRecentWorkSection]
     * which picks FULL/DETAILED/SUMMARY levels based on available tokens). Hard-capping
     * here used to hide older tool calls from the agent, causing it to forget its own
     * prior attempts and re-run failed operations. PENDING/RUNNING subtasks are skipped
     * because they have no result to render yet.
     */
    fun buildExecutedSteps(
        subtasks: List<Subtask>
    ): List<ExecutedStepDTO> {
        val gson = GsonInstance.gson
        // Include both SUCCESS and FAILED so the agent sees its own errors in RECENT_WORK.
        // Without this, failed tool calls were invisible to the agent and it kept re-trying
        // the same approach under the impression it hadn't tried yet.
        val completed = subtasks.filter {
            it.status == TaskStatus.SUCCESS || it.status == TaskStatus.FAILED
        }
        val executedSteps = mutableListOf<ExecutedStepDTO>()
        val mapType = object : TypeToken<Map<String, Any>>() {}.type

        completed.forEach { prevSubtask ->
            val rawResult = prevSubtask.result ?: return@forEach
            var hasAddedSteps = false
            val fallbackParams = extractParamsFromSubtask(prevSubtask, gson)

            try {
                val normalized = normalizeResultJson(rawResult, gson)
                if (normalized != null) {
                    val parsed = gson.fromJson<Map<String, Any>>(normalized, mapType)
                    val outputs = parsed?.get("outputs") as? List<*>

                    if (!outputs.isNullOrEmpty()) {
                        outputs.mapNotNull { it as? Map<*, *> }.forEach { output ->
                            val tool = output["tool"] as? String ?: prevSubtask.kind.name
                            val outputParams = extractParamsFromOutput(output)
                            val paramsMap = toStringAnyMap(outputParams).ifEmpty { fallbackParams }
                            val resultMap = output["result"] as? Map<*, *>
                            val filePath = pickFilePath(
                                paramsMap = outputParams ?: fallbackParams,
                                resultMap = resultMap,
                                affectedFiles = output["affectedFiles"] as? List<*>
                            )
                            val resultText = extractResultText(resultMap, gson)
                            val summary = prevSubtask.summary  // Get summary from subtask
                            val timestamp = prevSubtask.completedAt ?: prevSubtask.updatedAt

                            executedSteps.add(
                                ExecutedStepDTO(
                                    subtaskId = prevSubtask.id,
                                    file = filePath,
                                    tool = tool,
                                    parameters = paramsMap,
                                    result = resultText,
                                    rawResultSize = resultText.length,
                                    summary = summary,
                                    timestamp = Instant.ofEpochMilli(timestamp),
                                    success = prevSubtask.status == TaskStatus.SUCCESS
                                )
                            )
                            hasAddedSteps = true
                        }
                    } else {
                        logger.debug {
                            "[CONTEXT] No outputs found in parsed JSON, subtask=${prevSubtask.id}, " +
                                    "tool=${prevSubtask.kind.name}, normalized.startsWith('{')=${normalized.startsWith("{")}"
                        }
                    }
                } else {
                    logger.debug {
                        "[CONTEXT] normalizeResultJson returned null, subtask=${prevSubtask.id}, " +
                                "tool=${prevSubtask.kind.name}, rawResult.length=${rawResult.length}, " +
                                "starts with '{'=${
                                    rawResult.trim().startsWith("{")
                                }, starts with '\"'=${rawResult.trim().startsWith("\"")}"
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[CONTEXT] Failed to parse executed step from subtask ${prevSubtask.id}" }
            }

            // FALLBACK: If no steps were added from outputs, create a basic entry from subtask metadata
            if (!hasAddedSteps) {
                val timestamp = prevSubtask.completedAt ?: prevSubtask.updatedAt
                val summary = prevSubtask.summary ?: "Completed: ${prevSubtask.kind.name}"

                // Keep raw data up to 512KB — let RECENT_WORK budget-driven compression decide.
                // Only truncate truly huge outputs to prevent memory pressure.
                val maxRawResultChars = 524_288 // 512KB
                val resultText = if (rawResult.length > maxRawResultChars) {
                    rawResult.take(maxRawResultChars) + "\n... [truncated from ${rawResult.length} chars]"
                } else {
                    rawResult
                }

                executedSteps.add(
                    ExecutedStepDTO(
                        subtaskId = prevSubtask.id,
                        file = null,
                        tool = prevSubtask.kind.name,
                        parameters = fallbackParams,
                        result = resultText,
                        rawResultSize = rawResult.length,
                        summary = summary,
                        timestamp = Instant.ofEpochMilli(timestamp),
                        success = prevSubtask.status == TaskStatus.SUCCESS
                    )
                )
                logger.debug { "[CONTEXT] Added fallback step for subtask ${prevSubtask.id}: ${prevSubtask.kind.name}" }
            }
        }

        // No hard cap — return all terminal steps and let the RECENT_WORK section
        // picker apply budget-driven selection + compression.
        return executedSteps
    }

    private fun normalizeResultJson(rawResult: String, gson: com.google.gson.Gson): String? {
        val trimmed = rawResult.trim()
        if (trimmed.startsWith("{")) {
            return trimmed
        }
        if (trimmed.startsWith("\"")) {
            val unquoted = try {
                gson.fromJson(trimmed, String::class.java).trim()
            } catch (e: Exception) {
                return null
            }
            return if (unquoted.startsWith("{")) unquoted else null
        }
        return null
    }

    private fun extractParamsFromOutput(output: Map<*, *>): Map<*, *>? {
        val direct = firstMapByKeys(
            source = output,
            keys = listOf("params", "parameters", "arguments", "args", "tool_args", "toolArgs", "input")
        )
        if (direct != null) return direct

        val toolCall = output["toolCall"] as? Map<*, *> ?: output["tool_call"] as? Map<*, *>
        return firstMapByKeys(
            source = toolCall,
            keys = listOf("params", "parameters", "arguments", "args", "tool_args", "toolArgs", "input")
        )
    }

    private fun extractParamsFromSubtask(subtask: Subtask, gson: com.google.gson.Gson): Map<String, Any> {
        val raw = subtask.paramsJson?.trim().orEmpty()
        if (raw.isBlank() || !raw.startsWith("{")) return emptyMap()

        return runCatching {
            val parsed = gson.fromJson<Map<String, Any>>(raw, object : TypeToken<Map<String, Any>>() {}.type)
            val nested = firstMapByKeys(
                source = parsed,
                keys = listOf("params", "parameters", "arguments", "args", "tool_args", "toolArgs", "input")
            )
            when {
                nested != null -> toStringAnyMap(nested)
                else -> toStringAnyMap(parsed)
            }
        }.getOrElse { emptyMap() }
    }

    private fun firstMapByKeys(source: Map<*, *>?, keys: List<String>): Map<*, *>? {
        if (source == null) return null
        for (key in keys) {
            val value = source[key]
            if (value is Map<*, *>) return value
        }
        return null
    }

    private fun toStringAnyMap(raw: Map<*, *>?): Map<String, Any> {
        if (raw == null) return emptyMap()
        val result = mutableMapOf<String, Any>()
        for ((key, value) in raw.entries) {
            val k = key as? String ?: continue
            if (value != null) result[k] = value
        }
        return result
    }

    private fun pickFilePath(
        paramsMap: Map<*, *>?,
        resultMap: Map<*, *>?,
        affectedFiles: List<*>?
    ): String? {
        val metadataPath = (resultMap?.get("metadata") as? Map<*, *>)?.get("path") as? String
        val candidates = listOfNotNull(
            paramsMap?.get("path") as? String,
            paramsMap?.get("file_path") as? String,
            paramsMap?.get("file") as? String,
            paramsMap?.get("target") as? String,
            paramsMap?.get("source") as? String,
            metadataPath,
            affectedFiles?.firstOrNull() as? String
        )
        return candidates.firstOrNull()
    }

    private fun extractResultText(resultMap: Map<*, *>?, gson: com.google.gson.Gson): String {
        if (resultMap == null) return "-"
        val output = resultMap["output"] as? String
        val error = resultMap["error"] as? String
        val message = resultMap["message"] as? String

        return output ?: error ?: message ?: gson.toJson(resultMap)
    }

    /**
     * Clean subtask summary - remove JSON artifacts if present.
     */
    fun cleanSubtaskSummary(subtask: String): String {
        // If subtask contains JSON-like content, extract just the description
        return if (subtask.contains("{\"toolsExecuted\"") || subtask.contains("{\"outputs\"")) {
            // Try to extract meaningful description before JSON
            val colonIndex = subtask.indexOf(": {")
            if (colonIndex > 0) {
                subtask.substring(0, colonIndex).trim().removePrefix("- ")
            } else {
                subtask.substringBefore("{").trim().removePrefix("- ")
            }
        } else {
            subtask
        }
    }

    /**
     * Summarize file changes by type and importance.
     * Groups files by extension for concise display.
     * Based on Python context_service.py lines 1107-1132
     */
    fun summarizeFileChanges(completedFiles: List<String>): String {
        if (completedFiles.isEmpty()) return ""

        val byType = mutableMapOf<String, MutableList<String>>()

        for (filePath in completedFiles) {
            try {
                val path = Path.of(filePath)
                val ext = path.fileName.toString().substringAfterLast('.', "no-ext").lowercase()
                val name = path.fileName.toString()
                byType.getOrPut(ext) { mutableListOf() }.add(name)
            } catch (e: Exception) {
                byType.getOrPut("unknown") { mutableListOf() }.add(filePath)
            }
        }

        val summaryParts = byType.map { (ext, files) ->
            if (files.size > 2) {
                "$ext: ${files.size} files"
            } else {
                val fileNames = files.map { if (it.length > 25) "${it.take(25)}..." else it }
                "$ext: ${fileNames.joinToString(", ")}"
            }
        }.take(4)

        return summaryParts.joinToString(" | ")
    }
}
