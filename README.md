# Refio
Refio – open source, local-first coding companion for IntelliJ

## Table of Contents

* [Project name](#refio)
* [Project description](#project-description)
* [How Refio Works](#how-refio-works)
* [Subagents](#subagents)
* [Tech stack](#tech-stack)
* [Getting started locally](#getting-started-locally)
* [Available scripts](#available-scripts)
* [Project scope](#project-scope)
* [Project status](#project-status)
* [Roadmap](#roadmap)
* [Repository structure](#repository-structure)
* [Architecture overview](#architecture-overview)
* [Building and installing the plugin](#building-and-installing-the-plugin)
* [License](#license)

---

## Project description

Refio is a local-first AI assistant packaged as an IntelliJ plugin (Kotlin) with an embedded core. It supports three modes: **Chat** (conversation + project context), **Planning** (read-only steps with approval), and **Agent** (read/write). The architecture is fully Kotlin: the plugin calls **CoreApiRouter** in-process (no HTTP), data is stored in SQLite (WAL), and the UI uses native IntelliJ components (no webview). It is tested with local models and smaller cloud-hosted models, and is designed to be cost-optimized by default.

**Design Philosophy:** Refio is inspired by agents like Claude Code, Open Coder, Codex, Junie, and Continue.dev, but built with one guiding principle: **minimize LLM context instead of sending everything to the model**. To achieve this, the plugin leverages RAG (Retrieval-Augmented Generation) and comprehensive code analysis to provide only the most relevant information. The plugin is written natively in Kotlin to ensure maximum compatibility with IntelliJ IDEA and maintain seamless integration with the IDE's ecosystem.

**Experimental Nature:** This project represents a unique experiment in AI-assisted development: **can coding agents successfully build another coding agent?** Refio serves as both a practical coding assistant and a proof-of-concept that demonstrates the potential for AI systems to create sophisticated development tools through iterative collaboration and self-improvement.

### Key capabilities
- Embedded Kotlin core.
- Local privacy controls: no-egress toggle and path sandbox (project-root only).
- Tools system: 10 tools (5 read-only, 5 active write; terminal tool is disabled by default).
- UnifiedStepExecutor with simple auto mode and orchestration (reflection loop).
- Context providers for @file/@folder/@codebase/@docs (URLs + local files)/@grep/@url/@commit/etc.; lightweight RAG on SQLite.
- **MCP (Model Context Protocol) Support**: Full implementation of MCP protocol for extensible context sources.
- **Subagents System**: Specialized AI assistants invoked with `!agent-name` prefix. Built-in agents for security reviews and code quality, with support for custom user and project agents.
- **File-level RAG indexing**: Automatic analysis and indexing of code files with language-specific analyzers (Kotlin/Java/Python/TypeScript), semantic chunking, embeddings, and PDF extraction.
- **Semantic project summary**: Compact architecture/key-component summary optimized for small LLMs.
- **Conversation summarization**: Automatic summarization of long chat sessions to reduce token usage and API costs (~50-70% reduction).
- **Advanced limits**: Configurable timeouts, max context/output, max file size, and auto-optimize threshold.
- **Background project indexing**: Automatic indexing of entire project at IDE startup with checksum-based change detection.
- **Code analysis**: Richer signatures, documentation hints, and lightweight pattern detection.
- Cost and metrics tracking per step and session.

---

## How Refio Works

Refio operates in two primary modes, each with its own distinct workflow designed to optimize interaction between the user and the AI assistant:

### Chat Mode Workflow

```
┌─────────────────┐
│ 1. User enters  │
│    prompt       │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ 2. Dynamic      │
│    project      │
│    context      │
│    is built     │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ 3. Everything   │
│    is sent to   │
│    LLM model    │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ 4. User         │
│    receives     │
│    response     │
└─────────────────┘
```

### Plan/Agent Mode Workflow

```
┌─────────────────┐
│ 1. User         │
│    provides     │
│    command      │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ 2. Application  │
│    context is   │
│    built        │
│    (dynamic)    │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ 3. Planning     │
│    phase for    │
│    execution    │
│    steps        │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ 4. Planning     │◄──┐
│    phase for    │   │
│    specific     │   │
│    step         │   │
└─────┬───────────┘   │
      │               │
      ▼               │
┌─────────────────┐   │
│ 5. Step         │   │
│    execution    │   │
│    and step     │   │
│    summary      │   │
└─────┬───────────┘   │
      │               │
      ▼               │
┌─────────────────┐   │
│ 6. Plan         │   │
│    modification │   │
│    phase (add,  │   │
│    modify,      │   │
│    remove step) │   │
└─────┬───────────┘   │
      │               │
      ▼               │
┌─────────────────┐   │
│ 7. More steps?  │───┘
│    Yes → Go to 4│   
│    No → Go to 8 │   
└─────┬───────────┘   
      │               
      ▼               
┌─────────────────┐   
│ 8. Final task   │   
│    summary and  │   
│    completion   │   
└─────────────────┘   
```

**Chat Mode** provides immediate responses with full project context, ideal for quick questions, code explanations, and conversational interactions.

**Plan/Agent Mode** offers a structured, iterative approach where the AI breaks down complex tasks into manageable steps, executes them systematically, and adapts the plan based on results - perfect for comprehensive code refactoring, feature implementation, and multi-step development tasks.

---

## Subagents

Refio includes a **subagents system** - specialized AI assistants that can be invoked for specific tasks. Subagents are context-aware, tool-restricted assistants that excel at focused operations like security audits or code reviews.

### Invoking Subagents

Type `!` followed by the agent name and your prompt:

```
!security-reviewer Review this authentication code for vulnerabilities
!code-reviewer Check this function for potential bugs
```

The autocomplete popup (triggered by `!`) shows all enabled subagents sorted by priority.

### Built-in Subagents

| Agent | Purpose | Model | Tools |
|-------|---------|-------|-------|
| **security-reviewer** | Security audits, OWASP vulnerabilities, authentication/authorization checks | weak | read_file, grep_search, file_search |
| **code-reviewer** | Code quality, design patterns, bug detection, maintainability | default | read_file, grep_search, file_search |

### Custom Subagents

Create your own subagents by adding Markdown files with YAML frontmatter:

**User-level:** `~/.refio/agents/my-agent.md`
**Project-level:** `.refio/agents/my-agent.md`

```markdown
---
name: my-agent
description: Custom agent for specific task
tools: read_file, grep_search
model: default
priority: 5
enabled: true
---

You are a specialized assistant for...
[system prompt content]
```

**Configuration fields:**
- `name`: Agent identifier (required)
- `description`: Short description (required)
- `tools`: Comma-separated tool names (optional, defaults to inherit)
- `disallowedTools`: Tools to exclude (optional)
- `model`: Model alias - `default`, `plan`, `coding`, `weak`, or `inherit` (optional)
- `priority`: Auto-delegation priority, higher = preferred (default: 0)
- `enabled`: Whether agent is active (default: true)

### Tool Filtering

Subagents respect mode restrictions:
- **CHAT/PLAN mode**: Only READ_ONLY tools (`read_file`, `grep_search`, `file_search`, `read_directory`, `view_diff`)
- **AGENT mode**: All tools as specified in the agent definition

### Managing Subagents

Use the **Subagents** settings panel (Settings → Subagents) to:
- View all subagents (built-in, user, and project)
- Create/edit/delete custom subagents
- Toggle enabled state (including built-in agents)
- View system prompts (read-only for built-in agents)

---

## Tech stack

- **Language/Platform:** Kotlin 1.9.25, JVM target 17, IntelliJ Platform 2024.1.7 (IC)
- **UI:** native IntelliJ Swing components; no webview
- **Core/Transport:** in-process CoreApiRouter; optional Ktor server wrapper (CLI planned v1.1+)
- **LLM/HTTP:** Ktor Client 2.3.7; adapters for Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio
- **Database:** SQLite (WAL) via Exposed 0.46.0 + sqlite-jdbc 3.44.1.0
- **Serialization:** Gson 2.10.1, kotlinx-serialization-json 1.6.2, KAML (YAML) 0.55.0
- **Markdown/UI:** commonmark 0.21.0
- **Logging:** kotlin-logging + logback 1.4.14
- **Build:** Gradle IntelliJ Plugin 1.17.4; `instrumentCode` disabled (JDK 21 issue)

---

## Getting started locally

### Prerequisites

For proper plugin functionality, you need to install and configure Ollama:

1. **Install Ollama** from [https://ollama.com/](https://ollama.com/)
2. **Download required models** using `ollama pull`:
   ```bash
   ollama pull nomic-embed-text:latest
   ollama pull qwen2.5-coder:14b
   ollama pull gpt-oss:20b
   ollama pull qwen3:14b
   ollama pull deepseek-r1:14b
   ```
3. **Set Ollama context** to minimum 32k, preferably 64k tokens for optimal performance

**Note:** The plugin is tested with small local models (listed above) as well as external providers like OpenAI (e.g., gpt-4.1-mini), Anthropic, LM Studio, and OpenRouter. Due to ongoing development and the variety of available models, some errors may occur.

### Setup Steps

1. Install JDK 17 and IntelliJ IDEA 2024.x.
2. `git clone https://github.com/shadoq/refio.git && cd refio`
3. `cd agent/plugin && ./gradlew runIde` (Windows: `.\\gradlew.bat runIde`) to launch a sandbox IDE with the plugin.
4. Optional: add provider keys/endpoints via Settings or `%USERPROFILE%\\.refio\\config.yaml` (`~/.refio/config.yaml`).
   The Ollama endpoint is used for both chat/completions and embeddings.
5. Open the **Refio** tool window and use Chat/Plan/Agent modes.

### User Interface

After startup, Refio displays a **simple chat window view** by default, providing a clean and focused interface for immediate interaction with the AI assistant.

To access additional debugging and monitoring capabilities, enable **Advanced View** by navigating to **Settings → General** and enabling the advanced view option. This reveals:

- **Context Preview** - View the dynamically built project context before it's sent to the LLM
- **RAG Components** - Monitor Retrieval-Augmented Generation processes and search results
- **Logs** - Access detailed logging information for troubleshooting and monitoring
- **Debug Panel** - Simple debugging interface for development and issue analysis

The advanced view is particularly useful for understanding how Refio processes your project context and for troubleshooting any issues that may arise during operation.

See `QUICKSTART.md` for the full installation flow (ZIP install, troubleshooting, configuration).

---

## Configuration

Refio uses a hierarchical configuration system with multiple sources (in priority order):

1. **Database** (Settings UI) - Highest priority
2. **Project config** (`<project>/.refio/config.yaml`) - Project-specific settings
3. **User config** (`~/.refio/config.yaml`) - Personal preferences
4. **Built-in defaults** - Fallback values

### Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| User config | `~/.refio/config.yaml` (Linux/macOS)<br>`%USERPROFILE%\.refio\config.yaml` (Windows) | Personal settings, API keys |
| Project config | `<project>/.refio/config.yaml` | Project-specific prompts, MCP servers |

### Quick Start Example

```yaml
# ~/.refio/config.yaml
general:
  formatMarkdown: true
  streamingEnabled: true

providers:
  ollama:
    endpoint: "http://localhost:11434"
  anthropic:
    apiKey: "sk-ant-..."      # Your API key

models:
  defaults:
    chat: "ollama/qwen2.5:7b"
    coding: "ollama/qwen2.5-coder:7b"
    embedding: "ollama/nomic-embed-text"

  visibility:
    "ollama/qwen2.5:7b": true
    "anthropic/claude-3-5-sonnet-20241022": true
```

### Project-Specific Configuration

```yaml
# <project>/.refio/config.yaml
prompts:
  systemChat: |
    You are a Kotlin expert. Follow CLAUDE.md guidelines.

  commands:
    - name: "review"
      description: "Code review"
      content: "Review this code for best practices."
      enabled: true

mcp:
  servers:
    - id: "github"
      type: "STDIO"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-github"]
      enabled: true
```

You can add a project-level `.aiignore` file (same syntax as `.gitignore`) to override default ignore paths for RAG indexing, project analysis, and automatic searches (for example `@codebase` and `@grep`). Explicit `@file` and `@folder` selections are not filtered. When present, Settings -> Context shows the `.aiignore` patterns as read-only.

> **Full configuration documentation:** See [`docs/config.md`](docs/config.md) for all available options, including RAG search tuning.

---

## Example Prompts

The plugin has been tested with various coding prompts, including some fun game development challenges. Here's what we've been testing with (and yes, it's a snake 🐍):

### JS Games

**Classic Snake Game:**
```
Write a snake game in javascript, css, html in one file. Build a classic Snake game on a 30x30 grid. The snake moves continuously in one of four directions and grows when eating food. If it hits a wall or itself, the game ends. Implement keyboard controls for player movement. Add a CPU-controlled snake mode that automatically searches for food. Add the three game modes: human vs human, cpu vs human, cpu vs cpu. Display the score during gameplay. Include a start menu and game over screen. Allow restarting the game with a keypress. Use file name "snake.html"
```

**3-CPU Snake Game:**
```
Write a snake game in javascript, css, html in one file. Build a classic Snake game on a 30x30 grid. The snake moves continuously in one of four directions and grows when eating food. If it hits a wall or itself, the game ends. Implement keyboard controls for player movement. Add a CPU-controlled snake mode that automatically searches for food. Add the three game modes: human vs human, cpu vs human, cpu vs cpu, cpu vs cpu vs cpu (3 cpu players). Display the score during gameplay. Include a start menu and game over screen. Allow restarting the game with a keypress. Use file name "snake3cpu_01.html"
```

**Breakout Clone:**
```
Create breakout clone using javascript, css, html in one file. Develop a Breakout-style arcade game with a paddle, a bouncing ball, and a wall of colored bricks. The ball reflects off walls and bricks, breaking them on contact. The player controls the paddle with the mouse or keyboard to keep the ball in play. Losing all lives ends the game. Add a score counter based on destroyed bricks. Include basic sound effects for hits. Show a "Game Over" screen with a restart option. Bricks can be arranged in multiple levels or random patterns. All production code in one file file name "breakout.html".
```

**Pong with AI:**
```
Design Pong with AI players using javascript, css, html in one file. Create a two-player Pong game with rectangular paddles and a ball bouncing between them. The left paddle is controlled by the player, the right by a simple AI. The ball bounces off the paddles and top/bottom screen edges. A player scores when the opponent misses the ball. Display each player's score on screen. Add a start screen with a "Player vs AI" and "AI vs AI" mode. Keep gameplay fast and smooth. Include sound effects and restart functionality. All production code in one file file name "pong.html".
```

---

## Available scripts

```bash
cd agent/plugin
# Development / build
./gradlew runIde          # Windows: .\\gradlew.bat runIde
./gradlew build
./gradlew clean build
./gradlew buildPlugin     # ZIP in build/distributions

# Quality
./gradlew detekt
./gradlew ktlintCheck
./gradlew ktlintFormat
./gradlew detectSensitiveLogging

# Tests (when available)
./gradlew test
./gradlew test jacocoTestReport
```

---

## Project scope

- **Modes:** Chat (conversation + context), Planning (read-only, approve each step), Agent (read/write with interactive or approve-all, snapshot-before-write, rollback).
- **Tools:** read_file, read_directory, file_search, grep_search, view_diff; create_new_file, code_editing, advance_code_editing, multi_edit; run_terminal_command (disabled by default until denylist/sandbox hardening).
- **Context/RAG:** providers @file/@folder/@codebase/@docs/@url/@commit/@grep/@terminal/@diff; local SQLite index with embeddings (OpenAI/Ollama) and top-k search.
- **MCP (Model Context Protocol):** Full MCP protocol support with STDIO and HTTP/SSE transports; 16 built-in presets (GitHub, GitLab, PostgreSQL, SQLite, Brave Search, Exa, Google Drive, Slack, AWS, Context7, Sentry, Memory, Puppeteer, Sequential Thinking, Filesystem, Custom API); automatic env var resolution; dual access modes (READ/READ_WRITE); dynamic context injection and tool registration for Agent mode.
- **Security:** path sandbox (known P0 symlink escape), no-egress toggle, read-only mode, terminal denylist, size/time limits, secret redaction in logs.
- **Distribution:** single plugin JAR/ZIP (~30MB), no external services required to start.

---

## Project status

- **Version:** 0.0.1 (full-Kotlin plugin + embedded core)
- **CLI:** planned (v1.1+), not implemented yet.
- **Tests:** Kotlin coverage 0% (migration in progress).
- **Recent implementations:**
    - ✅ **Plan as Specification**: Separation of plan creation (PLAN mode) from execution (AGENT sessions) with snapshot-based copying
    - ✅ **CoreApiRouter Refactor**: Split into 10 domain routers (~1,735 LOC main, delegated logic in separate routers)
    - ✅ **SessionManager Refactor**: Extracted into 8 focused components (SessionStateManager, SessionLifecycleService, MessageDispatcher, ExecutionMonitor, SubtaskTracker, etc.)
    - ✅ **MCP Protocol Support**: Full Model Context Protocol with STDIO/HTTP transports, 16 presets, tool/context modes
    - ✅ **File Analysis Service**: Language analyzers (Kotlin/Java/Python/TypeScript/HTML), semantic chunking, embeddings
    - ✅ **Conversation Summarization**: Context optimization with ~50-70% token reduction
    - ✅ **Startup RAG Indexing**: Automatic project indexing at IDE startup with checksum-based change detection
    - ✅ **AST-based Analysis**: Regex-based analyzers for Kotlin/Java/Python/TypeScript extracting classes, functions, imports
    - ✅ **Secret redaction in logs**: API keys and tokens redacted in LLM logs
    - ✅ **Orchestration Strategy**: Reflection loop with dynamic plan modification (add/skip/modify/retry steps)
- **Known critical issues:**
    - `PathSandbox.kt`: symlink escape vulnerability (blocks safe terminal enablement)
    - `RunTerminalCommandTool.kt`: denylist bypass risk (tool kept disabled)

---

## Roadmap
- **v0.1 (current work):**
    - Stabilize embedded core
    - Fix P0 issues (PathSandbox, terminal denylist, plaintext key logs)
    - Split CoreApiRouter/SessionManager god objects
    - Add basic Kotlin tests
    - RAG implementation - startup indexing
- **v0.2:**
    - CLI parity (Ktor wrapper)
    - Export/import flows
    - Better context preview and cost controls
    - File watcher for automatic RAG index updates
    - AST-based analysis
    - ...
---

## Repository structure

```
build.gradle.kts                    # Plugin + embedded core build
src/main/kotlin/pl/jclab/refio/
├── api/                            # Public API models
│   └── models/                     # Session, ExecutionMode, CodeSnippet, TaskPlan
├── core/                           # Embedded core (no IDE dependencies)
│   ├── api/                        # Router layer (10 domain routers)
│   │   ├── CoreApiRouter.kt        # Main facade (~1,735 LOC after split)
│   │   ├── ChatRouter.kt           # Chat messaging
│   │   ├── PlanningRouter.kt       # Plan generation
│   │   ├── PlanRouter.kt           # Plan specification management
│   │   ├── AgentRouter.kt          # Execution orchestration
│   │   ├── RagRouter.kt            # RAG indexing & search
│   │   └── ...                     # Config, Tool, Task, Subtask, Prompts, ApiLogs
│   ├── context/                    # Context system
│   │   ├── BaseContextProvider.kt  # Provider interface
│   │   ├── ContextProviderRegistry.kt
│   │   └── providers/              # @file, @folder, @codebase, @docs, @url, @grep...
│   ├── db/                         # Database layer
│   │   ├── DatabaseFactory.kt      # SQLite + Exposed ORM
│   │   ├── migrations/             # Schema migrations
│   │   └── repositories/           # Data access layer
│   ├── llm/                        # LLM integration
│   │   ├── LLMClient.kt            # Unified provider interface
│   │   ├── adapters/               # Anthropic, OpenAI, Gemini, Ollama, OpenRouter, LMStudio
│   │   ├── ModelRegistry.kt        # Dynamic model discovery
│   │   └── Pricing.kt              # Cost calculation
│   ├── mcp/                        # Model Context Protocol
│   │   ├── MCPManager.kt           # Server lifecycle management
│   │   ├── MCPConnection.kt        # JSON-RPC 2.0 handler
│   │   ├── MCPStdioTransport.kt    # Subprocess transport
│   │   ├── MCPHttpTransport.kt     # HTTP/SSE transport
│   │   └── MCPServerPresets.kt     # 16 built-in presets
│   ├── services/                   # Core services
│   │   ├── analysis/               # Code analysis, embeddings
│   │   │   ├── FileAnalyzerService.kt
│   │   │   ├── EmbeddingsService.kt
│   │   │   └── project/            # Project analysis
│   │   ├── execution/unified/      # Execution system
│   │   │   ├── UnifiedStepExecutor.kt
│   │   │   ├── ExecutionStrategy.kt
│   │   │   ├── SimpleAutoStrategy.kt
│   │   │   └── OrchestrationStrategy.kt
│   │   ├── orchestration/          # Reflection loop
│   │   │   ├── ReflectionEngine.kt
│   │   │   ├── PlanModifier.kt
│   │   │   └── UserInteraction.kt
│   │   ├── RagIndexingService.kt
│   │   ├── RagSearchService.kt
│   │   ├── ContextService.kt
│   │   └── SnapshotService.kt
│   ├── tools/                      # Tool system
│   │   ├── base/                   # Tool, ToolRegistry, ToolFactory
│   │   ├── implementations/        # 10 tool implementations
│   │   └── security/               # PathSandbox, FileLimits, CommandDenylist
│   └── prompts/                    # Prompt templates
├── services/                       # Plugin services (project-scoped)
│   ├── session/                    # SessionManager (refactored into 8 components)
│   │   ├── SessionManager.kt       # Facade
│   │   ├── SessionStateManager.kt  # Reactive state (11 StateFlows)
│   │   ├── SessionLifecycleService.kt
│   │   ├── MessageDispatcher.kt
│   │   ├── ExecutionMonitor.kt
│   │   └── SubtaskTracker.kt
│   ├── core/                       # CoreConnectionManager
│   ├── execution/                  # StepExecutionService
│   └── logging/                    # DualLogger
├── startup/                        # ProjectStartupActivity (RAG indexing)
└── ui/                             # IntelliJ UI components
    ├── components/                 # Reusable components
    ├── settings/                   # Settings panels
    └── toolwindow/                 # Tool window factory

docs/                               # Documentation
├── overview.md                     # Full technical architecture
├── planning/                       # Product planning
├── implemented/                    # Completed RFCs
└── todo/                           # Pending tasks
```

---

## Architecture overview

Workflow v2 plumbing lives in `src/main/kotlin/pl/jclab/refio/core/workflow/` (IntentRouter, WorkflowOrchestrator, adapter executors) with UI streaming adapter in `src/main/kotlin/pl/jclab/refio/ui/listeners/`.

### Layer Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  UI Layer (IntelliJ Swing)                                              │
│  RefioToolWindowFactory → ChatPanel / PlanPanel / AgentPanel            │
├─────────────────────────────────────────────────────────────────────────┤
│  Service Layer (Project-Scoped)                                         │
│  SessionManager (facade)                                                │
│  ├── SessionStateManager (11 StateFlows for reactive UI)                │
│  ├── SessionLifecycleService (session create/switch/load)               │
│  ├── MessageDispatcher (CHAT vs PLAN/AGENT routing)                     │
│  ├── ExecutionMonitor (step execution lifecycle)                        │
│  └── SubtaskTracker (subtask CRUD operations)                           │
├─────────────────────────────────────────────────────────────────────────┤
│  Core Layer (In-Process API)                                            │
│  CoreApiRouter (facade + domain delegation)                             │
│  ├── ChatRouter → ChatService                                           │
│  ├── PlanningRouter → PlanningService                                   │
│  ├── PlanRouter → PlanRepository (plan specifications)                  │
│  ├── AgentRouter → AgentExecutor → UnifiedStepExecutor                  │
│  ├── RagRouter → RagSearchService + RagIndexingService                  │
│  ├── ConfigRouter, ToolRouter, TaskRouter, SubtaskRouter...             │
│  └── ContextService (dynamic context building)                          │
├─────────────────────────────────────────────────────────────────────────┤
│  Infrastructure Layer                                                   │
│  ├── LLMClient (unified) → Adapters: Anthropic, OpenAI, Gemini, Ollama  │
│  ├── ToolRegistry → 10 tools (5 read-only, 5 write)                     │
│  ├── MCPManager → MCPConnection (STDIO/HTTP transports)                 │
│  ├── EmbeddingsService → OpenAI/Ollama providers                        │
│  └── DatabaseFactory → SQLite (WAL) + Exposed ORM                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### Execution System

- **UnifiedStepExecutor**: Single execution loop with pluggable strategies
- **SimpleAutoStrategy**: Sequential execution, no reflection
- **OrchestrationStrategy**: Reflection loop with LLM analysis, dynamic plan modification (add/skip/modify/retry steps), user questions
- **Safeguards**: MAX_CONSECUTIVE_FAILURES=3, MAX_PLAN_MODIFICATIONS=10, MAX_ADD_STEPS_PER_CYCLE=3

### RAG Pipeline

```
Project Files → FileAnalyzerService (Kotlin/Java/Python/TypeScript analyzers)
             → SemanticChunkingStrategy (full-file, class, function chunks)
             → EmbeddingsService (OpenAI text-embedding-3-small / Ollama nomic-embed-text)
             → SQLite storage (index_files, index_chunks, embeddings)
             → RagSearchService (cosine similarity, threshold 0.5, topK results)
```

### Context System

- **13 providers**: @file, @folder, @open, @recent, @codebase (RAG), @docs, @url, @grep, @commit, @diff, @terminal, @problems, @clipboard
- **Provider types**: NORMAL (passive), QUERY (user input), SUBMENU (interactive picker)
- **MCP Integration**: 16 built-in presets, STDIO/HTTP transports, tool/context exposure modes

### Security Layers

1. **PathSandbox**: Restricts file operations to project root (known symlink escape - P0)
2. **FileLimits**: Size limits (2MB), excluded directories (24), excluded extensions (30+)
3. **CommandDenylist**: 50+ blocked patterns (rm -rf, sudo, curl|sh)
4. **ToolPermissions**: Per-mode (PLAN=read-only, AGENT=read-write), per-task overrides
5. **No-Egress Mode**: Blocks cloud providers, allows only local (Ollama, LM Studio)
6. **Secret Redaction**: API keys masked in all logs

### LLM Providers

| Provider | Models | Special Features |
|----------|--------|------------------|
| Anthropic | Claude 3.7/3.5, Opus 4.1 | Thinking mode, top-level system |
| OpenAI | GPT-5.x, GPT-4o, O1/O3 | Responses API, reasoning models |
| Gemini | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| Ollama | Local models | Free, JSON format mode |
| OpenRouter | All providers | Dynamic pricing, unified gateway |
| LM Studio | Local models | OpenAI-compatible, free |

> **Full technical documentation:** See [`docs/overview.md`](docs/overview.md) for comprehensive architecture details.

---

## Building and installing the plugin

### Build ZIP package
```powershell
cd agent\plugin
.\gradlew.bat clean buildPlugin
```
```bash
cd agent/plugin
./gradlew clean buildPlugin
```
Output: `agent/plugin/build/distributions/refio-<version>.zip`

### Install in IntelliJ (Install from Disk)
1. `File > Settings > Plugins > (gear) > Install Plugin from Disk...`
2. Pick the ZIP from `build/distributions`
3. Restart IDE
4. Open `View > Tool Windows > Refio`

### Run sandbox IDE (development)
```bash
cd agent/plugin
./gradlew runIde          # Windows: .\\gradlew.bat runIde
```
Launches a separate IntelliJ instance with the plugin pre-installed.

---

## License

MIT License. See `LICENSE`.
