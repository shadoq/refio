package pl.jclab.refio.cli.acp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.ToolApprovalService
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import pl.jclab.refio.core.services.turn.TurnEventListener
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The protocol flow end to end, with a scripted turn in place of a model: what the IDE sees for
 * tool calls, approvals and a cancel, and in which order.
 */
class AcpAgentFlowTest {

    private class ScriptedBackend(
        private val script: suspend ScriptedBackend.(taskId: String, listener: TurnEventListener) -> TurnResult,
    ) : AcpBackend {
        override val approvals = ToolApprovalService()
        var openedAt: Path? = null
        val finished = mutableListOf<TurnResult?>()

        override suspend fun open(cwd: Path, mcpServers: List<MCPServerConfig>): Boolean {
            if (openedAt == null) openedAt = cwd
            return openedAt == cwd
        }

        override fun createTask(mode: TaskMode) = "task-1"

        override suspend fun runTurn(
            taskId: String, mode: TaskMode, text: String, contextRefs: List<ContextReference>, listener: TurnEventListener,
        ): TurnResult = script(taskId, listener)

        override fun markFinished(taskId: String, result: TurnResult?) { finished += result }
        override fun close() {}

        suspend fun ask(taskId: String, toolCallId: String, tool: String, args: Map<String, Any>): ApprovalDecision =
            approvals.requestApproval(
                ToolApprovalService.ApprovalRequest("${taskId}_$toolCallId", taskId, tool, args, "")
            )
    }

    private fun done(text: String) =
        TurnResult(success = true, response = text, iterations = 1, tokensIn = 0, tokensOut = 0, cost = 0.0)

    private suspend fun openSession(client: AcpTestClient, backend: AcpBackend, cwd: Path): String {
        AcpAgent(client.peer, backend, TaskMode.AGENT, client.scope).install()
        client.start()
        client.request(1, "initialize", buildJsonObject { put("protocolVersion", 1) })
        client.readUntil { it.isResponse(1) }
        client.request(2, "session/new", buildJsonObject { put("cwd", cwd.toAbsolutePath().toString()); put("mcpServers", buildJsonArray {}) })
        return client.readUntil { it.isResponse(2) }.last().obj("result").str("sessionId")!!
    }

    private fun promptParams(sessionId: String, text: String) = buildJsonObject {
        put("sessionId", sessionId)
        put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    }

    @Test
    fun `the IDE sees the tool call, its result and the final answer before the turn ends`(@TempDir cwd: Path) = runBlocking<Unit> {
        val backend = ScriptedBackend { taskId, listener ->
            val call = ToolCallData("c1", "read_file", """{"path":"README.md"}""")
            listener.onToolExecutionStarted(taskId, call)
            listener.onToolExecutionCompleted(taskId, call, "# Readme", true)
            done("The README has one heading.")
        }
        AcpTestClient().use { client ->
            val session = openSession(client, backend, cwd)
            client.request(3, "session/prompt", promptParams(session, "What is in the README?"))

            val messages = client.readUntil { it.isResponse(3) }

            assertEquals(
                listOf("tool_call", "tool_call_update", "agent_message_chunk"),
                messages.mapNotNull { it.updateKind() }
            )
            val call = messages.first { it.updateKind() == "tool_call" }.obj("params").obj("update")
            assertEquals("c1", call.str("toolCallId"))
            assertEquals("read", call.str("kind"))
            assertEquals("completed", messages.first { it.updateKind() == "tool_call_update" }.obj("params").obj("update").str("status"))
            assertEquals("end_turn", messages.last().obj("result").str("stopReason"))
        }
    }

    @Test
    fun `rejecting one call in the IDE lets the turn continue with a denial`(@TempDir cwd: Path) = runBlocking<Unit> {
        var decision: ApprovalDecision? = null
        val backend = ScriptedBackend { taskId, _ ->
            decision = ask(taskId, "c2", "run_terminal_command", mapOf("command" to "rm -rf build"))
            done("I did not delete the build directory.")
        }
        AcpTestClient().use { client ->
            val session = openSession(client, backend, cwd)
            client.request(3, "session/prompt", promptParams(session, "Clean the build"))

            val permission = client.readUntil { it.str("method") == "session/request_permission" }.last()
            assertEquals("c2", permission.obj("params").obj("toolCall").str("toolCallId"))
            client.respond(permission["id"]!!, selected("reject_once"))

            val rest = client.readUntil { it.isResponse(3) }
            assertIs<ApprovalDecision.NotPermitted>(decision)
            assertEquals("end_turn", rest.last().obj("result").str("stopReason"))
        }
    }

