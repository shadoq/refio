# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.0.1.14] - 2026-07-22

### Added

- `find_usages` tool - lists every use of a symbol (`file:line` + snippet + count, capped by `max_results`).
- `rename_symbol` tool - project-wide rename from a `file`/`line` anchor; reports occurrences changed and files touched.
- Structural-refactor engine with two backends: identifier-boundary-aware text (CLI/headless) and IntelliJ PSI (semantic, updates references), with PSI falling back to text when a symbol can't be resolved.
- Deterministic post-turn verification (`TurnVerifier`): runs the project's build/test after a write, feeds failures back to the model for up to `verify.max_repair_rounds`, else marks the turn `VERIFICATION_FAILED`. Keys: `verify.enabled`, `verify.command`, `verify.max_repair_rounds`.
- Verify-command autodetect when unset: `build.gradle*` -> `./gradlew build -q`, `package.json` -> `npm test --silent`, `Cargo.toml` -> `cargo build -q`.
- Pre-write baseline capture - an already-failing build/test isn't blamed on the agent's change.
- Diff preview on approval: write tools attach a `ProposedChange`, and the IntelliJ approval panel gained a "Show diff" button.
- Extended next-speaker-judge re-entry (up to 2 early in a turn, second nudge re-includes the JSON schema). Toggle: `general.judge_extended_reentry_enabled`.
- Actionable LLM errors with fix hints: `LLMConnectionFailed`, `LLMModelNotFound`, `LLMContextOverflow`.
- Headless CLI always writes a `run.json` even when a turn throws before the normal export (task forced `FAILED`, error appended).
- Verification metrics (`metrics.verification`) and a `VERIFICATION_FAILED` marker in `run.json`.
- E2e gates: `e2e-gate.sh` (N runs per scenario -> pass-rate table), `validate-scenarios.sh` / `validate-analysis-scenarios.sh` (deterministic, no LLM), `tui-smoke.py` (CLI in a pty).
- E2e suite: +27 scenarios, +22 prompts, +21 fixture trees, +11 golden dirs.
- TUI Settings Tools tab (per-tool ON/ASK/OFF), plus reset-all / export / reload-config actions and scroll-to-selection.
- Cached-token cost accounting: input tokens split into fresh / cache-read / cache-write subsets, each billed at its own rate, so a repeated (cache-served) prompt is no longer charged as fresh. Explicit cache-read prices for OpenAI, Anthropic (0.1x input) and Gemini (0.25x input); models without a configured cache price fall back to the full input rate (never a phantom discount).
- Cached tokens surfaced in the UI: a `cached_tokens` session counter (persisted via a new DB column) with a `💾` indicator in the TUI status line and the IntelliJ StatusBar, so a user sees how much of a turn was served from cache.
- `general.reasoning_effort` (OFF/LOW/MEDIUM/HIGH) replacing the boolean `general.thinking_enabled`; each level maps per-adapter to the provider-native knob (OpenAI/OpenRouter reasoning effort, Anthropic/Gemini thinking-token budget, Ollama on/off). Selectors added to the TUI settings and the plugin General settings.
- Subagent history isolation: every subagent turn gets an agent-instance id even when the spawning caller didn't assign one, so a subagent's intermediate steps no longer leak into the parent thread or inflate its token budget.
- Multi-agent debug export (`SessionDebugExporter.exportMultiAgent`) writes a per-agent snapshot (ordered by start time, aggregate status and token totals) for a multi-agent run.
- Two orchestration e2e scenarios (`subagent-locate-and-fix`, `subagent-two-file-fix`) with fixtures, prompts and golden trees, checking the parent stays on-task after a read-heavy delegation.
- `TransientErrorClassifier` - one shared definition of "retryable upstream failure" (bare HTTP 500 with an empty body, Cloudflare 520, Anthropic 529 overloaded), used by `LLMRetryHandler` and by `advance_code_editing` (which bypasses the retry handler).
- `advance_code_editing` salvages a truncated generation: when the stream ends without a closing fence but the body is substantial (2 KB+), the near-complete file is written and flagged instead of the whole edit being lost. A truncated block also short-circuits the extraction-repair loop, which would only truncate again.
- `advance_code_editing` retries a transient editor-LLM error with a bounded backoff (2 retries, 1 s / 2 s), but only while nothing has streamed to the UI yet, so a partial stream is never duplicated.
- Repeated-full-regeneration nudge: rebuilding one path whole-file 3x in a turn (`advance_code_editing` / `create_new_file`) injects a single soft SYSTEM hint to make a targeted edit or deliver. Targeted-edit tools never count, so ordinary iterative editing is unaffected.
- Native-vs-JSON tool-routing decision (`ProjectContextResponse.nativeToolsDecision`) shown in the plugin Debug panel, so an operator can see *why* a run took the JSON-in-text path without re-deriving the precedence by hand.
- `TaskStatus.CANCELED` recorded when the user presses Stop, instead of the turn being logged as `FAILED`.

