package pl.jclab.refio.core.debug

/**
 * Verbosity of a [SessionDebugSnapshot].
 *
 * - [MINIMAL] — metrics + final output + errors (auto-fill benchmark metrics)
 * - [STANDARD] — minimal + subtasks + conversation + session-scoped API logs
 * - [FULL] — standard + larger content previews (full prompt / agent trace are TODO, see warnings)
 * - [JUDGE] — view tuned for an LLM judge (same data as standard for now)
 */
enum class DebugLevel { MINIMAL, STANDARD, FULL, JUDGE }

/**
 * Options controlling what a [SessionDebugExporter] includes. Build from a [DebugLevel] via
 * [forLevel], or construct directly for fine-grained control.
 */
data class SessionDebugOptions(
    val level: DebugLevel = DebugLevel.STANDARD,
    val includeSubtasks: Boolean = true,
    val includeConversation: Boolean = true,
    val includeApiLogs: Boolean = true,
    /** Per-message / per-output content is truncated to this many chars to bound JSON size. */
    val maxContentPreviewChars: Int = 2000,
) {
    companion object {
        fun forLevel(level: DebugLevel): SessionDebugOptions = when (level) {
            DebugLevel.MINIMAL -> SessionDebugOptions(
                level = level,
                includeSubtasks = false,
                includeConversation = false,
                includeApiLogs = false,
            )
            DebugLevel.STANDARD -> SessionDebugOptions(level = level)
            DebugLevel.FULL -> SessionDebugOptions(level = level, maxContentPreviewChars = 20_000)
            DebugLevel.JUDGE -> SessionDebugOptions(level = level, maxContentPreviewChars = 8_000)
        }

        /** Parse a CLI `--debug-level` value (case-insensitive), defaulting to [DebugLevel.STANDARD]. */
        fun levelFromString(raw: String?): DebugLevel =
            raw?.trim()?.uppercase()?.let { runCatching { DebugLevel.valueOf(it) }.getOrNull() }
                ?: DebugLevel.STANDARD
    }
}
