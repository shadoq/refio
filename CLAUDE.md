# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent Rules

Apply to every task. Precedence: user's explicit instruction > project CLAUDE.md > AGENTS.md > this file.
Hard floor, overridable only by an explicit and knowing user decision in the moment: no secrets (12), confirm destructive/irreversible actions (2), no auto-commit (13).
Bias: caution over speed on non-trivial work.

Proportionality overrides every rule below. These rules set the ceiling of the process, not its obligatory minimum. When a rule would produce an artifact that reduces no real risk for the change at hand, skip it and say in one sentence that you did and why. This never relaxes the hard floor above.

### 1. Language and typography
- Talk to the user in Polish, plain, no marketing jargon.
- Code identifiers, comments, internal logs: English.
- User-facing text (UI, API responses, errors): follow the project's existing language and i18n mechanism; English if there is none.
- Working docs may stay Polish.
- Never use `—` or `–` anywhere. Plain hyphen `-`.
- Markdown: one paragraph is one line. Never hard-wrap prose to 80/100 columns; let the editor wrap.
- Diagrams (docs, comments, plans): ASCII/text only. No Mermaid, no images, no external diagram tools.

### 2. Think, read, decide
- State assumptions. Push back when something simpler works.
- Before writing code, read the exports, the immediate callers and the shared utilities you touch.
- If you cannot tell why existing code looks the way it does, ask.
- Ask based on the decision, not on your discomfort: irreversible, costly or contract-changing → ask; otherwise record the assumption and go.
- Low-risk, reversible → proceed.
- Medium-risk (shared code or behavior) → proceed with a short inline plan.
- High-risk (public API, schema, data model, security, billing, migration, architecture, irreversible state) → stop and ask first, unless the user already approved this concrete change.
- Stop only when genuinely blocked, not at the first uncertainty.

### 3. Simplicity first
- Minimum code that solves the problem. Nothing speculative.
- No abstraction for single-use code.
- Prefer the minimum viable change, unless it adds maintenance risk in a known hotspot.

### 4. Surgical changes
- Touch only what you must. Do not improve adjacent code.
- Match existing style. Do not refactor what is not broken.
- Root cause outside scope → report it, do not patch symptoms.
- If a formatter or codegen rewrites a whole file, keep only your diff and revert the churn.
- Repo-wide formatting is a separate work item, never a side effect.

### 5. Goal-driven execution
- Define success criteria first, then loop until verified.
- "Done" scales to the change: code plus what it actually needs (tests, user docs, migration, error handling). Compiling is not done.

### 6. Model only for judgment calls
- Model for: classification, drafting, summarization, extraction, genuinely ambiguous intent.
- Never for deterministic work (routing, retries, fixed transforms) when plain code does the job.

### 7. Token budgets are not advisory
- Rough guide: small 10k, medium 30k, large 90k; session cap comes from the user or config.
- At a task budget → write a checkpoint (rule 10). At the session cap → hand off working state to a fresh session, do not just truncate.
- Surface the breach, never overrun silently.

### 8. Tests: TDD by default, real flows, not trivia
- TDD is the default for behavioral code: write the failing test that defines success, then the minimum code, then refactor.
- Test complex or risky flows: business logic, state machines, guardrails, parsers, edge cases, regressions.
- Skip TDD (and usually the test itself) for simple, non-behavioral changes: getters, renames, config or version bumps, docs, styling, a button color, a changed label, a passthrough wrapper, integrations genuinely hard to isolate.
- Small change, especially in the GUI (layout, wording, colors, wiring an existing call to a new button) → no unit test. Tests are for logic worth protecting, not for proving that a widget was added.
- Outside those exemptions a change in behavior needs at least one regression test that fails without the fix; "not practical" must name a concrete reason, it is not a blanket excuse.
- Test name and inputs express the business rule, so the reader sees WHY it matters.
- A test that cannot fail when the business logic changes is dead weight; delete it instead of writing it.
- Comment only the non-obvious.

