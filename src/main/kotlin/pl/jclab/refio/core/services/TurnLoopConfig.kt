package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.TaskMode
import java.time.Duration

/**
 * Configuration for AgentTurnLoop behavior.
 *
 * Single loop, different configs per mode. Enables ADR-0028 enhancements:
 * - Auto-compaction when context window fills
 * - Prompt caching for static prefix
 * - Parallel tool execution for READ_ONLY tools
 * - Retry logic with exponential backoff
 * - Working memory integration
 *
 * @property maxIterations Maximum number of loop iterations
 * @property warningThreshold When to show iteration warning
 * @property parallelReadTools Enable parallel execution for READ_ONLY tools
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
 * @property maxConsecutiveReadOnlyIterations Max consecutive read-only iterations before nudging agent to write
 */
data class TurnLoopConfig(
    // Iteration limits
    val maxIterations: Int,
    val warningThreshold: Int,

    // Tool execution
    val parallelReadTools: Boolean,
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

    // Read-only budget guard (ADR-0044)
    val maxConsecutiveReadOnlyIterations: Int
) {
    companion object {
        /**
         * Default configuration for PLAN mode.
         *
         * PLAN mode is read-only, focused on analysis and planning.
         */
        fun plan() = TurnLoopConfig(
            maxIterations = 25,
            warningThreshold = 10,
            parallelReadTools = true,
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
            maxConsecutiveReadOnlyIterations = 15 // PLAN mode is read-only by design
        )

        /**
         * Default configuration for AGENT mode.
         *
         * AGENT mode has full read-write access and can execute all tools.
         */
        fun agent() = TurnLoopConfig(
            maxIterations = 50,
            warningThreshold = 18,
            parallelReadTools = true,
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
            verificationIterationThreshold = 10,
            maxConsecutiveReadOnlyIterations = 15 // Allow deeper analysis before nudging agent to write
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
