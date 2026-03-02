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
     */
    class LoopDetector(
        private val maxConsecutiveRepeats: Int = 3,
        private val maxSameToolCallsTotal: Int = 5
    ) {
        private val toolCallHistory = mutableListOf<String>()  // List of signatures
        private val toolCallCounts = mutableMapOf<String, Int>()  // Signature -> count

        /**
         * Record a tool call and check if it indicates a loop.
         * @return LoopStatus indicating if we should continue, warn, or abort
         */
        fun recordToolCall(toolName: String, arguments: String): LoopStatus {
            val signature = createSignature(toolName, arguments)
            toolCallHistory.add(signature)
            toolCallCounts[signature] = (toolCallCounts[signature] ?: 0) + 1

            val totalCount = toolCallCounts[signature] ?: 0
            val consecutiveCount = countConsecutiveRepeats(signature)

            return when {
                consecutiveCount >= maxConsecutiveRepeats -> {
                    LoopStatus.ABORT("Same tool call repeated $consecutiveCount times consecutively: $toolName")
                }
                totalCount >= maxSameToolCallsTotal -> {
                    LoopStatus.ABORT("Same tool call made $totalCount times total: $toolName")
                }
                consecutiveCount >= 2 || totalCount >= 3 -> {
                    LoopStatus.WARN("Tool $toolName called $totalCount times (consecutive: $consecutiveCount)")
                }
                else -> LoopStatus.OK
            }
        }

        /**
         * Check if model is stuck producing empty tool calls.
         */
        fun recordEmptyToolCalls(): LoopStatus {
            val signature = "__EMPTY_TOOL_CALLS__"
            toolCallHistory.add(signature)
            toolCallCounts[signature] = (toolCallCounts[signature] ?: 0) + 1

            val count = toolCallCounts[signature] ?: 0
            return when {
                count >= 3 -> LoopStatus.ABORT("Model returned empty tool calls $count times - may be stuck")
                count >= 2 -> LoopStatus.WARN("Model returned empty tool calls twice")
                else -> LoopStatus.OK
            }
        }

        private fun createSignature(toolName: String, arguments: String): String {
            // Normalize arguments by removing whitespace and sorting keys
            val normalizedArgs = try {
                arguments.trim().replace(Regex("\\s+"), "")
            } catch (e: Exception) {
                arguments
            }
            return "$toolName:$normalizedArgs"
        }

        private fun countConsecutiveRepeats(signature: String): Int {
            var count = 0
            for (i in toolCallHistory.indices.reversed()) {
                if (toolCallHistory[i] == signature) {
                    count++
                } else {
                    break
                }
            }
            return count
        }

        fun getStats(): String {
            val uniqueTools = toolCallCounts.keys.size
            val totalCalls = toolCallHistory.size
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
