package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("V6AddCachedTokensToTasks")

/**
 * Adds the `cached_tokens` column to `tasks` (cache-read input tokens accumulated per task, a
 * subset of `tokens_in`). Existing rows default to 0. Idempotent: skips when the column is already
 * present, so a fresh DB (where Exposed creates the table with the column) is unaffected.
 */
class V6AddCachedTokensToTasks : Migration {
    override val version: Int = 6

    override fun migrate(database: Database) {
        transaction(database) {
            val jdbc = (connection.connection as java.sql.Connection)
            if (!tableExists(jdbc, "tasks")) {
                logger.info { "tasks table does not exist (fresh DB); skipping V6 add-column" }
                return@transaction
            }
            if (columnExists(jdbc, "tasks", "cached_tokens")) {
                logger.info { "tasks.cached_tokens already present; skipping V6" }
                return@transaction
            }
            jdbc.createStatement().use { st ->
                st.executeUpdate("ALTER TABLE tasks ADD COLUMN cached_tokens INTEGER NOT NULL DEFAULT 0")
                logger.info { "Added tasks.cached_tokens column" }
            }
        }
    }

    private fun columnExists(jdbc: java.sql.Connection, table: String, column: String): Boolean {
        jdbc.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) return true
                }
            }
        }
        return false
    }
}
