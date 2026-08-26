package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMToolCall
import pl.jclab.refio.core.llm.LLMMessageMapper

/**
 * Stateless helper that slices, filters and converts persisted [ChatMessage] lists
 * into [LLMMessage] sequences suitable for an LLM turn.
 *
 * Extracted from [pl.jclab.refio.core.services.ContextService].
 */
class ConversationContextBuilder {

    companion object {
        const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"
    }

    // ── public API ────────────────────────────────────────────────────────

    /**
     * Filter conversation history to keep only meaningful exchanges.
     * Removes system messages, tool usage notifications, and very short messages.
     * Based on Python context_service.py lines 966-990
     */
    fun filterMeaningfulConversation(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        return messages.filter { msg ->
            when (msg.role) {
                MessageRole.USER -> msg.content.isNotBlank()
                MessageRole.ASSISTANT -> {
                    // KEEP assistant messages that have EITHER meaningful content OR tool calls.
                    // Previously this branch required `toolCalls.isNullOrEmpty()` and rejected
                    // any JSON envelope content, which in AGENT mode filtered out practically
                    // EVERY assistant message — the model lost memory of its own decisions
                    // between turns and kept oscillating on the same problem.
                    //
                    // We now keep the message and rely on [convertChatMessageToLLMMessage] to
                    // render it compactly: the `response` field from the JSON envelope (or the
                    // raw text if no envelope) + a one-line "called: tool1, tool2" summary.
                    msg.content.isNotBlank() || !msg.toolCalls.isNullOrEmpty()
                }
                MessageRole.TOOL -> {
                    msg.content.isNotBlank() || !msg.rawOutput.isNullOrBlank()
                }
                MessageRole.SYSTEM -> {
                    isConversationSummary(msg) ||
                        msg.metadata == "compaction" ||
                        msg.content.contains("<conversation_summary>") ||
                        msg.content.contains("<parent_working_memory>")
                }
            }
        }
    }

    fun sliceConversationHistoryFromLastSummary(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        val lastSummaryIndex = messages.indexOfLast { isConversationSummary(it) }
        if (lastSummaryIndex < 2) return messages

        val tail = messages.drop(lastSummaryIndex - 1)
        val droppedHead = messages.take(lastSummaryIndex - 1)

        // Always preserve the very first message (usually the user's opening
        // request) and every USER message that appeared before the summary —
        // they may carry instructions, constraints, or facts the model still
        // needs, and they are small enough that duplicating them next to the
        // summary is cheap.
        val preservedHead = droppedHead.filterIndexed { index, msg ->
            index == 0 || msg.role == MessageRole.USER
        }

        return preservedHead + tail
    }

    /**
     * Build a lookup of tool call id → tool name by scanning ASSISTANT messages.
     * Used so TOOL result messages can be rendered with the originating tool's
     * name (e.g. `[Tool result: run_code id: abc]`) instead of a bare id.
     */
    fun buildToolNameByCallId(messages: List<ChatMessage>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (msg in messages) {
            if (msg.role != MessageRole.ASSISTANT) continue
            val calls = msg.toolCalls ?: continue
            for (call in calls) {
                if (call.id.isNotBlank() && call.name.isNotBlank()) {
                    map[call.id] = call.name
                }
            }
        }
        return map
    }

    fun isConversationSummary(message: ChatMessage): Boolean {
        val metadata = message.metadata ?: return false
        return metadata.contains("\"type\":\"$CONVERSATION_SUMMARY_METADATA_TYPE\"")
    }

    fun looksLikeToolEnvelope(content: String): Boolean {
        val trimmed = stripCodeFence(content)
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false

        return trimmed.contains("\"actions\"") ||
            trimmed.contains("\"tool_calls\"") ||
            trimmed.contains("\"subtasks\"") ||
            trimmed.contains("\"intent\"")
    }

