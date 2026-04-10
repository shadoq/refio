package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.turn.TurnGuardrails.LoopStatus

/**
 * Guardrails for turn-based loop execution.
 * Protects against infinite loops and excessive error rates.
 */
class TurnGuardrails {

    /**
     * Tool error tracker using sliding window approach.
     * Tracks error rate over last N operations instead of consecutive failures.
     */
    class ToolErrorTracker(private val windowSize: Int = 10) {
        private val recentResults = ArrayDeque<Boolean>(windowSize)

        fun recordResult(success: Boolean) {
            if (recentResults.size >= windowSize) {
                recentResults.removeFirst()
            }
            recentResults.addLast(success)
        }

        fun getErrorRate(): Double {
            if (recentResults.isEmpty()) return 0.0
            return recentResults.count { !it }.toDouble() / recentResults.size
        }

        fun shouldAbort(threshold: Double = 0.7): Boolean {
            // Abort if >70% of last operations are errors AND we have enough data
            return recentResults.size >= 5 && getErrorRate() > threshold
        }

        fun getStats(): String {
            val errorCount = recentResults.count { !it }
            val totalCount = recentResults.size
            val rate = if (totalCount > 0) (errorCount * 100 / totalCount) else 0
            return "$errorCount/$totalCount ($rate%)"
        }
    }

    /**
     * Loop detector to prevent infinite loops when model repeatedly calls same tools.
     *
     * Consecutive and total-call tracking both use tool name only. This is intentionally
     * conservative: repeatedly invoking the same tool with different arguments is often
     * still a loop pattern that should trigger warnings or aborts.
     *
     * @param maxConsecutiveRepeats Abort after N consecutive calls to the same tool
     * @param maxSameToolTotal Abort after N total calls to the same tool
     * @param warnConsecutiveThreshold Warn after this many consecutive same-tool calls
     * @param warnTotalThreshold Warn after this many total same-tool calls
     */
    class LoopDetector(
        private val maxConsecutiveRepeats: Int = 5,
        private val maxSameToolTotal: Int = 15,
        private val warnConsecutiveThreshold: Int = 3,
        private val warnTotalThreshold: Int = 8,
        private val maxHistory: Int = 200
    ) {
        private val toolHistory = ArrayDeque<String>(maxHistory)
        private val toolCallCounts = mutableMapOf<String, Int>()

        /**
         * Record a tool call and check if it indicates a loop.
         * @return LoopStatus indicating if we should continue, warn, or abort
         */
        fun recordToolCall(toolName: String, arguments: String): LoopStatus {
            recordHistory(toolName)

            val totalCount = toolCallCounts[toolName] ?: 0
            val consecutiveCount = countConsecutiveRepeats(toolName)

            return when {
                consecutiveCount >= maxConsecutiveRepeats -> {
                    LoopStatus.ABORT("Tool $toolName called $consecutiveCount times consecutively - agent may be stuck")
                }
                totalCount >= maxSameToolTotal -> {
                    LoopStatus.ABORT("Tool $toolName called $totalCount times total - agent may be stuck")
                }
                consecutiveCount >= warnConsecutiveThreshold || totalCount >= warnTotalThreshold -> {
                    LoopStatus.WARN(
                        message = "Tool $toolName called $totalCount times (consecutive: $consecutiveCount)",
                        toolName = toolName,
                        totalCount = totalCount,
                        consecutiveCount = consecutiveCount
                    )
                }
                else -> LoopStatus.OK
            }
        }

        /**
         * Check if model is stuck producing empty tool calls.
         */
        fun recordEmptyToolCalls(): LoopStatus {
            val toolName = "__EMPTY_TOOL_CALLS__"
            recordHistory(toolName)

            val count = toolCallCounts[toolName] ?: 0
            return when {
                count >= 3 -> LoopStatus.ABORT("Model returned empty tool calls $count times - may be stuck")
                count >= 2 -> LoopStatus.WARN(
                    message = "Model returned empty tool calls twice",
                    toolName = toolName,
                    totalCount = count,
                    consecutiveCount = count
                )
                else -> LoopStatus.OK
            }
        }

        private fun countConsecutiveRepeats(toolName: String): Int {
            var count = 0
            for (entry in toolHistory.reversed()) {
                if (entry == toolName) {
                    count++
                } else {
                    break
                }
            }
            return count
        }

        private fun recordHistory(toolName: String) {
            if (toolHistory.size >= maxHistory) {
                toolHistory.removeFirst()
            }
            toolHistory.addLast(toolName)
            toolCallCounts[toolName] = (toolCallCounts[toolName] ?: 0) + 1
        }

        fun getStats(): String {
            val uniqueTools = toolCallCounts.keys.size
            val totalCalls = toolHistory.size
            val mostFrequent = toolCallCounts.maxByOrNull { it.value }
            return "unique=$uniqueTools, total=$totalCalls, mostFrequent=${mostFrequent?.key?.take(30)}(${mostFrequent?.value})"
        }
    }

