# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

The rules for how to work here (language, risk and approvals, tests, commits and branches, docs) are in `WORKFLOW.md`. Read it before starting any task.

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
./gradlew :core:jacocoTestCoverageVerification # Coverage gate (50% minimum instructions, wired into :core:check)
./gradlew :intellij-plugin:jacocoTestReport    # Plugin coverage report (HTML/XML only, no threshold - tests live in :core)
```

## Module Architecture

Three Gradle modules, each with its own source directory:

- **`:core`** - IDE-independent logic (LLM clients, tools, RAG, agents, DB). Source in `core/src/main/kotlin/`. Targets JDK 17.
- **`:intellij-plugin`** - IntelliJ plugin UI and services. Uses the IntelliJ Platform Gradle Plugin 2.x. Source in `intellij-plugin/src/main/kotlin/`. Depends on `:core`. Targets IntelliJ 2026.1 (IC), builds `242`-`263.*`. Compiled against JDK 21.
- **`:cli`** - Standalone TUI. Source in `cli/src/main/kotlin/`. Depends on `:core`. Uses Clikt 5.0.2 + Mordant 3.0.1 + JLine 3.26.3. Targets JDK 17.

All modules use the Kotlin 2.3.20 compiler with `apiVersion`/`languageVersion` pinned to 1.9 for source compatibility.

## Key Architectural Layers

```
UI (IntelliJ Swing / TUI Mordant)
  → Service Layer (SessionManager, MessageDispatcher)
    → Domain Routers (13 routers: Task, Chat, Agent, Subtask, Config, Prompts, Tool, RAG, ApiLogs, MultiAgent, ProjectContext, Subagent, Snapshot)
    → CoreApiRouter (composition root - creates dependencies, exposes routers, no business logic)
      → Execution (CoreSessionService → ChatService for CHAT | AgentTurnLoop → TurnExecutor for PLAN/AGENT)
        → LLMClient (8 provider adapters) + ToolRegistry (32 tools) + ContextService (14 providers)
          → Infrastructure (SQLite via Exposed ORM, Ktor HTTP, Caffeine cache)
```

Callers access domain routers directly via `coreApiRouter.taskRouter`, `coreApiRouter.chatRouter`, etc. CoreApiRouter itself is a thin composition root (~300 LOC) with no facade methods.

## Three Execution Modes

- **CHAT** - No tools. Conversation-only via ChatRouter → ChatService.
- **PLAN** - Read-only tools (the read-only subset). AgentTurnLoop with max 200 iterations.
- **AGENT** - Full read/write tool set (32 tools). AgentTurnLoop with max 200 iterations. File snapshots before edits.

Subagents use a nested invocation model (max depth 3) with custom system prompts and tool filtering.

## Headless CLI Self-Testing

The CLI runs the **same** `AgentTurnLoop` / tools / context pipeline as the IntelliJ plugin, but headless and scriptable. This lets the agent (Claude) **reproduce and verify Refio's own runtime behavior** - turn loop, tool selection, guardrails, a model's tool-use discipline - without the IDE, and inspect the result from logs and a metrics file.

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
| `--config k=v` (repeatable) / `--config-file <f>` | Run-scope config overrides (headless **and** interactive TUI). E.g. `--config agent.max_turn_minutes=20` or retarget a provider: `--config providers.ollama.ollama_endpoint=http://127.0.0.1:11434` / `--config providers.lmstudio.lmstudio_base_url=...`. |
| `--print-config` | Print resolved config (overrides applied) and exit - **no LLM call, writes nothing**. |
| `--acp` | Run as an Agent Client Protocol (v1) agent over stdin/stdout for JetBrains AI Assistant or Zed. The IDE sends the project directory, prompts and approvals; `--model`, `--mode`, `--config`, `--no-egress`, `--max-cost`, `--mcp-server` still apply; cannot be combined with `--headless`, `--prompt*`, `--auto-approve`, `--until`, `--goal`, `--resume`. Code in `cli/acp/`. |
| `--until "<cmd>"` / `--goal "<condition>"` | Run the task as a long task: rounds of AGENT turns until the command exits 0 in the project after a round, or until the completion judge finds the goal met. |
| `--max-rounds N` / `--max-hours H` | Limits of a long task (defaults `long_task.max_rounds` 12 and `long_task.max_minutes` 240). |
| `--resume <taskId>` | Continue a stopped long task with its next round; it keeps its own request and end condition, `--max-rounds` / `--max-hours` raise its limits, and a task that ended on its round or time limit gets the same allowance again. |
| `-v, --verbose` | Stream live LLM tokens to stderr (on top of always-on turn/tool progress) - tells producing-vs-hanging apart. |

