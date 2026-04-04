package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.turn.TurnGuardrails.LoopStatus

/**
 * Guardrails for turn-based loop execution.
 * Protects against infinite loops and excessive error rates.
 */
class TurnGuardrails {

    /**
     * Tool error tracker using sliding window approach.
     * Tracks error rate over last N operations instead of consecutive failures.
     */
    class ToolErrorTracker(private val windowSize: Int = 10) {
        private val recentResults = ArrayDeque<Boolean>(windowSize)

        fun recordResult(success: Boolean) {
            if (recentResults.size >= windowSize) {
                recentResults.removeFirst()
            }
            recentResults.addLast(success)
        }

        fun getErrorRate(): Double {
            if (recentResults.isEmpty()) return 0.0
            return recentResults.count { !it }.toDouble() / recentResults.size
        }

        fun shouldAbort(threshold: Double = 0.7): Boolean {
            // Abort if >70% of last operations are errors AND we have enough data
            return recentResults.size >= 5 && getErrorRate() > threshold
        }

        fun getStats(): String {
            val errorCount = recentResults.count { !it }
            val totalCount = recentResults.size
            val rate = if (totalCount > 0) (errorCount * 100 / totalCount) else 0
            return "$errorCount/$totalCount ($rate%)"
        }
    }

    /**
     * Loop detector to prevent infinite loops when model repeatedly calls same tools.
     *
     * Consecutive and total-call tracking both use tool name only. This is intentionally
     * conservative: repeatedly invoking the same tool with different arguments is often
     * still a loop pattern that should trigger warnings or aborts.
     *
     * @param maxConsecutiveRepeats Abort after N consecutive calls to the same tool
     * @param maxSameToolTotal Abort after N total calls to the same tool
     * @param warnConsecutiveThreshold Warn after this many consecutive same-tool calls
     * @param warnTotalThreshold Warn after this many total same-tool calls
     */
    class LoopDetector(
        private val maxConsecutiveRepeats: Int = 5,
        private val maxSameToolTotal: Int = 15,
        private val warnConsecutiveThreshold: Int = 3,
        private val warnTotalThreshold: Int = 8,
        private val maxHistory: Int = 200
    ) {
        private val toolHistory = ArrayDeque<String>(maxHistory)
        private val toolCallCounts = mutableMapOf<String, Int>()

        /**
         * Record a tool call and check if it indicates a loop.
         * @return LoopStatus indicating if we should continue, warn, or abort
         */
        fun recordToolCall(toolName: String, arguments: String): LoopStatus {
            recordHistory(toolName)

            val totalCount = toolCallCounts[toolName] ?: 0
            val consecutiveCount = countConsecutiveRepeats(toolName)

            return when {
                consecutiveCount >= maxConsecutiveRepeats -> {
                    LoopStatus.ABORT("Tool $toolName called $consecutiveCount times consecutively - agent may be stuck")
                }
                totalCount >= maxSameToolTotal -> {
                    LoopStatus.ABORT("Tool $toolName called $totalCount times total - agent may be stuck")
                }
                consecutiveCount >= warnConsecutiveThreshold || totalCount >= warnTotalThreshold -> {
                    LoopStatus.WARN("Tool $toolName called $totalCount times (consecutive: $consecutiveCount)")
                }
                else -> LoopStatus.OK
            }
        }

        /**
         * Check if model is stuck producing empty tool calls.
         */
        fun recordEmptyToolCalls(): LoopStatus {
            val toolName = "__EMPTY_TOOL_CALLS__"
            recordHistory(toolName)

            val count = toolCallCounts[toolName] ?: 0
            return when {
                count >= 3 -> LoopStatus.ABORT("Model returned empty tool calls $count times - may be stuck")
                count >= 2 -> LoopStatus.WARN("Model returned empty tool calls twice")
                else -> LoopStatus.OK
            }
        }

        private fun countConsecutiveRepeats(toolName: String): Int {
            var count = 0
            for (entry in toolHistory.reversed()) {
                if (entry == toolName) {
                    count++
                } else {
                    break
                }
            }
            return count
        }

        private fun recordHistory(toolName: String) {
            if (toolHistory.size >= maxHistory) {
                toolHistory.removeFirst()
            }
            toolHistory.addLast(toolName)
            toolCallCounts[toolName] = (toolCallCounts[toolName] ?: 0) + 1
        }

        fun getStats(): String {
            val uniqueTools = toolCallCounts.keys.size
            val totalCalls = toolHistory.size
            val mostFrequent = toolCallCounts.maxByOrNull { it.value }
            return "unique=$uniqueTools, total=$totalCalls, mostFrequent=${mostFrequent?.key?.take(30)}(${mostFrequent?.value})"
        }
    }

    sealed class LoopStatus {
        object OK : LoopStatus()
        data class WARN(val message: String) : LoopStatus()
        data class ABORT(val reason: String) : LoopStatus()
    }

    enum class AssistantIntent {
        IMPLEMENTATION,
        ANALYSIS,
        RESPONSE,
        UNKNOWN
    }

    companion object {
        /**
         * Check if agent is stuck in a read-only loop.
         * Triggers when agent executes only READ tools for N consecutive iterations
         * without executing any WRITE tool in the current turn (ADR-0044).
         */
        fun isReadOnlyLoop(
            mode: TaskMode,
            consecutiveReadOnlyIterations: Int,
            threshold: Int = 3
        ): Boolean {
            if (mode != TaskMode.AGENT) return false
            return consecutiveReadOnlyIterations >= threshold
        }

        /**
         * Build nudge message for missing intent field.
         */
        fun buildMissingIntentNudgeMessage(): String {
            return TurnNudgeBuilder.buildMissingIntentNudgeMessage()
        }

        /**
         * Build nudge message for invalid format.
         */
        fun buildInvalidFormatMessage(mode: TaskMode): String {
            return TurnNudgeBuilder.buildInvalidFormatMessage(mode.name)
        }
    }
}

// TaskMode import needs to match the actual package
private typealias TaskMode = pl.jclab.refio.core.db.TaskMode
