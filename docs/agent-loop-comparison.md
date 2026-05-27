# 0Agent Turn Loop: Refio vs Industry Comparison

**Status**: Reference document
**Audience**: Engineers working on `AgentTurnLoop`, `TurnPromptBuilder`, `TurnGuardrails`, or any extension that changes loop semantics.

This document captures a comparative analysis of how Refio's agent turn loop is structured relative to other production AI coding agents. It exists because the architectural choices are not obvious from reading the code alone — many decisions look unusual until you see what the alternatives are.

---

## 1. Purpose & When to Consult

Consult this document when you are:

- Adding a new guardrail or heuristic to `AgentTurnLoop` — check if other systems already addressed the same failure mode and how.
- Refactoring loop iteration boundaries, termination conditions, or tool dispatch.
- Designing a new feature (e.g. subagent variant, working memory expansion) — check what's standard practice elsewhere.
- Reviewing whether existing scaffolding is still load-bearing — knowing what minimal-scaffolding agents (Codex, Claude Code) do without that scaffolding is the calibration point.
- Onboarding to the loop subsystem — read this alongside `core/services/AgentTurnLoop.kt` and `docs/ARCHITECTURE.md`.

**Do not** treat this as a roadmap. Patterns from other agents are not all good fits for Refio; the comparison is descriptive, the adoption decisions live in `docs/ROADMAP.md`.

---

## 2. Systems Surveyed

