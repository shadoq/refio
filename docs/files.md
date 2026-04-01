# Package & File Reference

## `core/api/` — Core API Contracts

- **Router.kt** — Base router interface defining `initialize()` and `shutdown()` contract for all domain routers.
- **UIAdapter.kt** — Platform-agnostic UI interface for notifications, status, questions, progress without IntelliJ dependency.
- **StreamTypes.kt** — Unified streaming callback architecture (RFC 0032): `StreamCallback` typedef and `StreamChunk` data class for UI updates during LLM generation.
- **ChatModels.kt** — Chat request/response models with `ChatRequest`, `ChatResponse`, `ChatCosts`, `LLMParams` for conversations with optional context references.
- **PlanModels.kt** — Plan response models including `PlanResponse`, `PlanSpecStepResponse`, plan statistics, and execution summary.
- **StreamingModels.kt** — Streaming agent/plan models with `ToolCallSpec` and `PlanDecisionInfo` for tool selection transparency.
- **PromptsModels.kt** — DTOs for prompt CRUD: `PromptDto`, save/update requests for rules, commands, and system prompts.
- **ModelOperation.kt** — Enum for model selection slots (DEFAULT, PLAN, CODING, WEAK, EMBEDDING); maps `TaskMode` to operation.

## `core/api/routers/` — Domain Routers

- **TaskRouter.kt** — CRUD for tasks, status updates, queries; maps Task entity to `TaskResponse` DTO.
- **ChatRouter.kt** — Chat conversations with streaming support (RFC 0032 callbacks); handles summarization, session titles, message history.
- **AgentRouter.kt** — Agent execution: subtask planning, step execution, auto-mode; integrates with `AgentExecutor` and approval workflows.
- **ToolRouter.kt** — Tool registry and permissions management; provides tool catalog and per-mode (plan/agent) availability.
- **PromptsRouter.kt** — System prompts, rules, slash commands CRUD with variable substitution and reset to defaults.
- **ConfigRouter.kt** — Model configuration and visibility; lists models, manages visibility, provider testing.
- **RagRouter.kt** — RAG operations: project indexing, semantic chunking, documentation indexing, similarity search.
- **ApiLogsRouter.kt** — API log management: recent logs, filtering by provider/model/source, statistics.
- **SubtaskRouter.kt** — Subtask CRUD, approval status updates, ordering; maps `Subtask` → `SubtaskResponse`.
- **MultiAgentRouter.kt** — Multi-agent session lifecycle: launch (YAML parse → DB session → parallel agent execution), query, list sessions.
- **ProjectContextRouter.kt** — Project context for UI visualization: builds context, generates runtime/auxiliary prompt previews, maps to response DTOs (~500 LOC extracted from CoreApiRouter).

## `core/config/` — Configuration System

- **ConfigKeys.kt** — Centralized typed configuration registry (70+ `ConfigKey` definitions) covering general settings, limits, models, RAG, providers, UI, tools, security, agent flow. Each key has parser, default, serializer, and optional YAML accessor.
- **ConfigYaml.kt** — Data classes for YAML deserialization with hierarchical config (user + project); includes sanitization for malformed YAML.
- **HierarchicalConfigLoader.kt** — Merges config from multiple sources: built-in defaults > `~/.refio/config.yaml` > `.refio/config.yaml`. 30-second cache.

## `core/db/` — Database Layer

### Tables
- **TasksTable.kt** — Core task tracking with modes (CHAT/PLAN/AGENT), status, execution modes, approval workflow, token/cost aggregation.
- **SubtasksTable.kt** — Steps within task execution plans with approval workflow, LLM metrics, snapshot rollback; FK to TasksTable.
- **ChatMessagesTable.kt** — Conversation history with tool call support, thinking (reasoning), metadata, summarization.
- **SnapshotsTable.kt** — File content snapshots with zlib compression for rollback; stores hash, compressed content, compression ratio.
- **ApiLogsTable.kt** — Complete LLM API call history with secret redaction; stores provider, model, endpoint, request/response, tokens, costs.
- **PromptsTable.kt** — System prompts, rules, slash commands with enable/disable toggle; comprehensive prompt type enum.
- **ConfigTable.kt** — Unified configuration with scope-based precedence (APP/PROJECT/TASK); composite primary key.
- **MCPServersTable.kt** — MCP server configs stored as JSON payloads with enable/disable toggle and per-project scoping.
- **RagTables.kt** — RAG system: `IndexFilesTable`, `IndexChunksTable`, `EmbeddingsTable` (vector BLOB), `IndexingProgressTable`, `DocumentationSourcesTable`.
- **ProjectAnalysisReportsTable.kt** — Cached project analysis reports with fingerprint checksums for invalidation.
- **AgentTables.kt** — Multi-agent: `AgentSessionsTable`, `AgentInstancesTable`, `AgentEventsTable` (event sourcing with JSON payloads).

### Migration System
- **SchemaMigrationsTable.kt** — Tracks executed migration versions.
- **Migration.kt** — Abstract migration interface with version number (strategy pattern).
- **MigrationRunner.kt** — Executes pending migrations in order (orchestrator pattern).
- **SeedTestDataMigration.kt** — v1 migration seeding demo conversations for UI development.

### DatabaseFactory
- **DatabaseFactory.kt** — Initializes SQLite with WAL mode, pragma settings, connection pooling, transaction retry logic; creates all tables (singleton factory with eager init).

### Repositories
- **TaskRepository.kt** — Full CRUD for tasks with filtering, status updates, metric increments, cursor pagination.
- **SubtaskRepository.kt** — Subtask lifecycle: atomic shift-and-insert for plan reordering, approval workflow, metrics.
- **ChatMessageRepository.kt** — Conversation history: append-only with selective deletion from pivot point for truncation.
- **SnapshotRepository.kt** — Compressed snapshot storage/retrieval with decompression; hash-based deduplication.
- **ApiLogRepository.kt** — API call logging with global statistics, filtering, secret redaction on insert.
- **PromptsRepository.kt** — Prompt management: system prompts, rules, commands with type-based queries and enable/disable.
- **ConfigRepository.kt** — Scope-aware configuration with precedence lookup (TASK > PROJECT > APP); handles DB-not-ready gracefully.
- **MCPServerRepository.kt** — MCP config management with JSON serialization and per-project/global scoping.
- **ProjectAnalysisReportRepository.kt** — Upsert for cached reports with checksum-based invalidation.
- **DocumentationRepository.kt** — RAG doc source management with lifecycle (PENDING > INDEXED > FAILED), URL normalization, batch ops.
- **RagRepositories.kt** — Three specialized repos: `IndexFileRepository`, `IndexChunkRepository`, `EmbeddingRepository`, plus `IndexingProgressRepository`.
- **RagRepository.kt** — Unified RAG facade over specialized repos with batch optimization (`getFilesBatch`, `getChunksBatch`).
- **AgentSessionRepository.kt** — Multi-agent session creation and status tracking with project isolation.
- **AgentInstanceRepository.kt** — Individual agent management within sessions with status/result updates and cost tracking.
- **AgentEventSqlRepository.kt** — Event sourcing implementation with Gson serialization and polymorphic deserialization; supports replay.

