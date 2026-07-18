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
- CI enforces the full Gradle `check` on every PR (module tests + coverage gate +
  sensitive-logging scan), plus an IntelliJ Plugin Verifier job (no detekt/ktlint yet)
- Embedding circuit breaker (CLOSED / OPEN / HALF_OPEN) for graceful RAG degradation
- Parallel read-only tool execution

### Known gaps

Open acknowledgement of what's pragmatic-MVP today. No sugarcoating:

- **Orchestration is a light router + executors**, not a deep agent engine. `IntentRouter` maps modes and dispatches to executors; `WorkflowOrchestrator` coordinates (~200 LOC).
- **Multi-agent runtime - early seed only.** `MultiAgentRunner` with DFS cycle detection, `supervisorScope` parallel execution, `AgentEventBus`, and a YAML spec parser (`MultiAgentTaskParser`) exist in `core/agents/`, plus `MultiAgentRouter` persisting `agent_sessions` / `agent_instances`. Wired to runtime through **only one entry point**: CLI flag `--headless --multi-agent <file.yaml>`. No IntelliJ UI, no interactive TUI launcher, no decomposer/dispatcher (removed), no resume. Subagents invoked from a turn (`invoke_subagent` tool) are a **separate** nested-invocation path and do not use `MultiAgentRunner`.
- **Agent-to-agent messaging - wired via per-agent inboxes, still maturing.** The live round-trip: `send_message` enqueues a `DataRequest` into the peer's `AgentMessageInbox` (`AgentInboxRegistry` keys inboxes by session + agent), `TurnPromptBuilder` injects pending requests into that peer's next prompt, and `answer_message` emits the matching `DataResponse` that un-suspends the waiting turn in `AgentTurnLoop`. Events persist via `AgentEventSqlRepository`. Integration-tested (`MultiAgentA2ATest`) but not production-hardened - typed routing and resume are still missing. A parallel never-wired event-handler plus the unused `ArtifactProduced`/`SpawnAgentRequest` event types were removed once confirmed dead.
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
- E2E stabilization gate: N-runs aggregated to a pass-rate with baseline + delta and
  failure-mode classification, driven by one command (`gate.sh` loops the runner into a
  `StabilizationGate` aggregator, surfaced via `cli --gate`). The harness now measures a
  pass-rate, not a single run.
- Stabilization gate extensions: per-scenario baselines (`--gate-baseline-file` /
  `--gate-write-baseline`), guardrail failure markers surfaced in `run.json`
  (`metrics.failureMarker`, classified into loop-aborted / noop-write-stall), a persistent
  commit-attributed trend history (`--gate-history` / `--gate-trend`), and long-context
  scenarios that stress the context budget (e.g. `large-file-edit`).
- Benchmark statistics and broader scenarios: the persisted e2e run records aggregate into a
  per-model / per-tool view (leaderboard, scenario-by-model matrix, tool-use histogram via
  `e2e-stats.sh`); application-build and CTF scenario classes join the behavioural set; and a
  headless `--model` now applies to every model slot (turn, editing, plan) so a single flag
  benchmarks one model end-to-end.

Remaining:

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

- Architect / Editor split as a first-class pillar - **non-goal (decision 2026-07-01).** The
  editor deliberately stays on the coding slot (`default_model.agent`): the turn model plans
  and the edit tool calls the same coding model. There is no separate `EDITOR` slot and none
  is planned. The implicit split (plan in the turn, generate in the edit tool) stays as-is.
- Capability routing (cheap model for read-only, strong coder for edits). A delegate-to-
  strong-model tool exists; a full per-step routing / CLASSIFIER step is deferred. (The editor
  still resolves the coding slot; routing would pick *which* coding model, not add an editor role.)

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

- Close the three concrete gaps found in the 2026-07 health review (fail-open
  `NetworkPolicy`, DNS-rebinding window in `UrlPolicy`, shell redirection outside
  `CommandRule` downgrade) - **done 2026-07**, see "Project health review" below
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

## 2026 trend review - ten recommendations

