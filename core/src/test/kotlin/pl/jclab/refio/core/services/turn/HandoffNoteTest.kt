package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.context.ConversationContextBuilder
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A turn that stops on a limit or a guardrail leaves work half done, and the next turn (or the next
 * round of a long task) starts with only the history. The note asks the model, while everything is
 * still in front of it, what is done and what is left, and stores the answer where the next turn
 * reads it. The note repeats the turn's last request with one question added, so a server that
 * caches prompt prefixes only reads the question.
 */
class HandoffNoteTest {

    private companion object {
        const val TASK = "task-handoff"
        const val NOTE = "Done: parser skeleton in src/parser.kt.\nLeft: tokenizer tests.\nNext step: write the tokenizer tests."
    }

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val messages = ChatMessageRepository()
    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>(relaxed = true)

    private val sentMessages = slot<List<LLMMessage>>()
    private val sentSystemPrompt = slot<String>()
    private val sentMaxTokens = slot<Int>()
    private val sentKwargs = slot<Map<String, Any>>()
    private val sentProvider = slot<String>()
    private val sentModel = slot<String>()

    private val lastRequest = LastLlmRequest(
        systemPrompt = "You are the coding agent.",
        messages = listOf(LLMMessage(role = "user", content = "Build the parser"), LLMMessage(role = "assistant", content = "Writing src/parser.kt")),
        kwargs = mapOf("cacheable_system_length" to 25),
        model = "qwen3.6:35b",
        provider = "ollama",
    )

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction { TaskRepository().create(name = "Handoff", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = TASK) }
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        coEvery {
            llmClient.complete(
                provider = capture(sentProvider), model = capture(sentModel), messages = capture(sentMessages),
                systemPrompt = capture(sentSystemPrompt), maxTokens = capture(sentMaxTokens), temperature = any(),
                responseFormat = any(), thinking = any(), reasoningEffort = any(), noEgressEnabled = any(), stream = any(),
                onChunk = any(), taskId = any(), subtaskId = any(), source = any(), contextContent = any(),
                systemMessages = any(), kwargs = capture(sentKwargs)
            )
        } returns LLMResponse(
            content = NOTE, usage = LLMUsage(inputTokens = 40, outputTokens = 60, totalTokens = 100),
            model = "qwen3.6:35b", provider = "ollama", cost = 0.0
        )
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun writer() = HandoffNoteWriter(llmClient, messages, configService)

    @Test
    fun `time limit leaves a handoff note`() = runTest {
        val note = writer().write(TASK, TurnStopReason.TIME_LIMIT, lastRequest, agent = null)

        assertEquals(lastRequest.systemPrompt, sentSystemPrompt.captured, "the note starts like the turn's last request")
        assertEquals(lastRequest.messages, sentMessages.captured.dropLast(1))
        assertEquals("user", sentMessages.captured.last().role)
        assertEquals(lastRequest.kwargs, sentKwargs.captured)
        assertEquals("ollama/qwen3.6:35b", "${sentProvider.captured}/${sentModel.captured}")
        assertEquals(800, sentMaxTokens.captured)
        val row = messages.findByTaskId(TASK).single()
        assertEquals(MessageRole.SYSTEM, row.role)
        assertEquals(HarnessMessageTypes.HANDOFF_NOTE, HarnessMessageTypes.typeOf(row.metadata))
        assertTrue(row.metadata!!.contains("TIME_LIMIT"), "the note says why the turn stopped: ${row.metadata}")
        assertTrue(row.content.contains("Next step: write the tokenizer tests"))
        assertEquals(row.content, note)
    }

    @Test
    fun `next turn sees the handoff note`() = runTest {
        messages.create(TASK, MessageRole.USER, "Build the parser")
        writer().write(TASK, TurnStopReason.MAX_ITERATIONS, lastRequest, agent = null)

        val builder = ConversationContextBuilder()
        val history = builder.filterMeaningfulConversation(messages.findByTaskId(TASK))
            .mapNotNull { builder.convertChatMessageToLLMMessage(it, toolContentResolver = { m -> m.content }) }

        assertEquals("user", history.last().role)
        assertTrue(history.last().content.startsWith("${HarnessMessageTypes.PREFIX} "), history.last().content)
        assertTrue(history.last().content.contains("Next step: write the tokenizer tests"))
    }

    @Test
    fun `cost ceiling ends without a note`() = runTest {
        val note = writer().write(TASK, TurnStopReason.COST_LIMIT, lastRequest, agent = null)

        assertNull(note, "one more paid call would go over the ceiling the user set")
        coVerify(exactly = 0) {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(), maxTokens = any(), temperature = any(),
                responseFormat = any(), thinking = any(), reasoningEffort = any(), noEgressEnabled = any(), stream = any(),
                onChunk = any(), taskId = any(), subtaskId = any(), source = any(), contextContent = any(),
                systemMessages = any(), kwargs = any()
            )
        }
    }

    @Test
    fun `subagent note stays in the subagent's thread`() = runTest {
        writer().write(TASK, TurnStopReason.MAX_ITERATIONS, lastRequest, AgentRef(instanceId = "sub-1", name = "explorer", depth = 1))

        val row = messages.findByTaskId(TASK).single()
        assertEquals(Triple("sub-1", "explorer", 1), Triple(row.agentInstanceId, row.agentName, row.agentDepth))
    }
}