## `core/errors/` — Error Handling

- **RefioError.kt** — Sealed class error hierarchy: `LLMTimeout`, `LLMAuthentication`, `LLMRateLimit`, `LLMError`, `ProviderNotConfigured`.
- **LLMErrorMapper.kt** — Maps HTTP status codes and throwables to `RefioError` types; detects auth (401/403), rate limits (429), timeouts.

## `core/http/` — HTTP Server

- **HttpServer.kt** — Ktor Netty HTTP wrapper for `CoreApiRouter` with SSE streaming; exposes in-process API over HTTP/JSON.

## `core/llm/` — LLM Abstraction Layer

- **Base.kt** — Core data structures and abstract `BaseLLMAdapter` base class for all LLM adapters; message types, response types, universal parameter normalization.
- **LLMClient.kt** — Unified entry point for all LLM requests; routes to appropriate adapter by provider name (facade pattern); handles token estimation, streaming, error handling.
- **ModelRegistry.kt** — Dynamic model discovery with parallel provider fetching, 5-minute cache, mutex-protected single-flight requests.
- **ModelDefinitions.kt** — Central registry (~3800 lines) of all supported models with pricing, context limits, capabilities, parameter mappings. Single source of truth.
- **SupportedModels.kt** — Whitelist of supported models per provider; filters dynamically discovered models against tested/approved sets.
- **Pricing.kt** — Cost calculation: queries `ModelDefinitions` for pricing, computes cost = (tokens / 1M) * price.
- **TokenEstimator.kt** — Token counting heuristic (4 chars = 1 token); validates requests against model context limits with fallback chain.
- **JsonExtractor.kt** — Universal JSON extraction from LLM responses (plain JSON, markdown blocks, wrapped content); chain of responsibility with multiple fallback strategies.
- **HttpClientConfig.kt** — HTTP client timeout/retry/rate config; loads from `ConfigService` with fallback defaults.
- **NoEgressViolationException.kt** — Exception thrown when no-egress mode blocks cloud LLM usage; only local providers (Ollama, LM Studio) allowed.

## `core/llm/adapters/` — LLM Provider Adapters

- **AnthropicAdapter.kt** — Claude API: system messages as top-level param (not in messages array), thinking/extended thinking mode, `tool_use` content blocks, SSE streaming.
- **OpenAIAdapter.kt** — GPT models: Chat Completions and Responses API formats; special handling for reasoning models (o1, o3) that don't support streaming/system roles/temperature.
- **OllamaAdapter.kt** — Local Ollama: JSON mode, thinking mode, retry logic for model loading delays (`done_reason="load"`), GPU memory management via `keep_alive`, NDJSON streaming.
- **GeminiAdapter.kt** — Google Gemini: converts to "contents/parts" structure, `system_instruction` param, SSE streaming, function call extraction from parts.
- **OpenRouterAdapter.kt** — Unified API for 100+ models; OpenAI-compatible format; thinking mode for Claude models via OpenRouter.
- **CustomOpenAIAdapter.kt** — Base class for OpenAI-compatible APIs (custom endpoints, Z.AI, LMStudio); rate limiting (mutex-based cooldown for Z.AI), tool call accumulation for streaming.
- **LMStudioAdapter.kt** — LM Studio local server: OpenAI-compatible wrapper; configurable base URL, optional API key; logs unsupported thinking mode.
- **ZAIAdapter.kt** — Thin wrapper around `CustomOpenAIAdapter` for Z.AI provider; inherits rate limiting and streaming.
- **ToolCallContentNormalizer.kt** — Normalizes tool call formats from all providers into unified canonical JSON; includes `OpenAiStreamingToolCallAccumulator` for streaming.

## `core/context/` — Context Provider System

- **BaseContextProvider.kt** — Abstract base class for all context providers with optional submenu support (template method pattern).
- **ContextProviderRegistry.kt** — Singleton registry managing all context providers; lazy-initializes via pluggable factory for platform independence (IDE vs CLI).

### `core/context/providers/` — IDE-specific Providers

- **CurrentFileContextProvider.kt** — Returns active editor file from IntelliJ `FileEditorManager` with sandbox validation.
- **OpenFilesContextProvider.kt** — Returns all open editor tabs with full content.
- **RecentFilesContextProvider.kt** — Returns 15 most recently edited files from `EditorHistoryManager`; submenu support.
- **FileContextProvider.kt** — File picker with search via `FilenameIndex`; sandbox validation.
- **FolderContextProvider.kt** — Directory tree structure with file sizes; 3-level depth, folder search.
- **ClipboardContextProvider.kt** — Reads system clipboard content via Java AWT.
- **UrlContextProvider.kt** — Fetches web content via Ktor HttpClient; 100KB limit.
- **CodebaseContextProvider.kt** — Semantic codebase search via RAG/embeddings; strategy pattern for embedding providers.
- **DocsContextProvider.kt** — Searches indexed documentation via RAG; conditional inline vs RAG based on content size.
- **GrepSearchContextProvider.kt** — Regex/text search via IntelliJ `PsiSearchHelper`; 50 match limit, respects `.aiignore`.
- **GitDiffContextProvider.kt** — Uncommitted changes from IntelliJ `ChangeListManager`; limited to 20 files.
- **GitCommitContextProvider.kt** — Git commit details via ProcessBuilder CLI; supports hash, branch, tag, keyword search.
- **ProblemsContextProvider.kt** — Compilation errors/warnings from `WolfTheProblemSolver`; limited to 20 files.
- **TerminalContextProvider.kt** — Extracts last 200 lines of terminal history using reflection to access JediTermWidget internals.

### `core/context/providers/standalone/` — CLI-compatible Providers

- **StandaloneCodebaseContextProvider.kt** — RAG codebase search without IntelliJ; uses `workspacePath` directly.
- **StandaloneDocsContextProvider.kt** — Documentation search without IntelliJ; simplified RAG initialization.
- **StandaloneFileContextProvider.kt** — File discovery via `Files.walk()` instead of `FilenameIndex`; regex filtering, binary detection.
- **StandaloneFolderContextProvider.kt** — Directory traversal via `Files.walk()`; 5-level depth, hardcoded ignore list.
- **StandaloneGitCommitContextProvider.kt** — Git commit lookup via ProcessBuilder without IDE dependencies.
- **StandaloneGitDiffContextProvider.kt** — Git staged/unstaged/untracked status via CLI; structured diff summary.
- **StandaloneGrepSearchContextProvider.kt** — Regex search via Java `Pattern`; falls back to literal on compile error; binary detection.

