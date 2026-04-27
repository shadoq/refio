# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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
