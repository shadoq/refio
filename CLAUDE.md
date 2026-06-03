# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rules

These rules apply to every task in this project unless explicitly overridden.
Bias: caution over speed on non-trivial work.

### Rule 1 — Think Before Coding
State assumptions explicitly. Ask rather than guess.
Push back when a simpler approach exists. Stop when confused.

### Rule 2 — Simplicity First
Minimum code that solves the problem. Nothing speculative.
No abstractions for single-use code.
Prefer minimum viable change, unless it increases future maintenance risk in an already known hotspot.

### Rule 3 — Surgical Changes
Touch only what you must. Don't improve adjacent code.
Match existing style. Don't refactor what isn't broken.
If root cause is outside the initial scope, stop and report it instead of patching symptoms.

### Rule 4 — Goal-Driven Execution
Define success criteria. Loop until verified.
Strong success criteria let Claude loop independently.

### Rule 5 — Use the model only for judgment calls
Use for: classification, drafting, summarization, extraction.
Do NOT use for: routing, retries, deterministic transforms.
If code can answer, code answers.
Use code for routing when the routing criteria are explicit. Use model judgment only when intent or context is ambiguous.

### Rule 6 — Token budgets are not advisory
small task: 8k
medium task: 24k
large task: 60k
session hard cap: configurable
If approaching budget, summarize and start fresh.
Surface the breach. Do not silently overrun.

### Rule 7 — Surface conflicts, don't average them
If two patterns contradict, pick one (more recent / more tested).
Explain why. Flag the other for cleanup.

### Rule 8 — Read before you write
Before adding code, read exports, immediate callers, shared utilities.
If unsure why existing code is structured a certain way, ask.
Ask when the decision changes product behavior, public API, data model, security, or irreversible state. Otherwise make the smallest reversible assumption and state it.

### Rule 9 — Tests verify intent, not just behavior
Tests must encode WHY behavior matters, not just WHAT it does.
A test that can't fail when business logic changes is wrong.

### Rule 10 — Checkpoint after every significant step
Summarize what was done, what's verified, what's left.
Don't continue from a state you can't describe back.

### Rule 11 — Match the codebase's conventions, even if you disagree
Conformance > taste inside the codebase.
If you think a convention is harmful, surface it. Don't fork silently.

### Rule 12 — Fail loud
"Completed" is wrong if anything was skipped silently.
"Tests pass" is wrong if any were skipped.
Default to surfacing uncertainty, not hiding it.


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
        → LLMClient (8 provider adapters) + ToolRegistry (24 tools) + ContextService (14 providers)
          → Infrastructure (SQLite via Exposed ORM, Ktor HTTP, Caffeine cache)
```

Callers access domain routers directly via `coreApiRouter.taskRouter`, `coreApiRouter.chatRouter`, etc. CoreApiRouter itself is a thin composition root (~300 LOC) with no facade methods.

## Three Execution Modes

- **CHAT** — No tools. Conversation-only via WorkflowOrchestrator → ChatService.
- **PLAN** — Read-only tools (14). AgentTurnLoop with max 25 iterations.
- **AGENT** — Full read/write tools (24). AgentTurnLoop with max 50 iterations. File snapshots before edits.

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
| `--model <provider/model>` | Override the **turn/orchestration** LLM (see gotcha below). |
| `--prompt` / `--prompt-file` | The instruction (file form avoids quoting issues). |
| `--output json` + `--output-file <f>` | Write a `run.json` with metrics (tokens/cost/iterations/status) instead of stdout. |
| `--debug-level minimal\|standard\|full\|judge` | Detail in the JSON output. |
| `--config k=v` (repeatable) / `--config-file <f>` | Run-scope config overrides, headless only. E.g. `--config agent.max_iterations=80`. |
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

### Multi-model harness

`benchmark/scripts/benchmark-models.ps1` drives one prompt across a list of `$models` (× `$Runs`) headless, writing a per-run `run.json` to `_debug/`. Use it to compare how different models behave on the same task (planning, tool choice, whether they re-read/over-verify their own output).

### Gotchas

- **`--model` overrides only the turn LLM, not the editing sub-model.** `advance_code_editing` / code-edit tools resolve their generation model via `ModelSelectionService` (`ui.selected_model` / the CODING role from config), so the **actual file content is generated by the config's coding model regardless of `--model`.** To benchmark a model's *generation* quality, also set the config (e.g. `--config ui.selected_model=...`), not just `--model`.
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