### `core/context/mcp/` — Model Context Protocol

- **MCPProtocol.kt** — JSON-RPC 2.0 request/response data classes and MCP method constants (initialize, resources/list, tools/list, tools/call).
- **MCPModels.kt** — Config data classes: `MCPServerConfig`, auth (bearer/OAuth), transport (stdio/HTTP), workflow config.
- **MCPServerPresets.kt** — 15+ built-in MCP server presets by category (Documentation, VCS, Database, Search, Filesystem, Communication, Cloud).
- **MCPManager.kt** — Singleton managing MCP server connections per project; loads configs from DB, registers tools and context providers dynamically.
- **MCPConnection.kt** — Connection lifecycle and communication: connect, initialize, refresh resources/tools, call tools; async with request/response tracking.
- **MCPHttpTransport.kt** — Ktor-based HTTP/SSE transport; bearer auth, custom headers, OAuth, env var resolution.
- **MCPStdioTransport.kt** — ProcessBuilder-based stdio transport; process spawning, env setup, non-blocking I/O with separate reader/stderr jobs.
- **MCPContextProvider.kt** — Dynamic context provider wrapping MCP servers; supports resource-based and tool-based exposure with workflow execution.
- **MCPToolWrapper.kt** — Adapts MCP tool definitions as internal `Tool` objects for `ToolRegistry`.
- **MCPToolWorkflowExecutor.kt** — Multi-step workflow execution with input/output mapping and JSON path extraction for variable propagation.
- **MCPTestRunner.kt** — Tests MCP server connections by sending initialize request; transport-agnostic with env/header redaction.

## `core/tools/` — Tool System

### Base Architecture
- **Tool.kt** — Core `Tool` interface with name, description, `ToolMode` (READ_ONLY/WRITE), `ToolCategory` (DATA_PRODUCING/FILE_MODIFYING/EXECUTION); `ToolResult` data class for standardized responses.
- **ToolFactory.kt** — Factory creating and registering tools with dependency injection; separates read-only from write tools.
- **ToolRegistry.kt** — Thread-safe `ConcurrentHashMap` registry; lookup by name, filtering by mode, permission-based availability.

### Security
- **PathSandbox.kt** — Restricts all file ops to project root; resolves symlinks via `toRealPath()` for escape attack prevention; defense-in-depth with multiple checkpoints.
- **FileLimits.kt** — File operation constraints: 2MB max size, 1000 dir listing limit, 10 search depth; blacklists of excluded directories (24) and extensions (34).
- **CommandWhitelist.kt** — Two-mode command validation (WHITELIST_ONLY / WHITELIST_PLUS_DENY); parses commands into program/subcommand/args with shell operator support.
- **CommandWhitelistConfig.kt** — Config data classes for command whitelist: `AllowedCommand` with blocked flags/subcommands/patterns, max args, confirmation requirement.
- **CommandWhitelistDefaults.kt** — ~780 lines with 100+ whitelisted programs across ecosystems (build, package managers, VCS, testing, databases, cloud CLIs).
- **CommandDenylist.kt** — Blocks destructive commands: filesystem destruction, privilege escalation, download-and-execute patterns, fork bombs.
- **FileLockManager.kt** — Coroutine-safe mutual exclusion via `ConcurrentHashMap<String, Mutex>` keyed by normalized paths; `withFileLock(path) { block }`.
- **FileOperations.kt** — Safe file ops with sandbox and snapshot support; SHA-256 hashing for snapshots; `normalizePath()` utility.

### Read-Only Tools
- **ReadFileTool.kt** — Reads file contents with security validation, 2MB size limit, path normalization; returns size/line count metadata.
- **ReadDirectoryTool.kt** — Lists directory contents with optional recursive traversal (max depth 3); formatted output with type/size/path.
- **FileSearchTool.kt** — Finds files by glob pattern (converted to regex); depth limit 10, result limit 100, offset/limit pagination.
- **GrepSearchTool.kt** — Regex text search across files with case sensitivity, glob filtering, 2MB exclusion; formatted as `file:lineNumber: content`.
- **ViewDiffTool.kt** — Compares two files or file vs content using line-by-line diff; unified diff format with add/remove/unchanged counts.

### Write Tools
- **CreateNewFileTool.kt** — Creates files with content validation, size limits, file lock; warns on existing file; auto-creates parent dirs.
- **CodeEditingTool.kt** — Search-and-replace editing; validates old_string exists and is unique (unless `replace_all=true`); generates unified diff.
- **MultiEditTool.kt** — Atomic multi-file search-and-replace; validates all edits before applying any; per-edit error messages.
- **MultiLineEditorTool.kt** — LLM-assisted editor for 3-10 code locations (~$0.02 cost); parses JSON edits with `EditChange`, validates bounds/overlaps, applies in reverse order; LCS diff generation; streaming support.
- **AdvanceCodeEditingTool.kt** — Full file regeneration via LLM (~$0.06 cost); extracts code from markdown fences; LCS diff; temperature 0.2; streaming support.
- **RunTerminalCommandTool.kt** — Shell execution with whitelist validation; 120s timeout, 200KB output limit; async I/O to prevent deadlocks; cross-platform shell selection.

### Subagent Integration
- **InvokeSubagentTool.kt** — Enables agent-to-subagent delegation; validates availability, detects recursion; builds `TurnRequest` with overrides for system prompt, tools, depth.

## `core/services/` — Core Services

