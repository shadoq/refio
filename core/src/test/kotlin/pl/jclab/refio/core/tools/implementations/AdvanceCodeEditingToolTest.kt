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
 * Testy dla AdvanceCodeEditingTool — narzędzia do zaawansowanej edycji kodu z pomocą LLM.
 */
class AdvanceCodeEditingToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var mockLLMClient: LLMClient
    private lateinit var mockConfigService: ConfigService
    private lateinit var mockPromptsService: PromptsService
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var tool: AdvanceCodeEditingTool

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

        tool = AdvanceCodeEditingTool(
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
            assertEquals("advance_code_editing", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.WRITE, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.FILE_PRODUCING, tool.category)
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
    inner class LLMAssistedEditTests {

        @Test
        fun `should edit existing file with LLM assistance`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "original content")

            val llmResponse = """
                ```kotlin
                modified content
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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.002,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify the content"
            ))

            // Then
            assertTrue(result.success)
            assertEquals("modified content", Files.readString(tempDir.resolve("test.kt")))
            assertTrue(result.output!!.contains("edited successfully"))
        }

        @Test
        fun `should resolve the editor model via the EDITOR operation slot`() = runBlocking {
            // docs/0059 architect/editor split: full-file generation must resolve the EDITOR
            // sub-model slot, not CODING, so the editor can run a different model than the
            // turn/architect. EDITOR inherits CODING when default_model.editor is unset (proven
            // at the ModelSelectionService level), so users who never configure an editor model
            // see no behavioural change.
            Files.writeString(tempDir.resolve("test.kt"), "original content")

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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "```kotlin\nmodified content\n```",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                cost = 0.002,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify the content"
            ))

            // Then — the edit succeeds and the model was resolved through the EDITOR slot
            assertTrue(result.success)
            coVerify { mockConfigService.getModel(ModelOperation.EDITOR, any()) }
        }

        @Test
        fun `should re-prompt and recover when the first reply has no code block`() = runBlocking {
            // docs/0059 Faza 2: a weak editor model that replies with prose instead of a fenced
            // code block must NOT fail the edit outright. The tool re-prompts with a corrective
            // hint and writes the retry's clean code block — the most common weak-model failure.
            Files.writeString(tempDir.resolve("test.kt"), "original content")

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
                    source = "AdvCodeEditor"
                )
            } returnsMany listOf(
                LLMResponse(
                    content = "No code block this time, just talking.",
                    usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                    cost = 0.0,
                    model = "test-model",
                    provider = "test-provider"
                ),
                LLMResponse(
                    content = "```kotlin\nrepaired content\n```",
                    usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                    cost = 0.0,
                    model = "test-model",
                    provider = "test-provider"
                )
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify the content"
            ))

            // Then — recovered on the retry; the retry's content is what got written
            assertTrue(result.success, "expected recovery on retry, got error=${result.error}")
            assertEquals("repaired content", Files.readString(tempDir.resolve("test.kt")))
            coVerify(exactly = 2) {
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
                    source = "AdvCodeEditor"
                )
            }
        }

        @Test
        fun `should fail loud after exhausting extraction repair attempts`() = runBlocking {
            // docs/0059 Faza 2 + Rule 12: when the editor never returns a usable code block, the
            // tool fails with a clear diagnostic after the bounded retries — never a silent or
            // partial write. The original file stays untouched (the write happens only on success).
            Files.writeString(tempDir.resolve("test.kt"), "original content")

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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "No code block this time, just talking.",
                usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                cost = 0.0,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify the content"
            ))

            // Then — loud failure, original file untouched, bounded to the retry budget
            assertFalse(result.success)
            assertTrue(
                (result.error ?: "").contains("did not return a usable code block", ignoreCase = true),
                "expected a clear diagnostic, got error=${result.error}"
            )
            assertEquals("original content", Files.readString(tempDir.resolve("test.kt")))
            coVerify(exactly = 2) {
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
                    source = "AdvCodeEditor"
                )
            }
        }

        @Test
        fun `should create new file with LLM assistance`() = runBlocking {
            // Given - file doesn't exist
            val llmResponse = """
                ```kotlin
                fun main() {
                    println("Hello, World!")
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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.002,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "new.kt",
                "edit_description" to "Create a main function"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("new.kt")))
            assertTrue(result.output!!.contains("created successfully"))
            val content = Files.readString(tempDir.resolve("new.kt"))
            assertTrue(content.contains("println"))
        }

        @Test
        fun `should create parent directories when creating new file`() = runBlocking {
            // Given
            val llmResponse = """
                ```kotlin
                new file content
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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.002,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "subdir/nested/file.kt",
                "edit_description" to "Create file"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("subdir/nested/file.kt")))
            assertTrue(Files.isDirectory(tempDir.resolve("subdir/nested")))
        }

        @Test
        fun `should include diff in output`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "old")

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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "```kotlin\nnew\n```",
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
            assertTrue(result.success)
            assertTrue(result.output!!.contains("```diff"))
            assertTrue(result.output!!.contains("---"))
            assertTrue(result.output!!.contains("+++"))
        }

        @Test
        fun `should include metadata with cost information`() = runBlocking {
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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "```kotlin\nnew\n```",
                usage = LLMUsage(inputTokens = 200, outputTokens = 100, totalTokens = 200 + 100),
                cost = 0.005,
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
            assertEquals("llm_assisted", result.metadata!!["mode"])
            assertEquals(200, result.metadata!!["tokens_in"])
            assertEquals(100, result.metadata!!["tokens_out"])
            assertEquals(0.005, result.metadata!!["cost_usd"])
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when LLM fails`() = runBlocking {
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
                    source = "AdvCodeEditor"
                )
            } throws RuntimeException("LLM service unavailable")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("LLM request failed", ignoreCase = true))
        }

        @Test
        fun `should return error when LLM returns no code block`() = runBlocking {
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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "Here's the explanation but no code block",
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

            // Then — after docs/0059 Faza 2 the tool re-prompts before giving up; with no code
            // block ever returned it fails loud (the bounded-retry depth is covered in detail by
            // `should fail loud after exhausting extraction repair attempts`).
            assertFalse(result.success)
            assertTrue(result.error!!.contains("usable code block", ignoreCase = true))
        }

        @Test
        fun `should return error when file is too large`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxFileSize = 100)
            val strictTool = AdvanceCodeEditingTool(
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
        fun `should return error when path is a directory`() = runBlocking {
            // Given
            Files.createDirectories(tempDir.resolve("adir"))

            // When
            val result = tool.execute(mapOf(
                "path" to "adir",
                "edit_description" to "Edit"
            ))

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("not a regular file", ignoreCase = true))
        }

        @Test
        fun `should return error when extension is excluded`() = runBlocking {
            val result = tool.execute(mapOf(
                "path" to "binary.exe",
                "edit_description" to "Edit"
            ))

            assertFalse(result.success)
            assertTrue(result.error!!.contains("extension not allowed", ignoreCase = true))
        }
    }

    @Nested
    inner class CodeBlockExtractionTests {

        @Test
        fun `should extract code from markdown block with language`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.py"), "old")

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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "```python\nnew code\n```",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.py",
                "edit_description" to "Edit"
            ))

            // Then
            assertTrue(result.success)
            assertEquals("new code", Files.readString(tempDir.resolve("test.py")))
        }

        @Test
        fun `should extract code from markdown block without language`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.kt"), "old")

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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "```\nnew code\n```",
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
            assertEquals("new code", Files.readString(tempDir.resolve("test.kt")))
        }
    }

    @Nested
    inner class LanguageDetectionTests {

        @Test
        fun `should detect kotlin from kt extension`() = runBlocking {
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
                    source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = "```kotlin\nnew\n```",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 100 + 50),
                cost = 0.001,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            tool.execute(mapOf(
                "path" to "test.kt",
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
                    source = "AdvCodeEditor"
                )
            }
        }

        @Test
        fun `should detect python from py extension`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.py"), "content")

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
                    source = any()  // Changed from "AdvCodeEditor" to any()
                )
            } returns LLMResponse(
                content = "```python\nnew\n```",
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

            // Then - verify LLM was called
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
        }
    }
}