Interactively (TUI and IDE) the same long-task mode is `/until <cmd>`, or `/goal <condition>` plus `/long on`; `/long rounds N`, `/long hours H`, `/long off`, `/until off`. Typed while a loop runs, they apply from its next round (`/long off` ends it after the current one); the limits are checked before each round, so a started round runs to its own turn limit and check. Each round rewrites `.refio/progress.md` in the project. A `--multi-agent` YAML takes run limits `on_failure` (`skip` by default, `stop`, `continue`), `max_minutes`, `max_cost`, `max_parallel`, and per agent `profile`, `max_iterations`, `max_minutes`; a dependent agent's task gets its dependencies' status, changed files and report.

### Safety rails (prefer these for self-tests)

- `--max-cost <usd>` - hard per-session ceiling; aborts the turn once reached (`0` disables). Sugar over `agent.max_cost_usd`.
- `--auto-approve "<regex>"` - headless approval gate: auto-approve only tool calls whose command matches the regex, **reject all others** (e.g. `--auto-approve "^git status"`). Without it, headless approvals depend on the configured policy.
- `--no-egress` - block all cloud LLM/web traffic (NetworkPolicy gate); keeps a self-test fully local.

### Observability (where to look after a run)

- **stderr** - always-on concise progress from `HeadlessTurnListener` (`cli/HeadlessTurnListener.kt`): `▶ turn started`, `→ tool args`, `✓/✗ tool`, `■ turn complete: success/iterations/tokens/cost`. stdout stays reserved for the `run.json` document.
- **`~/.refio/refio-cli.log`** - full DEBUG trace (`[HEADLESS] …`, `TurnLLMCaller`, `OllamaAdapter`, `ToolExecutor`, context budget). The post-mortem source of truth.
- **`run.json`** (`--output json --output-file`) - machine-readable metrics for comparing runs/models; `longTask` lists each round of a long task, `agents` each subagent (iterations, stop reason, files), and a multi-agent run carries each agent's status, files and conversation.

### Multi-scenario / multi-model harness

`tools/e2e/e2e-run.sh` (with a `.ps1` sibling) runs the e2e scenarios in `test_data/e2e/*.json` through the headless CLI into a throwaway project, then asserts on the produced `run.json`. HARD tiers (fail the run): run status SUCCESS, content needle in the edited file, build exit 0, no silent context overflow; SOFT (warn only): tool order, judge. `--self-test` exercises just the assertion engine (no LLM call); `--list` shows scenarios; `--all` or `<id>` selects what to run; `--model <provider/model>` compares models on the same task. Set `E2E_OUT_DIR=<dir>` to persist each run as `<id>__<model>__<run>.run.json` plus a `results.jsonl` verdict record (`{scenario, model, run, verdict, reasons[], failure_mode, status, costUsd, tokensOut}`) - the per-run input a pass-rate stabilization gate aggregates over N runs.

**Every e2e run uses a 64k context window.** Both harnesses default `--ollama-ctx` / `-OllamaCtx` to `65536`, and a cross-model comparison only means something when all models share one window. At the previous 32768 the longer scenarios overshot the window by 1-10%, Ollama truncated the prompt head in silence, and `num_predict` was clamped to its 512-token floor in 17 of 21 calls - the model was handed ~2 KB to write a file with, having lost the start of its own instructions. Since every scenario asserts `no_context_overflow`, that was a HARD fail attributed to the model rather than to the setup. Override the window only when the window itself is what you are measuring, and say so in the report.

