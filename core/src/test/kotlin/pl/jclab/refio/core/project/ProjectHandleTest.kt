package pl.jclab.refio.core.project

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectHandleTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `StandaloneProjectHandle should derive id from path`() {
        val handle = StandaloneProjectHandle(tempDir)
        assertNotNull(handle.id)
        assertTrue(handle.id.isNotBlank())
        assertEquals(64, handle.id.length) // SHA-256 hex = 64 chars
    }

    @Test
    fun `StandaloneProjectHandle should use directory name as project name`() {
        val handle = StandaloneProjectHandle(tempDir)
        assertEquals(tempDir.fileName.toString(), handle.name)
    }

    @Test
    fun `StandaloneProjectHandle should return rootPath`() {
        val handle = StandaloneProjectHandle(tempDir)
        assertEquals(tempDir, handle.rootPath)
    }

    @Test
    fun `StandaloneProjectHandle should have null platformProject`() {
        val handle = StandaloneProjectHandle(tempDir)
        assertNull(handle.platformProject)
    }

    @Test
    fun `same path should produce same id`() {
        val handle1 = StandaloneProjectHandle(tempDir)
        val handle2 = StandaloneProjectHandle(tempDir)
        assertEquals(handle1.id, handle2.id)
    }

    @Test
    fun `different paths should produce different ids`() {
        val dir1 = tempDir.resolve("project1").also { it.toFile().mkdirs() }
        val dir2 = tempDir.resolve("project2").also { it.toFile().mkdirs() }
        val handle1 = StandaloneProjectHandle(dir1)
        val handle2 = StandaloneProjectHandle(dir2)
        assertTrue(handle1.id != handle2.id)
    }
}
