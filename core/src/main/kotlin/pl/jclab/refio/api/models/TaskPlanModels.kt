package pl.jclab.refio.api.models

import com.google.gson.annotations.SerializedName

/**
 * Sub‑task DTO used by the UI to render the Steps Queue.
 * Contains only fields required for read‑only display.
 * Mirrors Python SubtaskResponse from agent/core/schema/schemas.py:164
 */
data class SubtaskDto(
    @SerializedName("id")
    val id: String,
    @SerializedName("task_id")
    val taskId: String,
    @SerializedName("order_index")
    val orderIndex: Int,
    @SerializedName("kind")
    val kind: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("approval_status")
    val approvalStatus: String = "not_required",
    @SerializedName("requires_approval")
    val requiresApproval: Boolean = false,
    @SerializedName("approved_by_user")
    val approvedByUser: Boolean = false,
    @SerializedName("description")
    val description: String? = null,
    @SerializedName("params_json")
    val paramsJson: String? = null,
    @SerializedName("step_plan_json")
    val stepPlanJson: String? = null,
    @SerializedName("summary")
    val summary: String? = null,
    @SerializedName("result")
    val result: String? = null,
    @SerializedName("started_at")
    val startedAt: Long? = null,
    @SerializedName("finished_at")
    val finishedAt: Long? = null,
    @SerializedName("error_code")
    val errorCode: String? = null,
    @SerializedName("error_message")
    val errorMessage: String? = null,
    @SerializedName("tokens_in")
    val tokensIn: Int? = null,
    @SerializedName("tokens_out")
    val tokensOut: Int? = null,
    @SerializedName("cost_usd")
    val costUsd: Double? = null,
    @SerializedName("latency_ms")
    val latencyMs: Int? = null,
    @SerializedName("model")
    val model: String? = null,
    @SerializedName("provider")
    val provider: String? = null,
    @SerializedName("result_summary")
    val resultSummary: String? = null,
    @SerializedName("created_at")
    val createdAt: Long? = null,
    @SerializedName("updated_at")
    val updatedAt: Long? = null,
    @SerializedName("completed_at")
    val completedAt: Long? = null
)
