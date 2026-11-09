package pl.jclab.refio.core.services.maintenance

import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.longtask.LongTaskStatus

private val logger = dualLogger("TaskReconciler")

/**
 * Repairs the tasks a process left behind when it stopped in the middle of work (closed IDE,
 * Ctrl+C, crash): the task becomes INCOMPLETE, a long task's loop INTERRUPTED (so the user can
 * continue it), running steps CANCELED, and every tool call of the interrupted step gets a result,
 * as the model expects one. Tasks whose owner still runs are left alone: the IDE and the CLI can
 * share one database.
 */
class TaskReconciler(
    private val taskStateRepository: TaskStateRepository,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val isAlive: (pid: Long, startedAt: Long) -> Boolean = { pid, startedAt -> ProcessOwner.isAlive(pid, startedAt) },
) {

    /** Returns how many tasks were repaired. */
    fun reconcile(): Int {
        var repaired = 0
        for (row in taskStateRepository.findLeased()) {
            val pid = row.ownerPid ?: continue
            if (isAlive(pid, row.ownerStartedAt ?: 0L)) {
                continue
            }
            runCatching { repair(row) }
                .onSuccess { repaired++ }
                .onFailure { logger.warn(it) { "[RECONCILE] could not repair task ${row.taskId}" } }
        }
        return repaired
    }

    private fun repair(row: TaskStateRow) {
        val taskId = row.taskId
        val closed = closeInterruptedToolCalls(taskId)
        subtaskRepository.findByTaskId(taskId)
            .filter { it.status == TaskStatus.RUNNING }
            .forEach { subtaskRepository.updateStatus(it.id, TaskStatus.CANCELED) }
        taskRepository.update(taskId, status = TaskStatus.INCOMPLETE)
        if (row.loopStatus == LongTaskStatus.RUNNING.name) {
            taskStateRepository.upsert(row.copy(loopStatus = LongTaskStatus.INTERRUPTED.name, stopReason = PROCESS_STOPPED))
        }
        taskStateRepository.releaseLease(taskId)
        logger.info { "[RECONCILE] task $taskId: process ${row.ownerPid} is gone; task INCOMPLETE, $closed tool calls closed" }
    }

    /**
     * Answers the calls of each agent's newest tool-calling message that have no result. Only those
     * were running when the process stopped; an older call left open by Stop stays as it is, since a
     * result appended far below its call would only confuse the model.
     */
    private fun closeInterruptedToolCalls(taskId: String): Int {
        val history = chatMessageRepository.findByTaskId(taskId)
        val answered = history.filter { it.role == MessageRole.TOOL }.mapNotNull { it.toolCallId }.toSet()
        val newestCallsPerAgent = history
            .filter { it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty() }
            .groupBy { it.agentInstanceId }
            .values
            .map { it.last() }
        var closed = 0
        for (message in newestCallsPerAgent) {
            for (call in message.toolCalls.orEmpty()) {
                if (call.id.isBlank() || call.id in answered) {
                    continue
                }
                answerInterrupted(taskId, message, call.id)
                closed++
            }
        }
        return closed
    }

    private fun answerInterrupted(taskId: String, call: ChatMessage, toolCallId: String) {
        chatMessageRepository.createToolResult(
            taskId = taskId,
            toolCallId = toolCallId,
            subtaskId = null,
            result = INTERRUPTED_RESULT,
            agentInstanceId = call.agentInstanceId,
            agentName = call.agentName,
            agentDepth = call.agentDepth,
        )
    }

    companion object {
        const val INTERRUPTED_RESULT = "Interrupted: the Refio process stopped before this tool call finished."
        const val PROCESS_STOPPED = "the Refio process stopped during the round"
    }
}
