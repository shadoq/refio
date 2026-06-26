# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- Headless CLI self-testing surface (`cli/main.kt`) - scriptable flags for driving a turn without the GUI: `--output json` + `--output-file` write a `run.json` with per-turn metrics (tokens/cost/iterations/status) via `SessionDebugExporter`; `--debug-level minimal|standard|full|judge`; `--config k=v` (repeatable) / `--config-file` run-scope overrides; `--print-config` (dry, no LLM call, writes nothing); `--max-cost <usd>` hard per-session ceiling; `--auto-approve "<regex>"` approval gate (auto-approve matching tool commands, reject the rest); `--no-egress`. Documented in `CLAUDE.md` under "Headless CLI Self-Testing", with a consent rule - the agent proposes a command and waits for approval before running a turn.
- `--verbose` / `-v` headless flag + `HeadlessTurnListener` - turn/tool events are mirrored to `~/.refio/refio-cli.log` and stderr (`▶ turn started`, `→ tool`, `✓/✗ tool`, `■ turn complete`), so a headless run isn't a black box; `-v` additionally streams live LLM token deltas to stderr. stdout stays reserved for the `run.json` document.
- Unified tool-call extraction (`ToolCallExtractor`) - one `extract()` returns `ExtractionResult.Calls(source)` / `None(reason)` with `ToolCallSource ∈ {NATIVE, JSON, HERMES, QWEN_CODER_XML}`, replacing the three-way `if (nativeCalls != null) … else if … else` native↔JSON branch in `AgentTurnLoop`. Provider-native results are authoritative; when a model has no native channel, guarded recovery parses Hermes `<tool_call>{json}</tool_call>` and Qwen3-Coder `<function=><parameter=>` text - but only when native was unused **and** the name is a registered tool, so it can't resurrect garbage. No more silent `emptyList()`: every non-extraction logs an explicit `reason` + WARN, closing the failure where a weak local model returned prose in PLAN and the loop finished early (session `79abb6e5`). `ToolCallExtractorTest` (10 cases) locks each format and native≡JSON equivalence.
- Native function calling extended to the **OpenAI-compatible adapters** (OpenRouter, Z.AI, Generic OpenAI, LM Studio) via a shared `OpenAICompatibleAdapter`, and to **Gemini** - each builds a tools array and parses tool calls back (including end-of-stream accumulation), joining the existing Ollama / OpenAI / Anthropic paths.
- Persistent native-tools fallback - `NativeToolsFallbackTracker` records a model that failed the native-tools probe and persists the set to `models.native_tools_fallbacks`, so the fallback survives a process restart instead of re-probing every run. On `ToolsNotSupportedException` the turn marks the model, rebuilds the prompt without native tools, and retries on the JSON path.
- `ToolSchemaSanitizer` - per-provider tool-schema normalization (`forOpenAI` strict-mode handling, `forAnthropic` strips `oneOf`/`allOf`/`anyOf`, `forGemini` upper-cases types + nullable unions) so the same registered tool schema is accepted by every provider.
- `tools.native_tools` config (`auto` | `always` | `never`) + a General-settings dropdown to pick it; `auto` follows `ModelDefinition.supportsFunctionCalling`. `nativeToolsDecisionReason()` plus explicit `[NATIVE_TOOLS] Enabled/Disabled - <reason>` and enriched `[TOOLCALL] source=… nativeChannel=…` log lines make a run's native-vs-JSON path - and *why* it was chosen - visible in the debug log.
- Progressive native tool-call streaming - as a model streams a tool call's arguments, the IntelliJ chat and the TUI now show a live "⚙ <tool>(<args>)" indicator that appears while the call assembles and clears when the stream completes. Each streaming adapter (Ollama, OpenAI, the OpenAI-compatible family, Anthropic, Gemini) emits `StreamChunk.toolCallDelta`; `LLMClient` accumulates per call index into a renderable `ToolCallProgress` snapshot carried on the API `StreamChunk`; `SessionStateManager.toolCallProgress` (a `StateFlow`) drives both front-ends, and headless prints a concise `⚙ building <tool>` marker to stderr (once per call). Purely presentational - the assembled call is unchanged; `ToolCallStreamingAccumulatorTest` locks that streamed deltas concatenate exactly to the final call. The indicator is also cleared when a turn is stopped/cancelled (it is reset in the workflow `finally`, not only on the completion chunk) and when switching to another session (`SessionStateManager.setActiveSession` resets it on a session-id change), so a stale "⚙ building" never outlives its turn.
- Coding-agent e2e regression harness - `benchmark/scripts/e2e-run.sh` (+ a Windows `e2e-run.ps1` parity) drives JSON scenarios under `test_data/e2e/` through the existing headless CLI into a throwaway `--project` and asserts on the produced `run.json`. Deliberately not a Gradle module and not part of `./gradlew test` (slow, needs a model). Assertions are tiered per the doc's review note so weak-model run-to-run drift can't cause flaky "fix the test" pressure: **hard** (fail) = `needle_in_file` present + `build_cmd` exit 0 + no `metrics.contextOverflow` (spins with docs/0057 - silent truncation is never a success); **soft** (warn only) = `tool_order` as a subsequence of `conversation[].toolCalls[]` + the LLM judge. The assertion engine is validated offline by `e2e-run.sh --self-test` (good-run → PASS; overflow / build-failed / needle-missing → FAIL; bad tool order → warn but still PASS) - no LLM call, no consent needed. A live scenario run spends tokens/GPU and writes a temp project, so it stays behind the CLAUDE.md headless-consent rule.
- Compressed tool results now carry a recovery pointer - when `ToolResultCompression` shows the agent less than the full tool output (SUMMARY/DETAILED truncation, or a summary substituted for a longer raw), it appends a one-line `[result compressed <raw>→<body> chars via <LEVEL> - full output: memory(action="get_subtask_output", subtask_id="…")]` so a long-turn agent knows the result was shortened and can pull the full content back via the existing `MemoryTool.get_subtask_output`, instead of silently working off (and hallucinating on) a truncated view. No-ops when nothing was cut, when there is no subtask id to reference, or when a pointer is already present (`DiffCompressor` adds its own for diff bodies - never double-marked). `ToolResultCompressionTest` covers the append / not-truncated / no-id cases.

