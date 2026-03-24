package pl.jclab.refio.core.api

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.project.ProjectHandle
import pl.jclab.refio.core.project.StandaloneProjectHandle
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for CoreApiRouter's projectHandle parameter support.
 * We verify construction with different parameter combinations.
 */
class CoreApiRouterProjectHandleTest {

    @Test
    fun `should construct with projectHandle`() {
        val handle = StandaloneProjectHandle(Path.of("/tmp/test-project"))
        val router = CoreApiRouter(projectHandle = handle)

        assertNotNull(router.projectHandle)
        assertEquals(handle.id, router.projectHandle?.id)
    }

    @Test
    fun `should construct with legacy ideProject null`() {
        // Traditional construction — backward compat
        val router = CoreApiRouter(
            projectRoot = Path.of("/tmp/test"),
            ideProject = null
        )

        assertFalse(router.hasIdeProject())
    }

    @Test
    fun `should construct with no parameters`() {
        val router = CoreApiRouter()
        assertFalse(router.hasIdeProject())
    }

    @Test
    fun `projectHandle should be accessible`() {
        val handle = StandaloneProjectHandle(Path.of("/tmp/project"))
        val router = CoreApiRouter(
            projectRoot = Path.of("/tmp/project"),
            projectHandle = handle
        )

        assertEquals("/tmp/project", router.projectHandle?.rootPath?.toString())
        assertEquals(handle.name, router.projectHandle?.name)
    }
}
