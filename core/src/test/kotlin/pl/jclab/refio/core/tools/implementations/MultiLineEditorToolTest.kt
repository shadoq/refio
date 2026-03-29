package pl.jclab.refio.core.tools.implementations

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.db.repositories.TaskRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Testy dla MultiLineEditorTool — narzędzia do edycji wieloliniowej z pomocą LLM.
 */
class MultiLineEditorToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var mockLLMClient: LLMClient
    private lateinit var mockConfigService: ConfigService
    private lateinit var mockPromptsService: PromptsService
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var tool: MultiLineEditorTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)

        mockLLMClient = mockk()
        mockConfigService = mockk {
            coEvery { getModel(any(), any()) } returns Pair("test-model", "test-provider")
            every { getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns 4096
            every { getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        }
        mockPromptsService = mockk {
            every { getSystemPrompt(any(), any()) } returns "System prompt with {{FILE_PATH}} and {{LANGUAGE}}"
        }
        mockTaskRepository = mockk(relaxed = true)

        tool = MultiLineEditorTool(
            sandbox = sandbox,
            limits = FileLimits.DEFAULT,
            llmClient = mockLLMClient,
            configService = mockConfigService,
            promptsService = mockPromptsService,
            taskRepository = mockTaskRepository
        )
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("multi_line_editor", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.WRITE, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.FILE_MODIFYING, tool.category)
        }

        @Test
        fun `should have non-empty description`() {
            assertTrue(tool.description.isNotEmpty())
        }
    }

    @Nested
    inner class ParameterValidationTests {

        @Test
        fun `should validate params with path and edit_description`() {
            // When & Then - should not throw
            tool.validateParams(mapOf(
                "path" to "test.kt",
                "edit_description" to "Add error handling"
            ))
        }

        @Test
        fun `should throw exception when path is missing`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("edit_description" to "edit"))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when path is empty`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "", "edit_description" to "edit"))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when edit_description is missing`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "test.kt"))
            }
            assertTrue(exception.message!!.contains("edit_description"))
        }

        @Test
        fun `should throw exception when edit_description is blank`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "test.kt", "edit_description" to "   "))
            }
            assertTrue(exception.message!!.contains("edit_description"))
        }
    }

    @Nested
    inner class SuccessfulEditTests {

        @Test
        fun `should apply edits when LLM returns valid JSON`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 2\nline 3")

            val llmResponse = """
                ```json
                {
                  "changes": [
                    {
                      "line_start": 2,
                      "line_end": 2,
                      "new_content": "modified line 2",
                      "description": "Update line 2"
                    }
                  ]
                }
                ```
            """.trimIndent()

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null,
                    taskId = null,
                    subtaskId = null,
                    source = "MultiLineEditor"
                )
            } returns LLMResponse(
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify line 2"
            ))

            // Then
            assertTrue(result.success)
            val content = Files.readString(tempDir.resolve("test.kt"))
            assertTrue(content.contains("modified line 2"))
        }

        @Test
        fun `should handle multiple edits in one call`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 2\nline 3\nline 4\nline 5")

            val llmResponse = """
                ```json
                {
                  "changes": [
                    {
                      "line_start": 2,
                      "line_end": 2,
                      "new_content": "modified 2",
                      "description": "First edit"
                    },
                    {
                      "line_start": 4,
                      "line_end": 4,
                      "new_content": "modified 4",
                      "description": "Second edit"
                    }
                  ]
                }
                ```
            """.trimIndent()

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify lines 2 and 4"
            ))

            // Then
            assertTrue(result.success)
            val content = Files.readString(tempDir.resolve("test.kt"))
            assertTrue(content.contains("modified 2"))
            assertTrue(content.contains("modified 4"))
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 2")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":1,"line_end":1,"new_content":"new line 1","description":"edit"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.002,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertNotNull(result.metadata)
            assertEquals("test.kt", result.metadata!!["path"])
            assertEquals("multi_line_edit", result.metadata!!["mode"])
            assertEquals(1, result.metadata!!["edits_count"])
            assertEquals(100, result.metadata!!["tokens_in"])
            assertEquals(50, result.metadata!!["tokens_out"])
        }

        @Test
        fun `should include diff in output`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "original line")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":1,"line_end":1,"new_content":"new line","description":"edit"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("```diff"))
            assertTrue(result.output!!.contains("---"))
            assertTrue(result.output!!.contains("+++"))
        }

        @Test
        fun `should update task metrics when taskId is provided`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "content")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":1,"line_end":1,"new_content":"new","description":"e"}]}""",
                usage = LLMUsage(inputTokens = 200, outputTokens = 100, totalTokens = 200 + 100),
                cost = 0.003,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit",
                "taskId" to "task-123"
            ))

            // Then
            coVerify { mockTaskRepository.incrementMetrics("task-123", 200, 100, 0.003) }
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when file not found`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "nonexistent.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when file is too large`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxFileSize = 100)
            val strictTool = MultiLineEditorTool(
                sandbox, strictLimits, mockLLMClient,
                mockConfigService, mockPromptsService, mockTaskRepository
            )
            val largeContent = "x".repeat(200)
            Files.writeString(tempDir.resolve("large.kt"), largeContent)

            // When
            val result = strictTool.execute(mapOf(
                "path" to "large.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("too large", ignoreCase = true))
        }

        @Test
        fun `should return error when LLM returns invalid JSON`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "content")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = "This is not valid JSON at all",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("parse", ignoreCase = true))
        }

        @Test
        fun `should return error when LLM returns no edits`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "content")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("did not identify", ignoreCase = true))
        }

        @Test
        fun `should return error when edits have invalid line numbers`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 2")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":5,"line_end":5,"new_content":"x","description":"bad line"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("exceeds file length", ignoreCase = true))
        }

        @Test
        fun `should return error when edits overlap`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 2\nline 3")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":1,"line_end":2,"new_content":"x","description":"e1"},{"line_start":2,"line_end":3,"new_content":"y","description":"e2"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("overlap", ignoreCase = true))
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle deletion edits (empty new_content)`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 2\nline 3")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":2,"line_end":2,"new_content":"","description":"delete"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Delete line 2"
            ))

            // Then
            assertTrue(result.success)
            val content = Files.readString(tempDir.resolve("test.kt"))
            assertEquals("line 1\nline 3", content)
        }

        @Test
        fun `should handle insertion edits when line_end less than line_start`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "line 1\nline 3")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":2,"line_end":1,"new_content":"line 2","description":"insert"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Insert line 2"
            ))

            // Then
            assertTrue(result.success)
        }

        @Test
        fun `should detect language from file extension`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.py"), "print('hello')")

            coEvery {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            } returns LLMResponse(
                content = """{"changes":[{"line_start":1,"line_end":1,"new_content":"# modified","description":"e"}]}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            tool.execute(mapOf(
                "path" to "test.py",
                "edit_description" to "Edit"
            ))

            // Then
            coVerify {
                mockLLMClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    temperature = any(),
                    maxTokens = any(),
                    stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null,
                    subtaskId = null,
                    source = any()
                )
            }
            // Verify promptsService was called with language
            verify { mockPromptsService.getSystemPrompt(any(), any()) }
        }
    }

    @Nested
    inner class ParameterSchemaTests {

        @Test
        fun `should return valid parameter schema`() {
            // When
            val schema = tool.getParameterSchema()

            // Then
            assertEquals("object", schema["type"])
            val properties = schema["properties"] as Map<*, *>
            assertNotNull(properties["path"])
            assertNotNull(properties["edit_description"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("path"))
            assertTrue(required.contains("edit_description"))
        }
    }
}
