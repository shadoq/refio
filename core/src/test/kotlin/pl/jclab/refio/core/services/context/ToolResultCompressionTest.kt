package pl.jclab.refio.core.services.context

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/0063 Faza 2 — when a tool result is shortened, the agent must be told the full output is
 * recoverable instead of silently working off a truncated view (the long-turn failure mode: model
 * hallucinates on data it can't see was cut). The recovery pointer names `memory(get_subtask_output)`
 * with the literal subtask id, matching what MemoryTool / DiffCompressor already emit.
 */
class ToolResultCompressionTest {

    private val config = ToolResultCompressionConfig(detailedMaxChars = 200, summaryMaxChars = 120)

    @Test
    fun `SUMMARY compression of a long output appends a get_subtask_output recovery pointer`() {
        val raw = "DATA ".repeat(400)  // ~2000 chars, well over summaryMaxChars

        val result = ToolResultCompression.compress(
            rawOutput = raw,
            summary = null,
            level = CompressionLevel.SUMMARY,
            config = config,
            subtaskId = "st-42"
        )

        assertTrue(result.length < raw.length, "expected the output to actually be shortened")
        assertTrue(result.contains("get_subtask_output"), "missing recovery pointer: $result")
        assertTrue(result.contains("st-42"), "recovery pointer must carry the subtask id: $result")
    }

    @Test
    fun `no recovery pointer when the output already fits (nothing compressed)`() {
        val raw = "short output"

        val result = ToolResultCompression.compress(
            rawOutput = raw,
            summary = null,
            level = CompressionLevel.SUMMARY,
            config = config,
            subtaskId = "st-42"
        )

        // Agent already sees the whole thing — a "full output: memory(...)" pointer would be noise.
        assertFalse(result.contains("get_subtask_output"), "should not point at recovery when nothing was cut: $result")
    }

    @Test
    fun `no recovery pointer when there is no subtask id to reference`() {
        val raw = "DATA ".repeat(400)

        val result = ToolResultCompression.compress(
            rawOutput = raw,
            summary = null,
            level = CompressionLevel.SUMMARY,
            config = config,
            subtaskId = null
        )

        // Can't reference what we can't name — no false promise of recoverability.
        assertFalse(result.contains("get_subtask_output"), "cannot reference a missing subtask id: $result")
    }
}
