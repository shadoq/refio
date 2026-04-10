package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

/**
 * Tool: think — explicit reasoning slot for the agent.
 *
 * No-op by design: the tool does not query anything, modify state, or call the LLM.
 * Its sole purpose is to give the model a structured place to *write down its
 * reasoning* between tool calls — which empirically improves multi-step task
 * performance, especially when the agent is approaching a loop, has hit a tool
 * error, or is about to commit to an irreversible action.
 *
 * When the agent should use it:
 * - Before calling the same tool a third time with similar arguments (break the loop)
 * - After a tool error, to plan the recovery instead of blindly retrying
 * - Before WRITE actions, to verify the diff plan against what was just read
 * - When the user request is vague — to enumerate gaps that need rag_search/read_file
 *
 * Why a tool and not just system prompt advice:
 * - Forces a discrete, observable reasoning step in the turn loop
 * - Survives prompt compression (it lives as a tool message, not a hint)
 * - Makes the model's plan auditable in the subtask trail
 *
 * Parameters:
 * - thought (string, required): the reasoning, plan, hypothesis, or question.
 *
 * Runtime dedup
 * -------------
 * Empirically the agent occasionally falls into a "think loop": it records the same
 * hypothesis ("the food calculation is wrong") two or three times in a row,
 * interleaved with patches that don't change the situation. The system prompt asks
 * it not to, but a textual prohibition does not survive prompt compression. So
 * `execute` keeps a small ring buffer of the most recent thought hashes (normalized
 * — lowercased, whitespace-collapsed) and refuses to record a thought whose
 * normalized form matches any of the last [DEDUP_WINDOW] entries. The error pushes
 * the agent toward a different action instead of just rephrasing the same idea.
 *
 * The ring is deliberately small and global. Cross-task collision risk is low
 * because real thoughts contain task-specific tokens (file names, identifiers,
 * exact errors), and the cost of a false positive is tiny — the agent rephrases
 * or, better, takes a non-think action.
 *
 * Returns the thought verbatim as the tool output. No side effects.
 */
class ThinkTool : Tool {

    override val name: String = "think"
    override val description: String =
        "Record a short reasoning step (plan, hypothesis, gap analysis) without performing any action. " +
            "Use BEFORE acting when: you are about to repeat a tool, just hit an error, are about to write/edit, " +
            "or the request is ambiguous and you need to enumerate what you don't yet know. " +
            "This tool has no side effects — it only forces a structured think before the next action. " +
            "The same thought cannot be recorded twice in a row — repeats are rejected."
    override val mode: ToolMode = ToolMode.READ_ONLY
    override val category: ToolCategory = ToolCategory.SYSTEM

    override fun validateParams(params: Map<String, Any>) {
        val thought = params["thought"] as? String
        if (thought.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'thought' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val thought = (params["thought"] as? String)?.trim()
            ?: return ToolResult.error(
                message = "Missing required parameter: 'thought'",
                recovery = "Call think with: {\"thought\": \"<your short plan or hypothesis>\"}"
            )
        if (thought.isBlank()) {
            return ToolResult.error(
                message = "Parameter 'thought' cannot be empty",
                recovery = "Provide non-empty reasoning text in the 'thought' parameter."
            )
        }

        val normalized = normalizeThought(thought)
        if (normalized.isEmpty()) {
            // Defensive: a thought that becomes empty after normalization is just whitespace/punctuation.
            return ToolResult.error(
                message = "Parameter 'thought' contains no meaningful content after normalization",
                recovery = "Provide concrete reasoning text — at least a few words of substance."
            )
        }

        val isDuplicate = synchronized(recentThoughtsLock) {
            val isDup = recentThoughts.contains(normalized)
            if (!isDup) {
                recentThoughts.addLast(normalized)
                while (recentThoughts.size > DEDUP_WINDOW) {
                    recentThoughts.removeFirst()
                }
            }
            isDup
        }

        if (isDuplicate) {
            return ToolResult.error(
                message = "Duplicate thought rejected: you have already recorded a thought " +
                    "with the same content within the last $DEDUP_WINDOW think calls. Recording " +
                    "the same hypothesis again does not move the task forward.",
                recovery = "Take a DIFFERENT action instead of re-thinking. Concrete options: " +
                    "(1) verify the assumption against an authoritative source — call the API, " +
                    "read the file you have not read yet, query a discovery endpoint; " +
                    "(2) recover the full middle of a previous tool output via " +
                    "memory(action=\"get_subtask_output\", subtask_id=\"<id>\", offset=0, limit=64000) " +
                    "if you have been reasoning only from a head+tail summary; " +
                    "(3) try a qualitatively different approach to the underlying problem " +
                    "(different tool, different file, different angle); " +
                    "(4) report to the user what you have tried and what is blocking you. " +
                    "Do NOT just rephrase the same thought to bypass this check."
            )
        }

        return ToolResult(
            success = true,
            output = "Thought recorded:\n$thought",
            metadata = mapOf(
                "thought_length" to thought.length,
                "no_side_effects" to true
            )
        )
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "thought" to mapOf(
                    "type" to "string",
                    "description" to "Short reasoning, plan, hypothesis, or list of unknowns. " +
                        "Keep it focused — 1-5 sentences. Will be returned verbatim, no LLM call. " +
                        "Cannot be a near-duplicate of one of the last $DEDUP_WINDOW thoughts."
                )
            ),
            "required" to listOf("thought")
        )
    }

    /**
     * Normalize a thought for dedup comparison: lowercase, collapse all whitespace
     * runs to a single space, strip leading/trailing whitespace, drop trivial
     * punctuation that the model varies between calls without changing meaning.
     *
     * Returns a stable string suitable for equality comparison. Two thoughts that
     * differ only in capitalization, line breaks, or trailing punctuation will
     * produce the same normalized form and be considered duplicates.
     */
    private fun normalizeThought(thought: String): String {
        return thought
            .lowercase()
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        /**
         * Number of most recent thoughts kept for dedup. A new thought matching any
         * entry in this window is rejected. Picked empirically: 5 is enough to catch
         * the observed "same hypothesis 2-4 times in a row" pattern, small enough
         * that genuinely different thoughts within a longer task are not blocked.
         */
        private const val DEDUP_WINDOW = 5

        private val recentThoughts: ArrayDeque<String> = ArrayDeque(DEDUP_WINDOW + 1)
        private val recentThoughtsLock = Any()

        /**
         * Test/maintenance hook — clears the dedup ring. Not used in production code,
         * exposed only so unit tests can isolate cases without relying on JVM lifecycle.
         */
        @JvmStatic
        fun clearDedupRingForTesting() {
            synchronized(recentThoughtsLock) {
                recentThoughts.clear()
            }
        }
    }
}
