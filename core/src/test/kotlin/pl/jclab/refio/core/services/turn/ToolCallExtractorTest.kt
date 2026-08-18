package pl.jclab.refio.core.services.turn

import io.mockk.every
import io.mockk.mockk
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.NativeToolCall
import pl.jclab.refio.core.tools.base.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolCallExtractorTest {

    private val registry = mockk<ToolRegistry>(relaxed = true).also {
        // Only these are registered tools — recovery must refuse anything else (anti-garbage guard).
        every { it.hasTool(any()) } returns false
        every { it.hasTool("read_file") } returns true
        every { it.hasTool("read_directory") } returns true
        every { it.hasTool("grep_search") } returns true
    }
    private val parser = ToolCallParser(
        toolRegistry = registry,
    )
    private val extractor = ToolCallExtractor(parser, registry)

    private fun response(
        content: String,
        native: List<NativeToolCall>? = null,
        finishReason: String? = "stop",
        model: String = "qwen3-coder:30b",
    ) = LLMResponse(
        content = content,
        usage = LLMUsage(0, 0, 0),
        model = model,
        provider = "ollama",
        cost = 0.0,
        finishReason = finishReason,
        nativeToolCalls = native,
    )

    private fun extract(r: LLMResponse) = extractor.extract(r, r.content, TaskMode.AGENT, null)

    @Test
    fun `provider-native calls are mapped 1-to-1 and reported as NATIVE`() {
        val r = response(
            content = "I'll read it.",
            native = listOf(NativeToolCall(id = "c1", name = "read_file", argumentsJson = """{"path":"a.kt"}""")),
        )
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.NATIVE, result.source)
        assertEquals("read_file", result.calls.single().name)
        assertEquals("""{"path":"a.kt"}""", result.calls.single().arguments)
    }

    @Test
    fun `empty native list is a legitimate finish, not a failure`() {
        // Model used the native channel and chose to answer in prose — must NOT be flagged as error.
        val result = extract(response(content = "Done, nothing to change.", native = emptyList()))
        assertIs<ExtractionResult.None>(result)
        assertEquals("native-channel-no-calls", result.reason)
    }

    @Test
    fun `JSON envelope in text is extracted and reported as JSON`() {
        val r = response(content = """{"actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}],"response":"reading"}""")
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.JSON, result.source)
        assertEquals("read_file", result.calls.single().name)
    }

    @Test
    fun `Hermes tool_call tags are recovered even with surrounding prose`() {
        // Local models emit this around prose — recovery MUST tolerate noise (docs/0056 §4).
        val r = response(
            content = "Sure, let me look.\n<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.kt\"}}</tool_call>\nThanks!",
        )
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.HERMES, result.source)
        assertEquals("read_file", result.calls.single().name)
        assertTrue(result.calls.single().arguments.contains("\"path\""))
    }

    @Test
    fun `Qwen3-Coder XML function-parameter form is recovered`() {
        val r = response(content = "<function=read_file><parameter=path>a.kt</parameter></function>")
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.QWEN_CODER_XML, result.source)
        assertEquals("read_file", result.calls.single().name)
        assertTrue(result.calls.single().arguments.contains("a.kt"))
    }

    @Test
    fun `recovery refuses calls whose name is not a registered tool and surfaces the attempt`() {
        // The reason XML parsing was removed historically: it resurrected garbage. Guard must hold —
        // AND the failed attempt must be reported (not silently treated as a finish).
        val r = response(content = "<tool_call>{\"name\":\"make_coffee\",\"arguments\":{}}</tool_call>")
        val result = extract(r)
        assertIs<ExtractionResult.None>(result)
        assertEquals("attempted-toolcall-unparseable", result.reason)
    }

    @Test
    fun `native channel suppresses text recovery even when tags are present`() {
        // If the runtime gave us the native channel (non-null), it is authoritative — text tags
        // (e.g. a duplicate echo) must not spawn a second, conflicting call.
        val r = response(
            content = "<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.kt\"}}</tool_call>",
            native = emptyList(),
        )
        val result = extract(r)
        assertIs<ExtractionResult.None>(result)
        assertEquals("native-channel-no-calls", result.reason)
    }

    @Test
    fun `empty native list plus an actions envelope in text is recovered (real adapter shape)`() {
        // Codex adversarial-review regression (docs/0068): real adapters return nativeToolCalls =
        // emptyList() (NOT null) when native tools were requested but the model produced 0 native
        // calls. A model flagged native-capable that instead emits the canonical {response, actions}
        // envelope in TEXT used to have it silently dropped here (authoritative "finished"), so the
        // requested tool never ran. There is no native call to conflict with, so recovering the
        // envelope cannot spawn a phantom double-call — it MUST be recovered.
        val r = response(
            content = """{"response":"reading","actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}]}""",
            native = emptyList(),
        )
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.JSON, result.source)
        assertEquals("read_file", result.calls.single().name)
    }

    @Test
    fun `empty native list plus a fenced actions envelope is recovered`() {
        // Same recovery, fenced — the model wraps the envelope in a ```json block while native is empty.
        val r = response(
            content = "```json\n{\"actions\":[{\"tool\":\"read_file\",\"arguments\":{\"path\":\"a.kt\"}}],\"response\":\"reading\"}\n```",
            native = emptyList(),
        )
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.JSON, result.source)
        assertEquals("read_file", result.calls.single().name)
    }

    @Test
    fun `prose that gestures at a tool call but carries no parseable call is a failed attempt`() {
        // Regression for session 79abb6e5: weak model talks about calling a tool but emits no
        // recoverable JSON/tag. The loop used to finish silently; now it surfaces a distinct reason.
        val r = response(
            content = """Let me call the tool: I'll set "name" to read_file and pass "arguments".""",
            finishReason = "stop",
        )
        val result = extract(r)
        assertIs<ExtractionResult.None>(result)
        assertEquals("attempted-toolcall-unparseable", result.reason)
    }

    @Test
    fun `plain final answer is a clean no-toolcall, not a failed attempt`() {
        val result = extract(response(content = "The bug is in line 42; here is the explanation."))
        assertIs<ExtractionResult.None>(result)
        assertEquals("final-text-no-toolcall", result.reason)
    }

    @Test
    fun `truncated incomplete JSON envelope at finishReason=length is flagged as incomplete, not a finish`() {
        // The model hit the output cap mid-envelope: an unclosed `{...` that cannot parse. This is a
        // distinct, actionable failure (suggest smaller output / bigger context), NOT "finished in prose".
        // Centralizing detection here (vs a parallel guard in AgentTurnLoop) is the point of docs/0064.
        val r = response(
            content = """{"response":"creating the file","actions":[{"tool":"read_file","arguments":{"pa""",
            finishReason = "length",
        )
        val result = extract(r)
        assertIs<ExtractionResult.None>(result)
        assertEquals("incomplete-json-truncated", result.reason)
    }

    @Test
    fun `a complete envelope is parsed even when finishReason=length, never mislabeled truncated`() {
        // finishReason=length alone must not trigger truncation handling — only an *incomplete* envelope
        // does. A complete, parseable envelope still yields its calls.
        val r = response(
            content = """{"actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}],"response":"reading"}""",
            finishReason = "length",
        )
        val result = extract(r)
        assertIs<ExtractionResult.Calls>(result)
        assertEquals("read_file", result.calls.single().name)
    }

    @Test
    fun `plain prose truncated at finishReason=length is not hijacked as incomplete-json-truncated`() {
        // No JSON envelope present — a cut-off prose answer is a normal no-toolcall, not the JSON
        // truncation case. Guards against the truncation branch swallowing every length-capped reply.
        val r = response(content = "Here is the explanation and it got cut o", finishReason = "length")
        val result = extract(r)
        assertIs<ExtractionResult.None>(result)
        assertEquals("final-text-no-toolcall", result.reason)
    }

    @Test
    fun `native channel is never flagged truncated even at finishReason=length`() {
        // Native is authoritative and returns before truncation inspection; an empty native reply is a
        // finish regardless of finishReason (mirrors the loop's !usedNativeChannel precondition).
        val r = response(content = "", native = emptyList(), finishReason = "length")
        val result = extract(r)
        assertIs<ExtractionResult.None>(result)
        assertEquals("native-channel-no-calls", result.reason)
    }

    @Test
    fun `bracket-labeled tool lines emitted as text are recovered as a batch`() {
        // Real failure (session 6a1534a9, qwen3.5:35b): the model batched four reads in the ad-hoc
        // `[TOOL] name: key="val"` line format that matches NO contract, so the whole attempt was
        // dropped and the turn went INCOMPLETE. Recover it like the other text formats (docs/0067).
        val content = """
            Let me examine more critical source files to identify specific risks.

            [TOOL] read_file: path="core/build.gradle.kts"
            read_file: path="cli/build.gradle.kts"
            read_directory: path="core/src/main/kotlin/pl/jclab/refio/core/security", max_depth=2
            grep_search: pattern="apiKey|secret|password|token", file_pattern="*.kt", max_results=50
        """.trimIndent()
        val result = extract(response(content = content, model = "qwen3.5:35b"))
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.BRACKET_TOOL, result.source)
        assertEquals(
            listOf("read_file", "read_file", "read_directory", "grep_search"),
            result.calls.map { it.name },
        )
        assertTrue(result.calls[0].arguments.contains("core/build.gradle.kts"))
        assertTrue(
            result.calls[3].arguments.contains("apiKey|secret|password|token"),
            "pipe-delimited quoted value must survive intact, got: ${result.calls[3].arguments}",
        )
    }

    @Test
    fun `space-form tool line after a standalone bracket marker is recovered (qwen3-coder shape)`() {
        // Real failure (qwen3-coder:30b, increase-retry-count): after editing correctly the model
        // emitted a verification grep as a standalone `[TOOL]` marker line followed by `name key="value"`
        // (space-separated, NO colon) — the colon-only labeled recovery dropped it, tripping the format
        // hard-fail. Recover the space form too, armed only by the bare marker line.
        val content = """
            Let me double-check the change.

            [TOOL]
            grep_search pattern="MAX_RETRIES"
        """.trimIndent()
        val result = extract(response(content = content))
        assertIs<ExtractionResult.Calls>(result)
        assertEquals(ToolCallSource.BRACKET_TOOL, result.source)
        assertEquals(listOf("grep_search"), result.calls.map { it.name })
        assertTrue(result.calls[0].arguments.contains("MAX_RETRIES"))
    }

    @Test
    fun `standalone bracket marker does not turn arbitrary prose with an equals sign into a call`() {
        // Narrowness for the space form (Rule 7): the bare `[TOOL]` marker arms recovery, but a
        // following line must still name a REGISTERED tool with key=value args — `the result = 5` is not.
        val content = """
            [TOOL]
            the result = 5 and everything = fine
        """.trimIndent()
        val result = extract(response(content = content))
        assertIs<ExtractionResult.None>(result)
    }

    @Test
    fun `bracket-tool recovery refuses an unregistered tool name and surfaces the attempt`() {
        // Same anti-garbage guard as the XML strategies: a [TOOL] line naming a non-tool is not a call,
        // but the [TOOL] marker means it WAS an attempt — report it, never finish silently on it.
        val result = extract(response(content = """[TOOL] make_coffee: cups="2""""))
        assertIs<ExtractionResult.None>(result)
        assertEquals("attempted-toolcall-unparseable", result.reason)
    }

    @Test
    fun `a tool-shaped line without the bracket marker is left as prose, not a call`() {
        // Narrow trigger (Rule 7 / docs/0056): only the literal [TOOL] marker arms this recovery.
        // A bare `name: key=val` line must NOT be hijacked — any "Field: a=b" prose would false-positive.
        val result = extract(response(content = """read_file: path="a.kt""""))
        assertIs<ExtractionResult.None>(result)
        assertEquals("final-text-no-toolcall", result.reason)
    }

    @Test
    fun `TOOL marker in unrelated prose does not arm recovery for a separate tool-shaped prose line`() {
        // Devil's-advocate regression (review of branch `fix`): the marker armed the WHOLE block via a
        // bare content.contains("[TOOL]"), but the per-line `[TOOL]` prefix was optional — so once the
        // marker appeared ANYWHERE, every `registeredTool: key=value` prose line spawned a spurious call
        // (silent wrong action, Rule 12). The marker must actually PREFIX a tool-shaped line to arm
        // recovery, matching the documented `[TOOL] name: args` batch format. Here `[TOOL]` only appears
        // inside prose and the tool-shaped line carries no marker, so nothing must be recovered.
        val content = """
            I considered using the [TOOL] system here.
            read_file: useful when path=clear and you know=what
        """.trimIndent()
        val result = extract(response(content = content))
        assertIs<ExtractionResult.None>(result)
    }

    @Test
    fun `native and JSON-in-text produce equivalent calls (one unified shape)`() {
        // Proof that the path is unified: same intent, same resulting tool call.
        val viaNative = extract(
            response(
                content = "",
                native = listOf(NativeToolCall("x", "read_file", """{"path":"a.kt"}""")),
            ),
        )
        val viaJson = extract(
            response(content = """{"actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}]}"""),
        )
        assertIs<ExtractionResult.Calls>(viaNative)
        assertIs<ExtractionResult.Calls>(viaJson)
        assertEquals(viaNative.calls.single().name, viaJson.calls.single().name)
        assertEquals("read_file", viaJson.calls.single().name)
    }
}
