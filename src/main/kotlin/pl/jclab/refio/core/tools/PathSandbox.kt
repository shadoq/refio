package pl.jclab.refio.core.tools

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

private val logger = dualLogger("PathSandbox")

/**
 * Security sandbox for file operations
 * Restricts all file operations to project working directory
 */
class PathSandbox(
    private val projectRoot: Path,
    private val allowSymlinksProvider: () -> Boolean = { false }
) {
    private val normalizedRoot: Path = projectRoot.normalize().toAbsolutePath()

    init {
        require(projectRoot.isAbsolute) { "Project root must be an absolute path" }
        require(projectRoot.exists()) { "Project root must exist: $projectRoot" }
        require(projectRoot.isDirectory()) { "Project root must be a directory: $projectRoot" }
        logger.info { "PathSandbox initialized: projectRoot=$projectRoot" }
    }

    /**
     * Validates that the path is within the sandbox
     * @throws SecurityException if path is outside sandbox
     */
    fun validatePath(path: Path, followSymlinks: Boolean = true): Path {
        val normalizedPath = path.normalize().toAbsolutePath()
        val allowSymlinks = allowSymlinksProvider()

        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw SecurityException(
                "Path outside sandbox: $normalizedPath (sandbox: $normalizedRoot)"
            )
        }

        if (!allowSymlinks) {
            rejectSymlinks(normalizedPath)
        }

        val realPath = resolveRealPathIfNeeded(normalizedPath, followSymlinks)
        if (!realPath.startsWith(normalizedRoot)) {
            throw SecurityException(
                "Path outside sandbox (symlink): $normalizedPath (real: $realPath, sandbox: $normalizedRoot)"
            )
        }

        if (containsSymlinks(normalizedPath)) {
            logger.warn { "Symlink detected in path: $normalizedPath" }
        }

        logger.debug { "Path validated: $normalizedPath" }
        return normalizedPath
    }

    /**
     * Resolves a relative path within the sandbox
     */
    fun resolve(relativePath: String): Path {
        val resolved = projectRoot.resolve(relativePath)
        return validatePath(resolved)
    }

    /**
     * Checks if a path is within the sandbox (doesn't throw)
     */
    fun isPathAllowed(path: Path): Boolean {
        return try {
            validatePath(path)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Check if path contains any symlinks.
     */
    fun containsSymlinks(path: Path): Boolean {
        var current: Path? = path.normalize().toAbsolutePath()
        while (current != null && current != current.root) {
            if (current.isSymbolicLink()) {
                return true
            }
            current = current.parent
        }
        return false
    }

    /**
     * Validate path and log symlink warning when detected.
     */
    fun validatePathWithWarning(path: Path, followSymlinks: Boolean = true): Path {
        return validatePath(path, followSymlinks)
    }

    private fun rejectSymlinks(path: Path) {
        if (Files.exists(path) && Files.isSymbolicLink(path)) {
            throw SecurityException("Symbolic links are not allowed for security reasons: $path")
        }

        var current = path.parent
        while (current != null && current.startsWith(normalizedRoot)) {
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw SecurityException("Path contains symbolic link in parent directory: $current")
            }
            if (current == normalizedRoot) {
                break
            }
            current = current.parent
        }
    }

    private fun resolveRealPathIfNeeded(path: Path, followSymlinks: Boolean): Path {
        if (!followSymlinks || !path.exists()) {
            return path
        }

        return try {
            path.toRealPath()
        } catch (e: Exception) {
            logger.warn { "Failed to resolve real path for $path: ${e.message}" }
            path
        }
    }

    companion object {
        fun withConfig(projectRoot: Path, configService: ConfigService): PathSandbox {
            return PathSandbox(projectRoot) {
                configService.getTyped(ConfigKeys.SECURITY_ALLOW_SYMLINKS)
            }
        }

        /**
         * Creates a sandbox for the current working directory
         */
        fun fromCurrentDirectory(): PathSandbox {
            val cwd = Paths.get("").toAbsolutePath()
            return PathSandbox(cwd)
        }
    }
}

/**
 * Exception thrown when file operations fail
 */
class FileOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)
