# Architecture Reference

> For contributors and advanced users. See [README.md](../README.md) for product overview.

> **Recent notable changes** (see [CHANGELOG.md](../CHANGELOG.md) `[Unreleased]`):
> - "Slash commands" were renamed to "Prompts" (code: `SlashCommand` → `SlashPrompt`, DB enum `SLASH_COMMAND` → `SLASH_PROMPT` with V3 migration). The `/name` invocation syntax is unchanged; UI tab is now **Settings → Prompts**.
> - Terminal command security uses a single `CommandRule` regex engine (`ALLOW` / `BLOCK` / `ASK`); the legacy `CommandWhitelist` / `CommandDenylist` classes have been removed.
> - Models settings tab no longer triggers remote provider fetches on open — only the **Refresh** button does. Local providers (Ollama, LM Studio) use a 3 s `listModels` timeout.

---

## Project Overview

Refio is a **local-first AI assistant** packaged as an IntelliJ plugin written entirely in Kotlin. It supports three user-facing execution modes: **Chat** (conversation with project context), **Plan** (read-only analysis with tool usage), and **Agent** (full read/write access with automatic execution), plus a **Subagent run profile** on the same execution loop.

### Design Philosophy

Unlike tools that send entire codebases to LLMs, Refio uses **selective context injection** through RAG (Retrieval-Augmented Generation) and comprehensive code analysis. This approach:

- Reduces API costs by 50-70%
- Provides faster responses
- Works with smaller context windows (local models)
- Maintains privacy (no-egress mode available)

---

## Key Features

| Feature | Description                                                                          |
|---------|--------------------------------------------------------------------------------------|
| **Three UI Modes + Subagent Profile** | Chat, Plan, Agent + Subagent profile on unified turn loop                     |
| **Enhanced AgentTurnLoop** | AgentTurnLoop execution + ADR-0028/ADR-0029 profile support, caching, parallel tools |
| **14 Context Providers** | @file, @folder, @codebase, @grep, @url, @docs, @commit, and more                     |
| **RAG Indexing** | Automatic project indexing with language-specific analyzers                          |
| **15 Registered Tools** | 7 read-only + 8 write tools (AGENT enables full toolset by default)                 |
| **8 LLM Adapters** | Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio, Custom OpenAI, Z.AI        |
| **MCP Protocol** | Full Model Context Protocol with 17 built-in presets                                 |
| **21 Built-in Subagents** | Specialized agents for code review, security, architecture, docs, business analysis, and coordination |
| **Performance Optimizations** | Token estimation, retry logic, working memory integration                            |
| **No-Egress Mode** | Block cloud providers, use only local models                                         |
| **Native UI** | IntelliJ Swing components, no webview                                                |

---

## How Refio Works

### Architecture Overview

```
+-------------------------------------------------------------------------+
|  UI Layer (IntelliJ Swing)                                              |
|  RefioToolWindowFactory -> ChatView -> PromptInputPanel                 |
+-------------------------------------------------------------------------+
|  Service Layer (Project-Scoped)                                         |
|  SessionManager (facade)                                                |
|  +-- SessionStateManager (11 StateFlows for reactive UI)                |
|  +-- SessionLifecycleService (session create/switch/load)               |
|  +-- MessageDispatcher (CHAT vs PLAN/AGENT routing)                     |
|  +-- SubtaskTracker (subtask lifecycle management)                      |
+-------------------------------------------------------------------------+
|  Execution Layer                                                        |
|  +-- CHAT mode -> WorkflowOrchestrator -> ChatExecutor                  |
|  +-- PLAN/AGENT mode -> AgentTurnLoop (self-directing tool loop)        |
+-------------------------------------------------------------------------+
|  Core Layer (In-Process API)                                            |
|  CoreApiRouter (composition root, ~962 LOC)                             |
|  +-- 12 Domain Routers (Chat, Task, Subtask, Agent, Config,             |
|  +--   Prompts, Tool, RAG, ApiLogs, MultiAgent, ProjectContext, Subagent)|
|  +-- ContextService (~2140 LOC) + 6 extracted sub-services              |
+-------------------------------------------------------------------------+
|  Infrastructure Layer                                                   |
|  +-- LLMClient (unified) -> 8 provider adapters                        |
|  +-- ToolRegistry -> 15 registered tools with security layers            |
|  +-- MCPManager -> MCP server lifecycle (STDIO/HTTP)                    |
|  +-- EmbeddingsService -> Ollama/OpenAI embeddings                      |
|  +-- DatabaseFactory -> SQLite (WAL) + Exposed ORM                      |
+-------------------------------------------------------------------------+
```

