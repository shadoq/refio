# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.0.1.12] - 2026-07-02

### Added

- Headless CLI scriptable flags for driving turns without the GUI: `--output json` + `--output-file` write a `run.json` with per-turn metrics (tokens/cost/iterations/status); `--debug-level minimal|standard|full|judge`; `--config k=v` / `--config-file` run-scope overrides; `--print-config` (dry-run, no LLM call); `--max-cost <usd>` hard per-session ceiling; `--auto-approve "<regex>"` approval gate; `--no-egress`. Turn/tool events streamed to stderr and `~/.refio/refio-cli.log`; `-v` additionally streams live LLM token deltas.
- Unified tool-call extraction with an explicit reason logged on every failed attempt - closes a silent failure mode where a weak model returning prose caused an early turn finish without any indication why.
- Native function calling extended to OpenAI-compatible adapters (OpenRouter, Z.AI, Generic OpenAI, LM Studio) and Gemini, joining the existing Ollama / OpenAI / Anthropic paths.
- Native-tools fallback now persists across process restarts via `models.native_tools_fallbacks`; models that failed the native-tools probe are not re-probed on the next run.
- Per-provider tool-schema normalization so the same registered tool schema is accepted by every LLM provider.
- `tools.native_tools` config (`auto` | `always` | `never`) with a General-settings dropdown; debug log shows which path was chosen and why.
- Live tool-call indicator in chat and TUI - shows the tool name and arguments while a call is streaming, clears when done.
- E2e regression harness (`benchmark/scripts/e2e-run.sh` + Windows parity) drives JSON scenarios headless and asserts on the result. Not part of `./gradlew test` (slow, requires a model). `--self-test` validates the assertion engine offline without an LLM call.
- Compressed tool results now include a recovery pointer so the agent knows output was shortened and can retrieve the full content via the memory tool.
- HTTP responses larger than 64 KB are auto-saved to a `.refio_http_*.txt` file with a `read_file` pointer instead of being truncated into the conversation, keeping multi-MB API payloads (e.g. a 6.8 MB CSV) out of the context window.
- "Change approach" nudges when an agent thrashes one operation: 2+ failed edits of the same file, or 2+ back-to-back `run_code` / `run_terminal_command` failures, inject a SYSTEM hint to isolate the failing piece and work in smaller steps.
- New model definitions in the registry - Anthropic `claude-sonnet-5` and `claude-mythos-5`, plus Ollama cloud models `minimax-m3`, `ornith` (9b/31b/35b/397b), `glm-5.2`, `kimi-k2.7-code` (+ high-speed variant), `deepseek-v4-pro` / `deepseek-v4-flash`, `step-3.7-flash`, `nemotron-3-ultra` / `nemotron-3-nano-omni`, and `mimo-v2.5-pro` - so their context window, pricing, and capability flags resolve correctly.
- E2e stabilization gate - each scenario runs N times and is reported as a pass-rate verdict instead of a single sample, with per-scenario baselines, a commit-attributed trend history, and guardrail failure markers surfaced in `run.json` (`metrics.failureMarker`, classified into loop-aborted / noop-write-stall) so a flaky pass is measured as a rate. Driven by one command that loops the runner into an aggregator (`cli --gate`).
- Per-model / per-tool benchmark statistics - the persisted e2e run records aggregate into a model leaderboard, a scenario-by-model matrix, and a tool-use histogram (`benchmark/scripts/e2e-stats.sh`, read-only, with an offline self-test).
- Application-build and CTF e2e scenario classes - multi-file build tasks gated by a real `build_cmd` (or a Playwright browser smoke test for web pages), and capture-the-flag scenarios gated on a constant flag string in the agent's output, including a custom vulnerable-server hook for a real path-traversal case.
- CI now enforces the full Gradle `check` on every PR (module tests + the Jacoco coverage gate + the sensitive-logging scan, previously decorative), plus a new IntelliJ Plugin Verifier job, a nightly workflow (full check + the e2e harness offline self-test), and a tag-triggered release workflow (GitHub release with the plugin ZIP; Marketplace publish when signing/publish secrets are configured).
- "Generate missing embeddings" button in the RAG panel - the red "N chunks missing embeddings" warning now has a one-click action wired to the same embedding path the settings panel uses, visible only when embeddings are actually missing.

### Changed

