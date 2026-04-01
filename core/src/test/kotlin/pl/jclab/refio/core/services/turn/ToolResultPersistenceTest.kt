package pl.jclab.refio.core.services.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for tool result persistence policy (Zmiana 5).
 * Tests the resolveEffectiveContent decision logic.
 */
class ToolResultPersistenceTest {

    @Test
    fun `small output kept raw without summarization flag`() {
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = "Success: file created",
            summaryText = "File created",
            wasSummarized = true,
            isDataProducing = false
        )
        assertEquals("Success: file created", content)
        assertFalse(isSummarized)
    }

    @Test
    fun `output at 500 chars boundary kept raw`() {
        val raw = "x".repeat(500)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = "summary",
            wasSummarized = true,
            isDataProducing = true
        )
        assertEquals(raw, content)
        assertFalse(isSummarized)
    }

    @Test
    fun `data-producing medium output kept raw when summarized`() {
        val raw = "x".repeat(5_000)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = "summary of file",
            wasSummarized = true,
            isDataProducing = true
        )
        assertEquals(raw, content, "Medium data-producing output should be kept raw")
        assertTrue(isSummarized, "Should be marked as summarized to prevent 320-char truncation")
    }

    @Test
    fun `data-producing at buffer boundary kept raw`() {
        val raw = "x".repeat(TurnToolExecutor.DATA_PRODUCING_RAW_OUTPUT_BUFFER)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = "summary",
            wasSummarized = true,
            isDataProducing = true
        )
        assertEquals(raw, content, "Output at exactly buffer size should be kept raw")
        assertTrue(isSummarized)
    }

    @Test
    fun `data-producing over buffer uses summary`() {
        val raw = "x".repeat(TurnToolExecutor.DATA_PRODUCING_RAW_OUTPUT_BUFFER + 1)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = "Concise summary",
            wasSummarized = true,
            isDataProducing = true
        )
        assertEquals("Concise summary", content, "Over-buffer output should use summary")
        assertTrue(isSummarized)
    }

    @Test
    fun `non-data-producing medium output uses summary`() {
        val raw = "x".repeat(5_000)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = "Summarized result",
            wasSummarized = true,
            isDataProducing = false
        )
        assertEquals("Summarized result", content, "Non-data-producing should always use summary")
        assertTrue(isSummarized)
    }

    @Test
    fun `fallback truncates to 2000 chars when not summarized`() {
        val raw = "x".repeat(5_000)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = raw, // summary same as raw (not summarized)
            wasSummarized = false,
            isDataProducing = false
        )
        assertEquals(2000, content.length, "Fallback should truncate to 2000 chars")
        assertFalse(isSummarized)
    }

    @Test
    fun `data-producing medium not summarized uses fallback truncation`() {
        val raw = "x".repeat(5_000)
        val (content, isSummarized) = TurnToolExecutor.resolveEffectiveContent(
            rawOutput = raw,
            summaryText = raw,
            wasSummarized = false,
            isDataProducing = true
        )
        assertEquals(2000, content.length, "Data-producing but not summarized should fallback to truncation")
        assertFalse(isSummarized)
    }

    @Test
    fun `buffer constant is 16KB`() {
        assertEquals(16_000, TurnToolExecutor.DATA_PRODUCING_RAW_OUTPUT_BUFFER)
    }
}