### Changed

- Default models `qwen2.5:7b` -> `qwen3.5:9b` (chat/plan/agent/weak).
- Empty-content GiveUp is deliverable-aware: a write that already landed finalizes as `SUCCESS` instead of `FAILED`.
- Turn decision LLM call routes through `LLMRetryHandler`, so a first-call zero-byte stream abort is retried instead of killing the turn.
- LLM error mapping walks the cause chain, detects context overflow across Ollama phrasings, maps 404 to model-not-found, and includes the endpoint.
- OpenAI-compatible SSE parses each chunk once into a typed JSON object (Ollama, OpenRouter, others).
- `find_usages` preferred over `grep_search` for rename impact; `rename_symbol` over grep-plus-per-file edits.
- Hooks run on the IO dispatcher, so a blocking hook no longer stalls the turn.
- Agent-event queries load only the newest N (cap 5000); API-log fetch takes a limit (default 500).
- RAG cheaper on large indexes: anti-join for missing embeddings, keyset pagination, query norm computed once, reused content hash.
- Doc/file indexing batches chunk and embedding inserts (one lock per table).
- `.aiignore` matcher cached per project root, invalidated by mtime.
- IntelliJ minimum IDE raised to 2024.2 (`sinceBuild` 242, first line with JBR 21).
- ChatView resize debounced (300 ms).
- Chat render pipeline rewritten (IntelliJ): the message `StateFlow` is collected directly and throttled with a trailing delay (leading-edge throttle, one rebuild per 300 ms) instead of being hopped through a `MutableSharedFlow(extraBufferCapacity = 1)` + `sample`. `StateFlow` conflation guarantees the final frame lands, so a burst of streaming deltas can no longer swallow the last update. The field is now named `uiUpdateThrottleMs` - it is not a debounce and must not become one (a debounce waits for the stream to fall quiet, which never happens while a tool generates).
- ChatView collectors moved to `Dispatchers.IO`: on `Dispatchers.Default` a long tool stream kept the bounded pool busy and starved the UI collectors. The collector bodies only marshal work to the EDT, so the elastic pool is the right home.
- `kotlinx-coroutines-core` excluded from the plugin's runtime classpath (it is provided by the IntelliJ Platform). A second bundled copy caused a `ServiceLoader` clash that silently stalled `StateFlow` collectors. Compilation and the standalone CLI are unaffected.
- A tool bubble is marked live (`isStreaming` / `isToolStreaming`) at creation instead of at its first delta, and `MessageDispatcher.reconcileMessages` treats an `EXECUTING` tool call as live.
- Tool-call bubbles deduplicated by `toolCallId` on reload: while the call streams the in-memory bubble wins (it is the only copy with content) and its persisted twin is held back; once it finishes the persisted row takes over. Keyed strictly by `toolCallId`, never `agentName`, so earlier turns of a repeatedly-invoked subagent stay visible.
- Streaming deltas whose accumulated text has not grown are skipped instead of rebuilding the whole message list (safe: the text only ever grows by appending).
- Stop cancels the running turn `Job`, so cancellation propagates into the in-flight LLM HTTP call instead of only flipping UI state.
- `advance_code_editing` requests the model's full output budget; adapters clamp to the per-model limit. An explicit caller `maxTokens` is now honored up to the *model* limit across all adapters (Anthropic, Gemini, Ollama, OpenAI-compatible); `limits.max_output_size` is the default when no value is passed and the ceiling when the model's real limit is unknown.
- `RepetitionDetector` threshold raised 4 -> 32 identical adjacent blocks. Legitimate structured output (table cells, list items, data rows) has a small bounded run; a decoder loop is unbounded. Large-block runaway stays covered by the 128 KB size limiter and the 180 s wall-clock deadline.
- The model chosen in the dropdown is persisted to APP-scope `ui.selected_model` and applied to a new session, so the CODING slot (`advance_code_editing`) stops generating with a stale model while the turn LLM already uses the new one.
- `system-agent.md`: the "do not validate static content" rule is explicit that writing a static file finishes it - no re-reading, no parsing its inline scripts, no self-authored grep-for-feature checkers. A failing self-authored checker is almost always the checker being wrong, and chasing it is the most common way a turn spirals.
- CLI/TUI numeric formatting uses `Locale.US`.
- Hot-path regexes precompiled (`JsonExtractor`, `TokenEstimator`, `ToolResultCompression`, `ToolCallParser`); JSON extraction now single-pass balanced-brace.

