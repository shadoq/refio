package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("V5RenameThinkingEnabledToReasoningEffort")

/**
 * Migrates the legacy boolean config key `general.thinking_enabled` to the enum
 * `general.reasoning_effort` (OFF/LOW/MEDIUM/HIGH). The old `true` maps to `MEDIUM`,
 * anything else (`false`) to `OFF`, preserving each stored override across scopes.
 */
class V5RenameThinkingEnabledToReasoningEffort : Migration {
    override val version: Int = 5

    override fun migrate(database: Database) {
        transaction(database) {
            val jdbc = (connection.connection as java.sql.Connection)
            if (!tableExists(jdbc, "config")) {
                logger.info { "config table does not exist (fresh DB); skipping V5 rename" }
                return@transaction
            }
            jdbc.createStatement().use { st ->
                val updated = st.executeUpdate(
                    """
                    UPDATE config
                    SET key = 'general.reasoning_effort',
                        value = CASE WHEN value = 'true' THEN 'MEDIUM' ELSE 'OFF' END
                    WHERE key = 'general.thinking_enabled'
                    """.trimIndent()
                )
                logger.info { "Renamed general.thinking_enabled → general.reasoning_effort for $updated row(s)" }
            }
        }
    }
}
