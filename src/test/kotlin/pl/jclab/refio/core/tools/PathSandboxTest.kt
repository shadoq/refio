package pl.jclab.refio.core.tools

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists

class PathSandboxTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
    }

    @Test
    fun `should reject symlink pointing outside sandbox`() {
        Assumptions.assumeTrue(isSymlinkSupported(), "Symlinks not supported")

        val outsideDir = Files.createTempDirectory("outside")
        val outsideFile = Files.createTempFile(outsideDir, "outside", ".txt")
        val symlinkInSandbox = tempDir.resolve("malicious-link")

        try {
            Files.createSymbolicLink(symlinkInSandbox, outsideFile)

            assertThrows(SecurityException::class.java) {
                sandbox.validatePath(symlinkInSandbox)
            }
        } finally {
            symlinkInSandbox.deleteIfExists()
            outsideFile.deleteIfExists()
            outsideDir.deleteIfExists()
        }
    }

    @Test
    fun `should allow symlink pointing inside sandbox`() {
        Assumptions.assumeTrue(isSymlinkSupported(), "Symlinks not supported")

        val insideFile = tempDir.resolve("safe.txt").createFile()
        val symlinkInSandbox = tempDir.resolve("safe-link")

        try {
            Files.createSymbolicLink(symlinkInSandbox, insideFile)

            val result = sandbox.validatePath(symlinkInSandbox)

            assertTrue(result.startsWith(tempDir))
        } finally {
            symlinkInSandbox.deleteIfExists()
            insideFile.deleteIfExists()
        }
    }

    @Test
    fun `should detect symlinks in path`() {
        Assumptions.assumeTrue(isSymlinkSupported(), "Symlinks not supported")

        val targetDir = tempDir.resolve("target-dir").createDirectories()
        val symlinkDir = tempDir.resolve("link-dir")

        try {
            Files.createSymbolicLink(symlinkDir, targetDir)
            val pathWithSymlink = symlinkDir.resolve("file.txt")

            assertTrue(sandbox.containsSymlinks(pathWithSymlink))
        } finally {
            symlinkDir.deleteIfExists()
            targetDir.deleteIfExists()
        }
    }

    @Test
    fun `should handle non-existent files gracefully`() {
        val nonExistentPath = tempDir.resolve("non-existent.txt")

        val result = sandbox.validatePath(nonExistentPath, followSymlinks = false)

        assertEquals(nonExistentPath.normalize().toAbsolutePath(), result)
    }

    @Test
    fun `should reject path traversal attempts`() {
        val traversalPath = tempDir.resolve("..").resolve("..").resolve("outside.txt")

        assertThrows(SecurityException::class.java) {
            sandbox.validatePath(traversalPath)
        }
    }

    @Test
    fun `should allow nested directories`() {
        val nestedDir = tempDir.resolve("a/b/c").createDirectories()
        val nestedFile = nestedDir.resolve("file.txt").createFile()

        val result = sandbox.validatePath(nestedFile)

        assertTrue(result.startsWith(tempDir))
    }

    private fun isSymlinkSupported(): Boolean {
        val target = tempDir.resolve("symlink-target.txt")
        val link = tempDir.resolve("symlink-link")

        return try {
            Files.writeString(target, "x")
            Files.createSymbolicLink(link, target)
            true
        } catch (e: Exception) {
            false
        } finally {
            link.deleteIfExists()
            target.deleteIfExists()
        }
    }
}