### Fixed

- Chat bubbles appeared only after resizing the tool window or dragging the splitter. Two independent causes: the lossy `tryEmit` render hop dropped the last update of a burst, and `messagesPanel.revalidate()` stopped at the enclosing `JViewport` validate root, so the scroll pane never re-ran its layout. The whole scroll chain is revalidated now.
- The live char counter in `advance_code_editing` never moved. The tool bubble was created non-streaming, so a mid-turn reload dropped it from the list; every later delta then mapped over an id that was gone, producing an equal list that the `StateFlow` never emitted (observed: 3433 deltas over 62 s with zero UI updates).
- One tool call rendered as two bubbles after a mid-turn reload (the streaming transient and its persisted display row carry different ids).
- Stop hung on OpenAI-compatible streaming (Z.AI/GLM, LM Studio, OpenRouter, generic OpenAI): the SSE loop never received the cancel check, so the turn read the stream to completion before reacting.
- A bare upstream `HTTP 500` / `520` / `529` killed a whole session instead of being retried.
- Weak models on the JSON-in-text path that emit tool params as siblings of `tool` (flat action shape, observed on gemma4:31b) failed with "Missing required parameter"; the leftover keys are now mapped as arguments.
- A turn that already wrote its file then narrated "let me verify..." burned extra iterations: the guardian no longer drops native tool-calling after a landed file write, and the dangling intent stub is replaced with a factual completion note.
- Orphaned background-process hang: `run_terminal_command` force-kills the whole descendant tree and drains stdout on its own thread, so a backgrounded child (`python app.py &`) holding the stdout pipe no longer hangs the turn (`orphan_reaped` in metadata).
- `ProcessManager` kills the whole descendant tree on stop, reaps children via a JVM shutdown hook, and serves continuously drained output.
- Code-intelligence/refactor child processes drain output on a daemon thread and force-destroy on timeout instead of deadlocking.
- Concurrent LLM completions no longer lose metric updates - atomic in-DB increments; task stats via one grouped SQL `SUM`.
- `MultiAgentRunner` runs its completion path even when agent setup fails, so dependents don't wait forever.
- Verification still red after all repair rounds ends the turn as a real failure, not fake success.
- Cost overcharge on cached / repeated context: a cache-read hit was previously billed at the full input rate. Anthropic cache-read was 10x over (now 0.1x), and a real gpt-5.4-nano run now reconciles to the actual OpenAI bill instead of ~5x under.
- Corrected OpenAI list prices used for cost estimates (`gpt-5.4-nano`, `gpt-5.4-mini`, `gpt-5.5`).
- OpenRouter model resolution matches the most specific prefix over an ordered map, so `openai/gpt-5.1*` keeps its 400k context window and its own pricing instead of falling back to the 128k `gpt-` family.

---

## [0.0.1.12] - 2026-07-02

### Added

