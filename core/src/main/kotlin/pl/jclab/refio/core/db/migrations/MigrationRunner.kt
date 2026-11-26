package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("MigrationRunner")

/**
 * Executes pending database migrations.
 *
 * Each migration acquires its own `BEGIN IMMEDIATE` transaction and re-checks `schema_migrations`
 * inside that write lock so concurrent processes (IDE + CLI sharing one SQLite file) cannot apply
 * the same migration twice.
 */
object MigrationRunner {
    private val migrations: List<Migration> = listOf(
        // SeedTestDataMigration removed from production runtime.
        // Seed data was for UI development only. Existing databases retain v1 data.
        V2DropAgentEventsSessionFk(),
        V3RenameSlashCommandToSlashPrompt(),
        V4SnapshotGroups(),
        V5RenameThinkingEnabledToReasoningEffort(),
        V6AddCachedTokensToTasks(),
        V7SubtaskAgentInstance(),
        V8ConfigAndMcpUniqueness(),
    )

    fun run(database: Database) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(SchemaMigrationsTable)
        }

        migrations
            .sortedBy { it.version }
            .forEach { migration ->
                if (migration is SqliteMigration) {
                    runSqliteImmediate(database, disableForeignKeys = migration.requiresForeignKeysOff) { jdbc ->
                        if (isApplied(jdbc, migration.version)) {
                            return@runSqliteImmediate
                        }
                        logger.info { "Applying migration ${migration.version}" }
                        migration.migrateOnConnection(jdbc)
                        recordApplied(jdbc, migration.version)
                    }
                } else {
                    val alreadyApplied = runSqliteImmediate(database) { jdbc ->
                        isApplied(jdbc, migration.version)
                    }
                    if (!alreadyApplied) {
                        logger.info { "Applying migration ${migration.version}" }
                        migration.migrate(database)
                        runSqliteImmediate(database) { jdbc ->
                            recordApplied(jdbc, migration.version)
                        }
                    }
                }
            }
    }

    private fun isApplied(jdbc: java.sql.Connection, version: Int): Boolean {
        jdbc.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?").use { ps ->
            ps.setInt(1, version)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun recordApplied(jdbc: java.sql.Connection, version: Int) {
        jdbc.prepareStatement(
            "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, ?)"
        ).use { ps ->
            ps.setInt(1, version)
            ps.setLong(2, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }
}
