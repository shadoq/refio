package pl.jclab.refio.cli.tui.state

import pl.jclab.refio.core.api.SubtaskResponse

/**
 * Test helper — builds a [SubtaskResponse] with sensible defaults so tests only
 * specify the fields they care about. Used by TuiStepsView tests and related
 * fixtures after TUI DTO de-duplication (TuiSubtask removed).
 */
fun subtaskFixture(
    id: String,
    description: String,
    status: String = "NEW",
    kind: String = "",
    tokensIn: Int = 0,
    tokensOut: Int = 0,
    costUsd: Double = 0.0,
    model: String? = null,
    provider: String? = null,
    startedAt: Long? = null,
    finishedAt: Long? = null,
    resultSummary: String? = null,
    errorMessage: String? = null,
    result: String? = null,
    orderIndex: Int = 0,
    paramsJson: String? = null,
): SubtaskResponse = SubtaskResponse(
    id = id,
    taskId = "t-fixture",
    orderIndex = orderIndex,
    kind = kind,
    status = status,
    approvalStatus = "NONE",
    requiresApproval = false,
    approvedByUser = false,
    description = description,
    paramsJson = paramsJson,
    stepPlanJson = null,
    summary = null,
    result = result,
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorCode = null,
    errorMessage = errorMessage,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    costUsd = costUsd,
    latencyMs = 0,
    model = model,
    provider = provider,
    resultSummary = resultSummary,
    createdAt = 0L,
    updatedAt = 0L,
)