### Message Flow

```
User Input
    |
SessionManager.sendMessage()
    |
+-------------------------------------------------------------------------+
| CHAT mode                         | PLAN/AGENT mode                     |
|                                   |                                     |
| WorkflowOrchestrator              | AgentTurnLoop.runTurn()             |
|     |                             |     |                               |
| IntentRouter                      | 1. Save user message                |
|     |                             | 2. Build prompt + tool descriptions |
| ChatExecutor                      | 3. Call LLM                         |
|     |                             | 4. If tool calls:                   |
| ChatService.chat()                |    - Create subtasks                |
|     |                             |    - Execute tools                  |
| LLMClient.complete()              |    - Summarize results              |
|     |                             |    - Continue loop                  |
| Response                          | 5. Save final response              |
+-------------------------------------------------------------------------+
    |
MessageDispatcher.loadMessages()
    |
UI Update via StateFlow
```

---

## Execution Modes

### Chat Mode

Direct LLM conversation with full project context. No tool execution.

- Uses ChatExecutor -> ChatService
- Streaming responses
- Context includes: project analysis, conversation history, RAG fragments, @mentions

### Plan Mode

Read-only analysis with tool usage for codebase exploration.

- Uses AgentTurnLoop with READ_ONLY tools only
- Model self-directs tool usage (read_file, grep_search, file_search, etc.)
- Each tool call creates a tracked subtask
- Ideal for: code review, architecture analysis, bug investigation

### Agent Mode

Full read/write access with automatic execution.

- Uses AgentTurnLoop with ALL tools
- Model can: create files, edit code, run multi-file edits
- Subtask lifecycle: PENDING -> RUNNING -> SUCCESS/FAILED
- File snapshots before write operations (rollback support)
- Safety: loop detection, error rate monitoring, max iterations

### Subagent Run Profile

Specialized execution profile on the same `AgentTurnLoop`.

- Triggered explicitly by `!subagent-name <prompt>` in PLAN/AGENT
- Uses profile overrides: custom system prompt, tool filters, model/provider overrides
- Supports nested delegation via `invoke_subagent` tool
- `invoke_subagent` permission is managed in Tools Settings (display name: `subagent`)
- Smart default: `subagent` is enabled in PLAN and AGENT
- Includes recursion/depth safeguards (`maxSubagentDepth = 3`)

### Performance Optimizations (ADR-0028)

AgentTurnLoop includes production-grade enhancements:

| Optimization | Benefit |
|--------------|---------|
| **Auto-Compaction** | Prevents context overflow by summarizing old messages at 80-85% capacity |
| **Prompt Caching** | Caches static prompts for 5min, reducing construction overhead |
| **Parallel Tools** | Executes READ_ONLY tools concurrently (~2-3x faster for multi-tool calls) |
| **Retry Logic** | Exponential backoff for rate limits/timeouts (1s -> 2s -> 4s) |
| **Token Estimation** | Pre-flight counting prevents unexpected API errors |
| **Working Memory** | Auto-extracts knowledge from tool results for context continuity |

**Configuration:** Mode/profile settings (PLAN: 25 iterations, AGENT: 50 iterations, SUBAGENT depth limit: 3)

---

## Context System

### Built-in Context Providers

| Provider | Type | Description |
|----------|------|-------------|
| `@file` | SUBMENU | File picker with search |
| `@folder` | SUBMENU | Directory structure browser |
| `@current` | NORMAL | Currently active editor file |
| `@recent` | SUBMENU | Recently edited files (15-file history) |
| `@open_files` | NORMAL | All open editor tabs |
| `@clipboard` | NORMAL | System clipboard content |
| `@terminal` | NORMAL | Recent terminal output (max 200 lines) |
| `@problems` | NORMAL | Compilation errors/warnings |
| `@diff` | NORMAL | Git uncommitted changes |
| `@codebase` | QUERY | Semantic search via RAG embeddings |
| `@grep` | QUERY | Regex search across project |
| `@url` | QUERY | Fetch web content (100KB max) |
| `@commit` | QUERY | Git commit details by hash |
| `@docs` | SUBMENU | Documentation search with semantic ranking |

