package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentSessionRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: AgentSessionRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = AgentSessionRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Test
    fun `should create and find session`() {
        transaction {
            val session = repository.create(
                projectId = "test-project",
                name = "Test Multi-Agent Session"
            )

            assertNotNull(session.id)
            assertEquals("test-project", session.projectId)
            assertEquals("Test Multi-Agent Session", session.name)
            assertEquals("NEW", session.status)

            val found = repository.findById(session.id)
            assertNotNull(found)
            assertEquals(session.id, found.id)
        }
    }

    @Test
    fun `should find sessions by project`() {
        transaction {
            repository.create(projectId = "proj-1", name = "Session 1")
            repository.create(projectId = "proj-1", name = "Session 2")
            repository.create(projectId = "proj-2", name = "Session 3")

            val sessions = repository.findByProjectId("proj-1")
            assertEquals(2, sessions.size)
        }
    }

    @Test
    fun `should update status`() {
        transaction {
            val session = repository.create(projectId = "test", name = "Test")
            val now = System.currentTimeMillis()

            repository.updateStatus(session.id, "COMPLETED", completedAt = now)

            val updated = repository.findById(session.id)
            assertNotNull(updated)
            assertEquals("COMPLETED", updated.status)
            assertEquals(now, updated.completedAt)
        }
    }

    @Test
    fun `should delete session`() {
        transaction {
            val session = repository.create(projectId = "test", name = "Test")
            repository.delete(session.id)
            assertNull(repository.findById(session.id))
        }
    }

    @Test
    fun `should store definition yaml`() {
        transaction {
            val yaml = "agents:\n  - name: analyst\n    task: analyze"
            val session = repository.create(
                projectId = "test",
                name = "YAML Session",
                definitionYaml = yaml
            )

            val found = repository.findById(session.id)
            assertNotNull(found)
            assertEquals(yaml, found.definitionYaml)
        }
    }
}
