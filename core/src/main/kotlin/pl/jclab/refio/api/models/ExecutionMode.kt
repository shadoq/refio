package pl.jclab.refio.api.models

/**
 * Execution mode for task workflow.
 *
 * Controls how subtasks are approved and executed:
 * - INTERACTIVE: Step-by-step approval required before each subtask
 * - AUTO: Autonomous execution until completion or error
 */
enum class ExecutionMode(val apiValue: String) {
    INTERACTIVE("interactive"),
    AUTO("auto");

    companion object {
        fun fromApiValue(value: String): ExecutionMode {
            return entries.find { it.apiValue == value }
                ?: throw IllegalArgumentException("Unknown execution mode: $value")
        }
    }
}