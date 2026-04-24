# Onboarding: Refio

> **Last Updated:** 2026-04-24
> **Version:** 0.0.1.8
> **Status:** Active Development

---

## Welcome

**Refio** is a local-first AI coding assistant for IntelliJ IDEA and the terminal (CLI/TUI). Written in Kotlin, built on a modular Gradle architecture, supporting 8 LLM providers (including local models via Ollama/LM Studio), 15 tools, 21 built-in subagents, and a full RAG system. Refio is MIT-licensed, with no telemetry and no vendor lock-in.

**Core philosophy:** Minimize LLM context through selective context injection (RAG + code analysis), resulting in 50-70% lower API costs and compatibility with smaller context windows of local models.

---

## Project Overview and Structure

Refio is a Kotlin/JVM project with three Gradle modules, each with its own source tree:

```
refio/
├── core/                    # IDE-independent core (~284 Kotlin files)
│   └── src/main/kotlin/pl/jclab/refio/core/
├── cli/                     # Standalone TUI (~46 Kotlin files)
│   └── src/main/kotlin/pl/jclab/refio/cli/
├── intellij-plugin/         # IntelliJ IDEA plugin (~118 Kotlin files)
│   └── src/main/kotlin/pl/jclab/refio/
├── docs/                    # Documentation (~10 files + planning/)
├── .github/                 # CI/CD workflows
└── .refio/                  # Runtime data (database, logs, cache)
```

**Total:** ~591 Kotlin files (284 core + 46 CLI + 118 plugin + 143 tests)

### Architectural Layers

```
UI (IntelliJ Swing / TUI Mordant+JLine3)
  -> Service Layer (SessionManager, MessageDispatcher)
    -> Domain Routers (12 routers: Task, Chat, Agent, Subtask, Config, Prompts, Tool, RAG, ApiLogs, MultiAgent, ProjectContext, Subagent)
    -> CoreApiRouter (composition root — creates dependencies, exposes routers, zero business logic)
      -> Execution (WorkflowOrchestrator -> ChatService for CHAT | AgentTurnLoop for PLAN/AGENT)
        -> LLMClient (8 adapters) + ToolRegistry (15 tools) + ContextService (14 providers)
          -> Infrastructure (SQLite via Exposed ORM, Ktor HTTP, Caffeine cache)
```

---

## Main Modules

### `:core` — Core (IDE-independent)

- **Role:** All business logic, LLM clients, tools, RAG, agents, database. No IntelliJ SDK dependency.
- **Kotlin:** 1.9.25, JDK 17
- **Key packages:**

| Package | Files | Description |
|---------|-------|-------------|
| `api/routers/` | ~12 | Domain routers (Task, Chat, Agent, Config, RAG, Tool, etc.) |
| `llm/adapters/` | 8 | LLM adapters: Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio, Custom OpenAI, Z.AI |
| `tools/implementations/` | 14 | Tools: read_file, grep_search, code_editing, http_request, run_code, invoke_subagent, etc. |
| `tools/security/` | ~7 | CommandRule (regex ALLOW/BLOCK/ASK), CommandWhitelist (legacy), FileLimits, PathSandbox |
| `services/` | ~35 | AgentTurnLoop, ContextService, RagIndexingService, ConfigService, ToolPermissionsService, etc. |
| `services/turn/` | ~13 | AgentTurnLoop sub-components: TurnLLMCaller, TurnPromptBuilder, TurnToolExecutor, ToolApprovalService, etc. |
| `services/context/` | ~6 | ContextBudget, WorkingMemoryService, ProjectInstructionsLoader, ToolResultCompression |
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
- **Key files:**

| Component | Description |
|-----------|-------------|
| `TuiApp.kt` | Entry point — `launchTuiApp()` |
| `TuiViewModel` | Coordinator (~1289 LOC, MVVM), 20 StateFlows, delegates to 3 sub-ViewModels |
| `TuiRenderer` | Full-screen compositor, split-pane layout, ANSI cursor positioning |
| `TuiRenderBuffer` | ANSI-aware line buffer, merge side-by-side |
| `TuiInputHandler` | Dual-mode input: raw TTY (F-keys, Ctrl+combo) / line mode (IDE terminal) |
| `TuiSettingsScreen` | 11 settings sub-tabs (General, Providers, Models, Prompts, Context, MCP, Tools, etc.) |

