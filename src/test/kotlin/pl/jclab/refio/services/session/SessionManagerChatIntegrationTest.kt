package pl.jclab.refio.services.session

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.GetDefaultModelResponse
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.models.api.ChatCosts
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.services.orchestration.UserInteraction
import pl.jclab.refio.core.workflow.WorkflowOrchestrator
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.execution.StepExecutionService
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionManagerChatIntegrationTest {

    private lateinit var project: Project
    private lateinit var coreManager: CoreConnectionManager
    private lateinit var projectRouter: CoreApiRouter
    private lateinit var configService: ConfigService
    private lateinit var propertiesComponent: PropertiesComponent
    private lateinit var userInteraction: UserInteraction
    private var dbInitialized = false

    @BeforeEach
    fun setup() {
        if (!dbInitialized) {
            val tempDb = Files.createTempFile("refio-test-", ".db")
            tempDb.toFile().deleteOnExit()
            DatabaseFactory.init(tempDb.toString())
            dbInitialized = true
        }

        mockkObject(CoreConnectionManager.Companion)
        mockkStatic(PropertiesComponent::class)

        project = mockk(relaxed = true)
        every { project.basePath } returns "D:/_work/Saas/refio"
        every { project.name } returns "refio-test"
        every { project.getService(StepExecutionService::class.java) } returns mockk(relaxed = true)

        propertiesComponent = mockk(relaxed = true)
        every { PropertiesComponent.getInstance(project) } returns propertiesComponent
        every { propertiesComponent.getValue("refio.lastSession") } returns null

        configService = mockk(relaxed = true)
        every { configService.get(ConfigService.KEY_STREAMING_ENABLED, ConfigScope.APP, any(), any()) } returns "false"
        every { configService.isReadOnlyMode() } returns false
        every { configService.isNoEgressDefault() } returns false

        userInteraction = mockk(relaxed = true)
        every { userInteraction.isWaitingForResponse } returns MutableStateFlow(false)

        projectRouter = mockk(relaxed = true)
        every { projectRouter.getConfigService() } returns configService
        every { projectRouter.configService } returns configService
        every { projectRouter.userInteraction } returns userInteraction
        every { projectRouter.subagentRouter } returns null
        every { projectRouter.getLastSessionForProject(any()) } returns null
        every { projectRouter.getDefaultModel(ModelOperation.DEFAULT, any()) } returns GetDefaultModelResponse(
            operation = ModelOperation.DEFAULT.name,
            modelId = "gpt-4.1-mini",
            provider = "openai"
        )
        every { projectRouter.getModel(ModelOperation.DEFAULT, any()) } returns GetDefaultModelResponse(
            operation = ModelOperation.DEFAULT.name,
            modelId = "gpt-4.1-mini",
            provider = "openai"
        )
        every { projectRouter.createTask(any()) } returns TaskResponse(
            id = "task-1",
            name = "New Session",
            mode = TaskMode.CHAT.name,
            status = "PENDING",
            readOnly = false,
            pinned = false,
            executionMode = "INTERACTIVE",
            uiState = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        coreManager = mockk(relaxed = true)
        every { coreManager.getOrCreateProjectRouter(any(), any()) } returns projectRouter
        every { CoreConnectionManager.getInstance() } returns coreManager
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `should send chat via workflow orchestrator`() = runBlocking {
        val requestSlot = slot<WorkflowRequest>()
        val orchestrator = mockk<WorkflowOrchestrator>()
        coEvery { orchestrator.execute(capture(requestSlot), any()) } returns IntentResult.ChatResult(
            ChatResponse(
            requestId = "req-1",
            taskId = "task-1",
            messageId = "msg-1",
            output = "OK",
            costs = ChatCosts(tokensIn = 10, tokensOut = 20, usdEst = 0.001)
            )
        )
        every { projectRouter.workflowOrchestrator } returns orchestrator

        val sessionManager = SessionManager(project)

        val response = sessionManager.sendMessage(
            input = "Hello",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        assertEquals("user", response.role)
        assertEquals("Hello", response.content)

        val workflowRequest = requestSlot.captured
        assertEquals(TaskMode.CHAT, workflowRequest.uiState.mode)
        assertEquals("Hello", workflowRequest.uiState.input)
        assertEquals(false, workflowRequest.uiState.streamingEnabled)
        assertNotNull(workflowRequest.uiState.taskId)
    }
}
