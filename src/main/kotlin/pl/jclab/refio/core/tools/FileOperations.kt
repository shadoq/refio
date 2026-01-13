package pl.jclab.refio.core.tools

import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

private val logger = dualLogger("FileOperations")

/**
 * PoC File Operations with Sandbox and Snapshot Support
 * Demonstrates safe file operations within project boundaries
 */
class FileOperations(private val sandbox: PathSandbox) {

    /**
     * Reads file content
     * @throws FileOperationException if file doesn't exist or is outside sandbox
     */
    fun readFile(relativePath: String): String {
        return try {
            val path = sandbox.resolve(relativePath)

            if (!path.exists()) {
                throw FileOperationException("File not found: $relativePath")
            }

            if (!path.isRegularFile()) {
                throw FileOperationException("Not a regular file: $relativePath")
            }

            val content = path.readText()
            logger.info { "Read file: $relativePath (${content.length} chars)" }
            content
        } catch (e: SecurityException) {
            throw FileOperationException("Access denied: $relativePath", e)
        } catch (e: Exception) {
            throw FileOperationException("Failed to read file: $relativePath", e)
        }
    }

    /**
     * Writes content to file with snapshot creation
     * @return FileSnapshot containing backup information
     * @throws FileOperationException if operation fails or path is outside sandbox
     */
    fun writeFile(relativePath: String, content: String): FileSnapshot {
        return try {
            val path = sandbox.resolve(relativePath)

            // Create snapshot if file exists
            val snapshot = if (path.exists()) {
                createSnapshot(path)
            } else {
                FileSnapshot(
                    originalPath = path,
                    contentHash = null,
                    existed = false,
                    size = 0L
                )
            }

            // Ensure parent directory exists
            path.parent?.createDirectories()

            // Write new content
            path.writeText(content)
            logger.info { "Wrote file: $relativePath (${content.length} chars)" }

            snapshot
        } catch (e: SecurityException) {
            throw FileOperationException("Access denied: $relativePath", e)
        } catch (e: Exception) {
            throw FileOperationException("Failed to write file: $relativePath", e)
        }
    }

    /**
     * Lists files in a directory
     * @throws FileOperationException if directory doesn't exist or is outside sandbox
     */
    fun listFiles(relativePath: String = "."): List<FileInfo> {
        return try {
            val path = sandbox.resolve(relativePath)

            if (!path.exists()) {
                throw FileOperationException("Directory not found: $relativePath")
            }

            if (!path.isDirectory()) {
                throw FileOperationException("Not a directory: $relativePath")
            }

            val files = path.listDirectoryEntries()
                .map { file ->
                    FileInfo(
                        name = file.name,
                        relativePath = file.relativeTo(sandbox.resolve(".")).toString(),
                        isDirectory = file.isDirectory(),
                        size = if (file.isRegularFile()) file.fileSize() else 0L,
                        lastModified = file.getLastModifiedTime().toMillis()
                    )
                }
                .sortedBy { it.name }

            logger.info { "Listed directory: $relativePath (${files.size} entries)" }
            files
        } catch (e: SecurityException) {
            throw FileOperationException("Access denied: $relativePath", e)
        } catch (e: Exception) {
            throw FileOperationException("Failed to list directory: $relativePath", e)
        }
    }

    /**
     * Deletes a file with snapshot creation
     * @return FileSnapshot containing backup information
     * @throws FileOperationException if operation fails or path is outside sandbox
     */
    fun deleteFile(relativePath: String): FileSnapshot {
        return try {
            val path = sandbox.resolve(relativePath)

            if (!path.exists()) {
                throw FileOperationException("File not found: $relativePath")
            }

            // Create snapshot before deletion
            val snapshot = createSnapshot(path)

            // Delete file
            path.deleteExisting()
            logger.info { "Deleted file: $relativePath" }

            snapshot
        } catch (e: SecurityException) {
            throw FileOperationException("Access denied: $relativePath", e)
        } catch (e: Exception) {
            throw FileOperationException("Failed to delete file: $relativePath", e)
        }
    }

    /**
     * Creates a snapshot of the current file state
     */
    private fun createSnapshot(path: Path): FileSnapshot {
        val content = path.readText()
        val hash = calculateHash(content)

        return FileSnapshot(
            originalPath = path,
            contentHash = hash,
            existed = true,
            size = path.fileSize(),
            backupContent = content
        )
    }

    /**
     * Calculates SHA-256 hash of content
     */
    private fun calculateHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * File snapshot for rollback capability
 */
data class FileSnapshot(
    val originalPath: Path,
    val contentHash: String?,
    val existed: Boolean,
    val size: Long,
    val backupContent: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * File information for listing operations
 */
data class FileInfo(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

/**
 * Normalizes path to work properly with PathSandbox.
 *
 * Security-focused normalization that ensures paths work correctly:
 * - Converts backslashes to forward slashes (Windows compatibility)
 * - Adds "./" prefix for bare filenames (e.g., "file.txt" → "./file.txt")
 * - Preserves existing relative paths (e.g., "src/Main.kt" stays unchanged)
 * - Does NOT resolve ".." or "." - PathSandbox handles security
 *
 * This is a defense-in-depth measure: PathSandbox.resolve() already prevents
 * escaping project root, but proper path format prevents confusion.
 *
 * @param path Raw path from LLM or user input
 * @return Normalized path safe for PathSandbox.resolve()
 */
fun normalizePath(path: String): String {
    if (path.isBlank()) {
        return "."
    }

    // Normalize backslashes to forward slashes (Windows compatibility)
    var normalized = path.replace('\\', '/')

    // Check if this is a bare filename (no directory separators)
    val isBareFilename = !normalized.contains('/') && normalized.isNotEmpty()

    // Add "./" prefix for bare filenames to make them explicit relative paths
    if (isBareFilename) {
        normalized = "./$normalized"
    }

    return normalized
}