- **Layout:** 8 tabs (F1 Help, F2-F7 tabs, F8 Files) + F9 Settings, split-pane (55%/45%), responsive
- **Tests:** 39 test files

### `:intellij-plugin` — IntelliJ IDEA Plugin

- **Role:** Native plugin with Swing UI, settings panels, IDE integration (terminals, editor, clipboard, problems).
- **Kotlin:** 1.9.25, gradle-intellij-plugin 1.17.4
- **Target:** IntelliJ 2024.1.7 (IC), builds 241-253.*
- **Key areas:**

| Component | Files | Description |
|-----------|-------|-------------|
| `ui/` | ~74 | Swing components: chat bubbles, autocomplete, toolbar, settings panels |
| `services/` | ~21 | SessionManager (6 components), CoreConnectionManager, RAG, notification |
| `actions/` | 8 | IDE actions |
| `core/context/providers/` | ~13 | IDE-dependent context providers (@current, @open_files, @terminal, @problems) |

- **Tests:** 5 files (limited due to IntelliJ Platform API dependency)

---

## Three Execution Modes

| Mode | Executor | Tools | Iterations | Use Case |
|------|----------|-------|------------|----------|
| **CHAT** | ChatExecutor | None | - | Conversation, code explanations |
| **PLAN** | AgentTurnLoop | READ_ONLY (6) | Max 25 | Analysis, code review |
| **AGENT** | AgentTurnLoop | ALL (14) | Max 50 | Code generation, refactoring |
| **SUBAGENT** | AgentTurnLoop (profile) | Filtered | Max depth 3 | Delegated specialist tasks |

**ExecutionMode:** AUTO (autonomous) / INTERACTIVE (step-by-step approval)

---

## Tool System (15 tools)

### READ_ONLY (6)

| Tool | Description |
|------|-------------|
| `read_file` | Read file content (max 2MB) |
| `read_directory` | List directories (depth 10) |
| `file_search` | Glob pattern search (100 results) |
| `grep_search` | Regex search (500 results) |
| `view_diff` | Line-by-line file comparison |
| `invoke_subagent` | Invoke nested subagent |

### WRITE (8)

| Tool | Description | Cost |
|------|-------------|------|
| `create_new_file` | Create file with parent directories | Free |
| `code_editing` | Search-and-replace | Free |
| `multi_edit` | Atomic multi-file edit | Free |
| `multi_line_editor` | LLM identifies line ranges | ~$0.02 |
| `advance_code_editing` | Full file regeneration | ~$0.06 |
| `run_terminal_command` | Shell execution (ASK in AGENT, CommandRule-protected) | Free |
| `http_request` | HTTP GET/POST/PUT/DELETE, save_to_file | Free |
| `run_code` | Python, JavaScript, Kotlin Script (120s) | Free |

### Permissions (3-level)

| Level | Behavior |
|-------|----------|
| **ON** | Automatic execution |
| **ASK** | Requires user approval (ToolApprovalService with session trust rules, 5-min timeout) |
| **OFF** | Disabled |

---

## LLM Providers (8)

| Provider | Models | Features |
|----------|--------|----------|
| **Ollama** | Local (qwen3.5, llama, etc.) | Free, JSON mode, privacy |
| **OpenAI** | GPT-4o, GPT-4o-mini, o1, o3, GPT-5 | Responses API, reasoning |
| **Anthropic** | Claude 3.5/3.7, Opus 4.1 | Thinking mode, top-level system |
| **Gemini** | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| **OpenRouter** | 100+ models | Unified gateway, dynamic pricing |
| **LM Studio** | Local models | OpenAI-compatible, free |
| **Custom OpenAI** | Any OpenAI-compatible API | Configurable base URL, API key |
| **Z.AI** | Z.AI models | Rate-limited, OpenAI-compatible |

**Reference model for testing:** `ollama/qwen3.5:9b`

