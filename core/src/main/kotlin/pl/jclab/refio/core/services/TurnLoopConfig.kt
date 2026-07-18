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
 * @property toolTimeout Timeout for single tool execution
 * @property enableAutoCompaction Enable automatic conversation compaction
 * @property compactionThreshold Compaction threshold (0.0-1.0) of context window
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
         * Default configuration for PLAN mode.
         *
         * PLAN mode is read-only, focused on analysis and planning.
         */
        fun plan() = TurnLoopConfig(
            // Bumped from 50 → 100 to align with industry baselines (Gemini CLI 100,
            // Hermes 90). PLAN is read-only so iterations are cheap; the previous cap
            // was hitting prematurely on large-codebase exploration tasks. AGENT was
            // already at 100. Iteration cost is bounded by ToolErrorTracker (≥70%
            // error rate aborts) and TurnRepetitionTracker (output-hash × 4 aborts).
            maxIterations = 100,
            warningThreshold = 30,
            parallelReadTools = true,
            // Bumped from 3 → 6: filesystem reads are cheap and IO-bound. Real-world batches
            // are 4-6 files at once (typical "read these 4 candidates" pattern from PLAN),
            // and capping at 3 forces a needless serialisation chunk for the 4th-6th items.
            maxParallelReadTools = 6,
            enableSnapshots = false,
            toolTimeout = Duration.ofSeconds(30),
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
            maxIterations = 100,
            warningThreshold = 30,
            parallelReadTools = true,
            // See plan() — same rationale, bumped to 6.
            maxParallelReadTools = 6,
            enableSnapshots = true,
            toolTimeout = Duration.ofMinutes(2),
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
