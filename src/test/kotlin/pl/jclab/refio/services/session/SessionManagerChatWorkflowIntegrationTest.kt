package pl.jclab.refio.services.session

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionManagerChatWorkflowIntegrationTest {

    private lateinit var project: Project
    private lateinit var coreManager: CoreConnectionManager
    private lateinit var projectRouter: CoreApiRouter
    private lateinit var propertiesComponent: PropertiesComponent
    private lateinit var llmClient: LLMClient
    private lateinit var keepAliveConnection: java.sql.Connection

    @BeforeEach
    fun setup() {
        val sharedDb = TestDatabase.createSharedInMemory()
        keepAliveConnection = sharedDb.keepAlive

        mockkObject(CoreConnectionManager.Companion)
        mockkStatic(PropertiesComponent::class)

        project = mockk(relaxed = true)
        every { project.basePath } returns "D:/_work/Saas/refio"
        every { project.name } returns "refio-test"
        every { project.getService(StepExecutionService::class.java) } returns mockk(relaxed = true)

        propertiesComponent = mockk(relaxed = true)
        every { PropertiesComponent.getInstance(project) } returns propertiesComponent
        every { propertiesComponent.getValue("refio.lastSession") } returns null

        llmClient = mockk()
        coEvery {
            llmClient.complete(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns LLMResponse(
            content = "Mocked response",
            usage = LLMUsage(inputTokens = 10, outputTokens = 20, totalTokens = 30),
            model = "qwen2.5:7b",
            provider = "ollama",
            cost = 0.0,
            finishReason = "stop"
        )

        projectRouter = CoreApiRouter(
            toolRegistry = null,
            projectRoot = null,
            ideProject = project,
            llmClientOverride = llmClient
        )
        projectRouter.getConfigService().set(ConfigService.KEY_STREAMING_ENABLED, "false")
        projectRouter.getConfigService().set(ConfigService.KEY_UI_SELECTED_MODEL, "openai/gpt-4.1-mini")

        coreManager = mockk(relaxed = true)
        every { coreManager.getOrCreateProjectRouter(any(), any()) } returns projectRouter
        every { CoreConnectionManager.getInstance() } returns coreManager
    }

    @AfterEach
    fun teardown() {
        keepAliveConnection.close()
        unmockkAll()
    }

    @Test
    fun `chat workflow persists mocked assistant response`() = runBlocking {
        val sessionManager = SessionManager(project)

        val userMessage = sessionManager.sendMessage(
            input = "Hello",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        assertEquals("user", userMessage.role)
        assertEquals("Hello", userMessage.content)

        val messages = sessionManager.messages.value
        assertTrue(messages.any { it.role == "assistant" && it.content == "Mocked response" })
    }
}