### 9. Match the approach to the work
- Feature → failing test first (rule 8), minimum code, then refactor.
- Bugfix → reproduce with a failing test, root-cause, fix, then refactor.
- Exploration → read-only explorer; keep the conclusion, not file dumps.
- Planning or design → plan first.
- Review → adversarial review before merge.
- Cleanup → quality only, no behavior change.
- Describe agents by role (read-only explorer, adversarial reviewer), never by hardcoded agent name.

### 10. Checkpoint at phase changes
- Checkpoint on phase change (analysis → implementation → tests → blocked), not per file or command.
- A checkpoint says: what was done, what is verified, what is left.
- Do not continue from a state you cannot describe back.

### 11. Fail loud and do not average conflicts
- "Completed" is false if anything was skipped silently.
- When reporting tests: name which scopes ran, which were skipped and why, plus the residual risk. Skipping e2e or paid integrations is fine, hiding it is not.
- Two contradicting patterns → first check whether the difference is intentional; if no reason is found, pick one (local to the change, more recent or better tested), say why, flag the other for cleanup.
- Conformance to the codebase beats your taste. If a convention is harmful, surface it, do not fork silently.

### 12. Secrets and production data
- Never print, log or commit tokens, keys, passwords, connection strings, `.env` or credential files.
- Mask sensitive values in logs, fixtures, test data and examples.
- Need a secret? Read it from the project's existing config mechanism. Never hardcode or invent one.
- No real personal data in tests, fixtures or seed data. Never copy production rows into the repo, not even "just one to reproduce it" - generate synthetic values of the same shape.
- Tests and local runs point at local/test storage and stubs. They must never mutate a production database or a live external service, and never call a paid API without the user approving that exact call.
- Reading production data for diagnosis needs the user to approve the concrete query first. Report an aggregate or a masked sample, never raw rows in chat, docs or code.

### 13. Commits
- Do NOT commit automatically. Commit only when the user asks.
- Message: one short imperative sentence, no body. What changed, not how hard it was.
- No trailers, no `Co-Authored-By`, no "Generated with Claude Code / Codex / any agent", no assistant attribution of any kind.
- Rationale and investigation notes go to chat or `docs/`, never to the message.

### 14. No documentation references in shipped code
- Never reference design docs (`doc 0021`, `docs/0011-...md`, `pkt 4`, `iter.4`, `M0..M7`) in anything shipped: comments, identifiers, log or error messages, commit messages, and everything on the front end - UI strings, tooltips, component names, view comments.
- Docs are not bundled with the product, so such pointers are dead noise for the reader.
- Say WHAT the code does and WHY in terms that stand on their own.
- Plain dates and ticket URLs are fine.

### 15. Documentation and plans
- Docs live in `docs/`, unless the repo already uses another location (then follow it).
- Classify by risk and architectural impact, not by file count. A multi-file rename is low-risk; a one-file schema migration is high-risk.
- Low or medium risk → implement directly, plan inline in chat, no plan document.
- High-risk or architecturally significant (new module or service, schema or public-API change, security, migration) → write a plan document before any code.
- Naming: follow the repo convention; otherwise `docs/{NNNN}-{title}.md`, NNNN = highest existing number + 1.
- The plan covers: expected change, how, why, verification, success criteria. Migrations and public-API changes also cover rollback, backward compatibility, rollout order and validation against real data.
- The document format (required sections, spec anatomy) lives in the `writing-spec` skill - load it before writing a plan document, do not restate it here.
- Write it for a junior agent: what, how, why, with `- [ ]` checkboxes marked `[x]` as they complete.
- User review before implementation is required only for high-risk or product-changing work.
- One document per work item: update the existing plan, do not create a second one.

### 16. Dependencies are a decision, not a shortcut
- Before adding a library, check that the stdlib, a framework already in the project or an existing helper here cannot do it. Name what you checked. A dependency added to save twenty lines is rarely worth it.
- Before proposing one, verify it is maintained, compatible with the versions already pinned here, and that its license fits a commercial closed-source product. Say so explicitly when any of these is unclear.
- A major version upgrade of an existing dependency is a contract change: its own work item, never a side effect of an unrelated task.
- Never edit a lockfile by hand. Change the manifest and let the tool regenerate it.