- **AgentExecutor.kt** — Orchestrates step-by-step execution: planning → execution → summarization with subtask lifecycle management.
- **AgentTurnLoop.kt** — Self-directing tool loop for PLAN/AGENT modes (~988 LOC); delegates to `turn/` sub-components for LLM calls, prompt building, tool execution, response processing, guardrails.
- **TurnLoopConfig.kt** — Configuration for AgentTurnLoop with factory methods for PLAN (15 iterations) and AGENT (25 iterations) presets.
- **ChatService.kt** — Chat interactions with auto-optimization (conversation summarization on token threshold); builds project context via ContextService.
- **PlanningService.kt** — Creates execution plans from LLM JSON; validates tools per mode, respects permissions, stores tool_args as suggestions.
- **StepPlanner.kt** — Generates execution plans for subtasks with dynamic parameters based on runtime state; loads context from previous results.
- **StepSummarizer.kt** — LLM-based step summaries with fallback to simple formatting; tracks tool calls, formats with emoji indicators.
- **ContextService.kt** — Central context orchestrator (~2140 LOC): delegates to 6 sub-services for RAG loading, MCP resources, context resolution, project summarization, conversation building, task extraction; budget-aware compression.
- **ConversationCompactor.kt** — Compacts conversation history at 80-85% capacity using WEAK model; keeps last N messages raw, summarizes older ones.
- **ConversationSummaryService.kt** — LLM-based conversation summaries; tracks summary metadata (count, indices, timestamps) to avoid re-summarizing.
- **ConfigService.kt** — YAML config management with scope-based precedence (GLOBAL/APP/TASK/USER) (~2268 LOC); in-memory cache, database storage; all deprecated getters removed, callers use `getTyped(ConfigKeys.XXX)`.
- **PromptsService.kt** — Prompt templates with `{{variable}}` substitution for CHAT/PLAN/AGENT modes; manages user-defined rules and commands.
- **ToolExecutor.kt** — Sequential tool execution with validation, error handling, streaming for code generation tools; snapshot creation.
- **ParallelToolExecutor.kt** — Parallel execution for READ_ONLY tools, sequential for WRITE; maintains order, creates snapshots, tracks parallelism stats.
- **ToolPermissionsService.kt** — Per-mode tool permissions (PLAN=read-only, AGENT=all) with smart defaults; task-level overrides.
- **ToolResultSummarizer.kt** — Summarizes tool outputs using WEAK model; short outputs (<500 chars) skip; deterministic compression fallback.
- **ToolResultData.kt** — DTO for tool result with summarization info (content, isSummarized, rawOutput, metadata).
- **SnapshotService.kt** — File versioning and rollback with compression, SHA-256 hashing, snapshot grouping by subtask; keeps N most recent.
- **TaskVerifier.kt** — Optional LLM-based task completion verification; strategy pattern with `NoopTaskVerifier` and `LlmTaskVerifier`.
- **TokenEstimator.kt** — Estimates token count (3.5 chars/token heuristic) with provider-specific multipliers; safe token limits per provider/model.
- **PromptCache.kt** — Caffeine cache for static prompt components (5-minute TTL matching Anthropic cache lifetime); invalidation on config changes.
- **ProjectAnalyzerService.kt** — Analyzes project structure/technologies with in-memory caching (10-min TTL); invalidates on file modification; mutex-protected.
- **RagIndexingService.kt** — RAG indexing: scans project files, detects changes via checksums, creates semantic chunks; multi-language support.
- **RagSearchService.kt** — Semantic search with cosine similarity, BM25 scoring, content type filtering, hybrid search, top-K ranking.
- **RagEmbeddingService.kt** — Generates/stores embeddings with batch processing, error recovery; multiple providers (OpenAI, Ollama); vector serialization.
- **EmbeddingProvider.kt** — Interface and implementations (`OpenAIEmbeddingProvider`, `OllamaEmbeddingProvider`) for vector embeddings; batch processing, circuit breaker integration.
- **EmbeddingCircuitBreaker.kt** — Circuit breaker (CLOSED/OPEN/HALF_OPEN) for embedding providers; prevents UI freezing from repeated failures.
- **LLMRetryHandler.kt** — Exponential backoff retry for LLM API calls; does NOT retry cancellations, auth errors, or invalid requests.
- **OllamaRequestGate.kt** — Semaphore-based rate limiting for Ollama (default 1 concurrent request per endpoint).
- **DocumentFetcher.kt** — HTML fetcher with optional JS rendering via HtmlUnit; configurable timeouts, best-effort error handling.
- **DocumentationIndexingService.kt** — Web crawling and local file indexing; depth-limited, same-domain following, robots.txt respect, Flow-based progress.

## `core/services/turn/` — AgentTurnLoop Sub-Components

- **TurnPromptBuilder.kt** — Builds system and user prompts for each turn; assembles context, tools, working memory.
- **TurnToolExecutor.kt** — Executes tool calls from LLM responses; parallel execution for read-only tools.
- **ToolCallParser.kt** — Parses tool call JSON from LLM responses; handles multiple formats and streaming accumulation.
- **TurnLLMCaller.kt** — Calls LLM with retry and streaming support; handles provider-specific quirks.
- **TurnResponseProcessor.kt** — Processes LLM response into tool calls, text, and thinking segments.
- **TurnGuardrails.kt** — Safety checks: iteration limits, infinite loop detection, token budget enforcement.
- **TurnNudgeBuilder.kt** — Builds nudge prompts when LLM gets stuck or produces empty responses.
- **TurnFinalizer.kt** — Finalizes turn: saves messages, updates metrics, handles completion.
- **TurnJsonUtils.kt** — JSON parsing utilities for tool call arguments.
- **TurnSubagentValidator.kt** — Validates subagent invocation requests.
- **TurnEventListener.kt** — Event listener interface for turn lifecycle events.
- **TurnLoopConfigAliases.kt** — Type aliases for turn loop configuration.
- **TurnPromptAliases.kt** — Type aliases for prompt building.

## `core/services/context/` — Context Building Helpers & Extracted Sub-Services

**Extracted from ContextService (Phase 2 refactor):**
- **RagContextLoader.kt** — RAG fragment loading: `loadRagFragments()`, skip logic, keyword extraction; holds volatile RAG service/model/provider refs.
- **McpContextLoader.kt** — MCP resource loading with TTL cache (`ConcurrentHashMap`); tool output formatting.
- **ContextReferenceResolver.kt** — Resolves @mentions to content: provider dispatch, legacy type handlers, deduplication, file analysis summaries.
- **ProjectContextSummarizer.kt** — Compact project summaries: architecture, key components, patterns, navigation map.
- **ConversationContextBuilder.kt** — Conversation history filtering: meaningful messages, summary slicing, tool envelope detection.
- **TaskContextExtractor.kt** — Task/subtask data extraction: executed steps, user requirements, result parsing, file change summaries.

**Infrastructure:**
- **ContextBudget.kt** — Token budget allocation per context section; `forContextSize()` factory with configurable ratios and overrides.
- **ContextSection.kt** — Enum of context sections (PROJECT_OVERVIEW, CURRENT_TASK, RAG_FRAGMENTS, CONVERSATION, RECENT_WORK, etc.).
- **ContextTokenEstimator.kt** — Token estimation heuristic (`text.length / 4`); used for budget enforcement.
- **WorkingMemoryService.kt** — Per-task working memory: key-value facts with importance scoring, LRU eviction, iteration tracking.
- **ProjectInstructionsLoader.kt** — Loads project instructions from `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` with glob-based activation.
- **ToolResultCompression.kt** — Multi-level compression for tool results; adapts to budget constraints.
- **CompressionLevel.kt** — Enum for compression levels.

## `core/services/analysis/` — Code Analysis