---

## Security System (7 layers)

| Layer | Protection |
|-------|------------|
| **PathSandbox** | File operations restricted to project root, symlink detection |
| **FileLimits** | Max 2MB, 24 excluded directories, 34 excluded extensions |
| **CommandRule** | Regex-based: 22 BLOCK rules, 154 ALLOW, ASK rules (docker, kubectl, ssh, sudo) |
| **ToolPermissions** | ON/ASK/OFF per mode, per task, ToolApprovalService with trust rules |
| **Mid-Execution Control** | PendingUserMessageQueue, ToolRejectedException, ExecutionMode AUTO/INTERACTIVE |
| **No-Egress Mode** | Blocks cloud providers, only Ollama/LM Studio allowed |
| **Secret Redaction** | API keys masked in all logs |

---

## Subagents (21 built-in)

Invocation: `!agent-name <prompt>` in PLAN/AGENT mode or `invoke_subagent` tool (nested invocation).

| Category | Agents |
|----------|--------|
| **Quality & Security** | code-reviewer, security-engineer, architect-reviewer |
| **Development** | frontend-developer, fullstack-developer, refactoring-specialist, api-designer, ui-designer |
| **Documentation** | documentation-engineer, api-documenter, technical-writer |
| **Infrastructure** | sre-engineer |
| **Business & Product** | business-analyst, product-manager, project-manager, legal-advisor, ux-researcher, risk-manager |
| **Orchestration** | multi-agent-coordinator, workflow-orchestrator |
| **Research** | research-analyst |

**Custom subagents:** `~/.refio/agents/*.md` (user) or `.refio/agents/*.md` (project)

---

## Context System (14 providers)

| Provider | Type | Description |
|----------|------|-------------|
| `@file` | SUBMENU | File picker with search |
| `@folder` | SUBMENU | Directory browser |
| `@current` | NORMAL | Active editor file |
| `@recent` | SUBMENU | Recently edited files (15-file history) |
| `@open_files` | NORMAL | All open editor tabs |
| `@clipboard` | NORMAL | System clipboard content |
| `@terminal` | NORMAL | Terminal output (max 200 lines) |
| `@problems` | NORMAL | Compilation errors/warnings |
| `@diff` | NORMAL | Git uncommitted changes |
| `@codebase` | QUERY | Semantic search via RAG |
| `@grep` | QUERY | Regex search across project |
| `@url` | QUERY | Fetch web content (100KB max) |
| `@commit` | QUERY | Git commit details |
| `@docs` | SUBMENU | Documentation search with semantic ranking |

**Token budgeting:** ~28K tokens by default, with per-section limits.

---

## RAG System

- Automatic background project indexing (40+ file types)
- Incremental updates (SHA-256 checksum-based change detection)
- 5 language analyzers: Kotlin, Java, Python, TypeScript, HTML
- Semantic chunking (classes, functions, full-file) + default (line-based)
- Embeddings: Ollama (nomic-embed-text 768 dims) / OpenAI (text-embedding-3-small 1536 dims)
- Cosine similarity search + optional hybrid (70% semantic + 30% keyword)

---

## MCP Protocol (17 presets)

| Category | Servers |
|----------|---------|
| **VCS** | GitHub, GitLab |
| **Databases** | PostgreSQL, SQLite, Database (HTTP) |
| **Search** | Brave Search, Exa |
| **Documentation** | Context7 |
| **DevOps** | Sentry, AWS |
| **Storage** | Google Drive, Filesystem |
| **Development** | Puppeteer, Sequential Thinking, Custom API |
| **Communication** | Slack |
| **Memory** | Memory |

---

## Key Contributors

| Contributor | Role |
|-------------|------|
| **Jaroslaw Czub** (jarek / jaroslaw.czub) | Primary author and maintainer, 100% of commits |

---

## General Insights and Recent Development Directions

### Active Areas (v0.0.1.5, 2025-04-02)

