package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnProfileOverrides
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
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.subagents.models.SubagentContextProfile
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A subagent's request starts with the same system prompt on every iteration, as the main agent's
 * does with the trailing layout: its work state (working memory, recent steps) goes into the final
 * message instead. A local server then reuses everything before that message instead of reading
 * the whole prompt again after every tool call. The subagent's context profile still applies to
 * that final message: what the profile leaves out stays out, and its token limit counts it too.
 */
class SubagentStablePrefixTest {

    private val taskId = "task-sub"
    private val instanceId = "sub-1"
    private val goal = "Find every caller of TurnVerifier and list the files."

    private lateinit var configService: ConfigService
    private lateinit var contextService: ContextService
    private lateinit var chatMessageRepository: ChatMessageRepository
    private val trailingFlag = slot<Boolean>()
    private var workState =
        "<WORKING_MEMORY>\nnote: TurnVerifier lives in services/turn\n</WORKING_MEMORY>\n\n" +
            "<RECENT_WORK>\ngrep TurnVerifier -> 3 files\n</RECENT_WORK>"
    private var conversation = listOf(
        LLMMessage(role = "user", content = goal),
        LLMMessage(role = "assistant", content = "Searching for callers.")
    )

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        contextService = mockk(relaxed = true)
        chatMessageRepository = mockk(relaxed = true)
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { configService.getTyped(ConfigKeys.AGENT_PROMPT_LAYOUT, any()) } returns ConfigKeys.PROMPT_LAYOUT_TRAILING
        every { chatMessageRepository.findHistoryForInvocation(taskId, instanceId) } returns listOf(userMessage(goal))
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
                projectContextPrompt = "<PROJECT_CONTEXT>project</PROJECT_CONTEXT>" + if (trailing) "" else "\n\n$workState",
                historySize = conversation.size,
                trailingState = if (trailing) workState else ""
            )
        }
    }

    private suspend fun build(iteration: Int, profile: SubagentContextProfile? = null) = TurnPromptBuilder(
        promptsService = mockk<PromptsService>(relaxed = true).also {
            every { it.getSystemPrompt(any(), any()) } returns "You are the explorer subagent."
        },
        chatMessageRepository = chatMessageRepository,
        toolDescriptionBuilder = mockk<ToolDescriptionBuilder>(relaxed = true),
        contextService = contextService,
        workingMemoryService = null,
        projectRoot = Path.of("/tmp/project"),
        configService = configService
    ).buildPrompt(
        taskId = taskId,
        mode = TaskMode.AGENT,
        currentIteration = iteration,
        maxIterations = 50,
        userContextRefs = emptyList(),
        runProfile = TurnRunProfile.SUBAGENT,
        profileOverrides = TurnProfileOverrides(
            subagentName = "explorer", depth = 1, agentInstanceId = instanceId, contextProfile = profile
        )
    )

    @Test
    fun `two subagent requests share the whole system prompt`() = runTest {
        val first = build(iteration = 2)
        workState = "<RECENT_WORK>\nread TurnExecutor.kt -> TurnVerifier(\n</RECENT_WORK>"
        val second = build(iteration = 3)

        assertEquals(first.systemPrompt, second.systemPrompt)
        assertTrue(second.messages.last().content.contains("read TurnExecutor.kt"), "the new step must still reach the subagent")
    }

    @Test
    fun `subagent context profile still trims trailing sections`() = runTest {
        val prompt = build(iteration = 2, profile = SubagentContextProfile(includeWorkingMemory = false))
        val request = prompt.systemPrompt + "\n" + prompt.messages.joinToString("\n") { it.content }

        assertFalse(request.contains("TurnVerifier lives in services/turn"), "the profile leaves the working memory out")
        assertTrue(prompt.messages.last().content.contains("grep TurnVerifier -> 3 files"), "recent steps still reach the subagent")
    }

    @Test
    fun `subagent token limit counts the work state at the end of the request`() = runTest {
        conversation = (1..40).map { LLMMessage(role = if (it % 2 == 1) "user" else "assistant", content = "step $it " + "x".repeat(400)) }
        workState = "<RECENT_WORK>\n" + "grep TurnVerifier -> TurnExecutor.kt:12\n".repeat(40) + "</RECENT_WORK>"
        val limit = 1_500

        val prompt = build(iteration = 2, profile = SubagentContextProfile(maxContextTokens = limit))

        val estimator = PromptTokenEstimator()
        val used = estimator.estimateString(prompt.systemPrompt) + prompt.messages.sumOf { estimator.estimateString(it.content) }
        assertTrue(used <= limit, "the request takes $used tokens, the profile allows $limit")
        assertTrue(prompt.messages.last().content.contains("TurnExecutor.kt:12"), "the work state is kept at the end")
    }

    private fun userMessage(content: String) = ChatMessage(
        id = "msg-1", taskId = taskId, role = MessageRole.USER,
        content = content, thinking = null, metadata = null,
        toolCalls = null, toolCallId = null, isSummarized = false,
        rawOutput = null, tokensIn = null, tokensOut = null,
        cost = null, createdAt = 0L
    )
}
