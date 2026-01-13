package pl.jclab.refio.testutil

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Helper for setting up test databases.
 * Provides isolated database instances for each test.
 */
object TestDatabase {

    /**
     * Create a temporary file-based database for testing.
     * Returns the database connection and file path.
     *
     * @param tempDir JUnit TempDir for automatic cleanup
     * @return Pair of Database and dbPath
     */
    fun createTemporary(tempDir: Path): Pair<Database, String> {
        val dbPath = tempDir.resolve("test-${System.nanoTime()}.db").toString()
        val database = initializeDatabase(dbPath)
        return database to dbPath
    }

    /**
     * Create an in-memory SQLite database for fast tests.
     * Note: In-memory databases are isolated per connection.
     */
    fun createInMemory(): Database {
        val jdbcUrl = "jdbc:sqlite::memory:"

        // Register driver
        try {
            val driver = org.sqlite.JDBC()
            DriverManager.registerDriver(driver)
        } catch (_: Exception) {
            // Driver may already be registered
        }

        val database = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC"
        )

        // Create all tables
        transaction(database) {
            createTables()
        }

        return database
    }

    /**
     * Initialize a file-based database with all tables.
     */
    private fun initializeDatabase(dbPath: String): Database {
        val jdbcUrl = "jdbc:sqlite:$dbPath"

        // Register driver
        try {
            val driver = org.sqlite.JDBC()
            DriverManager.registerDriver(driver)
        } catch (_: Exception) {
            // Driver may already be registered
        }

        // Configure PRAGMA settings
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA busy_timeout=10000")
                statement.execute("PRAGMA foreign_keys=ON")
            }
        }

        val database = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC"
        )

        // Create all tables
        transaction(database) {
            createTables()
        }

        return database
    }

    /**
     * Create all required tables for testing.
     */
    private fun createTables() {
        SchemaUtils.createMissingTablesAndColumns(
            TasksTable,
            SnapshotsTable,
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
            DocumentationSourcesTable,
            ProjectAnalysisReportsTable,
            PlansTable,
            PlanStepsTable
        )
    }

    /**
     * Clean up a file-based database.
     */
    fun cleanup(dbPath: String) {
        if (dbPath != ":memory:") {
            try {
                Files.deleteIfExists(Path.of(dbPath))
                // Also delete WAL and SHM files
                Files.deleteIfExists(Path.of("$dbPath-wal"))
                Files.deleteIfExists(Path.of("$dbPath-shm"))
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Clear all data from all tables (useful for test isolation).
     */
    fun clearAllData(database: Database) {
        transaction(database) {
            // Delete in reverse dependency order
            EmbeddingsTable.deleteAll()
            IndexChunksTable.deleteAll()
            IndexFilesTable.deleteAll()
            IndexingProgressTable.deleteAll()
            DocumentationSourcesTable.deleteAll()
            ProjectAnalysisReportsTable.deleteAll()
            PromptsTable.deleteAll()
            ConfigTable.deleteAll()
            MCPServersTable.deleteAll()
            ApiLogsTable.deleteAll()
            ChatMessagesTable.deleteAll()
            SubtasksTable.deleteAll()
            PlanStepsTable.deleteAll()
            PlansTable.deleteAll()
            SnapshotsTable.deleteAll()
            TasksTable.deleteAll()
        }
    }
}
