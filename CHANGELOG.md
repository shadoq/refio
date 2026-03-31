# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- Multi-agent architecture with parallel agent orchestration, inter-agent event bus, dependency resolution, and YAML-based task definitions.
- Multi-agent API: launch, query, and list multi-agent sessions with per-agent metrics.
- Database tables for multi-agent sessions, instances, and event persistence.
- Standalone CLI with full TUI (Mordant + JLine3) replacing Compose Desktop GUI.
- Split-pane terminal layout: Chat (55%) + active tab (45%) with 7 tabs (F1–F7) and Settings (F8).
- Tab bar with inline status (mode, model, streaming, cost, tokens).
- Settings screen with 11 sub-tabs covering all configuration areas.
- `@context` autocomplete popup and session history browser in TUI.
- ANSI color system with per-agent colors and role-based message styling.
- Dual-mode TUI input: raw TTY with F-keys for terminals, line-based fallback for pipes/IDEs.
- `http_request` tool for external API calls (GET/POST/PUT/DELETE) with `save_to_file` for large responses.
- `run_code` tool for executing Python, JavaScript, and Kotlin Script snippets inline.
- TUI file viewer overlay, extended API Logs/Context/Debug/Logs/Steps views.
- 7 standalone context providers for CLI mode (file, folder, git diff, git commit, grep, codebase, docs) — 9/14 providers now available outside IDE.
- Platform-agnostic `ProjectHandle` interface replacing direct IntelliJ `Project` dependency in core.
- Per-agent metrics tracking, mutex-based file locking for write tools, configurable Ollama request gating.
- Expanded test coverage: multi-agent, standalone providers, core services, integration tests.
- Prompt snapshot and turn-state tracking exposed from core to the IntelliJ plugin, including context inclusion/drop traces, token usage, and tool-batch summaries.
- IntelliJ context panel now shows a prompt-context trace table and a dedicated context inspector view for prompt debugging.
- Modular prompt-building extension points with dedicated prompt section providers and extracted context pruning services.

### Fixed

- `Ctrl+D` no longer crashes the prompt input panel.
- Regex error in `ContextService.compactDirectoryList()`.
- JLine warnings suppressed on dumb terminals.
- TUI streaming no longer produces duplicate messages.
- TUI input and cursor rendering issues resolved.
- `PathSandbox` symlink resolution on macOS.
- `resetAllSettingsToDefaults()` now actually resets config entries.
- EventBus wiring for multi-agent UI visualization in CLI.
- Cross-platform fixes for terminal command tests.
- Help action now opens the project GitHub page.
- Model visibility defaults now initialize only once and preserve existing user-configured dropdown visibility.
- MCP settings now distinguish disabled, stale, and auth-required server states and retain last connection/error metadata for diagnostics.

### Removed

- Compose Desktop GUI and all related dependencies, replaced by TUI.

### Changed

- Gradle multi-module structure: `:core`, `:intellij-plugin`, `:cli`.
- Core module no longer has IntelliJ compile-time dependency.
- Clikt upgraded to 5.0.2 for Mordant 3.x compatibility.
- `ContextService` was split into formatter/pruner responsibilities and now records structured context-budget decisions for each prompt section.
- Agent turn execution now publishes observable phase changes for prompt building, model calls, tool execution, and finalization.

## [0.0.1.4] - 2025-03-22

### Added

- Project instructions support via `.refio/agent.md`, `AGENTS.md`, and conditional rules from `.refio/rules/*.md`, now included in context and visible in the Context Panel.
- Richer project analysis for CSS, TypeScript, HTML, C++, and dependency ecosystems.
- Typed configuration access through `ConfigKeys` and `ConfigService.getTyped()` / `setTyped()`.
- HTTP API server exposing `CoreApiRouter` over JSON and SSE.
- Expanded automated test coverage for config, RAG, workflow, subagent, and analysis areas.

### Changed

- Context building and project analysis now expose more useful structured data while suppressing empty sections.
- Subagent execution was aligned with the newer turn-loop and JSON planning flow.
- Configuration and model resolution were simplified across DB, YAML, and fallback defaults.

