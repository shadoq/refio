package pl.jclab.refio.core.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import pl.jclab.refio.core.db.Config
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPermissionsServiceTest {

    private lateinit var configRepository: ConfigRepository
    private lateinit var service: ToolPermissionsService

    // Mock tools
    private val readFileTool = mockk<Tool> {
        every { name } returns "read_file"
        every { mode } returns ToolMode.READ_ONLY
    }

    private val codeEditingTool = mockk<Tool> {
        every { name } returns "code_editing"
        every { mode } returns ToolMode.WRITE
    }

    private val terminalTool = mockk<Tool> {
        every { name } returns "run_terminal_command"
        every { mode } returns ToolMode.WRITE
    }

    private val allTools = listOf(readFileTool, codeEditingTool, terminalTool)

    @BeforeEach
    fun setup() {
        configRepository = mockk()
        every { configRepository.getWithPrecedence(any(), any()) } returns null
        service = ToolPermissionsService(configRepository)
    }

    @Nested
    inner class GetPermissionsTests {

        @Test
        fun `should return default permissions when no config stored`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val permissions = service.getPermissions()

            // Then
            assertTrue(permissions.containsKey("read_file"))
            assertTrue(permissions.containsKey("code_editing"))
            assertEquals(PermissionLevel.ON, permissions["read_file"]?.planMode)
            assertEquals(PermissionLevel.ON, permissions["read_file"]?.agentMode)
        }

        @Test
        fun `should merge stored permissions with defaults`() {
            // Given
            val storedJson = """{"tools":{"custom_tool":{"planMode":"ON","agentMode":"OFF"}}}"""
            every { configRepository.getWithPrecedence(any(), any()) } returns Config(
                key = "tools_permissions",
                value = storedJson,
                scope = ConfigScope.APP,
                projectId = null,
                taskId = null,
                description = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // When
            val permissions = service.getPermissions()

            // Then
            // Should have both defaults and stored
            assertTrue(permissions.containsKey("read_file"))
            assertTrue(permissions.containsKey("custom_tool"))
            assertEquals(PermissionLevel.ON, permissions["custom_tool"]?.planMode)
            assertEquals(PermissionLevel.OFF, permissions["custom_tool"]?.agentMode)
        }
    }

    @Nested
    inner class GetPermissionTests {

        @Test
        fun `should return planMode for CHAT mode`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val permission = service.getPermission("read_file", TaskMode.CHAT)

            // Then
            assertEquals(PermissionLevel.ON, permission)
        }

        @Test
        fun `should return planMode for PLAN mode`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val permission = service.getPermission("read_file", TaskMode.PLAN)

            // Then
            assertEquals(PermissionLevel.ON, permission)
        }

        @Test
        fun `should return agentMode for AGENT mode`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val permission = service.getPermission("code_editing", TaskMode.AGENT)

            // Then
            assertEquals(PermissionLevel.ON, permission)
        }

        @Test
        fun `should return OFF for terminal in any mode by default`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When/Then
            assertEquals(PermissionLevel.OFF, service.getPermission("run_terminal_command", TaskMode.PLAN))
            assertEquals(PermissionLevel.OFF, service.getPermission("run_terminal_command", TaskMode.AGENT))
        }

        @Test
        fun `should return OFF for unknown tool`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val permission = service.getPermission("unknown_tool", TaskMode.AGENT)

            // Then
            assertEquals(PermissionLevel.OFF, permission)
        }
    }

    @Nested
    inner class FilterAvailableToolsTests {

        @Test
        fun `PLAN mode should only allow READ_ONLY tools`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val result = service.filterAvailableTools(allTools, TaskMode.PLAN)

            // Then
            assertEquals(1, result.size)
            assertEquals("read_file", result.first().name)
            assertTrue(result.all { it.mode == ToolMode.READ_ONLY })
        }

        @Test
        fun `CHAT mode should only allow READ_ONLY tools`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val result = service.filterAvailableTools(allTools, TaskMode.CHAT)

            // Then
            assertEquals(1, result.size)
            assertEquals("read_file", result.first().name)
        }

        @Test
        fun `AGENT mode should allow READ_ONLY and enabled WRITE tools`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val result = service.filterAvailableTools(allTools, TaskMode.AGENT)

            // Then
            // read_file (READ_ONLY) + code_editing (WRITE with ON permission)
            // terminal is OFF by default
            assertEquals(2, result.size)
            assertTrue(result.any { it.name == "read_file" })
            assertTrue(result.any { it.name == "code_editing" })
        }

        @Test
        fun `AGENT mode should block terminal by default`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            // When
            val result = service.filterAvailableTools(allTools, TaskMode.AGENT)

            // Then
            assertFalse(result.any { it.name == "run_terminal_command" })
        }
    }

    @Nested
    inner class CapabilityGatingInvariantsTests {

        @Test
        fun `INVARIANT - WRITE tools must NEVER be available in PLAN mode`() {
            // Given - This is the critical security invariant
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            val writeTools = listOf(
                mockk<Tool> { every { name } returns "create_new_file"; every { mode } returns ToolMode.WRITE },
                mockk<Tool> { every { name } returns "code_editing"; every { mode } returns ToolMode.WRITE },
                mockk<Tool> { every { name } returns "advance_code_editing"; every { mode } returns ToolMode.WRITE },
                mockk<Tool> { every { name } returns "multi_edit"; every { mode } returns ToolMode.WRITE },
                mockk<Tool> { every { name } returns "run_terminal_command"; every { mode } returns ToolMode.WRITE },
            )

            // When
            val result = service.filterAvailableTools(writeTools, TaskMode.PLAN)

            // Then - MUST be empty regardless of permission settings
            assertTrue(
                result.isEmpty(),
                "SECURITY VIOLATION: WRITE tools should NEVER be available in PLAN mode!"
            )
        }

        @Test
        fun `INVARIANT - WRITE tools must NEVER be available in CHAT mode`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            val writeTools = listOf(
                mockk<Tool> { every { name } returns "code_editing"; every { mode } returns ToolMode.WRITE },
                mockk<Tool> { every { name } returns "create_new_file"; every { mode } returns ToolMode.WRITE },
            )

            // When
            val result = service.filterAvailableTools(writeTools, TaskMode.CHAT)

            // Then
            assertTrue(
                result.isEmpty(),
                "SECURITY VIOLATION: WRITE tools should NEVER be available in CHAT mode!"
            )
        }

        @Test
        fun `INVARIANT - READ_ONLY tools must always be available in all modes`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null

            val readOnlyTools = listOf(
                mockk<Tool> { every { name } returns "read_file"; every { mode } returns ToolMode.READ_ONLY },
                mockk<Tool> { every { name } returns "read_directory"; every { mode } returns ToolMode.READ_ONLY },
                mockk<Tool> { every { name } returns "file_search"; every { mode } returns ToolMode.READ_ONLY },
                mockk<Tool> { every { name } returns "grep_search"; every { mode } returns ToolMode.READ_ONLY },
                mockk<Tool> { every { name } returns "view_diff"; every { mode } returns ToolMode.READ_ONLY },
            )

            // When/Then
            listOf(TaskMode.CHAT, TaskMode.PLAN, TaskMode.AGENT).forEach { mode ->
                val result = service.filterAvailableTools(readOnlyTools, mode)
                assertEquals(
                    readOnlyTools.size, result.size,
                    "All READ_ONLY tools should be available in $mode mode"
                )
            }
        }
    }

    @Nested
    inner class SetPermissionTests {

        private fun mockConfigResponse() = Config(
            key = "tools_permissions",
            value = "{}",
            scope = ConfigScope.APP,
            projectId = null,
            taskId = null,
            description = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        @Test
        fun `should save permission to config repository`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null
            every { configRepository.set(any(), any(), any(), any(), any(), any()) } returns mockConfigResponse()

            // When
            service.setPermission("test_tool", PermissionLevel.ON, PermissionLevel.OFF)

            // Then
            verify {
                configRepository.set(
                    key = any(),
                    value = match { it.contains("test_tool") },
                    scope = ConfigScope.APP,
                    projectId = any(),
                    taskId = null,
                    description = any()
                )
            }
        }

        @Test
        fun `should save with TASK scope when taskId provided`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any()) } returns null
            every { configRepository.set(any(), any(), any(), any(), any(), any()) } returns mockConfigResponse()
            val taskId = "task-123"

            // When
            service.setPermission("test_tool", PermissionLevel.ON, PermissionLevel.OFF, taskId)

            // Then
            verify {
                configRepository.set(
                    key = any(),
                    value = any(),
                    scope = ConfigScope.TASK,
                    projectId = any(),
                    taskId = taskId,
                    description = any()
                )
            }
        }
    }

    @Nested
    inner class ResetToDefaultsTests {

        private fun mockConfigResponse() = Config(
            key = "tools_permissions",
            value = "{}",
            scope = ConfigScope.APP,
            projectId = null,
            taskId = null,
            description = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        @Test
        fun `should reset permissions to defaults`() {
            // Given
            every { configRepository.set(any(), any(), any(), any(), any(), any()) } returns mockConfigResponse()

            // When
            service.resetToDefaults()

            // Then
            verify {
                configRepository.set(
                    key = any(),
                    value = match {
                        it.contains("read_file") &&
                        it.contains("code_editing") &&
                        it.contains("run_terminal_command")
                    },
                    scope = ConfigScope.APP,
                    projectId = any(),
                    taskId = null,
                    description = any()
                )
            }
        }
    }
}
