package pl.jclab.refio.ui.components.chat

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [FilePathDetector], which powers the clickable file links in chat. False positives
 * (turning `v1.2` into a link) or misses (dropping a real `src/App.kt`) are user-visible, and the
 * captured indices feed navigation, so the extension allowlist, segment threshold and offsets are
 * pinned here.
 */
class FilePathDetectorTest {

    @Test
    fun `finds a multi-segment source path`() {
        val matches = FilePathDetector.findFilePaths("please open src/main/App.kt now")
        assertEquals(1, matches.size)
        assertEquals("src/main/App.kt", matches[0].path)
    }

    @Test
    fun `a single-segment path is filtered out at the default two-segment minimum`() {
        assertTrue(FilePathDetector.findFilePaths("just App.kt here").isEmpty())
    }

    @Test
    fun `a single-segment path is accepted when the minimum is lowered to one`() {
        val matches = FilePathDetector.findFilePaths("just App.kt here", minPathSegments = 1)
        assertEquals("App.kt", matches.single().path)
    }

    @Test
    fun `an unknown extension is not treated as a file path`() {
        assertTrue(FilePathDetector.findFilePaths("config at foo/bar.xyz please").isEmpty())
    }

    @Test
    fun `the captured range points at the path within the original text`() {
        val text = "x src/A.kt y"
        val match = FilePathDetector.findFilePaths(text).single()
        assertEquals("src/A.kt", text.substring(match.startIndex, match.endIndex + 1))
    }

    @Test
    fun `looksLikeFilePath requires a separator and a known extension`() {
        assertTrue(FilePathDetector.looksLikeFilePath("src/App.kt"))
        assertTrue(FilePathDetector.looksLikeFilePath("src\\App.kt"))
        assertFalse(FilePathDetector.looksLikeFilePath("App.kt"), "no path separator")
        assertFalse(FilePathDetector.looksLikeFilePath("src/notes"), "no known extension")
        assertFalse(FilePathDetector.looksLikeFilePath("v1.2"), "version string is not a path")
    }
}
