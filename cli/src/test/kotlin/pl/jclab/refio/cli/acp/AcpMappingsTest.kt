package pl.jclab.refio.cli.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.context.mcp.MCPServerType
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.ToolApprovalService.ApprovalDecision
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcpMappingsTest {

    private fun result(stop: TurnStopReason, success: Boolean = true) =
        TurnResult(success = success, response = "", iterations = 1, tokensIn = 0, tokensOut = 0, cost = 0.0, stopReason = stop)

    @Test
    fun `a stop requested by the IDE is reported as cancelled even if the turn finished normally`() {
        assertEquals("cancelled", AcpMappings.stopReason(result(TurnStopReason.COMPLETED), cancelRequested = true))
        assertEquals("cancelled", AcpMappings.stopReason(null, cancelRequested = false))
    }

    @Test
    fun `hitting the iteration cap tells the IDE the turn ran out of requests, not that it finished`() {
        assertEquals("max_turn_requests", AcpMappings.stopReason(result(TurnStopReason.MAX_ITERATIONS, success = false), false))
        assertEquals("max_tokens", AcpMappings.stopReason(result(TurnStopReason.OUTPUT_TRUNCATED, success = false), false))
    }

    @Test
    fun `limits and guardians end the turn normally so their explanation reaches the user as text`() {
        listOf(TurnStopReason.COMPLETED, TurnStopReason.COST_LIMIT, TurnStopReason.TIME_LIMIT, TurnStopReason.GUARDIAN_INCOMPLETE)
            .forEach { assertEquals("end_turn", AcpMappings.stopReason(result(it), false), it.name) }
    }

    @Test
    fun `a file link inside the project becomes context, one outside the project is dropped`(@TempDir root: Path) {
        val inside = root.resolve("src/Main.kt")
        val outside = root.parent.resolve("secret.txt")
        val blocks = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "Explain this") })
            add(buildJsonObject { put("type", "resource_link"); put("name", "Main.kt"); put("uri", inside.toUri().toString()) })
            add(buildJsonObject { put("type", "resource_link"); put("name", "secret.txt"); put("uri", outside.toUri().toString()) })
        }

        val input = AcpMappings.promptToTurnInput(blocks, root)

        assertEquals("Explain this", input.text)
        assertEquals(listOf(inside.toAbsolutePath().normalize().toString()), input.contextRefs.map { it.path })
        assertTrue(input.skipped.single().contains("outside project"))
    }

    @Test
    fun `embedded editor content is passed to the model as text`(@TempDir root: Path) {
        val blocks = buildJsonArray {
            add(buildJsonObject {
                put("type", "resource")
                put("resource", buildJsonObject { put("uri", "file:///x/Sel.kt"); put("text", "fun selected() = 1") })
            })
        }
        val input = AcpMappings.promptToTurnInput(blocks, root)
        assertTrue(input.text.contains("fun selected() = 1"))
        assertTrue(input.text.contains("file:///x/Sel.kt"))
    }

    @Test
    fun `tool kinds drive the IDE icons for the main tool families`() {
        assertEquals("read", AcpMappings.toolKind("read_file"))
        assertEquals("search", AcpMappings.toolKind("grep_search"))
        assertEquals("edit", AcpMappings.toolKind("code_editing"))
        assertEquals("execute", AcpMappings.toolKind("run_terminal_command"))
        assertEquals("fetch", AcpMappings.toolKind("web_search"))
        assertEquals("other", AcpMappings.toolKind("mcp_github_create_issue"))
    }

    @Test
    fun `reject once lets the turn continue, reject always and a cancelled prompt end it`() {
        fun answer(outcome: String, optionId: String? = null) = buildJsonObject {
            put("outcome", buildJsonObject { put("outcome", outcome); optionId?.let { put("optionId", it) } })
        }
        assertIs<ApprovalDecision.Approved>(AcpMappings.permissionDecision(answer("selected", "allow_once"), "read_file"))
        val trusted = AcpMappings.permissionDecision(answer("selected", "allow_always"), "read_file")
        assertIs<ApprovalDecision.Trusted>(trusted)
        assertEquals("read_file", trusted.toolName)
        assertIs<ApprovalDecision.NotPermitted>(AcpMappings.permissionDecision(answer("selected", "reject_once"), "x"))
        assertIs<ApprovalDecision.Rejected>(AcpMappings.permissionDecision(answer("selected", "reject_always"), "x"))
        assertIs<ApprovalDecision.Rejected>(AcpMappings.permissionDecision(answer("cancelled"), "x"))
        assertIs<ApprovalDecision.Rejected>(AcpMappings.permissionDecision(null, "x"))
    }

    @Test
    fun `stdio MCP servers from the IDE are connected with their environment kept secret`() {
        val servers = buildJsonArray {
            add(buildJsonObject {
                put("name", "Git Hub")
                put("command", "/usr/bin/gh-mcp")
                put("args", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("--stdio")) })
                put("env", buildJsonArray { add(buildJsonObject { put("name", "TOKEN"); put("value", "xyz") }) })
            })
            add(buildJsonObject { put("type", "http"); put("name", "remote"); put("url", "https://example.invalid") })
        }

        val configs = AcpMappings.mcpServers(servers)

        val server = configs.single()
        assertEquals("acp-git-hub", server.id)
        assertEquals(MCPServerType.STDIO, server.type)
        assertEquals(listOf("--stdio"), server.args)
        assertTrue(server.env.single().isSecret)
        assertEquals(emptyList(), AcpMappings.mcpServers(JsonArray(emptyList())))
    }
}
