package pl.jclab.refio.core.services.turn

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.debug.GuardrailStats
import pl.jclab.refio.core.debug.IterationSummary
import pl.jclab.refio.core.debug.TurnFailureMarkerTracker
import pl.jclab.refio.core.debug.TurnGuardrailStatsTracker
import pl.jclab.refio.core.debug.TurnIterationTracker
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.debug.TurnStopReasonTracker
import pl.jclab.refio.core.debug.VerificationSummary
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.tools.base.ToolSchema

internal sealed interface IterationOutcome {
    object Continue : IterationOutcome
    data class Finished(val result: TurnResult) : IterationOutcome
}

internal class SoftNudgeState {
    var consecutiveGatheringCalls = 0
    var consolidationNudges = 0
    val fullRegenCountByPath = mutableMapOf<String, Int>()
    var regenerationNudges = 0
    var consecutiveSubagentDefinitions = 0
    val definedSubagentNames = linkedSetOf<String>()
    var subagentInvokeNudges = 0
}

/**
 * Per-turn finalization and persistence binding shared by [TurnExecutor],
 * [TurnIterationRunner], and [TurnCompletionPipeline].
 */
internal class TurnPersistence(
    val taskId: String,
    val listener: TurnEventListener?,
    val runId: String,
    val parentRunId: String?,
    val depth: Int,
    val subagentMetadata: String?,
    val agentInstanceId: String?,
    val agentName: String?,
    val agentDepth: Int?,
    val mode: TaskMode,
    val maxIterations: Int,
    private val turnToolExecutor: TurnToolExecutor,
    private val turnFinalizer: TurnFinalizer,
    private val chatMessageRepository: ChatMessageRepository,
    private val onResetTurnState: () -> Unit,
) {
    var guardrailStats: (() -> GuardrailStats)? = null

    fun finish(
        result: TurnResult,
        persistAssistantMessage: Boolean,
        stopReason: TurnStopReason = TurnStopReason.UNKNOWN,
    ): TurnResult {
        var reason = when {
            stopReason != TurnStopReason.UNKNOWN -> stopReason
            result.success -> TurnStopReason.COMPLETED
            else -> TurnStopReason.UNKNOWN
        }
        if (TurnDeliverable.stalledWithoutWriting(
                success = result.success,
                mode = mode,
                depth = depth,
                fileWriteToolsExecutedInTurn = result.toolsUsed.count { turnToolExecutor.isFileWriteTool(it) },
            )
        ) {
            TurnFailureMarkerTracker.record(taskId, TurnFailureMarkerTracker.NO_FILE_WRITTEN)
            if (reason.isClean) {
                reason = TurnStopReason.NO_FILE_WRITTEN
            }
        }
        if (depth == 0) {
            TurnStopReasonTracker.record(taskId, reason)
            TurnIterationTracker.record(
                taskId,
                IterationSummary(used = result.iterations, limit = maxIterations),
            )
            guardrailStats?.let {
                TurnGuardrailStatsTracker.record(taskId, it())
            }
        }
        return turnFinalizer.completeTurn(
            taskId, result, listener, runId, parentRunId, depth,
            persistAssistantMessage = persistAssistantMessage,
            metadata = subagentMetadata,
            agentInstanceId = agentInstanceId,
            agentName = agentName,
            agentDepth = agentDepth,
            stopReason = reason,
        ).copy(stopReason = reason)
            .also { if (depth == 0) onResetTurnState() }
    }

    fun persist(
        role: MessageRole,
        content: String,
        thinking: String? = null,
        toolCalls: List<ToolCallData>? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        cost: Double? = null,
        metadata: String? = null,
    ) {
        chatMessageRepository.create(
            taskId = taskId,
            role = role,
            content = content,
            thinking = thinking,
            toolCalls = toolCalls,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            cost = cost,
            metadata = metadata,
            agentInstanceId = agentInstanceId,
            agentName = agentName,
            agentDepth = agentDepth,
        )
    }
}

/**
 * Immutable per-turn execution context resolved once before the iteration loop starts.
 */
