package pl.jclab.refio.core.context.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import pl.jclab.refio.core.utils.GsonInstance
import java.io.File

/**
 * Reads an [MCPServerConfig] from a JSON file, for servers declared per run rather than stored in
 * the database.
 *
 * The file uses the same field names as the stored config, so a server can be moved between the
 * two by copy and paste.
 */
object MCPServerConfigFile {

    private val gson = GsonInstance.gson

    /**
     * @throws IllegalArgumentException when the file is missing, malformed, or describes a server
     * that cannot be connected to. Failing here is the point: a headless run that silently skipped
     * a broken server would look exactly like a model that chose not to call the tool.
     */
    fun parse(file: File): MCPServerConfig {
        require(file.isFile) { "MCP server config not found: ${file.absolutePath}" }

        val json = try {
            gson.fromJson(file.readText(), JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("MCP server config is not valid JSON: ${file.absolutePath}", e)
        } ?: throw IllegalArgumentException("MCP server config is empty: ${file.absolutePath}")

        val config = gson.fromJson(withDefaults(json), MCPServerConfig::class.java)
            ?: throw IllegalArgumentException("MCP server config is empty: ${file.absolutePath}")

        require(config.id.isNotBlank()) { "MCP server config needs a non-blank id: ${file.absolutePath}" }
        when (config.type) {
            MCPServerType.STDIO ->
                require(!config.command.isNullOrBlank()) {
                    "STDIO MCP server '${config.id}' needs a command: ${file.absolutePath}"
                }
            MCPServerType.HTTP_SSE, MCPServerType.HTTP_STREAMABLE ->
                require(!config.url.isNullOrBlank()) {
                    "${config.type} MCP server '${config.id}' needs a url: ${file.absolutePath}"
                }
        }

        // `enabled` is transient, so Gson neither reads it from the file nor applies the data
        // class default - an untouched config would arrive disabled and never connect. The stored
        // path has the same gap and fills the value from its own column.
        return config.copy(enabled = json.get(FIELD_ENABLED)?.asBoolean ?: true)
    }

    /**
     * Lays the file over a serialized default config.
     *
     * Gson builds objects without running the constructor, so any field the file omits would stay
     * null - including non-null Kotlin collections, which then blow up on first use. The stored
     * path never hits this because its JSON is machine-written and always complete. Merging over
     * the defaults keeps that guarantee for hand-written files without a field-by-field mapping
     * that would rot the next time the config grows a field.
     */
    private fun withDefaults(json: JsonObject): JsonObject {
        val merged = gson.toJsonTree(DEFAULTS).asJsonObject
        json.entrySet().forEach { (key, value) -> merged.add(key, value) }
        return merged
    }

    private val DEFAULTS = MCPServerConfig(id = "", type = MCPServerType.STDIO)

    private const val FIELD_ENABLED = "enabled"
}
