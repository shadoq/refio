package pl.jclab.refio.services.acp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcpRegistrationTest {

    private fun read(path: Path) = JsonParser.parseString(Files.readString(path)).asJsonObject

    @Test
    fun `a missing file is created with the Refio entry only`(@TempDir dir: Path) {
        val config = dir.resolve(".jetbrains/acp.json")

        val result = AcpRegistration.register(config, "/opt/refio/bin/cli")

        assertIs<AcpRegistration.Result.Written>(result)
        val refio = read(config).getAsJsonObject("agent_servers").getAsJsonObject("Refio")
        assertEquals("/opt/refio/bin/cli", refio.get("command").asString)
        assertEquals("--acp", refio.getAsJsonArray("args").single().asString)
    }

    @Test
    fun `other agents, global settings and unknown fields survive the registration`(@TempDir dir: Path) {
        val config = dir.resolve("acp.json")
        val original = """
            {"default_mcp_settings":{"use_idea_mcp":true},
             "agent_servers":{"goose":{"command":"/bin/goose","args":["acp"],"env":{"K":"v"}},
                              "Refio":{"command":"/old","args":["--acp"],"env":{"REFIO_HOME":"/h"}}},
             "future_key":42}
        """.trimIndent()
        Files.writeString(config, original)

        AcpRegistration.register(config, "/new/cli")

        val root = read(config)
        assertEquals(JsonParser.parseString(original).asJsonObject.get("default_mcp_settings"), root.get("default_mcp_settings"))
        assertEquals(42, root.get("future_key").asInt)
        assertEquals("/bin/goose", root.getAsJsonObject("agent_servers").getAsJsonObject("goose").get("command").asString)
        val refio = root.getAsJsonObject("agent_servers").getAsJsonObject("Refio")
        assertEquals("/new/cli", refio.get("command").asString)
        assertEquals("/h", refio.getAsJsonObject("env").get("REFIO_HOME").asString)
        assertEquals(original, Files.readString(dir.resolve("acp.json.bak")))
    }

    @Test
    fun `registering twice does not rewrite the file`(@TempDir dir: Path) {
        val config = dir.resolve("acp.json")
        AcpRegistration.register(config, "/cli")
        val first = Files.readString(config)

        assertIs<AcpRegistration.Result.Unchanged>(AcpRegistration.register(config, "/cli"))
        assertEquals(first, Files.readString(config))
    }

    @Test
    fun `a broken file is left untouched and reported`(@TempDir dir: Path) {
        val config = dir.resolve("acp.json")
        Files.writeString(config, "{ \"agent_servers\": { oops ")

        val result = AcpRegistration.register(config, "/cli")

        assertIs<AcpRegistration.Result.Invalid>(result)
        assertEquals("{ \"agent_servers\": { oops ", Files.readString(config))
        assertTrue(Files.notExists(dir.resolve("acp.json.bak")))
    }
}
