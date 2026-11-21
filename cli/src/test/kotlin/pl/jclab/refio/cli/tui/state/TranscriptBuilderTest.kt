package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.MessageResponse
import pl.jclab.refio.core.db.ToolCallData
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A turn must look the same while it runs and after it is read back from the database: the listener keeps
 * one message per tool call, the database keeps the call (inside the assistant message) and the result
 * (a separate row). Before, the reload showed every call twice: once as `[read_file]`, once as `[tool]`.
 */
class TranscriptBuilderTest {

    private val readCall = ToolCallData(id = "c1", name = "read_file", arguments = """{"path":"src/main/App.kt","start_line":1}""")
    private val grepCall = ToolCallData(id = "c2", name = "grep_search", arguments = """{"query":"TODO"}""")

    private fun stored(id: String, role: String, content: String, at: Long, toolCallsJson: String? = null, toolCallId: String? = null) =
        MessageResponse(
            id = id, taskId = "t1", role = role, content = content, metadata = null, toolCallsJson = toolCallsJson,
            toolCallId = toolCallId, tokensIn = null, tokensOut = null, cost = null, createdAt = at
        )

    private fun liveTurn(): List<TuiChatMessage> {
        val messages = MutableStateFlow(
            listOf(
                TuiChatMessage(id = "u1", timestamp = 1_000, role = "user", content = "Find the TODOs"),
                TuiChatMessage(id = "a1", timestamp = 2_000, role = "assistant", content = "Reading the app first."),
            )
        )
        var now = 3_000L
        val durations = mutableMapOf<String, Long>()
        val listener = TuiToolCallListener(CoroutineScope(Dispatchers.Unconfined), messages, {}, {}, durations) { now }
        listener.onToolExecutionStarted("t1", readCall)
        listener.onToolExecutionStarted("t1", grepCall)
        now = 3_400
        listener.onToolExecutionCompleted("t1", readCall, "lines 1-40 of 296\nclass App", true)
        now = 3_900
        listener.onToolExecutionCompleted("t1", grepCall, "3 matches", true)
        return messages.value
    }

    private fun reloadedTurn(durations: Map<String, Long>): List<TuiChatMessage> = StoredChatMessages.toChatMessages(
        listOf(
            stored("u1", "user", "Find the TODOs", 1_000),
            stored("a1", "assistant", "Reading the app first.", 2_000, toolCallsJson = ToolCallData.toJsonList(listOf(readCall, grepCall))),
            stored("r1", "tool", "lines 1-40 of 296\nclass App", 3_400, toolCallId = "c1"),
            stored("r2", "tool", "3 matches", 3_900, toolCallId = "c2"),
        ),
        durations
    )

    /** What the user sees of a transcript, without ids and times that legitimately differ. */
    private fun shape(items: List<TranscriptItem>): List<String> = items.map { item ->
        when (item) {
            is TranscriptItem.Message -> "${item.message.role}: ${item.message.content}"
            is TranscriptItem.Tools -> item.rows.joinToString(" | ") { "${it.key} ${it.toolName} ${it.argument} ${it.status} ${it.shortResult} ${it.durationMs}" }
        }
    }

    @Test
    fun `live turn and reloaded turn give the same groups`() {
        val live = TranscriptBuilder.build(liveTurn())
        val reloaded = TranscriptBuilder.build(reloadedTurn(mapOf("c1" to 400L, "c2" to 900L)))
        assertEquals(shape(live), shape(reloaded))
        val tools = reloaded.filterIsInstance<TranscriptItem.Tools>().single()
        assertEquals(2, tools.rows.size, "each call shown once")
        assertEquals("src/main/App.kt", tools.rows[0].argument)
    }

    @Test
    fun `call and result are paired by tool call id even when all calls come before the results`() {
        val rows = TranscriptBuilder.build(reloadedTurn(emptyMap())).filterIsInstance<TranscriptItem.Tools>().single().rows
        assertEquals(listOf("read_file" to "lines 1-40 of 296", "grep_search" to "3 matches"), rows.map { it.toolName to it.shortResult })
        assertTrue(rows.all { it.status == ToolRowStatus.DONE })
    }

