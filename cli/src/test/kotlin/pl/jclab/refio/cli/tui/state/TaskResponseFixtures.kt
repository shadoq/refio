package pl.jclab.refio.cli.tui.state

import pl.jclab.refio.core.api.TaskResponse

/**
 * Test fixture helper — covers the TaskResponse defaults that TUI tests don't care about
 * (readOnly/executionMode/uiState/…). Keeps call sites short while letting tests override
 * only the fields they actually assert on.
 */
fun taskResponseFixture(
    id: String = "task-1",
    name: String = "Session",
    mode: String = "CHAT",
    status: String = "SUCCESS",
    tokensIn: Int = 0,
    tokensOut: Int = 0,
    costUsd: Double = 0.0,
    createdAt: Long = 0,
    updatedAt: Long = 0,
    pinned: Boolean = false,
): TaskResponse = TaskResponse(
    id = id,
    name = name,
    mode = mode,
    status = status,
    readOnly = false,
    pinned = pinned,
    executionMode = "AUTO",
    uiState = null,
    createdAt = createdAt,
    updatedAt = updatedAt,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    costUsd = costUsd,
)
