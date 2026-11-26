package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import java.sql.Connection

/**
 * Contract for database migrations.
 */
interface Migration {
    val version: Int
    fun migrate(database: Database)
}

/**
 * SQLite migration that executes on a single raw JDBC connection inside a `BEGIN IMMEDIATE`
 * transaction, optionally toggling `PRAGMA foreign_keys = OFF` outside the transaction.
 */
abstract class SqliteMigration : Migration {
    open val requiresForeignKeysOff: Boolean = false

    abstract fun migrateOnConnection(jdbc: Connection)

    override fun migrate(database: Database) {
        runSqliteImmediate(database, disableForeignKeys = requiresForeignKeysOff) { jdbc ->
            migrateOnConnection(jdbc)
        }
    }
}
