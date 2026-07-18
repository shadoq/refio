package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.testutil.MockFactory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task-completion verification must judge the thread it is actually completing. A subagent's
 * evidence must not be diluted with the parent conversation, and the main agent's evidence must not
 * be polluted with a subagent's internal steps.
 */
class TaskVerifierTest {

    private fun captureEvidenceFor(agentInstanceId: String?): String {
        val llmClient = mockk<LLMClient>()
        val configService = mockk<ConfigService>()
        val chatMessageRepository = mockk<ChatMessageRepository>()

        val parentMsg = MockFactory.createChatMessage(id = "p1", taskId = "task-1", role = MessageRole.USER, content = "PARENT_EVIDENCE")
        val subMsg = MockFactory.createChatMessage(id = "s1", taskId = "task-1", role = MessageRole.USER, content = "SUBAGENT_EVIDENCE")
        // The leaky path returned everything via findByTaskId; the verifier must read per-thread.
        every { chatMessageRepository.findByTaskId("task-1") } returns listOf(parentMsg, subMsg)
        every { chatMessageRepository.findHistoryForInvocation("task-1", null) } returns listOf(parentMsg)
        every { chatMessageRepository.findHistoryForInvocation("task-1", "sub-1") } returns listOf(subMsg)
        every { configService.getModel(ModelOperation.WEAK, "task-1") } returns ("weak-model" to "prov")

        val messagesSlot = slot<List<LLMMessage>>()
        coEvery {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = capture(messagesSlot),
                systemPrompt = any(),
                taskId = any(),
                source = any(),
                stream = any(),
                onChunk = any()
            )
        } returns LLMResponse(
            content = """{"is_complete": true, "reason": "ok"}""",
            usage = LLMUsage(inputTokens = 1, outputTokens = 1, totalTokens = 2),
            model = "weak-model",
            provider = "prov",
            cost = 0.0
        )

        val verifier = LlmTaskVerifier(llmClient, configService, chatMessageRepository)
        runTest {
            verifier.verifyCompletion("task-1", "user request", "assistant response", agentInstanceId = agentInstanceId)
        }
        return messagesSlot.captured.joinToString("\n") { it.content }
    }

    @Test
    fun `subagent verification sees only its own execution evidence`() {
        val evidence = captureEvidenceFor(agentInstanceId = "sub-1")

        assertTrue(evidence.contains("SUBAGENT_EVIDENCE"), "verifier must see the subagent's own evidence")
        assertFalse(evidence.contains("PARENT_EVIDENCE"), "verifier must not mix the parent thread into subagent evidence")
    }

    @Test
    fun `main agent verification is not polluted by a subagent's internal steps`() {
        val evidence = captureEvidenceFor(agentInstanceId = null)

        assertTrue(evidence.contains("PARENT_EVIDENCE"), "verifier must see the main thread's evidence")
        assertFalse(evidence.contains("SUBAGENT_EVIDENCE"), "the main agent's evidence must exclude a subagent's internal steps")
    }
}
