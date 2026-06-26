package pl.jclab.refio.core.api

import pl.jclab.refio.core.db.TaskMode

/**
 * Logical operation slot used for model selection.
 *
 * DEFAULT   - generic chat / regular prompts
 * PLAN      - planning / reasoning heavy steps
 * CODING    - agent coding/execution flows
 * WEAK      - cheap auxiliary steps (summaries, reflections)
 * EMBEDDING - RAG embedding generation
 * STRONG    - powerful model for complex delegation (optional, no fallback)
 * EDITOR    - file-editing sub-model of the architect/editor split (optional, inherits CODING)
 */
enum class ModelOperation {
    DEFAULT,
    PLAN,
    CODING,
    WEAK,
    EMBEDDING,
    STRONG,
    EDITOR;

    companion object {
        fun fromTaskMode(mode: TaskMode): ModelOperation = when (mode) {
            TaskMode.CHAT -> DEFAULT
            TaskMode.PLAN -> PLAN
            TaskMode.AGENT -> CODING
        }
    }
}
