package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.utils.NameSuggestion
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("InvokeSubagentTool")

// Mirrors TurnSubagentValidator(maxSubagentDepth = 3): the turn loop enforces this ceiling when a
// subagent turn starts; InvokeSubagentTool checks it first so an over-deep call fails BEFORE spawn
// (defense in depth; a spawn-then-fail still pays the allocation).
private const val MAX_SUBAGENT_DEPTH = 3

class InvokeSubagentTool(
    private val subagentRouterProvider: () -> SubagentRouter?,
    private val runTurnCallback: suspend (TurnRequest, TurnEventListener?, StreamCallback?) -> TurnResult,
    private val configServiceProvider: () -> ConfigService,
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {

    override val name = "invoke_subagent"
    override val description: String
        get() = buildDynamicDescription()
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint =
        "Delegate a sub-task to a specialized subagent (EXPENSIVE). Dispatch multiple in parallel for independent work."

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val taskId = params["_task_id"]?.toString()
            ?: return ToolResult.error("Missing internal parameter: _task_id")
        val subagentName = params["subagent_name"]?.toString()?.trim()
            ?: return ToolResult.error("Missing required parameter: subagent_name")
        val goal = params["goal"]?.toString()?.trim()
            ?: return ToolResult.error("Missing required parameter: goal")
        if (goal.isBlank()) {
            return ToolResult.error("Parameter 'goal' cannot be empty")
        }

        val parentDepth = (params["_parent_depth"] as? Number)?.toInt() ?: 0
        val parentRunId = params["_parent_run_id"]?.toString()
        val parentChain = (params["_subagent_chain"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
        val turnListener = params["_turn_event_listener"] as? TurnEventListener

        // Build a StreamCallback from the turn listener so subagent LLM output streams to the UI
        val streamCallback: StreamCallback? = if (turnListener != null) {
            { chunk: StreamChunk ->
                turnListener.onStreamChunk(taskId, chunk.delta, chunk.accumulated)
            }
        } else {
            null
        }

        if (parentChain.any { it.equals(subagentName, ignoreCase = true) }) {
            return ToolResult.error("Subagent recursion detected for '$subagentName'")
        }

        // Security ceiling: subagent mode can never exceed parent mode.
        // Default to PLAN (not AGENT) if _mode is missing — safe fallback.
        val parentMode = runCatching {
            TaskMode.valueOf(params["_mode"]?.toString() ?: TaskMode.PLAN.name)
        }.getOrDefault(TaskMode.PLAN)
        val mode = parentMode // subagent inherits parent's mode as ceiling

        val executionMode = runCatching {
            ExecutionMode.valueOf(params["_execution_mode"]?.toString() ?: ExecutionMode.AUTO.name)
        }.getOrDefault(ExecutionMode.AUTO)

        val router = subagentRouterProvider()
            ?: return ToolResult.error("Subagent system not available")
        val definition = router.getSubagent(subagentName)
            ?: return ToolResult.error(subagentNotFoundMessage(router, subagentName))
        if (!definition.enabled) {
            return ToolResult.error("Subagent is disabled: $subagentName")
        }

        val configService = configServiceProvider()
        val (resolvedModel, resolvedProvider) = definition.resolveModel(configService)
        val contextRefs = parseContextRefs(params["context_refs"])
        val childDepth = parentDepth + 1

        // Fresh instance id isolates this subagent's chat history from the parent and from sibling
        // subagents. The turn loop tags every row it writes with this id; the prompt builder then
        // loads only matching rows, so the subagent's intermediate steps never reach the parent.
        val agentInstanceId = java.util.UUID.randomUUID().toString()

        // Refuse a spawn that would breach the nesting ceiling before allocating
        // the child turn. Reported as a clean ToolResult.error, consistent with the recursion guard
        // above (the turn-loop TurnSubagentValidator remains the backstop).
        if (childDepth > MAX_SUBAGENT_DEPTH) {
            return ToolResult.error(
                "Max subagent depth exceeded: $childDepth/$MAX_SUBAGENT_DEPTH — refusing to spawn '$subagentName'"
            )
        }

        logger.info {
            "[INVOKE_SUBAGENT] name=$subagentName, taskId=$taskId, depth=$childDepth, parentRunId=${parentRunId ?: "-"}"
        }

        val request = TurnRequest(
            taskId = taskId,
            userInput = goal,
            mode = mode,
            executionMode = executionMode,
            model = resolvedModel,
            provider = resolvedProvider,
            userContextRefs = contextRefs,
            runProfile = TurnRunProfile.SUBAGENT,
            profileOverrides = TurnProfileOverrides(
                subagentName = subagentName,
                systemPromptOverride = definition.systemPrompt,
                allowedTools = definition.allowedTools,
                disallowedTools = definition.disallowedTools,
                modelOverride = resolvedModel,
                providerOverride = resolvedProvider,
                maxIterationsOverride = definition.maxSteps,
                reasoningEffort = definition.reasoningEffort,
                parentRunId = parentRunId,
                agentInstanceId = agentInstanceId,
                depth = childDepth,
                // Keep only ancestors in the chain. The active subagent name is
                // stored separately in subagentName and validated against ancestors.
                subagentChain = parentChain,
                contextProfile = definition.contextProfile
            )
        )

        return try {
            val startedAt = clock()
            val result = runTurnCallback(request, turnListener, streamCallback)
            subagentResult(subagentName, childDepth, result, durationMs = clock() - startedAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stop is not a subagent failure - handing it back as a tool error would let the parent
            // model keep working on a turn the user already interrupted.
            throw e
        } catch (e: Exception) {
            ToolResult.error("Subagent '$subagentName' error: ${e.message}")
        }
    }

    /**
     * The subagent's answer in a fixed shape the caller can rely on: one line with the outcome, then
     * the files it changed, the questions it left open and its own report. A subagent that stopped
     * on a limit still hands over what it did (its report ends with its handoff note); only a stop
     * on an error is a tool error, and even then the files and the report go with it.
     */
    private fun subagentResult(subagentName: String, depth: Int, result: TurnResult, durationMs: Long): ToolResult {
        val status = when {
            result.success -> STATUS_DONE
            result.stopReason.isPartial -> STATUS_PARTIAL
            else -> STATUS_FAILED
        }
        val stopReason = result.stopReason.name.lowercase()
        val questions = result.unansweredQuestions.orEmpty()

        // A subagent that called NO tool inspected nothing and changed nothing, so whatever it
        // reports about the project is a claim, not an observation. Passing that up as a plain
        // success is how a delegated step gets silently skipped: a subagent answered "already
        // fixed in a prior iteration" (it was not), the caller believed it and finished the turn
        // SUCCESS with the file untouched (observed 2026-09-07 e2e run). Still a success - a purely
        // reasoning delegation legitimately needs no tool - but the caller is told the claim is
        // unverified so it can check before relying on it.
        val madeNoToolCalls = result.toolsUsed.isEmpty()
        if (madeNoToolCalls && result.success) {
            logger.warn {
                "Subagent '$subagentName' returned success with no tool calls " +
                    "(iterations=${result.iterations}) - its answer is unverified"
            }
        }

        val report = buildString {
            append("[subagent ").append(subagentName)
            append(" · status: ").append(status)
            append(" · stop: ").append(stopReason)
            append(" · iterations: ").append(result.iterations)
            append(" · tokens: ").append(result.tokensIn).append('/').append(result.tokensOut)
            append(" · time: ").append(formatDuration(durationMs)).append("]\n")
            appendSection("Files changed", result.filesChanged)
            appendSection("Open questions", questions)
            append("Report:\n").append(capReport(result.response))
            if (madeNoToolCalls && result.success) {
                append(
                    "\n\n[Subagent '$subagentName' made no tool calls: it read nothing and " +
                        "changed nothing, so the answer above is its claim, not a verified result. " +
                        "If it reports a file as already correct, verify that yourself before " +
                        "relying on it, or delegate again with a concrete instruction.]"
                )
            }
        }

        val metadata = mapOf(
            "subagent_name" to subagentName,
            "depth" to depth,
            "iterations" to result.iterations,
            // The subagent's turn already stored this usage on its own replies in this task.
            // Under tokens_in/tokens_out/cost_usd the tool message would carry it as well and
            // the task totals would count it twice.
            "delegated_tokens_in" to result.tokensIn,
            "delegated_tokens_out" to result.tokensOut,
            "delegated_cost_usd" to result.cost,
            "unanswered_questions" to questions.size,
            "made_no_tool_calls" to madeNoToolCalls,
            "status" to status,
            "stop_reason" to stopReason,
            "files_changed" to result.filesChanged,
            "duration_ms" to durationMs,
        )
        val filesChanged = result.filesChanged.takeIf { it.isNotEmpty() }
        return if (status == STATUS_FAILED) {
            ToolResult(success = false, error = report, filesChanged = filesChanged, metadata = metadata)
        } else {
            ToolResult(success = true, output = report, filesChanged = filesChanged, metadata = metadata)
        }
    }

    private fun StringBuilder.appendSection(title: String, items: List<String>) {
        if (items.isEmpty()) {
            append(title).append(": none\n")
            return
        }
        append(title).append(":\n")
        items.forEach { append("- ").append(it).append('\n') }
    }

    /** Keeps the start (what it set out to do) and the longer end (where it got, its handoff note). */
    private fun capReport(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) {
            return text
        }
        val tail = MAX_REPORT_CHARS - REPORT_HEAD_CHARS
        val omitted = text.length - REPORT_HEAD_CHARS - tail
        return text.take(REPORT_HEAD_CHARS) + "\n[... $omitted characters of the report left out ...]\n" + text.takeLast(tail)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "required" to listOf("subagent_name", "goal"),
            "properties" to mapOf(
                "subagent_name" to mapOf(
                    "type" to "string",
                    "description" to "Subagent name to invoke"
                ),
                "goal" to mapOf(
                    "type" to "string",
                    "description" to "Task goal for the subagent"
                ),
                "context_refs" to mapOf(
                    "type" to "array",
                    "description" to "Optional context references",
                    "items" to mapOf("type" to "string")
                )
            )
        )
    }

    /**
     * Parse and normalize the `context_refs` parameter from an `invoke_subagent` call.
     *
     * Behavior:
     *  - Drops blank / null entries.
     *  - Normalizes leading `./` and collapses redundant `/` so equivalent paths
     *    that vary only in surface form deduplicate cleanly.
     *  - Deduplicates while preserving insertion order (first occurrence wins).
     *  - Hard-caps at [MAX_CONTEXT_REFS]; remaining entries are dropped with a
     *    single warning. Without this guard, parallel `invoke_subagent` calls
     *    that all pass the project's full file list (observed: 21 paths × 3
     *    parallel agents in session 11) duplicate the same string into every
     *    subagent's prompt and inflate context cost linearly.
     */
    private fun parseContextRefs(raw: Any?): List<ContextReference> {
        val refs = raw as? List<*> ?: return emptyList()
        if (refs.isEmpty()) return emptyList()

        val seen = LinkedHashSet<String>(refs.size)
        var blank = 0
        var duplicate = 0
        var capped = 0
        for (value in refs) {
            val asString = value?.toString()?.trim().orEmpty()
            if (asString.isBlank()) {
                blank++
                continue
            }
            val normalized = normalizePath(asString)
            if (!seen.add(normalized)) {
                duplicate++
                continue
            }
            if (seen.size >= MAX_CONTEXT_REFS) {
                capped = refs.size - refs.indexOf(value) - 1
                break
            }
        }
        if (duplicate > 0 || capped > 0 || blank > 0) {
            logger.info {
                "[INVOKE_SUBAGENT] context_refs sanitized: kept=${seen.size}, " +
                    "duplicates=$duplicate, blank=$blank, dropped_over_cap=$capped, " +
                    "cap=$MAX_CONTEXT_REFS"
            }
        }
        return seen.map { ContextReference.file(it) }
    }

    private fun normalizePath(path: String): String {
        var p = path
        while (p.startsWith("./")) p = p.removePrefix("./")
        // Collapse `//` runs but preserve a leading `//` (UNC paths) — though sandbox
        // rejects absolute paths anyway, this is just defensive normalization.
        val leading = if (p.startsWith("//")) "//" else ""
        val body = p.removePrefix(leading).replace(Regex("/+"), "/")
        return leading + body
    }

    private companion object {
        const val STATUS_DONE = "done"
        const val STATUS_PARTIAL = "partial"
        const val STATUS_FAILED = "failed"

        /** A subagent's report in the parent's history; longer ones keep their start and end. */
        const val MAX_REPORT_CHARS = 16_384
        const val REPORT_HEAD_CHARS = 4_096

        /**
         * Upper bound for context_refs per `invoke_subagent` call. Generous enough
         * for real multi-file investigations (a code review across 30 modules) but
         * stops a model from blindly forwarding the entire project listing. When
         * exceeded the trailing entries are dropped — the model sees the first
         * [MAX_CONTEXT_REFS] paths in declaration order.
         */
        const val MAX_CONTEXT_REFS = 32
    }

    /**
     * A bare "Subagent not found: X" leaves a weak model guessing - it invented the name, so it
     * has nothing to correct against. List the real names and, for a plausible typo, point at the
     * nearest one (observed: `architecture-reviewer` -> `architect-reviewer`).
     */
    /** What decides whether a subagent may run next to others: its provider and its tool lists. */
    data class RunShape(val provider: String, val allowedTools: List<String>?, val disallowedTools: List<String>?)

    /** How [subagentName] would run, or null when no such subagent is defined. */
    fun runShape(subagentName: String): RunShape? {
        val definition = subagentRouterProvider()?.getSubagent(subagentName) ?: return null
        val (_, provider) = definition.resolveModel(configServiceProvider())
        return RunShape(provider, definition.allowedTools, definition.disallowedTools)
    }

    private fun subagentNotFoundMessage(router: SubagentRouter, requested: String): String {
        val names = runCatching { router.listSubagents(includeDisabled = false).map { it.name } }
            .getOrElse {
                logger.warn(it) { "[INVOKE_SUBAGENT] Failed to load subagent list for not-found hint" }
                emptyList()
            }
        val didYouMean = NameSuggestion.closest(requested, names)?.let { " Did you mean '$it'?" }.orEmpty()
        val available = if (names.isEmpty()) "" else " Available subagents: ${names.sorted().joinToString(", ")}."
        return "Subagent not found: '$requested'.$didYouMean$available"
    }

    private fun buildDynamicDescription(): String {
        val router = subagentRouterProvider()
            ?: return "Delegate a task to a specialized subagent. EXPENSIVE (spawns a full turn loop). Available: none."

        val subagents = runCatching { router.listSubagents(includeDisabled = false) }
            .getOrElse {
                logger.warn(it) { "[INVOKE_SUBAGENT] Failed to load subagent list for tool description" }
                return "Delegate a task to a specialized subagent. EXPENSIVE (spawns a full turn loop). Available: unavailable."
            }

        val names = subagents.joinToString("; ") { "\n- ${it.name}: ${it.description}" }
        return "Delegate a task to a specialized subagent. EXPENSIVE (spawns a full turn loop). " +
            "Available: \n$names."
    }
}