### Changed

- Legacy plan/step execution path removed - `WorkflowOrchestrator`, `IntentRouter`, `PlanningService`, `StepPlanner`, `StepSummarizer`, `AgentExecutor` and their models/tests deleted. CHAT now calls `ChatService` directly; PLAN/AGENT in the TUI, IntelliJ plugin, **and headless CLI** all run the single `AgentTurnLoop` via `agentRouter.runTurn`. Headless previously used the orchestrator's AGENT branch, which only produced a plan and stopped - so headless/benchmark runs never executed tools or wrote files. Orphaned `PromptType`s (`SYSTEM_STEP_PLANNER`, `SYSTEM_STEP_SUMMARIZER`, `SYSTEM_ORCHESTRATOR`, `SYSTEM_INTENT_CLASSIFIER`) and their prompt files removed; `WorkflowEventListener` slimmed to a pure streaming interface.
- Headless turn calls now stream - `runHeadless` always passes a stream callback to `runTurn`/`chat` (matching the IntelliJ plugin, which always streams for its UI), so the turn LLM call streams even without `-v` (`-v` only controls whether deltas are echoed). A non-streaming request sends no bytes until the whole response is ready, so a slow/cold local model that took minutes to produce the full body looked like a dead connection and was killed at the socket-idle timeout - which is why `qwen3.5` timed out headless but worked in the plugin (same core, same `num_ctx`). Streaming resets the idle timer per token. Verified: `qwen3.5:4b/9b`, previously timing out, now complete.
- `STREAM_IDLE_CEILING_MS` raised 300 s → 600 s - a cold large *dense* model with a big context window (e.g. `qwen3.5:27b` at `num_ctx=131072`) can take >5 min before its first token, which the 5-min idle ceiling false-aborted at ~300 s even though the model was merely slow to start, not dead. Effective idle stays `min(limits.api_call_timeout, ceiling)`, so a first token beyond the API-call timeout still needs that raised too.
- Small qwen3.5 models (`0.8b`/`2b`/`4b`/`9b`) marked `supportsFunctionCalling=true` - in `auto` mode they now take the native tool-calling path (previously they silently fell back to the JSON-in-text contract); degrades to JSON automatically if the local runtime has no tool parser for the model.
- Truncated-response detection unified into `ToolCallExtractor` (docs/0064 / 0056 §4) - when the model hits the output cap mid-envelope (`finishReason=length` with an unclosed JSON object) the extractor now returns `None("incomplete-json-truncated")`, and `AgentTurnLoop` reacts to that reason instead of re-deriving the `finishReason`/`hasJsonEnvelope`/`isComplete` condition itself. Same behavior, single source of detection.
- Log de-noise - new `DualLogger.trace` level routes only to the kotlin-logging backend (logback, off by default) and **not** to the in-app log sink, keeping high-frequency breadcrumbs out of the "Debug Logs" view and the Session Debug Report. The per-lookup `[ORCHESTRATION-DEBUG]` config-precedence lines (~48 per context build, from `tools.permissions` being read once per tool) and the per-repaint `MessageMetadataExtractor` `[EXTRACT]` lines (including one mis-leveled `info`) were demoted from `debug`/`info` to `trace`. Real events (warn/error, "Extracted code changes", "Extracted question data") stay.
- Agentic search is the default; vector RAG is now opt-in (docs/0060) - `rag.index_on_startup` and `rag.auto_index_on_context_build` default to **`false`**, so a cold start pays zero CPU/embedding cost for the many users who never run `rag_search` (keyword/agentic search reaches ~RAG quality on *code* without the indexing tax). The `rag_search` tool stays available; `RagIndexingStartup` logs the re-enable path instead of silently doing nothing (`docs/config.md` + `example-config.yaml` updated). `grep_search` now ranks declaration hits (`class`/`interface`/`object`/`enum`/`fun`/`def`/`func`/`fn`/`type`/`struct`/`trait`, stable within a tier) above plain usages, so an agent grepping a symbol sees its definition before call sites - `val`/`var` are excluded because they mark variable declarations (i.e. *usages* of the searched type) and would pollute the ranking (`GrepSearchToolTest.ResultRankingTests`). Removed a per-index dead cost: `FileAnalyzerService` no longer serializes `codeElements` JSON into the `IndexFiles.metadata` column - that blob was never deserialized by anyone (ranking uses cosine+keyword; every consumer reads `codeElements` in-memory from a fresh `FileAnalysis`), so it was pure CPU+I/O. (The `adaptive_search` tool and a per-session grep cache from the same doc were deliberately deferred - see docs/0060 §6.)
- Subagent depth ceiling is now enforced before spawn - `invoke_subagent` refuses a call whose child depth would exceed `MAX_SUBAGENT_DEPTH` (3) with a clean `ToolResult.error`, instead of spawning the child turn and only tripping `TurnSubagentValidator`'s `require` once the nested turn starts (the turn-loop check remains the backstop). Failing fast at the tool layer - consistent with the existing recursion guard - stops runaway fan-out from paying allocation cost it will immediately discard. `TurnSubagentValidatorTest` + `InvokeSubagentToolTest.DepthLimitTests` lock the boundary (depth 3 allowed, depth 4 refused without spawning).
- Native-tools demotion is no longer one-strike - a capable model that occasionally emits a `{response,actions}` JSON envelope in text instead of a native `tool_calls` is no longer permanently (and persistently, across sessions) demoted off native function-calling on the first slip. `AgentTurnLoop` counts a consecutive `nativeIgnoredStreak` and only calls `NativeToolsFallbackTracker.markFallback` after `NATIVE_TOOLS_DEMOTE_AFTER_IGNORES` (2) envelope-ignores in a row; a single slip switches just that turn to the JSON path (graceful) while keeping native offered next turn, and any iteration that actually uses the native channel resets the streak. The hard `ToolsNotSupportedException` path stays one-strike (a definitive provider capability signal - R2 alignment). R3 (the prompt stimulus) needs no change: `response-contract-native.md` lists only `{tool_use}`/XML as the forbidden examples - never the `{response,actions}` envelope, which is the legitimate JSON-contract format in `response-contract-json.md`. `AgentTurnLoopTest` covers both directions ("a single JSON-envelope slip does not persistently demote a capable native model" + "two consecutive envelope slips still demote the model off native").

