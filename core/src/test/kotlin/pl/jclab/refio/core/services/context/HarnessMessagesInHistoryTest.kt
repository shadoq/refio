package pl.jclab.refio.core.services.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.services.turn.HarnessMessageTypes

/**
 * Messages the harness writes to steer the agent (a nudge to keep working, the verification errors,
 * a note left at a limit) are stored as SYSTEM rows. Providers drop the system role from the message
 * list, so unless the history turns them into user messages the model never reads them, and every
 * nudge only looks like it was given.
 */
class HarnessMessagesInHistoryTest {

    private val builder = ConversationContextBuilder()
    private var seq = 0

    private fun row(role: MessageRole, content: String, metadata: String? = null) = ChatMessage(
        id = "m${++seq}", taskId = "t1", role = role, content = content, metadata = metadata,
        toolCalls = null, toolCallId = null, tokensIn = null, tokensOut = null, cost = null, createdAt = seq.toLong()
    )

    private fun visibleToModel(rows: List<ChatMessage>) = builder.filterMeaningfulConversation(rows)
        .mapNotNull { builder.convertChatMessageToLLMMessage(it, toolContentResolver = { m -> m.content }) }

    @Test
    fun `guardian nudge reaches the model as a user message`() {
        val nudge = "STOP - the turn is not finished: src/c.js still has a stub."
        val messages = visibleToModel(
            listOf(
                row(MessageRole.USER, "Implement the stubs"),
                row(MessageRole.ASSISTANT, "Done."),
                row(MessageRole.SYSTEM, nudge, HarnessMessageTypes.metadataFor(HarnessMessageTypes.GUARDIAN_NUDGE))
            )
        )

        val last = messages.last()
        assertEquals("user", last.role)
        assertEquals("[harness] $nudge", last.content)
    }

    @Test
    fun `verification errors reach the model`() {
        val feedback = "Verification failed (exit 1). Errors:\nsrc/c.test.js: expected 3\nFix them."
        val messages = visibleToModel(
            listOf(
                row(MessageRole.USER, "Implement the stubs"),
                row(MessageRole.SYSTEM, feedback, HarnessMessageTypes.metadataFor(HarnessMessageTypes.VERIFICATION_FEEDBACK))
            )
        )

        assertTrue(messages.any { it.role == "user" && it.content.orEmpty().contains("expected 3") })
    }

    @Test
    fun `untyped system rows stay out of the history`() {
        val messages = visibleToModel(
            listOf(row(MessageRole.USER, "Implement the stubs"), row(MessageRole.SYSTEM, "internal bookkeeping"))
        )

        assertEquals(listOf("Implement the stubs"), messages.map { it.content })
    }

    @Test
    fun `type is read from row metadata`() {
        assertEquals(
            HarnessMessageTypes.HANDOFF_NOTE,
            HarnessMessageTypes.typeOf("""{"type":"handoff_note","reason":"TIME_LIMIT"}""")
        )
        assertEquals(null, HarnessMessageTypes.typeOf("compaction"))
    }
}
