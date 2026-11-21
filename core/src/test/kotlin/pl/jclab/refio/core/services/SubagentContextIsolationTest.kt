package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.context.ContextBudget
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One task, three runs: the parent, the subagent under test and its sibling. Each run's prompt is
 * built from its own work only. The subagent does not get the parent's request or a sibling's
 * steps, and the parent gets the subagent's report instead of the subagent's internal steps.
 */
class SubagentContextIsolationTest {

    private companion object {
        const val TASK = "task-iso"
        const val SUBAGENT = "sub-1"
        const val SIBLING = "sub-2"
        const val PARENT_REQUEST = "Refactor the payment module and keep the public API"
    }

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var projectRoot: Path
    private val tasks = TaskRepository()
    private val messages = ChatMessageRepository()
    private val subtasks = SubtaskRepository()
    private lateinit var service: ContextService

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        projectRoot = Files.createTempDirectory("refio-iso")
        mockkObject(MCPManager)
        every { MCPManager.getConnectedServers(any()) } returns emptyList()

        val analyzer = mockk<ProjectAnalyzerService>()
        coEvery { analyzer.analyzeProject(any(), any()) } returns analysis()
        val config = mockk<ConfigService>()
        every { config.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { config.getContextBudget(any(), any(), any()) } returns ContextBudget.forContextSize(200_000)

        service = ContextService(
            projectAnalyzer = analyzer,
            taskRepository = tasks,
            chatMessageRepository = messages,
            subtaskRepository = subtasks,
            configService = config,
            workingMemoryService = WorkingMemoryService()
        )
        seedThreeRuns()
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(MCPManager)
        db.keepAlive.close()
        projectRoot.toFile().deleteRecursively()
    }

    private fun step(kind: SubtaskKind, params: String, result: String, owner: String?): String {
        val created = subtasks.createNext(
            taskId = TASK, kind = kind, description = "${kind.name.lowercase()} $params",
            paramsJson = params, status = TaskStatus.PENDING, agentInstanceId = owner
        )
        subtasks.updateResult(created.id, result = result, summary = result)
        subtasks.updateStatus(created.id, TaskStatus.SUCCESS)
        return created.id
    }

    private fun seedThreeRuns() {
        transaction {
            tasks.create(name = PARENT_REQUEST, mode = TaskMode.AGENT, projectId = "p", projectPath = projectRoot.toString(), id = TASK)
        }
        messages.create(taskId = TASK, role = MessageRole.USER, content = PARENT_REQUEST)
        step(SubtaskKind.READ_FILE, """{"path":"src/PaymentService.kt"}""", "class PaymentService { fun pay() }", owner = null)

        messages.create(TASK, MessageRole.USER, "Review the logging setup", agentInstanceId = SIBLING, agentName = "reviewer", agentDepth = 1)
        step(SubtaskKind.READ_FILE, """{"path":"src/SiblingLogger.kt"}""", "class SiblingLogger wraps slf4j", owner = SIBLING)

        messages.create(TASK, MessageRole.USER, "Find every caller of TurnVerifier", agentInstanceId = SUBAGENT, agentName = "explorer", agentDepth = 1)
        step(SubtaskKind.GREP_SEARCH, """{"pattern":"TurnVerifier"}""", "TurnExecutor.kt:12: TurnVerifier(", owner = SUBAGENT)
    }

    private suspend fun promptFor(agentInstanceId: String?): String {
        val built = service.buildAgentTurnMessages(
            taskId = TASK, projectRoot = projectRoot, agentInstanceId = agentInstanceId, allowSummarization = false
        )
        return built.projectContextPrompt + "\n" + built.messages.joinToString("\n") { it.content.orEmpty() }
    }

    /** Stores a subagent report in the parent thread exactly as the tool executor decides to store it. */
    private fun parentReceivesReport(report: String): String {
        val invokeStep = step(SubtaskKind.INVOKE_SUBAGENT, """{"subagent_name":"explorer"}""", report, owner = null)
        messages.create(
            TASK, MessageRole.ASSISTANT, "",
            toolCalls = listOf(ToolCallData(id = "call-sub", name = "invoke_subagent", arguments = """{"subagent_name":"explorer"}"""))
        )
        val (content, summarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = report, summaryText = "Subagent [explorer] result:\n\n$report",
            wasSummarized = false, isDataProducing = false, toolName = "invoke_subagent"
        )
        messages.createToolResult(
            taskId = TASK, toolCallId = "call-sub", subtaskId = invokeStep, result = content,
            isSummarized = summarized, rawOutput = report, metadata = """{"tool_name":"invoke_subagent"}"""
        )
        return invokeStep
    }

