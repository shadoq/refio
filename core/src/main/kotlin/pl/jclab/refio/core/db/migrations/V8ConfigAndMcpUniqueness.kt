package pl.jclab.refio.core.db.migrations

import pl.jclab.refio.core.logging.dualLogger
import java.sql.Connection

private val logger = dualLogger("V8ConfigAndMcpUniqueness")

/**
 * Enforces uniqueness on `config` and `mcp_servers` rows whose composite primary keys include
 * nullable columns (`project_id`, `task_id`).
 *
 * In SQLite, `NULL != NULL` in PRIMARY KEY and UNIQUE constraints, so concurrent writers could
 * insert duplicate APP-scope or global rows, causing `.singleOrNull()` lookups to return null.
 * Partial unique indexes close that gap without changing column types or breaking foreign keys.
 */
class V8ConfigAndMcpUniqueness : SqliteMigration() {
    override val version: Int = 8

    override fun migrateOnConnection(jdbc: Connection) {
        if (tableExists(jdbc, "config")) {
            jdbc.createStatement().use { st ->
                val removed = st.executeUpdate(
                    """
                    DELETE FROM config
                    WHERE EXISTS (
                        SELECT 1 FROM config AS newer
                        WHERE newer.key = config.key
                          AND newer.scope = config.scope
                          AND ((newer.project_id IS NULL AND config.project_id IS NULL) OR newer.project_id = config.project_id)
                          AND ((newer.task_id IS NULL AND config.task_id IS NULL) OR newer.task_id = config.task_id)
                          AND (newer.updated_at > config.updated_at OR (newer.updated_at = config.updated_at AND newer.rowid > config.rowid))
                    )
                    """.trimIndent()
                )
                if (removed > 0) {
                    logger.warn { "Removed $removed duplicate row(s) from config before creating unique indexes" }
                }
            }
        }

        if (tableExists(jdbc, "mcp_servers")) {
            jdbc.createStatement().use { st ->
                val removed = st.executeUpdate(
                    """
                    DELETE FROM mcp_servers
                    WHERE EXISTS (
                        SELECT 1 FROM mcp_servers AS newer
                        WHERE newer.id = mcp_servers.id
                          AND ((newer.project_id IS NULL AND mcp_servers.project_id IS NULL) OR newer.project_id = mcp_servers.project_id)
                          AND (newer.updated_at > mcp_servers.updated_at OR (newer.updated_at = mcp_servers.updated_at AND newer.rowid > mcp_servers.rowid))
                    )
                    """.trimIndent()
                )
                if (removed > 0) {
                    logger.warn { "Removed $removed duplicate row(s) from mcp_servers before creating unique indexes" }
                }
            }
        }

        ensureUniqueIndexes(jdbc)
    }

    companion object {
        /**
         * Creates partial unique indexes on `config` and `mcp_servers` if those tables exist.
         * Called both by V8 (for upgraded DBs) and after initial table creation (for fresh DBs).
         */
        fun ensureUniqueIndexes(jdbc: Connection) {
            jdbc.createStatement().use { st ->
                if (tableExists(jdbc, "config")) {
                    st.execute(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_config_app
                        ON config(key, scope)
                        WHERE scope = 'APP' AND project_id IS NULL AND task_id IS NULL
                        """.trimIndent()
                    )
                    st.execute(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_config_project
                        ON config(key, scope, project_id)
                        WHERE scope = 'PROJECT' AND project_id IS NOT NULL AND task_id IS NULL
                        """.trimIndent()
                    )
                    st.execute(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_config_task
                        ON config(key, scope, task_id)
                        WHERE scope = 'TASK' AND task_id IS NOT NULL
                        """.trimIndent()
                    )
                }

                if (tableExists(jdbc, "mcp_servers")) {
                    st.execute(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_mcp_global
                        ON mcp_servers(id)
                        WHERE project_id IS NULL
                        """.trimIndent()
                    )
                    st.execute(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_mcp_project
                        ON mcp_servers(id, project_id)
                        WHERE project_id IS NOT NULL
                        """.trimIndent()
                    )
                }
            }
        }
    }
}