### Fixed

- Bracket-labeled tool batches emitted as text are now recovered  - a weak local model pushed onto the text path (e.g. qwen3.5:35b, PLAN, session `6a1534a9`) batched four reads as `[TOOL] read_file: path="…"` / `read_directory: …, max_depth=2` lines, a format matching no contract, so `ToolCallExtractor` returned `None` and the whole attempt was dropped → turn INCOMPLETE. A new narrow `BRACKET_TOOL` recovery strategy parses these lines: armed only by the literal `[TOOL]` marker (so a bare `Field: a=b` prose line can't false-positive), accepting a line only when its name is a registered tool **and** it carries `key=value` args; quoted values are taken verbatim (commas/pipes in `pattern="apiKey|secret|password|token"` survive). Mirrors the existing Hermes / Qwen-Coder XML recovery (guarded, logs `[TOOLCALL_RECOVERED]`, `ToolCallSource` gains `BRACKET_TOOL`); `ToolCallExtractorTest` covers the batch recover, the unregistered-tool refusal, and the no-marker non-firing case. This is a text-path patch - the deeper cause (capable qwen models permanently demoted off native tool-calling by a one-strike fallback) is addressed in docs/0068 (the demotion now requires a streak of native-ignores - see the matching Changed entry).
- Paraphrase-stall turns now terminate early - a weak model (e.g. qwen3.6:35b, PLAN) that re-renders the SAME intent sentence with a couple of words swapped on consecutive no-tool-call iterations ("…standardowe **skanowanie**…" → "…standardowe **wyszukiwanie**…") used to churn to INCOMPLETE because `TurnGuardrails.ConsecutiveTextRepetitionTracker` matched only the exact normalized hash - a paraphrase produced a different hash, reset the run, and never fired. The tracker now also recognises a near-identical paraphrase via token-set similarity (Dice ≥ 0.85), so two such responses in a row abort the turn with a clear reason instead of reaching the guardian's extra weak-model judge call. Short texts (under 4 tokens either side) keep strict exact matching (a one-word change in a short sentence is a real difference). Tokenization is Unicode-aware (`Char.isLetterOrDigit()`, not `\W` - which is ASCII-only on the JVM and would shred Polish words like "dało"). Already wired into `AgentTurnLoop` (~1716) - no turn-loop change; `TurnGuardrailsTest` covers the paraphrase abort and the below-threshold/short-sentence non-firing cases.
- Stray tool-call residue no longer leaks into assistant chat bubbles - a model without native function-calling (e.g. qwen3.6:35b) emits tool calls as ad-hoc text: a bare JSON array `[{…}]` or a `[TOOL]` marker wrapping a `{response,actions}` envelope. Extraction lifts the calls into `tool_calls_json` and runs them, but `ToolCallContentSanitizer.sanitize` used to leave the textual residue behind, so the bubble rendered a lone `[` (or a raw JSON dump). Sanitize now peels a leading `[`/`[TOOL]` wrapper so the envelope's `response` text surfaces cleanly, and blanks content that is only JSON structural punctuation (a lone `[` → empty, which then renders as the tool bubble alone). Display-only - extraction/tool execution are unchanged; guarded so plain prose, plan JSON, and real `{…}` envelopes are untouched (`ToolCallContentSanitizerTest`).
- Stalled turns on weak native-tool models recover via a channel switch - when a model on the native function-calling channel ends a turn by narrating intent with **zero** `tool_calls` (e.g. qwen3.5:9b: "Let me explore the docs…"), the `NextSpeakerJudgeGuardian`'s single bounded re-entry now drops native tools and retries on the JSON-in-text contract (which weak local models often follow better) instead of re-entering on the same channel that just stalled. Reuses the judge's existing MODEL verdict - no new LLM call, no keyword heuristic; the switch lasts only for that turn (no persistent `NativeToolsFallbackTracker` mark - that path stays reserved for the JSON-envelope-in-text case at `AgentTurnLoop` ~1101). `AgentTurnLoopTest` covers it (and `setup()` now resets the global `NativeToolsFallbackTracker` so the marker can't leak between tests).
- Transcript churn during a turn - a mid-turn message reload no longer drops not-yet-persisted in-memory bubbles. `MessageDispatcher` previously replaced the whole list with `DB + in-memory system only`, so the human's prompt and live streaming bubbles blinked out (and, in a subagent run, the user's top-level prompt vanished entirely because the turn loop persists it tagged to the subagent under a different id). Merging is now done by `reconcileMessages` **inside the messages lock** against the latest snapshot - keeping a transient only until its DB counterpart exists: `system` notices and `isStreaming` bubbles are preserved; a top-level (`depth 0`) `user` prompt is preserved unless the DB already holds a top-level user row with the same content (a plain turn → drop, no duplicate); finished assistant transients still defer to the DB copy. `MessageDispatcherReconcileTest` (7 cases) locks the keep/drop/no-duplicate matrix.

## [0.0.1.11] - 2026-05-31

### Added

- `INCOMPLETE` task status - turns that stop without delivering the request (a completion guardian gives up, or a no-op-write / read-spree abort fires) are recorded as `INCOMPLETE` instead of `SUCCESS`. Shown with its own colour in the TUI and a `◐ Incomplete` badge in the IntelliJ History panel.
- Read-spree consolidation nudge - after a long run of read/search calls with no write or delivery, a SYSTEM nudge tells the agent to persist to memory or deliver incrementally (top-level AGENT only). Targets the "read 25 files, write nothing" failure mode.
- No-op-write streak abort - a WRITE that changes nothing three times in a row on the same target aborts the turn as `INCOMPLETE`; previously a no-op reported success and reset every progress guard, so a futile-edit loop ran to the iteration cap.
- `ConsecutiveTextRepetitionTracker` - aborts when the model repeats byte-identical final text with no tool call across iterations (a nudge re-entry it answers by repeating itself).
- MCP global-server tool exposure - `GLOBAL`/`TOOLS` servers now register `mcp_<server>_*` tools into every project registry (and the CLI's), so the agent can actually call them; the `context7` preset switched `CONTEXT` → `TOOLS`, with a fail-loud `WARN` and a settings hint when a server exposes no tools. Caveat: a server already in the DB stays `CONTEXT` until re-added/edited.
- `ModelWindow` - single context-window resolver, replacing four that disagreed and silently truncated the Ollama prompt; `claude-opus-4-8` (Claude Opus 4.8) added to `ModelDefinitions`.

### Changed

- Next-speaker judge now runs in PLAN as well as AGENT, and passes the tool-use *count* (not just distinct names) so a "use tool X three times" task isn't judged done after one call; recovers a stashed answer on an empty native reply instead of re-entering.
- Loop detectors exempt pure-symbol runs - a requested ASCII diagram (`│`) or `────`/`====` separator rule no longer trips the content-chanting or streaming repetition aborts.
- LLM stream idle-timeout clamped to 300 s, independent of the total API-call timeout, so a dead stream fails fast instead of hanging for the full timeout (observed: a 122B model stream hung 53 min).
- Ollama request sizing - `num_predict` is clamped so input + output fits inside `num_ctx`, with an overflow warning before the server silently truncates; a streaming watchdog makes Stop respond immediately on slow models.
- RAG quality / indexing - duplicate and fully-contained chunks dropped at chunk time and in `rag_search` results (top-K no longer fills with copies of one fragment); out-of-range chunk line bounds clamped (no more index-pass crash); chunk/embedding inserts batched and the active turn yields the SQLite WAL writer-lock to cut contention that stalled tool writes ~122 s.
- Context-panel auto-refresh now fires only on session change (was re-running a full `getProjectContext()` every ~1.5 s); token bar / prompt trace stay in sync via `lastPromptSnapshot`, sections refresh on demand. Preview path skips the redundant project-context rebuild. Removed unused `loop*` fields from `TurnLoopConfig`; `PathSandbox` init logs at `debug`.

### Fixed

- Output-repetition hard-abort defeated by the loop nudge - the "[⚠ possible loop]" nudge appended a varying `subtask_id` to the output, defeating the byte-identical tracker; `ToolResultData.loopSignature` now feeds the tracker the raw, un-nudged output.
- Rejected-tool bubble flips to "✗ Failed" immediately instead of waiting for the post-turn DB reload.
- Guardian re-entry nudges render as a gentle "Agent guidance" note (attributed to the originating subagent) instead of a raw "STOP - the turn is NOT finished" wall of text.

## [0.0.1.10] - 2026-05-27

### Added

- `/goal <condition>` command - set an explicit completion condition for the active task; the LLM judge keeps re-entering the loop until the condition is met from transcript evidence. Available in TUI (`/goal …`, `/goal clear`) and the IntelliJ chat input (intercepted mid-execution). Persists across restarts. Solves weak models stopping mid-task ("I've migrated the main models. Done.") before tests actually run.
- LLM "next speaker" judge in AGENT mode - after a tool-call-free reply, a cheap weak-model call decides whether the agent finished or just stopped. "Stopped" verdict re-enters the loop with a brief SYSTEM nudge; capped at 3 re-entries per turn. Toggle: `general.next_speaker_judge_enabled` (default on). Falls back to "pass" on any judge error so a broken judge never blocks an otherwise-finished turn.
- Content-chanting loop detection - aborts the turn when the assistant message contains the same word n-gram repeated 10+ times consecutively (model echoing itself, runaway lists). Adjacent-repetition only, so legitimate enumerations and bullet lists don't trip it.
- Anthropic prompt-prefix caching - system prompt split into stable / volatile parts; subsequent turns billed at the ~10% cache-hit rate while the prefix stays identical (5-min TTL). Token accounting folds `cache_creation_input_tokens` + `cache_read_input_tokens` into the reported `inputTokens` so billing dashboards still match.
- Multi-agent A2A messaging - each agent gets its own message queue; `send_message` enqueues to a peer, `answer_message` replies to a specific inbound message instead of broadcasting. Integration tests cover per-agent scoping when multiple agents share a task.
- Native function calling - per-provider test suites (Anthropic, Ollama, OpenAI, `NativeToolsResolver`) lock the wire format; minor robustness fixes around tool-call extraction in `OllamaAdapter` / `OpenAIAdapter`.
- Universal `<tool_use_enforcement>` block in `system-agent.md` / `system-plan.md` - replaces the previous `ModelFamilyClassifier`-based dynamic injection. 250 tokens are negligible on strong models and meaningful on weak ones. `system-agent.md` also adds a `<task_planning>` block pushing the `tasks` tool harder for non-trivial multi-step work.
- PLAN iteration cap raised 50 → 100 (warning at 30), matching AGENT and aligning with Gemini CLI / Hermes. PLAN is read-only so extra iterations are cheap.
- `EmbeddingCircuitBreaker` - resilience layer for embedding provider failures.
- `CodeIntelligenceTool`, `GrepSearchTool`, `ReadFileTool`, `ReadDirectoryTool` - expanded actions, improved output formatting, refined token budgeting.
- `WebSearchTool`, `FetchWebpageTool`, `HttpRequestTool` - refined error handling and network policy integration; new `NetworkPolicyTest`.

### Changed

- `TurnGuardrails` simplified - removed `looksLikeIntentAnnouncement` / `looksLikeToolMarkerOnly` prose-pattern detectors and the count-based abort in `TurnRepetitionTracker`. Only objectively-broken triggers remain (empty envelope, native-text-embedded tool call, malformed JSON, output-hash repeat). Aligns with Codex / Claude Code: trust the model, don't algorithmically detect "lapsed into prose".
- `AgentTurnLoop` format-retry only fires on objective broken outputs - legitimate plain-text final answers in native-tools mode no longer get nudged into a JSON envelope they weren't asked to emit.
- `ModelFamilyClassifier` removed - replaced by the universal `<tool_use_enforcement>` block.

### Fixed

- `MultiAgentRunner` - edge cases around agent instance ID propagation through the turn loop.
- `ChatService` / `ContextService` - minor refactors and bug fixes.
- `SubtaskTracker` - improved lifecycle accuracy.

---

## [0.0.1.9] - 2026-05-05

### Added

- `DiffCompressor` - content-aware elision of tool-result diff bodies (small / pure-create / large-mixed paths); recovery hint embeds the literal `subtask_id`. Saves ~8-14K tokens on the iteration after a write tool.
- Centralized LLM stats in `LLMClient` - `tokens_in` / `tokens_out` / `cost_usd` auto-incremented on the `task` and `subtask` rows after every successful call; the ~20 `complete()` call-sites no longer track metrics manually.
- Persistent native-tools fallback - new `models.native_tools_fallbacks` config key; `NativeToolsFallbackTracker.bind(configService)` hydrates on startup and mirrors writes back, so users no longer pay the 2-nudge probe cost on every fresh process.
- Native function calling for OpenAI-compatible providers (OpenRouter, Z.AI, Generic OpenAI, LM Studio) via shared `OpenAICompatibleHelpers.buildOpenAIToolsArray` / `parseOpenAIToolCalls`.
- Sub-LLM cost surfaced in tool-call bubbles - `ToolResultSummary` carries token/cost so `advance_code_editing`, `multi_line_editor`, `fetch_webpage`, etc. show their inner-LLM cost alongside the parent assistant call.
- Cached `maxContextWindow` as `StateFlow` in `SessionManager` - Status bar / Settings / Context panel no longer hit SQLite from the EDT.

### Changed

- AGENT task status now transitions `NEW → RUNNING → SUCCESS/FAILED` (was stuck at `NEW`).
- `SessionLifecycleService.updateSession(persistSettings)` - token-only refresh paths pass `false` to skip 5 redundant `ConfigRepository` writes per turn.
- `SessionStatsCalculator` prefers `session.tokensIn/Out/costUsd` from the `task` row over per-message summing - fixes header/footer drift after auto-name turns.
- `MessageDispatcher` re-attaches tokens from dropped messages (legacy `TOOL_CALL:` envelopes, empty assistant bubbles) to the last visible message.

### Fixed

- `OpenAIAdapter` streaming usage parsing - `prompt_tokens` / `completion_tokens` no longer dropped from the final usage block.
- Removed dead `SubtaskRepository.updateLlmMetrics` (SET semantics) - only the additive `incrementLlmMetrics` is kept.
- Header/footer token drift after auto-name or thinking-only turns.

---

## [0.0.1.8] - 2026-04-27

### Added

- **Native function calling** - agents can now use providers' structured `tools` API instead of the JSON-in-text envelope. Opt-in per model via `tools.native_tools: auto | always | never` in `config.yaml`; default is `auto` (enabled for models with `supportsFunctionCalling=true`).
  - OpenAI (Chat Completions + Responses API) - strict-mode schema sanitization, `tool_choice: auto`.
  - Anthropic - `tools` array + `content[].tool_use` response parsing; schema sanitized (composition keywords stripped).
  - Gemini - `functionDeclarations` array + `functionCall` response parsing; Gemini-specific type normalization.
- `ToolSchemaSanitizer` - provider-aware JSON Schema normalization (OpenAI strict compatibility check, Anthropic forbidden-keyword stripping, Gemini single-type coercion and nullable fields).
- `NativeToolsFallbackTracker` - session-scoped in-memory registry; if a provider returns 400 "tools not supported", the model is marked and all subsequent requests in the same process skip native tools automatically.
- `NativeToolsResolver` (`NativeToolsMode` enum: `AUTO / ALWAYS / NEVER`) - central decision logic with precedence: fallback cache → NEVER → ALWAYS → `ModelDefinition.supportsFunctionCalling`.
- XML tag fallback in `ToolCallParser` - recovers tool calls from `<tool_name><arg>…</arg></tool_name>` pseudo-tags emitted by weak local models (qwen<70B, some Gemma builds) that bypass the native API entirely. Warns on use.
- New models: **GPT-5.5**, **GPT-5.4 Nano**, **Qwen 3.6 35B/27B** (Ollama), **Qwen 3-next 80B**, **gpt-oss-safeguard 20B/120B**, OpenRouter `qwen/*` patterns.
- `claude-sonnet-4-6` added to `SupportedModels`.
- Per-iteration token and cost metrics persisted directly to the task row so the History panel and live stats bar show running totals without aggregating from message metadata.

### Changed

- `AgentTurnLoop` auto-falls back to the JSON-in-text path when a provider throws `ToolsNotSupportedException` - prompt is rebuilt without `<available_tools>` and the JSON envelope contract; no user action needed.
- When native tools are active the `response_format` / `json_object` override is suppressed (incompatible with function-calling mode on most providers).
- `TurnLLMCaller` accepts `nativeToolSchemas` and forwards them as `native_tools` in `kwargs`; retry handler also propagates `thinking`, `reasoningEffort`, and `noEgressEnabled`.
- Subagent profiles filter the native `tools` array to match their `<available_tools>` list - prevents the model calling tools the harness would reject.
- `ToolCallParser.extractToolCalls` pre-guard (`startsWith("{")`) removed - some models (e.g. glm-4.7-flash) emit prose before the JSON envelope, which the brace-matching strategy handles correctly. All-strategies-failed log downgraded from `WARN` to `DEBUG`.

### Fixed

- Anthropic tool results were mapped to `role: "assistant"` - now correctly mapped to `role: "user"`, fixing HTTP 400 rejections from claude-opus-4-6 and newer models that treat assistant-role tool results as prefill.
- `temperature` parameter no longer sent to models that deprecated it (`claude-opus-4-7`, `claude-opus-4-6`, `gpt-5.5`, `gpt-5.4-nano`) - removes spurious provider warnings.
- Anthropic streaming: `content_block_start` for `tool_use` blocks now captures the call `id` alongside the name.

---

## [0.0.1.7] - 2025-04-17

### Added

- New agent tools: `sleep`, `ask_user`, `web_search`, `fetch_webpage`, `run_process_background` + `monitor_process`, `code_intelligence`.
- Subagent overrides - customize built-in subagents locally without forking their source.
- Example `config.yaml` shipped under `core/src/main/resources/config/`.

### Changed

- Execution mode, thinking, and no-egress toggles moved from the chat input to **Settings → General**.
- `thinkingEnabled` / `noEgressEnabled` / `executionMode` moved from `ui:` to `general:` in `config.yaml`. Values still under `ui:` are ignored - reconfigure once from the General tab.
- Feature renamed "Slash Commands" → "Prompts". `/name` syntax unchanged; existing entries migrated automatically.
- Terminal command rules unified into a single regex-based `ALLOW` / `BLOCK` / `ASK` ruleset.
- Models tab no longer stalls on open - provider discovery runs only via **Refresh**.
- Local provider listing timeouts tightened (Ollama/LM Studio: 3 s, was 15 s).
- Tools settings tab layout is responsive; YAML config is now typed (unknown keys raise errors).
- Session layer shared between plugin and CLI; Settings panels rebuilt on the typed config model.
- Built-in `system-agent` prompt refreshed.

### Removed

- "Enable No-Egress by default" checkbox (Advanced Settings) - duplicated the new General toggle.
- Multi-agent orchestration UI and strategies - the main agent now delegates via `invoke_subagent` on its own.
- Various internal backcompat shims.

### Fixed

- UI no longer freezes while persisting session state.
- Silent fallbacks removed from OpenAI parsing, terminal rule compilation, and `http_request` - bad input surfaces as real errors now.
- Duplicate agent-turn dispatch wiring consolidated.

---

## [0.0.1.6] - 2025-04-12

### Added

- Tool approvals with `ASK` permission and session trust; clearer prompts in IntelliJ and CLI.
- Follow-up messages can be queued while the agent is still working.
- Terminal command rules (per-pattern `ALLOW` / `BLOCK` / `ASK`).
- `delegate_to_strong_model` tool + optional `STRONG` model slot for heavy tasks.
- Compact prompt mode for smaller-context models.
- Multimodal pipeline (image-aware prompts) and broader MCP support (prompts, resources, cached metadata).
- Early multi-agent runtime: parallel orchestration, graph/timeline panels, nested-run visibility.
- Onboarding guide in `docs/onboarding.md` and broader automated coverage.

### Changed

- Tool permissions fully support `ON` / `ASK` / `OFF`; terminal in AGENT defaults to confirm.
- Agent turns recover better from rejections, transient HTTP issues, and repetitive tool loops.
- `run_terminal_command`, `run_code`, `http_request` more reliable around timeouts and retries.
- Read-before-write / verify-after-write enforced more strictly in prompts and tool guidance.
- RAG, context budgeting, and working-memory decay retuned; system-agent prompt condensed.
- RECENT_WORK is now budget-driven (FULL → DETAILED → SUMMARY step-down) and includes failed tool calls so the agent sees past errors.

### Fixed

- `http_request` egress checks, regex safety, approval/trust race conditions.
- Subagent recursion tracking, `CoreApiRouter` shutdown cleanup, file-write locking.
- Image/PDF handling in `read_file` and multimodal provider payloads.
- Chat message ordering when streaming and tool output interleave.
- Weak models silently ending a turn without the JSON envelope - now nudged back to format.

---

## [0.0.1.5] - 2025-04-02

### Added

- **Standalone CLI** with full TUI (Mordant + JLine3): tabs, autocomplete, session history.
- **Multi-agent architecture**: parallel orchestration, event bus, YAML task definitions.
- New tools: `http_request` (with `save_to_file`) and `run_code` (Python, JS, Kotlin Script).
- 7 standalone context providers for CLI mode.
- File-based prompt registry (project/user/built-in) with Markdown definitions.
- Turn-state inspector: prompt snapshots, context traces, token usage.

### Changed

- Gradle split into `:core`, `:intellij-plugin`, `:cli`. Core no longer depends on IntelliJ at compile time.
- `ContextService` split into formatter/pruner; conversation compaction uses structured summaries.
- Working memory decays stale entries; tool-result persistence keeps up to 16 KB before summarizing.
- `PromptsService` / `SubagentRegistry` moved onto the shared layered registry.

### Fixed

- TUI: `Ctrl+D` crash, duplicate streaming messages, JLine dumb-terminal warnings.
- `PathSandbox` symlink resolution on macOS; MCP disabled/stale/auth-required state tracking.
- Plan bubbles show real tool calls; conversation compaction preserves structured summaries.

### Removed

- Compose Desktop GUI (replaced by TUI).

---

## [0.0.1.4] - 2025-03-22

### Added

- Project instructions via `.refio/agent.md`, `AGENTS.md`, and `.refio/rules/*.md`.
- Richer project analysis for CSS, TypeScript, HTML, C++, and dependency ecosystems.
- Typed config access via `ConfigKeys` + `getTyped()` / `setTyped()`.
- HTTP API server exposing `CoreApiRouter` over JSON and SSE.

### Changed

- Terminal command policies relaxed for normal dev workflows; destructive actions stay guarded.
- Command timeouts and output limits increased for longer builds and tests.
- Subagent execution aligned with the newer turn-loop and JSON planning flow.

### Fixed

- Nested JSON model responses unwrapped more reliably.
- Chat tool bubbles stable after streaming; subagent responses show clearer attribution.

### Removed

- Legacy multi-step subagent execution path (replaced by the current turn loop).

---

## [0.0.1.3] - 2025-03-18

### Added

- Dedicated `providers.custom_openai` support (OpenAI-compatible backends) and standalone Z.AI provider with its own Settings card.
- `security.allow_symlinks` opt-in for symlink access in `PathSandbox`.
- `PRIVACY.md` describing local storage, cloud behavior, no-egress mode, and secret handling.

### Changed

- Model discovery uses single-flight caching; token estimation no longer fetches provider model lists on the hot path.
- Tool-call bubbles preserve assistant narrative alongside tool metadata.
- RAG: configurable memory controls, semantic chunking, paginated embedding scans, result caching for `@codebase`.
- Embedding generation batched for OpenAI and Ollama.

### Fixed

- `ChatView` row-layout fix for regular chat bubbles.
- PLAN/AGENT now forward `thinking` and `noEgressEnabled` to `LLMClient.complete()`.
- `PathSandbox` rejects symlinks by default (incl. parent dirs); `advance_code_editing` enforces excluded extensions.
- Streaming placeholders no longer leak raw `{"actions":...}` JSON during an active turn.
- Provider failures normalized into typed Refio LLM errors (timeout, auth, rate-limit, upstream).
- Ollama requests gated per endpoint to reduce contention.

---

## [0.0.1.2] - 2025-03-07

### Added

- Built-in slash command `/implementation-analysis`.

### Fixed

- Longer allowed AGENT/PLAN runs; deeper read-only analysis before nudging toward writes.
- Empty structured responses (`{}`, `""`) retry instead of ending the turn prematurely.
- Local providers (Ollama/LM Studio) no longer receive forced `json_object` response format.
- Deterministic fallback when the weak model returns an empty summary.
- AGENT prompt trimmed; `thinking` is optional, JSON contract kept explicit.

---

## [0.0.1.1] - 2025-03-03

### Fixed

- Terminal warning on IntelliJ check.

---

## [0.0.1] - 2025-03-02

Initial release - a local-first AI coding assistant for IntelliJ IDEA.

Highlights:

- **Three execution modes** - CHAT (conversational), PLAN (read-only analysis), AGENT (full read/write) - all backed by a Codex-style `AgentTurnLoop` with subtask tracking and safety limits.
- **10 tools** (5 read-only, 5 write) including `multi_line_editor` and `advance_code_editing`; `run_terminal_command` disabled by default.
- **18 context providers** via `@file`, `@folder`, `@current`, `@codebase`, `@grep`, `@commit`, `@docs`, `@url`, and more.
- **RAG with local embeddings** (Ollama + OpenAI), hybrid semantic+keyword search, incremental indexing.
- **6 LLM providers** - Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio.
- **MCP protocol** support (stdio + HTTP/SSE) with 16 built-in server presets.
- **Subagents** with YAML frontmatter (Claude Code compatible), built-in `security-reviewer` and `code-reviewer`.
- **Security layers** - `PathSandbox`, `FileLimits`, `CommandDenylist`, per-mode tool permissions, no-egress mode, secret redaction.
- **Config hierarchy** - DB (Settings UI) > project `.refio/config.yaml` > user `~/.refio/config.yaml` > built-in defaults.
- **SQLite persistence** via Exposed ORM (sessions, chat history, snapshots, API logs, RAG index).
- Native IntelliJ Swing UI with chat view, `@` autocomplete, context panel, and 12+ settings panels.

See `docs/` for full architecture details.
