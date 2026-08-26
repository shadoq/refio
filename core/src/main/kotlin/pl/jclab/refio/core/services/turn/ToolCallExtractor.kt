package pl.jclab.refio.core.services.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.StreamFinishReason
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.base.ToolRegistry
import java.util.UUID

private val logger = dualLogger("ToolCallExtractor")

/** Literal marker that arms the narrow labeled-line tool-call recovery (see `extractLabeledToolLines`). */
private const val LABELED_TOOL_MARKER = "[TOOL]"

/**
 * Which extraction strategy produced a tool call. Carried for telemetry so we can see, per
 * turn, HOW the model expressed its intent (structured native vs text formats) — the input
 * to deciding whether a model's `supportsFunctionCalling` flag is set correctly.
 *
 * `JSON` covers the existing [ToolCallParser] text path (JSON envelope + legacy `TOOL_CALL`);
 * the parser does not distinguish the two internally and the distinction is not operationally
 * useful here.
 */
enum class ToolCallSource { NATIVE, JSON, HERMES, QWEN_CODER_XML, BRACKET_TOOL }

/**
 * Result of unified tool-call extraction. Replaces the previous implicit contract where an
 * empty `List<ToolCallData>` conflated three very different situations (model finished, model
 * failed to follow the contract, blank response). Now every outcome is explicit.
 */
sealed interface ExtractionResult {
    /** At least one tool call was extracted via [source]. */
    data class Calls(val calls: List<ToolCallData>, val source: ToolCallSource) : ExtractionResult

    /** No tool call. [reason] distinguishes a legitimate finish from a failed attempt. */
    data class None(val reason: String) : ExtractionResult
}

/**
 * Single entry point that turns an [LLMResponse] into tool calls regardless of how the model
 * expressed them. Strategies are tried in order; the first that yields calls wins and its
 * `source` is reported. The turn loop no longer branches "native vs JSON" itself — it asks
 * this extractor.
 *
 * Strategy order:
 *  1. `provider-native`  — [LLMResponse.nativeToolCalls] the runtime already parsed.
 *  2. `json` / `legacy`  — existing [ToolCallParser] text extraction (JSON envelope, `TOOL_CALL`).
 *  3. `hermes`           — `<tool_call>{json}</tool_call>` tags (guarded recovery).
 *  4. `qwen-coder-xml`   — `<function=NAME><parameter=KEY>VAL</parameter></function>` (guarded recovery).
 *
 * **Rule 7:** parsing of XML pseudo-tags was deliberately removed once because it
 * masked the real failure (a wrong `supportsFunctionCalling` flag) and encouraged garbage output.
 * The recovery strategies (3, 4) are therefore intentionally NARROW: they run only when the
 * structured native channel was NOT used AND the JSON contract produced nothing, and they accept
 * a call only when its name is a registered tool. Every recovery logs a WARN pointing operators at
 * the model definition — recovery is a safety net, not a license to keep the flag wrong.
 */
