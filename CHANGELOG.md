# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- `PRIVACY.md` describing local storage, cloud-provider behavior, no-egress mode, and secret handling.
- New `security.allow_symlinks` configuration option, documented in `docs/config.md`, for explicit unsafe opt-in to symlink access in `PathSandbox`.

### Changed

- Public documentation, landing copy, Marketplace copy, and QUICKSTART are aligned with the current codebase counts: 12 tools, 14 context providers, 16 MCP presets, 21 built-in subagents, and 6 LLM providers.
- Model discovery now uses single-flight caching, and token context estimation no longer triggers provider model-list fetches from the request hot path.
- Project analysis caching is now keyed by `includeContent` and protected against duplicate concurrent analyses for the same project state.
- Assistant tool-call bubbles now preserve the assistant narrative alongside tool metadata, and simple tool bubbles use the same stacked layout.

### Fixed

- PLAN and AGENT turn execution now forward `thinking` and `noEgressEnabled` to `LLMClient.complete()`.
- Chat bubble caching no longer invalidates on `isStreaming` state changes alone, reducing end-of-stream UI flicker.
- Streaming assistant messages are now flushed in batches and always clear `isGenerating`, reducing chat UI churn and stuck generation state after failures.
- Context panel auto-refresh is now deferred while streaming/generation is active and resumes once the turn finishes, avoiding redundant refreshes and prompt churn.
- `RefioMainPanel` now unregisters property listeners during disposal to avoid listener leaks on panel recreation.
- `PathSandbox` now rejects symbolic links by default, including symlinked parent directories, with opt-in override via `security.allow_symlinks`.
- `advance_code_editing` now enforces excluded file-extension checks before invoking the LLM.

## [0.0.1.2] - 2025-03-07

### Added

- New built-in slash command `/implementation-analysis` for generating practical markdown implementation-analysis documents from a topic prompt.

### Fixed

- Agent turn loop now allows longer AGENT and PLAN runs, including deeper read-only analysis before nudging toward writes.
- Empty, blank, or meaningless structured responses such as `{}` or `""` now trigger structured-format retries instead of ending the turn prematurely.
- Local providers such as Ollama and LM Studio no longer receive forced `json_object` response format in turn execution.
- Tool-result summarization now falls back to deterministic compression when the weak model returns an empty summary.
- AGENT prompt now allows more autonomous analysis, makes `thinking` optional, and trims redundant examples while keeping the JSON contract explicit.
- Improved built-in slash command prompts

## [0.0.1.1] - 2025-03-03

### Fixed

- Terminal warning at InteliJ check


## [0.0.1] - 2025-03-02

### Initial Release

First public release of Refio - a local-first AI coding assistant for IntelliJ IDEA.

### Core Architecture

- **Full Kotlin Implementation** - Plugin + embedded core written entirely in Kotlin
- **In-Process API** - CoreApiRouter with 9 domain routers (no HTTP transport required)
- **SQLite Database** - WAL mode via Exposed ORM for data persistence
- **Native IntelliJ UI** - Swing components, no webview dependencies

### Execution Modes

- **CHAT Mode** - Direct LLM conversation with project context via ChatExecutor
- **PLAN Mode** - Read-only analysis with AgentTurnLoop (Codex CLI-style turn-based execution)
- **AGENT Mode** - Full read/write access with AgentTurnLoop, automatic subtask tracking

### AgentTurnLoop (Primary Execution Engine)

- Turn-based execution implementing Codex CLI pattern
- Self-directing model with automatic tool invocation
- Subtask lifecycle tracking (PENDING → RUNNING → SUCCESS/FAILED)
- Tool result summarization for context optimization
- Loop detection (prevents consecutive repeats)
- Error rate monitoring (>70% failure rate triggers abort)
- Max 25 LLM iterations per turn (safety limit)

### Context System (18 Built-in Providers)

**Phase 1 - File Context:**
- `@file` - File picker with search (SUBMENU)
- `@folder` - Directory structure browsing (SUBMENU)
- `@current` - Currently active editor file
- `@recent` - Recently edited files (15-file history)
- `@open_files` - All open editor tabs

**Phase 2 - IDE Integration:**
- `@clipboard` - System clipboard content
- `@terminal` - Recent terminal output (max 200 lines)
- `@problems` - Compilation errors/warnings (max 20 files)
- `@diff` - Git uncommitted changes

**Phase 3 - Advanced Search:**
- `@codebase` - Semantic search via RAG/embeddings
- `@grep` - Regex search across project (max 50 results)
- `@url` - Fetch and parse web content (100KB max)
- `@commit` - Git commit details by hash/message
- `@docs` - Documentation search with semantic ranking

### RAG System (Retrieval-Augmented Generation)

- **Automatic Project Indexing** - Background indexing at IDE startup
- **Incremental Updates** - SHA-256 checksum-based change detection
- **Language Analyzers** - Kotlin, Java, Python, TypeScript, HTML
- **Semantic Chunking** - Structure-aware code chunking (classes, functions)
- **Embeddings Support:**
  - Ollama: nomic-embed-text (768 dims), mxbai-embed-large (1024 dims)
  - OpenAI: text-embedding-3-small (1536 dims), text-embedding-3-large (3072 dims)
