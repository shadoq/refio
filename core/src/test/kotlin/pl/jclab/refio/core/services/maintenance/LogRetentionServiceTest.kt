package pl.jclab.refio.core.services.maintenance

import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.AgentEventsTable
import pl.jclab.refio.core.db.ApiLogsTable
import pl.jclab.refio.core.db.repositories.AgentEventSqlRepository
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every model call stores its full request and answer (tens of kilobytes), so hours of agent work
 * grow the database by tens of megabytes. Logs older than `logs.retention_days` go; the log file a
 * running CLI writes to never does.
 */
class LogRetentionServiceTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var logDir: Path
    private var retentionDays = 7
    private val now = 1_800_000_000_000L
    private val dayMs = 24 * 60 * 60 * 1000L

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        logDir = Files.createTempDirectory("refio-logs")
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
        logDir.toFile().deleteRecursively()
    }

    private fun service() = LogRetentionService(
        apiLogRepository = ApiLogRepository(),
        agentEventRepository = AgentEventSqlRepository(db.database),
        logDir = logDir,
        retentionDays = { retentionDays },
        clock = { now },
    )

    private fun ago(days: Double) = now - (days * dayMs).toLong()

    private fun apiLogs(count: Int, ageDays: Double) {
        transaction {
            ApiLogsTable.batchInsert(1..count) {
                this[ApiLogsTable.provider] = "ollama"
                this[ApiLogsTable.model] = "qwen3.5:9b"
                this[ApiLogsTable.endpoint] = "http://localhost:11434/api/chat"
                this[ApiLogsTable.requestPayload] = "{\"messages\":[]}"
                this[ApiLogsTable.createdAt] = ago(ageDays)
            }
        }
    }

    private fun apiLogCount() = transaction { ApiLogsTable.selectAll().count() }

    private fun agentEvent(ageDays: Double) {
        transaction {
            AgentEventsTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[sessionId] = "t1"
                it[sourceAgentId] = "main"
                it[eventType] = "AgentStarted"
                it[correlationId] = "c1"
                it[payloadJson] = "{}"
                it[timestamp] = ago(ageDays)
            }
        }
    }

    private fun agentEventCount() = transaction { AgentEventsTable.selectAll().count() }

    private fun logFile(name: String, ageDays: Double): Path = logDir.resolve(name).also {
        Files.writeString(it, "12:00:00.000 [main] INFO  Refio - started")
        Files.setLastModifiedTime(it, FileTime.fromMillis(ago(ageDays)))
    }

    @Test
    fun `old api logs go, recent stay`() {
        apiLogs(1, ageDays = 30.0)
        apiLogs(1, ageDays = 8.0)
        apiLogs(1, ageDays = 6.9)
        apiLogs(1, ageDays = 0.0)

        val report = service().sweep()

        assertEquals(2, report.apiLogsDeleted)
        assertEquals(2, apiLogCount(), "the last 7 days stay for the Debug panel")
    }

    @Test
    fun `old rows beyond one batch all go`() {
        apiLogs(1_201, ageDays = 10.0)

        val report = service().sweep()

        assertEquals(1_201, report.apiLogsDeleted, "deleted 500 rows at a time until none is left")
        assertEquals(0, apiLogCount())
    }

    @Test
    fun `retention zero keeps everything`() {
        retentionDays = 0
        apiLogs(1, ageDays = 400.0)
        agentEvent(ageDays = 400.0)
        val rolled = logFile("refio-cli.2025-10-01.0.log", ageDays = 400.0)

        val report = service().sweep()

        assertEquals(RetentionReport(0, 0, 0), report)
        assertEquals(1, apiLogCount())
        assertEquals(1, agentEventCount())
        assertTrue(Files.exists(rolled))
    }

    @Test
    fun `old rolled log files go, active log stays`() {
        val active = logFile("refio-cli.log", ageDays = 30.0)
        val oldRolled = logFile("refio-cli.2026-12-01.0.log", ageDays = 30.0)
        val recentRolled = logFile("refio-cli.2027-01-14.0.log", ageDays = 1.0)
        val other = logFile("notes.txt", ageDays = 30.0)

        val report = service().sweep()

        assertEquals(1, report.logFilesDeleted)
        assertFalse(Files.exists(oldRolled))
        assertTrue(Files.exists(active), "a CLI left idle for weeks still writes to this file")
        assertTrue(Files.exists(recentRolled))
        assertTrue(Files.exists(other), "not a Refio log")
    }

    @Test
    fun `old agent events go`() {
        agentEvent(ageDays = 10.0)
        agentEvent(ageDays = 1.0)

        val report = service().sweep()

        assertEquals(1, report.agentEventsDeleted)
        assertEquals(1, agentEventCount())
    }

    @Test
    fun `two sweeps in a row do not fail`() {
        apiLogs(3, ageDays = 9.0)
        agentEvent(ageDays = 9.0)
        logFile("refio-cli.2026-12-01.0.log", ageDays = 9.0)

        val first = service().sweep()
        val second = service().sweep()

        assertEquals(RetentionReport(3, 1, 1), first)
        assertEquals(RetentionReport(0, 0, 0), second, "the IDE and the CLI both sweep the same database")
    }
}
