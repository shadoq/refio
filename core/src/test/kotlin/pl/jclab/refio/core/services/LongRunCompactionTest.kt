package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.context.ConversationContextBuilder
import pl.jclab.refio.testutil.TestDatabase

/**
 * A long agent conversation compacted several times through the real summary service and real
 * SQLite ordering, then sliced and filtered the way the turn prompt is built. Every stored row must
 * stay visible to the model or be covered by the summary it can see: a row that is neither is work
 * the model has forgotten without knowing it.
 */
class LongRunCompactionTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val repo = ChatMessageRepository()
    private val taskRepo = TaskRepository()
    private val builder = ConversationContextBuilder()
    private val taskId = "task-long-run"
    private val summaryInputs = mutableListOf<String>()
    private val summaryMaxTokens = mutableListOf<Int?>()
    private lateinit var service: ConversationSummaryService

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction {
            taskRepo.create(name = "long run", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = taskId)
        }
        val llm = mockk<LLMClient>()
        val prompts = mockk<PromptsService>()
        val config = mockk<ConfigService>()
        every { prompts.getSystemPrompt(any(), any()) } answers { secondArg<Map<String, String>>()["conversation"].orEmpty() }
        every { config.getModel(ModelOperation.WEAK, any()) } returns ("weak" to "ollama")
        coEvery {
            llm.complete(
                provider = any(), model = any(), messages = any(), temperature = any(),
                maxTokens = any(), source = any(), taskId = any(), subtaskId = any()
            )
        } answers {
            summaryInputs += (arg<List<LLMMessage>>(2)).joinToString("\n") { it.content.orEmpty() }
            summaryMaxTokens += arg<Int?>(4)
            LLMResponse(
                content = "summary body ${summaryInputs.size}",
                usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                model = "weak", provider = "ollama", cost = 0.0
            )
        }
        service = ConversationSummaryService(llm, prompts, config, repo)
        repo.create(taskId = taskId, role = MessageRole.USER, content = "U0 implement twelve functions")
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun label(m: ChatMessage): String =
        if (builder.isConversationSummary(m)) "S" else m.content.substringBefore(' ')

    private fun addIterations(range: IntRange) = range.forEach { i ->
        repo.create(
            taskId = taskId, role = MessageRole.ASSISTANT, content = "A$i reading step $i",
            toolCalls = listOf(ToolCallData(id = "c$i", name = "read_file", arguments = "{\"path\":\"f$i.kt\"}"))
        )
        repo.create(
            taskId = taskId, role = MessageRole.TOOL, content = "T$i " + "result line of step $i. ".repeat(40),
            toolCallId = "c$i"
        )
    }

    private data class View(val stored: List<ChatMessage>, val visible: List<ChatMessage>, val lost: List<String>)

    private suspend fun compactAndView(): View {
        val all = repo.findHistoryForInvocation(taskId, null)
        val stored = service.ensureSummaryIfNeeded(taskId = taskId, messages = all, maxTokens = 4000)
        val visible = builder.filterMeaningfulConversation(builder.sliceConversationHistoryFromLastSummary(stored))
        val visibleIds = visible.map { it.id }.toSet()
        val ids = stored.map { it.id }
        val covered = mutableSetOf<String>()
        visible.filter { builder.isConversationSummary(it) }.forEach { s ->
            val first = Regex("first_message_id\":\"([^\"]+)").find(s.metadata!!)!!.groupValues[1]
            val last = Regex("last_message_id\":\"([^\"]+)").find(s.metadata!!)!!.groupValues[1]
            covered += ids.subList(ids.indexOf(first), ids.indexOf(last) + 1)
        }
        val lost = stored.filterNot { builder.isConversationSummary(it) }
            .filter { it.id !in visibleIds && it.id !in covered }
            .map { label(it) }
        return View(stored, visible, lost)
    }

    @Test
    fun `no message is lost across three compactions`() = runTest {
        for (round in 0 until 3) {
            addIterations(round * 20 + 1..round * 20 + 20)
            val view = compactAndView()
            assertEquals(round + 1, view.stored.count { builder.isConversationSummary(it) }, "compaction ${round + 1} did not run")
            assertTrue(view.lost.isEmpty(), "after compaction ${round + 1} the model has forgotten ${view.lost}")
        }
    }

    @Test
    fun `tool result never appears without its call`() = runTest {
        addIterations(1..20)
        compactAndView()
        addIterations(21..40)
        val view = compactAndView()
        val callIds = view.visible.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        val orphans = view.visible.filter { it.role == MessageRole.TOOL && it.toolCallId !in callIds }.map { label(it) }
        assertTrue(orphans.isEmpty(), "tool results visible without their call: $orphans")
    }

    @Test
    fun `next summary carries the previous one`() = runTest {
        addIterations(1..20)
        compactAndView()
        addIterations(21..40)
        compactAndView()
        assertEquals(2, summaryInputs.size)
        assertTrue(summaryInputs[1].contains("summary body 1"), "second summary was written without the first one")
    }

    @Test
    fun `summary has room for all its sections`() = runTest {
        addIterations(1..20)
        compactAndView()
        assertTrue((summaryMaxTokens.single() ?: 0) >= 1200, "summary capped at ${summaryMaxTokens.single()} tokens")
    }
}