## What is Refio

Local-first AI coding assistant for IntelliJ IDEA and the terminal. Kotlin/JVM project with three Gradle modules, each with its own source tree.

## Build Commands

```bash
# Build & run
./gradlew :intellij-plugin:runIde          # Launch sandbox IDE with plugin
./gradlew :intellij-plugin:buildPlugin     # Build plugin ZIP → intellij-plugin/build/distributions/
./gradlew :cli:installDist                 # Build CLI → cli/build/install/cli/bin/cli

# Run CLI
./cli/build/install/cli/bin/cli --project /path/to/project --mode AGENT --model ollama/qwen3.5:9b

# Testing
./gradlew test                             # All modules
./gradlew :core:test                       # Core tests only
./gradlew :core:test --tests "pl.jclab.refio.core.tools.ReadFileToolTest"  # Single test class
./gradlew :core:test --tests "*.ReadFileToolTest.testReadFile"             # Single test method

# Quality
./gradlew :intellij-plugin:check           # Includes detectSensitiveLogging task
./gradlew :core:jacocoTestCoverageVerification # Coverage gate (35% minimum instructions, wired into :core:check)
./gradlew :intellij-plugin:jacocoTestReport    # Plugin coverage report (HTML/XML only, no threshold — tests live in :core)
```

## Module Architecture

Three Gradle modules, each with its own source directory:

- **`:core`** — IDE-independent logic (LLM clients, tools, RAG, agents, DB). Source in `core/src/main/kotlin/`. Targets JDK 17.
- **`:intellij-plugin`** — IntelliJ plugin UI and services. Uses the IntelliJ Platform Gradle Plugin 2.x. Source in `intellij-plugin/src/main/kotlin/`. Depends on `:core`. Targets IntelliJ 2026.1 (IC), builds `241`-`261.*`. Compiled against JDK 21.
- **`:cli`** — Standalone TUI. Source in `cli/src/main/kotlin/`. Depends on `:core`. Uses Clikt 5.0.2 + Mordant 3.0.1 + JLine 3.26.3. Targets JDK 17.

All modules use the Kotlin 2.3.20 compiler with `apiVersion`/`languageVersion` pinned to 1.9 for source compatibility.

## Key Architectural Layers

```
UI (IntelliJ Swing / TUI Mordant)
  → Service Layer (SessionManager, MessageDispatcher)
    → Domain Routers (12 routers: Task, Chat, Agent, Subtask, Config, Prompts, Tool, RAG, ApiLogs, MultiAgent, ProjectContext, Subagent)
    → CoreApiRouter (composition root — creates dependencies, exposes routers, no business logic)
      → Execution (WorkflowOrchestrator → ChatService for CHAT | AgentTurnLoop for PLAN/AGENT)
        → LLMClient (8 provider adapters) + ToolRegistry (~30 tools) + ContextService (14 providers)
          → Infrastructure (SQLite via Exposed ORM, Ktor HTTP, Caffeine cache)
```

Callers access domain routers directly via `coreApiRouter.taskRouter`, `coreApiRouter.chatRouter`, etc. CoreApiRouter itself is a thin composition root (~300 LOC) with no facade methods.

## Three Execution Modes

- **CHAT** — No tools. Conversation-only via WorkflowOrchestrator → ChatService.
- **PLAN** — Read-only tools (the read-only subset). AgentTurnLoop with max 100 iterations.
- **AGENT** — Full read/write tool set (~30 tools). AgentTurnLoop with max 100 iterations. File snapshots before edits.

Subagents use a nested invocation model (max depth 3) with custom system prompts and tool filtering.

## Headless CLI Self-Testing

The CLI runs the **same** `AgentTurnLoop` / tools / context pipeline as the IntelliJ plugin, but headless and scriptable. This lets the agent (Claude) **reproduce and verify Refio's own runtime behavior** — turn loop, tool selection, guardrails, a model's tool-use discipline — without the IDE, and inspect the result from logs and a metrics file.