    @Test
    fun `always allow is asked once per tool, the next call of that tool runs without a prompt`(@TempDir cwd: Path) = runBlocking<Unit> {
        val decisions = mutableListOf<ApprovalDecision>()
        val backend = ScriptedBackend { taskId, _ ->
            decisions += ask(taskId, "c1", "run_terminal_command", mapOf("command" to "gradle test"))
            decisions += ask(taskId, "c2", "run_terminal_command", mapOf("command" to "gradle build"))
            done("Both ran.")
        }
        AcpTestClient().use { client ->
            val session = openSession(client, backend, cwd)
            client.request(3, "session/prompt", promptParams(session, "Test and build"))

            val first = client.readUntil { it.str("method") == "session/request_permission" }.last()
            client.respond(first["id"]!!, selected("allow_always"))
            val rest = client.readUntil { it.isResponse(3) }

            assertTrue(rest.none { it.str("method") == "session/request_permission" })
            assertEquals(2, decisions.size)
            assertIs<ApprovalDecision.Trusted>(decisions[0])
        }
    }

    @Test
    fun `cancelling while the IDE shows an approval ends the turn as cancelled and the tool never runs`(@TempDir cwd: Path) = runBlocking<Unit> {
        val toolRan = AtomicBoolean(false)
        val backend = ScriptedBackend { taskId, _ ->
            val d = ask(taskId, "c1", "create_new_file", mapOf("path" to "a.txt", "content" to "x"))
            if (d is ApprovalDecision.Approved) toolRan.set(true)
            done("written")
        }
        AcpTestClient().use { client ->
            val session = openSession(client, backend, cwd)
            client.request(3, "session/prompt", promptParams(session, "Create a.txt"))

            val permission = client.readUntil { it.str("method") == "session/request_permission" }.last()
            client.notification("session/cancel", buildJsonObject { put("sessionId", session) })
            // The protocol requires the IDE to answer open permission prompts with "cancelled".
            client.respond(permission["id"]!!, buildJsonObject { put("outcome", buildJsonObject { put("outcome", "cancelled") }) })

            val rest = client.readUntil { it.isResponse(3) }
            assertEquals("cancelled", rest.last().obj("result").str("stopReason"))
            assertFalse(toolRan.get())
            assertTrue(rest.none { it.updateKind() == "agent_message_chunk" })
        }
    }

    @Test
    fun `one process serves one project, a session for another directory is refused`(@TempDir cwd: Path, @TempDir other: Path) = runBlocking<Unit> {
        val backend = ScriptedBackend { _, _ -> done("") }
        AcpTestClient().use { client ->
            openSession(client, backend, cwd)
            client.request(9, "session/new", buildJsonObject { put("cwd", other.toAbsolutePath().toString()) })
            val reply = client.readUntil { it.isResponse(9) }.last()
            assertEquals(AcpError.INVALID_PARAMS, reply.obj("error")["code"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `switching to plan mode is confirmed to the IDE and used by the next turn`(@TempDir cwd: Path) = runBlocking<Unit> {
        val backend = ScriptedBackend { _, _ -> done("ok") }
        AcpTestClient().use { client ->
            val session = openSession(client, backend, cwd)
            client.request(4, "session/set_mode", buildJsonObject { put("sessionId", session); put("modeId", "plan") })
            val messages = client.readUntil { it.isResponse(4) }
            assertEquals("plan", messages.first { it.updateKind() == "current_mode_update" }.obj("params").obj("update").str("currentModeId"))
        }
    }

    private fun selected(optionId: String): JsonObject = buildJsonObject {
        put("outcome", buildJsonObject { put("outcome", "selected"); put("optionId", optionId) })
    }
}