1. **Standalone CLI/TUI** — full terminal alternative to the IntelliJ plugin, sharing the same `:core` module. Split-pane layout, 8 tabs, 11 settings sub-tabs.
2. **Multi-agent architecture** — parallel orchestration, event bus, dependency resolution, YAML task definitions.
3. **CommandRule security system** — new regex-based system with 3 levels (ALLOW/BLOCK/ASK) replacing the legacy whitelist. ToolApprovalService with session trust rules.
4. **Mid-execution control** — PendingUserMessageQueue enables user feedback while agent is running. ExecutionMode AUTO/INTERACTIVE.
5. **New tools** — `http_request` (with `save_to_file`), `run_code` (Python, JS, Kotlin Script).
6. **Reference model** — project is primarily tested on `qwen3.5:9b` (Ollama).

### Architectural Patterns

- **Thin Router Pattern** — CoreApiRouter is a composition root (~987 LOC), zero business logic
- **MVVM** — TUI with TuiViewModel (20 StateFlows -> merged TuiState)
- **Strategy** — ChunkingStrategy, EmbeddingProvider, CommandRule validation
- **Circuit Breaker** — EmbeddingCircuitBreaker (CLOSED/OPEN/HALF_OPEN)
- **Parallel Execution** — READ_ONLY tools run concurrently (~2-3x faster)
- **Hierarchical Config** — 4-level: Database > Project YAML > User YAML > Built-in defaults

---

## Potential Complexity / Areas Requiring Attention

### High Complexity

| Area | File(s) | Notes |
|------|---------|-------|
| **AgentTurnLoop** | `core/services/AgentTurnLoop.kt` (~1000+ LOC) | Main execution loop: compaction, retry, parallel tools, working memory, mid-execution messages |
| **CoreApiRouter** | `core/api/CoreApiRouter.kt` (~987 LOC) | Composition root — creates ~35 services, must remain thin (zero logic) |
| **ContextService** | `core/services/ContextService.kt` | Delegates to 6 sub-services, token budgeting, pruning |
| **TuiViewModel** | `cli/tui/state/TuiViewModel.kt` (~1289 LOC) | Coordinator of 20 StateFlows, 3 sub-ViewModels |
| **TuiRenderer** | `cli/tui/rendering/TuiRenderer.kt` | ANSI rendering, split-pane, cursor management, flicker-free |

### Pitfalls for New Developers

1. **`:core` module CANNOT depend on IntelliJ SDK** — all core code must work without the IDE. IDE-dependent context providers live in `intellij-plugin/`.
2. **Kotlin version mismatch** — Core and Plugin: Kotlin 1.9.25, CLI: Kotlin 2.0.21. Keep this in mind when adding dependencies.
3. **Thin Router Pattern** — DO NOT add business logic to CoreApiRouter or domain routers. Routers = delegation only.
4. **CommandRule vs CommandWhitelist** — The new CommandRule system (regex-based) is gradually replacing the legacy CommandWhitelist. New code should use CommandRule.
5. **ToolPermissions 3-level** — Tools now have 3 permission levels (ON/ASK/OFF), not 2. `run_terminal_command` is ASK in AGENT, not ON.
6. **Database location** — Changed from `refio_poc.db` (project root) to `~/.refio/data/database.sqlite` (shared across projects).

---

## Questions for the Team

1. What is the migration plan from CommandWhitelist to CommandRule? Will the legacy whitelist be removed in the next version?
2. Which LLM models besides `qwen3.5:9b` are regularly tested? Is there a model compatibility matrix?
3. What is the scope of planned MCP changes? Are new presets planned?
4. What does the release and distribution process look like for the JetBrains Marketplace plugin?
5. What are the testing priorities — increasing plugin coverage (currently 5 files) or expanding core tests?
6. Is support for other IDEs planned beyond IntelliJ (VS Code, other JetBrains IDEs)?
7. What is the plan for multi-agent architecture — production use or experimental status?

---

## Next Steps

1. **Set up your environment** — install JDK 17, Ollama, pull models (see section below).
2. **Run the sandbox IDE** — `./gradlew :intellij-plugin:runIde` — explore the plugin in a sandbox IntelliJ.
3. **Run the CLI** — `./gradlew :cli:installDist && ./cli/build/install/cli/bin/cli --project .` — explore the TUI.
4. **Read ARCHITECTURE.md** and **overview.md** — full architecture overview and data flows.
5. **Run tests** — `./gradlew test` — make sure everything passes.