**Consent rule (mandatory).** Claude must **propose a concrete command and wait for the user to agree before running any headless turn.** Every run spends tokens / local GPU time and **writes files into `--project`**. Never auto-launch a turn, never point `--project` at a real repo you don't want mutated (use a throwaway dir), and prefer the safety rails below. `--print-config` is the only no-consent-needed call (it makes no LLM call and writes nothing).

### Build & invoke

```bash
# Build/refresh the installed dist (do this after any :cli or :core change)
gradlew.bat -p "D:/_work/Saas/refio" :cli:installDist

# One headless AGENT turn, metrics to a file, prompt from a file (avoids shell quoting)
refio.bat -p <throwaway-project> --headless --model ollama/qwen3.6:35b \
  --prompt-file <prompt.md> --output json --output-file <run.json>
```

`refio.bat` wraps `cli/build/install/cli/bin/cli`. Headless needs one of `--prompt`, `--prompt-file`, or `--multi-agent <file>`.

### Key flags

| Flag | Purpose |
|---|---|
| `-p, --project <dir>` | Project root = **PathSandbox boundary**; agent file writes are confined here. |
| `-m, --mode CHAT\|PLAN\|AGENT` | Execution mode (default from config). |
| `--model <provider/model>` | Override the LLM. In headless it applies to **every slot** (turn, editing, plan, subagents) via `ui.selected_model` - see gotcha. |
| `--prompt` / `--prompt-file` | The instruction (file form avoids quoting issues). |
| `--output json` + `--output-file <f>` | Write a `run.json` with metrics (tokens/cost/iterations/status) instead of stdout. |
| `--debug-level minimal\|standard\|full\|judge` | Detail in the JSON output. |
| `--config k=v` (repeatable) / `--config-file <f>` | Run-scope config overrides (headless **and** interactive TUI). E.g. `--config agent.max_iterations=80` or retarget a provider: `--config providers.ollama.ollama_endpoint=http://127.0.0.1:11434` / `--config providers.lmstudio.lmstudio_base_url=...`. |
| `--print-config` | Print resolved config (overrides applied) and exit — **no LLM call, writes nothing**. |
| `-v, --verbose` | Stream live LLM tokens to stderr (on top of always-on turn/tool progress) — tells producing-vs-hanging apart. |

### Safety rails (prefer these for self-tests)

- `--max-cost <usd>` — hard per-session ceiling; aborts the turn once reached (`0` disables). Sugar over `agent.max_cost_usd`.
- `--auto-approve "<regex>"` — headless approval gate: auto-approve only tool calls whose command matches the regex, **reject all others** (e.g. `--auto-approve "^git status"`). Without it, headless approvals depend on the configured policy.
- `--no-egress` — block all cloud LLM/web traffic (NetworkPolicy gate); keeps a self-test fully local.

### Observability (where to look after a run)

- **stderr** — always-on concise progress from `HeadlessTurnListener` (`cli/HeadlessTurnListener.kt`): `▶ turn started`, `→ tool args`, `✓/✗ tool`, `■ turn complete: success/iterations/tokens/cost`. stdout stays reserved for the `run.json` document.
- **`~/.refio/refio-cli.log`** — full DEBUG trace (`[HEADLESS] …`, `TurnLLMCaller`, `OllamaAdapter`, `ToolExecutor`, context budget). The post-mortem source of truth.
- **`run.json`** (`--output json --output-file`) — machine-readable metrics for comparing runs/models.

### Multi-scenario / multi-model harness

`tools/e2e/e2e-run.sh` (with a `.ps1` sibling) runs the e2e scenarios in `test_data/e2e/*.json` through the headless CLI into a throwaway project, then asserts on the produced `run.json`. HARD tiers (fail the run): run status SUCCESS, content needle in the edited file, build exit 0, no silent context overflow; SOFT (warn only): tool order, judge. `--self-test` exercises just the assertion engine (no LLM call); `--list` shows scenarios; `--all` or `<id>` selects what to run; `--model <provider/model>` compares models on the same task. Set `E2E_OUT_DIR=<dir>` to persist each run as `<id>__<model>__<run>.run.json` plus a `results.jsonl` verdict record (`{scenario, model, run, verdict, reasons[], failure_mode, status, costUsd, tokensOut}`) — the per-run input a pass-rate stabilization gate aggregates over N runs.

