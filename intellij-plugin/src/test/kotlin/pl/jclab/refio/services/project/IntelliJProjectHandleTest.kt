package pl.jclab.refio.services.project

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntelliJProjectHandleTest {

    @Test
    fun `should wrap IntelliJ Project`() {
        val project = mockk<Project> {
            every { basePath } returns "/tmp/test-project"
            every { name } returns "test-project"
        }

        val handle = IntelliJProjectHandle(project)

        assertEquals("test-project", handle.name)
        assertEquals(Path.of("/tmp/test-project").toString(), handle.rootPath.toString())
        assertNotNull(handle.id)
        assertTrue(handle.id.length == 64) // SHA-256 hex = 64 chars
    }

    @Test
    fun `platformProject should return IntelliJ Project`() {
        val project = mockk<Project> {
            every { basePath } returns "/tmp/project"
            every { name } returns "project"
        }

        val handle = IntelliJProjectHandle(project)

        assertTrue(handle.platformProject is Project)
        assertEquals(project, handle.platformProject)
        assertEquals(project, handle.intellijProject)
    }

    @Test
    fun `should generate deterministic id from path`() {
        val project = mockk<Project> {
            every { basePath } returns "/tmp/stable-path"
            every { name } returns "stable"
        }

        val handle1 = IntelliJProjectHandle(project)
        val handle2 = IntelliJProjectHandle(project)

        assertEquals(handle1.id, handle2.id)
    }

    @Test
    fun `should handle null basePath gracefully`() {
        val project = mockk<Project> {
            every { basePath } returns null
            every { name } returns "no-path"
        }

        val handle = IntelliJProjectHandle(project)

        assertEquals("no-path", handle.name)
        assertEquals(".", handle.rootPath.toString())
    }
}
