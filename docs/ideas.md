# Ideas backlog

A living collection of ideas and their assessment. This is NOT the curated direction
(`ROADMAP.md`) nor an execution plan (`docs/{NNNN}-*.md`) - raw ideas from analyses and
discussions land here, with a verdict and reasoning, before (if ever) they grow into their
own plan doc.

Statuses: **PRIORITY** · **TO-CONSIDER** · **PARKED** · **REJECTED**.

---

## Positioning thesis (direction, not a task)

Do not grow RefIo as "yet another coding agent". Win where cloud-first tools are weak:
**locality, control, visibility, reproducibility, JetBrains/JVM**. Do not compete with Copilot
on distribution nor with Claude Code on the model.

**Audience resolved (2026-06-28): solo-dev (local-first).** Consequence: the enterprise vector
(audit, supply-chain, evidence pack) leaves the critical path. An audit trail for a solo dev
working locally is a feature almost nobody needs - we do not build for "auditability" as a goal in
itself. MCP-policy stays meaningful only as local security (no-egress, permissions), not as
compliance.

---

## PRIORITY

### Stabilize the agent on local models
The most important current direction: instead of new features, stabilize the agent loop on local
models. The guardrail history (repetition-abort, noop-abort, native-empty, read-spree,
stream-idle...) is whack-a-mole - each fix repairs one case and risks breaking two others.
**Necessary precondition: measurability** (see below), because you cannot stabilize what you do not
measure.

### E2E as a stabilization gate + autonomous self-improvement loop
Specced in **[docs/0069-e2e-stabilization-gate.md](0069-e2e-stabilization-gate.md)** (PROPOSAL).
In short: N-runs to a pass-rate, baseline + delta, failure-mode classification from `run.json`,
persistent trend, large long-context scenarios, plus a coding agent that runs e2e + LLM-as-judge +
code correction in a worktree (staged autonomy, never auto-merge to `main`). Stabilization and this
idea are one task: the gate is the measuring instrument for stabilization.

---

## TO-CONSIDER

### Local Model Doctor
`refio doctor model ollama/...` runs a mini-suite and reports: native tools work or not, recommended
mode (native/json), real context window, timeouts, whether the model is fit for Agent/Plan/Chat. A
good differentiator (moat = accumulated knowledge of local-model brittleness), but **not a
must-have**. Verdict: do not build as a separate feature - it is **the same e2e engine pointed at one
model instead of one commit**, so it falls out of the stabilization gate almost for free when needed.
Stabilization first.

### Worktree Mode
Each longer task in an isolated worktree (`.refio/worktrees/task-id`, branch `refio/task-id`,
separate diff + rollback). Reasonable as an enabler (incl. for the autonomous loop in 0069), but the
justification "otherwise it looks dated next to cloud/PR-first" is aesthetic, not need-driven.
**First verify whether the real user actually runs parallel tasks** - on local models
`OllamaRequestGate` serializes them anyway (1 concurrent/endpoint), so parallelism buys nothing.
Value = isolation, not parallelism.

### Internal multi-agency = subagent as a context firewall
RefIo already has subagents (nested, max depth 3, own prompt, tool filter). On local models subagents
do not run in parallel anyway (the gate) - their only value is **context isolation** (delegate
research over N files, get back a summary, the main context stays clean), which genuinely helps
models that degrade as the window fills. BUT every subagent is another brittle call = larger failure
surface. Verdict: **narrow, for read-heavy sub-tasks, only after the single loop is stabilized**. Do
not expand it now on an unstable base.

### MCP Marketplace with a policy layer
Not "we support MCP" (commodity), but: trusted presets, risk scan, per-server/tool permissions,
no-egress mode, usage audit. Without a policy layer MCP becomes a source of incidents. Sensible as a
"secure MCP" differentiator, scoped to local security (the audit angle belongs to the enterprise
vector, which is off the critical path).

### Settings screens should show values that come from config.yaml
`ConfigRouter.getConfig(section)` reads only the config database, so any value set exclusively in
`~/.refio/config.yaml` or `<project>/.refio/config.yaml` is invisible in Settings even though the
engine honors it (the resolver reads YAML through each key's `yamlAccessor`). Affects every provider
field, not just context size. A field then shows the value it was constructed with, which
misrepresents the effective configuration and invites the user to overwrite their own setting.
Fix direction: have the section read go through the resolver instead of the repository, and mark
YAML-sourced values as read-only in the UI so a save cannot silently move them into the database.

### Embedding provider resolution has silent egress paths
`EmbeddingProviderFactory.resolve` maps a bare model name with no known Ollama match to `openai`,
so a typo in `models.embedding_model` can send project content to `api.openai.com`. Separate from
the unknown *provider id* path: this one is the bare-name heuristic, which guesses rather than
failing. Worth narrowing to an explicit provider prefix once the config surface can carry a
deprecation.

---

## PARKED

### Command Center UI (multi-task dashboard)
An operational view: several tasks in parallel, each in a worktree, status
(planning/reading/editing/testing), live diff/cost/tokens, pause/redirect/approve/rollback/
promote-to-PR buttons, a "why panel", an "evidence pack". Pitched as the "biggest wow", but it is the
**most expensive and most risky** item: a huge Swing investment, entering the turf where cloud agents
are natively strong, for a **user assumption that is unverified** (a command center is for
orchestrating a fleet; a solo dev in an IDE wants one good agent in their flow, not a fleet console).
Parked until the user and the wedge are validated. This is an investment you make AFTER finding
traction, not to find it.

---

## REJECTED

### A large multi-agent pipeline (Planner/Executor/Reviewer) now
Multi-agent without isolation, UI and evals looks impressive fast but often lowers output quality.
A stable single loop + measurability first. A multi-agent pipeline possibly much later, on a measured
base.

### SDK as the main product direction
Competes with larger forces and requires platform stability (see `docs/0064-positioning.md`). RefIo
has a better shot as a concrete tool/lab than as a library "for everyone".

---

## Decisions to make (open questions)

- [x] **Audience:** RESOLVED - solo-dev local-first. (drops evidence pack and command center off the
      critical path; MCP-policy only as local security)
- [ ] **Worktree:** does the real user run parallel tasks? (verify before building)
- [ ] **0069:** accept the delta rule (variance tolerance) and the P5 loop acceptance criteria.
