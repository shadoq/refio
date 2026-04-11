package pl.jclab.refio.core.services.turn

/**
 * Stable signature for an arbitrary `body` argument value, used to make effect
 * keys for HTTP-style tools sensitive to the actual payload (not just URL).
 *
 * Without including the body in the effect key, every call to the same endpoint
 * with a different `action`/`payload` field would count as the same call. With
 * it, different bodies produce different keys and each action gets the full
 * per-target budget.
 */
private fun bodySignature(body: Any?): String = when (body) {
    null -> "_"
    is String -> body.trim().hashCode().toString()
    else -> body.toString().hashCode().toString()
}

/**
 * Hard-abort guardrails for turn-based loop execution.
 *
 * All guards here are strictly "abort" — they terminate the turn cleanly when
 * a real pathology is detected. No soft nudges, no retry loops: the turn loop
 * either keeps going or it stops. Interfering with a working agent via mid-loop
 * SYSTEM messages caused more problems than it solved (false positives, token
 * bloat, model confusion) so that entire layer was removed.
 */
class TurnGuardrails {

    /**
     * Tool error tracker using a sliding window. Aborts when the recent error
     * rate exceeds a threshold and enough data points have accumulated.
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
            return recentResults.size >= 5 && getErrorRate() > threshold
        }

        fun getStats(): String {
            val errorCount = recentResults.count { !it }
            val totalCount = recentResults.size
            val rate = if (totalCount > 0) (errorCount * 100 / totalCount) else 0
            return "$errorCount/$totalCount ($rate%)"
        }
    }

    sealed class LoopStatus {
        object OK : LoopStatus()
        data class ABORT(val reason: String) : LoopStatus()
    }

    /**
     * Unified repetition tracker. Keyed by `(tool, primary-target)` — e.g. the
     * edited file path, the shell command, the HTTP URL+body.
     *
     * Aborts the turn on two overlapping "stuck on same object" patterns:
     *
     *   1. **Count-based** — total invocations of the same (tool, target) crosses
     *      [abortThreshold]. Catches the "edit→run→edit→run on the same file
     *      15+ times" pattern.
     *
     *   2. **Output-based** — byte-identical normalized tail of a tool's output
     *      repeated [identicalOutputAbortThreshold] times in a row. Strongest
     *      "no progress" signal: even if each call is a textual variation, the
     *      environment keeps reporting the same thing.
     *
     * `run_code` is keyed by language only (`run_code@python`) — otherwise each
     * micro-edit to the script would reset the counter and the tracker could
     * never fire on the "tweak-and-rerun" failure mode.
     *
     * Tools without a meaningful primary target (read_file, grep_search, think,
     * memory) are not tracked: repetition on pure exploration is normal.
     */
    class TurnRepetitionTracker(
        private val abortThreshold: Int = 15,
        private val identicalOutputAbortThreshold: Int = 4,
        private val tailBytesForHash: Int = 800,
        private val maxHistory: Int = 200
    ) {
        private class State {
            var callCount: Int = 0
            val outputHashes: ArrayDeque<Int> = ArrayDeque()
        }

        private val states = mutableMapOf<String, State>()
        private val callOrder = ArrayDeque<String>(maxHistory)

        /**
         * Record one tool call and (optionally) its output. Returns [LoopStatus.ABORT]
         * if the turn should be terminated, [LoopStatus.OK] otherwise.
         *
         * `output` should be null for tool calls that failed, or for tools that
         * don't produce diagnostic output (edits). The output-hash signal is
         * meaningful only on successful runs — a failing call's error text is
         * tracked by [ToolErrorTracker] instead.
         */
        fun record(toolName: String, args: Map<String, Any?>, output: String? = null): LoopStatus {
            val key = effectKey(toolName, args) ?: return LoopStatus.OK

            if (callOrder.size >= maxHistory) {
                val evicted = callOrder.removeFirst()
                states[evicted]?.let {
                    it.callCount -= 1
                    if (it.callCount <= 0) states.remove(evicted)
                }
            }
            callOrder.addLast(key)

            val state = states.getOrPut(key) { State() }
            state.callCount += 1

            if (state.callCount >= abortThreshold) {
                return LoopStatus.ABORT(
                    "Tool $toolName has been invoked ${state.callCount} times on the same target ($key). " +
                        "The agent is stuck on this object."
                )
            }

            if (output != null) {
                val hash = normalizeTail(output).hashCode()
                state.outputHashes.addLast(hash)
                while (state.outputHashes.size > 16) state.outputHashes.removeFirst()

                var identical = 0
                for (h in state.outputHashes.reversed()) {
                    if (h == hash) identical++ else break
                }
                if (identical >= identicalOutputAbortThreshold) {
                    return LoopStatus.ABORT(
                        "Tool $toolName produced byte-identical output $identical times " +
                            "in a row on the same target ($key). Edits are not changing runtime behaviour."
                    )
                }
            }

            return LoopStatus.OK
        }

        fun stats(): String {
            val top = states.entries.sortedByDescending { it.value.callCount }.take(3)
                .joinToString(", ") { "${it.key}=${it.value.callCount}" }
            return "tracked=${states.size}, top=$top"
        }

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
                    // Language-only — see class docs for why we ignore code hash.
                    val lang = args["language"] as? String ?: return null
                    "run_code@$lang"
                }
                "http_request" -> {
                    val method = (args["method"] as? String)?.uppercase() ?: "GET"
                    val url = args["url"] as? String ?: return null
                    "http_request@$method@$url@${bodySignature(args["body"])}"
                }
                else -> null
            }
        }

        /**
         * Tail-only normalization: trim trailing whitespace, take last N bytes,
         * collapse whitespace, lowercase. Diagnostics live at the bottom of
         * stderr; the head usually contains variable garbage (timestamps,
         * ANSI codes, paths). Hashing only the tail keeps the signal stable
         * across runs.
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
    }

}