class ToolCallExtractor(
    private val parser: ToolCallParser,
    private val toolRegistry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val hermesRegex = Regex("""<tool_call>\s*(\{.*?})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
    private val qwenFunctionRegex = Regex("""<function=([^>\s]+)\s*>(.*?)</function>""", RegexOption.DOT_MATCHES_ALL)
    private val qwenParamRegex = Regex("""<parameter=([^>\s]+)\s*>(.*?)</parameter>""", RegexOption.DOT_MATCHES_ALL)

    // Labeled-line recovery: `[TOOL] name: args` (marker optional after the first line). Anchored per
    // trimmed line so `name:` must lead the line — prose like "Let me examine X" never matches.
    private val labeledLineRegex = Regex("""^(?:\[TOOL]\s*)?([A-Za-z_]\w*)\s*:\s*(.+)$""")
    // Space-form tool line: `name key="value" key2=value2` (NO colon), used by some weak local
    // models (qwen3-coder) that emit a standalone `[TOOL]` marker line then a space-separated call.
    // Armed ONLY when a bare `[TOOL]` marker line is present (see extractLabeledToolLines), so prose
    // `result equals x = y` never false-positives without the explicit marker; the args group must
    // still carry a `key=value` and the name must be a registered tool.
    private val labeledSpaceLineRegex = Regex("""^([A-Za-z_]\w*)\s+(.+=.+)$""")
    // One `key=value` arg: quoted value taken verbatim (group 2, commas/pipes preserved) or a bare
    // value up to the next comma (group 3).
    private val labeledArgRegex = Regex("""([A-Za-z_]\w*)\s*=\s*(?:"([^"]*)"|([^,]+))""")

    fun extract(
        response: LLMResponse,
        contentForExtraction: String,
        mode: TaskMode,
        profileOverrides: TurnProfileOverrides?,
    ): ExtractionResult {
        val native = response.nativeToolCalls

        // 1. Provider-native is AUTHORITATIVE when it carries calls. If the runtime gave us a NON-EMPTY
        //    native channel, trust it completely and do NOT fall through to text parsing — otherwise an
        //    echoed `<tool_call>` tag or a duplicate envelope in the prose would spawn a phantom second
        //    call alongside the real native call. This mirrors the turn loop's native-branch behaviour.
        if (native != null && native.isNotEmpty()) {
            val calls = native.map {
                ToolCallData(id = it.id, name = it.name, arguments = it.argumentsJson)
            }
            return ExtractionResult.Calls(parser.applyProfileFilter(calls, profileOverrides), ToolCallSource.NATIVE)
        }

        // 1b. Native channel active but EMPTY (real adapters return emptyList — NOT null — when native
        //     tools were requested and the model produced 0 native calls). Normally this is
        //     authoritative "the model finished with prose". EXCEPT: some models flagged native-capable
        //     emit the project's canonical {response, actions:[...]} envelope in TEXT instead of using
        //     the channel. Because there is NO native call here, recovering that envelope cannot spawn a
        //     phantom double-call — so we recover it. Recovery is STRICTLY the actions-envelope contract:
        //     tag echoes (`<tool_call>`, `<function=>`) start with `<`, fail the envelope gate, and stay
        //     suppressed (their phantom-echo risk is real and separately tested).
        if (native != null) {
            if (looksLikeActionsEnvelope(contentForExtraction)) {
                val recovered = parser.extractToolCalls(contentForExtraction, mode, profileOverrides)
                if (recovered.isNotEmpty()) {
                    logRecovery("native-empty-json-envelope", recovered, response.model)
                    return ExtractionResult.Calls(recovered, ToolCallSource.JSON)
                }
            }
            return ExtractionResult.None("native-channel-no-calls")
        }

        // --- native == null: the model emitted tool calls (if any) as text. ---

        // 2-3. Tag-wrapped formats first. They only match when their literal markers are present, so
        //      a normal `{actions:[...]}` envelope is untouched and falls through to the parser. Running
        //      them before the parser means a genuine `<tool_call>`/`<function=>` call is reported with
        //      its real source instead of being silently absorbed by the parser's bare-object recovery.
        val hermes = extractHermes(contentForExtraction)
        if (hermes.isNotEmpty()) {
            logRecovery("hermes", hermes, response.model)
            return ExtractionResult.Calls(parser.applyProfileFilter(hermes, profileOverrides), ToolCallSource.HERMES)
        }
        val qwen = extractQwenCoderXml(contentForExtraction)
        if (qwen.isNotEmpty()) {
            logRecovery("qwen-coder-xml", qwen, response.model)
            return ExtractionResult.Calls(parser.applyProfileFilter(qwen, profileOverrides), ToolCallSource.QWEN_CODER_XML)
        }
        val labeled = extractLabeledToolLines(contentForExtraction)
        if (labeled.isNotEmpty()) {
            logRecovery("bracket-tool", labeled, response.model)
            return ExtractionResult.Calls(parser.applyProfileFilter(labeled, profileOverrides), ToolCallSource.BRACKET_TOOL)
        }

        // 4. JSON-in-text envelope + legacy TOOL_CALL (existing parser; already profile-filtered).
        val parsed = parser.extractToolCalls(contentForExtraction, mode, profileOverrides)
        if (parsed.isNotEmpty()) {
            return ExtractionResult.Calls(parsed, ToolCallSource.JSON)
        }

        // 5. Truncation: the model hit the output-token cap mid-envelope (`finishReason=length`) and
        //    left an unparseable, unclosed JSON object. This is a distinct, actionable failure — not a
        //    "model finished in prose" — so it gets its own reason. Detecting it HERE (instead of a
        //    parallel guard in AgentTurnLoop) keeps the native-vs-text envelope inspection in one place.
        //    The native path never reaches this point (it returned above),
        //    matching the turn loop's long-standing `!usedNativeChannel` precondition.
        // A stream cut off mid-answer leaves the same unclosed envelope as an exhausted output
        // budget, so both reach this branch.
        if (response.finishReason == "length" || response.finishReason == StreamFinishReason.TRUNCATED) {
            val envelope = parser.inspectJsonEnvelope(contentForExtraction)
            if (envelope.hasJsonEnvelope && !envelope.isComplete) {
                return ExtractionResult.None("incomplete-json-truncated")
            }
        }

        // No tool call. Report WHY explicitly — never silently treat a failed attempt as "finished".
        val reason = when {
            contentForExtraction.isBlank() -> "blank-content"
            looksLikeAttemptedToolCall(contentForExtraction) -> "attempted-toolcall-unparseable"
            else -> "final-text-no-toolcall"                     // normal final answer
        }
        if (reason == "attempted-toolcall-unparseable") {
            // A weak model (session 79abb6e5) emits prose that *wants*
            // to call a tool but matches no contract, and the loop used to finish silently on it.
            logger.warn {
                "[TOOLCALL] all strategies failed but content looks like an attempted tool call " +
                    "(finishReason=${response.finishReason}, len=${contentForExtraction.length}, model=${response.model}) " +
                    "— model follows no tool-call contract; verify its supportsFunctionCalling flag."
            }
        }
        return ExtractionResult.None(reason)
    }

    /** Hermes convention: `<tool_call>{"name":..,"arguments":{..}}</tool_call>`, JSON inside tags. */
    private fun extractHermes(content: String): List<ToolCallData> {
        if (!content.contains("<tool_call", ignoreCase = true)) return emptyList()
        val calls = mutableListOf<ToolCallData>()
        for (match in hermesRegex.findAll(content)) {
            val obj = runCatching { json.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull() ?: continue
            val name = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: continue
            if (!toolRegistry.hasTool(name)) continue
            val argsElement = obj["arguments"] ?: obj["parameters"]
            val argumentsJson = when (argsElement) {
                is JsonObject -> argsElement.toString()
                is JsonPrimitive -> if (argsElement.isString) argsElement.content else argsElement.toString()
                null -> "{}"
                else -> argsElement.toString()
            }
            calls.add(ToolCallData(id = UUID.randomUUID().toString(), name = name, arguments = argumentsJson))
        }
        return calls
    }

    /**
     * Ad-hoc labeled-line convention some weak local models fall back to when they ignore both the
     * native channel and the JSON contract (session 6a1534a9, qwen3.5:35b): one tool per line as
     * `[TOOL] name: key="value", key2=value2`, the first line carrying a literal `[TOOL]` marker.
     *
     * Deliberately NARROW (Rule 7): recovery arms ONLY when the literal `[TOOL]`
     * marker actually PREFIXES a tool-shaped line (`[TOOL] name: args`) — a bare `Field: a=b` prose
     * line never false-positives, even alongside an unrelated `[TOOL]` mention elsewhere in the prose.
     * A line is accepted only when its name is a registered tool, and only when it actually carries
     * `key=value` arguments. Values are captured as strings — matching the Qwen-Coder XML strategy —
     * with quoted values taken verbatim (commas/pipes inside quotes survive) and bare values trimmed.
     */
    private fun extractLabeledToolLines(content: String): List<ToolCallData> {
        val lines = content.lineSequence().map { it.trim() }.toList()
        // Shape A (documented): the marker genuinely prefixes a colon-form tool line, `[TOOL] name: args`.
        // A bare `content.contains("[TOOL]")` armed on any prose mention of the marker, after which every
        // `registeredTool: key=value` prose line false-positived into a spurious call.
        val armedColon = lines.any { line ->
            line.startsWith(LABELED_TOOL_MARKER, ignoreCase = true) && labeledLineRegex.matches(line)
        }
        // Shape B (qwen3-coder): a STANDALONE `[TOOL]` marker line, then space-form `name key="value"`
        // tool lines (no colon). Armed only by the bare marker line so `result equals x = y` prose never
        // false-positives; each candidate still needs a registered tool name and parseable key=value args.
        val armedSpace = lines.any { it.equals(LABELED_TOOL_MARKER, ignoreCase = true) }
        if (!armedColon && !armedSpace) return emptyList()
        val calls = mutableListOf<ToolCallData>()
        val seen = HashSet<String>()
        for (line in lines) {
            // Shape A: colon form (marker optional after the first line).
            labeledLineRegex.find(line)?.let { addLabeledCall(it.groupValues[1], it.groupValues[2], calls, seen) }
            // Shape B: space form, but never the marker line itself, and only when the marker armed it.
            if (armedSpace && !line.startsWith(LABELED_TOOL_MARKER, ignoreCase = true)) {
                labeledSpaceLineRegex.find(line)?.let { addLabeledCall(it.groupValues[1], it.groupValues[2], calls, seen) }
            }
        }
        return calls
    }

    /** Add one recovered labeled-line call if [name] is a registered tool with parseable key=value args. */
    private fun addLabeledCall(name: String, argStr: String, calls: MutableList<ToolCallData>, seen: MutableSet<String>) {
        if (!toolRegistry.hasTool(name)) return
        val argMatches = labeledArgRegex.findAll(argStr).toList()
        if (argMatches.isEmpty()) return
        val argsObject = buildJsonObject {
            for (arg in argMatches) {
                put(arg.groupValues[1], arg.groups[2]?.value ?: arg.groupValues[3].trim())
            }
        }
        // Dedup so a line that matches both shapes (it cannot, but defensively) is not double-counted.
        if (seen.add("$name:$argsObject")) {
            calls.add(ToolCallData(id = UUID.randomUUID().toString(), name = name, arguments = argsObject.toString()))
        }
    }

    /** Qwen3-Coder convention: `<function=NAME><parameter=KEY>VALUE</parameter>...</function>`, no JSON. */
    private fun extractQwenCoderXml(content: String): List<ToolCallData> {
        if (!content.contains("<function=", ignoreCase = true)) return emptyList()
        val calls = mutableListOf<ToolCallData>()
        for (fn in qwenFunctionRegex.findAll(content)) {
            val name = fn.groupValues[1].trim()
            if (name.isBlank() || !toolRegistry.hasTool(name)) continue
            val body = fn.groupValues[2]
            val argsObject = buildJsonObject {
                for (param in qwenParamRegex.findAll(body)) {
                    put(param.groupValues[1].trim(), param.groupValues[2].trim())
                }
            }
            calls.add(ToolCallData(id = UUID.randomUUID().toString(), name = name, arguments = argsObject.toString()))
        }
        return calls
    }

    /**
     * Strict detector for the project's canonical `{response/tool, actions:[...]}` JSON-in-text
     * tool-call contract. Mirrors `AgentTurnLoop.isJsonEnvelopeFallback` (kept in sync). Deliberately
     * narrow: it gates the empty-native-channel recovery above so a tag echo (`<tool_call>{...}` —
     * which starts with `<`, not `{`/```` ``` ````) is NOT resurrected as a phantom call.
     */
    private fun looksLikeActionsEnvelope(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) return false
        val body = if (trimmed.startsWith("```")) trimmed.removePrefix("```").removePrefix("json").trim() else trimmed
        return body.contains("\"actions\"") && (body.contains("\"tool\"") || body.contains("\"response\""))
    }

    private fun looksLikeAttemptedToolCall(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        return trimmed.contains("<tool_call", ignoreCase = true) ||
            trimmed.contains("<function=", ignoreCase = true) ||
            trimmed.contains(LABELED_TOOL_MARKER, ignoreCase = true) ||
            trimmed.contains("\"tool_calls\"") ||
            (trimmed.contains("\"name\"") && (trimmed.contains("\"arguments\"") || trimmed.contains("\"parameters\"")))
    }

    private fun logRecovery(strategy: String, calls: List<ToolCallData>, model: String) {
        logger.warn {
            "[TOOLCALL_RECOVERED] strategy=$strategy recovered ${calls.size} call(s) " +
                "[${calls.joinToString(",") { it.name }}] from text (model=$model) — model bypassed both the native " +
                "and JSON contracts; consider fixing its supportsFunctionCalling flag."
        }
    }
}
