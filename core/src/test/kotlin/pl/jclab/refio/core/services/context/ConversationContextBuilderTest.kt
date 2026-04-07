package pl.jclab.refio.core.services.context

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.llm.LLMContentPart
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationContextBuilderTest {

    private val builder = ConversationContextBuilder()

    @Test
    fun `should convert image tool result into multimodal llm message`() {
        val message = ChatMessage(
            id = "msg-1",
            taskId = "task-1",
            role = MessageRole.TOOL,
            content = "[Image: screenshot.png]",
            metadata = """{"type":"image","path":"screenshot.png","media_type":"image/png","base64":"Zm9v"}""",
            toolCalls = null,
            toolCallId = "tool-1",
            tokensIn = null,
            tokensOut = null,
            cost = null,
            createdAt = 1L
        )

        val llmMessage = builder.convertChatMessageToLLMMessage(
            msg = message,
            toolContentResolver = { "[Image: screenshot.png]" }
        )

        assertEquals("user", llmMessage?.role)
        assertTrue(llmMessage?.content?.contains("Attached image from screenshot.png.") == true)
        assertTrue(llmMessage?.parts?.any { it is LLMContentPart.Image } == true)
    }
}
