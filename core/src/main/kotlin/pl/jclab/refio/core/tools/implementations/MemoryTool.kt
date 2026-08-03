package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.db.repositories.SubtaskRepository
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
 * - get_subtask_output: Retrieve the FULL raw output of a previous tool call
 *   by its subtask id (the same id shown as `ref#...` in <WORKING_MEMORY> and
 *   as `id: ...` in tool-result message headers). Use when the in-context
 *   summary lost details you need to verify.
 */
class MemoryTool(
    private val workingMemoryService: WorkingMemoryService,
    private val subtaskRepository: SubtaskRepository? = null
) : Tool {
    override val name = "memory"
    override val description = """Manage shared working memory visible to orchestrator and other agents.
Actions: write (store a finding), read (retrieve facts), list (show all keys),
get_subtask_output (recover full raw output of a past tool call by subtask id / ref#).
Use for: key findings, intermediate results, decisions, blockers, recovering data lost to summarization."""
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM
    override val selectionHint =
        "Cross-turn working memory: write/read findings, list keys, recover truncated subtask output."

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to listOf("write", "read", "list", "get_subtask_output"),
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
            ),
            "subtask_id" to mapOf(
                "type" to "string",
                "description" to "Subtask / tool-call id — the same value shown as ref# in WORKING_MEMORY and as id: in tool-result message headers. Required for get_subtask_output."
            ),
            "offset" to mapOf(
                "type" to "integer",
                "description" to "For get_subtask_output: char offset to start reading from (default: 0)."
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "For get_subtask_output: max chars to return (default: 16384, max: 64000)."
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"] as? String
            ?: return ToolResult.error("action required. Use one of: write, read, list, get_subtask_output")

        return when (action) {
            "write" -> handleWrite(params)
            "read" -> handleRead(params)
            "list" -> handleList(params)
            "get_subtask_output" -> handleGetSubtaskOutput(params)
            else -> ToolResult.error("Unknown action: $action. Use: write, read, list, get_subtask_output")
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

        // Tag the entry with the subtask id so it lines up with RECENT_WORK / MESSAGES headers
        // (the same canonical id used everywhere else for this tool execution).
        val subtaskId = params[ToolInternalParams.SUBTASK_ID] as? String
        val entry = WorkingMemoryEntry(
            iteration = (params[ToolInternalParams.ITERATION] as? Number)?.toInt() ?: 0,
            key = prefixedKey,
            value = value,
            importance = importance.coerceIn(1, 10),
            timestamp = Instant.now(),
            lastAccessedAt = Instant.now(),
            originId = subtaskId
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

    /**
     * Recover the FULL raw output of a previous tool call by subtask id.
     *
     * The model only sees a (possibly summarized) version of past tool results
     * in conversation history. When it needs the literal data — exact numbers,
     * full lists of identifiers, complete error bodies — it can call this with
     * the `ref#XXXXXXXX` shown in <WORKING_MEMORY> tags (or the full subtask id).
     *
     * Pagination via offset/limit so even very large outputs (the file we
     * persist via run_code's auto-save also lives in subtask.result) can be
     * scrolled without blowing the context window.
     */
    private fun handleGetSubtaskOutput(params: Map<String, Any>): ToolResult {
        val repo = subtaskRepository
            ?: return ToolResult.error("Subtask repository not wired into MemoryTool")
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")
        val rawId = (params["subtask_id"] as? String)?.trim()
            ?: return ToolResult.error("subtask_id required for get_subtask_output")

        val offset = (params["offset"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        val limit = (params["limit"] as? Number)?.toInt()?.coerceIn(1, 64_000) ?: 16384

        // Try exact id first; if the user passed only the 8-char ref#, fall back
        // to a prefix lookup over the task's subtasks.
        val subtask = repo.findById(rawId)
            ?: run {
                // Prefix lookup — `getByTaskId` is the broadest enumerator we have here.
                val candidates = runCatching { repo.findByTaskId(taskId) }.getOrNull().orEmpty()
                candidates.firstOrNull { it.id.startsWith(rawId, ignoreCase = true) }
            }
            ?: return ToolResult.error(
                "No subtask found for id/ref '$rawId' in task $taskId. " +
                    "Use the full id from a tool result or the ref#XXXXXXXX from <WORKING_MEMORY>."
            )

        val raw = subtask.result
        if (raw.isNullOrEmpty()) {
            return ToolResult(
                success = true,
                output = "[Subtask ${subtask.id} (${subtask.kind}) has no stored raw output. " +
                    "Summary: ${subtask.summary ?: "(none)"}]",
                metadata = mapOf(
                    "subtask_id" to subtask.id,
                    "kind" to subtask.kind.toString(),
                    "has_raw" to false
                )
            )
        }

        val total = raw.length
        val safeStart = offset.coerceAtMost(total)
        val safeEnd = (safeStart + limit).coerceAtMost(total)
        val slice = raw.substring(safeStart, safeEnd)
        val unreadAfter = total - safeEnd

        val header = "[Subtask ${subtask.id} (${subtask.kind}), chars $safeStart-$safeEnd of $total]"
        val footer = if (unreadAfter > 0) {
            "\n\n[!! ${unreadAfter} more chars available. Continue with " +
                "memory(action=\"get_subtask_output\", subtask_id=\"${subtask.id}\", offset=$safeEnd) !!]"
        } else ""

        return ToolResult(
            success = true,
            output = "$header\n$slice$footer",
            metadata = mapOf(
                "subtask_id" to subtask.id,
                "kind" to subtask.kind.toString(),
                "char_offset" to safeStart,
                "char_end" to safeEnd,
                "total_chars" to total,
                "unread_after" to unreadAfter
            )
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
