package pl.jclab.refio.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

class IncrementalToolCallStreamFilterTest {

    @Test
    fun `should append plain text without rescanning full payload`() {
        val filter = IncrementalToolCallStreamFilter()

        val first = filter.filter(
            delta = "Hello",
            accumulated = "Hello",
            isComplete = false
        )
        val second = filter.filter(
            delta = " world",
            accumulated = "Hello world",
            isComplete = false
        )

        assertEquals("Hello", first)
        assertEquals("Hello world", second)
    }

    @Test
    fun `should remove tool call protocol split across chunks`() {
        val filter = IncrementalToolCallStreamFilter()

        filter.filter(
            delta = "Working...\nTOOL_",
            accumulated = "Working...\nTOOL_",
            isComplete = false
        )
        val filtered = filter.filter(
            delta = "CALL: read_file\nARGUMENTS: {\"path\":\"a.kt\"}\nDone",
            accumulated = "Working...\nTOOL_CALL: read_file\nARGUMENTS: {\"path\":\"a.kt\"}\nDone",
            isComplete = false
        )

        assertEquals("Working...\nDone", filtered)
    }

    @Test
    fun `should unwrap assistant response envelope on completion`() {
        val filter = IncrementalToolCallStreamFilter()
        val payload = """{"response":"Done","actions":[]}"""

        val filtered = filter.filter(
            delta = payload,
            accumulated = payload,
            isComplete = true
        )

        assertEquals("Done", filtered)
    }
}