    /**
     * Strip a leading/trailing markdown code fence from `content`.
     *
     * Qwen / DeepSeek / many smaller Ollama models wrap their JSON envelope in a
     * ```json ... ``` (or bare ``` ... ```) code block. The raw `llmResponse.content`
     * is persisted as-is in the DB (see `AgentTurnLoop.kt:816`), so when we reassemble
     * past assistant turns into conversation history we see the fence too.
     *
     * Without stripping, [looksLikeToolEnvelope] returns false (content does not start
     * with `{`), [extractResponseText] bails out to `content.trim()`, and we leak the
     * entire JSON envelope — actions array, arguments, system schema echoes, everything —
     * back into every subsequent prompt. The model then sees its own raw JSON replies
     * 6–10 turns deep, gets confused about whether it has already replied, and (observed
     * with qwen3.5:122b) starts returning an empty `content` + EOS as the very first
     * token on turn 6+.
     *
     * Stripping is intentionally conservative:
     *   - only touches leading `` ``` `` + optional language tag (`` ```json `` / `` ```JSON ``)
     *     and trailing `` ``` ``;
     *   - leaves raw `{...}` (no fence) untouched;
     *   - if the opening/closing fences don't match cleanly, returns the original
     *     content — we never want to silently corrupt a message.
     */
    fun stripCodeFence(content: String): String {
        val raw = content.trim()
        if (!raw.startsWith("```")) return raw

        // Skip the opening fence (``` + optional language tag up to the first newline
        // OR the first whitespace, for single-line forms).
        val afterTripleTick = raw.substring(3)
        val firstNewline = afterTripleTick.indexOf('\n')
        val body = if (firstNewline >= 0) {
            // Fence followed by an optional language tag and a newline:
            //   ```json\n{...}\n```
            //   ```\n{...}\n```
            afterTripleTick.substring(firstNewline + 1)
        } else {
            // Single-line fence form: ```json {...} ``` — skip the language-tag word
            // (leading run of non-whitespace chars before the first space) if one is
            // present. When there is no language tag (``` {...} ``` / ```{...}```), the
            // substring is kept intact and the caller's closing-fence scan trims the
            // trailing ``` .
            val firstWhitespace = afterTripleTick.indexOfFirst { it.isWhitespace() }
            when {
                // No whitespace at all (```{...}``` with no leading tag) — keep as-is.
                firstWhitespace < 0 -> afterTripleTick
                // Whitespace immediately after the fence ⇒ no language tag.
                firstWhitespace == 0 -> afterTripleTick.trimStart()
                // Leading non-whitespace run = language tag; skip it.
                else -> afterTripleTick.substring(firstWhitespace).trimStart()
            }
        }

        val closing = body.lastIndexOf("```")
        val inner = if (closing >= 0) body.substring(0, closing) else body
        return inner.trim()
    }

    /**
     * Extract the user-visible text from an assistant message content field.
     *
     * In AGENT mode the model emits a JSON envelope like
     * `{"actions":[...],"response":"...","intent":"..."}`. Only the `response` field is
     * worth keeping in conversation history — the `actions` are duplicated in
     * RECENT_WORK / WORKING_MEMORY, and the `intent` is workflow metadata.
     *
     * This method is deliberately permissive about **where** the envelope sits in the
     * content. Smaller Ollama models (qwen3.5:35b, qwen3.5:122b) routinely emit:
     *
     *   - a pure envelope:                   `{"thinking":"…","response":"…","actions":[…]}`
     *   - a fenced envelope:                 ```json\n{…}\n```
     *   - a **preamble + envelope** blob:    `I have extracted the notes...\n\n{"response":"…","actions":[…]}`
     *
     * The third shape was the killer regression in session 4b005061 (qwen3.5:35b, S04E04
     * filesystem task): the old code saw that the content did not start with `{` and fell
     * back to returning the raw string, which leaked the full envelope — actions list,
     * arguments, thinking field, everything — into every subsequent prompt. That trained
     * the smaller model to emit the same preamble+envelope shape itself until, ten turns
     * later, it gave up and echoed back a footer line we had added to the render.
     *
     * So: we search for `"response"` **anywhere** in the stripped content. If a quoted
     * string value follows, that's the response — return it unescaped. Otherwise return
     * the stripped content as-is (plain text assistant reply with no envelope at all).
     *
     * Kept as a lightweight string scanner (not a full JSON parser) because this runs on
     * every ASSISTANT message in every turn's context build, and `response` values are
     * single-level escaped strings that naive unescaping of `\"` / `\n` / `\\` handles
     * in every realistic case — we do not need Gson here.
     */
    fun extractResponseText(content: String): String {
        val stripped = stripCodeFence(content)
        val extracted = findResponseFieldValue(stripped)
        if (extracted != null) return extracted.trim()
        // No `"response":"..."` pattern. If the content still carries envelope markers
        // (pure envelope with no response field, or preamble+envelope where the envelope
        // has no response), drop the whole thing rather than leak the raw actions list
        // into conversation history. This is exactly what the preamble+envelope shape
        // was doing before — see the class-level comment on extractResponseText.
        if (containsEnvelopeMarkers(stripped)) return ""
        return stripped
    }