- **Cosine Similarity Search** - Threshold filtering (default 0.5)
- **Hybrid Search** - Combined semantic (70%) + keyword (30%) scoring
- **40+ Supported File Types** - .kt, .java, .py, .ts, .tsx, .js, .md, etc.

### Tools System (10 Tools)

**READ_ONLY Tools (5):**
- `read_file` - Read file content (2MB max)
- `read_directory` - List directory tree (max depth 10)
- `file_search` - Glob pattern search with pagination (100 results max)
- `grep_search` - Regex content search (500 results max)
- `view_diff` - Line-by-line file comparison

**WRITE Tools (5):**
- `create_new_file` - Create file with parent directories
- `code_editing` - Search-and-replace editing
- `multi_edit` - Atomic multi-file edit
- `multi_line_editor` - LLM-assisted line range editing (~$0.02/call)
- `advance_code_editing` - Full file regeneration via LLM (~$0.06/call)
- `run_terminal_command` - Shell execution (DISABLED by default)

### Security Layers

- **PathSandbox** - Project root restriction with path validation
- **FileLimits** - Size limits (2MB), excluded directories (24), excluded extensions (34)
- **CommandDenylist** - 58 dangerous command patterns blocked
- **ToolPermissions** - Per-mode tool access control
- **No-Egress Mode** - Blocks cloud providers (Ollama/LM Studio only)
- **Secret Redaction** - API keys masked in all logs

### LLM Provider Adapters (6)

| Provider | Models | Features |
|----------|--------|----------|
| Ollama | Local models | Free, JSON mode |
| OpenAI | GPT-4o, GPT-4o-mini, o1, o3, GPT-5 | Responses API, reasoning models |
| Anthropic | Claude 3.5/3.7, Opus 4.1 | Thinking mode, top-level system |
| Gemini | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| OpenRouter | All providers | Unified gateway, dynamic pricing |
| LM Studio | Local models | OpenAI-compatible |

### MCP Protocol (Model Context Protocol)

- **Full Protocol Support** - JSON-RPC 2.0 implementation
- **Transport Types:**
  - STDIO - Subprocess with stdin/stdout
  - HTTP/SSE - HTTP with Server-Sent Events
- **16 Built-in Presets:**
  - VCS: GitHub, GitLab
  - Databases: PostgreSQL, SQLite
  - Search: Brave Search, Exa
  - Docs: Context7
  - DevOps: Sentry, AWS
  - Development: Puppeteer, Sequential Thinking

### Subagents System

- **Built-in Agents:**
  - `security-reviewer` - Security audits, OWASP vulnerabilities
  - `code-reviewer` - Code quality, patterns, bugs
- **Custom Agents** - User (~/.refio/agents/) and Project (.refio/agents/)
- **YAML Frontmatter** - Claude Code compatible format
- **Tool Filtering** - Per-agent tool permissions
- **Model Resolution** - inherit, sonnet, opus, haiku, default, plan, coding, weak

### Session Management

- **SessionManager** - Main facade with 6 components:
  - SessionStateManager (11 StateFlows for reactive UI)
  - SessionLifecycleService (create/switch/load sessions)
  - MessageDispatcher (mode-specific routing)
  - SubtaskTracker (subtask CRUD operations)
  - PromptStateTracker (pending context/input state)
  - StatusBarIntegration (UI status bar)

### Configuration System

- **4-Level Hierarchy:**
  1. Database (Settings UI) - Highest priority
  2. Project config (.refio/config.yaml)
  3. User config (~/.refio/config.yaml)
  4. Built-in defaults
- **Key Sections:**
  - General settings (markdown, streaming)
  - Provider endpoints and API keys
  - Model defaults per operation
  - System limits (timeouts, context size)
  - RAG configuration
  - Tool permissions
  - Custom prompts and rules
  - MCP server definitions

### UI Components

- **RefioToolWindowFactory** - Main tool window
- **ChatView** - Conversation interface
- **PromptInputPanel** - Input with @ autocomplete
- **12+ Settings Panels** - Comprehensive configuration UI
- **Context Panel** - Color-coded sections, LLM prompt viewer
- **Advanced View** - Context preview, RAG components, logs, debug panel

### Database Schema

**Core Tables:**
- TasksTable - Sessions (id, project_id, mode, status, ui_state_json)
- SubtasksTable - Execution steps (task_id, order_index, kind, status)
- ChatMessagesTable - Conversation (task_id, role, content, metadata_json)
- SnapshotsTable - File snapshots for rollback
- ApiLogsTable - LLM API call logs with costs

**RAG Tables:**
- IndexFilesTable - File metadata + checksums
- IndexChunksTable - Code chunks with positions
- EmbeddingsTable - Vector BLOB (little-endian float32)

**Configuration Tables:**
- ConfigTable - Key-value settings
- PromptsTable - System prompts
- MCPServersTable - MCP server configs