- Headless CLI flags: `--output json` + `--output-file` (per-turn `run.json`), `--debug-level`, `--config`/`--config-file`, `--print-config`, `--max-cost`, `--auto-approve "<regex>"`, `--no-egress`; `-v` streams live token deltas.
- Unified tool-call extraction logs a reason on every failed attempt (no more silent early finish when a model returns prose).
- Native function calling on OpenAI-compatible adapters (OpenRouter, Z.AI, Generic OpenAI, LM Studio) and Gemini.
- Native-tools fallback persists across restarts (`models.native_tools_fallbacks`) - failed probes aren't re-run.
- Per-provider tool-schema normalization so one schema works on every provider.
- `tools.native_tools` config (`auto`|`always`|`never`) with a settings dropdown.
- Live tool-call indicator (name + args) in chat and TUI while a call streams.
- E2e regression harness (`e2e-run.sh` + Windows parity) drives JSON scenarios headless; `--self-test` checks the assertion engine offline.
- Compressed tool results carry a recovery pointer to fetch the full output via the memory tool.
- HTTP responses over 64 KB are saved to a `.refio_http_*.txt` file with a `read_file` pointer instead of flooding the context.
- "Change approach" nudge after 2+ failed edits of one file or 2+ back-to-back `run_code`/`run_terminal_command` failures.
- New model definitions: Anthropic `claude-sonnet-5`, `claude-mythos-5`, and Ollama cloud `minimax-m3`, `ornith` (9b/31b/35b/397b), `glm-5.2`, `kimi-k2.7-code`, `deepseek-v4-pro`/`-flash`, `step-3.7-flash`, `nemotron-3-ultra`/`-nano-omni`, `mimo-v2.5-pro`.
- E2e stabilization gate (`cli --gate`): N runs per scenario -> pass-rate verdict, per-scenario baselines, trend history, `metrics.failureMarker` in `run.json`.
- Per-model/per-tool benchmark stats (`e2e-stats.sh`): model leaderboard, scenario-by-model matrix, tool-use histogram.
- App-build and CTF e2e scenarios - build tasks gated by a real `build_cmd` (or Playwright smoke test), CTF gated on a flag string.
- CI enforces full Gradle `check` on every PR, plus a Plugin Verifier job, a nightly workflow, and a tag-triggered release workflow.
- "Generate missing embeddings" button in the RAG panel (shown only when embeddings are missing).

### Changed

- Legacy plan/step path removed - PLAN/AGENT/headless all run through one `AgentTurnLoop` (headless now executes tools and writes files).
- Headless turns stream, fixing slow/cold local models killed at the socket-idle timeout (`qwen3.5:4b/9b` now complete).
- Stream idle-timeout ceiling raised 300 s -> 600 s.
- Small qwen3.5 models (`0.8b`/`2b`/`4b`/`9b`) use native tool-calling in `auto` mode, degrading to JSON if unsupported.
- Truncated-response detection unified into the extraction layer.
- High-frequency internal logs demoted to trace for a readable "Debug Logs" view.
- Agentic search is default; vector RAG opt-in (`rag.index_on_startup: false`). `grep_search` ranks declarations above usages.
- Subagent depth ceiling enforced before spawn - `invoke_subagent` refuses a call past depth 3 without allocating a child turn.
- Native-tools demotion needs two consecutive JSON-in-text slips (one slip retries on JSON for that turn only).
- RAG indexing/embedding moved off `Dispatchers.IO` onto a dedicated bounded pool.
- `llm_call` description clarifies it's the way to run an LLM step, not OpenAI/Anthropic HTTP from `run_code` (which has no keys).
- Guardian re-entry budget is per stall-episode: 3+ new tool calls after a re-entry replenish the single bounded re-entry.
- Headless `--model X` applies to every slot (turn, editing, plan, subagents) by folding into `ui.selected_model`; explicit `--config ui.selected_model` still wins.
- Internal decomposition (behavior-preserving) of the turn executor, project analyzer (2050 -> 783 LOC), and context service (1606 -> 1191 LOC).
- Chat input/panels use IDE icons and scale-aware sizing instead of emoji and fixed pixels (HiDPI-correct).
- Diagnostic/settings panels reworked: sortable Debug tables, incremental Logs/API-Logs updates, paginated API Logs, lazy tab init, standard IDE toolbars for Prompts/Subagents.

### Fixed