    /**
     * Heuristic: does [content] look like it contains a JSON envelope (anywhere inside)?
     * Used as a belt-and-braces check so that when [findResponseFieldValue] returns null
     * but the content is obviously an envelope (just without a `response` field), we
     * drop it instead of echoing raw `actions`/`intent`/`tool_calls` JSON back into
     * every subsequent prompt.
     */
    private fun containsEnvelopeMarkers(content: String): Boolean {
        return content.contains("\"actions\"") ||
            content.contains("\"tool_calls\"") ||
            content.contains("\"subtasks\"") ||
            content.contains("\"intent\"")
    }

    /**
     * Scan [content] for a `"response":"…"` pair and return the unescaped string value.
     * Returns null if no such field is found (so the caller can fall back to plain text).
     *
     * Works on pure envelopes, fenced envelopes (already stripped upstream), and mixed
     * preamble+envelope bodies.
     */
    private fun findResponseFieldValue(content: String): String? {
        val keyIndex = content.indexOf("\"response\"")
        if (keyIndex < 0) return null
        var i = keyIndex + "\"response\"".length
        while (i < content.length && (content[i].isWhitespace() || content[i] == ':')) i++
        if (i >= content.length || content[i] != '"') return null
        i++ // consume opening quote

        val sb = StringBuilder()
        while (i < content.length) {
            val c = content[i]
            if (c == '\\' && i + 1 < content.length) {
                when (val next = content[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '/' -> sb.append('/')
                    else -> { sb.append('\\'); sb.append(next) }
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        // Unterminated string — return what we have rather than losing the whole message.
        return sb.toString()
    }

    /**
     * Convert ChatMessage to LLMMessage for AgentTurnLoop.
     * Tool messages always use summarized/compact content.
     *
     * @param toolContentResolver resolves the conversation-ready content for TOOL messages
     *   (kept in ContextService because it is also used outside this builder).
     */
    fun convertChatMessageToLLMMessage(
        msg: ChatMessage,
        toolContentResolver: (ChatMessage) -> String,
        toolNameByCallId: Map<String, String> = emptyMap()
    ): LLMMessage? {
        return when (msg.role) {
            MessageRole.USER -> LLMMessage(
                role = "user",
                content = msg.content
            )

            MessageRole.ASSISTANT -> {
                val responseText = extractResponseText(msg.content)
                // The calls ride in the structured field, never as a text footer: a rendered
                // footer was tried and a model began echoing it back as its own reply. Carrying
                // them keeps a turn that called a tool without saying anything - drop it and the
                // model loses the record of its own action, sees a result it never asked for, and
                // reissues the same call until a loop guard ends the turn.
                val calls = msg.toolCalls.orEmpty()
                    .filter { it.name.isNotBlank() }
                    .map { LLMToolCall(id = it.id, name = it.name, argumentsJson = it.arguments) }
                when {
                    responseText.isNotBlank() -> LLMMessage(
                        role = "assistant",
                        content = responseText,
                        toolCalls = calls,
                    )
                    calls.isNotEmpty() -> LLMMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = calls,
                    )
                    else -> null
                }
            }

            MessageRole.TOOL -> {
                val summarized = toolContentResolver(msg)
                val toolName = msg.toolCallId?.let { toolNameByCallId[it] }
                LLMMessageMapper.fromToolResult(msg, summarized, toolName)
            }

            MessageRole.SYSTEM -> {
                val isSummaryMessage = isConversationSummary(msg) ||
                    msg.metadata == "compaction" ||
                    msg.content.contains("<conversation_summary>")

                if (isSummaryMessage) {
                    LLMMessage(
                        role = "user",
                        content = "[Conversation summary context]\n${msg.content}"
                    )
                } else {
                    LLMMessage(
                        role = "system",
                        content = msg.content
                    )
                }
            }
        }
    }
}