- **LanguageAnalyzer.kt** — Interface for language analyzers; abstract `ExtensionLanguageAnalyzer` with parsing utilities; fallback `GenericLanguageAnalyzer`.
- **FileAnalysisModels.kt** — Data classes: `FileAnalysis`, `CodeElements`, `ClassElement`, `FunctionElement`, `FieldElement`, `ImportElement`, `FileContext`.
- **FileAnalyzerService.kt** — Orchestrates per-file analysis dispatching to language analyzers; caching with TTL, file hashing, batch analysis, async RAG indexing.
- **EmbeddingsService.kt** — Vector embedding generation with mutex-based thread safety; SHA-256 key caching (30-min TTL); provider/model config resolution.
- **JavaLanguageAnalyzer.kt** — Regex-based Java parsing: classes, interfaces, enums, methods, imports; infers purposes (Controller, Service, Repository); detects Spring patterns.
- **KotlinLanguageAnalyzer.kt** — Kotlin parsing: data/sealed classes, objects, functions, extension functions, suspend functions; detects coroutine patterns (Flow, launch, async).
- **PythonLanguageAnalyzer.kt** — Indentation-based Python parsing: classes, functions, decorators, docstrings, type hints; detects frameworks (FastAPI, Flask, Django, Pydantic).
- **TypeScriptLanguageAnalyzer.kt** — TypeScript/JavaScript parsing: classes, interfaces, type aliases, arrow functions; detects React components/hooks, JSDoc.
- **CppLanguageAnalyzer.kt** — C++ parsing: classes, structs, enums, namespaces, templates; detects STL, Smart Pointers, Threading, Boost, Qt.
- **CssLanguageAnalyzer.kt** — CSS/SCSS/SASS/LESS parsing: class selectors, IDs, keyframes, media queries, CSS variables; detects Tailwind, Grid, Flexbox.
- **HtmlLanguageAnalyzer.kt** — HTML parsing: titles, canvas, buttons, scripts; delegates JS analysis to `TypeScriptLanguageAnalyzer`.

### `core/services/analysis/project/` — Project-Level Analysis

- **ProjectAnalysisModels.kt** — Data classes for AST-based project analysis: statistics, code structure, dependency graphs, architectural layers, quality metrics.
- **FrameworkAnalyzer.kt** — Detects frameworks by file paths and build files; JVM (Spring, Ktor, Micronaut), frontend (React, Next.js, Vue), Python (Django, FastAPI), Node.js (Express, NestJS); confidence scoring.
- **RichProjectAnalysisEngine.kt** — Comprehensive project analyzer orchestrating full AST-based analysis with caching, fingerprinting, concurrency (mutexes); builds multi-faceted reports.

## `core/agents/` — Multi-Agent System

- **AgentSpec.kt** — Data classes for agent specifications (`AgentSpec` with name, task, dependencies, mode, model) and execution results (`AgentResult` with metrics).
- **MultiAgentRunner.kt** — Orchestrates parallel agent execution with DFS cycle detection in dependency graph; `supervisorScope` coroutines, `MutableStateFlow` for dependency wait.
- **MultiAgentTaskParser.kt** — Parses YAML multi-agent task definitions into `AgentSpec` objects via Kaml deserialization.
- **AgentEvent.kt** — Sealed interface with 12 event subtypes: lifecycle (Started/Completed/Failed), data (Request/Response), coordination (Artifact/Spawn), approval, progress.
- **AgentEventBus.kt** — Central event bus via `MutableSharedFlow` (200 replay buffer, DROP_OLDEST); optional persistence via repository; filtered subscriptions.
- **AgentEventHandler.kt** — Per-agent event handler for DataRequest/Response and Approval with `CompletableDeferred` suspension and timeout auto-approval.

## `core/subagents/` — Subagent System

- **SubagentDefinition.kt** — Data class for complete subagent definition: name, description, system prompt, tool whitelist/blacklist, model aliases, skills, priority, context profile.
- **SubagentParser.kt** — Parses markdown files with YAML frontmatter into `SubagentDefinition`; regex extraction, SnakeYAML parsing, Claude Code tool name mapping.
- **SubagentRegistry.kt** — Discovery, caching (60s TTL), CRUD across three scopes (Built-in, User, Project); higher-priority scopes override; keyword-based search.
- **SubagentToolFilter.kt** — Three-tier tool filtering: mode-based, whitelist/blacklist strategy, security override; normalizes between Claude Code and Refio tool names.
- **SubagentRouter.kt** — API router for subagent ops: listing, invocation, CRUD, `!subagent-name prompt` command parsing.
- **SubagentInvocation.kt** — Tracks subagent invocation lifecycle: status (RUNNING/SUCCESS/FAILED/CANCELLED), timing, result; immutable state transitions.

## `core/workflow/` — Workflow Engine

- **WorkflowOrchestrator.kt** — Main orchestrator: loops through intent determination → execution → result handling; switches on intent type (Chat/Plan/ExecuteStep/Subagent/AnswerQuestion).
- **IntentRouter.kt** — Determines next workflow intent with priority routing: subagent (explicit) → pending subtasks (auto-continuation) → mode-based fallback.
- **WorkflowEventListener.kt** — Callback interface for UI updates during workflow: decision/execution phases, streaming, tool starts, completion, errors (observer pattern).
- **ChatExecutor.kt** — Adapter wrapping `ChatService` for workflow intent execution.
- **PlanExecutor.kt** — Adapter wrapping `PlanningService` for plan creation.
- **SubagentExecutor.kt** — Adapter wrapping `SubagentRouter` for subagent invocation with model inheritance.
- **StepExecutor.kt** — Adapter wrapping `AgentRouter` for subtask step execution.
- **WorkflowRequest.kt** — Request wrapper holding `UIState` snapshot and optional project analysis context.
- **WorkflowIntent.kt** — Sealed interface: Chat, Plan, ExecuteStep, Subagent, AnswerQuestion intents.
- **IntentResult.kt** — Sealed interface mapping 1:1 to intent types; wraps service responses.
- **UIState.kt** — Snapshot of UI state for workflow turn: task context, user input, context refs, model override, feature flags.

## `core/models/` — Domain Models

- **ChatModels.kt** — LLM parameters, chat request/response with context references, token costs, tool call support.
- **PromptsModels.kt** — Prompt DTOs, save/update requests for rules and commands, response wrappers.
- **ToolPermissionsModels.kt** — Tool permission DTOs with per-tool plan/agent mode settings (ASK/ON/OFF).
- **PlanningModels.kt** — Planning request/response with subtask details, cost breakdown, model/provider tracking.
- **StreamingModels.kt** — Multi-level streaming: chat (delta + usage), planning (phases), step planning (tool specs), step execution, summary generation.
- **CodeAnalysisDTO.kt** — Per-language code metrics (JavaScript, Python, HTML, CSS, TypeScript, Kotlin, Java).
- **ExecutionMetadataDTO.kt** — Execution context: timestamp, workspace path, agent/interactive mode flags.
- **MetaDataDTO.kt** — Project metadata: name, description, file count, complexity, main language.
- **StructureDTO.kt** — Project structure: total files, type distribution, top-level items, directory depth.
- **WorkspaceDTO.kt** — Workspace paths and context: absolute path, task/project IDs, project name.
- **SummaryDTO.kt** — Project summary: type, complexity, architecture notes, key capabilities, entry points.
- **DependenciesDTO.kt** — Multi-language dependencies plus package managers and config files.
- **ExecutedStepDTO.kt** — Historical executed step: subtask ID, file, tool, params, result, summary.
- **HelperDTOs.kt** — Supporting DTOs: `CurrentTaskDTO`, `SubtaskDTO`, `ConversationMessageDTO`, `CodeFragmentDTO`, `ResolvedContextDTO`.
- **ProjectContextDTO.kt** — Master aggregate DTO: metadata, summary, structure, dependencies, analysis, task, subtasks, conversation, steps, RAG, context, MCP.

