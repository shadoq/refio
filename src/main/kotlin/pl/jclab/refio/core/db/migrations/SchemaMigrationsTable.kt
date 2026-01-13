package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Table

/**
 * Tracks executed database migrations.
 */
object SchemaMigrationsTable : Table("schema_migrations") {
    val version = integer("version")
    val appliedAt = long("applied_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(version)
}
