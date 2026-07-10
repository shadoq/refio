package pl.jclab.refio.core.subagents

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.core.tools.base.ToolRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentRouterTest {

    private lateinit var tempDir: Path
    private lateinit var configService: ConfigService
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var llmClient: LLMClient
    private lateinit var toolPermissionsService: ToolPermissionsService
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var router: SubagentRouter

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("subagent-test")
        configService = mockk(relaxed = true)
        toolRegistry = mockk(relaxed = true)
        llmClient = mockk(relaxed = true)
        toolPermissionsService = mockk(relaxed = true)
        chatMessageRepository = mockk(relaxed = true)

        // Mock the config service methods used during initialization
        every { configService.getBuiltinSubagentEnabledOverrides() } returns emptyMap()

        router = SubagentRouter(
            projectRoot = tempDir,
            toolRegistry = toolRegistry,
            configService = configService,
            llmClient = llmClient,
            toolPermissionsService = toolPermissionsService,
            chatMessageRepository = chatMessageRepository,
            contextService = null,
            runTurnCallback = null
        )
    }

    @Nested
    inner class ParseSubagentCommandTests {

        @Test
        fun `should parse valid subagent command`() {
            // When
            val result = router.parseSubagentCommand("!security-reviewer check this code")

            // Then
            assertNotNull(result)
            assertEquals("security-reviewer", result.first)
            assertEquals("check this code", result.second)
        }

        @Test
        fun `should parse command without prompt`() {
            // When
            val result = router.parseSubagentCommand("!code-analyzer")

            // Then
            assertNotNull(result)
            assertEquals("code-analyzer", result.first)
            assertEquals("", result.second)
        }

        @Test
        fun `should return null for non-subagent message`() {
            // When
            val result = router.parseSubagentCommand("hello world")

            // Then
            assertNull(result)
        }

        @Test
        fun `should return null for empty message`() {
            // When
            val result = router.parseSubagentCommand("")

            // Then
            assertNull(result)
        }

        @Test
        fun `should handle leading whitespace`() {
            // When
            val result = router.parseSubagentCommand("  !test-agent do something")

            // Then
            assertNotNull(result)
            assertEquals("test-agent", result.first)
        }

        @Test
        fun `should lowercase agent name`() {
            // When
            val result = router.parseSubagentCommand("!MyAgent check")

            // Then
            assertNotNull(result)
            assertEquals("myagent", result.first)
        }
    }

    @Nested
    inner class ParseSubagentInvocationTests {

        @Test
        fun `should return null when subagent does not exist`() {
            // Given - no subagents registered

            // When
            val result = router.parseSubagentInvocation("!nonexistent do something")

            // Then
            assertNull(result)
        }

        @Test
        fun `should return null for regular messages`() {
            // When
            val result = router.parseSubagentInvocation("just a regular message")

            // Then
            assertNull(result)
        }
    }

    @Nested
    inner class InvokeTests {

        @Test
        fun `should throw SubagentNotFoundException for unknown subagent`() {
            // When/Then
            assertThrows<SubagentNotFoundException> {
                runBlocking {
                    router.invoke(
                        taskId = "task-1",
                        name = "nonexistent-agent",
                        prompt = "do something"
                    )
                }
            }
        }
    }

    @Nested
    inner class ListSubagentsTests {

        @Test
        fun `should list builtin subagents`() {
            // When
            val subagents = router.listSubagents()

            // Then - should return list (may include builtins)
            assertNotNull(subagents)
        }

        @Test
        fun `should return empty list for nonexistent keywords`() {
            // When
            val results = router.findByKeywords(listOf("xyz-nonexistent-keyword-1234"))

            // Then
            assertTrue(results.isEmpty())
        }

        @Test
        fun `should return empty list when no keywords provided`() {
            // When
            val results = router.findByKeywords(emptyList())

            // Then
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class CrudOperationsTests {

        @Test
        fun `create rejects names that escape the registry directory`() {
            assertThrows<IllegalArgumentException> {
                router.createSubagent(
                    name = "../../outside",
                    description = "Test",
                    systemPrompt = "Test",
                    scope = SubagentScope.PROJECT
                )
            }
            assertTrue(Files.notExists(tempDir.resolve("outside.md")))
        }

        @Test
        fun `should create and find subagent`() {
            // When
            val created = router.createSubagent(
                name = "test-agent",
                description = "A test subagent",
                systemPrompt = "You are a test agent.",
                scope = SubagentScope.PROJECT
            )

            // Then
            assertNotNull(created)
            assertEquals("test-agent", created.name)
            assertEquals("A test subagent", created.description)

            // Verify it can be found
            val found = router.getSubagent("test-agent")
            assertNotNull(found)
            assertEquals("test-agent", found.name)
        }

        @Test
        fun `should check existence`() {
            // Given
            router.createSubagent(
                name = "exists-test",
                description = "Test",
                systemPrompt = "Test prompt",
                scope = SubagentScope.PROJECT
            )

            // Then
            assertTrue(router.exists("exists-test"))
        }

        @Test
        fun `should delete subagent`() {
            // Given
            router.createSubagent(
                name = "delete-me",
                description = "To be deleted",
                systemPrompt = "Test",
                scope = SubagentScope.PROJECT
            )

            // When
            val deleted = router.deleteSubagent("delete-me")

            // Then
            assertTrue(deleted)
            assertNull(router.getSubagent("delete-me"))
        }

        @Test
        fun `should update subagent description`() {
            // Given
            router.createSubagent(
                name = "update-test",
                description = "Original",
                systemPrompt = "Test",
                scope = SubagentScope.PROJECT
            )

            // When
            val updated = router.updateSubagent("update-test", description = "Updated description")

            // Then
            assertEquals("Updated description", updated.description)
        }

        @Test
        fun `update should throw for nonexistent subagent`() {
            assertThrows<SubagentNotFoundException> {
                router.updateSubagent("nonexistent", description = "test")
            }
        }
    }
}
