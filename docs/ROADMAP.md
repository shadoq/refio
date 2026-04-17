# RefIo — Development Roadmap

> Where the project is heading. No dates, no promises — a direction document.

## Current state (honest)

RefIo is an early-stage open-source AI coding plugin for IntelliJ IDEA, built in
Kotlin. v0.0.1.x, small commit history, small community. Used daily by the author —
that's the bar it meets today.

### What's in place

**Architecture**

- Three Gradle modules: `:core` (IDE-independent), `:intellij-plugin`, `:cli`
- Same `:core` drives the IntelliJ plugin and a full terminal TUI
- Pure Swing UI, no WebView
- SQLite + Exposed ORM for sessions, snapshots, RAG index
- In-process design — no HTTP bridge, no subprocess overhead

**Three execution modes**

- **Chat** — conversation with project context, no tools, no changes
- **Plan** — read-only analysis (code-enforced, not just convention)
- **Agent** — file edits with snapshots before every write, iteration/cycle guardrails

**Tool system**

- File operations, grep, terminal, HTTP, code runner, subagent invocation
- Per-mode permissions: `ON` / `ASK` / `OFF`
- Session-scoped tool approval with trust rules
- `PathSandbox` with symlink resolution + parent-chain check
- `CommandRule` (regex `ALLOW` / `BLOCK` / `ASK` policies)
- `SnapshotService` with zlib compression + SHA-256 dedup + `FileLockManager`

**Context system**

- @mentions for directing context (`@file`, `@codebase`, `@diff`, `@problems`, `@terminal`, …)
- RAG with semantic chunking (Kotlin / Java / Python / TypeScript / HTML)
- Token budget that scales to the model's context window
- Tool result compression (FULL → DETAILED → SUMMARY) when context fills up
- Conversation compaction at ~85% usage

**Models**

- Local: Ollama, LM Studio — runs fully offline with no-egress mode
- Cloud: OpenAI, Anthropic, Gemini, OpenRouter, Custom OpenAI, Z.AI
- Universal tool-calling protocol — works with models without native function calling

**Extensibility**

- Subagents as Markdown + YAML frontmatter (Claude Code compatible format)
- MCP protocol support (STDIO + HTTP/SSE) with built-in presets
- Project instructions via `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` (glob-activated)
- `.aiignore` for RAG exclusions

**Engineering signals**

- `detectSensitiveLogging` Gradle task fails the build if an API key pattern appears in log statements
- Jacoco coverage gate on `:core` (35% instructions)
- Detekt + ktlint enforced
- Embedding circuit breaker (CLOSED / OPEN / HALF_OPEN) for graceful RAG degradation
- Parallel read-only tool execution

### Known gaps

Open acknowledgement of what's pragmatic-MVP today. No sugarcoating:

- **Orchestration is a light router + executors**, not a deep agent engine. `IntentRouter` maps modes and dispatches to executors; `WorkflowOrchestrator` coordinates (~200 LOC).
- **Multi-agent runtime — early seed only.** `MultiAgentRunner` with DFS cycle detection, `supervisorScope` parallel execution, `AgentEventBus`, and a YAML spec parser (`MultiAgentTaskParser`) exist in `core/agents/`, plus `MultiAgentRouter` persisting `agent_sessions` / `agent_instances`. Wired to runtime through **only one entry point**: CLI flag `--headless --multi-agent <file.yaml>`. No IntelliJ UI, no interactive TUI launcher, no decomposer/dispatcher (removed), no resume. Subagents invoked from a turn (`invoke_subagent` tool) are a **separate** nested-invocation path and do not use `MultiAgentRunner`.
- **Agent-to-agent messaging — half-wired seed.** The `AgentEvent` vocabulary (`DataRequest`/`DataResponse`, `ArtifactProduced`, `SpawnAgentRequest`/`AgentSpawned`, `ApprovalRequired`/`ApprovalDecision`) is defined, `AgentEventBus` + `AgentEventHandler` are implemented with `CompletableDeferred` wait primitives, `agent_events` table persists every variant via `AgentEventSqlRepository`. The `send_message` tool emits `DataRequest` and `AgentTurnLoop` suspends on the matching `DataResponse` with a 5-min timeout. **But no responder exists in production code** — nothing emits `DataResponse`, `ArtifactProduced`, `SpawnAgentRequest` or `AgentSpawned` outside tests, so an agent that calls `send_message` of type `question`/`blocker` times out. The only A2A-ish loop that actually closes is `ApprovalRequired` ↔ `ApprovalDecision` (TUI consumes approval events).
- **No git worktree isolation per task.** Agents edit files directly (with snapshot rollback), not in an isolated branch.
- **Planning loop is basic.** A plan executor works, but there's no plan refinement iteration (plan → execute → evaluate → refine → continue).
- **No agent dashboard / command center.** Tool calls are visible in the chat stream, but there's no dedicated visualization of agent state across a long task.
- **Security layers are pragmatic v1.** `PathSandbox`, `CommandRule`, `RegexSafetyValidator` work as designed — but this is defense-at-depth-MVP, not mature multi-layered security.
- **Small community, small commit history.** v0.0.1.x. Changes happen fast. Breaking changes possible pre-1.0. Not yet battle-tested at scale.