### Provider Types

- **NORMAL**: No user input required (clipboard, current, terminal, problems, diff)
- **QUERY**: Requires search term (@codebase:auth, @grep:TODO, @url:https://...)
- **SUBMENU**: Interactive selection (@file, @folder, @recent, @docs)

### Context Building Flow

```
User Input + @mentions
    |
ContextService.buildProjectContext()
    +-- 1. Load cached project analysis (architecture, dependencies)
    +-- 2. Extract conversation history (with summarization)
    +-- 3. Build subtask summaries (completed steps)
    +-- 4. Load RAG fragments (code + docs)
    +-- 5. Load MCP resources
    +-- 6. Resolve @mentions via ContextProviderRegistry
    +-- 7. Combine into ProjectContextDTO
    |
Token budgeting (section limits, ~28K tokens default)
    |
Final prompt sent to LLM
```

---

## RAG System

### Indexing Pipeline

```
Project Files
    |
RagIndexingService.indexProject() [background, at startup]
    +-- Scan files (40+ extensions: .kt, .java, .py, .ts, .js, etc.)
    +-- Compute SHA-256 checksum (incremental detection)
    +-- Classify: NEW / MODIFIED / UNCHANGED
    |
FileAnalyzerService.analyze()
    +-- Language detection
    +-- AST-like parsing (regex-based)
    +-- Extract: classes, functions, imports, annotations
    |
ChunkingStrategy.createChunks()
    +-- SemanticChunkingStrategy (structure-aware)
    |   +-- Full-file chunks
    |   +-- Class-level chunks
    |   +-- Function-level chunks
    +-- DefaultChunkingStrategy (line-based fallback)
    |
EmbeddingsService.generateBatch()
    +-- Ollama: nomic-embed-text (768 dims)
    +-- OpenAI: text-embedding-3-small (1536 dims)
    |
SQLite Storage
    +-- IndexFilesTable (metadata + checksum)
    +-- IndexChunksTable (content + positions)
    +-- EmbeddingsTable (vector BLOB, little-endian float32)
```

### Search Pipeline

```
Query: "authentication logic"
    |
EmbeddingProvider.generateEmbedding(query)
    |
RagRepository.getEmbeddings(projectRoot)
    |
Cosine Similarity Calculation
    cos(q, e) = (q . e) / (||q|| x ||e||)
    |
Filter: similarity >= threshold (default 0.5)
    |
Sort by similarity, take topK (default 5)
    |
Optional: Hybrid search (70% semantic + 30% keyword)
    |
RagSearchResult[]
```

### Language Analyzers

| Language | Analyzer | Extracts |
|----------|----------|----------|
| Kotlin | KotlinLanguageAnalyzer | Classes, objects, functions, data classes, coroutines |
| Java | JavaLanguageAnalyzer | Classes, interfaces, methods, annotations (Spring) |
| Python | PythonLanguageAnalyzer | Classes, functions, decorators, type hints |
| TypeScript | TypeScriptLanguageAnalyzer | Classes, interfaces, functions, React components |
| HTML | HtmlLanguageAnalyzer | Structure, scripts, styles |

---

## Tools

### READ_ONLY Tools (14)

| Tool | Parameters | Description |
|------|------------|-------------|
| `read_file` | path | Read file content (2MB max) |
| `read_directory` | path, recursive, max_depth | List directory tree |
| `file_search` | pattern, path, offset, limit | Glob pattern search |
| `grep_search` | pattern, path, case_sensitive | Regex content search |
| `view_diff` | file1, file2 OR content2 | Line-by-line comparison |
| `invoke_subagent` | subagent_name, goal, context_refs? | Run nested child loop via subagent profile |
| `delegate_to_strong_model` | task, context?, allow_tools?, response_format? | Delegate complex task to a stronger model (single-shot or tool-enabled sub-agent). Only registered when `models.defaults.strong` is configured. |
| `web_search` | query, max_results? | Search the web (Brave/SerpAPI/DuckDuckGo) |
| `fetch_webpage` | url, prompt, max_content_chars? | Fetch URL, convert to Markdown, process with LLM |
| `code_intelligence` | action, symbol?, path?, language? | Find usages/definitions, list symbols, get diagnostics |
| `monitor_process` | process_id, max_lines? | Read output from background process |
| `ask_user` | question, options? | Ask the user a question and wait for response |
| `sleep` | duration_ms | Pause execution (max 30s) |
| `think` | thought | Explicit reasoning slot |

### WRITE Tools (10)

| Tool | Parameters | Description | Cost |
|------|------------|-------------|------|
| `create_new_file` | path, content | Create file with parent dirs | Free |
| `code_editing` | path, old_string, new_string, replace_all | Search-and-replace | Free |
| `multi_edit` | edits[] | Atomic multi-file edit | Free |
| `multi_line_editor` | path, edit_description | LLM identifies line ranges | ~$0.02 |
| `advance_code_editing` | path, edit_description | Full file regeneration | ~$0.06 |
| `run_terminal_command` | command | Shell execution (ASK in AGENT, CommandRule-protected) | Free |
| `http_request` | url, method, headers, body, save_to_file | HTTP requests (GET/POST/PUT/DELETE), 5 MB limit, 60s timeout | Free |
| `run_code` | language, code | Execute Python/JavaScript/Kotlin Script, 120s timeout | Free |
| `run_process_background` | command | Start command in background, return process_id | Free |
| `llm_call` | prompt, data?, file_path?, model? | Raw single-turn LLM call | ~$0.01 |

### Tool Availability by Mode

| Mode | READ_ONLY | WRITE |
|------|-----------|-------|
| CHAT | - | - |
| PLAN | Yes | - |
| AGENT | Yes | Yes |

---

`SUBAGENT` is a run profile on top of PLAN/AGENT with additional tool filtering.

## Subagents

Specialized AI assistants invoked with `!agent-name` prefix and executed on the same `AgentTurnLoop` (`runProfile=SUBAGENT`).

You can also delegate from the parent agent using the `invoke_subagent` tool (nested child loop).
This tool is shown as `subagent` in Tools Settings, but maps internally to `invoke_subagent`.
Its description is generated dynamically from currently enabled subagents (name, description, allowed tools/inherit).

### Built-in Agents (21)

| Category | Agents |
|----------|--------|
| **Quality & Security** | `code-reviewer`, `security-engineer`, `architect-reviewer` |
| **Core Development** | `frontend-developer`, `fullstack-developer`, `refactoring-specialist`, `api-designer`, `ui-designer` |
| **Documentation** | `documentation-engineer`, `api-documenter`, `technical-writer` |
| **Infrastructure** | `sre-engineer` |
| **Business & Product** | `business-analyst`, `product-manager`, `project-manager`, `legal-advisor`, `ux-researcher`, `risk-manager` |
| **Orchestration** | `multi-agent-coordinator`, `workflow-orchestrator` |
| **Research** | `research-analyst` |

Legacy invocations `!security-reviewer` and `!security-auditor` are aliased to `!security-engineer` for backward compatibility.

### Custom Agents

Create custom agents in:
- **User-level**: `~/.refio/agents/my-agent.md`
- **Project-level**: `.refio/agents/my-agent.md`

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

### Model Aliases

| Alias | Maps To |
|-------|---------|
| `inherit` | Parent conversation model |
| `default` | ConfigService DEFAULT model |
| `plan` | ConfigService PLAN model |
| `coding` | ConfigService CODING model |
| `weak` | ConfigService WEAK model |
| `sonnet` | claude-3-5-sonnet-20241022 |
| `opus` | claude-3-opus-20240229 |
| `haiku` | claude-3-haiku-20240307 |

---

## MCP Protocol

Full Model Context Protocol implementation with STDIO and HTTP/SSE transports.

### 17 Built-in Presets

| Category | Servers |
|----------|---------|
| **VCS** | GitHub, GitLab |
| **Databases** | PostgreSQL, SQLite, Database (HTTP Streamable) |
| **Search** | Brave Search, Exa |
| **Docs** | Context7 |
| **DevOps** | Sentry, AWS |
| **Storage** | Google Drive, Filesystem |
| **Development** | Puppeteer, Sequential Thinking, Custom API |
| **Collaboration** | Slack |
| **Memory** | Memory |

### Configuration

```yaml
# .refio/config.yaml
mcp:
  servers:
    - id: "github"
      type: "STDIO"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-github"]
      accessMode: "READ"
      enabled: true
      env:
        - name: "GITHUB_TOKEN"
          value: "${GITHUB_TOKEN}"
          isSecret: true
```

---

## LLM Providers

| Provider | Models | Features |
|----------|--------|----------|
| **Ollama** | Local models (qwen3.5, llama, etc.) | Free, JSON mode, local privacy |
| **OpenAI** | GPT-4o, GPT-4o-mini, o1, o3, GPT-5 | Responses API, reasoning models |
| **Anthropic** | Claude 3.5/3.7, Opus 4.1 | Thinking mode, top-level system |
| **Gemini** | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| **OpenRouter** | All providers | Unified gateway, dynamic pricing |
| **LM Studio** | Local models | OpenAI-compatible, free |
| **Custom OpenAI** | Any OpenAI-compatible API | Configurable base URL, API key |
| **Z.AI** | Z.AI models | Rate-limited, OpenAI-compatible |

### Pricing (per 1M tokens, USD)

| Model | Input | Output |
|-------|-------|--------|
| gpt-4o-mini | $0.15 | $0.60 |
| gpt-4o | $2.50 | $10.00 |
| claude-3-5-sonnet | $3.00 | $15.00 |
| claude-opus-4-1 | $15.00 | $75.00 |
| Ollama (local) | $0.00 | $0.00 |

---

## Tech Stack

- **Language/Platform**: Kotlin 1.9.25, JVM target 17, IntelliJ Platform 2024.1.7 (IC)
- **UI**: Native IntelliJ Swing components (no webview)
- **Core/Transport**: CoreApiRouter (composition root) + 12 domain routers; Ktor server for HTTP transport
- **LLM/HTTP**: Ktor Client 2.3.7 with 8 provider adapters
- **Database**: SQLite (WAL) via Exposed 0.46.0 + sqlite-jdbc 3.44.1.0
- **Serialization**: Gson 2.10.1, kotlinx-serialization-json 1.6.2, KAML 0.55.0
- **Markdown**: commonmark 0.21.0
- **Logging**: kotlin-logging + logback 1.4.14
- **Build**: Gradle IntelliJ Plugin 1.17.4

---

## Getting Started (Development)

### Prerequisites

1. **JDK 17** and **IntelliJ IDEA 2024.x**
2. **Ollama** from [https://ollama.com/](https://ollama.com/)
3. Required models:
   ```bash
   ollama pull nomic-embed-text:latest  # Embeddings
   ollama pull qwen3.5:9b        # Coding
   ```

### Build & Run

```bash
# Clone repository
git clone https://github.com/shadoq/refio.git && cd refio

# Run in sandbox IDE
./gradlew runIde          # Linux/macOS
.\gradlew.bat runIde      # Windows

# Or build plugin ZIP
./gradlew buildPlugin     # Output: build/distributions/refio-<version>.zip
```

### Prompt TPL Variables

`PromptInputPanel` supports inline TPL placeholders in prompt text and slash-command templates.

Available placeholders:

- `{{MODEL_ID}}` - selected model in filesystem-safe `provider_model` format
- `{{MODEL_RAW}}` - selected model in raw `provider/model` format
- `{{PROVIDER}}` - selected provider id (filesystem-safe)
- `{{MODE}}` - current mode (`CHAT`, `PLAN`, `AGENT`)
- `{{EXECUTION_MODE}}` - current execution mode (`AUTO`, `INTERACTIVE`)
- `{{TIMESTAMP}}` - current local timestamp (`yyyyMMdd_HHmmss`)
- `{{DATE}}` - current local date (`yyyy-MM-dd`)
- `{{TIME}}` - current local time (`HHmmss`)
- `{{PROJECT_NAME}}` - IntelliJ project name (filesystem-safe)
- `{{SESSION_ID}}` - active session id (filesystem-safe)

---

## Configuration

### File Locations

| File | Location | Purpose |
|------|----------|---------|
| User config | `~/.refio/config.yaml` | Personal settings, API keys |
| Project config | `<project>/.refio/config.yaml` | Project-specific prompts, MCP servers |
| Database | `refio_poc.db` (project root) | Session data, messages, RAG index |
| Ignore patterns | `<project>/.aiignore` | RAG indexing exclusions |

### Quick Start Config

```yaml
# ~/.refio/config.yaml
general:
  formatMarkdown: true
  streamingEnabled: true

providers:
  ollama:
    endpoint: "http://localhost:11434"
  anthropic:
    apiKey: "sk-ant-..."

models:
  defaults:
    chat: "ollama/qwen3.5:9b"
    coding: "ollama/qwen3.5:9b"
    embedding: "ollama/nomic-embed-text"
    strong: "anthropic/claude-3-5-sonnet-20241022"  # optional, enables delegate_to_strong_model tool

  visibility:
    "ollama/qwen3.5:9b": true
    "anthropic/claude-3-5-sonnet-20241022": true
```

See [`docs/config.md`](config.md) for full configuration reference.

---

## Security

### Defense Layers

| Layer | Protection |
|-------|------------|
| **PathSandbox** | All file ops restricted to project root |
| **FileLimits** | Size limits (2MB), excluded directories (24), extensions (34) |
| **CommandRule System** | Regex-based rules with `ALLOW` / `BLOCK` / `ASK` actions; unified replacement for the removed `CommandWhitelist` / `CommandDenylist`. Defaults in `CommandRuleDefaults`, limits in `CommandLimits`. |
| **ToolPermissions** | 3-level access control: ON/ASK/OFF per mode (PLAN=read-only, AGENT=read-write) |
| **No-Egress Mode** | Blocks cloud providers, allows only Ollama/LM Studio |
| **Secret Redaction** | API keys masked in all logs |

### Known Issues (P0)

| Issue | Description | Mitigation |
|-------|-------------|------------|
| Symlink Escape | PathSandbox can be bypassed via symlinks | Detection + logging in place |
| Command Rule Coverage | Some workflows may require adding project-specific safe commands | Configure in Tools Settings → Terminal Command Rules (regex-based `ALLOW` / `BLOCK` / `ASK`) |

---

## Repository Structure

```
core/src/main/kotlin/pl/jclab/refio/
+-- core/                     # IDE-independent core logic
|   +-- api/                  # CoreApiRouter (composition root) + 12 domain routers
|   +-- api/routers/          # Domain routers (Chat, Task, Agent, Config, RAG, etc.)
|   +-- context/              # Context providers + MCP
|   +-- db/                   # Database layer (Exposed ORM)
|   +-- llm/                  # LLM integration (8 adapters)
|   +-- services/             # Core services (~35 services)
|   +-- services/context/     # ContextService sub-components (6 extracted classes)
|   +-- services/turn/        # AgentTurnLoop sub-components (13 files)
|   +-- subagents/            # Subagent system
|   +-- tools/                # Tool system (15 registered implementations)
|   +-- prompts/              # Prompt templates
+-- api/                      # Shared API models (DTOs)

intellij-plugin/src/main/kotlin/pl/jclab/refio/
+-- services/                 # Plugin services (project-scoped)
|   +-- session/              # SessionManager (6 components)
|   +-- rag/                  # Background indexing
+-- ui/                       # IntelliJ UI components
|   +-- components/           # Chat, toolbar, autocomplete
|   +-- settings/             # 12+ settings panels
+-- actions/                  # IDE actions
+-- startup/                  # Plugin startup hooks

cli/src/main/kotlin/pl/jclab/refio/cli/
+-- tui/                      # TUI application
|   +-- state/                # TuiViewModel (coordinator) + 3 sub-ViewModels
|   +-- input/                # Input handling (raw TTY / line mode)
|   +-- rendering/            # Screen rendering (ANSI, buffers)
|   +-- views/                # Tab views (Chat, Steps, RAG, Logs, etc.)
|   +-- screens/              # Overlay screens (History, Settings)
|   +-- components/           # Reusable TUI components
```

---

## Available Scripts

```bash
# Development
./gradlew runIde              # Run in sandbox IDE
./gradlew buildPlugin         # Build ZIP distribution

# Quality
./gradlew detekt              # Static analysis
./gradlew ktlintCheck         # Lint check
./gradlew ktlintFormat        # Auto-format

# Testing
./gradlew test                # Run all tests
```

---

## License

MIT License. See `LICENSE`.
