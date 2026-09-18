package pl.jclab.refio.core.security

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The seam's whole behavioural surface today: which processes a mode refuses to start.
 *
 * `required` is the only mode in which this setting is a control rather than a comfort, so the
 * assertions are about what it closes and, just as importantly, what it deliberately leaves open.
 */
class HostExecutionSandboxTest {

    private val projectRoot = Paths.get(".").toAbsolutePath()

    private fun policy(intent: ExecutionIntent) = ExecutionPolicy(
        intent = intent,
        workingDirectory = projectRoot,
        writableRoots = listOf(projectRoot),
        networkAllowed = false,
    )

    private fun sandbox(mode: ExecutionIsolationMode) = HostExecutionSandbox { mode }

    @Test
    fun `required refuses code the model wrote itself, because nothing can contain it`() {
        val error = assertFailsWith<ExecutionIsolationUnavailable> {
            sandbox(ExecutionIsolationMode.REQUIRED)
                .newProcessBuilder(listOf("echo", "hi"), policy(ExecutionIntent.MODEL_AUTHORED))
        }

        assertTrue(error.message!!.contains("execution_isolation=required"))
        assertTrue(
            error.message!!.contains("auto"),
            "the refusal has to say how to proceed anyway, or it is a dead end",
        )
    }

    @Test
    fun `required still runs the user's own toolchain`() {
        // Deliberate: the point of a build is to exercise the real project with the real JDK and
        // the real dependencies. Refusing it would not make anyone safer, it would make the agent
        // useless on the one thing the user consciously asked for.
        val builder = sandbox(ExecutionIsolationMode.REQUIRED)
            .newProcessBuilder(listOf("echo", "hi"), policy(ExecutionIntent.PROJECT_TOOLCHAIN))

        assertEquals(listOf("echo", "hi"), builder.command())
    }

    @Test
    fun `auto and off both run model-authored code, which is today's behaviour`() {
        listOf(ExecutionIsolationMode.AUTO, ExecutionIsolationMode.OFF).forEach { mode ->
            val builder = sandbox(mode)
                .newProcessBuilder(listOf("echo", "hi"), policy(ExecutionIntent.MODEL_AUTHORED))

            assertEquals(listOf("echo", "hi"), builder.command(), "mode $mode must not change behaviour")
            assertEquals(projectRoot.toFile(), builder.directory(), "mode $mode must keep the working directory")
        }
    }

    @Test
    fun `the host backend never claims to enforce anything`() {
        // Reporting isolation it does not have is how a setting like this lulls someone into
        // trusting it.
        assertEquals(IsolationLevel.NONE, sandbox(ExecutionIsolationMode.AUTO).isolationLevel)
    }

    @Test
    fun `the mode is read per call, so a run-scope override takes effect`() {
        var mode = ExecutionIsolationMode.OFF
        val sandbox = HostExecutionSandbox { mode }

        sandbox.newProcessBuilder(listOf("echo"), policy(ExecutionIntent.MODEL_AUTHORED))
        mode = ExecutionIsolationMode.REQUIRED

        assertFailsWith<ExecutionIsolationUnavailable> {
            sandbox.newProcessBuilder(listOf("echo"), policy(ExecutionIntent.MODEL_AUTHORED))
        }
    }

    @Test
    fun `an unknown or missing setting falls back to auto, never to off`() {
        assertEquals(ExecutionIsolationMode.AUTO, ExecutionIsolationMode.parse(null))
        assertEquals(ExecutionIsolationMode.AUTO, ExecutionIsolationMode.parse("nonsense"))
        assertEquals(ExecutionIsolationMode.REQUIRED, ExecutionIsolationMode.parse("  Required "))
        assertEquals(ExecutionIsolationMode.OFF, ExecutionIsolationMode.parse("OFF"))
    }
}
