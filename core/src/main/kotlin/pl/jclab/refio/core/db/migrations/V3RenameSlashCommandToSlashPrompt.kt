package pl.jclab.refio.core.db.migrations

import pl.jclab.refio.core.logging.dualLogger
import java.sql.Connection

private val logger = dualLogger("V3RenameSlashCommandToSlashPrompt")

/**
 * Renames PromptType value `SLASH_COMMAND` to `SLASH_PROMPT` for existing rows.
 *
 * The feature was renamed from "slash commands" to "slash prompts" because these
 * are reusable prompt templates invoked via `/name`, not shell/CLI commands.
 * Existing databases stored the enum as its string name in `prompts.type`, so
 * we remap them in-place to keep user data (both built-in and custom entries).
 */
class V3RenameSlashCommandToSlashPrompt : SqliteMigration() {
    override val version: Int = 3

    override fun migrateOnConnection(jdbc: Connection) {
        if (!tableExists(jdbc, "prompts")) {
            logger.info { "prompts table does not exist (fresh DB); skipping V3 rename" }
            return
        }
        jdbc.createStatement().use { st ->
            val updated = st.executeUpdate(
                "UPDATE prompts SET type = 'SLASH_PROMPT' WHERE type = 'SLASH_COMMAND'"
            )
            logger.info { "Renamed SLASH_COMMAND -> SLASH_PROMPT for $updated row(s)" }
        }
    }
}