internal data class TurnRunContext(
    val taskId: String,
    val mode: TaskMode,
    val executionMode: ExecutionMode,
    val listener: TurnEventListener?,
    val userContextRefs: List<ContextReference>,
    val runProfile: TurnRunProfile,
    val profileOverrides: TurnProfileOverrides?,
    val runId: String,
    val parentRunId: String?,
    val depth: Int,
    val userMessageStrategy: TurnExecutor.UserMessageStrategy,
    val evSessionId: String,
    val evSourceAgentId: String,
    val agentName: String?,
    val config: TurnLoopConfig,
    val maxIterations: Int,
    val subagentHasWriteTools: Boolean,
    val maxConsecutiveIdenticalFailures: Int,
    val persistAgentInstanceId: String?,
    val persistAgentName: String?,
    val persistAgentDepth: Int?,
    val effectiveStreamCallback: StreamCallback?,
    val effectiveModel: String,
    val effectiveProvider: String,
    val responseFormat: Map<String, String>?,
    val maxCostUsd: Double,
    val maxTurnMinutes: Long,
    val baseFormatNudges: Int,
    val turnStartMs: Long,
    val turnPersistence: TurnPersistence,
    val turnStateUpdater: (TurnPhase) -> Unit,
)

/**
 * Mutable state carried across iterations of a single turn.
 */
internal class TurnLoopState(
    errorWindowSize: Int,
    depth: Int,
    initialNativeToolSchemas: List<ToolSchema>?,
    val lastRequest: LastRequestHolder,
    val changedFiles: ChangedFiles,
) {
    var iteration: Int = 0
    var totalTokensIn: Int = 0
    var totalTokensOut: Int = 0
    var totalCost: Double = 0.0
    var lastStreamedAssistantText: String? = null

    var activeNativeToolSchemas: List<ToolSchema>? = initialNativeToolSchemas
    var nativeIgnoredStreak: Int = 0

    var writeToolsExecutedInTurn: Int = 0
    var fileWriteToolsExecutedInTurn: Int = 0
    var verificationToolsExecutedAfterWrite: Int = 0
    var noopWriteCount: Int = 0

    var consecutiveIdenticalFailures: Int = 0
    var lastFailureSignature: String? = null

    var turnIncompleteReason: String? = null
    var verificationAttempts: Int = 0
    var verificationSummary: VerificationSummary? = null

    val errorTracker = TurnGuardrails.ToolErrorTracker(windowSize = errorWindowSize)
    val deniedTracker = TurnGuardrails.ConsecutiveDeniedToolTracker()
    val blockedTracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
    val repetitionTracker = TurnGuardrails.TurnRepetitionTracker(
        identicalOutputAbortThreshold = if (depth >= 1) 2 else 4
    )
    val textRepetitionTracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
    val softNudges = SoftNudgeState()
    val guardianState = TurnGuardianState()
    val recoveryState = RecoveryState()
    val usedTools = mutableListOf<String>()

    fun toGuardrailStats(): GuardrailStats = GuardrailStats(
        consolidationNudges = softNudges.consolidationNudges,
        regenerationNudges = softNudges.regenerationNudges,
        subagentInvokeNudges = softNudges.subagentInvokeNudges,
        formatRetryNudges = recoveryState.nudgeCount,
        guardianReentries = guardianState.reentryCount,
        maxRepeatedCall = repetitionTracker.maxCallCount(),
        toolErrorRate = errorTracker.getErrorRate(),
        noopWrites = noopWriteCount,
    )

    fun buildResult(
        success: Boolean,
        response: String,
        incomplete: Boolean = false,
        verification: VerificationSummary? = null,
        distinctTools: Boolean = true,
        rejectedByUser: Boolean = false,
        rejectedToolName: String? = null,
        rejectionReason: String? = null,
    ): TurnResult = TurnResult(
        success = success,
        response = response,
        iterations = iteration,
        tokensIn = totalTokensIn,
        tokensOut = totalTokensOut,
        cost = totalCost,
        toolsUsed = if (distinctTools) usedTools.distinct() else usedTools.toList(),
        incomplete = incomplete,
        verification = verification,
        rejectedByUser = rejectedByUser,
        rejectedToolName = rejectedToolName,
        rejectionReason = rejectionReason,
    )
}