## `core/logging/` — Logging

- **DualLogger.kt** — Dual logging to SLF4J and optional `LogSink` (UI panel); automatic sensitive data redaction; API request/response/error logging to database.
- **LogSink.kt** — Platform-agnostic log sink interface (debug/info/warn/error); allows IntelliJ or CLI implementations.
- **LogSinkRegistry.kt** — Global `LogSink` singleton registry; graceful degradation to SLF4J-only when not registered.

## `core/prompts/` — Prompt Engine

- **PromptTemplate.kt** — Template engine for `{{variable}}` substitution with extraction, validation, rendering; throws on missing variables.

## `core/security/` — Security

- **SecureLogger.kt** — Redacts API keys, tokens, passwords using regex patterns (OpenAI/Anthropic/Google); `redactMap`, `redactValue`, `redactAndTruncate`.

## `core/utils/` — Utilities

- **GsonInstance.kt** — Shared Gson singletons (`gson` + `prettyGson`) with HTML escaping disabled.
- **ProjectIdGenerator.kt** — Deterministic SHA-256 project IDs from absolute paths.
- **AiIgnoreMatcher.kt** — `.aiignore` file parser implementing gitignore-style glob/negation/anchoring pattern matching.

## `core/project/` — Project Abstraction

- **ProjectHandle.kt** — Platform-agnostic project interface: id, name, rootPath, platformProject.
- **StandaloneProjectHandle.kt** — `ProjectHandle` for CLI mode; deterministic ID via `ProjectIdGenerator`.

## `api/` — Shared API Models

- **CoreApiClient.kt** — Thin wrapper delegating to domain routers via `CoreApiRouter`: tasks (taskRouter), chat (chatRouter), subtasks (subtaskRouter), models/config (configRouter), tools (toolRouter), prompts (promptsRouter), subagents (subagentRouter).
- **models/ExecutionMode.kt** — Enum: INTERACTIVE (step-by-step approval) and AUTO (autonomous execution).
- **models/UserContextMetadata.kt** — Context references and summary alongside user messages with JSON serialization.
- **models/CodeSnippet.kt** — Editor-selected code with filepath, line numbers, language; converts to `ContextReference`.
- **models/Session.kt** — Unified model: `TaskMode`, `TaskStatus` enums; `Session`, `Message` (with metrics/thinking/tool calls), `Subtask`, `ContextReference` (with builder methods), `ContextType`.
- **models/SlashCommand.kt** — 40+ built-in commands by category (UNDERSTANDING, IMPROVEMENT, TESTING, FIXING, DOCUMENTATION, TRANSLATION, etc.) with prompt templates.
- **models/TaskPlanModels.kt** — `SubtaskDto` mirroring Python agent schema; approval/execution status, parameters, timing, error tracking, metrics.

## `services/` — IntelliJ Plugin Services

- **CoreConnectionManager.kt** — App-level service managing embedded Kotlin core connection, per-project routers, database init, config loading.
- **SessionManager.kt** — Project-level service orchestrating session operations, workflow execution, message handling, component coordination (lazy loading).
- **SessionLifecycleService.kt** — Session lifecycle: creation, loading, switching, mode changes, model selection, execution mode toggling, state persistence.
- **SessionStateManager.kt** — Reactive state holder: StateFlows for all session state (messages, subtasks, plans, model, toggles); thread-safe mutations.
- **MessageDispatcher.kt** — Loads/transforms DB messages into UI-ready `Message` objects; tool call parsing, content normalization, enrichment.
- **ExecutionMonitor.kt** — Execution lifecycle: streaming, step execution, approval messages, real-time UI listener integration.
- **StepExecutionService.kt** — Project-level execution state and progress tracking for PLAN/AGENT modes.
- **SubtaskTracker.kt** — Subtask lifecycle: loading, approval, skipping, reordering, deletion; auto-execution in INTERACTIVE mode.
- **PromptStateTracker.kt** — Tracks pending context references, user input, context section token info; delegates to `SessionStateManager`.
- **IntelliJUIAdapter.kt** — IntelliJ-specific `UIAdapter` bridging core UI operations to IDE services.
- **ToolCallContentSanitizer.kt** — Removes tool call protocol blocks; extracts text payloads preserving plan JSON.
- **ToolMessageDisplayResolver.kt** — Resolves tool message display format (with optional raw output for summarized messages).
- **IncrementalToolCallStreamFilter.kt** — Filters tool call streams incrementally with boundary detection.
- **PluginLogger.kt** — App-level logging: StateFlow of log entries (max 1000) with HTTP/API call logging.
- **PluginLoggerSink.kt** — IntelliJ-specific `LogSink` bridging core logging to `PluginLogger` for UI visibility.
- **DualLogger.kt** — Backward-compatibility re-export wrapper delegating to core's `DualLogger`.
- **NotificationService.kt** — IDE notification balloons (info, warning, error) with RAG unavailability handling.
- **RagProgressService.kt** — RAG indexing/embedding progress via StateFlows for reactive UI updates.
- **BackgroundIndexingTask.kt** — Background RAG indexing with progress reporting, cancellation, notifications.
- **StatusBarIntegration.kt** — StatusBar reference holder for status updates from services.
- **IntelliJProjectHandle.kt** — `ProjectHandle` wrapper for IntelliJ `Project` with concrete IDE accessor.

## `actions/` — IDE Actions

- **ReindexRagAction.kt** — Manual RAG reindexing trigger with user confirmation; queues `BackgroundIndexingTask`.
- **AddCodeToCurrentSessionAction.kt** — Ctrl+Shift+J: adds selected code snippet to current session.
- **AddCodeToNewSessionAction.kt** — Ctrl+J: creates new session and adds selected code snippet.
- **ToolWindowAction.kt** — Base class for tool window actions; includes concrete implementations: `NewSessionToolWindowAction`, `ShowHistoryToolWindowAction`, `ShowSettingsToolWindowAction`, `ShowHelpToolWindowAction`.

## `startup/` — Plugin Startup

- **RagIndexingStartup.kt** — `ProjectActivity` hook: queues automatic RAG indexing on project load if enabled.

## `ui/` — IntelliJ Swing UI

