package pl.jclab.refio.core.tools

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The model is always fed LF (read_file normalizes), so an edit string must be re-expressed in the
 * target file's line-ending convention before exact matching - otherwise edits miss on CRLF files.
 */
class LineEndingsTest {

    @Test
    fun `usesCrlf is true only when a CRLF pair is present`() {
        assertTrue(LineEndings.usesCrlf("a\r\nb"))
        assertFalse(LineEndings.usesCrlf("a\nb"))
        assertFalse(LineEndings.usesCrlf("single line, no break"))
    }

    @Test
    fun `LF edit string is expanded to CRLF for a CRLF file`() {
        val file = "x\r\ny\r\n"
        assertEquals("a\r\nb", LineEndings.toFileEol("a\nb", file))
    }

    @Test
    fun `LF edit string is left untouched for an LF file`() {
        val file = "x\ny\n"
        assertEquals("a\nb", LineEndings.toFileEol("a\nb", file))
    }

    @Test
    fun `a model string that already carries CRLF is not doubled on a CRLF file`() {
        // Guards against \r\n -> \r\r\n: fold to LF first, then expand once.
        val file = "x\r\ny\r\n"
        assertEquals("a\r\nb", LineEndings.toFileEol("a\r\nb", file))
    }

    @Test
    fun `a single line with no break is unchanged regardless of file EOL`() {
        assertEquals("hello", LineEndings.toFileEol("hello", "x\r\ny\r\n"))
        assertEquals("hello", LineEndings.toFileEol("hello", "x\ny\n"))
    }
}
