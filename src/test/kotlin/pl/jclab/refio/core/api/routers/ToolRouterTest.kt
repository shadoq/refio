package pl.jclab.refio.core.api.routers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.ToolPermissionConfig
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.models.api.SetToolPermissionRequest

class ToolRouterTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolPermissionsService: ToolPermissionsService
    private lateinit var toolRouter: ToolRouter

    @BeforeEach
    fun setup() {
        toolRegistry = mockk()
        toolPermissionsService = mockk()
        toolRouter = ToolRouter(toolRegistry, toolPermissionsService)
    }

    @Test
    fun `getTools returns all tools when registry is available`() = runBlocking {
        // When
        val registry = toolRouter.getToolRegistry()

        // Then
        assertNotNull(registry)
    }

    @Test
    fun `getToolRegistry throws when registry is null`() = runBlocking {
        // Given
        val router = ToolRouter(null, toolPermissionsService)

        // When
        assertThrows(IllegalStateException::class.java) {
            router.getToolRegistry()
        }
    }

    @Test
    fun `getToolPermissions returns permissions for all tools`() = runBlocking {
        // Given
        every { toolPermissionsService.getPermissions(null) } returns mapOf(
            "test_tool" to ToolPermissionConfig(
                planMode = PermissionLevel.ON,
                agentMode = PermissionLevel.OFF
            )
        )

        // When
        val response = toolRouter.getToolPermissions()

        // Then
        assertEquals(1, response.tools.size)
        assertEquals("test_tool", response.tools[0].toolName)
        assertEquals("ON", response.tools[0].planMode)
        assertEquals("OFF", response.tools[0].agentMode)
    }

    @Test
    fun `setToolPermission updates permissions for tool`() = runBlocking {
        // Given
        val toolName = "test_tool"
        val planMode = PermissionLevel.ON
        val agentMode = PermissionLevel.OFF
        every { toolPermissionsService.setPermission(toolName, planMode, agentMode, null) } just Runs
        val request = SetToolPermissionRequest(
            planMode = planMode.name,
            agentMode = agentMode.name
        )

        // When
        toolRouter.setToolPermission(toolName, request)

        // Then
        verify { toolPermissionsService.setPermission(toolName, planMode, agentMode, null) }
    }
}