### Main Structure
- **RefioToolWindowFactory.kt** — Factory creating tool window with title bar actions (New Session, History, Settings, Help).
- **RefioMainPanel.kt** — Main panel with tabbed pane (Chat, Steps, Context, RAG, Logs, Debug, API Logs), prompt input, status bar.

### Chat Components (`ui/components/chat/`)
- **ChatView.kt** — Main chat view: scrollable message list with `GridBagLayout` for vertical stacking (weightx=1.0, gridwidth=REMAINDER); `CachedMessagePanel` caches rendered bubbles; Flow-based state management.
- **PromptInputPanel.kt** — Input panel with `GridBagLayout`: row 0 for input container (snippets + context + editor), row 1 for controls (mode/model/context buttons). `GradientBorderPanel` wrapper with custom gradient rendering.
- **CodeBlockPanel.kt** — Code display with `BorderLayout`: header (NORTH) with `FlowLayout` for filename+buttons, `JBScrollPane` (CENTER), footer (SOUTH) for expand/collapse; dynamic height via `updatePanelSize()`.
- **EditableUserBubble.kt** — Read/edit mode toggle with `BorderLayout`; edit mode replaces content via `removeAll()` + `add()` + `revalidate()/repaint()`.
- **MetricsView.kt** — Token/cost/failed step chips with `GridBagLayout`.
- **PlanApprovalDialog.kt** — Multi-step plan approval dialog for PLAN/AGENT modes.
- **ChangesDialog.kt** — File changes preview dialog.
- **ContentSegmentParser.kt** — Parses message content into renderable segments.
- **FilePathDetector.kt** — Extracts file paths from text.
- **FileNavigationService.kt** — Navigates to files mentioned in chat.
- **ChatConversationExportUtil.kt** — Exports conversations to various formats.

### Chat Bubble System (`ui/components/chat/bubble/`) — Swing Layout Details

The bubble system uses a multi-layout composition pattern. **This is historically problematic — layout issues are common here.**

- **BaseBubbleRenderer.kt** — Base class using `GridBagLayout` for outer containers (weightx=1.0, fill=HORIZONTAL) and `BoxLayout.Y_AXIS` for mixed content panels. Critical workarounds: all components need `alignmentX = Component.LEFT_ALIGNMENT` in BoxLayout.Y_AXIS containers; `maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)` required for expansion; `Box.createVerticalStrut()` for consistent spacing.
- **AssistantBubbleRenderer.kt** — Routes message types to specialized creators; uses `GridBagLayout` with row counters (`var row = 0; gridy = row++`) for tool bubbles, `BoxLayout.Y_AXIS` for question/approval containers, `FlowLayout.LEFT` for header/button rows.
- **UserBubbleRenderer.kt** — `BoxLayout.Y_AXIS` for outer container and inner message block; uses `isOpaque=false` throughout.
- **ToolBubbleRenderer.kt** — Two parallel methods (regular/code result) both using `GridBagLayout` with identical row-stacking `addRow()` pattern.
- **OtherBubbleRenderer.kt** — Delegates to `createUniversalBubble` (GridBagLayout).
- **ChatMessageBubbleRouter.kt** — Pure routing logic dispatching to appropriate renderer.
- **BubbleComponentFactory.kt** — Core rendering engine: `FlowLayout(LEFT, 8, 4)` for headers, `BoxLayout.Y_AXIS` for content stacking via `FlatMessageBlock`, `BorderLayout` for markdown/thinking panels. Dynamic expand/collapse via `container.revalidate()/repaint()`. Context badge uses `BoxLayout.Y_AXIS` for header + details.
- **FlatMessageBlock.kt** — Custom `JBPanel` with compound border (1px matte + padding 4,8,4,8); no layout manager set (defaults to FlowLayout) — children set their own.
- **MarkdownRenderingService.kt** — Markdown parsing + HTML rendering; `installResponsiveEditorSizing` adds `ComponentListener` for dynamic width recalculation via `setSize(editorWidth, Short.MAX_VALUE.toInt())`.
- **MessageMetadataExtractor.kt** — Pure JSON parsing utility; no layout.

**Bubble rendering pipeline**: ChatView → ChatMessageBubbleRouter → (User|Assistant|Tool|Other)BubbleRenderer → BaseBubbleRenderer.createOuterPanel(GridBagLayout) → BubbleComponentFactory creates headers (FlowLayout), content (BoxLayout.Y_AXIS + FlatMessageBlock), actions (FlowLayout.RIGHT) → addToOuter() composes final bubble.

**Key Swing layout rules for bubbles**:
1. Outer container: always `GridBagLayout` with `weightx=1.0`, `fill=HORIZONTAL`
2. Vertical stacking: `BoxLayout.Y_AXIS` — every child MUST set `alignmentX = LEFT_ALIGNMENT`
3. Expansion: set `maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)` on components that should expand
4. Spacing: use `Box.createVerticalStrut()` not borders/insets
5. Dynamic content: `removeAll()` + `add()` + `revalidate()` + `repaint()` pattern
6. Markdown panels: need responsive sizing via `ComponentListener` on parent

### Autocomplete Components
- **AutocompletePopup.kt** — Generic autocomplete dropdown popup.
- **ContextAutocompleteItem.kt** — Item model for @context suggestions.
- **CommandAutocompleteItem.kt** — Item model for /command suggestions.
- **SubagentAutocompleteItem.kt** — Item model for !subagent suggestions.
- **ContextValidator.kt** — Validates context reference validity.

### Input Components
- **InputPanelContainer.kt** — Manages prompt input layout with editor and controls.
- **CodeSnippetCard.kt** — Visual card for code snippet in input panel.
- **SnippetsContainer.kt** — Displays added code snippets.

### Other Panels
- **ContextPanel.kt** — Project context sections updated in real-time during execution.
- **CollapsibleContextSection.kt** — Collapsible section within context panel.
- **HistoryPanel.kt** — Previous sessions list for quick access and switching.
- **LogsPanel.kt** — Debug logs from PluginLogger.
- **RagViewPanel.kt** — RAG indexing/embedding progress and controls.
- **StepsQueueView.kt** — Execution steps queue with status and controls.
- **ApiLogsPanel.kt** — API call logs with timestamps and durations.
- **ApiLogDetailsDialog.kt** — Detailed API call information dialog.
- **LLMPromptViewerDialog.kt** — Shows actual LLM prompt sent to model.
- **StatusBar.kt** — Operation progress, token usage, cost.