### Gotchas

- **`--model X` in headless applies to every model slot**, not just the turn LLM: it folds into `ui.selected_model`, which `ModelSelectionService` reads first, so `advance_code_editing` / `multi_line_editor` generate file content with X too. An explicit `--config ui.selected_model=...` still wins over `--model`; the editing tools fall back to the **CODING slot** (`default_model.agent`) only when neither is set. (This fold is applied on the headless and `--multi-agent` paths; it does not change the interactive TUI.)
- Requires the target Ollama endpoint reachable; an unloaded/oversized model manifests as `LLM timeout after <api_timeout>ms` (a result, not a code bug) - lower it with `--config` to fail fast.

## Source Layout

Each module has its own source tree:
- **`:core`** - `core/src/main/kotlin/pl/jclab/refio/{core,api}/`
- **`:intellij-plugin`** - `intellij-plugin/src/main/kotlin/pl/jclab/refio/{actions,services,startup,ui}/`
- **`:cli`** - `cli/src/main/kotlin/pl/jclab/refio/cli/`

### Core module (`core/src/main/kotlin/pl/jclab/refio/`)

- `core/llm/adapters/` - LLM provider implementations (Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio, Custom OpenAI, Z.AI)
- `core/tools/` - Tool implementations (read_file, grep_search, code_editing, run_terminal_command, delegate_to_strong_model, etc.)
- `core/services/` - ~50 services (AgentTurnLoop, ContextService, RagIndexingService, ConfigService, etc.)
- `core/services/turn/` - AgentTurnLoop sub-components (TurnExecutor (the iteration loop), TurnLLMCaller, TurnPromptBuilder, TurnToolExecutor, TurnResponseProcessor, TurnGuardrails, ToolCallParser, TurnFinalizer, ToolApprovalService, etc.)
- `core/services/context/` - Context building helpers (ContextBudget, ContextSection, WorkingMemoryService, ProjectInstructionsLoader, ToolResultCompression, ContextTokenEstimator)
- `core/context/providers/` - IntelliJ-dependent context providers (excluded from `:core` module)
- `core/context/providers/standalone/` - IDE-independent context providers (included in `:core`)
- `core/security/` - NetworkPolicy (no-egress gate for web tools), UrlPolicy, ExecutionSandbox, SecureLogger
- `core/tools/security/` - CommandRule + CommandRuleDefaults, FileLimits; `PathSandbox` lives in `core/tools/`
- `core/db/` - Exposed ORM tables + repositories + migration system
- `core/subagents/` - Subagent parser, router, profiles; definitions in `src/main/resources/subagents/*.md`
- `core/agents/` - Multi-agent orchestration (events, runner, cycle detection)
- `services/` - IntelliJ plugin services (SessionManager, CoreConnectionManager)
- `ui/` - IntelliJ Swing UI components
- `cli/` - TUI (TuiApp, TuiViewModel, TuiRenderer, TuiInputHandler)
- `api/` - Shared API models between CLI and plugin

## Testing

JUnit 5 + MockK + Turbine (Flow testing). Tests mirror source structure under `src/test/kotlin/`. The `:intellij-plugin` module runs `detectSensitiveLogging` as part of `check` - fails the build if API keys appear in log statements.

## Configuration

- User config: `~/.refio/config.yaml`
- Project config: `<project>/.refio/config.yaml`
- RAG exclusions: `<project>/.aiignore`
- Project instructions: `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` (glob-based activation)
- Custom subagents: `~/.refio/agents/*.md` (user) or `.refio/agents/*.md` (project)
- Database: `~/.refio/data/database.sqlite` (SQLite, shared across projects)

## Important Patterns

