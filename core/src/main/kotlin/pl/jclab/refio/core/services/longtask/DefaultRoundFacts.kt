package pl.jclab.refio.core.services.longtask

import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.SnapshotService

/** [RoundFacts] read from the task's tool steps and plan. */
class DefaultRoundFacts(
    private val subtaskRepository: SubtaskRepository,
    private val agentPlanService: AgentPlanService,
    private val snapshotService: SnapshotService?,
) : RoundFacts {

    override fun filesWritten(taskId: String, sinceMs: Long): Int =
        subtaskRepository.findByTaskId(taskId).count {
            it.createdAt >= sinceMs && it.status == TaskStatus.SUCCESS && it.kind in WRITE_KINDS
        }

    override fun planSteps(taskId: String): List<AgentPlanStep> = agentPlanService.getPlan(taskId).orEmpty()

    /** A loop of many rounds writes many file snapshots; keep the newest ones only. */
    override fun afterRound(taskId: String) {
        snapshotService?.cleanupOldSnapshots(taskId)
    }

    private companion object {
        val WRITE_KINDS = setOf(
            SubtaskKind.CODE_EDITING, SubtaskKind.ADVANCE_CODE_EDITING, SubtaskKind.MULTI_LINE_EDITOR,
            SubtaskKind.CREATE_NEW_FILE, SubtaskKind.MULTI_EDIT, SubtaskKind.RENAME_SYMBOL,
        )
    }
}
