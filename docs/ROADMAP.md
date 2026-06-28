# RefIo - Development Roadmap

> Where the project is heading. No dates, no promises - a direction document.

## Current state (honest)

RefIo is an early-stage open-source AI coding plugin for IntelliJ IDEA, built in
Kotlin. v0.0.1.x, small commit history, small community. Used daily by the author -
that's the bar it meets today.

### What's in place

**Architecture**

- Three Gradle modules: `:core` (IDE-independent), `:intellij-plugin`, `:cli`
- Same `:core` drives the IntelliJ plugin and a full terminal TUI
- Pure Swing UI, no WebView
- SQLite + Exposed ORM for sessions, snapshots, RAG index
- In-process design - no HTTP bridge, no subprocess overhead

**Three execution modes**

- **Chat** - conversation with project context, no tools, no changes
- **Plan** - read-only analysis (code-enforced, not just convention)
- **Agent** - file edits with snapshots before every write, iteration/cycle guardrails

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
- Tool result compression (FULL -> DETAILED -> SUMMARY) when context fills up
- Conversation compaction at ~85% usage

**Models**

- Local: Ollama, LM Studio - runs fully offline with no-egress mode
- Cloud: OpenAI, Anthropic, Gemini, OpenRouter, Custom OpenAI, Z.AI
- Universal tool-calling protocol - works with models without native function calling

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
- **Multi-agent runtime - early seed only.** `MultiAgentRunner` with DFS cycle detection, `supervisorScope` parallel execution, `AgentEventBus`, and a YAML spec parser (`MultiAgentTaskParser`) exist in `core/agents/`, plus `MultiAgentRouter` persisting `agent_sessions` / `agent_instances`. Wired to runtime through **only one entry point**: CLI flag `--headless --multi-agent <file.yaml>`. No IntelliJ UI, no interactive TUI launcher, no decomposer/dispatcher (removed), no resume. Subagents invoked from a turn (`invoke_subagent` tool) are a **separate** nested-invocation path and do not use `MultiAgentRunner`.
- **Agent-to-agent messaging - half-wired seed.** The `AgentEvent` vocabulary (`DataRequest`/`DataResponse`, `ArtifactProduced`, `SpawnAgentRequest`/`AgentSpawned`, `ApprovalRequired`/`ApprovalDecision`) is defined, `AgentEventBus` + `AgentEventHandler` are implemented with `CompletableDeferred` wait primitives, `agent_events` table persists every variant via `AgentEventSqlRepository`. The `send_message` tool emits `DataRequest` and `AgentTurnLoop` suspends on the matching `DataResponse` with a 5-min timeout. **But no responder exists in production code** - nothing emits `DataResponse`, `ArtifactProduced`, `SpawnAgentRequest` or `AgentSpawned` outside tests, so an agent that calls `send_message` of type `question`/`blocker` times out. The only A2A-ish loop that actually closes is `ApprovalRequired` ↔ `ApprovalDecision` (TUI consumes approval events).
- **No git worktree isolation per task.** Agents edit files directly (with snapshot rollback), not in an isolated branch.
- **Planning loop is basic.** A plan executor works, but there's no plan refinement iteration (plan -> execute -> evaluate -> refine -> continue).
- **No agent dashboard / command center.** Tool calls are visible in the chat stream, but there's no dedicated visualization of agent state across a long task.
- **Security layers are pragmatic v1.** `PathSandbox`, `CommandRule`, `RegexSafetyValidator` work as designed - but this is defense-at-depth-MVP, not mature multi-layered security.
- **Small community, small commit history.** v0.0.1.x. Changes happen fast. Breaking changes possible pre-1.0. Not yet battle-tested at scale.

See each phase below for the direction on these items.

## Direction

The following is a **working design** for where the project is heading. No timeline
commitments. Phases are ordered by what unblocks the most value for the target user:
**a solo developer who runs local models in JetBrains and wants control, not magic.**
Reliability of the agent on local models comes before new surface area.

### Phase 1 - Reliability & evals foundation (current focus)

Goal: make the agent loop dependable on local models, and *measure* it so progress is
real, not anecdotal. Most near-term work lives here.

Done:

- Turn-loop hardening for local models: native/JSON tool-calling fallback, loop / stall /
  repetition aborts, noop-write detection, stream-idle ceiling, long-turn guardrails. A
  dedicated guardrail layer (turn guardians, completion + next-speaker judge, cost guard,
  response recovery) is in place.
- E2E behavioural regression harness: a runner plus 20+ scenarios that assert on the final
  project state (content needles, real `build_cmd` runs, `file_unchanged`), split into
  HARD/SOFT tiers, with an offline self-test.
- Public benchmark across providers to compare model behaviour on the same tasks.

Remaining:

- Graduate the e2e harness into a **stabilization gate**: N-runs to a pass-rate, baseline +
  delta, failure-mode classification, trend history, long-context scenarios. Today the
  harness measures a single run, not a pass-rate.
- Optional self-improvement: a coding agent that runs the e2e gate, diagnoses regressions
  (LLM-as-judge) and proposes fixes in an isolated worktree - never auto-merged.

### Phase 2 - Local-model-first agent architecture

Goal: lean into the setup that actually works best on local models.