### Gotchas

- **`--model X` in headless applies to every model slot**, not just the turn LLM: it folds into `ui.selected_model`, which `ModelSelectionService` reads first, so `advance_code_editing` / `multi_line_editor` generate file content with X too. An explicit `--config ui.selected_model=...` still wins over `--model`; the editing tools fall back to the **CODING slot** (`default_model.agent`) only when neither is set. (This fold is applied on the headless and `--multi-agent` paths; it does not change the interactive TUI.)
- Requires the target Ollama endpoint reachable; an unloaded/oversized model manifests as `LLM timeout after <api_timeout>ms` (a result, not a code bug) — lower it with `--config` to fail fast.

## Source Layout

Each module has its own source tree:
- **`:core`** — `core/src/main/kotlin/pl/jclab/refio/{core,api}/`
- **`:intellij-plugin`** — `intellij-plugin/src/main/kotlin/pl/jclab/refio/{actions,services,startup,ui}/`
- **`:cli`** — `cli/src/main/kotlin/pl/jclab/refio/cli/`

### Core module (`core/src/main/kotlin/pl/jclab/refio/`)

- `core/llm/adapters/` — LLM provider implementations (Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio, Custom OpenAI, Z.AI)
- `core/tools/` — Tool implementations (read_file, grep_search, code_editing, run_terminal_command, delegate_to_strong_model, etc.)
- `core/services/` — ~35 services (AgentTurnLoop, ContextService, RagIndexingService, ConfigService, etc.)
- `core/services/turn/` — AgentTurnLoop sub-components (TurnLLMCaller, TurnPromptBuilder, TurnToolExecutor, TurnResponseProcessor, TurnGuardrails, ToolCallParser, TurnFinalizer, TurnNudgeBuilder, ToolApprovalService, etc.)
- `core/services/context/` — Context building helpers (ContextBudget, ContextSection, WorkingMemoryService, ProjectInstructionsLoader, ToolResultCompression, ContextTokenEstimator)
- `core/context/providers/` — IntelliJ-dependent context providers (excluded from `:core` module)
- `core/context/providers/standalone/` — IDE-independent context providers (included in `:core`)
- `core/security/` — PathSandbox, CommandWhitelist, CommandRule, FileLimits, NetworkPolicy (no-egress gate for web tools)
- `core/db/` — Exposed ORM tables + repositories + migration system
- `core/subagents/` — Subagent parser, router, profiles; definitions in `src/main/resources/subagents/*.md`
- `core/agents/` — Multi-agent orchestration (events, runner, cycle detection)
- `services/` — IntelliJ plugin services (SessionManager, CoreConnectionManager)
- `ui/` — IntelliJ Swing UI components
- `cli/` — TUI (TuiApp, TuiViewModel, TuiRenderer, TuiInputHandler)
- `api/` — Shared API models between CLI and plugin

## Testing

JUnit 5 + MockK + Turbine (Flow testing). Tests mirror source structure under `src/test/kotlin/`. The `:intellij-plugin` module runs `detectSensitiveLogging` as part of `check` — fails the build if API keys appear in log statements.

## Configuration

- User config: `~/.refio/config.yaml`
- Project config: `<project>/.refio/config.yaml`
- RAG exclusions: `<project>/.aiignore`
- Project instructions: `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` (glob-based activation)
- Custom subagents: `~/.refio/agents/*.md` (user) or `.refio/agents/*.md` (project)
- Database: `~/.refio/data/database.sqlite` (SQLite, shared across projects)

## Important Patterns