- Ollama streaming no longer discards a captured tool call when the `done:true` sentinel is missing.
- Native tool schemas counted in the context budget, so AGENT prompts don't overflow `num_ctx` on Ollama.
- API-log `source`/`taskId` reflect the actual request (PLAN vs AGENT now distinct), not the pool entry's first request.
- Bracket-labeled tool batches emitted as text (`[TOOL] read_file: ...`) are recovered, not dropped.
- Paraphrase-stall detection: word-level near-duplicate intent sentences now trip the repetition abort.
- Stray tool-call JSON no longer leaks into chat bubbles for non-native models.
- Stalled turns on weak native-tool models retry on the JSON-in-text path.
- Mid-turn reload no longer drops unpersisted bubbles or blinks out the prompt/streaming bubbles.
- Answers returned only in `reasoning_content` (some GLM/Z.AI/DeepSeek) no longer produce a blank reply.
- `run_terminal_command`/`run_code` decode output as UTF-8 (+ `PYTHONUTF8`, PowerShell prefix), so non-ASCII output isn't garbled.
- A plain-prose answer in JSON-envelope mode is surfaced and marked INCOMPLETE instead of lost as FAILED.
- `llm_call` returning empty is an error, not a silently-saved empty file.
- OpenAI-compatible adapter cost computed from the pricing table instead of hardcoded zero.
- Context panels render HTML lazily and capped, no longer freezing the UI on large tool results.
- `advance_code_editing` accepts only a fenced block; a fence-less reply goes through edit-repair then fails loud, file untouched.
- `multi_edit` validates all edits before writing, so a later failure doesn't leave earlier files half-written.
- A streamed call isn't retried once chunks reached the UI, so a mid-stream failure doesn't replay onto the partial.
- Embeddings cache key uses a fresh `MessageDigest` per call (the shared digest could poison the cache under concurrency).
- A turn resumed after a tool-approval pause yields the SQLite write lock to RAG indexing, like a normal turn.
- Single non-streaming write tools (`write_file`, `multi_edit`, overwrites) are snapshotted before execution for rollback.
- Tool success tracked via a structural flag, not an `Error:` text prefix, so legit `Error:` output isn't miscounted as failure.
- Execution step cards keep natural height with a status stripe (no "green wall"); "Delete All" moved to the toolbar's right edge.
- Session History uses toggle-button filters (no dead band) and compact token counts (2.4M/16.4K) with exact value in the tooltip.
- RAG file paths ellipsize in the middle (filename kept), full path in tooltip.
- Assistant bubbles strip echoed tool-result markers (`[Tool result: ...]`, "File created successfully:").
- File names with `<` or `&` are HTML-escaped, no longer corrupting bubble render.
- Tool-bubble status comes from the result flag, not a " failed"/"error:" substring, avoiding false red status.
- Large tool-call parameter values shown in an expandable panel instead of a 60-char truncated label.
- RAG "Search Test" results are clickable file paths (collapsed by default) instead of a debug dump.

### Security

- Command sandbox: a line with a chaining/substitution/redirection operator (`;`, `&`, `|`, backtick, `$(...)`, `>`, `<`, newline) falls from `ALLOW` to `ASK` so the whole line is reviewed (`git status; rm -rf /` no longer auto-runs). `BLOCK` still wins.
- No-egress gate no longer fails open - a config read error falls back to the last good value, else fails closed.
- SSRF guard validates every resolved DNS record, blocking the mixed public/private rebinding pattern.
- `run_process_background` defaults to `OFF` in PLAN and `ASK` in AGENT (was `ON`).
- `http_request`/`fetch_webpage` re-check SSRF/network-policy on every redirect hop, blocking a 30x to a loopback/internal address.
- `git clean` force flag blocked in any order/grouping (`-xfd`, `-d -x -f`, `--force`).

## [0.0.1.11] - 2026-05-31

### Added

- `INCOMPLETE` task status for turns that stop without delivering (guardian gives up, no-op-write / read-spree abort), with its own TUI colour and a `◐ Incomplete` History badge.
- Read-spree consolidation nudge - after many reads with no write, a SYSTEM nudge to persist to memory or deliver (top-level AGENT).
- No-op-write streak abort - a WRITE changing nothing 3x on the same target aborts the turn as `INCOMPLETE`.
- `ConsecutiveTextRepetitionTracker` aborts on byte-identical final text repeated with no tool call.
- MCP `GLOBAL`/`TOOLS` servers register `mcp_<server>_*` tools into every registry (context7 preset switched to `TOOLS`); WARN when a server exposes none.
- `ModelWindow` single context-window resolver (replaces four that disagreed); `claude-opus-4-8` added.

### Changed

