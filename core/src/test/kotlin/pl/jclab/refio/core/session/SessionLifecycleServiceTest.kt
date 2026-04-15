package pl.jclab.refio.core.session

import pl.jclab.refio.core.session.SessionStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.ModelInfo
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class SessionLifecycleServiceTest {

    @Test
    fun `switchMode updates session mode`() = runBlocking {
        val projectRouter = mockk<CoreApiRouter>()
        val configService = mockk<ConfigService>(relaxed = true)
        val stateManager = SessionStateManager()
        val mutex = Mutex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val currentSession = Session(
            id = "session-1",
            name = "Test",
            mode = TaskMode.CHAT,
            status = TaskStatus.PENDING,
            createdAt = 1L,
            updatedAt = 1L,
            executionMode = ExecutionMode.INTERACTIVE
        )

        every { projectRouter.taskRouter.updateTask("session-1", any()) } returns TaskResponse(
            id = "session-1",
            name = "Test",
            mode = TaskMode.PLAN.name,
            status = TaskStatus.PENDING.name,
            readOnly = false,
            pinned = false,
            executionMode = ExecutionMode.INTERACTIVE.name,
            uiState = null,
            createdAt = 1L,
            updatedAt = 2L
        )

        val service = SessionLifecycleService(
            projectRouter = projectRouter,
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = mutex,
            projectId = "project-1",
            normalizedProjectPath = "C:\\\\project",
            scope = scope
        )

        val updated = service.switchMode(currentSession, TaskMode.PLAN)

        assertEquals(TaskMode.PLAN, updated.mode)
    }

    @Test
    fun `getAvailableModels returns only visible models`() = runBlocking {
        val projectRouter = mockk<CoreApiRouter>()
        val configService = mockk<ConfigService>(relaxed = true)
        val stateManager = SessionStateManager()
        val mutex = Mutex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { projectRouter.configRouter.getModelsWithVisibility() } returns listOf(
            modelInfo("gpt-4o-mini", "openai", true),
            modelInfo("gpt-4.1", "openai", false)
        )

        val service = SessionLifecycleService(
            projectRouter = projectRouter,
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = mutex,
            projectId = "project-1",
            normalizedProjectPath = "C:\\\\project",
            scope = scope
        )

        val models = service.getAvailableModels()

        assertContentEquals(listOf("Openai/gpt-4o-mini"), models)
    }

    @Test
    fun `getAvailableModels falls back to all models when none are visible`() = runBlocking {
        val projectRouter = mockk<CoreApiRouter>()
        val configService = mockk<ConfigService>(relaxed = true)
        val stateManager = SessionStateManager()
        val mutex = Mutex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { projectRouter.configRouter.getModelsWithVisibility() } returns listOf(
            modelInfo("qwen2.5:7b", "ollama", false),
            modelInfo("gpt-4o-mini", "openai", false)
        )

        val service = SessionLifecycleService(
            projectRouter = projectRouter,
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = mutex,
            projectId = "project-1",
            normalizedProjectPath = "C:\\\\project",
            scope = scope
        )

        val models = service.getAvailableModels()

        assertContentEquals(
            listOf("Ollama/qwen2.5:7b", "Openai/gpt-4o-mini"),
            models
        )
    }

    private fun modelInfo(id: String, provider: String, showInDropdown: Boolean) = ModelInfo(
        id = id,
        provider = provider,
        name = id,
        contextSize = 128000,
        capabilities = listOf("CHAT_COMPLETION"),
        pricing = null,
        showInDropdown = showInDropdown
    )
}
