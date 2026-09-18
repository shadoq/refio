package pl.jclab.refio.core.security

import java.nio.file.Path

/**
 * What a spawned process is allowed to do.
 *
 * [PathSandbox] constrains the paths Refio's own file tools touch. It cannot constrain a child
 * process: once arbitrary code runs, it reads and writes wherever the user can. This type is where
 * that limit is stated rather than assumed, so a real backend can be slotted in without the callers
 * changing.
 *
 * Nothing below the kernel boundary stops a determined attacker who already has code execution.
 * Everything here limits the blast radius of a model that is wrong, or of content that talked one
 * into running something. Calling it a security boundary would be worse than not having it.
 */
data class ExecutionPolicy(
    val intent: ExecutionIntent,
    /** Directory the process starts in. */
    val workingDirectory: Path,
    /** Roots the process should be able to write to. Advisory until a backend can enforce it. */
    val writableRoots: List<Path>,
    /** Whether the process should reach the network. Advisory until a backend can enforce it. */
    val networkAllowed: Boolean,
)

/**
 * Why the process is being started. The two kinds carry very different risk and must not be handled
 * the same way, or isolation breaks the product.
 */
enum class ExecutionIntent {
    /**
     * The user's own toolchain: their build, their tests, their configured MCP servers. Runs on the
     * host under the approval gate, because the point is to exercise the real project with the real
     * JDK, Gradle and dependencies. A container image would never have the same ones, so a build
     * moved into one would either stop working or start lying.
     */
    PROJECT_TOOLCHAIN,

    /**
     * A script the model wrote itself (`run_code`). Nobody asked for this particular code to exist,
     * which is exactly where the risk sits, so this is what an isolation backend is for.
     */
    MODEL_AUTHORED,
}

/** How much the active backend can actually enforce. */
enum class IsolationLevel {
    /** The process runs with the user's full rights. Today's behaviour, and the only one so far. */
    NONE,
}

/** Raised when [ExecutionIsolationMode.REQUIRED] is set and no backend can honour the policy. */
class ExecutionIsolationUnavailable(message: String) : Exception(message)

/**
 * Starts processes under an [ExecutionPolicy].
 *
 * The seam exists before any backend does, deliberately: it gives the three places that spawn
 * processes (`run_code`, `run_terminal_command`, the MCP stdio transport) one contract to hold, so
 * adding Landlock, `sandbox-exec` or a container later is a change in one implementation instead of
 * a change spread across the callers.
 */
interface ExecutionSandbox {

    /** What this backend can enforce. */
    val isolationLevel: IsolationLevel

    /**
     * Build a process for [command] under [policy].
     *
     * @throws ExecutionIsolationUnavailable when the configured mode demands isolation this
     *         backend cannot provide. Refusing is the point of that mode: without a way to fail
     *         closed, the setting is a convenience and not a control.
     */
    fun newProcessBuilder(command: List<String>, policy: ExecutionPolicy): ProcessBuilder
}