> Added 2026-07 after a project scan plus a review of agentic-coding trend reports
> (Anthropic's 2026 Agentic Coding Trends report, JetBrains 2026.1 ACP support,
> the local-LLM ecosystem). Where a recommendation matches an existing phase it
> references it instead of duplicating it; genuinely new directions are marked
> **(new)**. Ordering is deliberate: trust and first impression first, daily-use
> satisfaction second, two-year positioning last.

### Near term - the "wow" layer

1. **Inline diff review in the IDE (new).** Show agent edits as highlighted diffs
   in the editor with accept / reject per hunk, instead of tool output in the chat
   stream. The single biggest UX gap versus Cursor / Junie; already flagged as
   high-priority-unbuilt in the UI plan. Nothing else lands its full effect
   without this.
2. **Checkpoint timeline with one-click rollback (new).** `SnapshotService`
   already captures every write; expose it as a visible session timeline where one
   click restores the project state before any agent step. Trust in an agent grows
   with how easy it is to undo its work.
3. **Verification as a default agent step.** Close the plan -> execute ->
   **verify** loop: after edits, the agent runs the affected build / tests and
   repairs regressions before ending the turn. This is the refinement loop from
   Phase 3, pulled forward - the industry consensus is that verification, not
   generation, is the new bottleneck, and the e2e gate already measures the
   payoff. Especially valuable for weaker local models.
4. **Context Inspector as a flagship feature (new UX, planned groundwork).** A
   panel showing exactly what entered the prompt and its token cost per section.
   No competitor exposes this; for the target user ("control, not magic") it is
   the clearest differentiator the project has.

### Mid term - daily-use satisfaction

