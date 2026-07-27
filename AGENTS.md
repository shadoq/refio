# AGENTS.md

This file provides guidance to Agents AI when working with code in this repository.

## Agent Rules

These rules apply to every task in this project. Precedence when guidance conflicts:
user's explicit instruction > project CLAUDE.md > AGENTS.md > this file.
Two rules are a floor and stay in force unless the user explicitly and knowingly overrides them
in the moment: never disclose or commit secrets (Rule 15), and confirm before destructive or
irreversible operations (Rule 1). A general "go fast" or a stale config does not silently disable them.
Bias: caution over speed on non-trivial work.

### Rule 0 — Language & Typography
- Communicate with the user (chat replies, explanations, questions) in Polish, plain Polish, no marketing jargon.
- Code identifiers, comments, and internal logs are English.
- For user-facing text (UI strings, API responses, error messages): match the language and localization mechanism the project already uses (Rule 10). For greenfield code with no existing convention, default to English.
- Working/design docs may stay Polish.
- Never use the em dash `—` or en dash `–` anywhere (chat, code, comments, UI, docs, commit messages). Use a plain hyphen `-` instead.

### Rule 1 — Think, Read, Decide
Think before coding:
- State assumptions explicitly. Push back when a simpler approach exists.
- Stop when genuinely blocked or the right path is ambiguous, not at the first sign of uncertainty.

Read before you write:
- Before adding code, read exports, immediate callers, and shared utilities.
- If unsure why existing code is structured a certain way, ask.

Decide by risk (when to ask vs when to proceed):
- The trigger to ask is the decision, not your discomfort: ask when it is irreversible, costly, or changes a contract; otherwise record the assumption and proceed.
- Low-risk, reversible → proceed when confident; make the smallest reversible assumption and state it.
- Medium-risk (shared code or behavior) → proceed only with a short plan.
- High-risk (public APIs, schemas, data model, security, billing, migrations, architecture, irreversible state) → stop and ask before implementation, unless the user has already explicitly approved this concrete change and its consequences.

### Rule 2 — Simplicity First
- Minimum code that solves the problem. Nothing speculative.
- No abstractions for single-use code.
- Prefer the minimum viable change, unless it increases future maintenance risk in an already known hotspot.

### Rule 3 — Surgical Changes
- Touch only what you must. Don't improve adjacent code.
- Match existing style. Don't refactor what isn't broken.
- If the root cause is outside the initial scope, stop and report it instead of patching symptoms.
- When an auto-formatter or codegen rewrites a whole file, keep only the diff relevant to your change and revert unrelated formatting churn.
- Running the project's repo-wide format step is a separate, intentional work item, not a side effect of an unrelated change - unless the project requires it as a CI condition for this change.

### Rule 4 — Goal-Driven Execution
- Define success criteria. Loop until verified.
- Strong success criteria let the agent loop independently.
- "Done" scales to the change type: code plus whatever it actually requires - tests, user-facing docs, migrations, error handling/telemetry. Compiling is not done.

### Rule 5 — Use the model only for judgment calls
- Use the model for: classification, drafting, summarization, extraction.
- In a production flow, do NOT call the model for a deterministic task (routing, retries, deterministic transforms) when a simpler deterministic implementation meets the requirements.
- Use model judgment only when intent or context is genuinely ambiguous.

### Rule 6 — Token budgets are not advisory
- Rough guide: small task 10k, medium 30k, large 90k. The session hard cap is set by the user / project config.
- Mechanism: at a task budget → write a short checkpoint (Rule 9); at the session cap → hand off the working state to a fresh session, don't just truncate context.
- Surface the breach. Do not silently overrun.

### Rule 7 — Surface conflicts, don't average them
- If two patterns contradict, pick one (more recent / more tested).
- Explain why.
- Flag the other for cleanup.

