package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("MigrationRunner")

/**
 * Executes pending database migrations.
 */
object MigrationRunner {
    private val migrations: List<Migration> = listOf(
        // SeedTestDataMigration removed from production runtime (Phase 7 refactor).
        // Seed data was for UI development only. Existing databases retain v1 data.
        V2DropAgentEventsSessionFk(),
        V3RenameSlashCommandToSlashPrompt(),
    )

    fun run(database: Database) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(SchemaMigrationsTable)

            val appliedVersions = SchemaMigrationsTable.selectAll()
                .map { it[SchemaMigrationsTable.version] }
                .toSet()

            migrations
                .sortedBy { it.version }
                .filter { it.version !in appliedVersions }
                .forEach { migration ->
                    logger.info { "Applying migration ${migration.version}" }
                    migration.migrate(database)
                    SchemaMigrationsTable.insert {
                        it[version] = migration.version
                    }
                }
        }
    }
}