5. **Persistent project memory across sessions (new).** The memory tool exists,
   but nothing carries over between sessions. Keep the agreed simple-DB model
   (fully inspectable): the agent persists durable facts after a turn ("build via
   `gradlew.bat -p`") and gets them back as context in the next one, so the user
   stops repeating the same instructions.
6. **Security hardening as positioning, not just backlog.** Pull the known
   sandbox / command-policy gaps to the front of Phase 6, then market the result:
   no-egress mode, auditable sandbox, zero telemetry. Data privacy is the top
   adoption barrier for LLM tooling; for a local-first project this is the
   sales argument, provided the layers are actually tight first.
7. **Capability routing between local models.** The Phase 2 remaining item, with
   a concrete trigger: routine steps on a cheap local model, planning and
   post-verification repair escalated to the strong slot
   (`delegate_to_strong_model` already exists). The benchmark's per-model
   pass-rate data becomes a product mechanism instead of just a report.

### Two-year horizon - where the market is going

8. **RefIo as an ACP agent (new).** IntelliJ 2026.1 natively hosts ACP-compatible
   agents (this is how Codex and Cursor plug in). Exposing `:core` as an ACP
   server puts RefIo in every JetBrains IDE without per-IDE UI work, and composes
   naturally with the Phase 4 headless / `refio run` direction. The cheapest path
   to distribution on a two-year horizon.
9. **Background agents on isolated worktrees + command center.** The parked
   multi-agent and dashboard items (see Later / uncertain) become the 2027-2028
   bet: worktree isolation per agent, resumability, a live per-agent view. Local
   MoE-class models make this feasible without the cloud - "an agent team that
   never sends a byte outside" is a position no cloud vendor can copy. Conditions
   for un-parking stay unchanged: single-agent loop stable and measured first.
10. **Self-improvement loop on the e2e gate.** The Phase 1 remaining item, raised
    in priority: an agent runs the e2e scenarios, an LLM judge classifies
    failures, fixes are proposed in an isolated worktree and always wait for
    human review - never auto-merged. A measurable self-improving product is an
    advantage competitors cannot copy without an equivalent eval harness.

## Project health review (2026-07)

> Snapshot from a parallel scan of the codebase (backend, UI, quality/process)
> plus repo statistics. Same spirit as "Known gaps": honest, concrete, with file
> pointers. The overall finding: the foundations are above average for the
> project's age; what lags is the *last mile* - change review UX, gate
> enforcement in CI, and a few specific security holes. The last mile is cheaper
> to fix than foundations would be.

**Repo shape:** ~139k LOC main + ~42k LOC tests across the three modules.
Test distribution is lopsided: `:core` is well covered (170 test files - turn
loop, tools, adapters), `:cli` covers mostly the TUI, and `:intellij-plugin` -
the flagship deliverable - has only 8 test files for 118 source files.

### Engineering debt (priority order)

1. **`TurnExecutor.kt` god method.** ~2,500 LOC class; `execute()` spans ~1,900
   lines with deeply nested local suspend functions and a ~20-dependency
   constructor. Its collaborators in `services/turn/` were decomposed cleanly;
   the executor itself was not. Highest maintainability risk - all the
   reliability work the e2e gate measures lives here.
2. **`AgentTurnLoop.kt`** looks like a legacy loop coexisting with the newer
   `turn/` package - verify and remove.
3. **DB migrations** (`MigrationRunner.kt`) have no checksums and no rollback;
   risky for the shared `~/.refio` database across plugin versions.
4. **`SettingsView` scope leak** - its `coroutineScope` is never cancelled on
   dispose.
5. Secondary size hotspots: `ProjectAnalyzerService` (~2,000 LOC),
   `ContextService` (~1,600), `TurnToolExecutor` (~1,400).

What is *not* a problem (verified): no `GlobalScope` anywhere, retry/timeout
centralized in `LLMRetryHandler` / `LLMKtorClientFactory`, `ChatView` has mature
anti-flicker rendering, `ConfigService` is no longer a monolith (~300 LOC),
TODO/FIXME density is near zero.

### Security gaps (all three fixed 2026-07, with regression tests)

1. **`NetworkPolicy` fails open - FIXED.** A config read error now falls back to
   the last successfully read flag value, and with no prior successful read it
   fails closed (egress blocked). The config default stays "no-egress disabled".
2. **DNS-rebinding TOCTOU in `UrlPolicy` - FIXED.** Every resolved DNS record is
   validated (not just the first), so a host answering [public, 127.0.0.1] is
   blocked; the immediate connect reuses the JVM's positive DNS cache, closing
   the fast record-flip window.
3. **Shell redirection outside `CommandRule` - FIXED.** `>` / `<` now downgrade
   ALLOW to ASK like the chaining operators, so an allowed `cat` can no longer
   overwrite arbitrary files via redirect without review.

### Process and quality gaps

- **CI gates (fixed 2026-07):** CI now runs `check` on every PR (coverage gate +
  sensitive-logging scan enforced), a Plugin Verifier job, a nightly full-check +
  e2e assertion-engine self-test, and a tag-triggered release workflow
  (GitHub release with the ZIP; Marketplace publish when secrets are configured).
  Note: detekt/ktlint are NOT set up in this project (an earlier claim here was
  wrong); adding them is a separate decision.
- **The model-backed e2e gate stays manual** - GitHub-hosted runners cannot serve
  a local model, so `gate.sh` (pass-rate over N runs) still needs a self-hosted
  machine; only the offline self-test runs nightly.
- **Stale planning docs** - `docs/planning/*` (prd, mvp, ui-plan) are frozen at
  Dec 2025 and contradict this roadmap; mark them as historical or refresh them.
  They actively mislead new contributors and AI agents.
- **Advanced UI tabs (Context, Debug, RAG) hidden behind `advanced_view`** - the
  transparency features that differentiate the project are invisible by default.

### Near-term execution order (next quarter)

1. Process quick wins: CI runs `check`, add plugin-verifier, nightly e2e gate
   job, tag-triggered release workflow.
2. Close the three security gaps above, with regression tests.
3. Inline diff review + visible snapshot timeline (trend review items 1-2) -
   the building blocks (`SnapshotService`, native diff viewer, approval panel)
   already exist; what is missing is one coherent workflow.
4. Refactor `TurnExecutor.execute()` before it grows further.
5. Mark or refresh stale planning docs; surface Context/Debug panels as
   features, not a hidden mode.
6. Then continue per the phases and the trend review above (memory, routing,
   ACP, self-improvement loop).

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
- **Capability routing** (Phase 2) - cheap model for read-only, strong coder for edits (the editor stays on the coding slot; no separate editor role)
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
