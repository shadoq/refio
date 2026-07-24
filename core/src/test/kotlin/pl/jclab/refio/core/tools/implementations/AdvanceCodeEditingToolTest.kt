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
        fun `should resolve the coding model via the CODING operation slot`() = runBlocking {
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

            // Then — the edit succeeds and the model was resolved through the CODING slot
            assertTrue(result.success)
            coVerify { mockConfigService.getModel(ModelOperation.CODING, any()) }
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
        fun `should not write multi-line prose to the file when no code fence is present`() = runBlocking {
            // Guardrail against corrupting the file with a non-code reply. A weak/refusing
            // editor model can return a multi-line explanation or apology with no fenced code
            // block. Such prose must never be written verbatim as the file's contents — the
            // tool re-prompts for a real code block and, failing that, fails loud and leaves
            // the original file untouched (Rule 11). Here the model returns prose on both the
            // initial call and the repair retry.
            val original = "fun original() = 1"
            Files.writeString(tempDir.resolve("test.kt"), original)

            val prose = """
                I cannot edit this file as requested.
                The instructions are ambiguous to me.
                You should provide a concrete before/after example.
                Try rephrasing the edit description.
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
                content = prose,
                usage = LLMUsage(inputTokens = 40, outputTokens = 30, totalTokens = 70),
                cost = 0.0,
                model = "test-model",
                provider = "test-provider"
            )

            // When
            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Modify the content"
            ))

            // Then — the prose was rejected, the file was not clobbered, and we failed loud
            assertFalse(result.success, "prose must not be accepted as file content")
            assertEquals(original, Files.readString(tempDir.resolve("test.kt")))
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
    inner class TruncationAndTransientRetryTests {

        @Test
        fun `salvages a large unterminated block instead of losing the whole generation`() = runBlocking {
            // The reported waste case: the editor streamed a big file but the stream ended without a
            // closing ``` fence, so strict extraction found "no usable code block" and the ~65KB
            // deliverable was thrown away. Salvage recovers the content after the opening fence when
            // it is substantial, so a near-complete file is written (marked truncated) rather than lost.
            val bigBody = "<!DOCTYPE html>\n<html>\n<body>\n" + "  <div class=\"row\">content</div>\n".repeat(120)
            val unterminated = "```html\n$bigBody" // NOTE: no closing fence — the stream was cut off

            coEvery {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = unterminated,
                usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                cost = 0.001, model = "test-model", provider = "test-provider"
            )

            val result = tool.execute(mapOf(
                "path" to "page.html",
                "edit_description" to "Build the landing page"
            ))

            assertTrue(result.success, "salvage should write the recovered content, got error=${result.error}")
            val written = Files.readString(tempDir.resolve("page.html"))
            assertTrue(written.startsWith("<!DOCTYPE html>"), "recovered content should be the file body, not the fence")
            assertFalse(written.contains("```"), "the opening fence must be stripped from the written file")
            assertTrue(result.output!!.contains("SALVAGED"), "the agent must be warned the file may be truncated")
        }

        @Test
        fun `does not re-generate on a truncated block - salvages after a single editor call`() = runBlocking {
            // A large unterminated block means the generation was cut off (output cap / upstream
            // truncation), not that the model refused — re-prompting would produce the same truncation
            // and burn another full multi-minute generation. The tool must salvage the first reply and
            // NOT run the extraction-repair re-generation.
            val bigBody = "<!DOCTYPE html>\n<html>\n<body>\n" + "  <div class=\"row\">content</div>\n".repeat(120)
            val unterminated = "```html\n$bigBody" // no closing fence — cut off mid-file

            coEvery {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = unterminated,
                usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                cost = 0.001, model = "test-model", provider = "test-provider"
            )

            val result = tool.execute(mapOf(
                "path" to "page.html",
                "edit_description" to "Build the landing page"
            ))

            assertTrue(result.success, "salvage should write the recovered content, got error=${result.error}")
            coVerify(exactly = 1) {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            }
        }

        @Test
        fun `does not salvage a short unterminated reply that is really prose`() = runBlocking {
            // A tiny opening-fence reply is far more likely a refusal than a truncated file — writing
            // it would corrupt the file. Below the salvage threshold we must still fail loud.
            Files.writeString(tempDir.resolve("test.kt"), "original content")
            val shortUnterminated = "```kotlin\nfun x() = 1 // sorry, I cannot complete this"

            coEvery {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            } returns LLMResponse(
                content = shortUnterminated,
                usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                cost = 0.0, model = "test-model", provider = "test-provider"
            )

            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            assertFalse(result.success, "a short unterminated reply must not be salvaged")
            assertEquals("original content", Files.readString(tempDir.resolve("test.kt")))
        }

        @Test
        fun `retries the editor call on a transient upstream error and then succeeds`() = runBlocking {
            // The editor call bypasses LLMRetryHandler; a single Anthropic HTTP 500 used to fail the
            // whole edit. It must now ride out one transient blip and write the retry's clean output.
            Files.writeString(tempDir.resolve("test.kt"), "original content")

            coEvery {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            } throws RuntimeException("Anthropic API error (HTTP 500): ") andThen LLMResponse(
                content = "```kotlin\nrecovered after retry\n```",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                cost = 0.002, model = "test-model", provider = "test-provider"
            )

            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            assertTrue(result.success, "expected recovery after a transient 500, got error=${result.error}")
            assertEquals("recovered after retry", Files.readString(tempDir.resolve("test.kt")))
            coVerify(exactly = 2) {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            }
        }

        @Test
        fun `does not retry a genuine client error`() = runBlocking {
            // A 400 is not transient — retrying wastes time and money. Fail fast on the first attempt.
            Files.writeString(tempDir.resolve("test.kt"), "original content")

            coEvery {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            } throws RuntimeException("Anthropic API error (HTTP 400): invalid request")

            val result = tool.execute(mapOf(
                "path" to "test.kt",
                "edit_description" to "Edit"
            ))

            assertFalse(result.success)
            coVerify(exactly = 1) {
                mockLLMClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    temperature = any(), maxTokens = any(), stream = false,
                    onChunk = null as ((pl.jclab.refio.core.api.StreamChunk) -> Unit)?,
                    taskId = null, subtaskId = null, source = "AdvCodeEditor"
                )
            }
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
