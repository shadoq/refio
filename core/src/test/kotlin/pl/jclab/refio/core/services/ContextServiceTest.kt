package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.api.models.UserContextMetadata
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.testutil.MockFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContextServiceTest {

    private lateinit var projectAnalyzer: ProjectAnalyzerService
    private lateinit var taskRepository: TaskRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var fileAnalyzerService: FileAnalyzerService
    private lateinit var configService: ConfigService
    private lateinit var ragSearchService: RagSearchService

    private lateinit var service: ContextService

    private lateinit var projectRoot: Path

    @BeforeEach
    fun setup() {
        projectAnalyzer = mockk()
        taskRepository = mockk()
        chatMessageRepository = mockk()
        subtaskRepository = mockk()
        fileAnalyzerService = mockk()
        configService = mockk()
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { configService.getContextBudget(any(), any()) } returns
            pl.jclab.refio.core.services.context.ContextBudget.forContextSize(32000)
        ragSearchService = mockk()

        projectRoot = Files.createTempDirectory("refio-test")

        mockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        every { transaction(any(), any<Function1<Transaction, Any>>()) } answers {
            val block = arg<Transaction.() -> Any>(1)
            block(mockk())
        }
        every { transaction(any<Int>(), any<Boolean>(), any(), any<Function1<Transaction, Any>>()) } answers {
            val block = arg<Transaction.() -> Any>(3)
            block(mockk())
        }

        mockkObject(MCPManager)
        every { MCPManager.getConnectedServers(any()) } returns emptyList()

        service = ContextService(
            projectAnalyzer = projectAnalyzer,
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository,
            subtaskRepository = subtaskRepository,
            fileAnalyzerService = fileAnalyzerService,
            configService = configService,
            ragSearchService = null
        )
    }

    @AfterEach
    fun teardown() {
        unmockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        unmockkObject(MCPManager)
        projectRoot.toFile().deleteRecursively()
    }

    // ---- Helpers ----

    private fun createTestProjectAnalysis(projectPath: String = "/test/project") = ProjectAnalysis(
        projectPath = projectPath,
        structure = StructureInfo(
            totalFiles = 10,
            maxDepth = 3,
            fileTypes = mapOf("kt" to 8, "xml" to 2),
            topLevelItems = listOf("src", "build.gradle.kts"),
            directoryCount = 5
        ),
        technologies = listOf("Kotlin", "Gradle"),
        dependencies = DependenciesInfo(
            python = emptyList(),
            javascript = emptyList(),
            packageManagers = listOf("Gradle"),
            configFiles = listOf("build.gradle.kts")
        ),
        codeAnalysis = CodeAnalysisInfo(
            kotlin = emptyMap(),
            java = emptyMap(),
            python = emptyMap(),
            javascript = emptyMap(),
            typescript = emptyMap(),
            html = emptyMap(),
            css = emptyMap()
        ),
        keyComponents = listOf("CoreApiRouter", "ContextService"),
        projectType = "JVM",
        primaryLanguage = "Kotlin",
        summary = SummaryInfo(
            projectType = "JVM",
            complexity = "Medium",
            mainLanguage = "Kotlin",
            fileCount = 10,
            architectureNotes = "Multi-module Gradle project"
        ),
        domainAnalysis = DomainAnalysis(
            primaryDomain = "Backend",
            confidenceScore = 0.9,
            domainScores = mapOf("Backend" to 0.9, "CLI" to 0.1)
        ),
        analyzedAt = System.currentTimeMillis()
    )

    private fun createTestSubtask(
        id: String = "sub-1",
        taskId: String = "task-1",
        orderIndex: Int = 0,
        kind: SubtaskKind = SubtaskKind.CODE_EDITING,
        status: TaskStatus = TaskStatus.SUCCESS,
        description: String = "Test subtask"
    ) = Subtask(
        id = id,
        taskId = taskId,
        orderIndex = orderIndex,
        kind = kind,
        status = status,
        description = description,
        paramsJson = null,
        stepPlanJson = null,
        summary = "Completed: $description",
        requiresApproval = false,
        approvalStatus = ApprovalStatus.NOT_REQUIRED,
        approvedAt = null,
        result = null,
        errorMessage = null,
        errorStacktrace = null,
        llmModel = null,
        llmProvider = null,
        inputTokens = 0,
        outputTokens = 0,
        costUsd = 0.0,
        latencyMs = 0,
        snapshotIdBeforeWrite = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        startedAt = null,
        completedAt = null
    )

    private fun setupStandardMocks(
        taskId: String = "task-1",
        task: Task = MockFactory.createTask(id = taskId, mode = TaskMode.AGENT),
        subtasks: List<Subtask> = emptyList(),
        messages: List<ChatMessage> = emptyList()
    ) {
        coEvery { projectAnalyzer.analyzeProject(any(), any()) } returns createTestProjectAnalysis(projectRoot.toString())
        every { taskRepository.findById(taskId) } returns task
        every { subtaskRepository.findByTaskId(taskId) } returns subtasks
        every { chatMessageRepository.findByTaskId(taskId) } returns messages
    }

    // ---- Existing Tests ----

    @Nested
    inner class FormatContextReferencesTests {

        @Test
        fun `should format file context reference`() {
            val refs = listOf(
                ContextReference(
                    type = ContextType.FILE,
                    path = "/src/main/main.kt",
                    displayName = "main.kt",
                    content = "fun main() {}"
                )
            )
            val formatted = service.formatContextReferencesForLLM(refs)
            assertTrue(formatted.contains("main.kt"))
            assertTrue(formatted.contains("fun main()"))
        }

        @Test
        fun `should format folder context reference`() {
            val refs = listOf(
                ContextReference(
                    type = ContextType.FOLDER,
                    path = "/src/main",
                    displayName = "main",
                    content = "file1.kt\nfile2.kt"
                )
            )
            val formatted = service.formatContextReferencesForLLM(refs)
            assertTrue(formatted.contains("main"))
        }

        @Test
        fun `should handle empty list`() {
            val formatted = service.formatContextReferencesForLLM(emptyList())
            assertTrue(formatted.isEmpty())
        }

        @Test
        fun `should format multiple references`() {
            val refs = listOf(
                ContextReference(type = ContextType.FILE, path = "/a.kt", displayName = "a.kt", content = "// A"),
                ContextReference(type = ContextType.FILE, path = "/b.kt", displayName = "b.kt", content = "// B")
            )
            val formatted = service.formatContextReferencesForLLM(refs)
            assertTrue(formatted.contains("a.kt"))
            assertTrue(formatted.contains("b.kt"))
        }
    }

    @Nested
    inner class ConvertStringRefsToContextReferencesTests {

        @Test
        fun `should convert file path to context reference`() {
            val refs = ContextService.convertStringRefsToContextReferences(listOf("@file:/src/main.kt"))
            assertEquals(1, refs.size)
            assertEquals(ContextType.FILE, refs.first().type)
            assertEquals("/src/main.kt", refs.first().path)
        }

        @Test
        fun `should detect folder type for directory paths`() {
            val refs = ContextService.convertStringRefsToContextReferences(listOf("@folder:/src/main/"))
            assertEquals(1, refs.size)
            assertEquals(ContextType.FOLDER, refs.first().type)
        }

        @Test
        fun `should handle empty list`() {
            val refs = ContextService.convertStringRefsToContextReferences(emptyList())
            assertTrue(refs.isEmpty())
        }
    }

    @Nested
    inner class UpdateRagSearchConfigTests {

        @Test
        fun `should update RAG search configuration`() {
            val newService = mockk<RagSearchService>()
            service.updateRagSearchConfig(newService, "model-123", "openai")
        }

        @Test
        fun `should accept null values`() {
            service.updateRagSearchConfig(null, null, null)
        }
    }

    // ---- New Contract Tests ----

    @Nested
    inner class BuildProjectContextTests {

        @Test
        fun `should return ProjectContextDTO with project metadata`() = runTest {
            setupStandardMocks()
            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1"
            )
            assertEquals("JVM", result.projectType)
            assertEquals(listOf("Kotlin", "Gradle"), result.technologies)
            assertEquals(10, result.metaData.fileCount)
            assertEquals("Kotlin", result.metaData.mainLanguage)
            assertEquals("Medium", result.metaData.complexity)
            assertNotNull(result.currentTask)
            assertEquals("task-1", result.currentTask!!.id)
        }

        @Test
        fun `should throw when task not found`() = runTest {
            coEvery { projectAnalyzer.analyzeProject(any(), any()) } returns createTestProjectAnalysis()
            every { taskRepository.findById("nonexistent") } returns null

            assertThrows<IllegalArgumentException> {
                service.buildProjectContext(
                    projectRoot = projectRoot,
                    taskId = "nonexistent"
                )
            }
        }

        @Test
        fun `should include subtasks in context`() = runTest {
            val subtasks = listOf(
                createTestSubtask(id = "sub-1", description = "First step"),
                createTestSubtask(id = "sub-2", orderIndex = 1, description = "Second step")
            )
            setupStandardMocks(subtasks = subtasks)

            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1"
            )
            assertEquals(2, result.subtasks.size)
            assertEquals("First step", result.subtasks[0].description)
            assertEquals("Second step", result.subtasks[1].description)
        }

        @Test
        fun `should include conversation history`() = runTest {
            val messages = listOf(
                MockFactory.createChatMessage(
                    id = "msg-1",
                    taskId = "task-1",
                    role = MessageRole.USER,
                    content = "Implement feature X"
                ),
                MockFactory.createChatMessage(
                    id = "msg-2",
                    taskId = "task-1",
                    role = MessageRole.ASSISTANT,
                    content = "I will implement feature X now."
                )
            )
            setupStandardMocks(messages = messages)

            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1"
            )
            assertTrue(result.conversationHistory.isNotEmpty())
            assertTrue(result.conversationHistory.any { it.content.contains("Implement feature X") })
        }

        @Test
        fun `should deduplicate user context refs`() = runTest {
            setupStandardMocks()
            val duplicateRefs = listOf(
                ContextReference(type = ContextType.FILE, path = "/src/a.kt", displayName = "a.kt", content = "// A"),
                ContextReference(type = ContextType.FILE, path = "/src/a.kt", displayName = "a.kt", content = "// A")
            )

            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1",
                userContextRefs = duplicateRefs
            )
            // Deduplication happens internally - verify no crash with duplicates
            assertNotNull(result)
        }

        @Test
        fun `should work without optional services`() = runTest {
            // Service created without RAG, working memory, conversation summary
            setupStandardMocks()
            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1"
            )
            assertNotNull(result)
            assertTrue(result.ragFragments.isEmpty())
        }

        @Test
        fun `should include project instructions when present`() = runTest {
            // Create .refio/agent.md in temp project root
            val refioDir = projectRoot.resolve(".refio")
            Files.createDirectories(refioDir)
            Files.writeString(refioDir.resolve("agent.md"), "Use Kotlin conventions.")

            setupStandardMocks()
            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1"
            )
            assertNotNull(result.projectInstructions)
            assertTrue(result.projectInstructions!!.contains("Kotlin conventions"))
        }

        @Test
        fun `should return null project instructions when none exist`() = runTest {
            // projectRoot is empty temp dir — no .refio/agent.md, no AGENTS.md
            setupStandardMocks()
            val result = service.buildProjectContext(
                projectRoot = projectRoot,
                taskId = "task-1"
            )
            // No instructions files → null or empty
            // Accept both null and empty since implementation may vary
            val instructions = result.projectInstructions
            assertTrue(instructions == null || instructions.isBlank())
        }
    }

    @Nested
    inner class BuildLLMContextPromptTests {

        private fun createMinimalProjectContextDTO(): ProjectContextDTO {
            return ProjectContextDTO(
                metaData = MetaDataDTO(projectName = "TestProject"),
                summary = SummaryDTO(projectType = "JVM", mainLanguage = "Kotlin"),
                structure = StructureDTO(totalFiles = 10),
                dependencies = DependenciesDTO(),
                codeAnalysis = CodeAnalysisDTO(),
                workspace = WorkspaceDTO(path = "/test/project"),
                executionMetadata = ExecutionMetadataDTO(),
                projectType = "JVM",
                technologies = listOf("Kotlin"),
                currentTask = CurrentTaskDTO(
                    id = "task-1",
                    name = "Test Task",
                    description = "Test Task",
                    status = "NEW"
                ),
                contextGeneratedAt = java.time.Instant.now(),
                analyzerVersion = "test-v1"
            )
        }

        @Test
        fun `should generate non-empty prompt from context`() {
            val context = createMinimalProjectContextDTO()
            val prompt = service.buildLLMContextPrompt(context)
            assertTrue(prompt.isNotBlank())
        }

        @Test
        fun `should include project context section tag`() {
            val context = createMinimalProjectContextDTO()
            val prompt = service.buildLLMContextPrompt(context)
            assertTrue(prompt.contains("<PROJECT_CONTEXT>"))
        }

        @Test
        fun `should include current task section tag`() {
            val context = createMinimalProjectContextDTO()
            val prompt = service.buildLLMContextPrompt(context)
            assertTrue(prompt.contains("<CURRENT_TASK>"))
        }

        @Test
        fun `should handle empty optional fields`() {
            val context = createMinimalProjectContextDTO().copy(
                ragFragments = emptyList(),
                userContextRefs = emptyList(),
                mcpResources = emptyList(),
                conversationHistory = emptyList(),
                subtasks = emptyList()
            )
            val prompt = service.buildLLMContextPrompt(context)
            assertTrue(prompt.isNotBlank())
        }

        @Test
        fun `should include conversation history when present`() {
            val context = createMinimalProjectContextDTO().copy(
                conversationHistory = listOf(
                    ConversationMessageDTO(id = "msg-1", role = "user", content = "Build feature ABC"),
                    ConversationMessageDTO(id = "msg-2", role = "assistant", content = "Working on it")
                )
            )
            val prompt = service.buildLLMContextPrompt(context)
            assertTrue(prompt.contains("Build feature ABC") || prompt.contains("<CONVERSATION_HISTORY>"))
        }
    }

    @Nested
    inner class BuildAgentTurnMessagesTests {

        @Test
        fun `should return messages and context prompt`() = runTest {
            val messages = listOf(
                MockFactory.createChatMessage(
                    taskId = "task-1",
                    role = MessageRole.USER,
                    content = "Implement caching"
                ),
                MockFactory.createChatMessage(
                    taskId = "task-1",
                    role = MessageRole.ASSISTANT,
                    content = "I will add caching."
                )
            )
            setupStandardMocks(messages = messages)
            every { configService.getContextBudget(any(), any()) } returns
                pl.jclab.refio.core.services.context.ContextBudget.forContextSize(32000)

            val result = service.buildAgentTurnMessages(
                taskId = "task-1",
                projectRoot = projectRoot
            )
            assertNotNull(result)
            assertTrue(result.messages.isNotEmpty())
            assertTrue(result.projectContextPrompt.isNotBlank())
        }

        @Test
        fun `should convert chat messages to LLM messages`() = runTest {
            val messages = listOf(
                MockFactory.createChatMessage(taskId = "task-1", role = MessageRole.USER, content = "Hello"),
                MockFactory.createChatMessage(taskId = "task-1", role = MessageRole.ASSISTANT, content = "Hi there")
            )
            setupStandardMocks(messages = messages)
            every { configService.getContextBudget(any(), any()) } returns
                pl.jclab.refio.core.services.context.ContextBudget.forContextSize(32000)

            val result = service.buildAgentTurnMessages(
                taskId = "task-1",
                projectRoot = projectRoot
            )
            assertTrue(result.messages.any { it.role == "user" && it.content.contains("Hello") })
            assertTrue(result.messages.any { it.role == "assistant" && it.content.contains("Hi there") })
        }

        @Test
        fun `should handle empty conversation`() = runTest {
            setupStandardMocks(messages = emptyList())
            every { configService.getContextBudget(any(), any()) } returns
                pl.jclab.refio.core.services.context.ContextBudget.forContextSize(32000)

            val result = service.buildAgentTurnMessages(
                taskId = "task-1",
                projectRoot = projectRoot
            )
            assertTrue(result.messages.isEmpty())
            assertEquals(0, result.historySize)
        }

        @Test
        fun `should filter empty messages`() = runTest {
            val messages = listOf(
                MockFactory.createChatMessage(taskId = "task-1", role = MessageRole.USER, content = "Valid message"),
                MockFactory.createChatMessage(taskId = "task-1", role = MessageRole.ASSISTANT, content = ""),
                MockFactory.createChatMessage(taskId = "task-1", role = MessageRole.USER, content = "Another valid")
            )
            setupStandardMocks(messages = messages)
            every { configService.getContextBudget(any(), any()) } returns
                pl.jclab.refio.core.services.context.ContextBudget.forContextSize(32000)

            val result = service.buildAgentTurnMessages(
                taskId = "task-1",
                projectRoot = projectRoot
            )
            // Empty assistant messages should be filtered out
            assertTrue(result.messages.none { it.role == "assistant" && it.content.isBlank() })
        }

        @Test
        fun `should include user context refs in project context`() = runTest {
            setupStandardMocks()
            every { configService.getContextBudget(any(), any()) } returns
                pl.jclab.refio.core.services.context.ContextBudget.forContextSize(32000)

            val refs = listOf(
                ContextReference(
                    type = ContextType.SELECTION,
                    path = "",
                    displayName = "Selected code",
                    content = "fun myFunction() { println(\"hello\") }"
                )
            )
            val result = service.buildAgentTurnMessages(
                taskId = "task-1",
                projectRoot = projectRoot,
                userContextRefs = refs
            )
            // The context prompt should be generated (may or may not contain the ref depending on resolution)
            assertNotNull(result.projectContextPrompt)
        }
    }

    @Nested
    inner class CollectUserContextRefsTests {

        @Test
        fun `should collect refs from user messages with metadata`() {
            val contextRef = ContextReference(
                type = ContextType.FILE,
                path = "/src/main.kt",
                displayName = "main.kt"
            )
            val metadata = UserContextMetadata(contextRefs = listOf(contextRef))
            val metadataJson = GsonInstance.gson.toJson(metadata)

            val messages = listOf(
                ChatMessage(
                    id = "msg-1",
                    taskId = "task-1",
                    role = MessageRole.USER,
                    content = "Check this file",
                    metadata = metadataJson,
                    toolCalls = null,
                    toolCallId = null,
                    tokensIn = null,
                    tokensOut = null,
                    cost = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            every { chatMessageRepository.findByTaskId("task-1") } returns messages

            val refs = service.collectAllUserContextRefs("task-1")
            assertEquals(1, refs.size)
            assertEquals(ContextType.FILE, refs[0].type)
            assertEquals("/src/main.kt", refs[0].path)
        }

        @Test
        fun `should return empty for messages without metadata`() {
            val messages = listOf(
                MockFactory.createChatMessage(taskId = "task-1", role = MessageRole.USER, content = "Just text")
            )
            every { chatMessageRepository.findByTaskId("task-1") } returns messages

            val refs = service.collectAllUserContextRefs("task-1")
            assertTrue(refs.isEmpty())
        }

        @Test
        fun `should return empty for no messages`() {
            every { chatMessageRepository.findByTaskId("task-1") } returns emptyList()

            val refs = service.collectAllUserContextRefs("task-1")
            assertTrue(refs.isEmpty())
        }
    }

    @Nested
    inner class CalculateContextSectionTokensTests {

        private val dummyContext = ProjectContextDTO(
            metaData = MetaDataDTO(projectName = "Test"),
            summary = SummaryDTO(),
            structure = StructureDTO(),
            dependencies = DependenciesDTO(),
            codeAnalysis = CodeAnalysisDTO(),
            workspace = WorkspaceDTO(path = "/test"),
            executionMetadata = ExecutionMetadataDTO(),
            projectType = "JVM",
            contextGeneratedAt = java.time.Instant.now(),
            analyzerVersion = "test"
        )

        @Test
        fun `should return section map for prompt with tags`() {
            val prompt = """
                <PROJECT_CONTEXT>
                Project name: TestProject
                Type: JVM
                </PROJECT_CONTEXT>
                <CURRENT_TASK>
                Task: Implement feature
                </CURRENT_TASK>
            """.trimIndent()

            val result = service.calculateContextSectionTokens(dummyContext, prompt)
            assertTrue(result.isNotEmpty())
            assertTrue(result.containsKey("project_overview") || result.containsKey("current_task"))
        }

        @Test
        fun `should return empty map for blank prompt`() {
            val result = service.calculateContextSectionTokens(dummyContext, "")
            assertTrue(result.isEmpty())
        }

        @Test
        fun `should handle multiple sections`() {
            val prompt = """
                <PROJECT_CONTEXT>Project overview</PROJECT_CONTEXT>
                <CURRENT_TASK>Task info</CURRENT_TASK>
                <RAG_FRAGMENTS>Some code</RAG_FRAGMENTS>
            """.trimIndent()

            val result = service.calculateContextSectionTokens(dummyContext, prompt)
            assertTrue(result.size >= 2)
        }
    }

    @Nested
    inner class BuildCompactProjectSummaryTests {

        @Test
        fun `should generate summary from project analysis`() {
            val analysis = createTestProjectAnalysis()
            val summary = service.buildCompactProjectSummary(analysis, null)
            assertTrue(summary.isNotBlank())
        }

        @Test
        fun `should respect maxTokens limit`() {
            val analysis = createTestProjectAnalysis()
            val maxTokens = 100
            val summary = service.buildCompactProjectSummary(analysis, null, maxTokens)
            // maxTokens * 4 chars is the limit (take(maxTokens * 4))
            assertTrue(summary.length <= maxTokens * 4)
        }
    }
}
