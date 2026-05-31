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

        /**
         * @property incomplete true when the abort means the deliverable was never produced but the
         * turn is abandonment, not a hard error — maps to [pl.jclab.refio.core.db.TaskStatus.INCOMPLETE]
         * (set by the no-op-write streak in [TurnRepetitionTracker]). Defaults false: a plain loop
         * abort (byte-identical output, content chanting) is a failure, not an incomplete delivery.
         */
        data class ABORT(val reason: String, val incomplete: Boolean = false) : LoopStatus()
    }

    /**
     * Content-chanting detector — catches the "model echoes itself" failure mode
     * where the assistant message contains the same short phrase repeated many
     * times consecutively (a single sentence chanted 20-50× in a row, or a
     * runaway list generation that lost its termination condition).
     *
     * Modeled after Gemini CLI's `loopDetectionService` (`packages/core/src/services/
     * loopDetectionService.ts`). Unlike Gemini's per-chunk streaming check, this
     * version runs post-response on the assembled content — simpler to wire into
     * Refio's turn loop (which already gates on `contentForExtraction` after
     * streaming completes) and avoids touching the streaming path. The cost is
     * detecting one iteration later than Gemini; the benefit is no streaming-state
     * coupling.
     *
     * **Algorithm: consecutive repetition of word phrases.**
     *
     * For each word position and each phrase length in [PHRASE_WORD_LENGTHS],
     * walk forward and count how many times the same phrase appears immediately
     * after itself with no gap. If any such run reaches [MIN_REPEATS], abort.
     *
     * Why "consecutive" rather than "total count anywhere in text":
     *
     *   - **Chants are inherently adjacent.** Real model loops produce text like
     *     "X. X. X. X." — the same phrase touching itself repeatedly. Sparse
     *     repetition spread across the response is normal structured output
     *     (enumerations, bullet lists, "for each item, do Y" patterns) and must
     *     not trigger.
     *   - **Resilient to phrase-length variation.** Whether the model is
     *     chanting a single word ("Yes Yes Yes …"), a sentence ("I will check.
     *     I will check. …"), or a paragraph cycle, one of the phrase lengths in
     *     the scan set will catch it.
     *   - **Robust against char-offset misalignment.** Character-window
     *     approaches drift when the chant period doesn't align with the window
     *     step. Word-aligned phrases sidestep this entirely.
     *
     * Phrase length set covers: 1-word ("yes" chants), 2-word ("first second"
     * cycles), 3 / 5 / 10 — sentence-length and paragraph-cycle chants.
     * [MIN_REPEATS] of 10 means a phrase must touch itself 10 times in a row —
     * unmistakably pathological.
     */
    object ContentChantingDetector {

        private const val MIN_REPEATS = 10
        // Dense range up to 10 — sparse lists missed common 7-word sentence cycles.
        // Per phrase length the scan is O(W × phraseLen); total O(W × Σ phraseLen) =
        // O(W × 55) which is trivial even at 10k-word responses.
        private val PHRASE_WORD_LENGTHS = (1..10).toList()
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Inspect the model's final response text for chanting.
         * Returns [LoopStatus.ABORT] if any word-phrase that contains at least one
         * alphanumeric character appears immediately after itself at least [MIN_REPEATS]
         * times in a row; else [LoopStatus.OK]. Pure-symbol runs (box-drawing, table
         * borders, separator rules) are exempt — they are structure, not a generation loop.
         */
        fun inspect(content: String): LoopStatus {
            val rawWords = content.trim().split(WHITESPACE_REGEX).filter { it.isNotBlank() }
            if (rawWords.size < MIN_REPEATS) return LoopStatus.OK
            val words = rawWords.map { it.lowercase() }

            for (phraseLen in PHRASE_WORD_LENGTHS) {
                if (words.size < phraseLen * MIN_REPEATS) continue
                var i = 0
                while (i + phraseLen * MIN_REPEATS <= words.size) {
                    val phrase = words.subList(i, i + phraseLen)
                    var runCount = 1
                    var j = i + phraseLen
                    while (j + phraseLen <= words.size &&
                        words.subList(j, j + phraseLen) == phrase
                    ) {
                        runCount++
                        j += phraseLen
                    }
                    // Only treat a run as a chant when the phrase carries at least one
                    // alphanumeric character. Pure symbol/box-drawing runs ("│ │ │ …",
                    // "─ ─ ─", "| | |", "=== === ===") are STRUCTURE — ASCII diagrams,
                    // markdown tables, separator rules — which the user often explicitly
                    // asks for (session 188eb64b: "produce a combined architectural diagram
                    // (ASCII)" was killed by 14 consecutive "│"). A real generation loop
                    // chants words, not table borders.
                    if (runCount >= MIN_REPEATS && phrase.any { word -> word.any(Char::isLetterOrDigit) }) {
                        val sample = rawWords.subList(i, i + phraseLen).joinToString(" ")
                        return LoopStatus.ABORT(
                            "Content chanting detected: phrase \"${sample.take(80)}${if (sample.length > 80) "…" else ""}\" " +
                                "repeated $runCount times consecutively. " +
                                "The model is stuck in a generation loop — terminating the turn."
                        )
                    }
                    i++
                }
            }
            return LoopStatus.OK
        }
    }

    /**
     * Unified repetition tracker. Keyed by `(tool, primary-target)` — e.g. the
     * edited file path, the shell command, the HTTP URL+body.
     *
     * Aborts the turn on **byte-identical normalized tail** of a tool's output
     * repeated [identicalOutputAbortThreshold] times in a row. This is the
     * strongest "no progress" signal: the environment keeps reporting the same
     * thing despite the agent's attempted variations.
     *
     * The previous count-based abort (15× same (tool, target) regardless of
     * output) was removed — legitimate iterative work (refactor touching one
     * file 20 times with different edits, exploration reading many sections of
     * one large file) repeatedly tripped false positives. Real write loops are
     * caught by [ToolErrorTracker] (≥70% error rate over last 10) instead;
     * read-loops are caught by output-hash since identical query+output is the
     * pathology, not "same target many times".
     *
     * `run_code` is keyed by language only (`run_code@python`) — otherwise each
     * micro-edit to the script would reset the hash window and the tracker
     * could never fire on the "tweak-and-rerun" failure mode.
     *
     * Read-only exploration tools (read_file, grep_search, rag_search,
     * read_directory, file_search, code_intelligence) ARE tracked too — the
     * output-hash signal catches the pathology of "5 identical rag_search
     * calls returning the same 3 hits" (observed with weak models that ignore
     * turn-N tool results and re-issue the previous query verbatim). Pure no-op
     * tools (think, memory) stay un-tracked — their repetition is by design.
     */
    class TurnRepetitionTracker(
        private val identicalOutputAbortThreshold: Int = 4,
        private val tailBytesForHash: Int = 800,
        private val maxHistory: Int = 200,
        private val noopWriteAbortThreshold: Int = 3
    ) {
        private class State {
            var callCount: Int = 0
            val outputHashes: ArrayDeque<Int> = ArrayDeque()
            // Consecutive WRITE calls on this target that changed nothing (changeSummary.noop).
            // Reset by any effective (non-no-op) call on the same target. See [record].
            var consecutiveNoopWrites: Int = 0
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
        fun record(
            toolName: String,
            args: Map<String, Any?>,
            output: String? = null,
            isNoopWrite: Boolean = false
        ): LoopStatus {
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

            // No-op-write streak: a WRITE that changed nothing, repeated on the same target, means the
            // editing model cannot act on the request. Invisible to [ToolErrorTracker] (the tool returns
            // success=true) and to the output-hash abort below (the "no changes" message varies slightly
            // per call — token counts differ), so it needs its own counter. Aborts as INCOMPLETE: the
            // deliverable was never produced, but this is abandonment, not a hard error.
            if (isNoopWrite) {
                state.consecutiveNoopWrites += 1
                if (state.consecutiveNoopWrites >= noopWriteAbortThreshold) {
                    return LoopStatus.ABORT(
                        "Tool $toolName produced no change ${state.consecutiveNoopWrites} times in a row " +
                            "on the same target ($key) — the editing model cannot act on this request. " +
                            "Stopping the turn.",
                        incomplete = true
                    )
                }
            } else {
                // Any effective (non-no-op) call on this target clears the futile-edit streak.
                state.consecutiveNoopWrites = 0
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
                // Read-only exploration tools: tracked so the output-hash mechanism
                // catches identical repeats. Offset/limit/case_sensitive intentionally
                // ignored from the key — varying those is legitimate exploration; only
                // identical-query+identical-output repetition is pathological.
                "read_file" -> {
                    val path = args["path"] as? String ?: return null
                    "read_file@$path"
                }
                "read_directory" -> {
                    val path = args["path"] as? String ?: return null
                    "read_directory@$path"
                }
                "grep_search" -> {
                    val pattern = args["pattern"] as? String ?: return null
                    val path = (args["path"] as? String) ?: "."
                    "grep_search@$pattern@$path"
                }
                "file_search" -> {
                    val pattern = args["pattern"] as? String ?: return null
                    val path = (args["path"] as? String) ?: "."
                    "file_search@$pattern@$path"
                }
                "rag_search" -> {
                    val query = args["query"] as? String ?: return null
                    val contentType = (args["content_type"] as? String) ?: "*"
                    "rag_search@$query@$contentType"
                }
                "code_intelligence" -> {
                    val action = args["action"] as? String ?: return null
                    val target = (args["symbol"] as? String)
                        ?: (args["path"] as? String)
                        ?: return null
                    "code_intelligence@$action@$target"
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

    /**
     * Consecutive assistant-TEXT repetition detector. Catches the cross-iteration failure
     * mode where the model emits the SAME final text (no tool call) on successive terminal
     * points — typically after a guardian re-entry / format nudge pushes it back and it just
     * repeats itself verbatim (observed session a28cfcaa: the identical "…Now let me search
     * for the retry mechanism." sentence on two consecutive iterations, one of them a 40s LLM
     * call, with nothing detecting it).
     *
     * Why neither existing guard sees this:
     *   - [TurnRepetitionTracker] only records on TOOL execution; these iterations issue no
     *     tool call, so its `record` is never reached.
     *   - [ContentChantingDetector] measures repetition WITHIN one response (a phrase chanted
     *     10× in a row), not the SAME whole response repeated across iterations.
     *
     * Aborts when the normalized text is recorded [identicalRepeatAbortThreshold] times in a
     * row. Blank text is ignored (handled by the empty-envelope recovery paths). Fed only from
     * the no-tool-call branch, so a single terminal answer that exits immediately never trips
     * it — a second identical answer only exists because the loop re-entered.
     */
    class ConsecutiveTextRepetitionTracker(
        private val identicalRepeatAbortThreshold: Int = 2
    ) {
        private var lastHash: Int? = null
        private var consecutiveCount: Int = 0

        fun record(text: String): LoopStatus {
            val normalized = text.trim().replace(Regex("\\s+"), " ").lowercase()
            if (normalized.isBlank()) return LoopStatus.OK

            val hash = normalized.hashCode()
            if (hash == lastHash) {
                consecutiveCount++
            } else {
                lastHash = hash
                consecutiveCount = 1
            }

            if (consecutiveCount >= identicalRepeatAbortThreshold) {
                return LoopStatus.ABORT(
                    "Model produced byte-identical text $consecutiveCount times in a row with no " +
                        "tool call — no progress is being made. Terminating the turn."
                )
            }
            return LoopStatus.OK
        }
    }

}
