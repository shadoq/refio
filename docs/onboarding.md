# Onboarding: Refio

> **Last Updated:** 2026-05-31
> **Version:** 0.0.1.11
> **Status:** Active Development

This guide helps new contributors get up and running. For technical reference, see [overview.md](overview.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Welcome

**Refio** is a local-first AI coding assistant for IntelliJ IDEA and the terminal (CLI/TUI). Written in Kotlin, built on a modular Gradle architecture, supporting 8 LLM providers (including local models via Ollama/LM Studio), 24 tools, 21 built-in subagents, and a full RAG system. Refio is MIT-licensed, with no telemetry and no vendor lock-in.

**Core philosophy:** Minimize LLM context through selective context injection (RAG + code analysis), resulting in 50-70% lower API costs and compatibility with smaller context windows of local models.

---

## Project Structure

Refio is a Kotlin/JVM project with three Gradle modules, each with its own source tree:

```
refio/
├── core/                    # IDE-independent core (~372 Kotlin files main)
│   └── src/main/kotlin/pl/jclab/refio/core/
├── cli/                     # Standalone TUI (~47 Kotlin files main)
│   └── src/main/kotlin/pl/jclab/refio/cli/
├── intellij-plugin/         # IntelliJ IDEA plugin (~118 Kotlin files main)
│   └── src/main/kotlin/pl/jclab/refio/
├── docs/                    # Documentation
└── .github/                 # CI/CD workflows
```

**Total:** ~713 Kotlin files (537 main + 176 tests: 134 core + 41 CLI + 1 plugin)

### Architectural Layers

```
UI (IntelliJ Swing / TUI Mordant+JLine3)
  -> Service Layer (SessionManager, MessageDispatcher)
    -> Domain Routers (12 routers: Task, Chat, Agent, Subtask, Config, Prompts, Tool, RAG, ApiLogs, MultiAgent, ProjectContext, Subagent)
    -> CoreApiRouter (composition root — creates dependencies, exposes routers, zero business logic)
      -> Execution (WorkflowOrchestrator -> ChatService for CHAT | AgentTurnLoop for PLAN/AGENT)
        -> LLMClient (8 adapters) + ToolRegistry (24 tools) + ContextService (14 providers)
          -> Infrastructure (SQLite via Exposed ORM, Ktor HTTP, Caffeine cache)
```

---

## Main Modules

### `:core` — Core (IDE-independent)

- **Role:** All business logic, LLM clients, tools, RAG, agents, database. No IntelliJ SDK dependency.
- **Kotlin:** 1.9.25, JDK 17

| Package | Files | Description |
|---------|-------|-------------|
| `api/routers/` | ~12 | Domain routers (Task, Chat, Agent, Config, RAG, Tool, etc.) |
| `llm/adapters/` | 8 | LLM adapters: Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio, Custom OpenAI, Z.AI |
| `tools/implementations/` | 14 | Tools: read_file, grep_search, code_editing, http_request, run_code, invoke_subagent, etc. |
| `tools/security/` | ~7 | CommandRule (regex ALLOW/BLOCK/ASK), FileLimits, PathSandbox |
| `services/` | ~35 | AgentTurnLoop, ContextService, RagIndexingService, ConfigService, ToolPermissionsService, etc. |
| `services/turn/` | ~13 | AgentTurnLoop sub-components: TurnLLMCaller, TurnPromptBuilder, TurnToolExecutor, ToolApprovalService, etc. |
| `services/context/` | ~7 | ContextBudget, WorkingMemoryService, ProjectInstructionsLoader, ToolResultCompression, **DiffCompressor** (content-aware diff body elision) |
| `subagents/` | ~6 | Parser, router, profiles, tool filtering; definitions in `resources/subagents/*.md` (21 agents) |
| `agents/` | ~6 | Multi-agent orchestration (EventBus, Runner, cycle detection) |
| `db/` | ~31 | SQLite via Exposed ORM: tables, repositories, migrations |
| `config/` | 3 | ConfigKeys (70+ keys), ConfigYaml, HierarchicalConfigLoader |

- **Resources:** 15 system prompts (`resources/prompts/`), 21 subagent definitions (`resources/subagents/`)
- **Tests:** 99 test files (JUnit 5, MockK, Turbine)

### `:cli` — Terminal User Interface

- **Role:** Standalone full-screen TUI mirroring the IntelliJ plugin GUI. Works in any terminal emulator.
- **Kotlin:** 2.0.21, JDK 17
- **Dependencies:** Clikt 5.0.2 (CLI args), Mordant 3.0.1 (ANSI rendering), JLine 3.26.3 (raw input)

| Component | Description |
|-----------|-------------|
| `TuiApp.kt` | Entry point — `launchTuiApp()` |
| `TuiViewModel` | Coordinator (~1289 LOC, MVVM), 20 StateFlows, delegates to 3 sub-ViewModels |
| `TuiRenderer` | Full-screen compositor, split-pane layout, ANSI cursor positioning |
| `TuiRenderBuffer` | ANSI-aware line buffer, merge side-by-side |
| `TuiInputHandler` | Dual-mode input: raw TTY (F-keys, Ctrl+combo) / line mode (IDE terminal) |
| `TuiSettingsScreen` | 11 settings sub-tabs (General, Providers, Models, Prompts, Context, MCP, Tools, etc.) |

- **Layout:** 8 tabs (F1 Help, F2–F7 tabs, F8 Files) + F9 Settings, split-pane (55%/45%), responsive
- **Tests:** 39 test files

### `:intellij-plugin` — IntelliJ IDEA Plugin

- **Role:** Native plugin with Swing UI, settings panels, IDE integration (terminals, editor, clipboard, problems).
- **Kotlin:** 1.9.25, gradle-intellij-plugin 1.17.4
- **Target:** IntelliJ 2024.1.7 (IC), builds 241-261.*

| Component | Files | Description |
|-----------|-------|-------------|
| `ui/` | ~74 | Swing components: chat bubbles, autocomplete, toolbar, settings panels |
| `services/` | ~21 | SessionManager (6 components), CoreConnectionManager, RAG, notification |
| `actions/` | 8 | IDE actions |
| `core/context/providers/` | ~13 | IDE-dependent context providers (@current, @open_files, @terminal, @problems) |

- **Tests:** 5 files (limited due to IntelliJ Platform API dependency)

---

## System Overview

For the full technical reference (execution modes, tools, providers, security, subagents, context, RAG, MCP, database schema) see:

- **[overview.md](overview.md)** — comprehensive technical reference (~1500 lines)
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — architecture diagrams, component details, design decisions
- **[config.md](config.md)** — full configuration reference

Quick summary of what the engine supports:

| Area | Count |
|------|-------|
| Execution modes | 4 (Chat, Plan, Agent, Subagent profile) |
| Tools | 24 (14 read-only + 10 write) |
| LLM providers | 8 (Ollama, LM Studio, OpenAI, Anthropic, Gemini, OpenRouter, Custom OpenAI, Z.AI) |
| Context providers | 14 (@file, @codebase, @grep, @diff, @url, …) |
| Built-in subagents | 21 |
| MCP presets | 17 |
| Security layers | 7 |

---

## Key Contributors

| Contributor | Role |
|-------------|------|
| **Jaroslaw Czub** (jarek / jaroslaw.czub) | Primary author and maintainer, 100% of commits |

---

## Development Context

### Active Areas (as of v0.0.1.11, 2026-05-31)

1. **Native function calling** — provider-native tool API for Ollama, OpenAI, Anthropic, Gemini, plus all OpenAI-compatible providers (OpenRouter, Z.AI, Generic OpenAI, LM Studio) via `OpenAICompatibleHelpers.buildOpenAIToolsArray` / `parseOpenAIToolCalls`. JSON-in-text fallback for the rest. Controlled by `tools.native_tools: auto|always|never`. **Persistent fallback list** in `models.native_tools_fallbacks` survives process restart.
2. **Centralized stats tracking** — `LLMClient` is now the single writer of `task.tokens_in/out/cost_usd` and (when `subtaskId` is provided) `subtask.tokens_in/out/cost_usd`. Per-call-site bookkeeping (~20 sites) was removed; new `complete()` callers just pass `taskId` / `subtaskId` and metrics increment automatically. `SessionStatsCalculator` reads the `task` row directly; `api_logs` is no longer a stats source.
3. **Tool-result compression** — `DiffCompressor` elides bodies of large diffs in tool results (small / pure-create / mixed paths). The wrap-up agent turn no longer pays for the file the agent just generated. Subtask id is plumbed through so the recovery hint embeds the literal id.
4. **Multi-agent infrastructure** — `AgentEventBus`, `MultiAgentRunner`, parallel orchestration wired; peer-to-peer A2A messaging (`send_message` → `answer_message` loop) not yet production-ready.
5. **Ongoing refactor** — CoreApiRouter and ConfigService being slimmed down; session layer migration to `:core` in progress.
6. **CommandRule security system** — regex-based ALLOW/BLOCK/ASK replacing legacy `CommandWhitelist`. New code should use `CommandRule`.
7. **Reference model for testing:** `ollama/qwen3.5:9b`
8. **Turn-loop reliability (v0.0.1.11)** — `INCOMPLETE` task status for turns that stop without delivering, read-spree consolidation nudge, no-op-write streak abort, `ConsecutiveTextRepetitionTracker`, and loop detectors that exempt ASCII diagrams/separators.
9. **MCP & model resolution (v0.0.1.11)** — global-server tools exposed to the agent as `mcp_<server>_*`; `ModelWindow` as the single context-window resolver; RAG duplicate/overlapping-chunk dedup and batched index writes.

### Key Architectural Patterns

- **Thin Router Pattern** — CoreApiRouter is a composition root, zero business logic; all logic lives in domain services
- **MVVM** — TUI with TuiViewModel (20 StateFlows → merged TuiState)
- **Strategy** — ChunkingStrategy, EmbeddingProvider, CommandRule validation modes
- **Circuit Breaker** — EmbeddingCircuitBreaker (CLOSED/OPEN/HALF_OPEN)
- **Parallel Execution** — READ_ONLY tools run concurrently (~2-3x faster)
- **Hierarchical Config** — 4-level precedence: Database > Project YAML > User YAML > Built-in defaults

---

## High-Complexity Areas

Files that are large, structurally central, or require special care:

| Area | File | Notes |
|------|------|-------|
| **AgentTurnLoop** | `core/services/AgentTurnLoop.kt` (~1000+ LOC) | Main execution loop: compaction, retry, parallel tools, working memory, mid-execution messages |
| **CoreApiRouter** | `core/api/CoreApiRouter.kt` (~305 LOC) | Composition root — creates ~35 services, must remain thin (zero logic) |
| **ContextService** | `core/services/ContextService.kt` | Delegates to 6 sub-services, token budgeting, pruning |
| **TuiViewModel** | `cli/tui/state/TuiViewModel.kt` (~1289 LOC) | Coordinator of 20 StateFlows, 3 sub-ViewModels |
| **TuiRenderer** | `cli/tui/rendering/TuiRenderer.kt` | ANSI rendering, split-pane, cursor management, flicker-free |

---

## Pitfalls for New Developers

1. **`:core` cannot depend on IntelliJ SDK** — all core code must work without the IDE. IDE-dependent context providers live in `intellij-plugin/core/context/providers/`.
2. **Kotlin version mismatch** — `:core` and `:intellij-plugin`: Kotlin 1.9.25; `:cli`: Kotlin 2.0.21. Keep this in mind when adding dependencies.
3. **Thin Router Pattern** — never add business logic to CoreApiRouter or domain routers. Routers = delegation only.
4. **CommandRule vs CommandWhitelist** — `CommandWhitelist` is legacy. New code uses `CommandRule` (regex-based ALLOW/BLOCK/ASK).
5. **ToolPermissions are 3-level** — ON/ASK/OFF, not a boolean. `run_terminal_command` is ASK in AGENT by default, not ON.
6. **Database location** — moved from `refio_poc.db` (project root) to `~/.refio/data/database.sqlite` (shared across projects).
7. **Native tools path vs JSON path** — `AgentTurnLoop` takes the native path when `LLMResponse.nativeToolCalls != null`, skipping `ToolCallParser` entirely. Both paths must stay consistent.
8. **`LLMClient` writes metrics, callers don't** — every successful `LLMClient.complete(...)` increments `task.tokens_in/out/cost_usd` (and `subtask.*` when `subtaskId` is non-null). New call-sites must NOT call `taskRepository.incrementMetrics(...)` themselves — that path was removed precisely because manual bookkeeping was the source of double-count and stale-stats bugs. Pass `taskId` / `subtaskId` and let the client handle it.
9. **EDT must not read SQLite** — `SessionManager.maxContextWindow` is a `StateFlow` cached off-EDT specifically because `lifecycleService.getMaxContextWindow()` hits the DB. Settings panels, Status bar, Context panel: read `.value`. After mutating `MAX_CONTEXT_SIZE`, call `refreshMaxContextWindow()` to push a new value through the flow.
10. **`updateSession(persistSettings = false)` for token-only refreshes** — the default `persistSettings = true` triggers `saveCurrentSessionState()` which emits ~5 `ConfigRepository` writes. Use `false` from auto-name, post-turn token refresh, and any path that only changes denormalized stats — not from settings save.

---

## Questions for Maintainers

1. What is the timeline for removing `CommandWhitelist` now that `CommandRule` is the standard?
2. Which LLM models besides `qwen3.5:9b` are regularly tested? Is there a model compatibility matrix?
3. What is the plan for completing A2A messaging in the multi-agent system?
4. What are the testing priorities — increasing plugin coverage (currently ~5 files) or expanding core tests?
5. Is support for other IDEs planned beyond IntelliJ IDEA?

---

## Next Steps

1. **Set up your environment** — install JDK 17, Ollama, pull models (see below).
2. **Run the sandbox IDE** — `./gradlew :intellij-plugin:runIde` — explore the plugin in a sandbox IntelliJ.
3. **Run the CLI** — `./gradlew :cli:installDist && ./cli/build/install/cli/bin/cli --project .`
4. **Read [overview.md](overview.md)** — full technical reference with all data flows.
5. **Run tests** — `./gradlew test` — make sure everything passes before making changes.

---

## Developer Environment Setup

### Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| **JDK** | 17 | Auto-provisioned by Gradle (Foojay resolver) |
| **IntelliJ IDEA** | 2024.1+ | For plugin development |
| **Ollama** | Latest | For local models and embeddings |
| **Git** | Latest | Version control |

### Installation and Running

```bash
# 1. Clone the repository
git clone https://github.com/shadoq/refio.git && cd refio

# 2. Install Ollama (https://ollama.com/)
ollama pull nomic-embed-text    # Required for RAG embeddings
ollama pull qwen3.5:9b          # Reference coding model

# 3. Run sandbox IDE (plugin development)
./gradlew :intellij-plugin:runIde

# 4. Build plugin ZIP
./gradlew :intellij-plugin:buildPlugin
# Output: intellij-plugin/build/distributions/refio-*.zip

# 5. Build CLI
./gradlew :cli:installDist
# Output: cli/build/install/cli/bin/cli

# 6. Run CLI
./cli/build/install/cli/bin/cli --project /path/to/project

# 7. Run tests
./gradlew test                  # All modules
./gradlew :core:test            # Core only
./gradlew :core:test --tests "pl.jclab.refio.core.tools.ReadFileToolTest"

# 8. Code quality
./gradlew :intellij-plugin:check  # Includes detectSensitiveLogging
./gradlew :core:jacocoTestCoverageVerification  # Coverage gate (35% minimum)
```

### Minimal Config

```yaml
# ~/.refio/config.yaml
providers:
  ollama:
    endpoint: "http://localhost:11434"

models:
  defaults:
    chat: "ollama/qwen3.5:9b"
    coding: "ollama/qwen3.5:9b"
    embedding: "ollama/nomic-embed-text"
```

### Environment Variables

```
OPENAI_API_KEY       # OpenAI provider
ANTHROPIC_API_KEY    # Anthropic provider
GEMINI_API_KEY       # Google Gemini
OPENROUTER_API_KEY   # OpenRouter
OLLAMA_BASE_URL      # Custom Ollama endpoint (default: http://localhost:11434)
LMSTUDIO_BASE_URL    # Custom LM Studio endpoint
```

---

## Documentation Map

| Document | Purpose |
|----------|---------|
| [README.md](../README.md) | Product overview, quick start, honest status |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Architecture diagrams, component details, design decisions |
| [overview.md](overview.md) | Full technical reference — tools, providers, security, flows (~1500 lines) |
| [config.md](config.md) | Complete configuration reference |
| [files.md](files.md) | Per-package file map (~537 main Kotlin files) |
| [CHANGELOG.md](../CHANGELOG.md) | Version history |
| [PRIVACY.md](../PRIVACY.md) | Privacy policy, no-egress mode |
| [QUICKSTART.md](../QUICKSTART.md) | User quick start guide |
| [CLAUDE.md](../CLAUDE.md) | Instructions for Claude Code AI assistant |
| [planning/prd.md](planning/prd.md) | Product Requirements Document |
| [planning/mvp.md](planning/mvp.md) | MVP scope |