    @Test
    fun `call read back without a result is not shown as still running`() {
        val messages = StoredChatMessages.toChatMessages(
            listOf(stored("a1", "assistant", "", 2_000, toolCallsJson = ToolCallData.toJsonList(listOf(readCall))))
        )
        val row = TranscriptBuilder.build(messages).filterIsInstance<TranscriptItem.Tools>().single().rows.single()
        assertEquals(ToolRowStatus.NO_RESULT, row.status)
        assertEquals("read_file", row.toolName)
    }

    @Test
    fun `result without its call from old data shows once`() {
        val messages = StoredChatMessages.toChatMessages(listOf(stored("r1", "tool", "Error: file not found", 3_000, toolCallId = "gone")))
        val rows = TranscriptBuilder.build(messages).filterIsInstance<TranscriptItem.Tools>().single().rows
        assertEquals(1, rows.size)
        assertEquals(ToolRowStatus.FAILED, rows.single().status)
    }

    @Test
    fun `failed call followed by a successful one becomes one dim line naming the retry`() {
        val edit = ToolCallData(id = "c1", name = "code_editing", arguments = """{"path":"demo.html"}""")
        val create = ToolCallData(id = "c2", name = "create_new_file", arguments = """{"path":"demo.html"}""")
        val messages = StoredChatMessages.toChatMessages(
            listOf(
                stored("a1", "assistant", "", 1_000, toolCallsJson = ToolCallData.toJsonList(listOf(edit, create))),
                stored("r1", "tool", "Error: file not found", 2_000, toolCallId = "c1"),
                stored("r2", "tool", "created demo.html", 3_000, toolCallId = "c2"),
            )
        )
        val rows = TranscriptBuilder.build(messages).filterIsInstance<TranscriptItem.Tools>().single().rows
        assertEquals(ToolRowStatus.RECOVERED, rows[0].status)
        assertEquals("create_new_file", rows[0].retriedAs)
        assertEquals(ToolRowStatus.DONE, rows[1].status)
    }

    @Test
    fun `a later success of an unrelated call does not hide a failure`() {
        val command = ToolCallData(id = "c1", name = "run_terminal_command", arguments = """{"command":"./gradlew test"}""")
        val read = ToolCallData(id = "c2", name = "read_file", arguments = """{"path":"build.gradle.kts"}""")
        val messages = StoredChatMessages.toChatMessages(
            listOf(
                stored("a1", "assistant", "", 1_000, toolCallsJson = ToolCallData.toJsonList(listOf(command, read))),
                stored("r1", "tool", "Error: tests failed", 2_000, toolCallId = "c1"),
                stored("r2", "tool", "plugins { }", 3_000, toolCallId = "c2"),
            )
        )
        val rows = TranscriptBuilder.build(messages).filterIsInstance<TranscriptItem.Tools>().single().rows
        assertEquals(ToolRowStatus.FAILED, rows[0].status)
        assertNull(rows[0].retriedAs)
    }

    @Test
    fun `failure the agent did not get past stays an error`() {
        val messages = StoredChatMessages.toChatMessages(
            listOf(
                stored("a1", "assistant", "", 1_000, toolCallsJson = ToolCallData.toJsonList(listOf(readCall))),
                stored("r1", "tool", "Error: permission denied", 2_000, toolCallId = "c1"),
            )
        )
        val row = TranscriptBuilder.build(messages).filterIsInstance<TranscriptItem.Tools>().single().rows.single()
        assertEquals(ToolRowStatus.FAILED, row.status)
        assertNull(row.retriedAs)
    }

    @Test
    fun `assistant text and its tool calls are separate items, text first`() {
        val items = TranscriptBuilder.build(reloadedTurn(emptyMap()))
        assertEquals(listOf("Message", "Message", "Tools"), items.map { it::class.simpleName })
    }

    @Test
    fun `main argument prefers a path, then a command or query, then a nested path`() {
        assertEquals("a/B.kt", TranscriptBuilder.mainArgument("""{"content":"x","path":"a/B.kt"}"""))
        assertEquals("gradle test", TranscriptBuilder.mainArgument("""{"command":"gradle test","timeout":30}"""))
        assertEquals("c/D.kt", TranscriptBuilder.mainArgument("""{"edits":[{"path":"c/D.kt","old_string":"a"}]}"""))
        assertEquals("", TranscriptBuilder.mainArgument("not json"))
    }
}
