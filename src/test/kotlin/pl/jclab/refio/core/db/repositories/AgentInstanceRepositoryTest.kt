package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import pl.jclab.refio.core.db.AgentInstanceStatus
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentInstanceRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var sessionRepo: AgentSessionRepository
    private lateinit var instanceRepo: AgentInstanceRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        sessionRepo = AgentSessionRepository()
        instanceRepo = AgentInstanceRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun createTestSession(): String {
        return sessionRepo.create(projectId = "test", name = "Test Session").id
    }

    @Test
    fun `should create and find agent instance`() {
        transaction {
            val sessionId = createTestSession()
            val instance = instanceRepo.create(
                sessionId = sessionId,
                name = "Analyst",
                taskDescription = "Analyze code",
                profile = "business-analyst",
                model = "claude-sonnet"
            )

            assertNotNull(instance.id)
            assertEquals("Analyst", instance.name)
            assertEquals(AgentInstanceStatus.PENDING.name, instance.status)

            val found = instanceRepo.findById(instance.id)
            assertNotNull(found)
            assertEquals("Analyst", found.name)
            assertEquals("business-analyst", found.profile)
        }
    }

    @Test
    fun `should find instances by session`() {
        transaction {
            val sessionId = createTestSession()
            instanceRepo.create(sessionId = sessionId, name = "Agent1", taskDescription = "task1")
            instanceRepo.create(sessionId = sessionId, name = "Agent2", taskDescription = "task2")

            val instances = instanceRepo.findBySessionId(sessionId)
            assertEquals(2, instances.size)
        }
    }

    @Test
    fun `should update status`() {
        transaction {
            val sessionId = createTestSession()
            val instance = instanceRepo.create(sessionId = sessionId, name = "Agent", taskDescription = "task")
            val now = System.currentTimeMillis()

            instanceRepo.updateStatus(instance.id, AgentInstanceStatus.RUNNING, startedAt = now)

            val updated = instanceRepo.findById(instance.id)
            assertNotNull(updated)
            assertEquals(AgentInstanceStatus.RUNNING.name, updated.status)
            assertEquals(now, updated.startedAt)
        }
    }

    @Test
    fun `should update result with metrics`() {
        transaction {
            val sessionId = createTestSession()
            val instance = instanceRepo.create(sessionId = sessionId, name = "Agent", taskDescription = "task")

            instanceRepo.updateResult(instance.id, "Analysis complete", 1000, 500, 0.05)

            val updated = instanceRepo.findById(instance.id)
            assertNotNull(updated)
            assertEquals("Analysis complete", updated.result)
            assertEquals(1000, updated.tokensIn)
            assertEquals(500, updated.tokensOut)
            assertEquals(0.05, updated.costUsd, 0.001)
        }
    }

    @Test
    fun `should store depends_on as JSON`() {
        transaction {
            val sessionId = createTestSession()
            val instance = instanceRepo.create(
                sessionId = sessionId,
                name = "Coder",
                taskDescription = "code",
                dependsOn = """["analyst","architect"]"""
            )

            val found = instanceRepo.findById(instance.id)
            assertNotNull(found)
            assertEquals("""["analyst","architect"]""", found.dependsOn)
        }
    }

    @Test
    fun `should delete instance directly`() {
        transaction {
            val sessionId = createTestSession()
            val instance = instanceRepo.create(sessionId = sessionId, name = "Agent", taskDescription = "task")

            instanceRepo.delete(instance.id)

            val instances = instanceRepo.findBySessionId(sessionId)
            assertEquals(0, instances.size)
        }
    }
}
