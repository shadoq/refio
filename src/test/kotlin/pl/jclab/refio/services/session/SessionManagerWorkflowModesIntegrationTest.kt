package pl.jclab.refio.services.session

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.GetDefaultModelResponse
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.PlanRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.models.api.ChatCosts
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.orchestration.UserInteraction
import pl.jclab.refio.core.tools.base.*
import pl.jclab.refio.core.workflow.WorkflowOrchestrator
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionManagerWorkflowModesIntegrationTest {

    private lateinit var project: Project
    private lateinit var coreManager: CoreConnectionManager
    private lateinit var projectRouter: CoreApiRouter
    private lateinit var propertiesComponent: PropertiesComponent
    private lateinit var llmClient: LLMClient
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var keepAliveConnection: Connection
    private lateinit var stepExecutionService: StepExecutionService
    private val chatMessageRepository = ChatMessageRepository()
    private val subtaskRepository = SubtaskRepository()
    private val planRepository = PlanRepository()

    private val agentPrompt = """
        Write a snake game in javascript, css, html in one file. Build a classic Snake game on a 30x30 grid.
        The snake moves continuously in one of four directions and grows when eating food. If it hits a wall or itself,
        the game ends. Implement keyboard controls for player movement. Add a CPU-controlled snake mode that automatically
        searches for food. Add the three game modes: human vs human, cpu vs human, cpu vs cpu. Display the score during gameplay.
        Include a start menu and game over screen. Allow restarting the game with a keypress. Use file name "snake.html"
    """.trimIndent()

    @BeforeEach
    fun setup() {
        val sharedDb = TestDatabase.createSharedInMemory()
        keepAliveConnection = sharedDb.keepAlive

        mockkObject(CoreConnectionManager.Companion)
        mockkStatic(PropertiesComponent::class)

        project = mockk(relaxed = true)
        every { project.basePath } returns "D:/_work/Saas/refio"
        every { project.name } returns "refio-test"

        stepExecutionService = mockk(relaxed = true)
        every { project.getService(StepExecutionService::class.java) } returns stepExecutionService

        propertiesComponent = mockk(relaxed = true)
        every { PropertiesComponent.getInstance(project) } returns propertiesComponent
        every { propertiesComponent.getValue("refio.lastSession") } returns null

        toolRegistry = ToolRegistry()
        toolRegistry.register(FakeReadFileTool())
        toolRegistry.register(FakeAdvanceCodeEditingTool())

        llmClient = mockk()
        stubLlmResponses()

        projectRouter = CoreApiRouter(
            toolRegistry = toolRegistry,
            projectRoot = null,
            ideProject = project,
            llmClientOverride = llmClient
        )
        projectRouter.getConfigService().set(ConfigService.KEY_STREAMING_ENABLED, "false")

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
    fun `chat intent classification persists messages`() = runBlocking {
        val sessionManager = SessionManager(project)
        sessionManager.setIntentClassificationEnabled(true)
        sessionManager.setThinkingEnabled(true)
        sessionManager.setNoEgressEnabled(true)
        sessionManager.setSelectedModelConfig("auto")

        val userMessage = sessionManager.sendMessage(
            input = "Explain tool registry",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        val sessionId = sessionManager.activeSession.value?.id
        assertNotNull(sessionId)
        assertEquals("user", userMessage.role)

        val chatMessages = chatMessageRepository.findByTaskId(sessionId)
        assertTrue(chatMessages.any { it.role == MessageRole.ASSISTANT && it.content == "Mocked chat response" })
        assertTrue(subtaskRepository.findByTaskId(sessionId).isEmpty())
        assertEquals(null, planRepository.findBySessionId(sessionId))

        coVerify {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                systemPrompt = any(),
                maxTokens = any(),
                temperature = any(),
                responseFormat = any(),
                thinking = true,
                noEgressEnabled = true,
                stream = any(),
                onChunk = any(),
                taskId = any(),
                subtaskId = any(),
                source = "Chat",
                contextContent = any(),
                systemMessages = any(),
                kwargs = any()
            )
        }
        coVerify { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), "IntentClassifier", any(), any(), any()) }
    }

    @Test
    fun `plan mode creates subtasks and chat history`() = runBlocking {
        val sessionManager = SessionManager(project)
        sessionManager.createSession("Plan Session", TaskMode.PLAN, ExecutionMode.INTERACTIVE)
        sessionManager.setSelectedModelConfig("ollama/qwen2.5:7b")

        sessionManager.sendMessage(
            input = "Plan a simple refactor",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        val sessionId = sessionManager.activeSession.value?.id
        assertNotNull(sessionId)

        val chatMessages = chatMessageRepository.findByTaskId(sessionId)
        assertTrue(chatMessages.any { it.role == MessageRole.ASSISTANT && it.content.contains("Plan Steps") })

        val subtasks = subtaskRepository.findByTaskId(sessionId)
        assertTrue(subtasks.isNotEmpty())
        assertTrue(subtasks.all { it.requiresApproval })
        assertEquals(null, planRepository.findBySessionId(sessionId))
    }

    @Test
    fun `agent orchestration disabled executes advance code editing step`() = runBlocking {
        val sessionManager = SessionManager(project)
        sessionManager.createSession("Agent Session", TaskMode.AGENT, ExecutionMode.INTERACTIVE)
        sessionManager.setOrchestrationEnabled(false)
        sessionManager.setSelectedModelConfig("ollama/qwen2.5:7b")

        sessionManager.sendMessage(
            input = agentPrompt,
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        val sessionId = sessionManager.activeSession.value?.id
        assertNotNull(sessionId)

        val subtask = subtaskRepository.findByTaskId(sessionId).first()
        val executeResponse = sessionManager.executeCurrentStep(subtask.id)
        assertNotNull(executeResponse)

        val updatedSubtask = subtaskRepository.findById(subtask.id)
        assertNotNull(updatedSubtask)
        assertEquals(TaskStatus.SUCCESS, updatedSubtask.status)
        assertTrue(updatedSubtask.summary?.contains("Step summary") == true)
        assertEquals(null, planRepository.findBySessionId(sessionId))
    }

    @Test
    fun `agent orchestration enabled executes step and saves reflection message`() = runBlocking {
        val sessionManager = SessionManager(project)
        sessionManager.createSession("Agent Orchestrated", TaskMode.AGENT, ExecutionMode.INTERACTIVE)
        sessionManager.setOrchestrationEnabled(true)
        sessionManager.setSelectedModelConfig("ollama/qwen2.5:7b")

        sessionManager.sendMessage(
            input = agentPrompt,
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        val sessionId = sessionManager.activeSession.value?.id
        assertNotNull(sessionId)

        val subtask = subtaskRepository.findByTaskId(sessionId).first()
        val executeResponse = sessionManager.executeCurrentStep(subtask.id)
        assertNotNull(executeResponse)

        val chatMessages = chatMessageRepository.findByTaskId(sessionId)
        assertTrue(chatMessages.any { it.role == MessageRole.SYSTEM && it.content.contains("Reflection Analysis") })
        assertEquals(null, planRepository.findBySessionId(sessionId))
    }

    @Test
    fun `agent auto mode uses auto execution path`() = runBlocking {
        val sessionManager = SessionManager(project)
        sessionManager.createSession("Agent Auto", TaskMode.AGENT, ExecutionMode.AUTO)
        sessionManager.setOrchestrationEnabled(false)
        sessionManager.setSelectedModelConfig("ollama/qwen2.5:7b")

        sessionManager.sendMessage(
            input = agentPrompt,
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        val sessionId = sessionManager.activeSession.value?.id
        assertNotNull(sessionId)

        val subtasks = subtaskRepository.findByTaskId(sessionId)
        assertTrue(subtasks.isNotEmpty())
        assertTrue(subtasks.all { !it.requiresApproval })
        verifyAutoExecutionStarted()
        assertEquals(null, planRepository.findBySessionId(sessionId))
    }

    private fun stubLlmResponses() {
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
        } answers {
            val source = arg<String?>(13)
            val messages = arg<List<LLMMessage>>(2)
            when (source) {
                "IntentClassifier" -> llmResponse(intentClassificationJson())
                "Chat" -> llmResponse("Mocked chat response")
                "Planner" -> llmResponse(planJsonFor(messages))
                "StepPlanner" -> llmResponse(stepPlannerJson())
                "StepSummarizer" -> llmResponse("Step summary for advance_code_editing")
                "ReflectionEngine" -> llmResponse(reflectionJson())
                else -> llmResponse("Mocked response")
            }
        }
    }

    private fun planJsonFor(messages: List<LLMMessage>): String {
        val prompt = messages.firstOrNull()?.content.orEmpty()
        return if (prompt.contains("snake", ignoreCase = true)) {
            """
            {
              "plan": "Create snake.html with the requested game",
              "subtasks": [
                {
                  "name": "Generate snake.html",
                  "description": "Create the full snake game in a single HTML file",
                  "kind": "advance_code_editing",
                  "tool_args": {
                    "path": "snake.html",
                    "edit_description": "Create the full snake game HTML, CSS, and JS"
                  }
                }
              ]
            }
            """.trimIndent()
        } else {
            """
            {
              "plan": "Read README and outline the refactor",
              "subtasks": [
                {
                  "name": "Read README",
                  "description": "Read README.md to understand the current structure",
                  "kind": "read_file",
                  "tool_args": {
                    "path": "README.md"
                  }
                }
              ]
            }
            """.trimIndent()
        }
    }

    private fun stepPlannerJson(): String {
        return """
        {
          "tool": "advance_code_editing",
          "args": {
            "path": "snake.html",
            "edit_description": "Generate snake game file with multiple game modes"
          },
          "reasoning": "Need to create a new HTML file"
        }
        """.trimIndent()
    }

    private fun intentClassificationJson(): String {
        return """
        {
          "decision": "CHAT_RESPONSE",
          "reasoning": "Direct answer is sufficient"
        }
        """.trimIndent()
    }

    private fun reflectionJson(): String {
        return """
        {
          "decision": "CONTINUE",
          "reasoning": "Step completed successfully",
          "analysis": "",
          "actions": []
        }
        """.trimIndent()
    }

    private fun llmResponse(content: String): LLMResponse {
        return LLMResponse(
            content = content,
            usage = LLMUsage(inputTokens = 10, outputTokens = 20, totalTokens = 30),
            model = "qwen2.5:7b",
            provider = "ollama",
            cost = 0.0,
            finishReason = "stop"
        )
    }

    private fun verifyAutoExecutionStarted() {
        io.mockk.verify { stepExecutionService.startAutoExecution(any()) }
    }

    private class FakeAdvanceCodeEditingTool : Tool {
        override val name: String = "advance_code_editing"
        override val description: String = "Fake advance code editing tool"
        override val mode: ToolMode = ToolMode.WRITE
        override val category: ToolCategory = ToolCategory.FILE_MODIFYING

        override suspend fun execute(params: Map<String, Any>): ToolResult {
            val path = params["path"]?.toString() ?: "unknown"
            return ToolResult(
                success = true,
                output = "Generated $path",
                filesChanged = listOf(path)
            )
        }
    }

    private class FakeReadFileTool : Tool {
        override val name: String = "read_file"
        override val description: String = "Fake read file tool"
        override val mode: ToolMode = ToolMode.READ_ONLY
        override val category: ToolCategory = ToolCategory.DATA_PRODUCING

        override suspend fun execute(params: Map<String, Any>): ToolResult {
            val path = params["path"]?.toString() ?: "unknown"
            return ToolResult(
                success = true,
                output = "Contents of $path"
            )
        }
    }
}

class SessionManagerChatWorkflowIntegrationTest {

    private lateinit var project: Project
    private lateinit var coreManager: CoreConnectionManager
    private lateinit var projectRouter: CoreApiRouter
    private lateinit var propertiesComponent: PropertiesComponent
    private lateinit var llmClient: LLMClient
    private lateinit var keepAliveConnection: Connection

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