    @Test
    fun `subagent prompt has neither the parent request nor sibling steps`() = runTest {
        val prompt = promptFor(SUBAGENT)

        assertTrue(prompt.contains("TurnExecutor.kt:12"), "the subagent lost its own step")
        assertFalse(prompt.contains("payment module"), "the parent's request reached the subagent")
        assertFalse(prompt.contains("PaymentService"), "a parent step reached the subagent")
        assertFalse(prompt.contains("SiblingLogger"), "a sibling's step reached the subagent")
    }

    @Test
    fun `parent recent work has no subagent internal steps`() = runTest {
        val prompt = promptFor(agentInstanceId = null)

        assertTrue(prompt.contains("PaymentService"), "the parent lost its own step")
        assertFalse(prompt.contains("TurnExecutor.kt:12"), "a subagent's internal step reached the parent")
        assertFalse(prompt.contains("SiblingLogger"), "a subagent's internal step reached the parent")
    }

    @Test
    fun `subagent report up to 16 KB reaches the parent in full`() = runTest {
        val report = "Callers of TurnVerifier:\n" + "TurnExecutor.kt:12 builds it once per turn.\n".repeat(330) + "END-OF-REPORT"
        assertTrue(report.length in 12_000..TurnToolExecutor.SUBAGENT_REPORT_INLINE_MAX)
        parentReceivesReport(report)

        val built = service.buildAgentTurnMessages(taskId = TASK, projectRoot = projectRoot, allowSummarization = false)
        val reportMessage = built.messages.last { it.role == "tool" }.content.orEmpty()

        assertTrue(reportMessage.contains(report), "the parent got a cut report: ${reportMessage.length} chars")
        assertFalse(reportMessage.contains("get_subtask_output"), "a report that fits must not tell the parent to fetch it again")
    }

    @Test
    fun `longer report ends with a get_subtask_output hint`() = runTest {
        val report = "Callers of TurnVerifier:\n" + "TurnExecutor.kt:12 builds it once per turn.\n".repeat(1_000) + "END-OF-REPORT"
        val invokeStep = parentReceivesReport(report)

        val built = service.buildAgentTurnMessages(taskId = TASK, projectRoot = projectRoot, allowSummarization = false)
        val reportMessage = built.messages.last { it.role == "tool" }.content.orEmpty()

        assertTrue(reportMessage.contains("END-OF-REPORT"), "the end of a long report is where the conclusion usually is")
        assertTrue(
            reportMessage.trimEnd().endsWith("]") && reportMessage.contains("get_subtask_output") && reportMessage.contains(invokeStep),
            "the parent is not told how to read the rest: ${reportMessage.takeLast(300)}"
        )
    }

    private fun analysis() = ProjectAnalysis(
        projectPath = projectRoot.toString(),
        structure = StructureInfo(totalFiles = 3, maxDepth = 2, fileTypes = mapOf("kt" to 3), topLevelItems = listOf("src"), directoryCount = 1),
        technologies = listOf("Kotlin"),
        dependencies = DependenciesInfo(python = emptyList(), javascript = emptyList(), packageManagers = listOf("Gradle"), configFiles = emptyList()),
        codeAnalysis = CodeAnalysisInfo(
            kotlin = emptyMap(), java = emptyMap(), python = emptyMap(), javascript = emptyMap(),
            typescript = emptyMap(), html = emptyMap(), css = emptyMap()
        ),
        keyComponents = emptyList(),
        projectType = "JVM",
        primaryLanguage = "Kotlin",
        summary = SummaryInfo(projectType = "JVM", complexity = "Low", mainLanguage = "Kotlin", fileCount = 3, architectureNotes = ""),
        domainAnalysis = DomainAnalysis(primaryDomain = "Backend", confidenceScore = 0.9, domainScores = mapOf("Backend" to 0.9)),
        analyzedAt = System.currentTimeMillis()
    )
}