- **Thin router pattern**: CoreApiRouter is a composition root (~300 LOC) that creates dependencies and exposes 13 domain routers. Callers use domain routers directly (e.g., `coreApiRouter.taskRouter.createTask()`). No facade methods - zero business logic in CoreApiRouter.
- **StateFlow reactivity**: SessionManager exposes 19 StateFlows; UI observes via `Flow.collect`.
- **Separate source trees**: Each module has its own `src/main/kotlin`. When adding new core files, ensure they don't depend on IntelliJ Platform APIs - the `:core` module has no IntelliJ dependency.
- **Security layers**: PathSandbox restricts file ops to project root; CommandRule (regex-based ALLOW/BLOCK/ASK, defaults in CommandRuleDefaults) governs terminal commands; FileLimits enforces size/extension restrictions; NetworkPolicy is the single egress gate consulted by `WebSearchTool`, `FetchWebpageTool`, and `HttpRequestTool` so `general.no_egress_enabled` blocks all outbound traffic, not just LLM providers. ToolPermissionsService provides 3-level (ON/ASK/OFF) per-mode access control. ToolApprovalService handles user approval flow with session trust rules.

---

## Documentation

- [docs/onboarding.md](docs/onboarding.md) - Onboarding guide for new team members
- [docs/files.md](docs/files.md) - Per-package file reference (606 main Kotlin files with 1-2 sentence descriptions)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - Architecture overview
- [docs/overview.md](docs/overview.md) - Technical architecture overview (~1500 lines)
- [docs/config.md](docs/config.md) - Configuration reference
- [docs/planning/prd.md](docs/planning/prd.md) - Product Requirements Document
- [docs/planning/prd-session.md](docs/planning/prd-session.md) - Session PRD
- [docs/planning/mvp.md](docs/planning/mvp.md) - MVP scope
- [docs/planning/tech-stack.md](docs/planning/tech-stack.md) - Tech stack decisions
- [docs/planning/ui-plan.md](docs/planning/ui-plan.md) - UI plan
- [docs/planning/tools-usage.md](docs/planning/tools-usage.md) - Tools usage guide

---

## Package & File Reference

See [docs/files.md](docs/files.md) for the full per-package file reference.

---

## Design Patterns Used

### Structural Patterns
- **Adapter** - LLM provider adapters (each implements `BaseLLMAdapter`); MCP tool wrapper (`MCPToolWrapper`); standalone context providers wrapping IDE-specific logic.
- **Facade** - `LLMClient` provides unified interface over 8+ adapters; `RagRepository` as facade over specialized repos; `SessionManager` as facade over lifecycle/state/execution services.
- **Registry** - `ToolRegistry` (ConcurrentHashMap), `ContextProviderRegistry`, `ModelRegistry`, `SubagentRegistry`, `LogSinkRegistry`, `ModelDefinitions`.
- **Composite** - `ProjectContextDTO` aggregates all context sections; `TuiState` merges 20+ StateFlows into one snapshot.

### Creational Patterns
- **Factory Method** - `ToolFactory` creates tools with DI; `TurnLoopConfig.Companion` factory methods for PLAN/AGENT presets; `BubbleComponentFactory` creates all UI elements; `MCPServerPresets` with build lambdas.
- **Builder** - `ContextReference` builder methods (file, folder, selection); request body construction in LLM adapters; GridBagConstraints incremental building.
- **Singleton** - `DatabaseFactory`, `MCPManager`, `GsonInstance`, `FileLockManager`, `LogSinkRegistry`.

