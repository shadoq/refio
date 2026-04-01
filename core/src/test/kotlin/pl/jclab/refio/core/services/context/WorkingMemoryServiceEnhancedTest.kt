package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.services.analysis.ClassElement
import pl.jclab.refio.core.services.analysis.CodeElements
import pl.jclab.refio.core.services.analysis.FunctionElement
import pl.jclab.refio.core.services.analysis.ImportElement
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for enhanced semantic working memory (Unit 3).
 */
class WorkingMemoryServiceEnhancedTest {

    private val service = WorkingMemoryService(maxEntriesPerTask = 50)

    @Test
    fun `extractKnowledge for read_file includes structural info from CodeElements`() {
        val codeElements = CodeElements(
            classes = listOf(
                ClassElement(
                    name = "UserService",
                    type = "class",
                    startLine = 5,
                    endLine = 50,
                    annotations = listOf("Service")
                )
            ),
            functions = listOf(
                FunctionElement(name = "getUser", startLine = 10, endLine = 20),
                FunctionElement(name = "createUser", startLine = 25, endLine = 40)
            ),
            imports = listOf(
                ImportElement(module = "pl.jclab.refio.UserRepository"),
                ImportElement(module = "pl.jclab.refio.UserMapper")
            )
        )

        val entries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "src/main/kotlin/UserService.kt"),
            output = "file content here...",
            iteration = 1,
            codeElementsProvider = { _ -> codeElements }
        )

        assertTrue(entries.isNotEmpty(), "Should produce entries")
        val value = entries.first().value
        assertTrue(value.contains("UserService"), "Should contain class name")
        assertTrue(value.contains("getUser"), "Should contain method name")
    }

    @Test
    fun `extractKnowledge for read_file falls back when no CodeElements`() {
        val entries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "src/main/kotlin/Foo.kt"),
            output = "class Foo {\n  fun bar() {}\n}\n",
            iteration = 1,
            codeElementsProvider = null
        )

        assertTrue(entries.isNotEmpty(), "Should produce entries")
        val value = entries.first().value
        assertTrue(value.contains("Foo"), "Should detect class from output")
    }

    @Test
    fun `extractKnowledge for grep_search includes top matches with file locations`() {
        val output = """
            src/main/kotlin/AuthService.kt:45: fun authenticate(token: String) {
            src/main/kotlin/SecurityFilter.kt:23: override fun doFilter(request: Request) {
            src/main/kotlin/LoginController.kt:89: fun login(credentials: Credentials) {
        """.trimIndent()

        val entries = service.extractKnowledge(
            toolName = "grep_search",
            args = mapOf("pattern" to "authenticate"),
            output = output,
            iteration = 1
        )

        assertTrue(entries.isNotEmpty())
        val value = entries.first().value
        assertTrue(value.contains("AuthService"), "Should contain file name from match")
    }

    @Test
    fun `extractKnowledge for read_directory highlights notable files`() {
        val output = """
            UserService.kt
            AuthService.kt
            ConfigService.kt
            README.md
            utils.py
            package.json
        """.trimIndent()

        val entries = service.extractKnowledge(
            toolName = "read_directory",
            args = mapOf("path" to "src/main/kotlin/services"),
            output = output,
            iteration = 1
        )

        assertTrue(entries.isNotEmpty())
        val value = entries.first().value
        assertTrue(value.contains("UserService.kt") || value.contains("services"), "Should contain directory info")
    }

    @Test
    fun `extractKnowledge for write tools includes change info`() {
        val output = """
            + fun getUser(id: Long): User? {
            +     return userRepository.findById(id)
            + }
        """.trimIndent()

        val entries = service.extractKnowledge(
            toolName = "code_editing",
            args = mapOf("path" to "src/main/kotlin/UserService.kt"),
            output = output,
            iteration = 1
        )

        assertTrue(entries.isNotEmpty())
        val value = entries.first().value
        assertTrue(value.contains("UserService.kt"), "Should contain modified file")
    }

    @Test
    fun `normalizeValue respects 600 char limit`() {
        val longOutput = "x".repeat(700)

        val entries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "big-file.kt"),
            output = longOutput,
            iteration = 1
        )

        assertTrue(entries.isNotEmpty())
        assertTrue(entries.first().value.length <= 600, "Value should be at most 600 chars")
    }

    @Test
    fun `extractKnowledge for unknown tool returns empty`() {
        val entries = service.extractKnowledge(
            toolName = "unknown_tool",
            args = emptyMap(),
            output = "some output",
            iteration = 1
        )

        assertTrue(entries.isEmpty())
    }
}
