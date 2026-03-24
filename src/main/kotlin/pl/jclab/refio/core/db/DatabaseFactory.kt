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

    fun isInitialized(): Boolean = initialized

    fun init(dbPath: String = "refio_poc.db") {
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

        // Configure SQLite PRAGMA settings using direct JDBC (must be OUTSIDE any transaction)
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                // Enable WAL mode for better concurrency
                statement.execute("PRAGMA journal_mode=WAL")
                // Set synchronous mode (can only be changed outside transaction)
                statement.execute("PRAGMA synchronous=NORMAL")
                // Set busy timeout to prevent SQLITE_BUSY errors (10 seconds)
                statement.execute("PRAGMA busy_timeout=10000")
                // Enable foreign key constraints
                statement.execute("PRAGMA foreign_keys=ON")
                logger.info { "Database configured: WAL mode, synchronous=NORMAL, busy_timeout=10s, foreign keys enabled" }
            }
        }

        // Now connect via Exposed
        val database = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC"
        )
        initialized = true

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

        logger.info { "Database initialization complete" }
    }

    fun <T> dbQuery(block: () -> T): T = transaction {
        block()
    }
}