- Legacy plan/step execution path removed. PLAN/AGENT/headless all run through a single `AgentTurnLoop`. Headless runs previously only produced a plan and never executed tools or wrote files - this is fixed.
- Headless turns now stream, fixing slow/cold local models that were killed at the socket-idle timeout while producing a large response body (`qwen3.5:4b/9b` previously timed out headless, now complete).
- Stream idle timeout ceiling raised 300 s -> 600 s to accommodate large models with slow first-token latency.
- Small qwen3.5 models (`0.8b`/`2b`/`4b`/`9b`) now use the native tool-calling path in `auto` mode; degrades to JSON automatically if the local runtime has no tool parser.
- Truncated-response detection unified into the extraction layer; the turn loop reacts to the extraction result instead of re-deriving it.
- High-frequency internal log lines demoted to trace so the in-app "Debug Logs" view stays readable.
- Agentic search is now the default; vector RAG is opt-in (`rag.index_on_startup: false`). `grep_search` ranks declaration hits above plain usages so an agent sees definitions before call sites.
- Subagent depth ceiling enforced before spawn - `invoke_subagent` refuses a call that would exceed depth 3 immediately, without allocating a child turn.
- Native-tools demotion now requires two consecutive JSON-envelope-in-text slips instead of one; a single slip retries on the JSON path for that turn only, without permanent demotion.
- RAG indexing and embedding moved off the shared `Dispatchers.IO` pool onto a dedicated bounded thread pool, isolating background indexing from turn execution.
- `llm_call` tool description now states it is the way to run an LLM step (classification / tagging / extraction / vision) from a task, and to use it instead of calling OpenAI/Anthropic HTTP APIs from `run_code` (the sandbox has no API keys, so those calls fail with 401).
- Guardian re-entry budget is now per stall-episode instead of per whole turn: after the agent makes sustained progress (3+ new tool calls) following a re-entry, the single bounded re-entry is replenished, so a budget spent early on a trivial pause still protects a genuine stall near the end of the turn.
- Headless `--model X` now applies to every model slot - turn, code editing (`advance_code_editing` / `multi_line_editor`), plan, and subagents - by folding into `ui.selected_model`, matching the intuitive "use X everywhere" semantics. Previously `--model` set only the turn LLM, so file content could be generated by a different model than the one named on the command line. An explicit `--config ui.selected_model=...` still wins.
- Internal decomposition of the three largest service classes, behavior-preserving and test-verified: the turn executor's main loop lost ~180 lines to a per-turn persistence binding and five extracted phases (native-schema resolution, tool timing, event emission, A2A response waiting, repetition tracking); the project analyzer (2050 -> 783 LOC) split into dependency parsers, per-language analyzers, domain scorer, and architecture detector; the context service (1606 -> 1191 LOC) split out tool-output formatting and prompt-section token accounting.
- Chat input and panels use IDE icons and scale-aware sizing instead of emoji and fixed pixels: Send/Stop buttons and the mode selector dropped emoji glyphs, Debug panel health markers are plain OK/WARN/ERR text, and fixed dimensions go through the IDE scaling helper on HiDPI.
- Diagnostic and settings panels got a systematic pass: the Debug panel shows real sortable tables (full values on hover) instead of monospaced text dumps; the Logs and API Logs views update incrementally, preserving selection and scroll, instead of rebuilding the whole table; the Logs detail popup is now a standard IDE dialog; API Logs paginate with a "Showing X of N" counter and a "Load more" control instead of a silent 50-row cap; snippet cards update the changed card only instead of rebuilding the whole strip; heavy tool-window tabs initialize lazily on first open behind a spinning loader; and the Prompts / Subagents settings lists use the standard IDE add/remove/edit toolbar.

### Fixed