- Next-speaker judge runs in PLAN too and passes the tool-use count (not just distinct names); recovers a stashed answer on an empty native reply.
- Loop detectors exempt pure-symbol runs (ASCII diagrams, `────`/`====` rules).
- LLM stream idle-timeout clamped to 300 s, independent of the total timeout, so a dead stream fails fast.
- Ollama `num_predict` clamped so input + output fits `num_ctx` (overflow warning); a streaming watchdog makes Stop respond immediately.
- RAG: duplicate/contained chunks dropped at chunk time and in `rag_search`; line bounds clamped; inserts batched and the turn yields the WAL writer-lock.
- Context-panel auto-refresh fires only on session change (was every ~1.5 s); sections refresh on demand. Removed unused `TurnLoopConfig.loop*` fields.

### Fixed

- Output-repetition abort was defeated by the loop nudge's varying `subtask_id`; `loopSignature` now feeds the tracker raw output.
- Rejected-tool bubble flips to "✗ Failed" immediately, not after the post-turn DB reload.
- Guardian re-entry nudges render as a gentle "Agent guidance" note instead of a raw "STOP" wall.

## [0.0.1.10] - 2026-05-27

### Added

- `/goal <condition>` command sets a completion condition the judge re-enters the loop until met; TUI + IntelliJ chat, persists across restarts.
- LLM "next speaker" judge in AGENT: after a tool-call-free reply a weak-model call decides finished vs stopped, re-entering with a nudge (max 3). Toggle `general.next_speaker_judge_enabled`; fails to "pass".
- Content-chanting loop detection aborts on the same word n-gram repeated 10+ times consecutively.
- Anthropic prompt-prefix caching (stable/volatile split, 5-min TTL); cache tokens folded into reported `inputTokens`.
- Multi-agent A2A messaging: per-agent queues; `send_message` enqueues to a peer, `answer_message` replies to a specific message.
- Native function calling: per-provider test suites lock the wire format; tool-call extraction robustness fixes.
- Universal `<tool_use_enforcement>` block in the system prompts (replaces `ModelFamilyClassifier` injection); new `<task_planning>` block.
- PLAN iteration cap raised 50 -> 100 (warning at 30), matching AGENT.
- `EmbeddingCircuitBreaker` for embedding-provider failures.
- `CodeIntelligenceTool`, `GrepSearchTool`, `ReadFileTool`, `ReadDirectoryTool` - expanded actions, better formatting and token budgeting.
- `WebSearchTool`, `FetchWebpageTool`, `HttpRequestTool` - refined error handling and network-policy integration.

### Changed

- `TurnGuardrails` simplified to objectively-broken triggers only (empty envelope, native-text tool call, malformed JSON, output-hash repeat); prose-pattern detectors removed.
- Format-retry fires only on objectively broken output, not on legitimate plain-text answers in native-tools mode.
- `ModelFamilyClassifier` removed (replaced by the universal `<tool_use_enforcement>` block).

### Fixed

- `MultiAgentRunner` agent-instance-ID propagation edge cases.
- `ChatService`/`ContextService` refactors and fixes.
- `SubtaskTracker` lifecycle accuracy.

---

## [0.0.1.9] - 2026-05-05

### Added

- `DiffCompressor` elides tool-result diff bodies (recovery hint embeds `subtask_id`), saving ~8-14K tokens after a write.
- Centralized LLM stats in `LLMClient` - tokens/cost auto-incremented on `task`/`subtask` rows, not per call-site.
- Persistent native-tools fallback (`models.native_tools_fallbacks`) so the probe cost isn't paid every fresh process.
- Native function calling for OpenAI-compatible providers (OpenRouter, Z.AI, Generic OpenAI, LM Studio).
- Sub-LLM cost surfaced in tool-call bubbles (`advance_code_editing`, `multi_line_editor`, `fetch_webpage`, ...).
- `maxContextWindow` cached as a `StateFlow`, off the EDT/SQLite path.

### Changed

- AGENT task status transitions `NEW -> RUNNING -> SUCCESS/FAILED` (was stuck at `NEW`).
- Token-only session refresh skips 5 redundant `ConfigRepository` writes per turn.
- `SessionStatsCalculator` prefers the `task` row over per-message summing, fixing header/footer drift.
- `MessageDispatcher` re-attaches tokens from dropped messages to the last visible message.

### Fixed

- `OpenAIAdapter` no longer drops `prompt_tokens`/`completion_tokens` from the final usage block.
- Removed dead `SubtaskRepository.updateLlmMetrics` (kept the additive `incrementLlmMetrics`).
- Header/footer token drift after auto-name or thinking-only turns.

---

## [0.0.1.8] - 2026-04-27

### Added

