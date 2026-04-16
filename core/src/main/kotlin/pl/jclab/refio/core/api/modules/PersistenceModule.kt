package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.db.repositories.AgentEventSqlRepository
import pl.jclab.refio.core.db.repositories.AgentInstanceRepository
import pl.jclab.refio.core.db.repositories.AgentSessionRepository
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.db.repositories.DocumentationRepository
import pl.jclab.refio.core.db.repositories.ProjectAnalysisReportRepository
import pl.jclab.refio.core.db.repositories.PromptsRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository

/**
 * Persistence layer — all repositories in one place.
 *
 * Repositories are stateless SQL wrappers; creating them is cheap,
 * but centralizing their construction here keeps CoreApiRouter free of boilerplate.
 */
class PersistenceModule {
    val chatMessageRepository = ChatMessageRepository()
    val subtaskRepository = SubtaskRepository()
    val configRepository = ConfigRepository()
    val apiLogRepository = ApiLogRepository()
    val promptsRepository = PromptsRepository()
    val ragRepository = RagRepository()
    val documentationRepository = DocumentationRepository()
    val snapshotRepository = SnapshotRepository()
    val projectAnalysisReportRepository = ProjectAnalysisReportRepository()
    val agentSessionRepository = AgentSessionRepository()
    val agentInstanceRepository = AgentInstanceRepository()
    val taskRepository = TaskRepository()
    val agentEventSqlRepository = AgentEventSqlRepository()
}
