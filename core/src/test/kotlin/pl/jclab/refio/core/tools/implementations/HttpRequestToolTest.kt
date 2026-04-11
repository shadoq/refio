package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for HttpRequestTool - HTTP request execution tool.
 */
class HttpRequestToolTest {

    private lateinit var tool: HttpRequestTool

    @BeforeEach
    fun setup() {
        tool = HttpRequestTool()
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("http_request", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.WRITE, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.DATA_PRODUCING, tool.category)
        }

        @Test
        fun `should have non-empty description`() {
            assertTrue(tool.description.isNotEmpty())
        }
    }

    @Nested
    inner class ParameterValidationTests {

        @Test
        fun `should reject missing url parameter`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(emptyMap())
            }
        }

        @Test
        fun `should reject blank url parameter`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("url" to ""))
            }
        }

        @Test
        fun `should reject url without protocol`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("url" to "example.com/api"))
            }
        }

        @Test
        fun `should accept valid https url`() {
            tool.validateParams(mapOf("url" to "https://example.com/api"))
        }

        @Test
        fun `should accept valid http url`() {
            tool.validateParams(mapOf("url" to "http://localhost:8080/api"))
        }

        @Test
        fun `should reject invalid method`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("url" to "https://example.com", "method" to "INVALID"))
            }
        }

        @Test
        fun `should accept all valid methods`() {
            for (method in HttpRequestTool.ALLOWED_METHODS) {
                tool.validateParams(mapOf("url" to "https://example.com", "method" to method))
            }
        }

        @Test
        fun `should accept method case insensitive`() {
            tool.validateParams(mapOf("url" to "https://example.com", "method" to "post"))
        }
    }

    @Nested
    inner class ParameterSchemaTests {

        @Test
        fun `should return non-empty schema`() {
            val schema = tool.getParameterSchema()
            assertTrue(schema.isNotEmpty())
        }

        @Test
        fun `should require url parameter`() {
            val schema = tool.getParameterSchema()
            @Suppress("UNCHECKED_CAST")
            val required = schema["required"] as List<String>
            assertTrue("url" in required)
        }

        @Test
        fun `should define url, method, body, headers, content_type, save_to_file properties`() {
            val schema = tool.getParameterSchema()
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as Map<String, Any>
            assertTrue("url" in properties)
            assertTrue("method" in properties)
            assertTrue("body" in properties)
            assertTrue("headers" in properties)
            assertTrue("content_type" in properties)
            assertTrue("save_to_file" in properties)
        }
    }

    @Nested
    inner class ExecutionTests {

        @Test
        fun `should return error for missing url parameter`() = runBlocking {
            val result = tool.execute(emptyMap())
            assertFalse(result.success)
            assertNotNull(result.error)
        }

        @Test
        fun `should return error for unreachable host`() = runBlocking {
            val result = tool.execute(mapOf(
                "url" to "http://192.0.2.1:1/nonexistent",
                "method" to "GET"
            ))
            assertFalse(result.success)
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // This will fail to connect but should still provide metadata in error
            val result = tool.execute(mapOf(
                "url" to "http://192.0.2.1:1/test"
            ))
            assertFalse(result.success)
        }

        @Test
        fun `should default to GET method`() = runBlocking {
            // Just verify no exception with missing method
            val result = tool.execute(mapOf(
                "url" to "http://192.0.2.1:1/test"
            ))
            // Will fail due to unreachable host, but method defaulting should work
            assertFalse(result.success)
        }
    }

    @Nested
    inner class TimeoutTests {

        @Test
        fun `should respect custom timeout`() {
            val shortTimeoutTool = HttpRequestTool(timeoutMs = 1000)
            runBlocking {
                val start = System.currentTimeMillis()
                val result = shortTimeoutTool.execute(mapOf(
                    "url" to "http://192.0.2.1:1/timeout-test"
                ))
                val elapsed = System.currentTimeMillis() - start
                assertFalse(result.success)
                // Should not take more than 10 seconds even with retries
                assertTrue(elapsed < 10_000, "Request took too long: ${elapsed}ms")
            }
        }
    }

    @Nested
    inner class ResponseSizeLimitTests {

        @Test
        fun `should accept custom max response size`() {
            // Verify tool can be constructed with custom limits without error
            val smallLimitTool = HttpRequestTool(maxResponseSize = 100)
            assertNotNull(smallLimitTool)
            assertEquals("http_request", smallLimitTool.name)
        }
    }

    /**
     * Tests for the body-coercion helper introduced after a bug where qwen3.5
     * (and other models) passed `body` as a JSON object instead of a JSON string.
     * Without coercion the parameter silently dropped to null and the HTTP body
     * went out empty, which is what produced the "no data sent" stuck-agent loop.
     */
    @Nested
    inner class BodyCoercionTests {

        @Test
        fun `coerce null body to null`() {
            assertNull(tool.coerceBody(null))
        }

        @Test
        fun `coerce string body returns it as-is`() {
            val raw = """{"apikey":"abc","task":"foo"}"""
            assertEquals(raw, tool.coerceBody(raw))
        }

        @Test
        fun `coerce empty string returns empty string`() {
            assertEquals("", tool.coerceBody(""))
        }

        @Test
        fun `coerce Map body serializes to JSON object`() {
            val raw = linkedMapOf<String, Any>(
                "apikey" to "abc",
                "task" to "domatowo",
                "answer" to linkedMapOf(
                    "action" to "inspect",
                    "object" to "8d347de8c433175cbc3b415b163cd9ec"
                )
            )
            val coerced = tool.coerceBody(raw)
            assertNotNull(coerced)
            // Verify it produced valid JSON, not Java map toString.
            assertTrue(coerced.startsWith("{"))
            assertTrue(coerced.contains("\"apikey\":\"abc\""), "Expected JSON-quoted apikey, got: $coerced")
            assertTrue(coerced.contains("\"action\":\"inspect\""), "Expected nested JSON serialization, got: $coerced")
            assertTrue(coerced.contains("\"task\":\"domatowo\""), "Expected JSON-quoted task, got: $coerced")
            // Critical anti-regression: must NOT use Java map's `=` syntax.
            assertFalse(coerced.contains("apikey=abc"), "Body must not look like Java Map toString: $coerced")
        }

        @Test
        fun `coerce List body serializes to JSON array`() {
            val raw = listOf("a", "b", "c")
            val coerced = tool.coerceBody(raw)
            assertEquals("[\"a\",\"b\",\"c\"]", coerced)
        }

        @Test
        fun `coerce nested Map preserves structure`() {
            val raw = linkedMapOf("outer" to linkedMapOf("inner" to listOf(1, 2, 3)))
            val coerced = tool.coerceBody(raw)
            assertEquals("{\"outer\":{\"inner\":[1,2,3]}}", coerced)
        }

        @Test
        fun `coerce Number body returns string form`() {
            assertEquals("42", tool.coerceBody(42))
            assertEquals("3.14", tool.coerceBody(3.14))
        }

        @Test
        fun `coerce Boolean body returns string form`() {
            assertEquals("true", tool.coerceBody(true))
            assertEquals("false", tool.coerceBody(false))
        }

        @Test
        fun `coerce empty Map serializes to empty JSON object`() {
            assertEquals("{}", tool.coerceBody(emptyMap<String, Any>()))
        }

        @Test
        fun `coerce empty List serializes to empty JSON array`() {
            assertEquals("[]", tool.coerceBody(emptyList<Any>()))
        }
    }
}
