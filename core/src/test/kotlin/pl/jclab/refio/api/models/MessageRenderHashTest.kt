package pl.jclab.refio.api.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MessageRenderHashTest {

    private fun message(
        id: String = "m1",
        role: String = "assistant",
        content: String = "Here is the report.",
        isStreaming: Boolean = false,
        agentName: String? = null,
        agentDepth: Int? = null,
        agentInstanceId: String? = null,
    ) = Message(
        id = id,
        taskId = "t1",
        role = role,
        content = content,
        createdAt = 0L,
        isStreaming = isStreaming,
        agentName = agentName,
        agentDepth = agentDepth,
        agentInstanceId = agentInstanceId,
    )

    // The bug this guards: a stream ends with byte-identical content and only isStreaming flips
    // false. If the render hash ignores isStreaming the cache keeps the "Generating..." bubble
    // alive forever - the user watches a finished answer that still says it is generating.
    @Test
    fun `content hash changes when streaming flag flips even if text is identical`() {
        val streaming = message(content = "partial answer", isStreaming = true)
        val finished = message(content = "partial answer", isStreaming = false)

        assertNotEquals(
            MessageRenderHash.content(streaming),
            MessageRenderHash.content(finished),
            "isStreaming true->false must invalidate the render cache",
        )
    }

    // A subagent bubble must stay tied to the agent it belongs to: if the same id were ever
    // reused for a different agent the cached panel (with its per-agent header) has to rebuild.
    @Test
    fun `content hash changes when the owning agent changes`() {
        val fromReviewer = message(agentName = "reviewer", agentDepth = 1, agentInstanceId = "inst-a")
        val fromPlanner = message(agentName = "planner", agentDepth = 1, agentInstanceId = "inst-b")

        assertNotEquals(
            MessageRenderHash.content(fromReviewer),
            MessageRenderHash.content(fromPlanner),
        )
    }

    // The streaming fast-path patches only the char counter when nothing but the growing content
    // changed. That is only safe if the non-content hash is blind to content itself.
    @Test
    fun `non-content hash ignores content but still tracks the streaming flag`() {
        val short = message(content = "a", isStreaming = true)
        val longer = message(content = "a much longer partial answer", isStreaming = true)
        assertEquals(
            MessageRenderHash.nonContent(short),
            MessageRenderHash.nonContent(longer),
            "growing content alone must not change the non-content hash",
        )

        val finished = message(content = "a", isStreaming = false)
        assertNotEquals(
            MessageRenderHash.nonContent(short),
            MessageRenderHash.nonContent(finished),
            "a stream ending must change the non-content hash so the bubble rebuilds",
        )
    }
}