    sealed class LoopStatus {
        object OK : LoopStatus()
        data class WARN(
            val message: String,
            val toolName: String,
            val totalCount: Int,
            val consecutiveCount: Int
        ) : LoopStatus()
        data class ABORT(val reason: String) : LoopStatus()
    }

    /**
     * Effect-keyed loop tracker.
     *
     * Counts repeated calls keyed by (toolName, primary-arg signature) — e.g. the
     * file path being edited, the command being run, the URL being fetched. This
     * catches the failure mode where the agent appears to vary its tools (read,
     * edit, run, edit, run...) but is functionally stuck on the same target object,
     * e.g. patching the same file 6 times in a row with the same diagnosis.
     *
     * The vanilla [LoopDetector] tracks by tool name only, which under-counts this
     * pattern: 5 alternating `code_editing`/`run_terminal_command` calls on the
     * same file look like "diverse tool usage" to a name-only counter, but to a
     * human reviewer they are obviously a loop.
     *
     * Tracking is FIRE-AND-FORGET on success or failure — both are equally
     * symptomatic of being stuck. The vanilla [ToolErrorTracker] only fires on
     * errors, which misses the case where the script keeps running successfully
     * but produces a wrong answer (the script's exit code is 0, the agent thinks
     * "tool succeeded", but the goal-level work is failing).
     *
     * Tools that don't have a meaningful "primary arg" (read_file, grep_search,
     * file_search, think, memory) are NOT tracked here — those are exploration
     * actions where repetition is normal and useful.
     *
     * @param warnThreshold Emit WARN after this many calls with the same effect key.
     * @param abortThreshold Emit ABORT after this many calls with the same effect key.
     * @param maxHistory Cap on the internal call key buffer (FIFO eviction).
     */
    class EffectKeyedLoopTracker(
        private val warnThreshold: Int = 4,
        private val abortThreshold: Int = 8,
        private val maxHistory: Int = 200
    ) {
        private val callKeys = ArrayDeque<String>(maxHistory)
        private val callCounts = mutableMapOf<String, Int>()

        /**
         * Record one tool call. Returns OK if this is the first few calls with this
         * effect key, WARN once the agent has crossed [warnThreshold], or ABORT past
         * [abortThreshold]. Tools without a tracked effect key always return OK.
         */
        fun record(toolName: String, args: Map<String, Any?>): LoopStatus {
            val key = effectKey(toolName, args) ?: return LoopStatus.OK

            if (callKeys.size >= maxHistory) {
                val evicted = callKeys.removeFirst()
                val newCount = (callCounts[evicted] ?: 1) - 1
                if (newCount <= 0) callCounts.remove(evicted) else callCounts[evicted] = newCount
            }
            callKeys.addLast(key)
            val newCount = (callCounts[key] ?: 0) + 1
            callCounts[key] = newCount

            return when {
                newCount >= abortThreshold -> LoopStatus.ABORT(
                    "Effect-keyed loop ABORT: $toolName has been invoked $newCount times " +
                        "on the same target ($key). The agent is stuck on this object."
                )
                newCount >= warnThreshold -> LoopStatus.WARN(
                    message = "Effect-keyed loop WARN: $toolName has been invoked $newCount times " +
                        "on the same target ($key). Continuing to act on the same object without " +
                        "qualitative change suggests the underlying model of the problem is wrong.",
                    toolName = toolName,
                    totalCount = newCount,
                    consecutiveCount = countConsecutiveAtTail(key)
                )
                else -> LoopStatus.OK
            }
        }

        /**
         * Reset state for a given effect key. Used by callers that want to clear the
         * counter after the agent has explicitly acknowledged the warning (e.g. after
         * an injected SYSTEM nudge).
         */
        fun reset(toolName: String, args: Map<String, Any?>) {
            val key = effectKey(toolName, args) ?: return
            callCounts.remove(key)
            // Keep callKeys so the FIFO eviction order is preserved; the count is
            // what gates the WARN/ABORT thresholds.
        }

        fun stats(): String {
            val top = callCounts.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { "${it.key}=${it.value}" }
            return "tracked=${callCounts.size}, top=$top"
        }

        private fun countConsecutiveAtTail(key: String): Int {
            var count = 0
            for (entry in callKeys.reversed()) {
                if (entry == key) count++ else break
            }
            return count
        }

        /**
         * Compute the effect key for a tool call, or `null` if this tool is not
         * effect-tracked. The key must be:
         * - Stable across semantically identical calls (so a counter can grow).
         * - Different for semantically different calls (so the agent can explore
         *   without false positives).
         * - Independent of the LLM's incidental phrasing (so we hash payloads
         *   instead of including their full text in the key).
         */
        private fun effectKey(toolName: String, args: Map<String, Any?>): String? {
            return when (toolName) {
                "code_editing", "multi_line_editor", "advance_code_editing", "create_new_file" -> {
                    val path = args["path"] as? String ?: return null
                    "$toolName@$path"
                }
                "multi_edit" -> {
                    val edits = args["edits"] as? List<*> ?: return null
                    val paths = edits.mapNotNull { (it as? Map<*, *>)?.get("path") as? String }.sorted()
                    if (paths.isEmpty()) return null
                    "multi_edit@${paths.joinToString(",")}"
                }
                "run_terminal_command" -> {
                    val command = args["command"] as? String ?: return null
                    "run_terminal_command@${command.trim()}"
                }
                "run_code" -> {
                    val lang = args["language"] as? String ?: return null
                    val code = (args["code"] as? String).orEmpty()
                    "run_code@$lang@${code.trim().hashCode()}"
                }
                "http_request" -> {
                    val method = (args["method"] as? String)?.uppercase() ?: "GET"
                    val url = args["url"] as? String ?: return null
                    "http_request@$method@$url"
                }
                else -> null
            }
        }
    }