- **Native function calling** - structured `tools` API instead of JSON-in-text; opt-in via `tools.native_tools: auto|always|never` (default `auto`). Covers OpenAI (Chat + Responses), Anthropic, and Gemini with per-provider schema sanitization.
- `ToolSchemaSanitizer` - provider-aware JSON Schema normalization (OpenAI strict, Anthropic keyword stripping, Gemini coercion).
- `NativeToolsFallbackTracker` - on a 400 "tools not supported", the model skips native tools for the rest of the process.
- `NativeToolsResolver` central decision logic (precedence: fallback cache -> NEVER -> ALWAYS -> `supportsFunctionCalling`).
- `ToolCallParser` XML-tag fallback recovers `<tool_name><arg>...</arg></tool_name>` pseudo-tags from weak local models.
- New models: GPT-5.5, GPT-5.4 Nano, Qwen 3.6 35B/27B, Qwen 3-next 80B, gpt-oss-safeguard 20B/120B, OpenRouter `qwen/*`.
- `claude-sonnet-4-6` added to `SupportedModels`.
- Per-iteration token/cost persisted to the task row for running totals without aggregation.

### Changed

- `AgentTurnLoop` auto-falls back to JSON-in-text on `ToolsNotSupportedException`, rebuilding the prompt.
- With native tools active, the `json_object` response-format override is suppressed.
- `TurnLLMCaller` forwards `nativeToolSchemas`; retry handler propagates `thinking`, `reasoningEffort`, `noEgressEnabled`.
- Subagent profiles filter the native `tools` array to their `<available_tools>` list.
- `ToolCallParser` `startsWith("{")` pre-guard removed (handles prose before the envelope); failed-parse log downgraded to `DEBUG`.

### Fixed

- Anthropic tool results mapped to `role: "user"` (was `"assistant"`), fixing HTTP 400 on claude-opus-4-6+.
- `temperature` no longer sent to models that deprecated it (`claude-opus-4-7`/`-4-6`, `gpt-5.5`, `gpt-5.4-nano`).
- Anthropic streaming captures the `tool_use` call `id` alongside the name.

---

## [0.0.1.7] - 2025-04-17

### Added

- New agent tools: `sleep`, `ask_user`, `web_search`, `fetch_webpage`, `run_process_background` + `monitor_process`, `code_intelligence`.
- Subagent overrides - customize built-in subagents locally without forking.
- Example `config.yaml` shipped under `core/src/main/resources/config/`.

### Changed

- Execution mode, thinking, and no-egress toggles moved to Settings -> General.
- `thinkingEnabled`/`noEgressEnabled`/`executionMode` moved from `ui:` to `general:` (old `ui:` values ignored).
- "Slash Commands" renamed "Prompts" (`/name` syntax unchanged, entries migrated).
- Terminal command rules unified into a single regex-based `ALLOW`/`BLOCK`/`ASK` ruleset.
- Models tab no longer stalls on open (discovery runs only via Refresh).
- Local provider listing timeouts tightened (Ollama/LM Studio: 3 s, was 15 s).
- Tools settings tab is responsive; YAML config is now typed (unknown keys raise errors).
- Session layer shared between plugin and CLI; Settings panels rebuilt on the typed config model.
- Built-in `system-agent` prompt refreshed.

### Removed

- "Enable No-Egress by default" checkbox removed (duplicated the General toggle).
- Multi-agent orchestration UI and strategies removed (main agent delegates via `invoke_subagent`).
- Various internal backcompat shims.

### Fixed

- UI no longer freezes while persisting session state.
- Silent fallbacks removed from OpenAI parsing, terminal-rule compilation, and `http_request`.
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
- RECENT_WORK is budget-driven (FULL -> DETAILED -> SUMMARY) and includes failed tool calls.

### Fixed

- `http_request` egress checks, regex safety, approval/trust race conditions.
- Subagent recursion tracking, `CoreApiRouter` shutdown cleanup, file-write locking.
- Image/PDF handling in `read_file` and multimodal provider payloads.
- Chat message ordering when streaming and tool output interleave.
- Weak models silently ending a turn without the JSON envelope are nudged back to format.

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

- **Three execution modes** - CHAT (conversational), PLAN (read-only analysis), AGENT (full read/write), all backed by a Codex-style `AgentTurnLoop` with subtask tracking and safety limits.
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
