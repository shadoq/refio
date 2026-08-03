package pl.jclab.refio.core.services.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the structural compression of directory listings.
 *
 * WHY it exists: a 29 KB recursive `read_directory` was summarized by the WEAK model into 562
 * chars of prose with no directory name in it - the agent could no longer choose a file to read,
 * while the raw listing kept costing ~15,5K tokens per iteration in RECENT_WORK. The compressed
 * form has to stay actionable: real directory names, real counts, real sample file names.
 */
class FileListingCompressionTest {

    private fun readDirectoryListing(dirs: List<String>, filesPerDir: Int): String =
        dirs.joinToString("\n") { dir ->
            (1..filesPerDir).joinToString("\n") { i ->
                "    FILE  ${i}KB  $dir\\file_$i.html"
            }
        }

    @Test
    fun `keeps directory names, counts and a sample instead of prose`() {
        val raw = readDirectoryListing(listOf("snake", "todo_app"), filesPerDir = 30)

        val compressed = FileListingCompression.compress(raw)

        assertTrue(compressed.contains("snake/"), compressed)
        assertTrue(compressed.contains("todo_app/"), compressed)
        assertTrue(compressed.contains("(30 entries"), compressed)
        assertTrue(compressed.contains("file_1.html"), compressed)
        assertTrue(compressed.contains("+25 more"), compressed)
        assertTrue(compressed.contains("60 entries in 2 directories"), compressed)
        assertTrue(compressed.length < raw.length / 2, "expected real savings, got ${compressed.length}/${raw.length}")
    }

    @Test
    fun `sums the size column per directory`() {
        val raw = readDirectoryListing(listOf("snake"), filesPerDir = 20)  // 1..20 KB = 210KB

        val compressed = FileListingCompression.compress(raw)

        assertTrue(compressed.contains("210KB"), compressed)
    }

    @Test
    fun `bare paths from file_search compress the same way`() {
        val raw = (1..40).joinToString("\n") { "neuron_growth/neuron_growth_$it.html" }

        val compressed = FileListingCompression.compress(raw)

        assertTrue(compressed.contains("neuron_growth/  (40 entries)"), compressed)
        assertTrue(compressed.contains("+35 more"), compressed)
    }

    @Test
    fun `a short listing is returned untouched`() {
        // Below the line threshold the raw listing is already readable - compressing would only
        // hide names for no budget gain.
        val raw = (1..5).joinToString("\n") { "src/File$it.kt" }

        assertEquals(raw, FileListingCompression.compress(raw))
    }

    @Test
    fun `text that is not a listing is returned untouched`() {
        val raw = (1..30).joinToString("\n") { "Line $it of an ordinary tool message" }

        assertEquals(raw, FileListingCompression.compress(raw))
    }

    @Test
    fun `a header line before the entries is preserved`() {
        val raw = "Found 40 results:\n" + (1..40).joinToString("\n") { "src/main/File$it.kt" }

        val compressed = FileListingCompression.compress(raw)

        assertTrue(compressed.startsWith("Found 40 results:"), compressed)
    }

    @Test
    fun `top-level files land in a dot group`() {
        val raw = (1..30).joinToString("\n") { "  FILE  2KB  file_$it.html" }

        val compressed = FileListingCompression.compress(raw)

        assertTrue(compressed.contains("./  (30 entries"), compressed)
    }
}
