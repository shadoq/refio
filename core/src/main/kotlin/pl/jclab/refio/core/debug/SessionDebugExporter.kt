package pl.jclab.refio.core.debug

import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.utils.GsonInstance

/**
 * Builds a stable [SessionDebugSnapshot] for a session from core repositories, keyed by `taskId`,
 * and serializes it to the `run.json` contract (docs/0059 §5, docs/0063 §4).
 *
 * This is the single source of truth behind the CLI `--output json` and (later) the plugin
 * DebugPanel — both render the same snapshot instead of duplicating report logic.
 */
class SessionDebugExporter(
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val apiLogRepository: ApiLogRepository,
    private val chatMessageRepository: ChatMessageRepository,
) {

    /**
     * Assemble the snapshot. Never throws on missing data: an unknown [taskId] yields a snapshot
     * carrying an error rather than failing the export.
     */
    fun export(taskId: String, options: SessionDebugOptions): SessionDebugSnapshot {
        val task = taskRepository.findById(taskId)
        val subtasks = subtaskRepository.findByTaskId(taskId)
        val apiLogs = apiLogRepository.findByTaskId(taskId)
        val messages = chatMessageRepository.findByTaskId(taskId)

        val errors = buildList {
            if (task == null) add("Task not found: $taskId")
            subtasks.forEach { st -> st.errorMessage?.let { add("subtask[${st.orderIndex}] ${st.kind.name}: $it") } }
            apiLogs.forEach { log -> log.errorMessage?.let { add("apiLog ${log.model}: $it") } }
        }

        val warnings = buildList {
            if (options.level == DebugLevel.FULL || options.level == DebugLevel.JUDGE) {
                add("full/judge extras (active prompt snapshot, agent trace, context sections) are not yet exported — see docs/0063 Faza 4")
            }
        }

        val model = apiLogs.lastOrNull()?.model ?: subtasks.lastOrNull { it.llmModel != null }?.llmModel
        val provider = apiLogs.lastOrNull()?.provider ?: subtasks.lastOrNull { it.llmProvider != null }?.llmProvider

        val finalOutput = messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
            ?.content
            ?.preview(options.maxContentPreviewChars)

        val durationMs = if (task != null) (task.updatedAt - task.createdAt).coerceAtLeast(0) else 0L

        val session = SessionDebugSnapshot.SessionInfo(
            id = task?.id ?: taskId,
            name = task?.name ?: "",
            mode = task?.mode?.name ?: "",
            executionMode = task?.executionMode?.name ?: "",
            model = model,
            provider = provider,
            status = task?.status?.name ?: "UNKNOWN",
            tokensIn = task?.tokensIn ?: 0,
            tokensOut = task?.tokensOut ?: 0,
            costUsd = task?.costUsd ?: 0.0,
        )

        return SessionDebugSnapshot(
            schemaVersion = SESSION_DEBUG_SCHEMA_VERSION,
            run = SessionDebugSnapshot.RunInfo(
                debugLevel = options.level.name,
                durationMs = durationMs,
                startedAt = task?.createdAt,
                endedAt = task?.updatedAt,
            ),
            session = session,
            metrics = SessionDebugSnapshot.Metrics(
                durationMs = durationMs,
                tokensIn = session.tokensIn,
                tokensOut = session.tokensOut,
                costUsd = session.costUsd,
                apiCallCount = apiLogs.size,
                toolCallCount = subtasks.size,
                contextOverflow = ContextOverflowTracker.didOverflow(taskId),
                failureMarker = TurnFailureMarkerTracker.markerFor(taskId),
                verification = TurnVerificationTracker.summaryFor(taskId).let {
                    SessionDebugSnapshot.VerificationInfo(
                        ran = it.ran,
                        attempts = it.attempts,
                        result = it.result,
                    )
                },
            ),
            finalOutput = finalOutput,
            subtasks = if (options.includeSubtasks) {
                subtasks.map { st ->
                    SessionDebugSnapshot.SubtaskInfo(
                        orderIndex = st.orderIndex,
                        kind = st.kind.name,
                        status = st.status.name,
                        description = st.description.takeIf { it.isNotBlank() },
                        tokensIn = st.inputTokens,
                        tokensOut = st.outputTokens,
                        costUsd = st.costUsd,
                        latencyMs = st.latencyMs.toLong(),
                        model = st.llmModel,
                        provider = st.llmProvider,
                        errorMessage = st.errorMessage,
                    )
                }
            } else emptyList(),
            conversation = if (options.includeConversation) {
                messages.map { msg ->
                    SessionDebugSnapshot.MessageInfo(
                        role = msg.role.name,
                        agentName = msg.agentName,
                        contentPreview = msg.content.preview(options.maxContentPreviewChars),
                        toolCalls = msg.toolCalls?.map { it.name } ?: emptyList(),
                        toolCallDetails = msg.toolCalls?.map {
                            SessionDebugSnapshot.ToolCallDetail(name = it.name, arguments = it.arguments)
                        } ?: emptyList(),
                        tokensIn = msg.tokensIn,
                        tokensOut = msg.tokensOut,
                        createdAt = msg.createdAt,
                    )
                }
            } else emptyList(),
            apiLogs = if (options.includeApiLogs) {
                apiLogs.map { log ->
                    SessionDebugSnapshot.ApiLogInfo(
                        provider = log.provider,
                        model = log.model,
                        requestSource = log.requestSource,
                        httpStatus = log.httpStatus,
                        inputTokens = log.inputTokens,
                        outputTokens = log.outputTokens,
                        costUsd = log.costUsd,
                        latencyMs = log.latencyMs,
                        errorType = log.errorType,
                    )
                }
            } else emptyList(),
            errors = errors,
            warnings = warnings,
        )
    }

    /** Serialize a snapshot to pretty JSON (`run.json`). */
    fun toJson(snapshot: SessionDebugSnapshot): String = GsonInstance.prettyGson.toJson(snapshot)

    private fun String.preview(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars) + "… [+${length - maxChars} chars]"
}
