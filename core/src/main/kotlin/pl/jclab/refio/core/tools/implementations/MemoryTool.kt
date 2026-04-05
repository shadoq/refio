package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.services.context.WorkingMemoryEntry
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import java.time.Instant

/**
 * Unified tool for managing shared working memory.
 *
 * Actions:
 * - write: Store a fact, conclusion, or discovery
 * - read: Read facts with optional key prefix filter
 * - list: List all memory keys with their importance scores
 */
class MemoryTool(
    private val workingMemoryService: WorkingMemoryService
) : Tool {
    override val name = "memory"
    override val description = """Manage shared working memory visible to orchestrator and other agents.
Actions: write (store a finding), read (retrieve facts), list (show all keys).
Use for: key findings, intermediate results, decisions, blockers.
Do NOT store raw data — only processed insights."""
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to listOf("write", "read", "list"),
                "description" to "Action to perform"
            ),
            "key" to mapOf(
                "type" to "string",
                "description" to "Category key for write: findings, decisions, blockers, results, context. For read: optional prefix filter."
            ),
            "value" to mapOf(
                "type" to "string",
                "description" to "The fact or conclusion to store (1-2 sentences, be specific). Required for write."
            ),
            "importance" to mapOf(
                "type" to "integer",
                "description" to "1-10 importance score. 10=critical finding, 5=useful context, 1=minor detail. Default: 7.",
                "default" to 7
            ),
            "filter" to mapOf(
                "type" to "string",
                "description" to "Optional key prefix filter for read (e.g., 'agent:searcher', 'findings')"
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"] as? String
            ?: return ToolResult.error("action required")

        return when (action) {
            "write" -> handleWrite(params)
            "read" -> handleRead(params)
            "list" -> handleList(params)
            else -> ToolResult.error("Unknown action: $action. Use: write, read, list")
        }
    }

    private fun handleWrite(params: Map<String, Any>): ToolResult {
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")
        val key = params["key"] as? String
            ?: return ToolResult.error("key required for write")
        val value = params["value"] as? String
            ?: return ToolResult.error("value required for write")
        val importance = (params["importance"] as? Number)?.toInt() ?: 7
        val agentName = params[ToolInternalParams.AGENT_NAME] as? String

        val prefixedKey = if (agentName != null) "agent:$agentName:$key" else key

        val entry = WorkingMemoryEntry(
            iteration = (params[ToolInternalParams.ITERATION] as? Number)?.toInt() ?: 0,
            key = prefixedKey,
            value = value,
            importance = importance.coerceIn(1, 10),
            timestamp = Instant.now(),
            lastAccessedAt = Instant.now()
        )

        val sessionId = params[ToolInternalParams.SESSION_ID] as? String
        workingMemoryService.recordEntries(taskId, listOf(entry), sessionId)

        return ToolResult(
            success = true,
            output = "Stored in working memory: [$prefixedKey] $value (importance: ${importance.coerceIn(1, 10)})",
            metadata = mapOf("memory_key" to prefixedKey, "agent" to (agentName ?: "main"))
        )
    }

    private fun handleRead(params: Map<String, Any>): ToolResult {
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")
        val filter = params["filter"] as? String ?: params["key"] as? String

        val section = workingMemoryService.buildWorkingMemorySection(taskId, maxTokens = 4096)

        val filtered = if (filter != null && section.isNotBlank()) {
            val lines = section.lines()
            val result = mutableListOf<String>()
            var inMatchingSection = false

            for (line in lines) {
                when {
                    line.startsWith("## ") -> {
                        inMatchingSection = line.contains(filter, ignoreCase = true)
                        if (inMatchingSection) result.add(line)
                    }
                    line.startsWith("<") || line.startsWith("</") -> result.add(line)
                    inMatchingSection -> result.add(line)
                }
            }
            result.joinToString("\n")
        } else {
            section
        }

        return ToolResult(
            success = true,
            output = if (filtered.isBlank()) "Working memory is empty." else filtered
        )
    }

    private fun handleList(params: Map<String, Any>): ToolResult {
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")

        val section = workingMemoryService.buildWorkingMemorySection(taskId, maxTokens = 4096)

        if (section.isBlank()) {
            return ToolResult(success = true, output = "Working memory is empty.")
        }

        // Extract keys from section headers
        val keys = section.lines()
            .filter { it.startsWith("## ") }
            .map { it.removePrefix("## ").trim() }

        return ToolResult(
            success = true,
            output = if (keys.isEmpty()) "Working memory is empty."
            else "Memory keys:\n" + keys.joinToString("\n") { "  - $it" }
        )
    }
}