### Settings Panels
- **SettingsView.kt** — Main settings panel coordinating all tabs.
- **GeneralSettingsPanel.kt** — General preferences.
- **AdvancedSettingsPanel.kt** — Advanced config options.
- **ModelsSettingsPanel.kt** — Model selection/config.
- **ProvidersSettingsPanel.kt** — LLM provider config and API keys.
- **ContextSettingsPanel.kt** — Context building and RAG config.
- **PromptsSettingsPanel.kt** — Prompt template management.
- **ToolsSettingsPanel.kt** — Tool registry and config.
- **SubagentSettingsPanel.kt** — Subagent config.
- **DocsSettingsPanel.kt** — Documentation integration.
- **MCPSettingsPanel.kt** — MCP settings.
- **BuiltinPromptEditDialog.kt** — Edit built-in prompt templates.
- **SystemPromptEditDialog.kt** — Edit system prompt.
- **CommandEditDialog.kt** — Edit slash commands.
- **RuleEditDialog.kt** — Edit context rules.

### Theme
- **LCATheme.kt** — Theme definitions: colors, fonts, borders, styling constants.
- **ContextSectionColorPalette.kt** — Color palette for context section visualization.

### Completion
- **RefioCompletionContributor.kt** — IntelliJ completion provider for slash commands and context.

### Listeners
- **SwingWorkflowListener.kt** — Bridges core workflow events to Swing UI updates.

## `cli/` — CLI / TUI

### Entry Points
- **main.kt** — `RefioCommand` (Clikt): handles `--project`, `--mode`, `--headless`, `--prompt`, `--multi-agent`; implements `runHeadless()` and `runMultiAgent()`.
- **StandaloneCoreBootstrap.kt** — Two-tier init (app + project routers) for non-IntelliJ; SQLite at `~/.refio/data/database.sqlite`; registers standalone context providers and optional MCP.

### TUI Application
- **tui/TuiApp.kt** — Main TUI entry: interactive (raw mode) vs simple (line-based) fallback; alternate screen buffer, resize detection, input/render loops.
- **tui/TuiScreenManager.kt** — Manages overlay screens (History, Settings) on top of main content.
- **tui/TuiTabBar.kt** — 7-tab header bar (F1-F7) with ANSI colors.

### TUI Components
- **tui/components/TuiCollapsible.kt** — Expandable section with arrow indicator (▼/▶).
- **tui/components/TuiDialog.kt** — Modal confirmation dialog with bordered box and Y/N prompts.
- **tui/components/TuiMessageBubble.kt** — Message renderer with role-based routing; parses code blocks, thinking tags, JSON, markdown.
- **tui/components/TuiProgressBar.kt** — Text-based `[████░░░░ 45%]` with color styling.
- **tui/components/TuiPromptInput.kt** — Prompt input showing mode, model, execution toggles, current input.
- **tui/components/TuiSpinner.kt** — Braille Unicode spinner with 10-frame animation.
- **tui/components/TuiStatusBar.kt** — Bottom bar: mode, model, streaming, cost/tokens, quit hint.
- **tui/components/TuiTable.kt** — Generic table renderer via Mordant with scrolling.

### TUI Input
- **tui/input/TuiCompleter.kt** — JLine3 completer for @mentions, /commands, !subagents.
- **tui/input/TuiContextValidator.kt** — Validates @file/@folder references; 100KB file limit, depth 3.
- **tui/input/TuiInputHandler.kt** — Dual-mode: raw (F-keys, Ctrl) for TTY, line mode (stdin) for non-interactive; slash command handlers.
- **tui/input/TuiKeybindings.kt** — Key bindings: escape sequences, F-keys, control chars → `TuiAction` sealed class.

### TUI Rendering
- **tui/rendering/TuiColors.kt** — ANSI palette: role colors (user=brightGreen, assistant=brightCyan), status, log levels, per-agent color indices.
- **tui/rendering/TuiContentParser.kt** — Parses assistant messages into segments (thinking, code, JSON, markdown) via regex.
- **tui/rendering/TuiContentSegment.kt** — Sealed interface: Thinking, Code (with language/path), Json, Markdown.
- **tui/rendering/TuiLayout.kt** — Split-pane dimensions (55%/45%); MIN_WIDTH=80, MIN_HEIGHT=24.
- **tui/rendering/TuiMarkdown.kt** — Markdown rendering wrapper using Mordant's widget.
- **tui/rendering/TuiRenderBuffer.kt** — Fixed-width line buffer: ANSI-aware measurement, truncation, word-wrapping, side-by-side merging.
- **tui/rendering/TuiRenderer.kt** — Full-screen framebuffer with split-pane compositor; overlays, tab bar assembly, atomic screen flushes.
- **tui/rendering/TuiScreenBuffer.kt** — Full-screen framebuffer: ANSI-padded rows, overlay splicing, atomic flush with clear-to-EOL, cursor positioning.

### TUI Screens
- **tui/screens/TuiHistoryScreen.kt** — Session history table (ID, Mode, Status, Date, Tokens, Cost, Name).
- **tui/screens/TuiSettingsScreen.kt** — 11-tab settings screen with config read/write via `TuiViewModel`.

### TUI State
- **tui/state/TuiChatMessageMapper.kt** — Maps `AgentEvent` to `TuiChatMessage` with per-agent color index assignment.
- **tui/state/TuiLogSink.kt** — `LogSink` capturing log messages into TUI state for Logs tab (max 500 entries).
- **tui/state/TuiState.kt** — Unified state: screen, activeTab, messages, agents, steps, context, logs, approvals, sessions, mode, model, toggles.
- **tui/state/TuiViewModel.kt** — Coordinator ViewModel (~1289 LOC): creates and wires 3 sub-VMs, manages tabs/screens/settings, lifecycle; exposes `chat`, `session`, `obs` sub-VMs.
- **tui/state/TuiChatViewModel.kt** — Chat sub-VM (~903 LOC): messages, input, autocomplete, approvals, slash commands, sendMessage.
- **tui/state/TuiSessionViewModel.kt** — Session sub-VM (~750 LOC): session history, execution/steps, model selector, mode cycling.
- **tui/state/TuiObservabilityViewModel.kt** — Observability sub-VM (~964 LOC): RAG, logs, debug, API logs, file browser, content viewer.
- **tui/state/TuiWorkflowListener.kt** — `WorkflowEventListener` with 500ms debounce on stream chunks; accumulates content, marks steps complete, extracts metrics.

### TUI Views
- **tui/views/TuiChatView.kt** — Messages (bottom-aligned auto-scroll) and prompt input.
- **tui/views/TuiStepsView.kt** — Subtasks with status badges (NEW/PENDING/RUNNING/COMPLETED/FAILED).
- **tui/views/TuiContextView.kt** — Token usage: colored segmented bar with section legend and category-colored dots.
- **tui/views/TuiDebugView.kt** — Session state display with core health indicator.
- **tui/views/TuiLogsView.kt** — Scrollable color-coded log list (DEBUG=gray, INFO=white, WARN=yellow, ERROR=red).
- **tui/views/TuiRagView.kt** — RAG stats: indexed files, chunks, embeddings with completion percentage.
- **tui/views/TuiApiLogsView.kt** — API call table: timestamp, provider, model, tokens, cost.
