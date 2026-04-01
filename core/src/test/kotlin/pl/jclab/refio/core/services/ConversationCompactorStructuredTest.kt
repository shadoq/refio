package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.*

/**
 * Tests for structured compaction (Zmiana 1) and cascade handling (Zmiana 3).
 * Uses real in-memory DB because compact() calls Exposed transaction {}.
 */
class ConversationCompactorStructuredTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var llmClient: LLMClient
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var configService: ConfigService
    private lateinit var compactor: ConversationCompactor

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        llmClient = mockk(relaxed = true)
        chatMessageRepository = ChatMessageRepository()
        taskRepository = TaskRepository()
        configService = mockk(relaxed = true)

        every { configService.getModel(ModelOperation.WEAK, any()) } returns Pair("gpt-4o-mini", "openai")

        compactor = ConversationCompactor(
            llmClient, chatMessageRepository, taskRepository, configService, PromptTokenEstimator()
        )
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun makeLLMResponse(content: String) = LLMResponse(
        content = content,
        usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
        model = "gpt-4o-mini",
        provider = "openai",
        cost = 0.001
    )

    private fun createTaskAndMessages(
        taskId: String,
        messageContents: List<Pair<MessageRole, String>>
    ) {
        transaction {
            taskRepository.create(
                name = "Test Task", mode = TaskMode.AGENT,
                projectId = "proj-1", projectPath = "/test", id = taskId
            )
            messageContents.forEach { (role, content) ->
                chatMessageRepository.create(
                    taskId = taskId, role = role, content = content
                )
            }
        }
    }

    @Nested
    inner class StructuredCompaction {

        @Test
        fun `compact stores structured summary when LLM returns compacted_summary format`() = runTest {
            val msgs = (1..8).map { MessageRole.USER to "Message $it" }
            createTaskAndMessages("task-1", msgs)

            val structuredSummary = """
<compacted_summary>
<decisions>
- Use Kotlin coroutines for async
</decisions>
<files_modified>
- src/Main.kt — added entry point
</files_modified>
<findings>
- Build passes with no errors
</findings>
<current_state>
- Initial setup complete
</current_state>
<next_steps>
- Add unit tests
</next_steps>
</compacted_summary>
            """.trimIndent()

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse(structuredSummary)

            compactor.compact("task-1", 5000)

            val remaining = chatMessageRepository.findByTaskId("task-1")
            val systemMsg = remaining.find { it.role == MessageRole.SYSTEM }
            assertNotNull(systemMsg, "Should have a system summary message")
            assertTrue(systemMsg.content.contains("<compacted_summary>"), "Should contain structured summary")
            assertTrue(systemMsg.content.contains("<decisions>"), "Should contain decisions section")
            assertTrue(systemMsg.content.contains("<files_modified>"), "Should contain files section")
            assertTrue(systemMsg.content.contains("Previous 4 messages were compacted"))
        }

        @Test
        fun `compact handles non-structured LLM response gracefully`() = runTest {
            val msgs = (1..8).map { MessageRole.USER to "Message $it" }
            createTaskAndMessages("task-1", msgs)

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse("Just a plain text summary without structure")

            val result = compactor.compact("task-1", 5000)
            assertTrue(result)

            val remaining = chatMessageRepository.findByTaskId("task-1")
            val systemMsg = remaining.find { it.role == MessageRole.SYSTEM }
            assertNotNull(systemMsg)
            assertTrue(systemMsg.content.contains("plain text summary"))
        }
    }

    @Nested
    inner class CascadeMerging {

        @Test
        fun `compact merges previous compacted summary with new one`() = runTest {
            val previousCompactedContent = """
<compacted_summary>
<decisions>
- Chose PostgreSQL over MySQL
</decisions>
<files_modified>
- build.gradle — added postgres driver
</files_modified>
<findings>
- None
</findings>
<current_state>
- Database setup in progress
</current_state>
<next_steps>
- Create migration scripts
</next_steps>
</compacted_summary>

[Previous 6 messages were compacted to save context space]
            """.trimIndent()

            val msgs = listOf(
                MessageRole.SYSTEM to previousCompactedContent,
                MessageRole.USER to "Now add the migration",
                MessageRole.ASSISTANT to "I'll create the migration file",
                MessageRole.TOOL to "File created: V001_init.sql",
                MessageRole.USER to "Good, also add indexes",
                MessageRole.ASSISTANT to "Adding indexes to users table",
                MessageRole.TOOL to "File modified: V001_init.sql",
                MessageRole.USER to "Run the tests now"
            )
            createTaskAndMessages("task-1", msgs)

            val newSummary = """
<compacted_summary>
<decisions>
- Added indexes on email and created_at columns
</decisions>
<files_modified>
- V001_init.sql — added CREATE INDEX statements
</files_modified>
<findings>
- Migration syntax validated
</findings>
<current_state>
- Migration file ready with indexes
</current_state>
<next_steps>
- Run tests
</next_steps>
</compacted_summary>
            """.trimIndent()

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse(newSummary)

            compactor.compact("task-1", 5000)

            val remaining = chatMessageRepository.findByTaskId("task-1")
            val systemMsg = remaining.find { it.role == MessageRole.SYSTEM }
            assertNotNull(systemMsg)

            val stored = systemMsg.content
            // New decisions present
            assertTrue(stored.contains("indexes on email"), "Should contain new decision")
            // Previous decisions merged in
            assertTrue(stored.contains("PostgreSQL over MySQL"), "Should contain previous decision")
            // Files from both phases
            assertTrue(stored.contains("V001_init.sql"), "Should contain new file")
            assertTrue(stored.contains("build.gradle"), "Should contain previous file")
            // current_state and next_steps only from new summary
            assertTrue(stored.contains("Migration file ready"), "Should contain current state from new")
            assertFalse(stored.contains("Database setup in progress"), "Should NOT contain old current_state")
        }

        @Test
        fun `cascade handling separates previous summaries from conversation in LLM prompt`() = runTest {
            val previousCompactedContent = """
<compacted_summary>
<decisions>
- Use REST over GraphQL
</decisions>
<files_modified>
- None
</files_modified>
<findings>
- None
</findings>
<current_state>
- Planning phase
</current_state>
<next_steps>
- None
</next_steps>
</compacted_summary>

[Previous 4 messages were compacted to save context space]
            """.trimIndent()

            val msgs = listOf(
                MessageRole.SYSTEM to previousCompactedContent,
                MessageRole.USER to "Create the endpoint",
                MessageRole.ASSISTANT to "Creating GET /users",
                MessageRole.TOOL to "File created: UserController.kt",
                MessageRole.USER to "Continue",
                MessageRole.ASSISTANT to "Done",
                MessageRole.TOOL to "Success",
                MessageRole.USER to "Test it"
            )
            createTaskAndMessages("task-1", msgs)

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse("Some summary")

            compactor.compact("task-1", 5000)

            val messagesSlot = slot<List<pl.jclab.refio.core.llm.LLMMessage>>()
            coVerify {
                llmClient.complete(
                    provider = any(), model = any(), messages = capture(messagesSlot),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            }

            val llmInput = messagesSlot.captured.first().content
            assertTrue(
                llmInput.contains("PREVIOUS SESSION CONTEXT"),
                "Should include previous summary as separate context"
            )
        }

        @Test
        fun `compact without previous summary does not add PREVIOUS SESSION CONTEXT`() = runTest {
            val msgs = (1..8).map { MessageRole.USER to "Message $it" }
            createTaskAndMessages("task-1", msgs)

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse("Summary")

            compactor.compact("task-1", 5000)

            val messagesSlot = slot<List<pl.jclab.refio.core.llm.LLMMessage>>()
            coVerify {
                llmClient.complete(
                    provider = any(), model = any(), messages = capture(messagesSlot),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            }

            val llmInput = messagesSlot.captured.first().content
            assertFalse(
                llmInput.contains("PREVIOUS SESSION CONTEXT"),
                "Should not contain previous context when there's no prior compaction"
            )
        }
    }

    @Nested
    inner class MergeSectionsEdgeCases {

        @Test
        fun `merge deduplicates case-insensitively`() = runTest {
            val previousCompactedContent = """
<compacted_summary>
<decisions>
- Use Kotlin Coroutines
</decisions>
<files_modified>
- None
</files_modified>
<findings>
- None
</findings>
<current_state>
- Done
</current_state>
<next_steps>
- None
</next_steps>
</compacted_summary>

[Previous 4 messages were compacted to save context space]
            """.trimIndent()

            val msgs = listOf(
                MessageRole.SYSTEM to previousCompactedContent,
                MessageRole.USER to "msg2", MessageRole.USER to "msg3",
                MessageRole.USER to "msg4", MessageRole.USER to "msg5",
                MessageRole.USER to "msg6", MessageRole.USER to "msg7",
                MessageRole.USER to "msg8"
            )
            createTaskAndMessages("task-1", msgs)

            val newSummary = """
<compacted_summary>
<decisions>
- use kotlin coroutines
- Added error handling
</decisions>
<files_modified>
- None
</files_modified>
<findings>
- None
</findings>
<current_state>
- Error handling added
</current_state>
<next_steps>
- None
</next_steps>
</compacted_summary>
            """.trimIndent()

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse(newSummary)

            compactor.compact("task-1", 5000)

            val remaining = chatMessageRepository.findByTaskId("task-1")
            val systemMsg = remaining.find { it.role == MessageRole.SYSTEM }
            assertNotNull(systemMsg)

            val stored = systemMsg.content
            val coroutinesMentions = Regex("(?i)kotlin coroutines").findAll(stored).count()
            assertEquals(1, coroutinesMentions, "Should deduplicate case-insensitively")
            assertTrue(stored.contains("error handling", ignoreCase = true))
        }

        @Test
        fun `merge limits items to max 7 per section`() = runTest {
            val previousDecisions = (1..6).joinToString("\n") { "- Previous decision $it" }
            val previousCompactedContent = """
<compacted_summary>
<decisions>
$previousDecisions
</decisions>
<files_modified>
- None
</files_modified>
<findings>
- None
</findings>
<current_state>
- Done
</current_state>
<next_steps>
- None
</next_steps>
</compacted_summary>

[Previous 10 messages were compacted to save context space]
            """.trimIndent()

            val msgs = listOf(
                MessageRole.SYSTEM to previousCompactedContent,
                MessageRole.USER to "m2", MessageRole.USER to "m3",
                MessageRole.USER to "m4", MessageRole.USER to "m5",
                MessageRole.USER to "m6", MessageRole.USER to "m7",
                MessageRole.USER to "m8"
            )
            createTaskAndMessages("task-1", msgs)

            val newDecisions = (1..5).joinToString("\n") { "- New decision $it" }
            val newSummary = """
<compacted_summary>
<decisions>
$newDecisions
</decisions>
<files_modified>
- None
</files_modified>
<findings>
- None
</findings>
<current_state>
- Latest
</current_state>
<next_steps>
- None
</next_steps>
</compacted_summary>
            """.trimIndent()

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()
                )
            } returns makeLLMResponse(newSummary)

            compactor.compact("task-1", 5000)

            val remaining = chatMessageRepository.findByTaskId("task-1")
            val systemMsg = remaining.find { it.role == MessageRole.SYSTEM }
            assertNotNull(systemMsg)

            val stored = systemMsg.content
            val decisionLines = stored.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").trim() }
                .filter { it.startsWith("New decision") || it.startsWith("Previous decision") }
            assertTrue(decisionLines.size <= 7, "Should limit decisions to max 7, got ${decisionLines.size}")
            assertTrue(stored.contains("New decision 1"), "New decisions should be present")
        }
    }
}