### Behavioral Patterns
- **Strategy** - `ChunkingStrategy` (Semantic vs Default); `CommandRule` actions (ALLOW / BLOCK / ASK); `EmbeddingProvider` (OpenAI vs Ollama); `TaskVerifier` (Noop vs LLM); `SubagentToolFilter` (whitelist vs blacklist).
- **Observer** - `WorkflowEventListener` callbacks for UI; `AgentEventBus` SharedFlow subscriptions; StateFlow-based reactivity throughout (SessionManager 19 flows, TuiViewModel 20 flows).
- **State Machine** - `AgentTurnLoop` iteration states; `EmbeddingCircuitBreaker` (CLOSED/OPEN/HALF_OPEN); `SubagentInvocation` lifecycle (RUNNING→SUCCESS/FAILED/CANCELLED).
- **Template Method** - `BaseLLMAdapter` with `executeStandard`/`executeStreaming`; `ExtensionLanguageAnalyzer` with parsing utilities; `BaseBubbleRenderer` for bubble composition.
- **Chain of Responsibility** - `JsonExtractor` tries multiple extraction strategies sequentially; `HierarchicalConfigLoader` merges config from multiple sources in priority order.
- **Command** - `TuiAction` sealed class variants mapped from keybindings; `SlashCommand` definitions with prompt templates.
- **Mediator** - `CoreSessionService` routes a message to ChatService (CHAT) or AgentTurnLoop (PLAN/AGENT); `SessionManager` mediates between UI and core services.

### Resilience Patterns
- **Circuit Breaker** - `EmbeddingCircuitBreaker` with CLOSED/OPEN/HALF_OPEN states and configurable cooldown.
- **Retry with Exponential Backoff** - `LLMRetryHandler` for API calls (1s→2s→4s); `OllamaAdapter` for model loading delays.
- **Rate Limiting** - `OllamaRequestGate` (semaphore-based, 1 concurrent per endpoint); `GenericOpenAIAdapter` (mutex-based cooldown for Z.AI); `ProviderRequestGate` (per-provider concurrency cap, `providers.max_concurrent`, Ollama excluded).
- **Snapshot/Memento** - `SnapshotService` for file versioning and rollback with compression and SHA-256.

### Concurrency Patterns
- **Parallel Execution** - `TurnToolExecutor` batches READ_ONLY tool calls (fan-out capped by `maxParallelReadTools`); `MultiAgentRunner` with `supervisorScope`; `ModelRegistry` parallel provider fetching.
- **Single-Flight** - `ModelRegistry` mutex prevents concurrent duplicate API calls.
- **File Locking** - `FileLockManager` with per-path `Mutex` for atomic file operations.
- **Flow-based Reactivity** - StateFlow/SharedFlow throughout: SessionStateManager, TuiViewModel, AgentEventBus, RagProgressService.

### Security Patterns
- **Sandbox** - `PathSandbox` restricts all file ops to project root with symlink detection.
- **Whitelist/Denylist** - `CommandRule` (regex-based ALLOW/BLOCK/ASK; destructive commands incl. Windows/PowerShell deletes are BLOCK), `SupportedModels` (tested models only).
- **Secret Redaction** - `SecureLogger` regex-based redaction in all logs; `detectSensitiveLogging` Gradle task.
- **Defense-in-Depth** - `PathSandbox` (path normalization + symlink parent chain + real path resolution); tool permissions (mode-based + per-tool + security override).

### Data Access Patterns
- **Repository** - 15+ repository classes with query builders; scope-based filtering; batch optimization.
- **Event Sourcing** - `AgentEventSqlRepository` with Gson serialization and event replay.
- **Cache-Aside** - `PromptCache` (5-min Caffeine TTL); `ModelRegistry` (5-min cache); `SubagentRegistry` (60s TTL); `ProjectAnalyzerService` (10-min TTL); `EmbeddingsService` (30-min SHA-256 cache).
- **Hierarchical Lookup** - `ConfigRepository` precedence (TASK > PROJECT > APP); `SubagentRegistry` scope override (Project > User > Built-in).

### UI Patterns (Swing)
- **Multi-Layout Composition** - Outer: `GridBagLayout` (responsive width); Vertical: `BoxLayout.Y_AXIS` (stacking); Horizontal: `FlowLayout` (controls/headers); Content: `BorderLayout` (markdown/code panels).
- **MVVM (TUI)** - `TuiViewModel` (20 StateFlows → merged `TuiState`), `TuiRenderer` (compositor), `TuiInputHandler` (input dispatch).
- **Message Bubble Router** - `ChatMessageBubbleRouter` dispatches to role-specific renderers; each renderer composes via `BaseBubbleRenderer` and `BubbleComponentFactory`.
