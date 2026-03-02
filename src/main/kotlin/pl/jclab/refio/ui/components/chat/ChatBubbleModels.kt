package pl.jclab.refio.ui.components.chat

internal data class QuestionData(
    val questionId: String,
    val options: List<String>
)

internal data class CodeChangesData(
    val filePath: String,
    val addedLines: Int,
    val removedLines: Int,
    val snapshotId: String?
)

internal data class ExecutionSummaryMetadata(
    val changedFiles: List<ExecutionSummaryFile>,
    val stats: ExecutionSummaryStats?,
    val generatedAt: Long?,
    val model: String?,
    val provider: String?
)

internal data class ConversationSummaryMetadata(
    val summarizedCount: Int,
    val summaryIndex: Int,
    val timestamp: Long?,
    val firstMessageId: String?,
    val lastMessageId: String?
)

internal data class ExecutionSummaryFile(
    val filePath: String,
    val addedLines: Int,
    val removedLines: Int,
    val snapshotId: String?
)

internal data class ExecutionSummaryStats(
    val totalSteps: Int,
    val completedSteps: Int,
    val failedSteps: Int,
    val totalTokens: Int,
    val totalCostUsd: Double
)
