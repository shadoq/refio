package pl.jclab.refio.core.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.migrations.MigrationRunner
import pl.jclab.refio.core.db.MCPServersTable
import pl.jclab.refio.core.logging.dualLogger
import java.sql.DriverManager

private val logger = dualLogger("DatabaseFactory")

object DatabaseFactory {
    @Volatile
    private var initialized = false
    private val initLock = Object()

    fun isInitialized(): Boolean = initialized

    fun init(dbPath: String = "database.sqlite") {
        // Synchronized to prevent double initialization from concurrent callers
        synchronized(initLock) {
            if (initialized) {
                logger.info { "Database already initialized, skipping" }
                return
            }

            logger.info { "Initializing database at: $dbPath" }

            val jdbcUrl = "jdbc:sqlite:$dbPath"

            // Load and register SQLite JDBC driver explicitly for IntelliJ Plugin classloader
            try {
                val driver = org.sqlite.JDBC()
                DriverManager.registerDriver(driver)
                logger.info { "SQLite JDBC driver registered successfully" }
            } catch (e: Exception) {
                logger.error { "Failed to register SQLite driver: ${e.message}" }
                throw e
            }

            // Set WAL journal mode via direct JDBC (persistent, only needs to be set once per DB file)
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    logger.info { "Database WAL mode enabled (persistent)" }
                }
            }

            // Connect via Exposed with per-connection PRAGMA setup.
            // synchronous, busy_timeout, foreign_keys are per-connection settings —
            // they MUST be set on every new connection, not just once.
            val database = Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    connection.createStatement().use { stmt ->
                        stmt.execute("PRAGMA synchronous=NORMAL")
                        stmt.execute("PRAGMA busy_timeout=10000")
                        stmt.execute("PRAGMA foreign_keys=ON")
                    }
                }
            )

            // Configure transaction retry behavior
            org.jetbrains.exposed.sql.transactions.TransactionManager.manager.defaultRepetitionAttempts = 5
            org.jetbrains.exposed.sql.transactions.TransactionManager.manager.defaultMinRepetitionDelay = 100
            org.jetbrains.exposed.sql.transactions.TransactionManager.manager.defaultMaxRepetitionDelay = 1000

            // Migrations run BEFORE createMissingTablesAndColumns so they can rebuild
            // tables whose schema drifted from the current Exposed definitions
            // (e.g. V4 rewires snapshots/subtasks to use snapshot_groups).
            // Each migration is idempotent and guards against missing tables for fresh DBs.
            MigrationRunner.run(database)

            transaction(database) {
                // Create tables in dependency order.
                // Snapshot groups have no FK back to subtasks (subtaskId is a plain column),
                // so there's no cycle: Tasks -> SnapshotGroups -> Subtasks -> Snapshots.
                SchemaUtils.createMissingTablesAndColumns(
                    TasksTable,
                    SnapshotGroupsTable,
                    SubtasksTable,
                    SnapshotsTable,
                    ChatMessagesTable,
                    ApiLogsTable,
                    MCPServersTable,
                    ConfigTable,
                    PromptsTable,
                    IndexFilesTable,
                    IndexChunksTable,
                    EmbeddingsTable,
                    IndexingProgressTable,
                    DocumentationSourcesTable,  // External documentation sources (US-024)
                    ProjectAnalysisReportsTable,
                    // Multi-agent tables
                    AgentSessionsTable,
                    AgentInstancesTable,
                    AgentEventsTable
                )

                logger.info { "All tables created successfully" }
            }

            // Set initialized AFTER everything is complete (tables + migrations)
            initialized = true

            logger.info { "Database initialization complete" }
        }
    }

    fun <T> dbQuery(block: () -> T): T = transaction {
        block()
    }

    /**
     * Coroutine-friendly variant for suspend callers (e.g. AgentEventBus.emit).
     * Runs the transaction on Dispatchers.IO so JDBC blocking never stalls the
     * caller's dispatcher (event emission happens on hot paths like StreamChunk).
     */
    suspend fun <T> suspendDbQuery(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) {
            block()
        }
}