See each phase below for the direction on these items.

## Direction

The following is **aspirational** — a working design for where the project is heading.
No timeline commitments. Phases are not strictly sequential; work happens where it
unblocks the most value.

### Phase 1 — Solid foundation

Goal: stable base for everything that follows.

- Unified `AgentState` model (context, plan, current step, history, tool registry)
- Formal `AgentStep` abstraction for step execution
- Cleaner separation of routing / execution / orchestration layers
- Structured per-step logging: `step_id`, duration, token cost, tool calls

### Phase 2 — Multi-agent runtime

Goal: multiple agents with explicit roles working on one task.

- Agent roles: `Planner`, `Executor`, `Reviewer`
- `AgentInstance` carrying status, state, lifecycle
- Scheduler for sequential + parallel execution
- Agent-to-agent messaging
- Pipeline: Planner → Executor → Reviewer → (refine)

### Phase 3 — Git worktree isolation

Goal: agents work in their own branch, not the user's working tree.

- Worktree per agent task (e.g. `.refio/agents/{id}/repo/`)
- Branch-per-task with managed merge flow
- `GitService` for commit / diff / branch operations
- Snapshot-before-every-operation (extending current `SnapshotService`)
- Diff viewer integrated with agent task output

### Phase 4 — Planning system v2

Goal: plans that are more than a list of steps.

- `Plan` with explicit step dependencies (DAG, not just a sequence)
- Typed steps: `READ` / `ANALYZE` / `WRITE` / `TEST` / `VERIFY`
- Plan persistence across sessions
- Refinement loop: plan → execute → evaluate → fix → continue
- Cost-aware planning (token / time budget per plan)

### Phase 5 — Command Center UI

Goal: see the whole agent process, not just the last chat bubble.

- Agent dashboard — who's doing what, status, progress
- Step-by-step task visualization with timeline
- Real-time metrics: tokens, cost, duration
- Task intervention controls (pause / redirect / rollback individual steps)
- IntelliJ panel + TUI view, same data

### Phase 6 — CLI beyond the TUI

Goal: run agents in headless / CI-style mode.

- `refio run "task description"` for non-interactive execution
- Batch mode for multi-task runs
- Output streaming and structured result reports
- Config-driven behaviour (task templates, role defaults)

### Phase 7 — Context v2

Goal: context that adapts to the task, not just the token budget.

- Layered context (static / project / dynamic / history)
- Smart context selection with ranking
- Context snapshot per execution step
- Per-step retrieval tuning (different sources for different step types)

### Phase 8 — Security hardening

Goal: defense-in-depth, not just a sandbox.

- Multi-layer filesystem validation (current `PathSandbox` is a single layer)
- Broader secret detection (multiple patterns, optional ML classifier)
- Network policy sandbox — not just filesystem (per-tool egress rules)
- Audit logs for all agent actions
- Deeper secure-logging enforcement (beyond `detectSensitiveLogging`)

## Comparable projects (for honest reference)

RefIo doesn't aim to compete with — or imitate — these mature projects. They
occupy different shapes and different spaces:

- **[pi-mono](https://github.com/badlogic/pi-mono)** — mature multi-frontend AI toolkit (coding-agent CLI, TUI, web UI, Slack bot, vLLM pods). 3500+ commits, 190+ releases.
- **[forgecode](https://github.com/tailcallhq/forgecode)** — deep Rust CLI agent with multi-crate architecture, worktree sandbox, git integration. 2500+ commits.
- **Claude Code** — Anthropic's polished terminal agent with a strong subagent model.
- **Cursor** — VS Code fork with dedicated agent capabilities.

RefIo aims for a different space: **a native JetBrains plugin that works fully
offline and is written in a language JVM teams can audit and extend.**
A specific niche — not a general-purpose champion.

## Anti-goals

Things RefIo intentionally **won't** do:

- **No VS Code version.** The design space is JetBrains-native.
- **No inline completion replacement.** That's Copilot's lane.
- **No closed or SaaS product.** MIT stays.
- **No cloud-only operation.** Local-first is a hard requirement.
- **No black-box "magic".** Every tool call visible, every prompt inspectable.

## How to contribute

Early-stage projects benefit enormously from contributions. Areas where help is
especially welcome:

- **Plan refinement loop** (Phase 4) — design + implementation
- **Agent dashboard UI** (Phase 5) — Swing components + TUI views
- **Worktree isolation** (Phase 3) — `GitService` design
- **Documentation** — onboarding guides, architecture deep-dives
- **Test coverage** — extending the existing Jacoco gate on `:core`
- **Provider adapters** — new LLM providers or improvements to existing ones
- **MCP presets** — additional built-in server configurations

Issues, design discussions, and pull requests welcome at
[github.com/shadoq/refio/issues](https://github.com/shadoq/refio/issues).

---

**Status:** This roadmap is a living document. Phases are not strictly sequential —
work happens where it unblocks the most value. Priority adjusts with contributor
interest and real-world feedback.