    /**
     * Output-hash tracker — a goal-level progress detector for `run_terminal_command`
     * and `run_code`.
     *
     * The motivating failure mode: the agent runs a script, the script fails with
     * a particular error, the agent edits the script and re-runs, the script
     * fails with the *same* error, and the agent repeats — sometimes 5 or 10 times.
     * Per-tool-call success counters never fire because each individual call
     * "succeeded" (the tool ran). Per-error counters never fire because the error
     * is in the script's *output*, not the tool's exit. The only honest signal of
     * "no progress" is that the same command produces the same output.
     *
     * This tracker hashes a normalized form of the *tail* of each output (the part
     * that contains the actual diagnostic — exit message, stack trace, error block)
     * keyed by the same effect key used by [EffectKeyedLoopTracker]. After
     * [warnThreshold] consecutive identical hashes for the same key, it returns a
     * STRATEGY_CHANGE_REQUIRED warning telling the agent that re-running with
     * variations of the same approach is futile.
     *
     * @param warnThreshold Emit STRATEGY_CHANGE warning after this many consecutive
     *   identical-output runs on the same target.
     * @param tailBytesForHash Number of trailing bytes hashed. Tail (not head)
     *   because most environment-dependent garbage (timestamps, ANSI escape codes,
     *   absolute paths) lives in the head while the actual diagnostic lives at
     *   the bottom of stderr.
     */
    class OutputHashTracker(
        private val warnThreshold: Int = 3,
        private val tailBytesForHash: Int = 800
    ) {
        private val outputHistory = mutableMapOf<String, ArrayDeque<Int>>()

        /**
         * Record one tool result and return WARN if this is the [warnThreshold]-th
         * consecutive run with identical normalized-tail output for the same key,
         * else OK.
         */
        fun record(toolName: String, args: Map<String, Any?>, output: String): LoopStatus {
            val key = effectKey(toolName, args) ?: return LoopStatus.OK
            val hash = normalizeTail(output).hashCode()

            val history = outputHistory.getOrPut(key) { ArrayDeque() }
            history.addLast(hash)
            while (history.size > 16) history.removeFirst()

            var identical = 0
            for (h in history.reversed()) {
                if (h == hash) identical++ else break
            }

            if (identical >= warnThreshold) {
                return LoopStatus.WARN(
                    message = "STRATEGY_CHANGE_REQUIRED: $toolName has now produced byte-identical " +
                        "output $identical times in a row for the same target ($key). Re-running " +
                        "the same command with cosmetically different arguments will not produce " +
                        "a different result. Either change the underlying command, change the " +
                        "input data, or step back and verify your model of the problem.",
                    toolName = toolName,
                    totalCount = identical,
                    consecutiveCount = identical
                )
            }
            return LoopStatus.OK
        }

        /**
         * Clear the recorded history for a given (tool, args) pair. Use after the
         * agent has been nudged about the loop to give it a clean slate for the
         * next attempt.
         */
        fun reset(toolName: String, args: Map<String, Any?>) {
            val key = effectKey(toolName, args) ?: return
            outputHistory.remove(key)
        }

        /**
         * Tail-only normalization: trim trailing whitespace, take last N bytes,
         * collapse all whitespace runs to single spaces, lowercase. Drops the
         * head completely because variable parts of the head (timestamps,
         * environment dumps) would otherwise produce different hashes for
         * functionally identical runs.
         */
        private fun normalizeTail(output: String): String {
            val trimmed = output.trimEnd()
            val tail = if (trimmed.length > tailBytesForHash) {
                trimmed.takeLast(tailBytesForHash)
            } else trimmed
            return tail
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()
        }

        private fun effectKey(toolName: String, args: Map<String, Any?>): String? {
            return when (toolName) {
                "run_terminal_command" -> {
                    val command = args["command"] as? String ?: return null
                    "run_terminal_command@${command.trim()}"
                }
                "run_code" -> {
                    val lang = args["language"] as? String ?: return null
                    val code = (args["code"] as? String).orEmpty()
                    "run_code@$lang@${code.trim().hashCode()}"
                }
                else -> null
            }
        }
    }

    enum class AssistantIntent {
        IMPLEMENTATION,
        ANALYSIS,
        RESPONSE,
        UNKNOWN
    }

    companion object {
        /**
         * Check if agent is stuck in a read-only loop.
         * Triggers when agent executes only READ tools for N consecutive iterations
         * without executing any WRITE tool in the current turn (ADR-0044).
         */
        fun isReadOnlyLoop(
            mode: TaskMode,
            consecutiveReadOnlyIterations: Int,
            threshold: Int = 3
        ): Boolean {
            if (mode != TaskMode.AGENT) return false
            return consecutiveReadOnlyIterations >= threshold
        }

        /**
         * Build nudge message for missing intent field.
         */
        fun buildMissingIntentNudgeMessage(): String {
            return TurnNudgeBuilder.buildMissingIntentNudgeMessage()
        }

        /**
         * Build nudge message for invalid format.
         */
        fun buildInvalidFormatMessage(mode: TaskMode): String {
            return TurnNudgeBuilder.buildInvalidFormatMessage(mode.name)
        }
    }
}

// TaskMode import needs to match the actual package
private typealias TaskMode = pl.jclab.refio.core.db.TaskMode
