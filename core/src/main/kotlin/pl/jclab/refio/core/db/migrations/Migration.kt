package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database

/**
 * Contract for database migrations.
 */
interface Migration {
    val version: Int
    fun migrate(database: Database)
}