- **Thin router pattern**: CoreApiRouter is a composition root (~300 LOC) that creates dependencies and exposes 12 domain routers. Callers use domain routers directly (e.g., `coreApiRouter.taskRouter.createTask()`). No facade methods — zero business logic in CoreApiRouter.
- **StateFlow reactivity**: SessionManager exposes 11 StateFlows; UI observes via `Flow.collect`.
- **Separate source trees**: Each module has its own `src/main/kotlin`. When adding new core files, ensure they don't depend on IntelliJ Platform APIs — the `:core` module has no IntelliJ dependency.
- **Security layers**: PathSandbox restricts file ops to project root; CommandRule (regex-based ALLOW/BLOCK/ASK) replaces legacy CommandWhitelist for terminal commands; FileLimits enforces size/extension restrictions; NetworkPolicy is the single egress gate consulted by `WebSearchTool`, `FetchWebpageTool`, and `HttpRequestTool` so `general.no_egress_enabled` blocks all outbound traffic, not just LLM providers. ToolPermissionsService provides 3-level (ON/ASK/OFF) per-mode access control. ToolApprovalService handles user approval flow with session trust rules.

---

## Documentation

- [docs/onboarding.md](docs/onboarding.md) — Onboarding guide for new team members
- [docs/files.md](docs/files.md) — Per-package file reference (~537 main Kotlin files with 1-2 sentence descriptions)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Architecture overview
- [docs/overview.md](docs/overview.md) — Technical architecture overview (~1500 lines)
- [docs/config.md](docs/config.md) — Configuration reference
- [docs/planning/prd.md](docs/planning/prd.md) — Product Requirements Document
- [docs/planning/prd-session.md](docs/planning/prd-session.md) — Session PRD
- [docs/planning/mvp.md](docs/planning/mvp.md) — MVP scope
- [docs/planning/tech-stack.md](docs/planning/tech-stack.md) — Tech stack decisions
- [docs/planning/ui-plan.md](docs/planning/ui-plan.md) — UI plan
- [docs/planning/tools-usage.md](docs/planning/tools-usage.md) — Tools usage guide

---

## Package & File Reference

See [docs/files.md](docs/files.md) for the full per-package file reference.

---

## Design Patterns Used

### Structural Patterns
- **Adapter** — LLM provider adapters (each implements `BaseLLMAdapter`); MCP tool wrapper (`MCPToolWrapper`); workflow executors (ChatExecutor, PlanExecutor, etc.); standalone context providers wrapping IDE-specific logic.
- **Facade** — `LLMClient` provides unified interface over 8+ adapters; `RagRepository` as facade over specialized repos; `SessionManager` as facade over lifecycle/state/execution services.
- **Registry** — `ToolRegistry` (ConcurrentHashMap), `ContextProviderRegistry`, `ModelRegistry`, `SubagentRegistry`, `LogSinkRegistry`, `ModelDefinitions`.
- **Composite** — `ProjectContextDTO` aggregates all context sections; `TuiState` merges 20+ StateFlows into one snapshot.

### Creational Patterns
- **Factory Method** — `ToolFactory` creates tools with DI; `TurnLoopConfig.Companion` factory methods for PLAN/AGENT presets; `BubbleComponentFactory` creates all UI elements; `MCPServerPresets` with build lambdas.
- **Builder** — `ContextReference` builder methods (file, folder, selection); request body construction in LLM adapters; GridBagConstraints incremental building.
- **Singleton** — `DatabaseFactory`, `MCPManager`, `GsonInstance`, `FileLockManager`, `LogSinkRegistry`.