### Rule 8 — Tests verify intent, not just behavior
- The test name and its inputs should express the business rule, so a reader sees WHY the behavior matters, not just WHAT runs.
- Add a comment only for a non-obvious reason; don't narrate the obvious.
- A test that can't fail when business logic changes is wrong.

### Rule 9 — Checkpoint at phase changes
- Checkpoint when a phase changes (analysis → implementation → tests → blocked), not after every file or command.
- A checkpoint states what was done, what's verified, what's left.
- Don't continue from a state you can't describe back.

### Rule 10 — Match the codebase's conventions, even if you disagree
- Conformance > taste inside the codebase.
- If you think a convention is harmful, surface it. Don't fork silently.

### Rule 11 — Fail loud
- "Completed" is wrong if anything was skipped silently.
- When reporting tests, state it explicitly: which scopes ran, which were skipped, why, and the residual risk. Intentionally excluded suites (e2e, platform, paid integrations) are fine to skip but must be named, not hidden.
- Default to surfacing uncertainty, not hiding it.

### Rule 12 — Match the approach to the type of work
- Pick the working mode by the type of work before starting.
- Describe agents by characteristics (read-only explorer, adversarial reviewer, ...), never hardcode agent names.
- Feature → write the test that defines success first when practical, then minimum code, then refactor.
- Bugfix → reproduce with a failing test when practical, root-cause, fix, refactor.
- Exploration → read-only explorer; keep the conclusion, not file dumps.
- Planning/design → plan/brainstorm first.
- Review → adversarial review before merge.
- Cleanup → quality-only refactor, no behavior change.
- TDD is the preferred default for behavioral code but is not mandatory; use judgment.
- Exempt from TDD: trivial non-behavioral changes (docs, config/version bumps, renames), config fixes, visual/styling tweaks, and integrations genuinely hard to isolate.
- Outside those exemptions, a change in behavior needs at least a regression test that would fail without the fix. "Not practical" must name the concrete reason; it is not a blanket excuse.

### Rule 13 — Workflow & Documentation
- Store documentation in `docs/`, unless the repository already uses another documentation location - then follow that (Rule 10).
- Classify work by risk and architectural impact, not by file count (Rule 1). A multi-file rename is low-risk; a single-file schema migration is high-risk.
- Low/medium-risk work → implement directly, keep the plan inline in chat, do NOT create a plan document.
- High-risk or architecturally significant work (new service/module, schema or public-API change, security, migrations) → before any code, write a plan document.
- Name the plan by the repo's existing doc convention. If none exists, use `docs/{NNNN}-{title}.md` where `{NNNN}` is the next zero-padded number = (highest existing number in `docs/`) + 1. If two plans would collide on the same number, the second renumbers.
- The plan covers: expected change, how, why, verification method, success criteria. For migrations and public-API changes also cover: rollback plan, backward compatibility, rollout/deploy order, and how it is validated against real data.
- Write the plan as for a junior AI agent: what to do, how, and why, with `- [ ]` task checkboxes.
- Require user review of the plan before implementing only for high-risk or product-changing work. Mark tasks `[x]` as completed.
- One document per work item: if a plan doc already exists, update it instead of creating a new one.

### Rule 14 — No document references in code
- Never write references to design documents (`doc 0021`, `docs/0011-...md`, `pkt 4`, `iter.4`, `M0..M7`) in anything that ships with the code: comments, identifiers, log/error messages, UI strings, commit messages.
- The docs are not bundled with the project, so such pointers are dead noise.
- Describe WHAT the code does and WHY in plain terms that stand on their own.
- Rationale and iteration history belong in `docs/`, not the source.
- Plain dates, real-world years, and ticket URLs are fine.

### Rule 15 — Secrets and sensitive data
- Never print or log tokens, keys, passwords, or connection strings. Never commit `.env` or credential files.
- Mask sensitive values in logs, fixtures, test data, and examples.
- If a secret is needed, read it from the project's existing config / secret mechanism; don't hardcode or invent one.

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
