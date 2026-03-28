package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*
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

            // Create tables
            transaction(database) {

                // Create tables in dependency order
                // Note: Subtasks <-> Snapshots have circular reference (both nullable)
                SchemaUtils.createMissingTablesAndColumns(
                    TasksTable,
                    SnapshotsTable,      // Created before Subtasks (circular ref handled via nullable FKs)
                    SubtasksTable,
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

            MigrationRunner.run(database)

            // Set initialized AFTER everything is complete (tables + migrations)
            initialized = true

            logger.info { "Database initialization complete" }
        }
    }

    fun <T> dbQuery(block: () -> T): T = transaction {
        block()
    }
}