- Ollama streaming turns no longer fail when the `done:true` sentinel is missing but a tool call was already captured - a valid call is no longer discarded on an incomplete stream.
- Native tool schemas now counted in the context budget so AGENT prompts no longer silently overflow `num_ctx` on Ollama with many tools active.
- API-log `source` / `taskId` now reflect the actual request instead of being frozen from the first request that created the adapter pool entry; PLAN and AGENT turns are now distinguishable in the Source column.
- Bracket-labeled tool batches emitted as text (`[TOOL] read_file: path="..."`) are now recovered instead of being dropped as an unrecognized format.
- Paraphrase-stall detection - a model re-wording the same intent sentence across iterations (word-level near-duplicate) now triggers the repetition abort the same way a byte-identical repeat does.
- Stray tool-call JSON residue no longer leaks into assistant chat bubbles when a model without native function-calling emits calls as text.
- Stalled turns on weak native-tool models now retry on the JSON-in-text path instead of re-entering the same channel that just produced no tool calls.
- Mid-turn message reload no longer drops in-memory bubbles not yet persisted to the database; the user's prompt and live streaming bubbles no longer blink out during an active turn.
- Models that return their answer in `reasoning_content` with an empty `content` field (some GLM / Z.AI / DeepSeek responses) no longer produce a blank reply - the reasoning text is used as a fallback on both the streaming and non-streaming OpenAI-compatible paths.
- `run_terminal_command` and `run_code` decode process output as UTF-8 and set `PYTHONUTF8` / `PYTHONIOENCODING` (plus a PowerShell UTF-8 prefix on Windows), so non-ASCII output (e.g. Polish text) is no longer garbled.
- A plain-prose answer produced while in JSON-envelope mode is now surfaced and the turn marked INCOMPLETE, instead of being discarded as a hard FAILED with the answer lost.
- `llm_call` returning an empty response is now reported as an error instead of silently saving an empty file.
- OpenAI-compatible adapter cost is computed from the pricing table instead of being hardcoded to zero, so the API Logs cost column matches the trace cost.
- Context section panels no longer freeze the IntelliJ UI on large tool results - the HTML is rendered lazily on expand and capped in size.
- `advance_code_editing` no longer writes a model's prose, apology, or refusal into a file as its contents when the reply has no code fence - only a fenced block is accepted; a fence-less reply goes through the edit-repair loop and then fails loud with the original file left untouched.
- `multi_edit` validates and computes every edit before writing any file, so a later failing edit no longer leaves earlier files half-written (atomic for logical failures; same-file cumulative edits are preserved).
- A streamed LLM call is no longer retried once chunks have been emitted to the UI, so a mid-stream failure no longer replays the whole response concatenated onto the partial one.
- Embeddings cache key is computed with a fresh `MessageDigest` per call - the previously shared, stateful digest could be corrupted by concurrent callers and return one text's embedding for another (cache poisoning) or produce spurious misses.
- Resuming a turn after a tool-approval pause now yields the SQLite write lock to background RAG indexing for the turn's duration, like a normal turn - a resumed turn previously let indexing grab the lock and stall tool-status writes.
- Single non-streaming write tools (`write_file`, `multi_edit`, overwrites) are snapshotted before execution so their edits can be rolled back; previously only streaming editors and mixed parallel batches were snapshotted.
- Tool-call success is tracked as a structural result flag instead of being inferred from an `Error:` text prefix, so a tool whose output legitimately begins with `Error:` (reading a log file, a grep hit on an `Error:` line) is no longer miscounted as a failure by the turn's error-rate and repeated-failure guards.
- A single execution step card no longer stretches to fill the whole Execution tab (the "green wall"); step cards keep their natural height, use a neutral background with a colored status stripe, and the destructive "Delete All" moved to the right edge of the toolbar.
- Session History no longer reserves a dead content band under the All/Chat/Plan/Agent filter (toggle buttons replaced the tabbed pane) and token counts are shown compactly (2.4M / 16.4K) with the exact value in the tooltip.
- RAG file paths ellipsize in the middle (keeping the filename visible) with the full path in a tooltip, instead of cutting off the tail.
- Assistant chat bubbles no longer duplicate tool-result markers (`[Tool result: ...]`, "File created successfully:") that a model echoes into its own prose - those are stripped in the presentation layer since the same information already appears in the tool bubble.
- File names containing `<` or `&` no longer corrupt the chat bubble HTML render; paths are HTML-escaped before display.
- A tool bubble no longer shows a false red "error" status when the tool output merely contains the substring " failed" or "error:" (a code diff, a grep hit) - the status now comes from the tool call's actual result flag, with substring scanning kept only as a fallback.
- Large tool-call parameter values (e.g. a full HTML document passed to a tool) are shown in an expandable panel instead of a 60-character truncated label with no way to see the rest.
- The RAG "Search Test" results are a list of clickable file paths that open the file, instead of a monospaced debug dump; the section is collapsed by default.

### Security

