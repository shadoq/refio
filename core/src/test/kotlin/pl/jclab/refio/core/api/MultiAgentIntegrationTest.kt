package pl.jclab.refio.core.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import pl.jclab.refio.core.project.StandaloneProjectHandle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for multi-agent session API with real DB.
 * Tests session creation, instance persistence, and error handling.
 * LLM execution is expected to fail (no provider configured) — we verify
 * the session/instance lifecycle up to that point.
 *
 * Uses PER_CLASS lifecycle because DatabaseFactory is a global singleton
 * and re-initializing it per test causes issues.
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiAgentIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var router: CoreApiRouter

    @BeforeAll
    fun setup() {
        tempDir = Files.createTempDirectory("refio-multi-agent-test-")
        val handle = StandaloneProjectHandle(tempDir)
        router = CoreApiRouter(
            projectRoot = tempDir,
            projectHandle = handle
        )
        val dbPath = tempDir.resolve("test.db").toString()
        router.initialize(dbPath)
    }

    @AfterAll
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    @Order(1)
    fun `should expose agentEventBus for subscriptions`() {
        assertNotNull(router.agentEventBus)
        val flow = router.agentEventBus.events
        assertNotNull(flow)
    }

    @Test
    @Order(2)
    fun `listMultiAgentSessions should return empty for fresh project`() {
        val sessions = router.multiAgentRouter.listMultiAgentSessions()
        assertTrue(sessions.isEmpty())
    }

    @Test
    @Order(3)
    fun `getMultiAgentSession should return null for nonexistent session`() {
        val result = router.multiAgentRouter.getMultiAgentSession("nonexistent-session-id")
        assertEquals(null, result)
    }

    @Test
    @Order(4)
    fun `should reject cyclic dependencies in launch`() {
        val yaml = """
            name: "Cyclic Test"
            agents:
              - name: a
                task: "Task A"
                depends_on: [b]
              - name: b
                task: "Task B"
                depends_on: [a]
        """.trimIndent()

        assertThrows<IllegalArgumentException> {
            runBlocking {
                router.multiAgentRouter.launchMultiAgentSession(
                    MultiAgentSessionRequest(name = "cyclic", yamlDefinition = yaml)
                )
            }
        }
    }

    @Test
    @Order(5)
    fun `should reject unknown dependency references`() {
        val yaml = """
            name: "Bad Deps"
            agents:
              - name: a
                task: "Task A"
                depends_on: [nonexistent]
        """.trimIndent()

        assertThrows<IllegalArgumentException> {
            runBlocking {
                router.multiAgentRouter.launchMultiAgentSession(
                    MultiAgentSessionRequest(name = "bad-deps", yamlDefinition = yaml)
                )
            }
        }
    }

    @Test
    @Order(6)
    fun `should create session and fail gracefully when LLM not configured`() {
        val yaml = """
            name: "No LLM Test"
            agents:
              - name: solo
                task: "Do something"
                mode: chat
        """.trimIndent()

        // Launch should fail because no LLM provider/toolRegistry is configured,
        // but the session should still be created in DB with FAILED status
        try {
            runBlocking {
                router.multiAgentRouter.launchMultiAgentSession(
                    MultiAgentSessionRequest(name = "no-llm", yamlDefinition = yaml)
                )
            }
        } catch (_: Exception) {
            // Expected — LLM/toolRegistry not available
        }

        // Session should exist in DB (either RUNNING or FAILED)
        val sessions = router.multiAgentRouter.listMultiAgentSessions()
        assertTrue(sessions.isNotEmpty(), "Session should be persisted even on failure")
        val session = sessions.first()
        assertEquals("no-llm", session.name)
        assertTrue(session.status in listOf("RUNNING", "FAILED", "COMPLETED"))
    }
}