| System | Repo / Source | Language | Loop entry | Notes |
|---|---|---|---|---|
| **OpenAI Codex CLI** | [`openai/codex`](https://github.com/openai/codex) | Rust | `codex-rs/core/src/session/turn.rs::run_turn` | Frontier-only, native function calling only |
| **Claude Code** | proprietary (CLI) | TypeScript | `sendMessageStream` | Documented in [agent-loop docs](https://code.claude.com/docs/en/agent-sdk/agent-loop) |
| **Aider** | [`Aider-AI/aider`](https://github.com/Aider-AI/aider) | Python | `aider/coders/base_coder.py::run_one` | Edit-format paradigm (not tool-call) |
| **Continue.dev** | [`continuedev/continue`](https://github.com/continuedev/continue) | TypeScript | `gui/src/redux/thunks/streamNormalInput.ts` | Recursion-based loop, no production iter cap |
| **Hermes Agent** | [`NousResearch/hermes-agent`](https://github.com/NousResearch/hermes-agent) | Python | `agent/conversation_loop.py` | Closest philosophy to Refio's multi-layer scaffolding |
| **Gemini CLI** | [`google-gemini/gemini-cli`](https://github.com/google-gemini/gemini-cli) | TypeScript | `packages/core/src/core/client.ts::sendMessageStream` | Most sophisticated loop detection |
| **Firecrawl FIRE-1** | [`firecrawl/firecrawl`](https://github.com/firecrawl/firecrawl) | TypeScript | `apps/api/src/lib/scrape-interact/browser-agent.ts` | Web-browsing agent only, not coding |
| **oh-my-openagent** | [`code-yeongyu/oh-my-openagent`](https://github.com/code-yeongyu/oh-my-openagent) | TypeScript | (plugin — host owns loop) | Plugin/harness for OpenCode, no own loop |

All findings below were verified against actual source code or first-party documentation as of 2026-05.

---

## 3. High-Level Topology

```
REFIO                           CODEX                    CLAUDE CODE
─────                           ─────                    ───────────
runTurn()                       run_turn()               sendMessageStream()
  ↓                               ↓                        ↓
while (iter < max):             loop {                   recursive:
  build_prompt                    needs_follow_up=...      tool_use? → exec → recurse
  call_llm (stream)               if !needs: break         else: end
  parse_response                  call_model            (no iter cap by default
  guards (format-retry)           dispatch_tools          unless max_turns set)
  execute_tools (parallel RO)     send_result_back
  guards (repetition/errors)    }
  continue or finalize

HERMES                          GEMINI CLI               AIDER
──────                          ──────────               ─────
while (count < 90):             while (depth < 100):     while (msg):
  call_llm                        Turn.run() generator     send_message()
  parse_tool_calls                yield chunks             apply_edits()
  if tool: execute                handle_function_call     check_lint/test
  if no tool: break               loopDetection            if reflected_message:
  guardrails check                checkNextSpeaker           reflect (max 3)
  budget tracking                 auto-compact

CONTINUE                        FIRECRAWL FIRE-1         OMO (OpenCode plugin)
────────                        ────────────────         ─────────────────────
streamNormalInput()             generateText({          (host owns loop)
  ↓                               stopWhen:               omo: hooks before/after
  recurses via callTool           stepCountIs(25),        prompt_assembler
  no iter cap (prod)              tool: browser })        no own loop logic
  exits when no tool_calls
```

---

## 4. Dimensional Comparison Matrix

| Dimension | Refio | Codex | Claude Code | Hermes | Gemini CLI | Aider | Continue | Firecrawl |
|---|---|---|---|---|---|---|---|---|
| **Iter cap** | 100 PLAN / 100 AGENT | none | `max_turns` (opt) | 90 (50 sub) | 100 | 3 reflections | none | 25 |
| **Loop control flow** | `while` bounded | `loop` open | recursive | `while` bounded | recursive + generator | `while reflected` | recursive | SDK-managed |
| **Tool call format** | native + JSON-in-text | native only | native only | native + XML (trajectory only) | native only | edit-blocks | native + codeblocks | native (1 tool) |
| **Parallel tool dispatch** | yes (READ_ONLY) | sequential | yes | sequential | yes (explicit in prompt) | N/A | yes (read-only built-in) | N/A |
| **Approval flow** | `ToolApprovalService` 3-level | sandbox per command | tool-level | none | per-tool policy | `--yes-always` flag | per-tool 3-state | none |
| **Subagents** | yes (max depth 3) | no | yes (`Task` tool) | yes (delegation, own iter budget 50) | yes (as context compression) | no | no | no |
| **Working memory** | `memory(action="write")` tool | no | filesystem | trajectory + state.md | no | no | no | no |
| **Snapshots / undo** | `SnapshotService` SHA-256 | no (git) | no (git) | no | no | git-based | no | no |
| **Context compaction** | `ConversationSummaryService` | token-limit auto | `/compact` + auto | preemptive, 3-tier prompt | auto + LLM judge | `ContextWindowExceededError` | `setIsPruned` message dropping | none |
| **Streaming UI** | `StateFlow` × 11 + `AgentEventBus` | TUI | TUI | TUI | TUI / SDK | TUI | Redux store | none |
| **Modes** | CHAT / PLAN / AGENT | one | one (configurable) | one | Default / Plan / YOLO / Auto-Edit | edit-format selection | Agent / Chat / Plan | one |
| **Hooks** | `HookService` | none | yes (`SessionStart`, `PreToolUse`, etc.) | none | none | none | none | none |
| **Multi-agent A2A** | `AgentInboxRegistry` (peer-to-peer) | no | no | parent→child only | no | no | no | no |
| **Format retry on bad output** | nudge × 2 + hard fail | error → model self-repairs | `is_error:true` → model | 3 retries + fuzzy repair | none (next-speaker check) | reflection × 3 with diagnostic | 1-msg context item | none |
| **Repetition detection** | output-hash × 4 | none | none (known gap) | multi-threshold | tool-hash × 5 + content-chanting + LLM judge | none | none | none |
| **Tool error threshold** | 70% in window of 10 | none | none | warnings only | none | none | none | none |

---

## 5. Refio's 7-Stage Per-Iteration Pipeline

Inside `while (iteration < maxIterations)` of `AgentTurnLoop.executeTurnLoop`:

```
┌──────────────────────────────────────────────────────────┐
│ ITERATION i                                              │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ 1. BUILDING_PROMPT          ← TurnPromptBuilder          │
│    - stable: identity + tools + tool_use_enforcement     │
│    - volatile: iteration warning + sticky requirements   │
│    - context: history + project state + working memory   │
│                                                          │
│ 2. CALLING_MODEL            ← TurnLLMCaller              │
│    - retry handler (LLMRetryHandler)                     │
│    - streaming → AgentEventBus.StreamChunk               │
│    - thinking mode pass-through                          │
│                                                          │
│ 3. PROCESSING_RESPONSE      ← TurnResponseProcessor      │
│    - finishReason check                                  │
│    - thinking extraction                                 │
│    - native tool_calls extraction                        │
│                                                          │
│ 4. PARSING_TOOLS            ← ToolCallParser             │
│    - JSON envelope fallback (legacy JSON-in-text path)   │
│    - malformed envelope recovery                         │
│                                                          │
│ 5. GUARDS (pre-execution)   ← TurnGuardrails             │
│    - format retry (nativeTextEmbeddedToolCall)           │
│    - hard fail (plainTextNudge >= 2)                     │
│                                                          │
│ 6. EXECUTING_TOOLS          ← TurnToolExecutor           │
│    - READ_ONLY: parallel via coroutineScope + awaitAll   │
│    - WRITE / EXEC: sequential                            │
│    - APPROVAL: sync wait on ToolApprovalService          │
│    - SnapshotService snapshot before write               │
│    - WorkingMemoryIntegration record                     │
│    - subagent delegation: recursive into runTurn         │
│                                                          │
│ 7. GUARDS (post-execution)  ← TurnGuardrails             │
│    - ToolErrorTracker (70% over last 10 calls)           │
│    - TurnRepetitionTracker (output-hash × 4)             │
│    - consecutiveIdenticalFailures (same tool + args)     │
│                                                          │
│ → continue (next iter) or finalize (TurnFinalizer)       │
└──────────────────────────────────────────────────────────┘
```

**Closest architectural analog**: Gemini CLI has a similar decomposition (`Turn.run` → `handlePendingFunctionCall` → `loopDetectionService` → recurse). **Codex** keeps all of this inline in a single `run_turn` (~300 LOC vs Refio's ~2000 LOC).

---

## 6. Deep Dive: Per-Dimension Analysis

### 6.1. Three modes (CHAT / PLAN / AGENT) — unusual

Refio is the only surveyed agent with three distinct full-loop modes carrying separate system prompts, tool lists, and iteration caps.

- **Codex / Claude Code / Continue**: one mode, tools are all-or-nothing.
- **Gemini CLI**: has approval modes (Default / Plan / YOLO / Auto-Edit) but they **filter** tools in a single base prompt — they do not change loop logic.
- **Aider**: has chat-mode vs code-mode but on a different axis (interactive vs non).

**Where this helps Refio**: Explicit separation of read-only PLAN from full AGENT gives users a "investigate first, commit second" workflow that's hard to replicate by tool filtering alone.

**Where this costs Refio**: Three system prompts = 3× surface area to maintain. A future option is conditional sections inside one prompt (Claude Code's modular approach).

### 6.2. Tool execution semantics

- **Refio**: `coroutineScope { tools.map { async { exec(it) } }.awaitAll() }` for `ToolCategory.READ_ONLY`, sequential for WRITE / EXEC.
- **Codex**: always sequential.
- **Continue**: parallel for built-in read-only tools.
- **Gemini CLI**: parallel by default, explicit in prompt: *"set `wait_for_previous=true` if a tool depends on previous output"*.
- **Hermes**: one tool at a time.

**Observation**: Refio's auto-parallel by `ToolCategory.READ_ONLY` is cleaner than Gemini's model-deciding-via-parameter. But Gemini's pattern is more flexible (the model can serialize when needed).

### 6.3. Subagents — Refio is one of three with deep support

- **Refio**: `TurnRunProfile.SUBAGENT` + `profileOverrides.depth` ≤ 3, custom system prompt per subagent + filtered tools, history isolated via `agentInstanceId`.
- **Hermes**: `delegation.max_iterations = 50` (vs parent 90), thread-safe `IterationBudget`, similar concept.
- **Claude Code**: `Task` tool spawns isolated subagent invocations, not nested in same loop.
- **Gemini CLI**: subagent = "context compression" — execute, collapse output to summary, inject into parent history.
- **Codex / Continue / Aider / Firecrawl**: none.

Refio's pattern (3-deep nesting + per-instance isolation + profile-based tool/context filtering, persisted to SQLite via `agentInstanceId`) is the most fully-realized of the surveyed systems.

### 6.4. Working memory — Refio + Hermes + omo only

- **Refio**: dedicated `memory(action="write|read|get_subtask_output")` tool, persisted in SQLite, survives compaction.
- **Hermes**: trajectory + `state.md`, session-scoped.
- **omo**: external markdown file via `mktemp -t ulw-*.md`, survives compaction.
- **Codex / Claude Code / Continue / Aider / Gemini CLI**: filesystem only (the model writes a file).

Refio's approach is cleanest from a UX perspective (one tool to remember). Filesystem-based approaches (Claude Code writes to `CLAUDE.md`, `.refio/memory/`) are simpler but less explicit.

### 6.5. Streaming UI — most reactive architecture

- **Refio**: **11 `StateFlow`s** in `SessionManager`, `AgentEventBus` with `SharedFlow`, per-token streaming chunks with agent metadata (`runId`, `depth`, `agentName`).
- **Continue**: Redux store (event-driven, not reactive flow).
- **Claude Code**: TUI with immediate prints.
- **Codex**: TUI with streaming printer.
- **Aider / Hermes / Gemini CLI**: TUI or SDK consumer.

Refio is the only agent designed for a rich IDE UI as primary target. The cost: any loop change must consider 11 flows, which slows development.

### 6.6. Context compaction

- **Refio**: `ConversationSummaryService` + `WorkingMemoryService` + auto-compact gate in `executeTurnLoop`.
- **Gemini CLI**: `tryCompressChat` automatic + `ContextWindowWillOverflow` event.
- **Codex**: token-limit compaction built into `run_turn`.
- **Claude Code**: `/compact` manual + automatic at approach to cap.
- **omo**: preemptive-compaction hook + `context-window-monitor`.
- **Hermes**: `ConversationContext` with 3-tier prompt + tool result truncation.
- **Continue**: `setIsPruned` with message dropping.
- **Aider**: `ContextWindowExceededError` → abort.

Refio has the most elaborated compaction (3 distinct services). Gemini's single `tryCompressChat` with an LLM judge is simpler. Trade-off: Refio has more control, more code to maintain.

### 6.7. Hooks — Refio + Claude Code + omo only

- **Refio**: `HookService.trigger("before_turn_loop", mapOf(...))`, lifecycle events.
- **Claude Code**: `SessionStart`, `PreToolUse`, `PostToolUse`, etc. via `settings.json`.
- **omo**: entire architecture on hooks (`ralph-loop`, `todo-continuation-enforcer`, `model-fallback`, …).
- **Codex / Continue / Gemini CLI / Aider / Hermes**: none.

Refio sits middle ground — the mechanism exists, but the hook taxonomy is less elaborated than Claude Code's.

### 6.8. Snapshots / undo — Refio + Aider only

- **Refio**: `SnapshotService` before each write tool — SHA-256 hashing + compression, rollback available.
- **Aider**: git commit per round, rollback via `/undo`.
- **Codex / Claude Code**: rely on user's own git.
- **Continue / Hermes / Gemini / Firecrawl / omo**: none.

Aider's approach (git) is simpler but requires the project be under git. Refio's approach (`SnapshotService`) works everywhere but duplicates git functionality.

### 6.9. Multi-agent A2A — unique to Refio

- **Refio**: `AgentInboxRegistry` → `AgentMessageInbox` per agent, pending peer messages injected into prompt → `answer_message` tool.
- **All other systems**: none (Hermes has parent→child delegation but not peer-to-peer).

This is the most unique feature of Refio. No surveyed competitor supports peer-to-peer agent communication within a session.

### 6.10. Format-retry / nudge behavior

| System | Trigger | Bound | Diagnostic content |
|---|---|---|---|
| **Refio** | empty envelope, native-text-embedded tool, malformed JSON-in-text | 2 nudges + hard fail | concrete (regenerated) |
| **Codex** | parse error | none (model retries) | error string verbatim |
| **Claude Code** | `is_error: true` from tool | none | tool message back to model |
| **Hermes** | unknown tool / invalid JSON / truncated args | 3 retries | fuzzy-repair attempt + tool-not-found message |
| **Gemini CLI** | `InvalidStreamError` | terminates turn (no retry) | schema depth context |
| **Aider** | malformed edit block | 3 reflections | "Did you mean..." with similar lines |
| **Continue** | parser error | none (throws → dialog) | per-tool `preprocessArgs` feedback |

Refio's bound-and-emphatic approach is closest to Aider's reflection pattern, but Aider's diagnostic content is much more concrete ("similar lines from file"). A future improvement direction.

---

## 7. What Refio Does Uniquely Well

1. **PLAN mode** — explicit "read-only investigate first" delivers clear UX.
2. **Multi-agent A2A** — no surveyed competitor has this.
3. **`SnapshotService`** — undo without requiring git.
4. **3-deep subagents** — the deepest hierarchy.
5. **Reactive UI architecture** — richest `StateFlow` stack.
6. **`HookService`** — middle ground between "none" and "everything is a hook" (omo).
7. **`AgentEventBus` with metadata** — per-agent stream tracking with `runId` / `depth` / `agentName`.

---

## 8. Architectural Debt

1. **`AgentTurnLoop.kt` size: ~2000 LOC** vs Codex `run_turn` ~300 LOC. Further decomposition is worth considering — extract `executeTurnLoop` into a `TurnExecutor` component with `AgentTurnLoop` as a thin facade.
2. ~~Conservative iteration caps: 25 / 50 vs 90–100 elsewhere.~~ **Resolved 2026-05-26**: bumped PLAN 50→100, AGENT was already at 100. Both now match Gemini CLI's baseline.
3. **3 modes ≠ 3 prompts in maintenance terms** — could be 1 prompt with conditional sections (Claude Code's modular approach).
4. **No LLM judge** — Gemini's `checkNextSpeaker` is an elegant way to answer "is the model done?". Refio relies on heuristics.
5. **JSON-in-text fallback persists** — Hermes abandoned it (except for trajectory export). Refio still supports it, doubling complexity in `ToolCallParser`, `AgentTurnLoop` format-retry branches, and `TurnPromptBuilder` (`response-contract-json` fragment).
6. **No prompt-cache markers per provider** — Anthropic `cache_control` markers aren't used. Phase C in 2026-05 restructured the prompt for stable / volatile separation, but didn't wire provider-specific markers.

---

## 9. Patterns Worth Adopting (Priority-Ranked)

| # | Pattern | Source | Cost | Benefit |
|---|---|---|---|---|
| 1 | **LLM judge for "is the agent done?"** | Gemini CLI's `checkNextSpeaker` | 1 weak-model call per turn-end | Replaces remaining heuristics for terminal detection |
| 2 | **Content-chanting loop detection** | Gemini's `CONTENT_LOOP_THRESHOLD` (50-char hash windows, ≥10 hits with avg distance ≤250 chars) | Low — incremental hash on stream | Catches "model echoes itself" pathology |
| 3 | **Anthropic `cache_control` markers** | Hermes 3-tier; OpenAI auto + Anthropic explicit | Medium — API-level wiring per adapter | Real cost savings (~30–50% input tokens on long-running tasks) |
| ~~4~~ | ~~**Iter cap → 100**~~ | Gemini / Hermes | None | **Done 2026-05-26** — PLAN 50→100, AGENT already there |
| ~~5~~ | ~~**`TodoWrite`-style explicit task tracking**~~ | Claude Code | — | **Already implemented**: `tasks` tool (TasksTool) + `AgentPlanService` + `AgentPlansSectionProvider`. 2026-05-26 added prompt-level emphasis in `system-agent.md` `<task_planning>` to encourage usage. |
| 6 | **Per-tool fuzzy repair on bad args** | Hermes `_repair_tool_call` | Low | Auto-correct small typos in arg names without nudge round-trip |
| 7 | **Concrete diagnostics in retry message** | Aider's "Did you mean ..." with similar lines | Low | Better recovery than generic "regenerate envelope" |
| 8 | **Per-iteration token-budget tracking** | Hermes `IterationBudget` thread-safe consume/refund | Low | Cleaner accounting for subagents and `execute_code` |

---

## 10. Philosophical Positioning

Refio is **closest to Hermes** in philosophy (multi-layer scaffolding, own iter cap, own memory tool, similar subagents) but **closest to Continue** in infrastructure (Redux-like reactive store, IDE integration as primary target).

Codex and Claude Code represent **"trust the model"** — minimal scaffolding, `max_turns` as the only safeguard. This works for them because they target frontier models exclusively.

Refio's position is **defensive in the good sense** — it supports weaker local models (Ollama qwen / glm) with scaffolding, while for strong models (Claude via API) the scaffolding is minimally taxing (~250 tokens added to system prompt for tool-use enforcement, ignored by frontier models that already follow the rule). This is a genuine unique value proposition for the local-first positioning stated in `CLAUDE.md`.

---

## 11. Sources & Cross-References

### Code references (Refio)

- `core/src/main/kotlin/pl/jclab/refio/core/services/AgentTurnLoop.kt` — main loop, `executeTurnLoop`
- `core/src/main/kotlin/pl/jclab/refio/core/services/turn/TurnPromptBuilder.kt` — prompt assembly (stable/volatile split)
- `core/src/main/kotlin/pl/jclab/refio/core/services/turn/TurnGuardrails.kt` — `ToolErrorTracker`, `TurnRepetitionTracker`
- `core/src/main/kotlin/pl/jclab/refio/core/services/turn/TurnToolExecutor.kt` — parallel READ_ONLY dispatch, snapshots
- `core/src/main/kotlin/pl/jclab/refio/core/services/turn/TurnLLMCaller.kt` — streaming, retry handler, native tools routing
- `core/src/main/kotlin/pl/jclab/refio/core/agents/events/AgentInboxRegistry.kt` — multi-agent A2A
- `core/src/main/resources/prompts/system-agent.md` — AGENT system prompt (with `<tool_use_enforcement>` block)
- `core/src/main/resources/prompts/system-plan.md` — PLAN system prompt

### External reference points

- **Codex CLI**:
  - `codex-rs/core/src/session/turn.rs::run_turn` (lines 133–402)
  - `codex-rs/core/src/tools/handlers/mod.rs::parse_arguments` (lines 77–83)
  - `codex-rs/tools/src/function_call_error.rs` — `RespondToModel` vs `Fatal`
  - `codex-rs/core/gpt_5_codex_prompt.md` — system prompt
- **Claude Code**:
  - [How the agent loop works](https://code.claude.com/docs/en/agent-sdk/agent-loop)
  - [How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works.md)
  - GitHub Issue [#19699](https://github.com/anthropics/claude-code/issues/19699) — repetition loops (known gap)
- **Aider**:
  - `aider/coders/base_coder.py::run_one` (lines 1089–1110), `apply_updates` (lines 1947–1972)
  - `aider/coders/editblock_coder.py::find_original_update_blocks`, `replace_most_similar_chunk`
  - `aider/coders/editblock_prompts.py` — system prompt
- **Continue.dev**:
  - `core/llm/toolSupport.ts::modelSupportsNativeTools` (line 534)
  - `gui/src/redux/thunks/streamNormalInput.ts` (line 113 — native tool switch)
  - `core/tools/systemMessageTools/toolCodeblocks/index.ts` — fallback framework
- **Hermes Agent**:
  - `agent/conversation_loop.py` (line 675 — `while` loop, lines 3295–3406 — retry logic)
  - `agent/system_prompt.py::build_system_prompt_parts` (line 60 — 3-tier prompt)
  - `agent/prompt_builder.py` (line 246 — `TOOL_USE_ENFORCEMENT_GUIDANCE`)
  - `agent/tool_guardrails.py` — `ToolCallGuardrailController`
- **Gemini CLI**:
  - `packages/core/src/core/client.ts::sendMessageStream` (line 79 — `MAX_TURNS = 100`)
  - `packages/core/src/core/turn.ts::Turn.run` (line 368 — function calls iteration)
  - `packages/core/src/services/loopDetectionService.ts` — tool / content / LLM-judge loop detection
  - `packages/core/src/utils/nextSpeakerChecker.ts` — `checkNextSpeaker`
  - `packages/core/src/prompts/snippets.ts` — modular system prompt
- **Firecrawl**:
  - `apps/api/src/lib/scrape-interact/browser-agent.ts::executePromptViaBrowserAgent` (line 202)
  - Note: FIRE-1 itself is a closed hosted service. The browser agent is the OSS equivalent.
- **oh-my-openagent**:
  - `src/index.ts` (entry — exports plugin module)
  - `packages/prompts-core/prompts/ultrawork/default.md` — orchestrator prompt
  - `src/hooks/` — lifecycle hook implementations

### Internal documents

- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — overall Refio architecture
- [`docs/overview.md`](overview.md) — technical overview
- [`docs/files.md`](files.md) — per-package file reference
- [`docs/ROADMAP.md`](ROADMAP.md) — agreed development plan
- [`docs/0054-multiagent.md`](0054-multiagent.md) — multi-agent design notes

---

## 12. Methodology / How This Was Built

This comparison was assembled in 2026-05 by:

1. Reading Refio's own loop implementation directly (`AgentTurnLoop.kt`, `TurnPromptBuilder.kt`, `TurnGuardrails.kt`, `TurnToolExecutor.kt`).
2. Cloning or browsing each external repository and reading the actual loop entry point, the tool dispatch path, the format-retry logic, and the published system prompt.
3. Cross-referencing official documentation where source code was unavailable (Claude Code — documented behavior; Firecrawl FIRE-1 — partial OSS coverage).
4. For each system, extracting the same dimensions: iteration model, tool format, format error handling, repetition detection, termination conditions, system prompt directives.

Where a feature was reported absent (e.g. "no repetition detection in Codex"), the absence was verified by grepping the actual source for related terms (`repetition`, `duplicate`, `dedup`, `hash`, `repeated`, `last_call`) and confirming zero matches in the loop path.

This is descriptive, not prescriptive — what each system does, not what each should do.

### Maintenance

This document captures a point-in-time snapshot. Other agents evolve quickly. Consider re-validating before adopting any pattern from Section 9 — verify that the source system still does what's described, and that Refio's constraints haven't changed.

---

**Last verified**: 2026-05-26 against repos as of that date.
**Owner**: Engineers actively working on `AgentTurnLoop`.
**Review cadence**: Re-validate before any major refactor of the loop or before adopting patterns from Section 9.