Done:

- Agentic search: grep / glob / read as the primary navigation path; RAG auto-indexing is
  off by default and demoted to an optional aid for prose / docs, with grep ranking that
  favours declarations over usages.
- Universal tool-calling for models without native function calling, plus streaming
  tool-call deltas (accumulated across stream chunks in the provider adapters).
- Diff-repair loop: when a weak editor model returns an unusable edit, the edit tool
  re-prompts it with a corrective error instead of failing the whole turn.

Remaining:

- Architect / Editor split as a first-class pillar. It exists *implicitly* today - the turn
  model plans and the edit tool calls a separate coding model - but the editor still shares
  the coding model slot rather than having its own configurable role. Making it explicit is
  the open work.
- Capability routing (cheap model for read-only, strong coder for edits). A delegate-to-
  strong-model tool exists; a full per-step routing / CLASSIFIER step is deferred.

### Phase 3 - Planning system v2

Goal: plans that are more than a list of steps.

Today there is a basic plan service: ordered steps with statuses, a read/write-op flag, and
persistence with execution history. The gap is everything that makes a plan adaptive:

- `Plan` with explicit step dependencies (DAG, not just a sequence)
- Fully typed steps: `READ` / `ANALYZE` / `WRITE` / `TEST` / `VERIFY`
- Refinement loop: plan -> execute -> evaluate -> fix -> continue
- Cost-aware planning (token / time budget per plan)

### Phase 4 - CLI beyond the TUI

Goal: run agents in headless / CI-style mode.

Much of this already works: a `--headless` mode takes a prompt (or a multi-agent file),
writes a structured `run.json` result report, and is fully config-driven via `--config`
overrides and safety rails (`--max-cost`, `--auto-approve`, `--no-egress`). The gap:

- A first-class `refio run "task description"` subcommand (today it is the `--headless` flag)
- Batch mode for multi-task runs
- Richer task templates / role defaults

### Phase 5 - Context v2

Goal: context that adapts to the task, not just the token budget.

- Layered context (static / project / dynamic / history)
- Smart context selection with ranking
- Context snapshot per execution step
- Per-step retrieval tuning (different sources for different step types)

### Phase 6 - Security hardening

Goal: defense-in-depth, not just a sandbox.

- Multi-layer filesystem validation (current `PathSandbox` is a single layer)
- Broader secret detection (multiple patterns, optional ML classifier)
- Network policy sandbox - not just filesystem (per-tool egress rules)
- Audit logs for all agent actions
- Deeper secure-logging enforcement (beyond `detectSensitiveLogging`)

### Later / uncertain

Not committed. These read like near-term phases in an earlier version of this roadmap; the
current direction (reliability first, solo-dev focus) pushes them out until there is a
proven need on a stabilized, measured base.

- **Multi-agent runtime (Planner / Executor / Reviewer).** Today a half-wired seed (see
  Known gaps). Multi-agent without isolation, UI and evals tends to *lower* output quality
  on local models, which serialize calls anyway. Revisit only once the single-agent loop is
  stable and measured.
- **Command Center UI / agent dashboard.** A large Swing investment that competes with
  cloud agents on their strongest turf, for a usage pattern (many parallel tasks) a solo
  local-first user may not have. Parked until the user and the wedge are validated.

### Conditional - Git worktree isolation

Per-task worktree + branch + rollback is a sound enabler (and a prerequisite for the
self-improvement loop in Phase 1). But on local models `OllamaRequestGate` serializes
calls regardless, so the value here is **isolation, not parallelism**. Build it once there
is evidence the workflow needs it.

## Comparable projects (for honest reference)

RefIo doesn't aim to compete with - or imitate - these mature projects. They
occupy different shapes and different spaces:

- **[pi-mono](https://github.com/badlogic/pi-mono)** - mature multi-frontend AI toolkit (coding-agent CLI, TUI, web UI, Slack bot, vLLM pods). 3500+ commits, 190+ releases.
- **[forgecode](https://github.com/tailcallhq/forgecode)** - deep Rust CLI agent with multi-crate architecture, worktree sandbox, git integration. 2500+ commits.
- **Claude Code** - Anthropic's polished terminal agent with a strong subagent model.
- **Cursor** - VS Code fork with dedicated agent capabilities.

RefIo aims for a different space: **a native JetBrains plugin that works fully
offline and is written in a language JVM teams can audit and extend.**
A specific niche - not a general-purpose champion.

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

- **E2E stabilization gate** (Phase 1) - N-runs, baseline/delta, failure-mode classification
- **Turn-loop hardening** (Phase 1) - guardrails for local-model reliability
- **Architect / Editor split** (Phase 2) - explicit editor role, capability routing
- **Documentation** - onboarding guides, architecture deep-dives
- **Test coverage** - extending the existing Jacoco gate on `:core`
- **Provider adapters** - new LLM providers or improvements to existing ones
- **MCP presets** - additional built-in server configurations

Issues, design discussions, and pull requests welcome at
[github.com/shadoq/refio/issues](https://github.com/shadoq/refio/issues).

---

**Status:** This roadmap is a living document. Phases are not strictly sequential -
work happens where it unblocks the most value. Priority adjusts with contributor
interest and real-world feedback.
