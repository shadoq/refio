package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.AgentTurnMessagesResult
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PromptsService
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the per-iteration work state lands in the decision prompt. With the trailing layout the
 * system prompt and the earlier messages must not change between iterations (so a server-side
 * prefix cache re-reads only the tail), while the model still sees the work state and the original
 * request, now at the very end of the request.
 */
class TurnPromptLayoutTest {

    private val taskId = "task-layout"
    private val originalRequest = "Implement the twelve stub functions in src/ so that npm test passes."

    private lateinit var configService: ConfigService
    private lateinit var contextService: ContextService
    private lateinit var chatMessageRepository: ChatMessageRepository
    private val trailingFlag = slot<Boolean>()
    private var workState = "<RECENT_WORK>\nread src/a.js -> function a() {}\n</RECENT_WORK>"
    private val conversation = listOf(
        LLMMessage(role = "user", content = originalRequest),
        LLMMessage(role = "assistant", content = "Reading the stubs first.")
    )

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        contextService = mockk(relaxed = true)
        chatMessageRepository = mockk(relaxed = true)
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { chatMessageRepository.findHistoryForInvocation(taskId, null) } returns listOf(userMessage(originalRequest))
        every { contextService.collectAllUserContextRefs(any()) } returns emptyList()
        coEvery {
            contextService.buildAgentTurnMessages(
                taskId = any(), projectRoot = any(), userContextRefs = any(), query = any(),
                staticPrefixTokens = any(), includeProjectContext = any(), modelId = any(),
                agentInstanceId = any(), allowSummarization = any(), trailingState = capture(trailingFlag),
                history = any()
            )
        } answers {
            val trailing = trailingFlag.captured
            AgentTurnMessagesResult(
                messages = conversation,
                projectContextPrompt = if (trailing) "<STABLE_CONTEXT>project</STABLE_CONTEXT>" else "<STABLE_CONTEXT>project</STABLE_CONTEXT>\n\n$workState",
                historySize = conversation.size,
                trailingState = if (trailing) workState else ""
            )
        }
    }

    private fun useLayout(layout: String) {
        every { configService.getTyped(ConfigKeys.AGENT_PROMPT_LAYOUT, any()) } returns layout
    }

    private fun builder(clock: () -> Long = System::currentTimeMillis) = TurnPromptBuilder(
        promptsService = mockk<PromptsService>(relaxed = true).also {
            every { it.getSystemPrompt(any(), any()) } returns "You are the agent."
        },
        chatMessageRepository = chatMessageRepository,
        toolDescriptionBuilder = mockk<ToolDescriptionBuilder>(relaxed = true),
        contextService = contextService,
        workingMemoryService = null,
        projectRoot = Path.of("/tmp/project"),
        configService = configService,
        clock = clock
    )

    private suspend fun build(iteration: Int) = builder().buildPrompt(
        taskId = taskId,
        mode = TaskMode.AGENT,
        currentIteration = iteration,
        maxIterations = 200,
        userContextRefs = emptyList(),
        runProfile = TurnRunProfile.DEFAULT,
        profileOverrides = null
    )

    @Test
    fun `trailing layout puts the work state and the original request in a final harness message`() = runTest {
        useLayout(ConfigKeys.PROMPT_LAYOUT_TRAILING)

        val prompt = build(iteration = 3)

        assertTrue(trailingFlag.captured)
        assertFalse(prompt.systemPrompt.contains("<RECENT_WORK>"))
        assertEquals(conversation, prompt.messages.dropLast(1))
        val reminder = prompt.messages.last()
        assertEquals("user", reminder.role)
        assertTrue(reminder.content.startsWith("<system-reminder>"))
        assertTrue(reminder.content.contains("read src/a.js -> function a() {}"))
        assertTrue(reminder.content.contains("<task_requirements>"))
        assertTrue(reminder.content.contains(originalRequest))
    }

    @Test
    fun `trailing layout keeps the system prompt identical while the work state changes`() = runTest {
        useLayout(ConfigKeys.PROMPT_LAYOUT_TRAILING)

        val first = build(iteration = 3)
        workState = "<RECENT_WORK>\nedited src/a.js\n</RECENT_WORK>"
        val second = build(iteration = 4)

        assertEquals(first.systemPrompt, second.systemPrompt)
        assertEquals(first.messages.dropLast(1), second.messages.dropLast(1))
        assertTrue(second.messages.last().content.contains("edited src/a.js"))
    }

    @Test
    fun `system layout keeps the work state in the system prompt and adds no harness message`() = runTest {
        useLayout(ConfigKeys.PROMPT_LAYOUT_SYSTEM)

        val prompt = build(iteration = 3)

        assertFalse(trailingFlag.captured)
        assertTrue(prompt.systemPrompt.contains("<RECENT_WORK>"))
        assertTrue(prompt.systemPrompt.contains("<task_requirements>"))
        assertTrue(prompt.systemPrompt.contains("3 of 200"), "the turn status stays in the system prompt")
        assertEquals(conversation, prompt.messages)
    }

    @Test
    fun `system prompt stays identical across iterations in trailing layout`() = runTest {
        // Near the end of the iteration budget the turn status changes on every iteration; with the
        // trailing layout it travels in the final message, not in the system prompt the server caches.
        useLayout(ConfigKeys.PROMPT_LAYOUT_TRAILING)

        val first = build(iteration = 190)
        val second = build(iteration = 191)

        assertEquals(first.systemPrompt, second.systemPrompt)
        assertTrue(second.messages.last().content.contains("191 of 200"), second.messages.last().content)
    }

    @Test
    fun `turn status counts minutes from the start of the turn`() = runTest {
        useLayout(ConfigKeys.PROMPT_LAYOUT_TRAILING)

        val prompt = builder(clock = { 10 * 60_000L }).buildPrompt(
            taskId = taskId, mode = TaskMode.AGENT, currentIteration = 3, maxIterations = 200,
            userContextRefs = emptyList(), runProfile = TurnRunProfile.DEFAULT, profileOverrides = null,
            turnStartedAtMs = 0L, maxTurnMinutes = 45L
        )

        assertTrue(prompt.messages.last().content.contains("35 left"), prompt.messages.last().content)
    }

    private fun userMessage(content: String) = ChatMessage(
        id = "msg-1", taskId = taskId, role = MessageRole.USER,
        content = content, thinking = null, metadata = null,
        toolCalls = null, toolCallId = null, isSummarized = false,
        rawOutput = null, tokensIn = null, tokensOut = null,
        cost = null, createdAt = 0L
    )
}
