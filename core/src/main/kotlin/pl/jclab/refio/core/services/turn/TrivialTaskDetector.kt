package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.tools.base.ToolSchema
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("TrivialTaskDetector")

/**
 * Cheap heuristic guard for the "create one file with explicit name" prompt shape.
 *
 * The system prompt has a STEP 4 rule "NO EXPLORATION FOR TRIVIAL TASKS" telling the
 * model to skip file_search / read_directory / read_file when the user already gave
 * a clear filename and content brief. Some models ignore that rule and burn 3+
 * iterations on pre-flight reads (gpt-5.4-mini in session 10, glm-* in PLAN mode).
 *
 * This detector enforces the rule at the harness level: when the user prompt
 * looks like a one-shot "create file X.html with Y" request, we restrict the
 * native tool schemas advertised on iteration 1 to write tools only. The model
 * then physically cannot call read_file/grep_search/etc., so it goes straight
 * to advance_code_editing or create_new_file.
 *
 * Iterations 2+ still get the full tool set, in case the write tool failed and
 * the model genuinely needs to read something.
 *
 * Detection is intentionally conservative — false negatives are fine (no harm,
 * just no enforcement), but false positives would break legitimate exploration.
 */
object TrivialTaskDetector {

    /**
     * Tools the harness allows on iteration 1 of a detected trivial create-file task.
     */
    private val WRITE_TOOLS = setOf(
        "advance_code_editing",
        "create_new_file",
        "code_editing",
        "multi_edit",
        "multi_line_editor",
    )

    /**
     * Imperative verbs that mark a task as a write-this-file request rather than
     * an analysis or question. We require both a verb AND an explicit filename.
     */
    private val CREATE_VERBS = listOf(
        "create", "write", "make", "generate", "build", "produce",
        "stwórz", "stworz", "napisz", "wygeneruj", "zrób", "zrob"
    )

    /**
     * Matches a bare filename anywhere in the prompt: `name.ext` where ext is one
     * of common code/markup extensions and name has no spaces. Backticks/quotes
     * around the filename are accepted.
     */
    private val FILENAME_PATTERN = Regex(
        """[`'"]?([A-Za-z0-9_./-]+\.(?:html?|md|json|ya?ml|txt|css|js|mjs|ts|tsx|jsx|kt|kts|py|rs|go|java|sh|toml|xml|svg|csv))[`'"]?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns true if the prompt looks like "create exactly one file named X with Y".
     *
     * Conditions (all must hold):
     *  1. Contains at least one create-verb
     *  2. Mentions exactly ONE filename (multiple filenames suggest multi-file work)
     *  3. Prompt body length under 2000 chars (longer prompts are usually multi-step)
     */
    fun isSingleFileCreateTask(userInput: String): DetectionResult {
        val normalized = userInput.trim()
        if (normalized.isEmpty() || normalized.length > 2000) {
            return DetectionResult.NotTrivial
        }
        val lower = normalized.lowercase()
        val verbHit = CREATE_VERBS.firstOrNull { lower.contains(it) }
            ?: return DetectionResult.NotTrivial

        val filenames = FILENAME_PATTERN.findAll(normalized)
            .map { it.groupValues[1] }
            .toSet()
        if (filenames.size != 1) {
            return DetectionResult.NotTrivial
        }
        val filename = filenames.first()
        return DetectionResult.SingleFileCreate(verb = verbHit, filename = filename)
    }

    /**
     * Filter native tool schemas down to write-only set when the prompt is a
     * single-file create. Returns the original list when nothing matches so
     * callers can chain unconditionally.
     */
    fun maybeRestrictForIteration1(
        schemas: List<ToolSchema>,
        userInput: String?,
        iteration: Int,
        modeName: String
    ): List<ToolSchema> {
        if (iteration != 1 || userInput == null) return schemas
        val detection = isSingleFileCreateTask(userInput)
        if (detection !is DetectionResult.SingleFileCreate) return schemas

        val restricted = schemas.filter { it.name in WRITE_TOOLS }
        if (restricted.isEmpty()) {
            // Mode permissions stripped all write tools (e.g. PLAN mode is read-only).
            // Trivial-task hint doesn't apply in read-only modes; fall back to full set.
            return schemas
        }
        if (restricted.size == schemas.size) return schemas

        logger.info {
            "[TRIVIAL_TASK_GUARD] Detected single-file create task " +
                "(verb='${detection.verb}', file='${detection.filename}', mode=$modeName) — " +
                "restricting iteration 1 tools: ${schemas.size} → ${restricted.size} " +
                "(allowed=${restricted.joinToString(",") { it.name }})"
        }
        return restricted
    }

    sealed interface DetectionResult {
        object NotTrivial : DetectionResult
        data class SingleFileCreate(val verb: String, val filename: String) : DetectionResult
    }
}
