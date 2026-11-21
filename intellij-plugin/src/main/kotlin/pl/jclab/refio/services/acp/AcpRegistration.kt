package pl.jclab.refio.services.acp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Adds Refio to the agent list of JetBrains AI Assistant (`~/.jetbrains/acp.json`, key
 * `agent_servers`), so the IDE can start the Refio CLI as an Agent Client Protocol agent.
 *
 * The file belongs to AI Assistant and may list other agents, so this only ever touches the
 * Refio entry: every other key and every unknown field is kept, the previous file is copied to
 * `acp.json.bak`, the new one is written atomically, and a file that is not valid JSON is left
 * alone instead of being "repaired".
 */
object AcpRegistration {
    const val AGENT_NAME = "Refio"
    val DEFAULT_ARGS = listOf("--acp")

    sealed class Result {
        data class Written(val path: Path, val created: Boolean) : Result()
        data class Unchanged(val path: Path) : Result()
        data class Invalid(val path: Path, val reason: String) : Result()
    }

    fun defaultConfigPath(): Path = Paths.get(System.getProperty("user.home"), ".jetbrains", "acp.json")

    fun register(configPath: Path, command: String, args: List<String> = DEFAULT_ARGS): Result {
        val exists = Files.exists(configPath)
        val root: JsonObject = if (exists) {
            val parsed = try {
                JsonParser.parseString(Files.readString(configPath).ifBlank { "{}" })
            } catch (e: Exception) {
                return Result.Invalid(configPath, "the file is not valid JSON (${e.message})")
            }
            parsed as? JsonObject ?: return Result.Invalid(configPath, "the file does not contain a JSON object")
        } else {
            JsonObject()
        }

        val serversElement = root.get("agent_servers")
        val servers: JsonObject = when {
            serversElement == null || serversElement.isJsonNull -> JsonObject().also { root.add("agent_servers", it) }
            serversElement.isJsonObject -> serversElement.asJsonObject
            else -> return Result.Invalid(configPath, "\"agent_servers\" is not a JSON object")
        }

        val before = servers.get(AGENT_NAME)?.deepCopy()
        val entry = (servers.get(AGENT_NAME) as? JsonObject) ?: JsonObject()
        entry.addProperty("command", command)
        entry.add("args", JsonArray().apply { args.forEach { add(it) } })
        servers.add(AGENT_NAME, entry)

        if (before == entry) return Result.Unchanged(configPath)

        Files.createDirectories(configPath.toAbsolutePath().parent)
        if (exists) {
            Files.copy(configPath, configPath.resolveSibling(configPath.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
        }
        val temp = Files.createTempFile(configPath.toAbsolutePath().parent, "acp", ".json.tmp")
        try {
            Files.writeString(temp, gson.toJson(root) + "\n")
            try {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return Result.Written(configPath, created = !exists)
    }

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}
