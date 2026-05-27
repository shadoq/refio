package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.testutil.MockFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationSummaryServiceTest {

    private lateinit var llmClient: LLMClient
    private lateinit var promptsService: PromptsService
    private lateinit var configService: ConfigService
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var service: ConversationSummaryService

    @BeforeEach
    fun setup() {
        llmClient = mockk()
        promptsService = mockk()
        configService = mockk()
        chatMessageRepository = mockk()

        service = ConversationSummaryService(
            llmClient = llmClient,
            promptsService = promptsService,
            configService = configService,
            chatMessageRepository = chatMessageRepository
        )
    }

    @Test
    fun `should not summarize when token budget is not exhausted`() = runTest {
        // Each message ~50 tokens, 5 messages = ~250 tokens, budget = 1000 → 25% used.
        val messages = (1..5).map { index ->
            MockFactory.createChatMessage(
                id = "msg-$index",
                taskId = "task-1",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "Message $index " + "x".repeat(200)
            )
        }

        val result = service.ensureSummaryIfNeeded(
            taskId = "task-1",
            messages = messages,
            maxTokens = 1000
        )

        assertEquals(messages, result)
    }

    @Test
    fun `should require higher token threshold before summarizing`() {
        val messages = listOf(
            MockFactory.createChatMessage(content = "x".repeat(400)),
            MockFactory.createChatMessage(content = "x".repeat(400))
        )

        assertFalse(service.shouldSummarize(messages, maxTokens = 300))
    }

    @Test
    fun `contentResolver controls token estimation`() {
        // Raw msg.content would dwarf the budget (~10k tokens at 4 chars/token), but the
        // resolver reports the prompt-side rendering (TOOL bodies truncated to ~1024 chars).
        // Decision must follow the resolver — otherwise large unsummarized read_file dumps
        // trigger premature summarization even though only a tiny fraction reaches the LLM.
        val messages = listOf(
            MockFactory.createChatMessage(role = MessageRole.TOOL, content = "x".repeat(40_000))
        )
        val promptSideResolver: (pl.jclab.refio.core.db.ChatMessage) -> String = { "tiny" }

        // Without resolver — old behavior: counts the full 40k chars → above threshold.
        assertTrue(service.shouldSummarize(messages, maxTokens = 1000))
        // With resolver — new behavior: counts only what truly reaches the LLM → well below.
        assertFalse(service.shouldSummarize(messages, maxTokens = 1000, contentResolver = promptSideResolver))
    }

    @Test
    fun `should summarize when token budget is exhausted`() = runTest {
        val messages = (1..24).map { index ->
            MockFactory.createChatMessage(
                id = "msg-$index",
                taskId = "task-1",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "Message $index " + "x".repeat(200)
            )
        }

        every { promptsService.getSystemPrompt(any(), any()) } returns "summary-prompt"
        every { configService.getModel(ModelOperation.WEAK, "task-1") } returns ("gpt-4o-mini" to "openai")
        coEvery {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                temperature = any(),
                maxTokens = any(),
                source = any(),
                taskId = any(),
                subtaskId = any()
            )
        } returns LLMResponse(
            content = "Condensed summary",
            usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
            model = "gpt-4o-mini",
            provider = "openai",
            cost = 0.0
        )
        every {
            chatMessageRepository.create(
                taskId = any(),
                role = any(),
                content = any(),
                metadata = any(),
                tokensIn = any(),
                tokensOut = any(),
                cost = any(),
                toolCalls = any(),
                toolCallId = any()
            )
        } returns MockFactory.createChatMessage(role = MessageRole.SYSTEM, content = "summary")
        every { chatMessageRepository.findByTaskId("task-1") } returns messages

        val result = service.ensureSummaryIfNeeded(
            taskId = "task-1",
            messages = messages,
            maxTokens = 100
        )

        assertEquals(messages, result)
    }
}
