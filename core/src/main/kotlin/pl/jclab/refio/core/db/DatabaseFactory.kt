package pl.jclab.refio.core.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.migrations.MigrationRunner
import pl.jclab.refio.core.db.migrations.V8ConfigAndMcpUniqueness
import pl.jclab.refio.core.logging.dualLogger
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = dualLogger("DatabaseFactory")

object DatabaseFactory {
    @Volatile
    private var initialized = false
    private val initLock = Object()
    private val writeLock = ReentrantLock()

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

            // Set WAL journal mode via direct JDBC (persistent, only needs to be set once per DB file).
            // Set busy_timeout first so startup waits instead of failing with SQLITE_BUSY when
            // another process (IDE + CLI) holds a write lock on the shared database.
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA busy_timeout=10000")
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
                    DocumentationSourcesTable,  // External documentation sources
                    ProjectAnalysisReportsTable,
                    // Multi-agent tables
                    AgentSessionsTable,
                    AgentInstancesTable,
                    AgentEventsTable,
                    TaskStateTable
                )

                // Ensure partial unique indexes exist on fresh databases too (V8 runs before table creation).
                V8ConfigAndMcpUniqueness.ensureUniqueIndexes(connection.connection as java.sql.Connection)

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
     * Serializes in-process SQLite writes and retries only transient SQLITE_BUSY/SQLITE_LOCKED
     * failures (never deterministic constraint errors).
     */
    fun <T> dbWriteQuery(db: Database? = null, maxAttempts: Int = 5, block: () -> T): T {
        return writeLock.withLock {
            var delayMs = 50L
            var lastError: Throwable? = null
            for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
                try {
                    return@withLock transaction(db) {
                        repetitionAttempts = 1
                        block()
                    }
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt >= maxAttempts || !isSqliteBusyError(e)) {
                        throw e
                    }
                    logger.warn { "SQLite busy on write (attempt $attempt/$maxAttempts), retrying in ${delayMs}ms" }
                    Thread.sleep(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(1000L)
                }
            }
            throw lastError ?: IllegalStateException("dbWriteQuery failed without exception")
        }
    }

    internal fun isSqliteBusyError(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            val msg = cause.message.orEmpty()
            (cause is SQLException && (cause.errorCode == 5 || cause.errorCode == 6)) ||
                msg.contains("SQLITE_BUSY", ignoreCase = true) ||
                msg.contains("SQLITE_LOCKED", ignoreCase = true) ||
                msg.contains("database is locked", ignoreCase = true)
        }
    }

    internal fun isSqliteUniqueConstraintError(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            val msg = cause.message.orEmpty()
            msg.contains("SQLITE_CONSTRAINT_UNIQUE", ignoreCase = true) ||
                msg.contains("SQLITE_CONSTRAINT_PRIMARYKEY", ignoreCase = true) ||
                msg.contains("UNIQUE constraint failed", ignoreCase = true)
        }
    }

    /**
     * Coroutine-friendly variant for suspend callers (e.g. AgentEventBus.emit).
     * Runs the transaction on Dispatchers.IO so JDBC blocking never stalls the
     * caller's dispatcher (event emission happens on hot paths like StreamChunk).
     */
    suspend fun <T> suspendDbQuery(block: () -> T): T = suspendDbQuery(null, block)

    /**
     * Same as [suspendDbQuery] but pinned to [db].
     *
     * Without an explicit database Exposed resolves the *default* one at execution time, which is
     * wrong for work that is queued now and runs later on another dispatcher: by the time the
     * coroutine is scheduled, something else may have registered a different default. That is how a
     * queued agent event ended up inserting into a database with no `agent_events` table and was
     * silently lost. Callers that hand off work asynchronously should capture their database up
     * front and pass it here.
     */
    suspend fun <T> suspendDbQuery(db: Database?, block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db) {
            block()
        }
}
