package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.TaskMode
import java.time.Duration

/**
 * Configuration for AgentTurnLoop behavior.
 *
 * Single loop, different configs per mode. Enables these enhancements:
 * - Auto-compaction when context window fills
 * - Prompt caching for static prefix
 * - Parallel tool execution for READ_ONLY tools
 * - Retry logic with exponential backoff
 * - Working memory integration
 *
 * @property maxIterations Maximum number of loop iterations
 * @property warningThreshold When to show iteration warning
 * @property parallelReadTools Enable parallel execution for READ_ONLY tools
 * @property maxParallelReadTools Cap on concurrent READ_ONLY tool execution within a single turn (chunks larger batches)
 * @property enableSnapshots Enable file snapshots before write operations
 * @property toolTimeout Timeout for a single in-process (CPU/filesystem) tool call
 * @property networkToolTimeout Timeout for a single tool call that waits on the network or a local model
 * @property enableAutoCompaction Unused by the loop: compaction is owned by ConversationSummaryService
 * @property compactionThreshold Unused by the loop: see enableAutoCompaction
 * @property summarizationThreshold Tool result char count for summarization
 * @property enablePromptCaching Enable prompt caching for static parts
 * @property cacheablePrefix Whether system prompt can be cached
 * @property maxRetries Maximum retry attempts for LLM calls
 * @property maxFormatRetries Maximum retry attempts for invalid structured responses
 * @property retryBackoffMs Base delay for retry backoff (ms)
 * @property errorRateThreshold Error rate threshold for aborting
 * @property errorWindowSize Sliding window size for error rate calculation
 * @property enableWorkingMemory Enable working memory integration
 * @property workingMemoryMaxEntries Maximum working memory entries to include
 * @property enableVerification Enable optional verification step
 * @property verificationIterationThreshold Iteration count to trigger verification
 */
data class TurnLoopConfig(
    // Iteration limits
    val maxIterations: Int,
    val warningThreshold: Int,

    // Tool execution
    val parallelReadTools: Boolean,
    val maxParallelReadTools: Int,
    val enableSnapshots: Boolean,
    val toolTimeout: Duration,
    val networkToolTimeout: Duration,

    // Context management
    val enableAutoCompaction: Boolean,
    val compactionThreshold: Double,
    val summarizationThreshold: Int,

    // Prompt caching
    val enablePromptCaching: Boolean,
    val cacheablePrefix: Boolean,

    // Error handling
    val maxRetries: Int,
    val maxFormatRetries: Int,
    val retryBackoffMs: Long,
    val errorRateThreshold: Double,
    val errorWindowSize: Int,

    // Working memory
    val enableWorkingMemory: Boolean,
    val workingMemoryMaxEntries: Int,

    // Verification
    val enableVerification: Boolean,
    val verificationIterationThreshold: Int,
) {
    companion object {
        /**
         * Budget for tools that wait on the network or on a local model (rag_search, web_search,
         * http_request, MCP calls) - see TurnToolExecutor's network-bound tool set.
         *
         * The same in PLAN and AGENT, because what bounds these calls is the remote side, not how
         * much thinking the mode allows. It has to clear the largest inner timeout with room for a
         * cold start and for queueing: http_request allows itself 60 s, the Ollama embedding client
         * 60 s, an MCP server 30 s by default, and embedding calls are serialized per endpoint, so a
         * cold local model can sit behind another request before it even starts loading. Below that
         * the outer net would fire first and report a false failure on a call that was still
         * working - which is what a 30 s PLAN budget did on a cold local embedding model.
         */
        private val NETWORK_TOOL_TIMEOUT: Duration = Duration.ofMinutes(3)

        /**
         * Default configuration for PLAN mode.
         *
         * PLAN mode is read-only, focused on analysis and planning.
         */
        fun plan() = TurnLoopConfig(
            // Raised over time (50 → 100 → 200) as the abort guardrails proved they carry the
            // real cost ceiling: ToolErrorTracker (≥70% error rate), TurnRepetitionTracker
            // (identical output × 4), the noop-write and blocked-tool trackers, and the cost
            // limit. The iteration cap is a backstop against runaway loops, not the budget, so
            // it should not be what stops a long but productive exploration.
            maxIterations = 200,
            warningThreshold = 50,
            parallelReadTools = true,
            // Bumped from 3 → 6: filesystem reads are cheap and IO-bound. Real-world batches
            // are 4-6 files at once (typical "read these 4 candidates" pattern from PLAN),
            // and capping at 3 forces a needless serialisation chunk for the 4th-6th items.
            maxParallelReadTools = 6,
            enableSnapshots = false,
            // In-process work only; network tools have their own, longer budget. Raised from 30s
            // because a cold local model behind an editing or analysis tool can legitimately take
            // longer than that, and a false timeout costs the whole turn.
            toolTimeout = Duration.ofMinutes(2),
            networkToolTimeout = NETWORK_TOOL_TIMEOUT,
            enableAutoCompaction = true,
            compactionThreshold = 0.85,
            summarizationThreshold = 5000,
            enablePromptCaching = true,
            cacheablePrefix = true,
            maxRetries = 3,
            maxFormatRetries = 3,
            retryBackoffMs = 1000,
            errorRateThreshold = 0.7,
            errorWindowSize = 10,
            enableWorkingMemory = true,
            workingMemoryMaxEntries = 20,
            enableVerification = false,
            verificationIterationThreshold = 0,
        )

        /**
         * Default configuration for AGENT mode.
         *
         * AGENT mode has full read-write access and can execute all tools.
         */
        fun agent() = TurnLoopConfig(
            // See plan() — same rationale; the guardrails, not the cap, bound the cost.
            maxIterations = 200,
            warningThreshold = 50,
            parallelReadTools = true,
            // See plan() — same rationale, bumped to 6.
            maxParallelReadTools = 6,
            enableSnapshots = true,
            // Longer than PLAN: AGENT runs the editing tools, which generate whole files through a
            // model, so the in-process work itself is model-bound.
            toolTimeout = Duration.ofMinutes(5),
            networkToolTimeout = NETWORK_TOOL_TIMEOUT,
            enableAutoCompaction = true,
            compactionThreshold = 0.80,
            summarizationThreshold = 3000,
            enablePromptCaching = true,
            cacheablePrefix = true,
            maxRetries = 3,
            maxFormatRetries = 3,
            retryBackoffMs = 1000,
            errorRateThreshold = 0.7,
            errorWindowSize = 10,
            enableWorkingMemory = true,
            workingMemoryMaxEntries = 50,
            enableVerification = true,
            verificationIterationThreshold = 40,
        )

        /**
         * Get configuration for task mode.
         *
         * @param mode Task mode (PLAN or AGENT)
         * @return TurnLoopConfig for the mode
         * @throws IllegalArgumentException if mode not supported
         */
        fun forMode(mode: TaskMode): TurnLoopConfig = when (mode) {
            TaskMode.PLAN -> plan()
            TaskMode.AGENT -> agent()
            else -> throw IllegalArgumentException("TurnLoop not supported for $mode")
        }
    }
}

/**
 * Preset configurations for TurnLoop.
 *
 * Provides default configs for different modes.
 */
object TurnLoopConfigs {
    val PLAN = TurnLoopConfig.plan()
    val AGENT = TurnLoopConfig.agent()

    /**
     * Get configuration for task mode.
     */
    fun forMode(mode: TaskMode): TurnLoopConfig = TurnLoopConfig.forMode(mode)
}