### Behavioral Patterns
- **Strategy** — `ChunkingStrategy` (Semantic vs Default); `CommandWhitelist` validation modes (WHITELIST_ONLY / WHITELIST_PLUS_DENY); `EmbeddingProvider` (OpenAI vs Ollama); `TaskVerifier` (Noop vs LLM); `SubagentToolFilter` (whitelist vs blacklist).
- **Observer** — `WorkflowEventListener` callbacks for UI; `AgentEventBus` SharedFlow subscriptions; StateFlow-based reactivity throughout (SessionManager 11 flows, TuiViewModel 20 flows).
- **State Machine** — `AgentTurnLoop` iteration states; `EmbeddingCircuitBreaker` (CLOSED/OPEN/HALF_OPEN); `SubagentInvocation` lifecycle (RUNNING→SUCCESS/FAILED/CANCELLED); workflow loop intent transitions.
- **Template Method** — `BaseLLMAdapter` with `executeStandard`/`executeStreaming`; `ExtensionLanguageAnalyzer` with parsing utilities; `BaseBubbleRenderer` for bubble composition.
- **Chain of Responsibility** — `JsonExtractor` tries multiple extraction strategies sequentially; `HierarchicalConfigLoader` merges config from multiple sources in priority order.
- **Command** — `TuiAction` sealed class variants mapped from keybindings; `SlashCommand` definitions with prompt templates.
- **Mediator** — `WorkflowOrchestrator` coordinates intent routing and executor dispatch; `SessionManager` mediates between UI and core services.

### Resilience Patterns
- **Circuit Breaker** — `EmbeddingCircuitBreaker` with CLOSED/OPEN/HALF_OPEN states and configurable cooldown.
- **Retry with Exponential Backoff** — `LLMRetryHandler` for API calls (1s→2s→4s); `OllamaAdapter` for model loading delays.
- **Rate Limiting** — `OllamaRequestGate` (semaphore-based, 1 concurrent per endpoint); `CustomOpenAIAdapter` (mutex-based cooldown for Z.AI).
- **Snapshot/Memento** — `SnapshotService` for file versioning and rollback with compression and SHA-256.

### Concurrency Patterns
- **Parallel Execution** — `ParallelToolExecutor` for READ_ONLY tools; `MultiAgentRunner` with `supervisorScope`; `ModelRegistry` parallel provider fetching.
- **Single-Flight** — `ModelRegistry` mutex prevents concurrent duplicate API calls.
- **File Locking** — `FileLockManager` with per-path `Mutex` for atomic file operations.
- **Flow-based Reactivity** — StateFlow/SharedFlow throughout: SessionStateManager, TuiViewModel, AgentEventBus, RagProgressService.

### Security Patterns
- **Sandbox** — `PathSandbox` restricts all file ops to project root with symlink detection.
- **Whitelist/Denylist** — `CommandRule` (regex-based ALLOW/BLOCK/ASK), `CommandWhitelist` (legacy, 100+ programs), `CommandDenylist` (destructive commands), `SupportedModels` (tested models only).
- **Secret Redaction** — `SecureLogger` regex-based redaction in all logs; `detectSensitiveLogging` Gradle task.
- **Defense-in-Depth** — `PathSandbox` (path normalization + symlink parent chain + real path resolution); tool permissions (mode-based + per-tool + security override).

### Data Access Patterns
- **Repository** — 15+ repository classes with query builders; scope-based filtering; batch optimization.
- **Event Sourcing** — `AgentEventSqlRepository` with Gson serialization and event replay.
- **Cache-Aside** — `PromptCache` (5-min Caffeine TTL); `ModelRegistry` (5-min cache); `SubagentRegistry` (60s TTL); `ProjectAnalyzerService` (10-min TTL); `EmbeddingsService` (30-min SHA-256 cache).
- **Hierarchical Lookup** — `ConfigRepository` precedence (TASK > PROJECT > APP); `SubagentRegistry` scope override (Project > User > Built-in).

### UI Patterns (Swing)
- **Multi-Layout Composition** — Outer: `GridBagLayout` (responsive width); Vertical: `BoxLayout.Y_AXIS` (stacking); Horizontal: `FlowLayout` (controls/headers); Content: `BorderLayout` (markdown/code panels).
- **MVVM (TUI)** — `TuiViewModel` (20 StateFlows → merged `TuiState`), `TuiRenderer` (compositor), `TuiInputHandler` (input dispatch).
- **Message Bubble Router** — `ChatMessageBubbleRouter` dispatches to role-specific renderers; each renderer composes via `BaseBubbleRenderer` and `BubbleComponentFactory`.
