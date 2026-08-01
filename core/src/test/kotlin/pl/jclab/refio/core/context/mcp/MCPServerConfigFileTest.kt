package pl.jclab.refio.core.context.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MCPServerConfigFileTest {

    @Test
    fun `a server declared for one run connects, since a transient flag would leave it disabled`(
        @TempDir tmp: Path
    ) {
        val file = write(
            tmp,
            """{ "id": "stub", "type": "STDIO", "command": "python", "args": ["stub_server.py"] }"""
        )

        val config = MCPServerConfigFile.parse(file)

        assertTrue(config.enabled, "a config with no explicit flag must connect")
        assertEquals("stub", config.id)
        assertEquals(listOf("stub_server.py"), config.args)
    }

    @Test
    fun `an explicitly disabled server stays disabled`(@TempDir tmp: Path) {
        val file = write(
            tmp,
            """{ "id": "stub", "type": "STDIO", "command": "python", "enabled": false }"""
        )

        assertTrue(!MCPServerConfigFile.parse(file).enabled)
    }

    @Test
    fun `the exposure mode survives the file, since CONTEXT means the tool is never registered`(
        @TempDir tmp: Path
    ) {
        val file = write(
            tmp,
            """{ "id": "docs", "type": "STDIO", "command": "python",
                 "toolsExposureMode": "CONTEXT", "contextToolName": "query-docs" }"""
        )

        val config = MCPServerConfigFile.parse(file)

        assertEquals(MCPToolsExposureMode.CONTEXT, config.toolsExposureMode)
        assertEquals("query-docs", config.contextToolName)
    }

    @Test
    fun `an HTTP server without a url is rejected instead of failing later as a silent no-call`(
        @TempDir tmp: Path
    ) {
        val file = write(tmp, """{ "id": "http", "type": "HTTP_STREAMABLE" }""")

        val error = assertFailsWith<IllegalArgumentException> { MCPServerConfigFile.parse(file) }
        assertTrue(error.message!!.contains("url"), "message should name the missing field")
    }

    @Test
    fun `a STDIO server without a command is rejected`(@TempDir tmp: Path) {
        val file = write(tmp, """{ "id": "stdio", "type": "STDIO" }""")

        val error = assertFailsWith<IllegalArgumentException> { MCPServerConfigFile.parse(file) }
        assertTrue(error.message!!.contains("command"))
    }

    @Test
    fun `a malformed file is rejected loudly rather than skipped`(@TempDir tmp: Path) {
        val file = write(tmp, "{ this is not json")

        assertFailsWith<IllegalArgumentException> { MCPServerConfigFile.parse(file) }
    }

    @Test
    fun `a missing file is rejected loudly`(@TempDir tmp: Path) {
        assertFailsWith<IllegalArgumentException> {
            MCPServerConfigFile.parse(tmp.resolve("absent.json").toFile())
        }
    }

    @Test
    fun `fields the file omits keep their defaults, since Gson skips the constructor`(
        @TempDir tmp: Path
    ) {
        val file = write(tmp, """{ "id": "stub", "type": "STDIO", "command": "python" }""")

        val config = MCPServerConfigFile.parse(file)

        // Non-null collections would arrive null and throw on first use.
        assertEquals(emptyList(), config.httpHeaders)
        assertEquals(emptyList(), config.env)
        assertEquals(emptyMap(), config.toolParamMapping)
        // A server defaults to exposing its tools; arriving as CONTEXT would make it uncallable.
        assertEquals(MCPToolsExposureMode.TOOLS, config.toolsExposureMode)
        assertEquals(MCPAccessMode.READ, config.accessMode)
        assertTrue(config.toolsEnabled)
        assertTrue(config.resourcesEnabled)
        assertEquals(30_000L, config.timeout)
    }

    private fun write(tmp: Path, content: String): File =
        tmp.resolve("server.json").toFile().apply { writeText(content) }
}
