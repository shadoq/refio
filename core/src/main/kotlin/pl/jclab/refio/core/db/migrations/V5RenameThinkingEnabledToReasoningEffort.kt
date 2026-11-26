package pl.jclab.refio.core.db.migrations

import pl.jclab.refio.core.logging.dualLogger
import java.sql.Connection

private val logger = dualLogger("V5RenameThinkingEnabledToReasoningEffort")

/**
 * Migrates the legacy boolean config key `general.thinking_enabled` to the enum
 * `general.reasoning_effort` (OFF/LOW/MEDIUM/HIGH). The old `true` maps to `MEDIUM`,
 * anything else (`false`) to `OFF`, preserving each stored override across scopes.
 * If `general.reasoning_effort` already exists for the same scope, the existing value wins
 * and the redundant legacy row is dropped instead of causing a unique constraint conflict.
 */
class V5RenameThinkingEnabledToReasoningEffort : SqliteMigration() {
    override val version: Int = 5

    override fun migrateOnConnection(jdbc: Connection) {
        if (!tableExists(jdbc, "config")) {
            logger.info { "config table does not exist (fresh DB); skipping V5 rename" }
            return
        }
        jdbc.createStatement().use { st ->
            val droppedConflicts = st.executeUpdate(
                """
                DELETE FROM config
                WHERE key = 'general.thinking_enabled'
                  AND EXISTS (
                      SELECT 1 FROM config AS existing
                      WHERE existing.key = 'general.reasoning_effort'
                        AND existing.scope = config.scope
                        AND ((existing.project_id IS NULL AND config.project_id IS NULL) OR existing.project_id = config.project_id)
                        AND ((existing.task_id IS NULL AND config.task_id IS NULL) OR existing.task_id = config.task_id)
                  )
                """.trimIndent()
            )
            if (droppedConflicts > 0) {
                logger.info { "Dropped $droppedConflicts conflicting legacy general.thinking_enabled row(s)" }
            }

            val updated = st.executeUpdate(
                """
                UPDATE config
                SET key = 'general.reasoning_effort',
                    value = CASE WHEN value = 'true' THEN 'MEDIUM' ELSE 'OFF' END
                WHERE key = 'general.thinking_enabled'
                """.trimIndent()
            )
            logger.info { "Renamed general.thinking_enabled -> general.reasoning_effort for $updated row(s)" }
        }
    }
}
