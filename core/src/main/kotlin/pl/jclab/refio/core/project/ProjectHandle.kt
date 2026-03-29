package pl.jclab.refio.core.project

import java.nio.file.Path

/**
 * Platform-agnostic project abstraction.
 *
 * IntelliJ plugin uses IntelliJProjectHandle (wraps com.intellij.openapi.project.Project),
 * CLI/standalone uses StandaloneProjectHandle.
 *
 * This allows core/ to remain free of IntelliJ Platform dependencies.
 */
interface ProjectHandle {
    /** Deterministic hash of project path (ProjectIdGenerator) */
    val id: String

    /** Human-readable project name */
    val name: String

    /** Absolute path to project root directory */
    val rootPath: Path

    /**
     * Access the underlying platform-specific project object.
     * Returns the IntelliJ Project in IDE mode, null in standalone mode.
     * Callers must cast appropriately and handle null.
     */
    val platformProject: Any?
        get() = null
}
