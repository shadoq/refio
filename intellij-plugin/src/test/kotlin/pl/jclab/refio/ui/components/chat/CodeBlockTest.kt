package pl.jclab.refio.ui.components.chat

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [extractCodeBlocks], the standalone fenced-code extractor used across chat rendering.
 * Language defaulting, optional `:filepath`, CRLF normalization and the captured offsets all feed
 * the code panels, so they are pinned here.
 */
class CodeBlockTest {

    @Test
    fun `extracts a fenced block with language and file path`() {
        val blocks = extractCodeBlocks("intro\n```kotlin:src/A.kt\nval x = 1\n```\noutro")
        assertEquals(1, blocks.size)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("src/A.kt", blocks[0].filePath)
        assertEquals("val x = 1", blocks[0].content)
    }

    @Test
    fun `a block without a language defaults to text`() {
        assertEquals("text", extractCodeBlocks("```\nplain\n```")[0].language)
    }

    @Test
    fun `extracts multiple blocks in order`() {
        val blocks = extractCodeBlocks("```kotlin\na\n```\ntext\n```java\nb\n```")
        assertEquals(2, blocks.size)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("java", blocks[1].language)
    }

    @Test
    fun `normalizes CRLF line endings in content`() {
        val block = extractCodeBlocks("```\r\nline1\r\nline2\r\n```")[0]
        assertEquals("line1\nline2", block.content)
    }

    @Test
    fun `returns empty when there are no fenced blocks`() {
        assertTrue(extractCodeBlocks("just prose, no fences").isEmpty())
    }

    @Test
    fun `the captured range spans the whole fenced block`() {
        val md = "x ```kotlin\ny\n``` z"
        val block = extractCodeBlocks(md).single()
        val span = md.substring(block.startIndex, block.endIndex + 1)
        assertTrue(span.startsWith("```") && span.endsWith("```"))
    }
}