- Command sandbox no longer auto-approves a chained line on the strength of its first program - a command carrying a shell chaining, substitution or redirection operator (`;`, `&`, `|`, backtick, `$(...)`, `>`, `<`, newline) falls through an `ALLOW` rule to `ASK` so the whole line is reviewed (`git status; rm -rf /` is no longer auto-run because `git` was allowed, and `cat x > build.gradle.kts` no longer lets an allowed read-only program overwrite a file unreviewed). `BLOCK` still wins.
- The no-egress gate no longer fails open - a config read error falls back to the last successfully read flag value, and with no prior successful read it fails closed (egress blocked) instead of silently allowing outbound traffic. The config default stays "no-egress disabled".
- SSRF guard validates every DNS record a host resolves to, not just the first - a host answering with a mix of public and private/loopback addresses (the DNS-rebinding pattern) is now blocked, since the HTTP client may connect to any of the records.
- `run_process_background` now defaults to `OFF` in PLAN and `ASK` in AGENT (like `run_terminal_command`) - it spawns arbitrary shell commands and previously ran unreviewed in AGENT as a plain write tool defaulting to `ON`.
- `http_request` / `fetch_webpage` re-apply the SSRF / network-policy check on every redirect hop - a 30x from an allowed public host to a loopback or internal address is now blocked instead of followed (the guard previously validated only the initial URL).
- `git clean` force flag is blocked in any order or grouping (`-xfd`, `-d -x -f`, `--force`), closing an auto-approve path that previously caught only `-f` immediately after `clean`.

## [0.0.1.11] - 2026-05-31

### Added

