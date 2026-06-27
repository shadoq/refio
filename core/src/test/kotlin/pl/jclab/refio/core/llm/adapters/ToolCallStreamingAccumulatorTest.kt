package pl.jclab.refio.core.llm.adapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streaming tool-call delta emission for the shared OpenAI-family accumulator (docs/0064).
 *
 * This accumulator backs OpenAIAdapter + every OpenAICompatible adapter (OpenRouter, Z.AI, LM Studio,
 * Generic). The contract that matters for progressive UI: each consumed chunk returns the increments
 * observed in it, AND the concatenation of those increments must equal the final assembled call — i.e.
 * surfacing progress never diverges from the call that ultimately executes.
 */
class ToolCallStreamingAccumulatorTest {

    private fun delta(vararg toolCalls: Map<String, Any?>): Map<String, Any?> =
        mapOf("tool_calls" to toolCalls.toList())

    @Test
    fun `arguments streamed across chunks emit deltas whose concatenation equals the final call`() {
        val acc = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()

        // OpenAI streams: first chunk carries id+name, later chunks carry argument fragments.
        val d1 = acc.consumeDelta(
            delta(mapOf("index" to 0, "id" to "call_1", "function" to mapOf("name" to "read_file", "arguments" to "")))
        )
        val d2 = acc.consumeDelta(delta(mapOf("index" to 0, "function" to mapOf("arguments" to "{\"pa"))))
        val d3 = acc.consumeDelta(delta(mapOf("index" to 0, "function" to mapOf("arguments" to "th\":\"a.kt\"}"))))

        // Name surfaces on the first delta so the UI can label the call immediately.
        assertEquals("read_file", d1.single().nameDelta)
        assertEquals(0, d1.single().index)

        // Argument fragments surface incrementally.
        assertEquals("{\"pa", d2.single().argumentsDelta)
        assertEquals("th\":\"a.kt\"}", d3.single().argumentsDelta)

        // Concatenated fragments == the final assembled arguments (no drift between progress and result).
        val streamedArgs = listOf(d1, d2, d3).flatten().mapNotNull { it.argumentsDelta }.joinToString("")
        val finalCall = acc.toNativeToolCalls(toolsWereRequested = true)!!.single()
        assertEquals("read_file", finalCall.name)
        assertEquals(streamedArgs, finalCall.argumentsJson)
        assertEquals("{\"path\":\"a.kt\"}", finalCall.argumentsJson)
    }

    @Test
    fun `a chunk with neither name nor argument content emits no progress delta`() {
        val acc = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        // index-only housekeeping chunk — nothing renderable, so it must not produce noise.
        val deltas = acc.consumeDelta(delta(mapOf("index" to 0, "function" to mapOf<String, Any?>())))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `parallel tool calls keep independent indices`() {
        val acc = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        val deltas = acc.consumeDelta(
            delta(
                mapOf("index" to 0, "id" to "a", "function" to mapOf("name" to "read_file", "arguments" to "{}")),
                mapOf("index" to 1, "id" to "b", "function" to mapOf("name" to "read_file", "arguments" to "{}")),
            )
        )
        assertEquals(listOf(0, 1), deltas.map { it.index })
        assertEquals(2, acc.toNativeToolCalls(toolsWereRequested = true)!!.size)
    }
}
