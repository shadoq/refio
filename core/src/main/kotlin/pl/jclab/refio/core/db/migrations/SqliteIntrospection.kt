package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import pl.jclab.refio.core.logging.dualLogger
import java.sql.Connection

private val sqliteMigrationLogger = dualLogger("SqliteMigration")

internal fun tableExists(jdbc: Connection, name: String): Boolean {
    jdbc.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?"
    ).use { ps ->
        ps.setString(1, name)
        ps.executeQuery().use { rs -> return rs.next() }
    }
}

internal fun columnExists(jdbc: Connection, table: String, column: String): Boolean {
    if (!tableExists(jdbc, table)) return false
    jdbc.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
            while (rs.next()) {
                if (rs.getString("name").equals(column, ignoreCase = true)) return true
            }
        }
    }
    return false
}

/**
 * Executes [block] inside a SQLite `BEGIN IMMEDIATE` transaction on a dedicated JDBC connection.
 *
 * When [disableForeignKeys] is true, `PRAGMA foreign_keys = OFF` is applied while `autoCommit` is
 * true (outside any active transaction), because SQLite silently ignores `PRAGMA foreign_keys`
 * changes once a transaction has started. Foreign keys are always restored in `finally`, followed
 * by a `PRAGMA foreign_key_check` diagnostic pass.
 */
internal fun <T> runSqliteImmediate(
    database: Database,
    disableForeignKeys: Boolean = false,
    block: (Connection) -> T
): T {
    val exposedConn = database.connector()
    try {
        val jdbc = exposedConn.connection as Connection
        val prevAutoCommit = jdbc.autoCommit
        jdbc.autoCommit = true
        var foreignKeysDisabled = false
        try {
            jdbc.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=10000")
                if (disableForeignKeys) {
                    st.execute("PRAGMA foreign_keys = OFF")
                    foreignKeysDisabled = true
                }
                st.execute("BEGIN IMMEDIATE")
            }

            val result = try {
                val value = block(jdbc)
                jdbc.createStatement().use { st -> st.execute("COMMIT") }
                value
            } catch (t: Throwable) {
                runCatching {
                    jdbc.createStatement().use { st -> st.execute("ROLLBACK") }
                }
                throw t
            }

            if (foreignKeysDisabled) {
                jdbc.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                    foreignKeysDisabled = false
                    st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                        var violations = 0
                        while (rs.next()) {
                            violations++
                            if (violations <= 5) {
                                sqliteMigrationLogger.warn {
                                    "Foreign key check violation after migration: table=${rs.getString(1)}, " +
                                        "rowid=${rs.getLong(2)}, parent=${rs.getString(3)}"
                                }
                            }
                        }
                        if (violations > 0) {
                            sqliteMigrationLogger.warn { "Total foreign_key_check violations after migration: $violations" }
                        }
                    }
                }
            }

            return result
        } finally {
            if (foreignKeysDisabled) {
                runCatching {
                    jdbc.createStatement().use { st -> st.execute("PRAGMA foreign_keys = ON") }
                }
            }
            runCatching { jdbc.autoCommit = prevAutoCommit }
        }
    } finally {
        runCatching { exposedConn.close() }
    }
}