- `INCOMPLETE` task status — turns that stop without delivering the request (a completion guardian gives up, or a no-op-write / read-spree abort fires) are recorded as `INCOMPLETE` instead of `SUCCESS`. Shown with its own colour in the TUI and a `◐ Incomplete` badge in the IntelliJ History panel.
- Read-spree consolidation nudge — after a long run of read/search calls with no write or delivery, a SYSTEM nudge tells the agent to persist to memory or deliver incrementally (top-level AGENT only). Targets the "read 25 files, write nothing" failure mode.
- No-op-write streak abort — a WRITE that changes nothing three times in a row on the same target aborts the turn as `INCOMPLETE`; previously a no-op reported success and reset every progress guard, so a futile-edit loop ran to the iteration cap.
- `ConsecutiveTextRepetitionTracker` — aborts when the model repeats byte-identical final text with no tool call across iterations (a nudge re-entry it answers by repeating itself).
- MCP global-server tool exposure — `GLOBAL`/`TOOLS` servers now register `mcp_<server>_*` tools into every project registry (and the CLI's), so the agent can actually call them; the `context7` preset switched `CONTEXT` → `TOOLS`, with a fail-loud `WARN` and a settings hint when a server exposes no tools. Caveat: a server already in the DB stays `CONTEXT` until re-added/edited.
- `ModelWindow` — single context-window resolver, replacing four that disagreed and silently truncated the Ollama prompt; `claude-opus-4-8` (Claude Opus 4.8) added to `ModelDefinitions`.

### Changed

- Next-speaker judge now runs in PLAN as well as AGENT, and passes the tool-use *count* (not just distinct names) so a "use tool X three times" task isn't judged done after one call; recovers a stashed answer on an empty native reply instead of re-entering.
- Loop detectors exempt pure-symbol runs — a requested ASCII diagram (`│`) or `────`/`====` separator rule no longer trips the content-chanting or streaming repetition aborts.
- LLM stream idle-timeout clamped to 300 s, independent of the total API-call timeout, so a dead stream fails fast instead of hanging for the full timeout (observed: a 122B model stream hung 53 min).
- Ollama request sizing — `num_predict` is clamped so input + output fits inside `num_ctx`, with an overflow warning before the server silently truncates; a streaming watchdog makes Stop respond immediately on slow models.
- RAG quality / indexing — duplicate and fully-contained chunks dropped at chunk time and in `rag_search` results (top-K no longer fills with copies of one fragment); out-of-range chunk line bounds clamped (no more index-pass crash); chunk/embedding inserts batched and the active turn yields the SQLite WAL writer-lock to cut contention that stalled tool writes ~122 s.
- Context-panel auto-refresh now fires only on session change (was re-running a full `getProjectContext()` every ~1.5 s); token bar / prompt trace stay in sync via `lastPromptSnapshot`, sections refresh on demand. Preview path skips the redundant project-context rebuild. Removed unused `loop*` fields from `TurnLoopConfig`; `PathSandbox` init logs at `debug`.

### Fixed

- Output-repetition hard-abort defeated by the loop nudge — the "[⚠ possible loop]" nudge appended a varying `subtask_id` to the output, defeating the byte-identical tracker; `ToolResultData.loopSignature` now feeds the tracker the raw, un-nudged output.
- Rejected-tool bubble flips to "✗ Failed" immediately instead of waiting for the post-turn DB reload.
- Guardian re-entry nudges render as a gentle "Agent guidance" note (attributed to the originating subagent) instead of a raw "STOP — the turn is NOT finished" wall of text.

## [0.0.1.10] - 2026-05-27

### Added

- `/goal <condition>` command — set an explicit completion condition for the active task; the LLM judge keeps re-entering the loop until the condition is met from transcript evidence. Available in TUI (`/goal …`, `/goal clear`) and the IntelliJ chat input (intercepted mid-execution). Persists across restarts. Solves weak models stopping mid-task ("I've migrated the main models. Done.") before tests actually run.
- LLM "next speaker" judge in AGENT mode — after a tool-call-free reply, a cheap weak-model call decides whether the agent finished or just stopped. "Stopped" verdict re-enters the loop with a brief SYSTEM nudge; capped at 3 re-entries per turn. Toggle: `general.next_speaker_judge_enabled` (default on). Falls back to "pass" on any judge error so a broken judge never blocks an otherwise-finished turn.
- Content-chanting loop detection — aborts the turn when the assistant message contains the same word n-gram repeated 10+ times consecutively (model echoing itself, runaway lists). Adjacent-repetition only, so legitimate enumerations and bullet lists don't trip it.
- Anthropic prompt-prefix caching — system prompt split into stable / volatile parts; subsequent turns billed at the ~10% cache-hit rate while the prefix stays identical (5-min TTL). Token accounting folds `cache_creation_input_tokens` + `cache_read_input_tokens` into the reported `inputTokens` so billing dashboards still match.
- Multi-agent A2A messaging — each agent gets its own message queue; `send_message` enqueues to a peer, `answer_message` replies to a specific inbound message instead of broadcasting. Integration tests cover per-agent scoping when multiple agents share a task.
- Native function calling — per-provider test suites (Anthropic, Ollama, OpenAI, `NativeToolsResolver`) lock the wire format; minor robustness fixes around tool-call extraction in `OllamaAdapter` / `OpenAIAdapter`.
- Universal `<tool_use_enforcement>` block in `system-agent.md` / `system-plan.md` — replaces the previous `ModelFamilyClassifier`-based dynamic injection. 250 tokens are negligible on strong models and meaningful on weak ones. `system-agent.md` also adds a `<task_planning>` block pushing the `tasks` tool harder for non-trivial multi-step work.
- PLAN iteration cap raised 50 → 100 (warning at 30), matching AGENT and aligning with Gemini CLI / Hermes. PLAN is read-only so extra iterations are cheap.
- `EmbeddingCircuitBreaker` — resilience layer for embedding provider failures.
- `CodeIntelligenceTool`, `GrepSearchTool`, `ReadFileTool`, `ReadDirectoryTool` — expanded actions, improved output formatting, refined token budgeting.
- `WebSearchTool`, `FetchWebpageTool`, `HttpRequestTool` — refined error handling and network policy integration; new `NetworkPolicyTest`.

### Changed

- `TurnGuardrails` simplified — removed `looksLikeIntentAnnouncement` / `looksLikeToolMarkerOnly` prose-pattern detectors and the count-based abort in `TurnRepetitionTracker`. Only objectively-broken triggers remain (empty envelope, native-text-embedded tool call, malformed JSON, output-hash repeat). Aligns with Codex / Claude Code: trust the model, don't algorithmically detect "lapsed into prose".
- `AgentTurnLoop` format-retry only fires on objective broken outputs — legitimate plain-text final answers in native-tools mode no longer get nudged into a JSON envelope they weren't asked to emit.
- `ModelFamilyClassifier` removed — replaced by the universal `<tool_use_enforcement>` block.

### Fixed

- `MultiAgentRunner` — edge cases around agent instance ID propagation through the turn loop.
- `ChatService` / `ContextService` — minor refactors and bug fixes.
- `SubtaskTracker` — improved lifecycle accuracy.

---

## [0.0.1.9] - 2026-05-05

### Added

- `DiffCompressor` — content-aware elision of tool-result diff bodies (small / pure-create / large-mixed paths); recovery hint embeds the literal `subtask_id`. Saves ~8-14K tokens on the iteration after a write tool.
- Centralized LLM stats in `LLMClient` — `tokens_in` / `tokens_out` / `cost_usd` auto-incremented on the `task` and `subtask` rows after every successful call; the ~20 `complete()` call-sites no longer track metrics manually.
- Persistent native-tools fallback — new `models.native_tools_fallbacks` config key; `NativeToolsFallbackTracker.bind(configService)` hydrates on startup and mirrors writes back, so users no longer pay the 2-nudge probe cost on every fresh process.
- Native function calling for OpenAI-compatible providers (OpenRouter, Z.AI, Generic OpenAI, LM Studio) via shared `OpenAICompatibleHelpers.buildOpenAIToolsArray` / `parseOpenAIToolCalls`.
- Sub-LLM cost surfaced in tool-call bubbles — `ToolResultSummary` carries token/cost so `advance_code_editing`, `multi_line_editor`, `fetch_webpage`, etc. show their inner-LLM cost alongside the parent assistant call.
- Cached `maxContextWindow` as `StateFlow` in `SessionManager` — Status bar / Settings / Context panel no longer hit SQLite from the EDT.

### Changed

- AGENT task status now transitions `NEW → RUNNING → SUCCESS/FAILED` (was stuck at `NEW`).
- `SessionLifecycleService.updateSession(persistSettings)` — token-only refresh paths pass `false` to skip 5 redundant `ConfigRepository` writes per turn.
- `SessionStatsCalculator` prefers `session.tokensIn/Out/costUsd` from the `task` row over per-message summing — fixes header/footer drift after auto-name turns.
- `MessageDispatcher` re-attaches tokens from dropped messages (legacy `TOOL_CALL:` envelopes, empty assistant bubbles) to the last visible message.

### Fixed

- `OpenAIAdapter` streaming usage parsing — `prompt_tokens` / `completion_tokens` no longer dropped from the final usage block.
- Removed dead `SubtaskRepository.updateLlmMetrics` (SET semantics) — only the additive `incrementLlmMetrics` is kept.
- Header/footer token drift after auto-name or thinking-only turns.

---

## [0.0.1.8] - 2026-04-27

### Added

- **Native function calling** — agents can now use providers' structured `tools` API instead of the JSON-in-text envelope. Opt-in per model via `tools.native_tools: auto | always | never` in `config.yaml`; default is `auto` (enabled for models with `supportsFunctionCalling=true`).
  - OpenAI (Chat Completions + Responses API) — strict-mode schema sanitization, `tool_choice: auto`.
  - Anthropic — `tools` array + `content[].tool_use` response parsing; schema sanitized (composition keywords stripped).
  - Gemini — `functionDeclarations` array + `functionCall` response parsing; Gemini-specific type normalization.
- `ToolSchemaSanitizer` — provider-aware JSON Schema normalization (OpenAI strict compatibility check, Anthropic forbidden-keyword stripping, Gemini single-type coercion and nullable fields).
- `NativeToolsFallbackTracker` — session-scoped in-memory registry; if a provider returns 400 "tools not supported", the model is marked and all subsequent requests in the same process skip native tools automatically.
- `NativeToolsResolver` (`NativeToolsMode` enum: `AUTO / ALWAYS / NEVER`) — central decision logic with precedence: fallback cache → NEVER → ALWAYS → `ModelDefinition.supportsFunctionCalling`.
- XML tag fallback in `ToolCallParser` — recovers tool calls from `<tool_name><arg>…</arg></tool_name>` pseudo-tags emitted by weak local models (qwen<70B, some Gemma builds) that bypass the native API entirely. Warns on use.
- New models: **GPT-5.5**, **GPT-5.4 Nano**, **Qwen 3.6 35B/27B** (Ollama), **Qwen 3-next 80B**, **gpt-oss-safeguard 20B/120B**, OpenRouter `qwen/*` patterns.
- `claude-sonnet-4-6` added to `SupportedModels`.
- Per-iteration token and cost metrics persisted directly to the task row so the History panel and live stats bar show running totals without aggregating from message metadata.

### Changed

- `AgentTurnLoop` auto-falls back to the JSON-in-text path when a provider throws `ToolsNotSupportedException` — prompt is rebuilt without `<available_tools>` and the JSON envelope contract; no user action needed.
- When native tools are active the `response_format` / `json_object` override is suppressed (incompatible with function-calling mode on most providers).
- `TurnLLMCaller` accepts `nativeToolSchemas` and forwards them as `native_tools` in `kwargs`; retry handler also propagates `thinking`, `reasoningEffort`, and `noEgressEnabled`.
- Subagent profiles filter the native `tools` array to match their `<available_tools>` list — prevents the model calling tools the harness would reject.
- `ToolCallParser.extractToolCalls` pre-guard (`startsWith("{")`) removed — some models (e.g. glm-4.7-flash) emit prose before the JSON envelope, which the brace-matching strategy handles correctly. All-strategies-failed log downgraded from `WARN` to `DEBUG`.

### Fixed

- Anthropic tool results were mapped to `role: "assistant"` — now correctly mapped to `role: "user"`, fixing HTTP 400 rejections from claude-opus-4-6 and newer models that treat assistant-role tool results as prefill.
- `temperature` parameter no longer sent to models that deprecated it (`claude-opus-4-7`, `claude-opus-4-6`, `gpt-5.5`, `gpt-5.4-nano`) — removes spurious provider warnings.
- Anthropic streaming: `content_block_start` for `tool_use` blocks now captures the call `id` alongside the name.

---

## [0.0.1.7] - 2025-04-17

### Added

- New agent tools: `sleep`, `ask_user`, `web_search`, `fetch_webpage`, `run_process_background` + `monitor_process`, `code_intelligence`.
- Subagent overrides — customize built-in subagents locally without forking their source.
- Example `config.yaml` shipped under `core/src/main/resources/config/`.

### Changed

- Execution mode, thinking, and no-egress toggles moved from the chat input to **Settings → General**.
- `thinkingEnabled` / `noEgressEnabled` / `executionMode` moved from `ui:` to `general:` in `config.yaml`. Values still under `ui:` are ignored — reconfigure once from the General tab.
- Feature renamed "Slash Commands" → "Prompts". `/name` syntax unchanged; existing entries migrated automatically.
- Terminal command rules unified into a single regex-based `ALLOW` / `BLOCK` / `ASK` ruleset.
- Models tab no longer stalls on open — provider discovery runs only via **Refresh**.
- Local provider listing timeouts tightened (Ollama/LM Studio: 3 s, was 15 s).
- Tools settings tab layout is responsive; YAML config is now typed (unknown keys raise errors).
- Session layer shared between plugin and CLI; Settings panels rebuilt on the typed config model.
- Built-in `system-agent` prompt refreshed.

### Removed

- "Enable No-Egress by default" checkbox (Advanced Settings) — duplicated the new General toggle.
- Multi-agent orchestration UI and strategies — the main agent now delegates via `invoke_subagent` on its own.
- Various internal backcompat shims.

### Fixed

- UI no longer freezes while persisting session state.
- Silent fallbacks removed from OpenAI parsing, terminal rule compilation, and `http_request` — bad input surfaces as real errors now.
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
- Weak models silently ending a turn without the JSON envelope — now nudged back to format.

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

Initial release — a local-first AI coding assistant for IntelliJ IDEA.

Highlights:

- **Three execution modes** — CHAT (conversational), PLAN (read-only analysis), AGENT (full read/write) — all backed by a Codex-style `AgentTurnLoop` with subtask tracking and safety limits.
- **10 tools** (5 read-only, 5 write) including `multi_line_editor` and `advance_code_editing`; `run_terminal_command` disabled by default.
- **18 context providers** via `@file`, `@folder`, `@current`, `@codebase`, `@grep`, `@commit`, `@docs`, `@url`, and more.
- **RAG with local embeddings** (Ollama + OpenAI), hybrid semantic+keyword search, incremental indexing.
- **6 LLM providers** — Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio.
- **MCP protocol** support (stdio + HTTP/SSE) with 16 built-in server presets.
- **Subagents** with YAML frontmatter (Claude Code compatible), built-in `security-reviewer` and `code-reviewer`.
- **Security layers** — `PathSandbox`, `FileLimits`, `CommandDenylist`, per-mode tool permissions, no-egress mode, secret redaction.
- **Config hierarchy** — DB (Settings UI) > project `.refio/config.yaml` > user `~/.refio/config.yaml` > built-in defaults.
- **SQLite persistence** via Exposed ORM (sessions, chat history, snapshots, API logs, RAG index).
- Native IntelliJ Swing UI with chat view, `@` autocomplete, context panel, and 12+ settings panels.

See `docs/` for full architecture details.
