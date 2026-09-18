package pl.jclab.refio.core.debug

/**
 * Current schema version of [SessionDebugSnapshot] / the CLI `run.json`.
 * Bump when removing or renaming fields; additive fields do not require a bump.
 */
const val SESSION_DEBUG_SCHEMA_VERSION = 1

/**
 * Stable, serialization-friendly snapshot of one Refio session, produced by [SessionDebugExporter]
 * and emitted as `run.json` for the benchmark/e2e pipeline.
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
    /**
     * Present only for a multi-agent run (CLI `--multi-agent`): the agents in execution order, so a
     * consumer (e.g. the e2e harness) can assert dependency ordering was respected. Null for a normal
     * single-task run. Additive; the schema version is unchanged.
     */
    val multiAgent: MultiAgentInfo? = null,
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
        /**
         * Number of SUBTASK ROWS the turn opened, which is not the number of loop iterations and
         * not the number of tool calls either: one iteration can open several rows (a parallel read
         * batch) or none (a text-only iteration). Read [iterations] for loop efficiency.
         */
        val toolCallCount: Int,
        /**
         * Loop iterations the turn actually took. The efficiency number: how many times the loop
         * had to go round to get the job done. Additive field, 0 when unrecorded.
         */
        val iterations: Int = 0,
        /**
         * The iteration ceiling the loop ran under, so a run that used its whole budget is visible
         * as such instead of looking like a turn that simply took a while. Additive field.
         */
        val maxIterations: Int = 0,
        /**
         * Why the turn stopped, as a countable value (see [TurnStopReason]). [failureMarker] names
         * only five guardrail aborts; this covers every terminal exit, including the clean ones.
         * Additive field.
         */
        val stopReason: String = TurnStopReason.UNKNOWN.name,
        /**
         * True if any turn's prompt exceeded the model's context window.
         * A `true` here means input was silently truncated (Ollama) or rejected - the e2e
         * harness treats it as a failed run, not a success. Additive field.
         */
        val contextOverflow: Boolean = false,
        /**
         * The guardrail that aborted the turn, if any (e.g. "LOOP_ABORTED", "NOOP_WRITE_STALL") -
         * lets the e2e harness classify a failure by cause, not just by status. Null when the turn
         * did not hit a marked abort. Additive field.
         */
        val failureMarker: String? = null,
        /**
         * Outcome of the deterministic post-turn verification step (build/test run by the loop
         * code after a file-writing AGENT turn). `ran=false, attempts=0, result=null` when
         * verification never executed. Additive field.
         */
        val verification: VerificationInfo = VerificationInfo(),
        /**
         * How hard Refio's own steering had to work to get the model to the goal: nudges sent,
         * guardian re-entries, the worst repetition streak seen, the tool-error rate the guardrail
         * measured. No external agent has an equivalent, which is exactly what makes it worth
         * exporting - it separates the model's contribution from the harness's. Additive field.
         */
        val guardrails: GuardrailInfo = GuardrailInfo(),
        /**
         * What the context builder had to throw away to fit the window, summed over the whole run.
         * Answers whether Refio loses its own earlier findings on long tasks. Additive field.
         */
        val context: ContextInfo = ContextInfo(),
        /**
         * Files the turn wrote, with how many writing calls touched each and whether the file ended
         * up different from how it started. Catches the run that edited the wrong file and then put
         * it back, which a byte comparison against the fixture cannot see. Additive field.
         */
        val filesWritten: List<FileWriteInfo> = emptyList(),
        /**
         * Why the turn stopped using the provider's tool-calling channel part-way through, or null
         * when it never did. A turn that finishes on the text contract does more work per step, and
         * without this the slowdown reads as the agent being worse rather than as a failed request.
         * Additive field.
         */
        val nativeToolsDegraded: String? = null,
    )

    /** Deterministic verification outcome: whether it ran, how many attempts, PASSED/FAILED. */
    data class VerificationInfo(
        val ran: Boolean = false,
        val attempts: Int = 0,
        val result: String? = null,
    )

    /** Counters from the turn's own steering: how often it had to intervene, and how hard. */
    data class GuardrailInfo(
        /** "You have read a lot and written nothing" nudges. */
        val consolidationNudges: Int = 0,
        /** "You already rewrote this whole file" nudges. */
        val regenerationNudges: Int = 0,
        /** "You created an agent, now run it" nudges. */
        val subagentInvokeNudges: Int = 0,
        /** "Answer in the required shape" nudges. */
        val formatRetryNudges: Int = 0,
        /** Times a completion guardian sent the turn back into the loop. */
        val guardianReentries: Int = 0,
        /** Longest run of identical tool calls the repetition tracker saw. */
        val maxRepeatedCall: Int = 0,
        /** Tool-error rate in the guardrail's own window, as the guardrail measured it. */
        val toolErrorRate: Double = 0.0,
        /** Writing calls that changed nothing on disk. */
        val noopWrites: Int = 0,
    )

    /** Context budget and what fell out of it over the whole run. */
    data class ContextInfo(
        val budgetTokens: Int = 0,
        val usedTokens: Int = 0,
        /** Conversation messages trimmed away across the whole run. */
        val droppedMessages: Int = 0,
        /** Older tool steps omitted from the prompt across the whole run. */
        val droppedSteps: Int = 0,
        /** Section name to the number of times it did not fit at all. */
        val drops: Map<String, Int> = emptyMap(),
    )

    /** One path the turn wrote to. */
    data class FileWriteInfo(
        val path: String,
        /** How many writing calls touched this path. */
        val writes: Int,
        /** Whether the file differs from what it was when the turn started. */
        val netChanged: Boolean,
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
        /**
         * Per-call name + raw arguments JSON for the calls in [toolCalls], same order. Carries the
         * detail the bare-name [toolCalls] list drops, so an e2e assertion can match not just "this
         * tool ran" but "this tool ran with these arguments" (e.g. invoke_subagent with a specific
         * subagent_name). Additive field; older readers ignore it, the schema version is unchanged.
         */
        val toolCallDetails: List<ToolCallDetail> = emptyList(),
        /**
         * The row's own metadata JSON, e.g. `{"type":"guardian_nudge"}`. Without it a nudge the
         * loop injected is indistinguishable from any other system message except by matching its
         * English text. Additive field.
         */
        val metadata: String? = null,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val createdAt: Long,
    )

    /** One tool invocation: its name, the raw arguments JSON the model passed, and how it went. */
    data class ToolCallDetail(
        val name: String,
        val arguments: String,
        /**
         * Whether the CALL itself succeeded. Not the exit code of a shell command: a
         * `run_terminal_command` that ran a failing build is a successful call. Additive field,
         * defaults to true so older readers and older rows keep their meaning.
         */
        val ok: Boolean = true,
        /** The call's error text, truncated like every other content preview. Null when [ok]. */
        val error: String? = null,
    )

    /** Multi-agent run summary: the agents in the order they actually executed. */
    data class MultiAgentInfo(
        val agents: List<AgentRunInfo>,
    )

    data class AgentRunInfo(
        val agentName: String,
        val status: String,
        val success: Boolean,
        val startedAt: Long?,
        val completedAt: Long?,
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
        val costUsd: Double = 0.0,
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