---

## Developer Environment Setup

### Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| **JDK** | 17 | Auto-provisioned by Gradle (Foojay resolver) |
| **IntelliJ IDEA** | 2024.x | For plugin development |
| **Ollama** | Latest | For local models and embeddings |
| **Git** | Latest | Version control |

### Installation and Running

```bash
# 1. Clone the repository
git clone https://github.com/shadoq/refio.git && cd refio

# 2. Install Ollama (https://ollama.com/)
ollama pull nomic-embed-text    # Required for RAG embeddings
ollama pull qwen3.5:9b           # Reference coding model

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
./gradlew :core:test --tests "pl.jclab.refio.core.tools.ReadFileToolTest"  # Single class

# 8. Code quality
./gradlew :intellij-plugin:check  # Includes detectSensitiveLogging
./gradlew :intellij-plugin:jacocoTestReport  # Coverage (40% minimum)
```

### Configuration (optional)

```yaml
# ~/.refio/config.yaml
general:
  formatMarkdown: true
  streamingEnabled: true

providers:
  ollama:
    endpoint: "http://localhost:11434"
  anthropic:
    apiKey: "sk-ant-..."    # Optional, for cloud models

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
OLLAMA_BASE_URL      # Custom Ollama endpoint (default http://localhost:11434)
LMSTUDIO_BASE_URL    # Custom LM Studio endpoint
```

---

## Useful Resources

### Internal Documentation

| Document | Description |
|----------|-------------|
| [README.md](../README.md) | Project overview, quick start, comparison with alternatives |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Architecture, components, key decisions |
| [overview.md](overview.md) | Detailed technical overview (~1500 lines) |
| [config.md](config.md) | Full configuration reference |
| [files.md](files.md) | Per-package file reference (~590 Kotlin files) |
| [CHANGELOG.md](../CHANGELOG.md) | Version history |
| [PRIVACY.md](../PRIVACY.md) | Privacy policy, no-egress mode |
| [QUICKSTART.md](../QUICKSTART.md) | Quick start guide |
| [CLAUDE.md](../CLAUDE.md) | Instructions for Claude Code (AI coding assistant) |

### Planning Documentation

| Document | Description |
|----------|-------------|
| [planning/prd.md](planning/prd.md) | Product Requirements Document |
| [planning/prd-session.md](planning/prd-session.md) | Session management PRD |
| [planning/mvp.md](planning/mvp.md) | MVP scope |
| [planning/tech-stack.md](planning/tech-stack.md) | Tech stack decisions |
| [planning/ui-plan.md](planning/ui-plan.md) | UI plan |
| [planning/tools-usage.md](planning/tools-usage.md) | Tools usage guide |

### Repository

- **GitHub:** https://github.com/shadoq/refio
- **License:** MIT
- **CI/CD:** GitHub Actions (`.github/workflows/`)

---

## Technology Summary

| Technology | Version | Usage |
|------------|---------|-------|
| Kotlin | 1.9.25 / 2.0.21 (CLI) | Primary language |
| JDK | 17 | Target JVM |
| Gradle | 7.x+ | Build system |
| IntelliJ Platform | 2024.1.7 (IC) | Plugin SDK |
| Ktor | 2.3.7 | HTTP server + client |
| Exposed ORM | 0.46.0 | Database access (SQLite) |
| SQLite | 3.44.1.0 (JDBC) | Database (WAL mode) |
| kotlinx-coroutines | 1.7.3 | Async execution |
| kotlinx-serialization | 1.6.2 | JSON serialization |
| Mordant | 3.0.1 | TUI rendering (ANSI) |
| JLine3 | 3.26.3 | Terminal raw input |
| Clikt | 5.0.2 | CLI argument parsing |
| Caffeine | 3.1.8 | In-memory caching |
| JUnit 5 | 5.10.1 | Testing |
| MockK | 1.13.8 | Mocking |
| Turbine | 1.0.0 | Flow testing |
