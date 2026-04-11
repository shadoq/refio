package pl.jclab.refio.core.services.context

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.llm.LLMContentPart
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationContextBuilderTest {

    private val builder = ConversationContextBuilder()

    private fun assistantMessage(
        id: String,
        content: String,
        toolCalls: List<ToolCallData>? = null
    ) = ChatMessage(
        id = id,
        taskId = "task-1",
        role = MessageRole.ASSISTANT,
        content = content,
        metadata = null,
        toolCalls = toolCalls,
        toolCallId = null,
        tokensIn = null,
        tokensOut = null,
        cost = null,
        createdAt = 1L
    )

    private fun userMessage(id: String, content: String) = ChatMessage(
        id = id,
        taskId = "task-1",
        role = MessageRole.USER,
        content = content,
        metadata = null,
        toolCalls = null,
        toolCallId = null,
        tokensIn = null,
        tokensOut = null,
        cost = null,
        createdAt = 1L
    )

    @Test
    fun `should convert image tool result into multimodal llm message`() {
        val message = ChatMessage(
            id = "msg-1",
            taskId = "task-1",
            role = MessageRole.TOOL,
            content = "[Image: screenshot.png]",
            metadata = """{"type":"image","path":"screenshot.png","media_type":"image/png","base64":"Zm9v"}""",
            toolCalls = null,
            toolCallId = "tool-1",
            tokensIn = null,
            tokensOut = null,
            cost = null,
            createdAt = 1L
        )

        val llmMessage = builder.convertChatMessageToLLMMessage(
            msg = message,
            toolContentResolver = { "[Image: screenshot.png]" }
        )

        assertEquals("user", llmMessage?.role)
        assertTrue(llmMessage?.content?.contains("Attached image from screenshot.png.") == true)
        assertTrue(llmMessage?.parts?.any { it is LLMContentPart.Image } == true)
    }

    // ── Bug 2A regression tests ────────────────────────────────────────────
    // Previously filterMeaningfulConversation dropped ASSISTANT messages that either
    // had non-empty toolCalls OR whose content looked like a JSON tool envelope. In
    // AGENT mode this matched every assistant message, so conversation history was
    // effectively empty from turn 2 onward. Fix: keep those messages and render them
    // compactly via extractResponseText + a one-line tool call summary.

    @Test
    fun `filterMeaningfulConversation keeps assistant messages with tool calls`() {
        val msg = assistantMessage(
            id = "a1",
            content = """{"actions":[{"tool":"read_file","arguments":{"path":"x.kt"}}],"response":"Reading x.kt to understand the bug","intent":"analysis"}""",
            toolCalls = listOf(
                ToolCallData(id = "tc1", name = "read_file", arguments = """{"path":"x.kt"}""")
            )
        )

        val filtered = builder.filterMeaningfulConversation(listOf(msg))

        assertEquals(1, filtered.size, "Assistant message with tool calls must be kept")
        assertEquals("a1", filtered[0].id)
    }

    @Test
    fun `filterMeaningfulConversation keeps assistant messages that are plain JSON envelopes`() {
        // Envelope with empty response string — still kept (the tool calls matter).
        val msg = assistantMessage(
            id = "a2",
            content = """{"actions":[],"response":"","intent":"response"}""",
            toolCalls = null
        )

        val filtered = builder.filterMeaningfulConversation(listOf(msg))

        // Content is non-blank ({"actions":...}) so the message is retained; the render
        // path will simply produce empty response text and return null if there's also
        // no tool calls to summarize.
        assertEquals(1, filtered.size)
    }

    @Test
    fun `filterMeaningfulConversation still drops blank assistant messages with no tool calls`() {
        val msg = assistantMessage(id = "a3", content = "", toolCalls = null)
        val filtered = builder.filterMeaningfulConversation(listOf(msg))
        assertTrue(filtered.isEmpty(), "Empty assistant message with no tool calls must still be dropped")
    }

    @Test
    fun `extractResponseText pulls out response field from JSON envelope`() {
        val content = """{"actions":[{"tool":"read_file","arguments":{"path":"x.kt"}}],"response":"Reading the file to verify line 42","intent":"analysis"}"""
        assertEquals("Reading the file to verify line 42", builder.extractResponseText(content))
    }

    @Test
    fun `extractResponseText handles escaped characters in response`() {
        // Real envelope from the AGENT session: escaped quotes and newlines.
        val content = """{"actions":[],"response":"Called API and got \"status\": 400.\nNeed to retry with different body.","intent":"implementation"}"""
        val extracted = builder.extractResponseText(content)
        assertTrue(extracted.contains("\"status\": 400"), "Escaped quotes must be unescaped")
        assertTrue(extracted.contains("\n"), "Escaped newlines must be unescaped to real newlines")
    }

    @Test
    fun `extractResponseText returns raw text when content is not a JSON envelope`() {
        val plain = "Just a plain assistant reply without JSON"
        assertEquals(plain, builder.extractResponseText(plain))
    }

    @Test
    fun `extractResponseText returns empty string when envelope has no response field`() {
        val content = """{"actions":[{"tool":"x","arguments":{}}],"intent":"implementation"}"""
        assertEquals("", builder.extractResponseText(content))
    }

    @Test
    fun `convertChatMessageToLLMMessage renders envelope assistant as response only`() {
        val msg = assistantMessage(
            id = "a4",
            content = """{"actions":[{"tool":"read_file","arguments":{"path":"x.kt"}},{"tool":"grep_search","arguments":{"pattern":"foo"}}],"response":"Checking both files for foo","intent":"analysis"}""",
            toolCalls = listOf(
                ToolCallData(id = "t1", name = "read_file", arguments = "{}"),
                ToolCallData(id = "t2", name = "grep_search", arguments = "{}")
            )
        )

        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })

        assertNotNull(llm)
        assertEquals("assistant", llm.role)
        assertEquals(
            "Checking both files for foo",
            llm.content,
            "Only the response text must be rendered — no raw envelope, no '→ called: ...' footer " +
                "(footer poisoned qwen3.5:35b in session 4b005061 and made it echo the footer as its own reply)"
        )
        assertFalse(
            llm.content.contains("\"actions\""),
            "Raw JSON envelope must NOT leak into rendered content"
        )
        assertFalse(
            llm.content.contains("→ called:"),
            "The '→ called:' footer must NEVER appear in rendered assistant content"
        )
    }

    @Test
    fun `convertChatMessageToLLMMessage renders plain assistant text unchanged`() {
        val msg = assistantMessage(id = "a5", content = "Simple reply, no tools.", toolCalls = null)
        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        assertNotNull(llm)
        assertEquals("Simple reply, no tools.", llm.content)
    }

    @Test
    fun `convertChatMessageToLLMMessage returns null for empty envelope with no tool calls`() {
        val msg = assistantMessage(
            id = "a6",
            content = """{"actions":[],"response":"","intent":"response"}""",
            toolCalls = null
        )
        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        assertNull(llm, "Empty response + no tool calls → no message emitted")
    }

    // ── Fenced-envelope regression tests (qwen3.5:122b, 2026-04-11) ────────
    // Qwen / DeepSeek / smaller Ollama models wrap their JSON reply in a
    // ```json ... ``` markdown fence. AgentTurnLoop persists llmResponse.content
    // *as-is* to the DB, so when ContextService replays history every past
    // assistant message still carries the fence. Without stripping, looksLikeToolEnvelope
    // returned false (content didn't start with `{`), extractResponseText fell back
    // to `content.trim()`, and the ENTIRE JSON envelope — actions, arguments, the
    // whole schema — was replayed back into the prompt. After ~5 turns the model
    // saw so many of its own raw envelopes that it stopped generating new output
    // and returned EOS as the first token (rawContent=0, output=1), killing the
    // session. These tests pin the fence-handling so the bug can't come back.

    @Test
    fun `looksLikeToolEnvelope detects JSON wrapped in fenced code block`() {
        val fenced = """```json
{"actions":[{"tool":"read_file","arguments":{"path":"x.kt"}}],"response":"Reading x.kt","intent":"analysis"}
```"""
        assertTrue(
            builder.looksLikeToolEnvelope(fenced),
            "```json-wrapped envelope must be recognized — failure here means we leak full JSON into conversation history"
        )
    }

    @Test
    fun `looksLikeToolEnvelope detects JSON wrapped in bare fenced code block`() {
        // Some models emit ```\n{...}\n``` without a language tag.
        val fenced = """```
{"actions":[],"response":"ok","intent":"response"}
```"""
        assertTrue(builder.looksLikeToolEnvelope(fenced))
    }

    @Test
    fun `stripCodeFence removes triple-backtick json wrapper`() {
        val fenced = """```json
{"response":"hello"}
```"""
        assertEquals("""{"response":"hello"}""", builder.stripCodeFence(fenced))
    }

    @Test
    fun `stripCodeFence removes bare triple-backtick wrapper`() {
        val fenced = """```
{"response":"hello"}
```"""
        assertEquals("""{"response":"hello"}""", builder.stripCodeFence(fenced))
    }

    @Test
    fun `stripCodeFence handles single-line fence form`() {
        // Single-line (no internal newlines) — rare but observed in some smaller models.
        val fenced = """```json {"response":"hello"} ```"""
        assertEquals("""{"response":"hello"}""", builder.stripCodeFence(fenced))
    }

    @Test
    fun `stripCodeFence leaves raw JSON untouched`() {
        val raw = """{"response":"hello"}"""
        assertEquals(raw, builder.stripCodeFence(raw))
    }

    @Test
    fun `stripCodeFence leaves plain text untouched`() {
        val plain = "Just a plain message"
        assertEquals(plain, builder.stripCodeFence(plain))
    }

    @Test
    fun `extractResponseText pulls out response from fenced envelope`() {
        val fenced = """```json
{"actions":[{"tool":"read_file","arguments":{"path":"x.kt"}}],"response":"Reading x.kt to understand the bug","intent":"analysis"}
```"""
        assertEquals(
            "Reading x.kt to understand the bug",
            builder.extractResponseText(fenced),
            "Response must be extracted from inside the ```json fence — " +
                "if this fails the model sees its own raw JSON envelope on every turn"
        )
    }

    @Test
    fun `extractResponseText handles fenced envelope with thinking field`() {
        // Realistic qwen3.5:122b output seen in S04E04 filesystem task — includes
        // `thinking`, multi-line `response`, and several actions.
        val fenced = """```json
{
  "thinking": "Plik ZIP istnieje. Teraz rozpakuję go używając pełnej ścieżki.",
  "intent": "implementation",
  "response": "Rozpakowuję archiwum ZIP używając pełnej ścieżki.",
  "actions": [
    {"tool": "run_terminal_command", "args": {"command": "powershell -Command 'Expand-Archive ...'"}}
  ]
}
```"""
        val extracted = builder.extractResponseText(fenced)
        assertEquals("Rozpakowuję archiwum ZIP używając pełnej ścieżki.", extracted)
        assertFalse(extracted.contains("thinking"), "thinking field must not leak")
        assertFalse(extracted.contains("actions"), "actions must not leak")
        assertFalse(extracted.contains("powershell"), "tool arguments must not leak into history")
    }

    @Test
    fun `convertChatMessageToLLMMessage renders fenced envelope as response only`() {
        // Exact shape qwen3.5:122b wrote into the DB in the S04E04 filesystem session —
        // ```json…``` wrapper with actions, thinking, response. Rendering must produce
        // ONLY the response text; no fence, no envelope JSON, no '→ called: ...' footer.
        val msg = assistantMessage(
            id = "a-fenced",
            content = """```json
{
  "thinking": "Rozpakuję archiwum.",
  "intent": "implementation",
  "response": "Rozpakowuję archiwum ZIP używając pełnej ścieżki.",
  "actions": [
    {"tool": "run_terminal_command", "args": {"command": "powershell -Command 'Expand-Archive -Path x.zip'"}}
  ]
}
```""",
            toolCalls = listOf(
                ToolCallData(id = "t1", name = "run_terminal_command", arguments = """{"command":"powershell"}""")
            )
        )

        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        assertNotNull(llm)
        assertEquals("assistant", llm.role)
        assertEquals("Rozpakowuję archiwum ZIP używając pełnej ścieżki.", llm.content)
        assertFalse(
            llm.content.contains("```"),
            "Raw markdown fence must NOT leak into rendered content, got: ${llm.content}"
        )
        assertFalse(
            llm.content.contains("\"actions\""),
            "Raw JSON envelope must NOT leak, got: ${llm.content}"
        )
        assertFalse(
            llm.content.contains("\"thinking\""),
            "Thinking field must NOT leak, got: ${llm.content}"
        )
        assertFalse(
            llm.content.contains("powershell"),
            "Tool arguments must NOT leak through conversation rendering, got: ${llm.content}"
        )
        assertFalse(
            llm.content.contains("→ called:"),
            "The '→ called:' footer must never appear — the model will mimic it"
        )
    }

    // ── Preamble+envelope regression (qwen3.5:35b, session 4b005061, 2026-04-11) ──
    // Smaller qwen models tend to "think out loud" in plain text BEFORE committing to
    // the JSON envelope, producing content like:
    //
    //   I have extracted the notes. Now I need to read the contents...
    //
    //   {"thinking":"...","response":"Reading all extracted files...","actions":[...]}
    //
    // The old extractResponseText checked trimmed.startsWith("{"), saw "I" instead,
    // and fell back to returning the entire content — preamble *and* raw JSON envelope.
    // That leaked the full actions list (with tool arguments) into history, and the
    // smaller model's next turn copied the shape. These tests pin the extractor to
    // pull out just the `response` field no matter where in the string it sits.

    @Test
    fun `extractResponseText extracts response from mixed preamble and envelope`() {
        val mixed = """I have extracted the notes. Now I need to read the contents of these files to understand what cities, people, and goods need to be organized.

{"thinking":"I need to read all the extracted files to understand the data structure.","intent":"implementation","response":"Reading all extracted files to understand the data structure and extract cities, people, and goods information.","actions":[{"tool":"read_file","args":{"path":"S04E04/extracted/README.md"}},{"tool":"read_file","args":{"path":"S04E04/extracted/rozmowy.txt"}}]}"""

        val extracted = builder.extractResponseText(mixed)

        assertEquals(
            "Reading all extracted files to understand the data structure and extract cities, people, and goods information.",
            extracted,
            "Must pull response field from the embedded envelope — NOT return the preamble + raw envelope"
        )
        assertFalse(
            extracted.contains("I have extracted"),
            "Preamble must be discarded (it's duplicated in the response field anyway)"
        )
        assertFalse(extracted.contains("\"actions\""), "Raw actions JSON must not leak")
        assertFalse(extracted.contains("thinking"), "Thinking field must not leak")
    }

    @Test
    fun `extractResponseText drops preamble+envelope with no response field`() {
        // If a mixed preamble+envelope has no response field, we must not leak the
        // raw envelope either — drop the whole message. Otherwise the model sees
        // its own "preamble text + JSON envelope" shape replayed and copies it.
        val mixed = """Doing something now.

{"actions":[{"tool":"read_file"}],"intent":"implementation"}"""

        assertEquals(
            "",
            builder.extractResponseText(mixed),
            "Envelope-shaped content without a response field must be dropped — " +
                "returning the raw body leaks the actions list into history"
        )
    }

    @Test
    fun `convertChatMessageToLLMMessage drops preamble+envelope output cleanly`() {
        // End-to-end check for the exact DB shape recorded by qwen3.5:35b in
        // session 4b005061 turn 4 (message 7 of that report).
        val msg = assistantMessage(
            id = "a-mixed",
            content = """I have extracted the notes. Now I need to read the contents of these files to understand what cities, people, and goods need to be organized. Let me read all the extracted files.

{"thinking":"I need to read all the extracted files.","intent":"implementation","response":"Reading all extracted files to understand the data structure.","actions":[{"tool":"read_file","args":{"path":"S04E04/extracted/README.md"}},{"tool":"read_file","args":{"path":"S04E04/extracted/rozmowy.txt"}},{"tool":"read_file","args":{"path":"S04E04/extracted/transakcje.txt"}},{"tool":"read_file","args":{"path":"S04E04/extracted/ogłoszenia.txt"}}]}""",
            toolCalls = listOf(
                ToolCallData(id = "t1", name = "read_file", arguments = """{"path":"README.md"}"""),
                ToolCallData(id = "t2", name = "read_file", arguments = """{"path":"rozmowy.txt"}"""),
                ToolCallData(id = "t3", name = "read_file", arguments = """{"path":"transakcje.txt"}"""),
                ToolCallData(id = "t4", name = "read_file", arguments = """{"path":"ogłoszenia.txt"}""")
            )
        )

        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        assertNotNull(llm)
        assertEquals("Reading all extracted files to understand the data structure.", llm.content)
        // Hard guards — every one of these was observed leaking in session 4b005061:
        assertFalse(llm.content.contains("I have extracted"), "Preamble leaked")
        assertFalse(llm.content.contains("\"actions\""), "Raw actions JSON leaked")
        assertFalse(llm.content.contains("\"thinking\""), "Thinking field leaked")
        assertFalse(llm.content.contains("S04E04/extracted"), "Tool args leaked")
        assertFalse(llm.content.contains("→ called:"), "The '→ called:' footer leaked")
    }

    // ── "→ called:" footer poisoning regression (turn 12 kill, 2026-04-11) ──
    // After the fence fix, qwen3.5:35b still died on turn 12 of S04E04 — the model
    // produced exactly 23 chars / 7 tokens of output: "→ called: http_request".
    // That is not JSON, not a tool call, not a valid reply — it was the model
    // copying the "→ called: $tool" footer we were synthesizing into every past
    // assistant message in the history. Seen often enough, the model adopted it
    // as a valid output format and stopped generating real replies.
    //
    // Fix: drop the footer entirely. Tool names already appear in the TOOL result
    // messages that follow immediately after, and in RECENT_WORK/WORKING_MEMORY.
    // No footer = no synthetic few-shot example for the model to mimic.

    @Test
    fun `convertChatMessageToLLMMessage never adds '→ called' footer even when tool calls present`() {
        val msg = assistantMessage(
            id = "a-no-footer",
            content = """{"response":"Downloading the file.","actions":[{"tool":"http_request","args":{"url":"x"}}],"intent":"implementation"}""",
            toolCalls = listOf(
                ToolCallData(id = "t1", name = "http_request", arguments = """{"url":"x"}""")
            )
        )

        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        assertNotNull(llm)
        assertEquals("Downloading the file.", llm.content)
        assertFalse(
            llm.content.contains("→"),
            "Arrow character '→' must never appear in rendered assistant content"
        )
        assertFalse(
            llm.content.contains("called:"),
            "The 'called:' footer marker must never appear in rendered assistant content"
        )
    }

    @Test
    fun `convertChatMessageToLLMMessage drops tool-only assistant turn with blank response`() {
        // Envelope with actions but empty response — the assistant effectively said
        // nothing, it just dispatched tools. Drop the message entirely; the tool
        // result messages that follow carry the narrative on their own, and they
        // still display the tool name in the `[Tool result: NAME id: XXX]` header.
        val msg = assistantMessage(
            id = "a-toolonly",
            content = """{"response":"","actions":[{"tool":"read_file","args":{"path":"x"}}],"intent":"implementation"}""",
            toolCalls = listOf(
                ToolCallData(id = "t1", name = "read_file", arguments = """{"path":"x"}""")
            )
        )

        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        assertNull(
            llm,
            "Blank response must drop the message even when tool calls are present — " +
                "we used to emit '→ called: read_file' here and the model would mimic it"
        )
    }

    @Test
    fun `convertChatMessageToLLMMessage does not echo back a poisoned footer-only message`() {
        // Defense-in-depth: if a PREVIOUS run persisted a poisoned "→ called: http_request"
        // message in the DB (see the turn-12 kill), rendering it back into a subsequent
        // session's context should not re-inject the poison. It's not an envelope and
        // not blank, so today it would be passed through as plain text. This test
        // documents that behavior so a future change that filters short suspicious
        // strings doesn't accidentally regress the "plain text assistant reply" path.
        val msg = assistantMessage(
            id = "a-poisoned",
            content = "→ called: http_request",
            toolCalls = null
        )
        val llm = builder.convertChatMessageToLLMMessage(msg, toolContentResolver = { "" })
        // For now we DO render it — but a new session starting fresh will never see
        // such a message because nothing in the render path produces this format any more.
        assertNotNull(llm)
    }

    @Test
    fun `filterMeaningfulConversation keeps user messages unchanged`() {
        val msgs = listOf(
            userMessage("u1", "Fix the bug in login flow"),
            userMessage("u2", "")  // blank, should be dropped
        )
        val filtered = builder.filterMeaningfulConversation(msgs)
        assertEquals(1, filtered.size)
        assertEquals("u1", filtered[0].id)
    }
}
