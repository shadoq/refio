package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import pl.jclab.refio.core.services.ProcessManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A background process started by the agent must also be stoppable from the app - otherwise the
 * only way to kill a runaway dev server is to quit the IDE.
 */
class MonitorProcessToolTest {

    private val manager = ProcessManager()
    private val tool = MonitorProcessTool(manager)
    private val workDir = File(System.getProperty("java.io.tmpdir"))

    @AfterEach
    fun tearDown() {
        manager.close()
    }

    @Test
    @Timeout(30)
    @DisabledOnOs(OS.WINDOWS)
    fun `stop action kills the background process`() {
        val managed = manager.start("sleep 120", workDir)
        assertTrue(managed.process.isAlive)

        val result = runBlocking {
            tool.execute(mapOf("process_id" to managed.processId, "action" to "stop"))
        }

        assertTrue(result.success, "stopping a running process must succeed: ${result.error}")
        assertTrue(
            managed.process.waitFor(10, TimeUnit.SECONDS),
            "the process must be dead after the stop action"
        )
    }

    @Test
    @Timeout(30)
    @DisabledOnOs(OS.WINDOWS)
    fun `default action still reads output`() {
        val managed = manager.start("echo hello", workDir)
        managed.process.waitFor(10, TimeUnit.SECONDS)
        Thread.sleep(200)

        val result = runBlocking { tool.execute(mapOf("process_id" to managed.processId)) }

        assertTrue(result.success)
        assertTrue(result.output?.contains("hello") == true, "output was: ${result.output}")
    }

    @Test
    @Timeout(30)
    fun `an unknown action is rejected instead of silently reading`() {
        val result = runBlocking {
            tool.execute(mapOf("process_id" to "whatever", "action" to "restart"))
        }

        assertFalse(result.success)
        assertTrue(result.error?.contains("restart") == true, "error was: ${result.error}")
    }
}
