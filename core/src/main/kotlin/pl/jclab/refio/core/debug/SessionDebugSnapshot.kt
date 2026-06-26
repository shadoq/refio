package pl.jclab.refio.core.debug

/**
 * Current schema version of [SessionDebugSnapshot] / the CLI `run.json` (docs/0059 §5).
 * Bump when removing or renaming fields; additive fields do not require a bump.
 */
const val SESSION_DEBUG_SCHEMA_VERSION = 1

/**
 * Stable, serialization-friendly snapshot of one Refio session, produced by [SessionDebugExporter]
 * and emitted as `run.json` for the benchmark/e2e pipeline (docs/0059, docs/0063).
 *
 * Decoupled from internal DB entities on purpose: this is the public data contract, so field names
 * here are intentionally stable even if the underlying tables change.
 */
data class SessionDebugSnapshot(
    val schemaVersion: Int,
    val run: RunInfo,
    val session: SessionInfo,
    val metrics: Metrics,
    /** Best-effort final assistant output (truncated). Present at every level. */
    val finalOutput: String?,
    val subtasks: List<SubtaskInfo>,
    val conversation: List<MessageInfo>,
    val apiLogs: List<ApiLogInfo>,
    val errors: List<String>,
    val warnings: List<String>,
) {
    data class RunInfo(
        val debugLevel: String,
        val durationMs: Long,
        val startedAt: Long?,
        val endedAt: Long?,
    )

    data class SessionInfo(
        val id: String,
        val name: String,
        val mode: String,
        val executionMode: String,
        val model: String?,
        val provider: String?,
        val status: String,
        val tokensIn: Int,
        val tokensOut: Int,
        val costUsd: Double,
    )

    data class Metrics(
        val durationMs: Long,
        val tokensIn: Int,
        val tokensOut: Int,
        val costUsd: Double,
        val apiCallCount: Int,
        val toolCallCount: Int,
        /**
         * True if any turn's prompt exceeded the model's context window (docs/0057 Tier 3).
         * A `true` here means input was silently truncated (Ollama) or rejected — the e2e
         * harness (docs/0061) treats it as a failed run, not a success. Additive field.
         */
        val contextOverflow: Boolean = false,
    )

    data class SubtaskInfo(
        val orderIndex: Int,
        val kind: String,
        val status: String,
        val description: String?,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val costUsd: Double?,
        val latencyMs: Long?,
        val model: String?,
        val provider: String?,
        val errorMessage: String?,
    )

    data class MessageInfo(
        val role: String,
        val agentName: String?,
        val contentPreview: String,
        val toolCalls: List<String>,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val createdAt: Long,
    )

    data class ApiLogInfo(
        val provider: String,
        val model: String,
        val requestSource: String?,
        val httpStatus: Int?,
        val inputTokens: Int,
        val outputTokens: Int,
        val costUsd: Double,
        val latencyMs: Int,
        val errorType: String?,
    )
}
