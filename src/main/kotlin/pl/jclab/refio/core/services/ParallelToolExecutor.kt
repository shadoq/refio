package pl.jclab.refio.core.services

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Paths

private val logger = dualLogger("ParallelToolExecutor")

/**
 * Executes tool calls with parallel execution for READ_ONLY tools.
 *
 * WRITE tools are always executed sequentially to maintain atomicity.
 * READ_ONLY tools are executed in parallel for better performance.
 *
 * Reference: ADR-0028 - Parallel Tool Execution
 */
class ParallelToolExecutor(
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry,
    private val snapshotService: SnapshotService?
) {
    // Track statistics
    private var parallelExecutions = 0
    private var sequentialExecutions = 0

    /**
     * Execute tool calls with optimal parallelism.
     *
     * @param toolCalls List of tool calls to execute
     * @param taskId Task ID for tracking
     * @param subtaskId Subtask ID for snapshots
     * @param config Turn loop configuration
     * @return List of (toolCall, result) pairs in original order
     */
    suspend fun executeTools(
        toolCalls: List<ToolCallData>,
        taskId: String,
        subtaskId: String,
        config: TurnLoopConfig
    ): List<Pair<ToolCallData, ToolResultData>> = coroutineScope {

        if (!config.parallelReadTools) {
            // Sequential execution (original behavior)
            sequentialExecutions += toolCalls.size
            return@coroutineScope executeSequentially(
                toolCalls, taskId, subtaskId, config
            )
        }

        // Partition by tool mode
        val indexed = toolCalls.mapIndexed { index, tc -> index to tc }
        val (readOnlyIndexed, writeIndexed) = indexed.partition { (_, tc) ->
            getToolMode(tc.name) == ToolMode.READ_ONLY
        }

        parallelExecutions += readOnlyIndexed.size
        sequentialExecutions += writeIndexed.size

        logger.info {
            "[PARALLEL] Executing ${readOnlyIndexed.size} READ_ONLY in parallel, " +
            "${writeIndexed.size} WRITE sequentially"
        }

        // Execute READ_ONLY tools in parallel
        val readResults = readOnlyIndexed.map { (index, toolCall) ->
            async {
                val result = executeSingleTool(
                    toolCall, taskId, config
                )
                index to (toolCall to result)
            }
        }.awaitAll()

        // Execute WRITE tools sequentially (order matters!)
        val writeResults = writeIndexed.map { (index, toolCall) ->
            // Create snapshot before write operation
            if (config.enableSnapshots && snapshotService != null) {
                createSnapshotIfNeeded(taskId, subtaskId, toolCall)
            }

            val result = executeSingleTool(
                toolCall, taskId, config
            )
            index to (toolCall to result)
        }

        // Combine and sort by original index
        (readResults + writeResults)
            .sortedBy { it.first }
            .map { it.second }
    }

    private suspend fun executeSequentially(
        toolCalls: List<ToolCallData>,
        taskId: String,
        subtaskId: String,
        config: TurnLoopConfig
    ): List<Pair<ToolCallData, ToolResultData>> {
        sequentialExecutions += toolCalls.size

        return toolCalls.map { toolCall ->
            // Create snapshot before write operation
            if (config.enableSnapshots && snapshotService != null) {
                if (getToolMode(toolCall.name) == ToolMode.WRITE) {
                    createSnapshotIfNeeded(taskId, subtaskId, toolCall)
                }
            }

            val result = executeSingleTool(
                toolCall, taskId, config
            )
            toolCall to result
        }
    }

    private suspend fun executeSingleTool(
        toolCall: ToolCallData,
        taskId: String,
        config: TurnLoopConfig
    ): ToolResultData {
        return try {
            withTimeout(config.toolTimeout.toMillis()) {
                val params = parseJsonToMap(toolCall.arguments)
                val result = toolExecutor.executeTool(
                    ToolCall(name = toolCall.name, params = params),
                    taskId
                )

                ToolResultData(
                    toolCallId = toolCall.id,
                    content = result.output ?: result.error ?: "No output",
                    isSummarized = false,
                    rawOutput = result.output
                )
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "[TIMEOUT] Tool ${toolCall.name} exceeded ${config.toolTimeout}" }
            val errorResult = ToolResult(success = false, error = "Timeout exceeded")
            ToolResultData(
                toolCallId = toolCall.id,
                content = "Error: Tool execution timed out after ${config.toolTimeout}",
                isSummarized = false,
                rawOutput = null
            )
        } catch (e: Exception) {
            logger.error(e) { "[ERROR] Tool ${toolCall.name} failed: ${e.message}" }
            ToolResultData(
                toolCallId = toolCall.id,
                content = "Error: ${e.message}",
                isSummarized = false,
                rawOutput = null
            )
        }
    }

    private suspend fun createSnapshotIfNeeded(
        taskId: String,
        subtaskId: String,
        toolCall: ToolCallData
    ) {
        try {
            val params = parseJsonToMap(toolCall.arguments)
            val path = params["path"]?.toString() ?: return
            snapshotService!!.createSnapshot(
                taskId = taskId,
                subtaskId = subtaskId,
                filePaths = listOf(path)
            )
        } catch (e: Exception) {
            logger.warn(e) { "[SNAPSHOT] Failed to create snapshot for ${toolCall.name}" }
        }
    }

    private fun getToolMode(toolName: String): ToolMode {
        val tool = toolRegistry.getTool(toolName)
        return tool?.mode ?: ToolMode.READ_ONLY
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonToMap(jsonString: String): Map<String, Any> {
        return try {
            val element = json.parseToJsonElement(jsonString)
            jsonElementToMap(element)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse JSON: $jsonString" }
            emptyMap()
        }
    }

    private fun jsonElementToMap(element: kotlinx.serialization.json.JsonElement): Map<String, Any> {
        return when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                element.mapValues { (_, v) -> jsonElementToAny(v) }
            }
            else -> emptyMap()
        }
    }

    private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any {
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content.contains(".") -> element.content.toDoubleOrNull() ?: element.content
                    else -> element.content.toLongOrNull() ?: element.content
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                element.map { jsonElementToAny(it) }
            }
            is kotlinx.serialization.json.JsonObject -> {
                element.mapValues { (_, v) -> jsonElementToAny(v) }
            }
            else -> element.toString()
        }
    }

    /**
     * Get execution statistics.
     */
    fun getStats(): ParallelExecutionStats = ParallelExecutionStats(
        parallelExecutions = parallelExecutions,
        sequentialExecutions = sequentialExecutions
    )
}

/**
 * Parallel execution statistics.
 */
data class ParallelExecutionStats(
    val parallelExecutions: Int,
    val sequentialExecutions: Int
)