### Improved

- Terminal command policies were expanded and relaxed for normal development workflows while keeping destructive actions guarded.
- Command validation was refined to better distinguish project-safe operations from unsafe system-level ones.
- Command timeouts and output limits were increased for longer builds and test runs.

### Removed

- Unused context and DTO code.
- Legacy multi-step subagent execution path replaced by the current turn-loop flow.

### Fixed

- Nested JSON model responses are now unwrapped more reliably before display.
- Chat tool bubbles remain stable after streaming completes.
- Subagent responses now show clearer attribution in the UI.

## [0.0.1.3] - 2025-03-18

### Added

- Provider settings export/import now correctly includes `providers.custom_openai` and `providers.zai` in YAML, and the Providers settings UI now exposes `CustomOpenAI` configuration fields alongside the existing Z.AI card.
- `PRIVACY.md` describing local storage, cloud-provider behavior, no-egress mode, and secret handling.
- New `security.allow_symlinks` configuration option, documented in `docs/config.md`, for explicit unsafe opt-in to symlink access in `PathSandbox`.
- Support for OpenAI-compatible providers via dedicated `providers.custom_openai` configuration, including API key, base URL, and default model selection.
- Dedicated Z.AI provider integration with separate adapter, config keys, and Settings UI card instead of sharing generic provider settings.
- Expanded project/user YAML support for `rag` settings and default embedding model selection.

### Changed

- Public documentation, landing copy, Marketplace copy, and QUICKSTART are aligned with the current codebase counts: 12 tools, 14 context providers, 18 MCP presets, 21 built-in subagents, and 6 LLM providers.
- Model discovery now uses single-flight caching, and token context estimation no longer triggers provider model-list fetches from the request hot path.
- Project analysis caching is now keyed by `includeContent` and protected against duplicate concurrent analyses for the same project state.
- Assistant tool-call bubbles now preserve the assistant narrative alongside tool metadata, and simple tool bubbles use the same stacked layout.
- Agent turn loop message reload now keeps assistant narration in a separate bubble from tool-call bubbles, so the chat layout stays stable after streaming completes.
- RAG indexing and retrieval now support configurable memory controls, semantic chunking, paginated embedding scans, result caching for `@codebase`, dynamic context-budget redistribution, and working-memory eviction tuning.
- Embedding generation now supports batched requests for OpenAI and Ollama providers, reducing per-chunk overhead during RAG indexing.
- Provider/model metadata was consolidated to improve compatibility for OpenAI-compatible backends and dedicated Z.AI model discovery.

### Fixed

- Chat bubbles now use an explicit row-based layout for regular responses, fixing incorrect horizontal positioning and narrow/offset content rendering in `ChatView`.
- PLAN and AGENT turn execution now forward `thinking` and `noEgressEnabled` to `LLMClient.complete()`.
- Chat bubble caching no longer invalidates on `isStreaming` state changes alone, reducing end-of-stream UI flicker.
- Streaming assistant messages are now flushed in batches and always clear `isGenerating`, reducing chat UI churn and stuck generation state after failures.
- Context panel auto-refresh is now deferred while streaming/generation is active and resumes once the turn finishes, avoiding redundant refreshes and prompt churn.
- `RefioMainPanel` now unregisters property listeners during disposal to avoid listener leaks on panel recreation.
- `PathSandbox` now rejects symbolic links by default, including symlinked parent directories, with opt-in override via `security.allow_symlinks`.
- `advance_code_editing` now enforces excluded file-extension checks before invoking the LLM.
- Streaming assistant placeholders now suppress raw `{"actions":...}` JSON and show only extracted partial `response` / `content` text while the turn is still in progress.
- Config reads during startup no longer fail before `Database.connect()` completes; repository lookups now safely skip pre-init access.
- Provider failures are now normalized into typed Refio LLM errors for timeout, authentication, rate-limit, and generic upstream failures.
- Ollama requests are gated per endpoint to reduce contention between concurrent chat, model-discovery, and embedding operations.

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
