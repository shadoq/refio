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
    private val normalizedRoot: Path

    init {
        require(projectRoot.isAbsolute) { "Project root must be an absolute path" }
        require(projectRoot.exists()) { "Project root must exist: $projectRoot" }
        require(projectRoot.isDirectory()) { "Project root must be a directory: $projectRoot" }
        // Use toRealPath() to resolve symlinks (e.g. macOS /var -> /private/var)
        // so that sandbox root matches paths resolved by validatePath's toRealPath() call.
        normalizedRoot = projectRoot.toRealPath()
        logger.info { "PathSandbox initialized: projectRoot=$projectRoot, normalizedRoot=$normalizedRoot" }
    }

    /**
     * Validates that the path is within the sandbox
     * @throws SecurityException if path is outside sandbox
     */
    fun validatePath(path: Path, followSymlinks: Boolean = true): Path {
        val normalizedPath = path.normalize().toAbsolutePath()
        val allowSymlinks = allowSymlinksProvider()

        // Resolve parent chain to real path for consistent comparison on platforms
        // where system paths contain symlinks (e.g. macOS /var -> /private/var)
        val comparablePath = resolveComparablePath(normalizedPath)

        if (!comparablePath.startsWith(normalizedRoot)) {
            throw SecurityException(
                "Path outside sandbox: $normalizedPath (sandbox: $normalizedRoot)"
            )
        }

        if (!allowSymlinks) {
            // Check symlinks on the original path (preserves symlink entries)
            rejectSymlinks(normalizedPath)
        }

        val realPath = resolveRealPathIfNeeded(comparablePath, followSymlinks)
        if (!realPath.startsWith(normalizedRoot)) {
            throw SecurityException(
                "Path outside sandbox (symlink): $normalizedPath (real: $realPath, sandbox: $normalizedRoot)"
            )
        }

        if (containsSymlinks(normalizedPath)) {
            logger.warn { "Symlink detected in path: $normalizedPath" }
        }

        logger.debug { "Path validated: $comparablePath" }
        return comparablePath
    }

    /**
     * Resolves a relative path within the sandbox
     */
    fun resolve(relativePath: String): Path {
        val resolved = projectRoot.resolve(relativePath)
        return validatePath(resolved)
    }

    /**
     * Re-validates that a previously validated path still resolves within the sandbox.
     * Use this immediately before file I/O (inside a file lock) to close the TOCTOU
     * window between initial validation and actual file operation.
     *
     * @throws SecurityException if the path's real location is now outside the sandbox
     */
    fun revalidateBeforeIO(path: Path) {
        if (!path.exists()) return // Non-existent paths can't be symlink-swapped
        val realPath = try {
            path.toRealPath()
        } catch (_: Exception) {
            return // Can't resolve — will fail on the actual I/O anyway
        }
        if (!realPath.startsWith(normalizedRoot)) {
            throw SecurityException(
                "Path escaped sandbox between validation and I/O (possible symlink swap): " +
                    "$path (real: $realPath, sandbox: $normalizedRoot)"
            )
        }
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

    private fun rejectSymlinks(originalPath: Path) {
        if (Files.exists(originalPath) && Files.isSymbolicLink(originalPath)) {
            throw SecurityException("Symbolic links are not allowed for security reasons: $originalPath")
        }

        var current = originalPath.parent
        val realRoot = normalizedRoot
        while (current != null) {
            val currentComparable = resolveComparablePath(current)
            if (!currentComparable.startsWith(realRoot)) break
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw SecurityException("Path contains symbolic link in parent directory: $current")
            }
            if (currentComparable == realRoot) break
            current = current.parent
        }
    }

    /**
     * Resolve a path for consistent comparison with normalizedRoot.
     * For existing paths, resolves the existing parent chain to real path
     * (handles platform symlinks like macOS /var -> /private/var).
     * For non-existing paths, resolves the existing ancestor to real path
     * and appends the remaining relative portion.
     */
    private fun resolveComparablePath(path: Path): Path {
        if (path.exists()) {
            return try {
                path.toRealPath()
            } catch (_: Exception) {
                path
            }
        }
        // For non-existent paths, resolve the nearest existing ancestor
        var existing = path.parent
        val remaining = mutableListOf(path.fileName)
        while (existing != null && !existing.exists()) {
            remaining.add(0, existing.fileName)
            existing = existing.parent
        }
        if (existing == null) return path
        return try {
            var result = existing.toRealPath()
            for (part in remaining) {
                result = result.resolve(part)
            }
            result
        } catch (_: Exception) {
            path
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

    /**
     * Returns the project root path (for logging and diagnostics).
     */
    fun getProjectRoot(): Path = projectRoot

    /**
     * Returns the normalized (real) root path used for sandbox comparisons.
     */
    fun getNormalizedRoot(): Path = normalizedRoot

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
