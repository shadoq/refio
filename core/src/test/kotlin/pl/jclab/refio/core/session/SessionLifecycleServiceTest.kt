package pl.jclab.refio.core.session

import pl.jclab.refio.core.session.SessionStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.ModelInfo
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SessionLifecycleServiceTest {

    /**
     * A restart must not silently resume the previous conversation: the user opens
     * the tool window on an empty chat, and the session is created on first prompt.
     */
    @Test
    fun `initialize leaves no active session even when the project has a previous one`() = runBlocking {
        mockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        every { transaction(any(), any<Function1<Transaction, Any>>()) } answers {
            val block = arg<Transaction.() -> Any>(1)
            block(mockk())
        }
        try {
            val projectRouter = mockk<CoreApiRouter>(relaxed = true)
            val configService = mockk<ConfigService>(relaxed = true)
            val stateManager = SessionStateManager()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            every { configService.get(ConfigKeys.UI_SELECTED_MODE.key) } returns TaskMode.AGENT.name
            every { projectRouter.taskRouter.getLastSessionForProject("project-1") } returns TaskResponse(
                id = "session-old",
                name = "Yesterday's work",
                mode = TaskMode.AGENT.name,
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
                modeSwitchMutex = Mutex(),
                projectId = "project-1",
                normalizedProjectPath = "C:\\\\project",
                scope = scope
            )

            service.initialize()
            service.awaitInitialization()

            assertNull(stateManager.getActiveSession())
            // The conversation is dropped, but the mode the user last worked in is not.
            assertEquals(TaskMode.AGENT, service.getSelectedMode())
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        }
    }

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

    /**
     * Redrawing the dropdown must not reach out to providers: a provider call can block for
     * seconds behind a running turn, and a timeout there used to empty the dropdown.
     */
    @Test
    fun `getAvailableModels can answer from cache without contacting providers`() = runBlocking {
        val projectRouter = mockk<CoreApiRouter>()
        val configService = mockk<ConfigService>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { projectRouter.configRouter.getModelsWithVisibility(fetchIfMissing = false) } returns listOf(
            modelInfo("glm-5.1", "zai", true)
        )

        val service = SessionLifecycleService(
            projectRouter = projectRouter,
            configService = configService,
            stateManager = SessionStateManager(),
            modeSwitchMutex = Mutex(),
            projectId = "project-1",
            normalizedProjectPath = "C:\\\\project",
            scope = scope
        )

        val models = service.getAvailableModels(fetchIfMissing = false)

        assertContentEquals(listOf("Zai/glm-5.1"), models)
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

    /**
     * The business rule: a concrete model picked in the dropdown must drive EVERY model slot for
     * the next session - including the CODING slot that `advance_code_editing` reads from
     * `ui.selected_model`. The old code inherited the model from config / the last session and pushed
     * that back over the live selection, so file generation silently ran on the stale provider.
     */
    @Test
    fun `a concrete dropdown model overrides the inherited config model for a new session`() = runBlocking {
        mockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        every { transaction(any(), any<Function1<Transaction, Any>>()) } answers {
            val block = arg<Transaction.() -> Any>(1)
            block(mockk())
        }
        try {
            val projectRouter = mockk<CoreApiRouter>(relaxed = true)
            val configService = mockk<ConfigService>(relaxed = true)
            val stateManager = SessionStateManager()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            every { configService.get(ConfigKeys.UI_SELECTED_MODE.key) } returns TaskMode.AGENT.name
            // Startup and last-session inheritance both resolve the stale persisted model.
            every {
                configService.get(ConfigKeys.UI_SELECTED_MODEL.key, ConfigScope.APP, taskId = null, projectId = null)
            } returns "Zai/glm-5.2"
            every { projectRouter.taskRouter.getLastSessionForProject("project-1") } returns null
            every { configService.getTyped(ConfigKeys.READ_ONLY_MODE) } returns false
            every { projectRouter.taskRouter.createTask(any()) } returns TaskResponse(
                id = "session-new",
                name = "Session (AGENT)",
                mode = TaskMode.AGENT.name,
                status = TaskStatus.PENDING.name,
                readOnly = false,
                pinned = false,
                executionMode = ExecutionMode.AUTO.name,
                uiState = null,
                createdAt = 1L,
                updatedAt = 2L
            )

            val service = SessionLifecycleService(
                projectRouter = projectRouter,
                configService = configService,
                stateManager = stateManager,
                modeSwitchMutex = Mutex(),
                projectId = "project-1",
                normalizedProjectPath = "C:\\\\project",
                scope = scope
            )

            service.initialize()
            service.awaitInitialization()
            // Startup loaded the stale persisted model into memory.
            assertEquals("Zai/glm-5.2", stateManager.getSelectedModel())

            // User picks a concrete model in the dropdown before the first session exists.
            stateManager.setSelectedModel("Openai/gpt-5.6-sol")

            service.createSession(name = "Session (AGENT)", mode = TaskMode.AGENT)

            // The new session - and thus the CODING slot resolved from ui.selected_model - must use
            // the dropdown model, not the inherited glm-5.2.
            assertEquals("Openai/gpt-5.6-sol", stateManager.getSelectedModel())

            // The CODING slot (advance_code_editing) resolves its model from the ui.selected_model
            // APP config, never from the task uiState blob. Creating the session must flush the
            // dropdown model there, otherwise file generation runs on the stale provider while the
            // turn LLM already uses the new one.
            verify {
                configService.set(
                    ConfigKeys.UI_SELECTED_MODEL.key,
                    "Openai/gpt-5.6-sol",
                    ConfigScope.APP
                )
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        }
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
