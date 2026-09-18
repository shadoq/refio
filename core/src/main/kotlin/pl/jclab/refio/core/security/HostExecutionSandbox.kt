package pl.jclab.refio.core.security

import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.atomic.AtomicBoolean

private val logger = dualLogger("HostExecutionSandbox")

/**
 * How much isolation the user is asking for.
 *
 * The three modes exist because the honest answer differs per person. Most want the agent to work
 * and accept the risk knowingly; someone who cares needs a setting that fails closed rather than
 * quietly doing nothing.
 */
enum class ExecutionIsolationMode {
    /** Use isolation where it is available; say so once per session where it is not. */
    AUTO,

    /** No isolation, no execution. The only mode in which this is a control rather than a comfort. */
    REQUIRED,

    /** Run on the host, no warnings. Today's behaviour. */
    OFF;

    companion object {
        fun parse(raw: String?): ExecutionIsolationMode = when (raw?.trim()?.lowercase()) {
            "required" -> REQUIRED
            "off" -> OFF
            else -> AUTO
        }
    }
}

/**
 * The backend that exists today: the process runs on the host with the user's own rights.
 *
 * It enforces nothing, and says so through [isolationLevel]. Its whole job is to keep behaviour
 * identical to what it was before the seam existed, so introducing the seam changes nothing on its
 * own, plus to honour [ExecutionIsolationMode.REQUIRED] by refusing to run what it cannot contain.
 */
class HostExecutionSandbox(
    private val mode: () -> ExecutionIsolationMode,
) : ExecutionSandbox {

    override val isolationLevel: IsolationLevel = IsolationLevel.NONE

    private val warned = AtomicBoolean(false)

    override fun newProcessBuilder(command: List<String>, policy: ExecutionPolicy): ProcessBuilder {
        when (val resolved = mode()) {
            ExecutionIsolationMode.OFF -> Unit

            ExecutionIsolationMode.REQUIRED ->
                // The user's own build and their own MCP servers keep running: they asked for those
                // to be exercised, and an image without their toolchain could not do it honestly.
                // A script the model invented has no such claim, so it is refused.
                if (policy.intent == ExecutionIntent.MODEL_AUTHORED) {
                    throw ExecutionIsolationUnavailable(
                        "general.execution_isolation=required, but no execution isolation backend is " +
                            "available on this platform, so model-authored code cannot be contained. " +
                            "Refusing to run it. Set general.execution_isolation=auto to run it on the " +
                            "host anyway, at your own risk."
                    )
                }

            ExecutionIsolationMode.AUTO ->
                // Once per session, not per call: a warning on every invocation is a warning nobody
                // reads.
                if (warned.compareAndSet(false, true)) {
                    logger.warn {
                        "[EXECUTION_ISOLATION] No isolation backend on this platform — spawned " +
                            "processes run with your full rights and can read and write outside the " +
                            "project. Set general.execution_isolation=required to refuse instead, or " +
                            "=off to silence this."
                    }
                }
        }

        return ProcessBuilder()
            .command(command)
            .directory(policy.workingDirectory.toFile())
    }
}
