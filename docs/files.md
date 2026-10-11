# Package & File Reference

606 main Kotlin source files: 424 in `:core`, 131 in `:intellij-plugin`, 51 in `:cli` (state as of 2026-10-11).

## `core/api/` - Core API Contracts

- **Router.kt** - Base router interface defining `initialize()` and `shutdown()` contract for all domain routers.
- **UIAdapter.kt** - Platform-agnostic UI interface for notifications, status, questions, progress without IntelliJ dependency.
- **StreamTypes.kt** - Unified streaming callback architecture: `StreamCallback` typedef and `StreamChunk` data class for UI updates during LLM generation.
- **ChatModels.kt** - Chat request/response models with `ChatRequest`, `ChatResponse`, `ChatCosts`, `LLMParams` for conversations with optional context references.
- **PlanModels.kt** - Plan response models including `PlanResponse`, `PlanSpecStepResponse`, plan statistics, and execution summary.
- **StreamingModels.kt** - Streaming agent/plan models with `ToolCallSpec` and `PlanDecisionInfo` for tool selection transparency.
- **PromptsModels.kt** - DTOs for prompt CRUD: `PromptDto`, save/update requests for rules, commands, and system prompts.
- **ModelOperation.kt** - Enum for model selection slots (DEFAULT, PLAN, CODING, WEAK, EMBEDDING); maps `TaskMode` to operation.
- **CoreApiRouter.kt** - Composition root (~320 LOC): creates the shared dependencies via the `modules/` helpers and exposes the domain routers; no business logic.
- **ApiModels.kt** - Request/response DTOs for `CoreApiRouter` and the domain routers (tasks, subtasks, config, tools, logs, etc.).
- **PlanningModels.kt** - Planning request/response DTOs (`PlanningRequest` with input, context refs, model/provider override).
- **RagModels.kt** - RAG DTOs (`RagIndexedFileDto`, `RagStatisticsDto`) shared by `RagRouter` and the UIs.

## `core/api/modules/` - Composition Root Modules

- **PersistenceModule.kt** - Constructs every repository in one place so the composition root stays free of boilerplate.
- **SupportServicesModule.kt** - Services between persistence and routers: hook runtime, working-memory caches, agent-plan registry, conversation summarizer, user-interaction signaller, pending-message queue.
- **AnalysisStack.kt** - Bundles embeddings, RAG chunking, file analyzer, rich project analysis, project analyzer, context service and snapshot service; all null for an app-level router without a project root.
- **ChatPlanningModule.kt** - Holds `ChatService` for CHAT mode (PLAN/AGENT run through `AgentTurnLoop`).
- **AgentExecutionModule.kt** - Builds the shared `ToolExecutor` used by `AgentTurnLoop`; null when the router has no tool registry.
- **AgentTurnLoopFactory.kt** - Wires `AgentTurnLoop` with all its `turn/` sub-components; returns null when the tool registry or executor is missing.
- **DomainRouters.kt** - Lazy holder that assembles the domain routers and workflow plumbing exposed by `CoreApiRouter`.
- **EmbeddingProviderFactory.kt** - Resolves `provider/model` strings into a concrete `EmbeddingProvider`, gated by `NetworkPolicy`.
- **ProjectRouterFactory.kt** - Builds a project-scoped `CoreApiRouter` from the app-scoped parent, sharing LLM client, config and prompts but with its own `ToolRegistry`.
- **CoreApiRouterBootstrap.kt** - One-shot bootstrap side effects: system tool registration, Ollama concurrency setup, database init, prompt defaults and RAG tool wiring.
- **SystemToolsRegistrar.kt** - Registers system tools (`invoke_subagent`, `delegate_to_strong_model`, `tasks`, `memory`, `manage_subagent`, `send_message`) into a `ToolRegistry`.

## `core/api/routers/` - Domain Routers

- **TaskRouter.kt** - CRUD for tasks, status updates, queries; maps Task entity to `TaskResponse` DTO.
- **ChatRouter.kt** - Chat conversations with streaming support (stream callbacks); handles summarization, session titles, message history.
- **AgentRouter.kt** - Agent execution: runs and continues PLAN/AGENT turns via `AgentTurnLoop` (`runTurn` / `continueTurn`) and generates execution summaries.
- **ToolRouter.kt** - Tool registry and permissions management; provides tool catalog and per-mode (plan/agent) availability.
- **PromptsRouter.kt** - System prompts, rules, slash commands CRUD with variable substitution and reset to defaults.
- **ConfigRouter.kt** - Model configuration and visibility; lists models, manages visibility, provider testing.
- **RagRouter.kt** - RAG operations: project indexing, semantic chunking, documentation indexing, similarity search.
- **ApiLogsRouter.kt** - API log management: recent logs, filtering by provider/model/source, statistics.
- **SubtaskRouter.kt** - Subtask CRUD, approval status updates, ordering; maps `Subtask` → `SubtaskResponse`.
- **MultiAgentRouter.kt** - Multi-agent session lifecycle: launch (YAML parse → DB session → parallel agent execution), query, list sessions.
- **ProjectContextRouter.kt** - Project context for UI visualization: builds context, generates runtime/auxiliary prompt previews, maps to response DTOs (~870 LOC).
- **SnapshotRouter.kt** - File snapshot operations (get, read file content, plan/run restore, delete by task) for rollback of AGENT-mode edits; throws when no project root (no `SnapshotService`).

## `core/config/` - Configuration System

- **ConfigKeys.kt** - Centralized typed configuration registry (70+ `ConfigKey` definitions) covering general settings, limits, models, RAG, providers, UI, tools, security, agent flow. Each key has parser, default, serializer, and optional YAML accessor.
- **ConfigYaml.kt** - Data classes for YAML deserialization with hierarchical config (user + project); includes sanitization for malformed YAML.
- **HierarchicalConfigLoader.kt** - Merges config from multiple sources: built-in defaults > `~/.refio/config.yaml` > `.refio/config.yaml`. 30-second cache.
- **ConfigYamlModel.kt** - Serializable per-section YAML data classes (`GeneralConfig` and the other sections) that make up `ConfigYaml`.
- **ConfigYamlIO.kt** - Load, save and encode/decode operations for `ConfigYaml` (Kaml, non-strict mode).
- **ConfigYamlMerger.kt** - Section-aware merge of user and project config: scalars override, visibility maps and hook lists concatenate, MCP servers dedupe by id.
- **ConfigYamlEmitter.kt** - Pure pretty-printers: commented YAML export for the Settings UI and the fully documented example config.
- **YamlSanitizer.kt** - Fallback fixes applied when the strict YAML parse fails (unescaped backslashes in double-quoted strings, broken legacy list items).
- **RunConfigOverrides.kt** - Parses CLI `--config key=value` and `--config-file` into a validated run-scope override map; fails loud on unknown keys or invalid values.
- **ConfigPrintView.kt** - Renders the resolved config for CLI `--print-config` (override > DB > YAML > default) with secrets redacted and overrides marked.
- **RefioHome.kt** - Resolves the per-user directory (normally `~/.refio`); a one-shot override lets headless runs point at a throwaway directory.

## `core/db/` - Database Layer

### Tables
- **TasksTable.kt** - Core task tracking with modes (CHAT/PLAN/AGENT), status, execution modes, approval workflow, token/cost aggregation.
- **SubtasksTable.kt** - Steps within task execution plans with approval workflow, LLM metrics, snapshot rollback; FK to TasksTable.
- **ChatMessagesTable.kt** - Conversation history with tool call support, thinking (reasoning), metadata, summarization.
- **SnapshotsTable.kt** - File content snapshots with zlib compression for rollback; stores hash, compressed content, compression ratio; each row belongs to a snapshot group (`group_id`).
- **SnapshotGroupsTable.kt** - Group of file snapshots taken together before one write operation (one group per tool execution); referenced from `SubtasksTable.snapshotIdBeforeWrite`.
- **MessageMetrics.kt** - Per-message/step execution metrics (model, provider, tokens, cost, latency, ...) stored as JSON in `ChatMessagesTable.metadata`.
- **ApiLogsTable.kt** - Complete LLM API call history with secret redaction; stores provider, model, endpoint, request/response, tokens, costs.
- **PromptsTable.kt** - System prompts, rules, slash commands with enable/disable toggle; comprehensive prompt type enum.
- **ConfigTable.kt** - Unified configuration with scope-based precedence (APP/PROJECT/TASK); composite primary key.
- **MCPServersTable.kt** - MCP server configs stored as JSON payloads with enable/disable toggle and per-project scoping.
- **RagTables.kt** - RAG system: `IndexFilesTable`, `IndexChunksTable`, `EmbeddingsTable` (vector BLOB), `IndexingProgressTable`, `DocumentationSourcesTable`.
- **ProjectAnalysisReportsTable.kt** - Cached project analysis reports with fingerprint checksums for invalidation.
- **AgentTables.kt** - Multi-agent: `AgentSessionsTable`, `AgentInstancesTable`, `AgentEventsTable` (event sourcing with JSON payloads).

### Migration System
- **SchemaMigrationsTable.kt** - Tracks executed migration versions.
- **Migration.kt** - Abstract migration interface with version number (strategy pattern).
- **MigrationRunner.kt** - Executes pending migrations in order (orchestrator pattern).
- **SqliteIntrospection.kt** - Raw JDBC helpers `tableExists` / `columnExists` used by migrations to stay idempotent.
- **V2DropAgentEventsSessionFk.kt** - Rebuilds `agent_events` without the foreign key on `session_id` (single-agent runs use the task id as session id).
- **V3RenameSlashCommandToSlashPrompt.kt** - Remaps stored prompt type `SLASH_COMMAND` to `SLASH_PROMPT`.
- **V4SnapshotGroups.kt** - Introduces `snapshot_groups` and rewires snapshot/subtask foreign keys to it, migrating existing snapshots into groups.
- **V5RenameThinkingEnabledToReasoningEffort.kt** - Migrates boolean `general.thinking_enabled` to enum `general.reasoning_effort` (true -> MEDIUM, false -> OFF).
- **V6AddCachedTokensToTasks.kt** - Adds the `cached_tokens` column to `tasks` (cache-read input tokens per task); idempotent.

### DatabaseFactory
- **DatabaseFactory.kt** - Initializes SQLite with WAL mode, pragma settings, connection pooling, transaction retry logic; creates all tables (singleton factory with eager init).

### Repositories
- **TaskRepository.kt** - Full CRUD for tasks with filtering, status updates, metric increments, cursor pagination.
- **SubtaskRepository.kt** - Subtask lifecycle: atomic shift-and-insert for plan reordering, approval workflow, metrics.
- **ChatMessageRepository.kt** - Conversation history: append-only with selective deletion from pivot point for truncation.
- **SnapshotRepository.kt** - Compressed snapshot storage/retrieval with decompression; hash-based deduplication.
- **SnapshotGroupRepository.kt** - Creates and looks up snapshot groups (the snapshots captured before a single tool execution).
- **ApiLogRepository.kt** - API call logging with global statistics, filtering, secret redaction on insert.
- **PromptsRepository.kt** - Prompt management: system prompts, rules, commands with type-based queries and enable/disable.
- **ConfigRepository.kt** - Scope-aware configuration with precedence lookup (TASK > PROJECT > APP); handles DB-not-ready gracefully.
- **MCPServerRepository.kt** - MCP config management with JSON serialization and per-project/global scoping.
- **ProjectAnalysisReportRepository.kt** - Upsert for cached reports with checksum-based invalidation.
- **DocumentationRepository.kt** - RAG doc source management with lifecycle (PENDING > INDEXED > FAILED), URL normalization, batch ops.
- **RagRepositories.kt** - Three specialized repos: `IndexFileRepository`, `IndexChunkRepository`, `EmbeddingRepository`, plus `IndexingProgressRepository`.
- **RagRepository.kt** - Unified RAG facade over specialized repos with batch optimization (`getFilesBatch`, `getChunksBatch`).
- **AgentSessionRepository.kt** - Multi-agent session creation and status tracking with project isolation.
- **AgentInstanceRepository.kt** - Individual agent management within sessions with status/result updates and cost tracking.
- **AgentEventSqlRepository.kt** - Event sourcing implementation with Gson serialization and polymorphic deserialization; supports replay.

## `core/errors/` - Error Handling

- **RefioError.kt** - Sealed class error hierarchy: `LLMTimeout`, `LLMAuthentication`, `LLMRateLimit`, `LLMError`, `ProviderNotConfigured`.
- **LLMErrorMapper.kt** - Maps HTTP status codes and throwables to `RefioError` types; detects auth (401/403), rate limits (429), timeouts.
- **TransientErrorClassifier.kt** - Single decision on whether an LLM failure is transient (bounded retry: 5xx, 529 overloaded, etc.) or terminal; shared by `LLMRetryHandler` and tools that call the LLM directly.
- **NativeToolTemplateError.kt** - Recognises the provider-side failure where the server (e.g. Ollama's chat template, HTTP 500) could not parse the model's tool call.

## `core/debug/` - Run Diagnostics (`run.json`)

- **SessionDebugSnapshot.kt** - Stable, versioned public data contract of one session, emitted as `run.json` for the benchmark/e2e pipeline.
- **SessionDebugExporter.kt** - Builds a `SessionDebugSnapshot` from core repositories by task id and serializes it; backs CLI `--output json`.
- **SessionDebugOptions.kt** - `DebugLevel` (MINIMAL/STANDARD/FULL/JUDGE) and the options controlling what the exporter includes.
- **StabilizationGate.kt** - Aggregates per-run e2e verdict records (`results.jsonl`) into pass-rate and failure-mode statistics.
- **ContextOverflowTracker.kt** - Records whether a task's prompt ever exceeded the model's context window (silent truncation counts as a failed run).
- **TurnContextTracker.kt** - Sums over a run what the context builder had to leave out to fit the window (dropped messages, budget, used tokens).
- **TurnFailureMarkerTracker.kt** - Records the first guardrail that aborted a task's turn so failing runs can be classified by cause.
- **TurnFileWriteTracker.kt** - Per-path write records for a turn, including whether the file ended up actually changed.
- **TurnGuardrailStatsTracker.kt** - How much steering a turn needed: nudges, guardian re-entries, worst repetition streak, tool error rate, no-op writes.
- **TurnIterationTracker.kt** - Loop iterations taken versus the allowed ceiling.
- **TurnNativeToolsTracker.kt** - Whether a turn dropped the provider's native tool-calling channel mid-way, and why.
- **TurnStopReason.kt** - Countable reason why the turn loop stopped, exported as `run.json.metrics.stopReason`.
- **TurnVerificationTracker.kt** - Outcome of the deterministic post-turn build/test verification step (ran, result, exit code, attribution).

## `core/llm/` - LLM Abstraction Layer

- **Base.kt** - Core data structures and abstract `BaseLLMAdapter` base class for all LLM adapters; message types, response types, universal parameter normalization.
- **LLMClient.kt** - Unified entry point for all LLM requests; routes to appropriate adapter by provider name (facade pattern); handles token estimation, streaming, error handling. **Also the central writer of LLM stats** - accepts `taskRepository` + `subtaskRepository` and auto-increments `tokens_in` / `tokens_out` / `cost_usd` on the `task` and (when `subtaskId` is supplied) `subtask` rows after every successful call.
- **NativeToolsFallbackTracker.kt** - Persistent set of model ids that failed native function-calling. `bind(configService)` hydrates from `models.native_tools_fallbacks` on startup; every `markFallback()` mirrors back to disk. Provides `unmark(modelId)` and `clear()` for user-driven retry.
- **NativeToolsResolver.kt** - Central decision logic for native vs JSON-in-text tool path. `NativeToolsMode` enum (`AUTO` / `ALWAYS` / `NEVER`); precedence: fallback cache → NEVER → ALWAYS → `ModelDefinition.supportsFunctionCalling`.
- **ToolSchemaSanitizer.kt** - Provider-aware JSON Schema normalization: OpenAI strict-mode compatibility, Anthropic composition-keyword stripping, Gemini single-type coercion + nullable fields.
- **ModelRegistry.kt** - Dynamic model discovery with parallel provider fetching, 5-minute cache, mutex-protected single-flight requests.
- **ModelDefinitions.kt** - Central registry (~7000 lines) of all supported models with pricing, context limits, capabilities, parameter mappings. Single source of truth.
- **SupportedModels.kt** - Whitelist of supported models per provider; filters dynamically discovered models against tested/approved sets.
- **Pricing.kt** - Cost calculation: queries `ModelDefinitions` for pricing, computes cost = (tokens / 1M) * price.
- **TokenEstimator.kt** - Token counting heuristic (4 chars = 1 token); validates requests against model context limits with fallback chain.
- **JsonExtractor.kt** - Universal JSON extraction from LLM responses (plain JSON, markdown blocks, wrapped content); chain of responsibility with multiple fallback strategies.
- **HttpClientConfig.kt** - HTTP client timeout/retry/rate config; loads from `ConfigService` with fallback defaults.
- **NoEgressViolationException.kt** - Exception thrown when no-egress mode blocks cloud LLM usage; only local providers (Ollama, LM Studio) allowed.
- **ModelWindow.kt** - The single resolver of a model's context window size in tokens; all other code asks it instead of keeping its own table.
- **ProviderToolSupport.kt** - Process-lifetime cache of what the serving provider reports about a model's function-calling support, so unknown local models are not silently treated as tool-less.
- **ReasoningEffort.kt** - User-selectable reasoning strength (OFF/LOW/MEDIUM/HIGH), translated per adapter to the provider's native knob; replaces the boolean thinking toggle.
- **StreamFinishReason.kt** - Finish-reason values Refio produces itself (e.g. stream ended without its protocol terminator), chosen not to collide with provider values.
- **LLMMessageMapper.kt** - Maps stored tool-result chat messages into `LLMMessage`s with a stable tool header (prefers subtask id over tool call id).

## `core/llm/streaming/` - Streaming Guardrails

- **StreamGuardrail.kt** - Request-scoped guard that inspects each streamed delta and decides to continue or abort.
- **StreamGuardrails.kt** - Composition of guardrails checked in order on every delta; the first abort wins.
- **RepetitionDetector.kt** - Aborts a stream stuck repeating the same block of text.
- **OutputSizeLimiter.kt** - Hard ceiling on streamed output size against runaway generations.
- **WallClockDeadline.kt** - Independent wall-clock deadline for a streaming response, separate from the HTTP timeout.
- **StreamAbortedException.kt** - `CancellationException` thrown when a guardrail aborts a stream; carries the partial content accumulated so far.

## `core/llm/adapters/` - LLM Provider Adapters

- **AnthropicAdapter.kt** - Claude API: system messages as top-level param (not in messages array), thinking/extended thinking mode, `tool_use` content blocks, SSE streaming.
- **OpenAIAdapter.kt** - GPT models: Chat Completions and Responses API formats; special handling for reasoning models (o1, o3) that don't support streaming/system roles/temperature.
- **OllamaAdapter.kt** - Local Ollama: JSON mode, thinking mode, retry logic for model loading delays (`done_reason="load"`), GPU memory management via `keep_alive`, NDJSON streaming.
- **GeminiAdapter.kt** - Google Gemini: converts to "contents/parts" structure, `system_instruction` param, SSE streaming, function call extraction from parts.
- **OpenRouterAdapter.kt** - Unified API for 100+ models; OpenAI-compatible format; thinking mode for Claude models via OpenRouter.
- **GenericOpenAIAdapter.kt** - Generic OpenAI-compatible adapter for `custom_openai` endpoints and the base of `ZAIAdapter`; supplies per-provider config keys, enforces `api_key` when required, and (for Z.AI) serializes requests through a mutex with retry after HTTP 429.
- **LLMKtorClientFactory.kt** - Shared Ktor `HttpClient` factory for OpenAI-compatible adapters: Gson content negotiation, sanitized logging, streaming-friendly timeouts with an idle (socket) ceiling independent of the total timeout.
- **ZAIUrls.kt** - Known Z.AI base URLs; normalizes legacy endpoints to the current default.
- **OpenAICompatibleAdapter.kt** - Shared OpenAI-compatible base used by OpenRouter / Z.AI / Generic OpenAI / LM Studio. Now wires the native `tools` array via `OpenAICompatibleHelpers.buildOpenAIToolsArray` and parses `tool_calls` responses (streaming + non-streaming) into `nativeToolCalls`.
- **OpenAICompatibleHelpers.kt** - Shared helpers for OpenAI-compatible adapters: `buildOpenAIToolsArray(tools)` (canonical `[{type:function,function:{name,description,parameters}}]` shape, sanitized via `ToolSchemaSanitizer.forOpenAI`), `parseOpenAIToolCalls(rawToolCalls)` (extracts `tool_calls` field into provider-agnostic `NativeToolCall` entries), `addCommonKwargs`, `resolveMaxTokens`.
- **LMStudioAdapter.kt** - LM Studio local server: OpenAI-compatible wrapper; configurable base URL, optional API key; logs unsupported thinking mode.
- **ZAIAdapter.kt** - Thin subclass of `GenericOpenAIAdapter` for the Z.AI provider; inherits rate-limit serialization and streaming.
- **ToolCallContentNormalizer.kt** - Normalizes tool call formats from all providers into unified canonical JSON; includes `OpenAiStreamingToolCallAccumulator` for streaming.

## `core/context/` - Context Provider System

- **BaseContextProvider.kt** - Abstract base class for all context providers with optional submenu support (template method pattern).
- **ContextProviderRegistry.kt** - Singleton registry managing all context providers; lazy-initializes via pluggable factory for platform independence (IDE vs CLI).
- **ContextRefSpec.kt** - Parses the CLI `--context-ref` form (`@file:...`, `@folder:...`, `@rules`, provider ids with a query) into a `ContextReference`, mirroring chat-input syntax.

### `core/context/providers/` - IDE-specific Providers

- **CurrentFileContextProvider.kt** - Returns active editor file from IntelliJ `FileEditorManager` with sandbox validation.
- **OpenFilesContextProvider.kt** - Returns all open editor tabs with full content.
- **RecentFilesContextProvider.kt** - Returns 15 most recently edited files from `EditorHistoryManager`; submenu support.
- **FileContextProvider.kt** - File picker with search via `FilenameIndex`; sandbox validation.
- **FolderContextProvider.kt** - Directory tree structure with file sizes; 3-level depth, folder search.
- **ClipboardContextProvider.kt** - Reads system clipboard content via Java AWT (lives in `:core`, the rest of this list lives in `:intellij-plugin`).
- **UrlContextProvider.kt** - Fetches web content via Ktor HttpClient; 100KB limit (lives in `:core`).
- **CodebaseContextProvider.kt** - Semantic codebase search via RAG/embeddings; strategy pattern for embedding providers.
- **DocsContextProvider.kt** - Searches indexed documentation via RAG; conditional inline vs RAG based on content size.
- **GrepSearchContextProvider.kt** - Regex/text search via IntelliJ `PsiSearchHelper`; 50 match limit, respects `.aiignore`.
- **GitDiffContextProvider.kt** - Uncommitted changes from IntelliJ `ChangeListManager`; limited to 20 files.
- **GitCommitContextProvider.kt** - Git commit details via ProcessBuilder CLI; supports hash, branch, tag, keyword search.
- **ProblemsContextProvider.kt** - Compilation errors/warnings from `WolfTheProblemSolver`; limited to 20 files.
- **TerminalContextProvider.kt** - Extracts last 200 lines of terminal history using reflection to access JediTermWidget internals.

### `core/context/providers/standalone/` - CLI-compatible Providers

- **StandaloneCodebaseContextProvider.kt** - RAG codebase search without IntelliJ; uses `workspacePath` directly.
- **StandaloneDocsContextProvider.kt** - Documentation search without IntelliJ; simplified RAG initialization.
- **StandaloneFileContextProvider.kt** - File discovery via `Files.walk()` instead of `FilenameIndex`; regex filtering, binary detection.
- **StandaloneFolderContextProvider.kt** - Directory traversal via `Files.walk()`; 5-level depth, hardcoded ignore list.
- **StandaloneGitCommitContextProvider.kt** - Git commit lookup via ProcessBuilder without IDE dependencies.
- **StandaloneGitDiffContextProvider.kt** - Git staged/unstaged/untracked status via CLI; structured diff summary.
- **StandaloneGrepSearchContextProvider.kt** - Regex search via Java `Pattern`; falls back to literal on compile error; binary detection.

### `core/context/mcp/` - Model Context Protocol

- **MCPProtocol.kt** - JSON-RPC 2.0 request/response data classes and MCP method constants (initialize, resources/list, tools/list, tools/call).
- **MCPModels.kt** - Config data classes: `MCPServerConfig`, auth (bearer/OAuth), transport (stdio/HTTP), workflow config.
- **MCPServerPresets.kt** - 17 built-in MCP server presets by category (Documentation, VCS, Database, Search, Filesystem, Communication, Cloud, Memory, DevOps, Development).
- **MCPManager.kt** - Singleton managing MCP server connections per project; loads configs from DB, registers tools and context providers dynamically.
- **MCPConnection.kt** - Connection lifecycle and communication: connect, initialize, refresh resources/tools, call tools; async with request/response tracking.
- **MCPHttpTransport.kt** - Ktor-based HTTP/SSE transport; bearer auth, custom headers, OAuth, env var resolution.
- **MCPStdioTransport.kt** - ProcessBuilder-based stdio transport; process spawning, env setup, non-blocking I/O with separate reader/stderr jobs.
- **MCPContextProvider.kt** - Dynamic context provider wrapping MCP servers; supports resource-based and tool-based exposure with workflow execution.
- **MCPToolWrapper.kt** - Adapts MCP tool definitions as internal `Tool` objects for `ToolRegistry`.
- **MCPToolWorkflowExecutor.kt** - Multi-step workflow execution with input/output mapping and JSON path extraction for variable propagation.
- **MCPTestRunner.kt** - Tests MCP server connections by sending initialize request; transport-agnostic with env/header redaction.
- **MCPToolExposure.kt** - Pure decision whether a connected MCP server contributes agent-callable `mcp_<id>_*` tools (tools enabled, TOOLS exposure mode, advertised capability) and, if not, why.
- **MCPSseFraming.kt** - Line-by-line Server-Sent Events reader used by the HTTP transport (multi-line data, comments, event types).
- **MCPServerConfigFile.kt** - Reads an `MCPServerConfig` from a JSON file for servers declared per run (CLI) instead of stored in the DB; fails loud on invalid files.

## `core/tools/` - Tool System

### Base Architecture
- **Tool.kt** - Core `Tool` interface with name, description, `ToolMode` (READ_ONLY/WRITE), `ToolCategory` (DATA_PRODUCING/FILE_MODIFYING/EXECUTION); `ToolResult` data class for standardized responses.
- **ToolFactory.kt** - Factory creating and registering tools with dependency injection; separates read-only from write tools.
- **ToolRegistry.kt** - Thread-safe `ConcurrentHashMap` registry; lookup by name, filtering by mode, permission-based availability.
- **base/FileTool.kt** - Abstract base for sandboxed file-editing tools: `path` validation, sandbox resolution, and file lock plus re-validation before I/O.
- **base/ToolInternalParams.kt** - Names of internal metadata parameters (`_task_id`, `_mode`, `_iteration`, ...) injected into tool arguments by `TurnToolExecutor`.
- **DiffUtils.kt** - Single source of unified-diff generation, hashing and `ChangeSummary` construction for the write tools.
- **LineEndings.kt** - Reconciles LF model strings with CRLF files so exact-match edits (`code_editing`, `multi_edit`) do not miss on Windows checkouts.

### Security
- **PathSandbox.kt** - Restricts all file ops to project root; resolves symlinks via `toRealPath()` for escape attack prevention; defense-in-depth with multiple checkpoints.
- **FileLimits.kt** - File operation constraints: 2MB max size, 1000 dir listing limit, 15 search depth, 100 search results, 500 grep results; lists of excluded directories and extensions.
- **CommandRule.kt** - Regex-based command validation with `RuleAction` enum (ALLOW/BLOCK/ASK); `CommandRuleMatcher` evaluates rules in priority order (BLOCK > ALLOW > ASK); lazy regex compilation with graceful error handling; default action is ASK.
- **CommandRuleDefaults.kt** - Default command rules: ~25 BLOCK rules (POSIX and Windows/PowerShell destructive deletes, mkfs, dd, git reset --hard / force push / force clean, package publishing, credential exposure, database destruction, fork bombs), ALLOW rules from a short curated program list plus primitive utilities, ASK rules for risky tools and a catch-all ASK for unknown commands.
- **ShellCommandAnalyzer.kt** - Splits a raw command line into every unit a shell would run (chained segments, command substitutions, wrapper payloads like `sh -c` or `xargs`) so anchored BLOCK rules are tested against each of them.
- **CommandLimits.kt** - Execution limits for terminal/background commands (default timeout 120s, 200KB output) with `DEFAULT` and `STRICT` presets.
- **FileLockManager.kt** - Coroutine-safe mutual exclusion via `ConcurrentHashMap<String, Mutex>` keyed by normalized paths; `withFileLock(path) { block }`.
- **FileOperations.kt** - Safe file ops with sandbox and snapshot support; SHA-256 hashing for snapshots; `normalizePath()` utility.

### Read-Only Tools
- **ReadFileTool.kt** - Reads file contents with security validation, 2MB size limit, path normalization; returns size/line count metadata.
- **ReadDirectoryTool.kt** - Lists directory contents with optional recursive traversal (max depth 3); formatted output with type/size/path.
- **FileSearchTool.kt** - Finds files by glob pattern (converted to regex); depth limit 10, result limit 100, offset/limit pagination.
- **GrepSearchTool.kt** - Regex text search across files with case sensitivity, glob filtering, 2MB exclusion; formatted as `file:lineNumber: content`.
- **ViewDiffTool.kt** - Compares two files or file vs content using line-by-line diff; unified diff format with add/remove/unchanged counts.
- **WebSearchTool.kt** - Web search via Brave Search, SerpAPI, or DuckDuckGo Instant Answers; configurable provider/API key via `tools.web_search.*` config; returns titles, URLs, snippets.
- **FetchWebpageTool.kt** - Fetches URL, converts HTML to Markdown via Jsoup (strips nav/ads/scripts), processes with weak LLM model using user prompt; 50K char content limit.
- **CodeIntelligenceTool.kt** - IDE-independent code intelligence: `find_usages` (grep), `find_definition` (ctags/grep), `list_symbols` (ctags), `get_diagnostics` (compiler CLI); works in CLI without IntelliJ PSI.
- **MonitorProcessTool.kt** - Reads stdout from a background process by `process_id` (`action="read"`, default) or kills it (`action="stop"`); returns lines and running status; auto-removes processes whose whole tree has finished.
- **AskUserTool.kt** - Asks user a question with optional predefined choices; suspends agent loop via `UserQuestionService`; 10-minute timeout.
- **SleepTool.kt** - Pauses agent execution for up to 30 seconds; uses coroutine `delay()`.
- **FindUsagesTool.kt** - `find_usages`: lists every location where a symbol is used; engine injected (IDE find-usages in the plugin, text search otherwise).
- **RagSearchTool.kt** - `rag_search`: on-demand semantic search over the project's RAG index, as an alternative to automatic RAG injection.
- **ThinkTool.kt** - `think`: no-op reasoning slot where the model writes down its reasoning between tool calls.

### Write Tools
- **CreateNewFileTool.kt** - Creates files with content validation, size limits, file lock; hard-fails if the file already exists; auto-creates parent dirs.
- **RenameSymbolTool.kt** - `rename_symbol`: renames a symbol across the project in one call via an injected `StructuralRefactorer` (IDE refactoring in the plugin, text fallback elsewhere).
- **LlmCallTool.kt** - `llm_call`: raw single-turn LLM call (no tools, history or project context) for classification/transformation; can read a file into the prompt and save the response to a file.
- **CodeEditingTool.kt** - Search-and-replace editing; validates old_string exists and is unique (unless `replace_all=true`); generates unified diff.
- **MultiEditTool.kt** - Atomic multi-file search-and-replace; validates all edits before applying any; per-edit error messages.
- **MultiLineEditorTool.kt** - LLM-assisted editor for 3-10 code locations (~$0.02 cost); parses JSON edits with `EditChange`, validates bounds/overlaps, applies in reverse order; LCS diff generation; streaming support.
- **AdvanceCodeEditingTool.kt** - Full file regeneration via LLM (~$0.06 cost); extracts code from markdown fences; LCS diff; temperature 0.2; streaming support.
- **RunTerminalCommandTool.kt** - Shell execution with CommandRule-based validation (ALLOW/BLOCK/ASK); legacy whitelist fallback; 120s timeout, 200KB output limit; async I/O to prevent deadlocks; cross-platform shell selection.
- **HttpRequestTool.kt** - HTTP requests (GET/POST/PUT/DELETE) with optional `save_to_file` for large responses; 5MB response limit, 60s timeout; auto-detects format (CSV, JSON, text) for saved files; header filtering for relevant response headers.
- **RunCodeTool.kt** - Inline code execution for Python, JavaScript, and Kotlin Script; 120s timeout; sandbox via temporary files; captures stdout/stderr; OFF by default (requires explicit enabling).
- **RunProcessBackgroundTool.kt** - Starts shell command in background via `ProcessManager`; returns `process_id` immediately; CommandRule security validation; for long-running builds/dev servers.

### Subagent Integration
- **InvokeSubagentTool.kt** - Enables agent-to-subagent delegation; validates availability, detects recursion; builds `TurnRequest` with overrides for system prompt, tools, depth.
- **DelegateToStrongModelTool.kt** - `delegate_to_strong_model`: hands a hard sub-problem to the configured strong model, single-shot or as a bounded tool loop.
- **ManageSubagentTool.kt** - `manage_subagent`: creates, updates and deletes subagent definitions at runtime (temporary in memory or saved to `.refio/agents/`).
- **SendMessageTool.kt** - `send_message`: sends a message to the parent orchestrator or another agent; a question suspends the turn until a response arrives.
- **AnswerMessageTool.kt** - `answer_message`: replies to a pending request addressed to this agent, validated against its inbox so responses cannot be forged.

### System Tools
- **TasksTool.kt** - `tasks`: creates and updates the agent's numbered execution plan (plan / update step status / list).
- **MemoryTool.kt** - `memory`: shared working memory (write, read, list facts) and retrieval of a previous tool call's full raw output by subtask id.

### Structural Refactoring (`core/tools/refactor/`)
- **StructuralRefactorer.kt** - Engine interface for rename and find-usages plus `RenameResult`; implemented by the text fallback here and by PSI in the plugin.
- **TextStructuralRefactorer.kt** - Text-based fallback: identifier-boundary-aware search/replace across project files through the same lock and sandbox path as the editing tools.

## `core/services/` - Core Services

- **ProcessManager.kt** - Manages long-running background processes; `start(command, workingDir)` returns `ManagedProcess` with `processId`; `readOutput()` non-blocking line reader; `stop()`/`close()` destroy the whole process tree (self-backgrounded children included); cross-platform shell wrapping (cmd.exe/sh).
- **ProcessTreeTracker.kt** - Retains handles of a process's descendants while it runs, so a child that backgrounds itself and reparents away can still be killed; shared by `ProcessManager` and `RunTerminalCommandTool`.
- **AgentTurnLoop.kt** - Thin facade (~380 LOC) for the self-directing PLAN/AGENT tool loop; keeps the `runTurn` / `continueTurn` / `turnState` / `lastPromptSnapshot` surface byte-stable and delegates the loop body to `turn/TurnExecutor.kt`.
- **TurnLoopConfig.kt** - Configuration for AgentTurnLoop with factory methods for PLAN and AGENT presets (200 iterations each); includes parallel read-only tools, snapshots, retry config, working memory and budget guards.
- **ChatService.kt** - Chat interactions with auto-optimization (conversation summarization on token threshold); builds project context via ContextService.
- **AgentPlanService.kt** - In-memory registry of agent execution plans per task and agent (steps with PENDING/IN_PROGRESS/COMPLETED/FAILED/SKIPPED); backs the `tasks` tool and the plan prompt section.
- **ContextService.kt** - Central context orchestrator (~1230 LOC): delegates to sub-services for MCP resources, context reference resolution, project summarization, conversation building, task extraction and project instructions; budget-aware compression.
- **ConversationSummaryService.kt** - LLM-based conversation summaries; tracks summary metadata (count, indices, timestamps) to avoid re-summarizing.
- **ConfigService.kt** - Configuration facade (~340 LOC): defaults < user YAML (APP) < project YAML (PROJECT) < stored settings (TASK > PROJECT > APP) < run-scope CLI overrides; delegates lookup, bootstrap, YAML import/export and model selection to the helpers below; callers use `getTyped(ConfigKeys.XXX)`.
- **ConfigResolver.kt** - Pure lookup/write layer: hierarchy resolution (TASK > PROJECT > APP > YAML > default), run-scope overrides, cache and invalidation.
- **ConfigCache.kt** - Small TTL cache (60s default) for resolved config values used by `ConfigService`.
- **ConfigDefaultsInitializer.kt** - One-shot startup contract: seed built-in defaults, merge user YAML for missing keys, forced reload from YAML, materialize the project config file into PROJECT scope.
- **ConfigYamlApplier.kt** - Writes a parsed `ConfigYaml` into the APP-scoped store, in overwrite (explicit reload) or load-if-missing (startup) mode.
- **ConfigYamlBuilder.kt** - Builds a `ConfigYaml` snapshot from current DB values for YAML export.
- **ConfigValidator.kt** - Runs every `ConfigKey` validator against the resolved value at startup and after reload; fails loud with `InvalidConfigException`.
- **ConfigKeys.kt** - UI helper that splits full config keys (e.g. `limits.api_call_timeout`) into section and key for settings panels (the typed key registry lives in `core/config/ConfigKeys.kt`).
- **ContextBudgetResolver.kt** - Translates raw config values (per-section overrides, compact-prompt detection, provider context sizes) into a `ContextBudget` snapshot.
- **ModelSelectionService.kt** - Model-slot resolution (default, strong, weak, embedding, per mode), slot writers, dropdown visibility and `provider/model` / `inherit` parsing helpers.
- **PromptsService.kt** - Prompt templates with `{{variable}}` substitution for CHAT/PLAN/AGENT modes; manages user-defined rules and commands.
- **ToolExecutor.kt** - Sequential tool execution with validation, error handling, streaming for code generation tools; snapshot creation.
- **ToolPermissionsService.kt** - 3-level tool permissions (ON/ASK/OFF) per mode (PLAN=read-only, AGENT=read-write) with smart defaults; `run_terminal_command`=ASK in AGENT, `run_code`=OFF by default; task-level overrides via ConfigRouter; `filterAvailableTools()` includes ASK tools.
- **ToolResultSummarizer.kt** - Summarizes tool outputs using WEAK model; short outputs (<500 chars) skip; deterministic compression fallback.
- **ToolResultData.kt** - DTO for tool result with summarization info (content, isSummarized, rawOutput, metadata).
- **SnapshotService.kt** - File versioning and rollback with compression, SHA-256 hashing, snapshot grouping by subtask; keeps N most recent.
- **TaskVerifier.kt** - Optional LLM-based task completion verification; strategy pattern with `NoopTaskVerifier` and `LlmTaskVerifier`.
- **TokenEstimator.kt** - `PromptTokenEstimator`: estimates prompt token count (3.5 chars/token base, the shared ratio for all estimators) with code/JSON overhead and provider-specific multipliers; used on the compaction path.
- **TokenRatioCalibrator.kt** - Closed-loop per-model chars/token calibration (EMA) from real input-token counts reported by the adapters.
- **PromptCache.kt** - Caffeine cache for static prompt components (5-minute TTL matching Anthropic cache lifetime); invalidation on config changes.
- **ProjectAnalyzerService.kt** - Analyzes project structure/technologies with in-memory caching (10-min TTL); invalidates on file modification; mutex-protected.
- **ProjectArchitectureDetector.kt** - Detects architectural patterns, entry points and top-level modules from the project file tree.
- **ProjectDependencyParsers.kt** - Parsers for manifest files (requirements.txt, package.json, Gradle/Maven, CMakeLists.txt, vcpkg.json) used by project analysis.
- **ProjectDomainScorer.kt** - Scores the file tree against known domains (programming, documentation, creative, research, business, educational) and picks the primary one.
- **ProjectLanguageAnalyzers.kt** - Per-language source analyzers (Kotlin, Java, Python, JavaScript, TypeScript, HTML, CSS) plus doc-comment and signature extraction for project analysis.
- **RagIndexingService.kt** - RAG indexing: scans project files, detects changes via checksums, creates semantic chunks; multi-language support.
- **ChunkingStrategy.kt** - `ChunkingStrategy` interface with `DefaultChunkingStrategy` (line-based) and `SemanticChunkingStrategy`; mode chosen from config.
- **RagDispatchers.kt** - Dedicated bounded thread pool for background RAG indexing/embedding so it never starves the interactive IO pool.
- **RagSearchService.kt** - Semantic search with cosine similarity, BM25 scoring, content type filtering, hybrid search, top-K ranking.
- **RagEmbeddingService.kt** - Generates/stores embeddings with batch processing, error recovery; multiple providers (OpenAI, Ollama); vector serialization.
- **EmbeddingProvider.kt** - Interface and implementations (`OpenAIEmbeddingProvider`, `OllamaEmbeddingProvider`, `OpenAICompatibleEmbeddingProvider` for a self-hosted `/embeddings` endpoint) for vector embeddings; batch processing, circuit breaker integration, `NetworkPolicy` egress gate.
- **EmbeddingCircuitBreaker.kt** - Circuit breaker (CLOSED/OPEN/HALF_OPEN) for embedding providers; prevents UI freezing from repeated failures.
- **LLMRetryHandler.kt** - Exponential backoff retry for LLM API calls; does NOT retry cancellations, auth errors, or invalid requests.
- **OllamaRequestGate.kt** - Semaphore-based rate limiting for Ollama (default 1 concurrent request per endpoint).
- **ProviderRequestGate.kt** - Per-provider semaphore capping simultaneous outbound LLM requests (e.g. parallel subagents on OpenRouter); composes with the 429 retry.
- **DocumentFetcher.kt** - HTML fetcher with optional JS rendering via HtmlUnit; configurable timeouts, best-effort error handling.
- **DocumentationIndexingService.kt** - Web crawling and local file indexing; depth-limited, same-domain following, robots.txt respect, Flow-based progress.
- **PendingUserMessageQueue.kt** - Handles mid-execution user input (messages sent while agent is running); saves to ChatMessageRepository with `"mid_execution_input"` metadata; `enqueue()` is non-blocking, `consumePending()` called between AgentTurnLoop iterations; enables user feedback/correction without explicit approve/reject dialog.
- **WorkingMemoryIntegration.kt** - Builds relevance-filtered working-memory sections for prompts from `WorkingMemoryService`.
- **SessionStatsCalculator.kt** - Aggregate session stats (duration, generation time, tokens, cost) from the message list, preferring session-row totals.
- **DocumentReadService.kt** - Reads PDF text via PDFBox, inline for small documents or by page range for large ones.
- **ImagePreparationService.kt** - Validates and downsizes images (PNG/JPEG/GIF/WebP, 5MB, 2048px) into base64 for multimodal requests.

### `core/services/` subpackages
- **execution/unified/ExecutionEventListener.kt** - Listener interface for step execution events so UIs can react without coupling to execution logic.
- **execution/unified/ExecutionModels.kt** - Step plan models (`StepPlan`, `ToolPlan`) returned by the prepare phase.
- **hooks/HookService.kt** - Triggers configured hooks for an event name, filtering by variables and running each via `HookExecutor`.
- **hooks/HookExecutor.kt** - Runs a hook shell command and returns success/output/error.
- **logging/CoreLogger.kt** - Platform-agnostic logger for core services that logs only to kotlin-logging, never to the plugin UI.
- **monitoring/GlobalMetrics.kt** - Process-wide singleton tracking operations across all sessions (per-agent totals used by the plugin's agent views).
- **monitoring/ModelUsageStats.kt** - Aggregated per-model LLM usage (calls, tokens, cost, latency) fed by the turn loop for the Debug panel.
- **monitoring/ToolUsageStats.kt** - Aggregated per-tool usage and failures fed by the turn loop for the Debug panel.
- **monitoring/SystemMonitor.kt** - CPU and memory usage monitor singleton.
- **orchestration/UserInteraction.kt** - Lets the orchestrator ask the user a question during execution, wait for the answer and resume.
- **rag/BM25Scorer.kt** - Okapi BM25 keyword scoring that complements embedding similarity in hybrid RAG search.
- **rag/RagSearchConfig.kt** - RAG search settings (similarity threshold, top-K, content type, timeout, hybrid search).

## `core/services/turn/` - AgentTurnLoop Sub-Components

- **TurnExecutor.kt** - Holds the actual PLAN/AGENT turn loop (`executeTurnLoop` plus its loop-only helpers and the two UI-observed StateFlows), extracted verbatim from `AgentTurnLoop` so the hottest file stays a thin facade; orchestrates the other `turn/` sub-components on each iteration.
- **TurnPromptBuilder.kt** - Builds system and user prompts for each turn; assembles context, tools, working memory.
- **TurnToolExecutor.kt** - Executes tool calls from LLM responses; parallel execution for read-only tools.
- **ToolCallParser.kt** - Parses tool call JSON from LLM responses; handles multiple formats and streaming accumulation.
- **TurnLLMCaller.kt** - Calls LLM with retry and streaming support; handles provider-specific quirks.
- **TurnResponseProcessor.kt** - Processes LLM response into tool calls, text, and thinking segments.
- **TurnGuardrails.kt** - Safety checks: iteration limits, infinite loop detection, token budget enforcement.
- **TurnFinalizer.kt** - Finalizes turn: saves messages, updates metrics, handles completion.
- **TurnJsonUtils.kt** - JSON parsing utilities for tool call arguments.
- **TurnSubagentValidator.kt** - Validates subagent invocation requests.
- **TurnEventListener.kt** - Event listener interface for turn lifecycle events.
- **TurnPrompt.kt** - The single prompt shape passed from `TurnPromptBuilder` to `TurnLLMCaller` (system prompt with cacheable prefix length, messages).
- **TurnState.kt** - Observable turn phase (`TurnPhase`: idle, building prompt, calling model, executing tools, ...) exposed to the UI via StateFlow.
- **PromptSnapshot.kt** - Record of which context sections entered the prompt, with token counts and drop reasons; feeds the Context Inspector.
- **PromptSectionProvider.kt** - Functional interface for one logical section of the system prompt (returns null to omit it).
- **AgentPlansSectionProvider.kt** - Injects agent execution plans into the prompt; the orchestrator sees all plans, a subagent only its own.
- **providers/IterationInfoPromptProvider.kt** - Prompt section with the current iteration and remaining loop budget warnings.
- **providers/SystemEnvironmentPromptProvider.kt** - `<system_environment>` prompt block: OS, shell, path separator, working directory and which common CLI tools are on PATH.
- **providers/ToolContractPromptProvider.kt** - Tool contract prompt section (none in CHAT; mode- and profile-filtered descriptions in PLAN/AGENT).
- **providers/WorkingMemoryPromptProvider.kt** - Working memory prompt section.
- **NativeToolsPrompt.kt** - Replacement text for the `{{tool_descriptions}}` placeholder when tool schemas are sent through the native function-calling API.
- **ToolCallExtractor.kt** - Extracts tool calls from a response across strategies (native, JSON envelope, legacy formats, labeled-line recovery) and records which strategy produced each call.
- **LLMResponseRecovery.kt** - Pure classification of recovery paths for an empty-content response in JSON-in-text mode (e.g. envelope landed in thinking) and the bounded nudge policy.
- **TurnCompletionGuardian.kt** - Before-finish hook that lets a turn finish (`Pass`) or re-enters the loop with a SYSTEM nudge (`Reenter`).
- **NextSpeakerJudgeGuardian.kt** - LLM judge guardian ("is the agent really done?") for PLAN/AGENT that catches turns which announce intent without calling a tool or abandon the request.
- **TurnGuardianState.kt** - Explicit, loggable state of guardian re-entries (count, candidate final response, tools used at last re-entry).
- **TurnDeliverable.kt** - Shared predicate whether a turn already produced a deliverable, deciding FAILURE vs SUCCESS at stall/abort points.
- **TurnVerifier.kt** - Deterministic post-turn verification: runs the project build/test command and reports the result.
- **CostLimitGuard.kt** - Pure per-session cost budget check (`--max-cost` / `agent.max_cost_usd`) evaluated at the top of each iteration.
- **TurnBudgetGuard.kt** - Pure per-turn wall-clock budget check (`agent.max_turn_minutes`), the brake that also works for free local models.
- **AutoApprover.kt** - Pure decision for headless `--auto-approve <regex>`: a match approves, anything else is denied immediately (fail-closed).
- **FileDeleteDenialHint.kt** - Extra guidance attached to a denied in-project file delete, pointing the model to rewrite the file in place.
- **ProposedChange.kt** - The concrete file change (full contents or unified diff) attached to an approval request so the user can review it before the write.
- **SubagentInstanceId.kt** - Resolves the agent-instance id stored on every chat row of a turn, isolating a subagent's history from the parent thread.
- **ToolBatchSummary.kt** - Short human-readable labels for batches of tool calls, shown in the chat UI instead of verbose results.
- **ToolApprovalService.kt** - Manages user approval flow for tools with `PermissionLevel.ASK`; `ApprovalDecision` sealed class (Approved/Trusted/Rejected/NotPermitted); session trust rules via `ConcurrentHashMap` with regex pattern matching; 5-minute approval timeout; `pendingRequests` StateFlow for UI observation.
- **UserQuestionService.kt** - Suspends agent loop while waiting for user answer; `AskUserRequest` with requestId/taskId/question/options; `Listener` interface for UI; `resolve()`/`cancel()` from UI; `CompletableDeferred` + 10-min timeout; same pattern as `ToolApprovalService`.
- **ToolRejectedException.kt** - Exception thrown when user rejects tool execution; caught in AgentTurnLoop to record rejection, set `TurnResult(rejectedByUser=true)`, and break the loop returning control to user prompt.

## `core/services/context/` - Context Building Helpers & Extracted Sub-Services

**Extracted from ContextService (Phase 2 refactor):**
- **ContextFormatter.kt** - Builds the individual prompt context sections (project overview, instructions, dependencies, current task, subtasks, budgeted conversation history, recent work, MCP resources, per-language analysis); ~1050 LOC.
- **ContextPruner.kt** - Context budget resolution and section pruning/truncation.
- **PromptSectionTokenReport.kt** - Parses the XML-tagged sections of a generated prompt into per-section token estimates for the UI breakdown.
- **ToolOutputFormatting.kt** - Pure helpers that render a tool result as a tagged block with metadata and a fenced body, plus truncation and language detection.
- **McpContextLoader.kt** - MCP resource loading with TTL cache (`ConcurrentHashMap`); tool output formatting.
- **ContextReferenceResolver.kt** - Resolves @mentions to content: provider dispatch, legacy type handlers, deduplication, file analysis summaries.
- **ProjectContextSummarizer.kt** - Compact project summaries: architecture, key components, patterns, navigation map.
- **ConversationContextBuilder.kt** - Conversation history filtering: meaningful messages, summary slicing, tool envelope detection.
- **TaskContextExtractor.kt** - Task/subtask data extraction: executed steps, user requirements, result parsing, file change summaries.

**Infrastructure:**
- **ContextBudget.kt** - Token budget allocation per context section; `forContextSize()` factory with configurable ratios and overrides.
- **ContextSection.kt** - Enum of context sections (PROJECT_OVERVIEW, CURRENT_TASK, RAG_FRAGMENTS, CONVERSATION, RECENT_WORK, etc.).
- **ContextTokenEstimator.kt** - Token estimation heuristic (`text.length / 4`); used for budget enforcement.
- **WorkingMemoryService.kt** - Per-task working memory: key-value facts with importance scoring, LRU eviction, iteration tracking.
- **ProjectInstructionsLoader.kt** - Loads project instructions from `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` with glob-based activation.
- **ToolResultCompression.kt** - Multi-level compression for tool results (FULL/DETAILED/SUMMARY); accepts optional `subtaskId` and forwards it to `DiffCompressor`; HTML-noise stripping for `<style>`/`<script>` blocks; safe head+tail truncation for sub-200-char budgets.
- **DiffCompressor.kt** - Content-aware diff body elision applied inside ` ```diff ` fences. Three paths: small diff passes through; pure-create keeps headers + 15-line head + 8-line tail with `memory(get_subtask_output, subtask_id="…")` recovery hint (literal id when supplied); large mixed preserves every `+`/`-` line and elides context (1 line per hunk run). Idempotent.
- **CompressionLevel.kt** - Enum for compression levels.
- **FileListingCompression.kt** - Deterministic structural compression of directory listings (per-directory counts plus sample names) instead of an LLM summary.

## `core/services/analysis/` - Code Analysis

- **LanguageAnalyzer.kt** - Interface for language analyzers; abstract `ExtensionLanguageAnalyzer` with parsing utilities; fallback `GenericLanguageAnalyzer`.
- **FileAnalysisModels.kt** - Data classes: `FileAnalysis`, `CodeElements`, `ClassElement`, `FunctionElement`, `FieldElement`, `ImportElement`, `FileContext`.
- **FileAnalyzerService.kt** - Orchestrates per-file analysis dispatching to language analyzers; caching with TTL, file hashing, batch analysis, async RAG indexing.
- **EmbeddingsService.kt** - Vector embedding generation with mutex-based thread safety; SHA-256 key caching (30-min TTL); provider/model config resolution.
- **JavaLanguageAnalyzer.kt** - Regex-based Java parsing: classes, interfaces, enums, methods, imports; infers purposes (Controller, Service, Repository); detects Spring patterns.
- **KotlinLanguageAnalyzer.kt** - Kotlin parsing: data/sealed classes, objects, functions, extension functions, suspend functions; detects coroutine patterns (Flow, launch, async).
- **PythonLanguageAnalyzer.kt** - Indentation-based Python parsing: classes, functions, decorators, docstrings, type hints; detects frameworks (FastAPI, Flask, Django, Pydantic).
- **TypeScriptLanguageAnalyzer.kt** - TypeScript/JavaScript parsing: classes, interfaces, type aliases, arrow functions; detects React components/hooks, JSDoc.
- **CppLanguageAnalyzer.kt** - C++ parsing: classes, structs, enums, namespaces, templates; detects STL, Smart Pointers, Threading, Boost, Qt.
- **CssLanguageAnalyzer.kt** - CSS/SCSS/SASS/LESS parsing: class selectors, IDs, keyframes, media queries, CSS variables; detects Tailwind, Grid, Flexbox.
- **HtmlLanguageAnalyzer.kt** - HTML parsing: titles, canvas, buttons, scripts; delegates JS analysis to `TypeScriptLanguageAnalyzer`.
- **GoLanguageAnalyzer.kt** - Regex-based Go parsing: package, imports, structs, interfaces, functions and methods.
- **RustLanguageAnalyzer.kt** - Regex-based Rust parsing: structs (incl. tuple structs), enums, traits, impls, functions, `use` imports.

### `core/services/analysis/project/` - Project-Level Analysis

- **ProjectAnalysisModels.kt** - Data classes for AST-based project analysis: statistics, code structure, dependency graphs, architectural layers, quality metrics.
- **FrameworkAnalyzer.kt** - Detects frameworks by file paths and build files; JVM (Spring, Ktor, Micronaut), frontend (React, Next.js, Vue), Python (Django, FastAPI), Node.js (Express, NestJS); confidence scoring.
- **RichProjectAnalysisEngine.kt** - Comprehensive project analyzer orchestrating full AST-based analysis with caching, fingerprinting, concurrency (mutexes); builds multi-faceted reports.

## `core/agents/` - Multi-Agent System

- **AgentSpec.kt** - Data classes for agent specifications (`AgentSpec` with name, task, dependencies, mode, model) and execution results (`AgentResult` with metrics).
- **MultiAgentRunner.kt** - Orchestrates parallel agent execution with DFS cycle detection in dependency graph; `supervisorScope` coroutines, `MutableStateFlow` for dependency wait.
- **MultiAgentTaskParser.kt** - Parses YAML multi-agent task definitions into `AgentSpec` objects via Kaml deserialization.
- **events/AgentEvent.kt** - Sealed interface with 14 event subtypes: lifecycle (Started/Completed/Failed), data exchange (Request/Response), approval, progress/stream, and per-turn trace events (TurnStarted/Ended, LLMCallCompleted, ToolCalled, StreamAborted).
- **events/AgentEventBus.kt** - Central event bus via `MutableSharedFlow` (200 replay buffer, DROP_OLDEST); optional persistence via repository; filtered subscriptions.
- **events/AgentMessageInbox.kt** - Per-session, per-agent queue of incoming data requests from the bus; drops a request once a matching response appears.
- **events/AgentInboxRegistry.kt** - Session-scoped lookup of agent inboxes keyed by (sessionId, agentName), shared by launchers, the turn loop and `answer_message`.

## `core/subagents/` - Subagent System

- **models/SubagentDefinition.kt** - Data class for complete subagent definition: name, description, system prompt, tool whitelist/blacklist, model aliases, skills, priority, context profile.
- **SubagentParser.kt** - Parses markdown files with YAML frontmatter into `SubagentDefinition`; regex extraction, SnakeYAML parsing, Claude Code tool name mapping.
- **SubagentRegistry.kt** - Discovery, caching (60s TTL), CRUD across three scopes (Built-in, User, Project); higher-priority scopes override; keyword-based search.
- **SubagentToolFilter.kt** - Three-tier tool filtering: mode-based, whitelist/blacklist strategy, security override; normalizes between Claude Code and Refio tool names.
- **SubagentRouter.kt** - API router for subagent ops: listing, invocation, CRUD, `!subagent-name prompt` command parsing.
- **models/SubagentInvocation.kt** - Tracks subagent invocation lifecycle: status (RUNNING/SUCCESS/FAILED/CANCELLED), timing, result; immutable state transitions.
- **BuiltinSubagentOverrides.kt** - Persists per-name enable/disable overrides of built-in subagents as one JSON blob in APP-scoped config.

## `core/workflow/` - Workflow Engine

- **WorkflowEventListener.kt** - Streaming/UI-notification callback interface used by the per-platform session bindings and `DefaultWorkflowStreamingListener` (the old intent-based plan/step orchestrator was removed).

## `core/session/` - Platform-Agnostic Session Layer

Shared by the IntelliJ plugin and the CLI TUI (previously plugin-only services).

- **CoreSessionService.kt** - Session execution service (~770 LOC): CHAT workflow, PLAN/AGENT turn loop, auto-naming, cost refresh and tool-call display helpers; IntelliJ's `SessionManager` and the TUI both delegate to it.
- **CoreSessionServiceFactory.kt** - Assembles every collaborator of `CoreSessionService` for embedders without IntelliJ wiring (CLI, tests).
- **SessionLifecycleService.kt** - Session lifecycle: creation, loading, switching, mode changes, model selection, execution mode toggling, state persistence. `updateSession(session, persistSettings = true)` - pass `false` from token-only refresh paths to skip 5 redundant `ConfigRepository` writes (~10 fewer DB writes per turn).
- **SessionStateManager.kt** - Reactive state holder: StateFlows for all session state (messages, subtasks, plans, model, toggles); thread-safe mutations.
- **MessageDispatcher.kt** - Loads/transforms DB messages into UI-ready `Message` objects; tool call parsing, content normalization, enrichment.
- **ExecutionMonitor.kt** - Execution lifecycle: streaming, step execution, approval messages, real-time UI listener integration.
- **SubtaskTracker.kt** - Subtask lifecycle: loading, approval, skipping, reordering, deletion; auto-execution in INTERACTIVE mode.
- **PromptStateTracker.kt** - Tracks pending context references, user input, context section token info; delegates to `SessionStateManager`.
- **ToolCallContentSanitizer.kt** - Removes tool call protocol blocks; extracts text payloads preserving plan JSON.
- **ToolMessageDisplayResolver.kt** - Resolves tool message display format (with optional raw output for summarized messages).
- **IncrementalToolCallStreamFilter.kt** - Filters tool call streams incrementally with boundary detection.
- **AbstractToolCallLifecycleListener.kt** - Shared bookkeeping for the "temporary message per tool call" pattern (toolCallId to temp message id, create/update/finalize hooks).
- **CoreMessageToolCallListener.kt** - `AbstractToolCallLifecycleListener` backed by `SessionStateManager`, rendering tool-call progress into the canonical `Message` stream.
- **DefaultWorkflowStreamingListener.kt** - `WorkflowEventListener` that materializes streaming output as one `Message` in `SessionStateManager`, throttled to one UI update per 500ms.
- **ExecutionStateController.kt** - Port for step execution lifecycle state (implemented by the plugin's `StepExecutionService`, stubbed in the CLI).
- **VfsRefresher.kt** - Port for refreshing the consumer's file view after core edits files (IntelliJ VFS in the plugin, `NoOp` in the TUI).

## `core/models/` - Domain Models

- **ChatModels.kt** - LLM parameters, chat request/response with context references, token costs, tool call support.
- **PromptsModels.kt** - Prompt DTOs, save/update requests for rules and commands, response wrappers.
- **ToolPermissionsModels.kt** - Tool permission DTOs with per-tool plan/agent mode settings (ASK/ON/OFF).
- **PlanningModels.kt** - Planning request/response with subtask details, cost breakdown, model/provider tracking.
- **StreamingModels.kt** - Multi-level streaming: chat (delta + usage), planning (phases), step planning (tool specs), step execution, summary generation.
- **CodeAnalysisDTO.kt** - Per-language code metrics (JavaScript, Python, HTML, CSS, TypeScript, Kotlin, Java).
- **ExecutionMetadataDTO.kt** - Execution context: timestamp, workspace path, agent/interactive mode flags.
- **MetaDataDTO.kt** - Project metadata: name, description, file count, complexity, main language.
- **StructureDTO.kt** - Project structure: total files, type distribution, top-level items, directory depth.
- **WorkspaceDTO.kt** - Workspace paths and context: absolute path, task/project IDs, project name.
- **SummaryDTO.kt** - Project summary: type, complexity, architecture notes, key capabilities, entry points.
- **DependenciesDTO.kt** - Multi-language dependencies plus package managers and config files.
- **ExecutedStepDTO.kt** - Historical executed step: subtask ID, file, tool, params, result, summary.
- **HelperDTOs.kt** - Supporting DTOs: `CurrentTaskDTO`, `SubtaskDTO`, `ConversationMessageDTO`, `CodeFragmentDTO`, `ResolvedContextDTO`.
- **ProjectContextDTO.kt** - Master aggregate DTO: metadata, summary, structure, dependencies, analysis, task, subtasks, conversation, steps, RAG, context, MCP.

## `core/logging/` - Logging

- **DualLogger.kt** - Dual logging to SLF4J and optional `LogSink` (UI panel); automatic sensitive data redaction; API request/response/error logging to database.
- **LogSink.kt** - Platform-agnostic log sink interface (debug/info/warn/error); allows IntelliJ or CLI implementations.
- **LogSinkRegistry.kt** - Global `LogSink` singleton registry; graceful degradation to SLF4J-only when not registered.

## `core/prompts/` - Prompt Engine

- **PromptTemplate.kt** - Template engine for `{{variable}}` substitution with extraction, validation, rendering; throws on missing variables.
- **PromptDefinition.kt** - Prompt definition model (name, SYSTEM/TOOL content type, description, content) loaded from markdown files.
- **PromptParser.kt** - Parses prompt definition `.md` files with YAML frontmatter; throws `PromptParseException` on invalid input.
- **PromptRegistry.kt** - Prompt definitions resolved Project (`.refio/prompts/`) > User (`~/.refio/prompts/`) > built-in resources.
- **ToolDescriptionBuilder.kt** - Builds the tool description text for prompts per mode (read-only set for CHAT/PLAN, full set for AGENT), filtered by permissions and grouped into sections.

## `core/registry/` - File-Based Registries

- **FileBasedRegistry.kt** - Abstract base for registries loaded from markdown files in three layers (built-in resources, `~/.refio/`, project `.refio/`); higher layers override by name.
- **DefinitionScope.kt** - Enum of definition layers: BUILTIN, USER, PROJECT.

## `core/security/` - Security

- **SecureLogger.kt** - Redacts API keys, tokens, passwords using regex patterns (OpenAI/Anthropic/Google); `redactMap`, `redactValue`, `redactAndTruncate`.
- **NetworkPolicy.kt** - Single gate for outbound network access (web tools, embedding providers), so `general.no_egress_enabled` blocks web tools too, not only cloud LLMs.
- **UrlPolicy.kt** - SSRF guard for outbound tool URLs: rejects loopback, private, link-local and any-local addresses (loopback opt-in for e2e fixtures; multicast never).
- **RegexSafetyValidator.kt** - Rejects user/model regex patterns prone to catastrophic backtracking (length cap plus dangerous constructs).
- **ExecutionSandbox.kt** - States what a spawned process is allowed to do, as a seam where a real isolation backend can be plugged in.
- **HostExecutionSandbox.kt** - Current backend: runs processes on the host without isolation; `ExecutionIsolationMode` AUTO (warn once) / REQUIRED (refuse to run) / OFF.

## `core/utils/` - Utilities

- **GsonInstance.kt** - Shared Gson singletons (`gson` + `prettyGson`) with HTML escaping disabled.
- **ProjectIdGenerator.kt** - Deterministic SHA-256 project IDs from absolute paths.
- **AiIgnoreMatcher.kt** - `.aiignore` file parser implementing gitignore-style glob/negation/anchoring pattern matching.
- **NameSuggestion.kt** - Suggests the closest known name (case-insensitive Levenshtein) for a misspelled tool or subagent name so the model can self-correct.

## `core/project/` - Project Abstraction

- **ProjectHandle.kt** - Platform-agnostic project interface: id, name, rootPath, platformProject.
- **StandaloneProjectHandle.kt** - `ProjectHandle` for CLI mode; deterministic ID via `ProjectIdGenerator`.

## `api/` - Shared API Models

- **StreamProgressFormat.kt** - Formats live progress of streaming tool output (e.g. "1,234 chars") identically for the plugin and the TUI.
- **models/ExecutionMode.kt** - Enum: INTERACTIVE (step-by-step approval) and AUTO (autonomous execution).
- **models/UserContextMetadata.kt** - Context references and summary alongside user messages with JSON serialization.
- **models/CodeSnippet.kt** - Editor-selected code with filepath, line numbers, language; converts to `ContextReference`.
- **models/Session.kt** - Unified model: `TaskMode`, `TaskStatus` enums; `Session`, `Message` (with metrics/thinking/tool calls), `Subtask`, `ContextReference` (with builder methods), `ContextType`.
- **models/SlashPrompt.kt** - Slash prompt model (reusable `/name` prompt template, stored as `SLASH_PROMPT`) plus ~30 built-in prompts by category (analysis, improvement, testing, security, translation, etc.).
- **models/AgentGrouping.kt** - Pure function deciding which transcript messages start a new subagent group and get an "Agent: name" header.
- **models/MessageRenderHash.kt** - Pure render-cache hashing of a `Message` (full hash and a hash without content, so a growing stream can patch its counter instead of rebuilding).

## `services/` - IntelliJ Plugin Services

- **core/CoreConnectionManager.kt** - App-level service managing embedded Kotlin core connection, per-project routers, database init, config loading.
- **session/SessionManager.kt** - Project-level service orchestrating session operations, message handling and component coordination (lazy loading); execution is delegated to core `CoreSessionService`. Caches `maxContextWindow` as a `StateFlow` (background-refreshed on session/model change) so EDT readers don't hit SQLite; expose `refreshMaxContextWindow()` for hooks after `MAX_CONTEXT_SIZE` save.
- **execution/StepExecutionService.kt** - Project-level execution state and progress tracking for PLAN/AGENT modes.
- **session/IntelliJUIAdapter.kt** - IntelliJ-specific `UIAdapter` bridging core UI operations to IDE services.
- **logging/PluginLogger.kt** - App-level logging: StateFlow of log entries (max 1000) with HTTP/API call logging.
- **logging/PluginLoggerSink.kt** - IntelliJ-specific `LogSink` bridging core logging to `PluginLogger` for UI visibility.
- **notification/NotificationService.kt** - IDE notification balloons (info, warning, error) with RAG unavailability handling.
- **rag/RagProgressService.kt** - RAG indexing/embedding progress via StateFlows for reactive UI updates.
- **rag/BackgroundIndexingTask.kt** - Background RAG indexing with progress reporting, cancellation, notifications.
- **session/StatusBarIntegration.kt** - StatusBar reference holder for status updates from services.
- **project/IntelliJProjectHandle.kt** - `ProjectHandle` wrapper for IntelliJ `Project` with concrete IDE accessor.
- **project/IntelliJVfsRefresher.kt** - Plugin implementation of core `VfsRefresher`: refreshes the IntelliJ VFS after core tools edit files.
- **project/SafeVfsAccess.kt** - Guarded VFS refresh (project root or a single path) that skips projects that cannot be refreshed and tolerates the refresh race during IDE shutdown.
- **core/PsiStructuralRefactorer.kt** - IDE-backed `StructuralRefactorer`: rename through IntelliJ refactoring, find-usages through the word index; falls back to the text engine when the symbol cannot be resolved.

Also in the plugin module, under the `core` package path:
- **core/services/ProjectStartupActivity.kt** - Project-open activity that analyzes the project structure in the background and caches the result.

## `actions/` - IDE Actions

- **ReindexRagAction.kt** - Manual RAG reindexing trigger with user confirmation; queues `BackgroundIndexingTask`.
- **AddCodeToCurrentSessionAction.kt** - Ctrl+Shift+J: adds selected code snippet to current session.
- **AddCodeToNewSessionAction.kt** - Ctrl+J: creates new session and adds selected code snippet.
- **ToolWindowAction.kt** - Base class for tool window actions; includes concrete implementations: `NewSessionToolWindowAction`, `ShowHistoryToolWindowAction`, `ShowSettingsToolWindowAction`, `ShowHelpToolWindowAction`.
- **NewSessionAction.kt** - Toolbar action: shows the Refio tool window and creates a new session.
- **ShowHistoryAction.kt** - Toolbar action: shows the Refio tool window with the history panel.
- **ShowSettingsAction.kt** - Toolbar action: shows the Refio tool window with the settings view.
- **ShowHelpAction.kt** - Toolbar action: shows the Refio tool window with help information.
- **RefioSlashPromptIntentionAction.kt** - Editor intention ("Refio: /name") that runs a slash prompt on the current selection.

## `startup/` - Plugin Startup

- **RagIndexingStartup.kt** - `ProjectActivity` hook: queues automatic RAG indexing on project load if enabled.
- **RefioIntentionStartup.kt** - Registers the slash prompt intention once per IDE session.
- **ProjectCloseListener.kt** - Releases a project's core router and its MCP child processes when the project closes.

## `ui/` - IntelliJ Swing UI

### Main Structure
- **RefioToolWindowFactory.kt** - Factory creating tool window with title bar actions (New Session, History, Settings, Help).
- **RefioMainPanel.kt** - Thin wrapper that shows an "Initializing..." placeholder while `SessionManager` is created off the EDT, then swaps in `RefioContentPanel`.
- **RefioContentPanel.kt** - The actual tool window content: navigation rail with screen cards (CardLayout), now-running bar, prompt input, status bar.
- **RefioScreen.kt** - Enum of screens reachable from the rail (Chat, Execution, Context, RAG, Agents, Logs, Debug, API); some are visible only with Advanced View.
- **rail/RefioRail.kt** - Vertical icon strip with one toggle per `RefioScreen`, replacing the old horizontal tab row.

### Chat Components (`ui/components/chat/`)
- **ChatView.kt** - Main chat view: scrollable message list with `GridBagLayout` for vertical stacking (weightx=1.0, gridwidth=REMAINDER); `CachedMessagePanel` caches rendered bubbles; Flow-based state management.
- **PromptInputPanel.kt** - Input panel with `GridBagLayout`: row 0 for input container (snippets + context + editor), row 1 for controls (mode/model/context buttons + send/stop). Execution mode, thinking, and no-egress toggles are *not* here - they live in **Settings → General**. `GradientBorderPanel` wrapper with custom gradient rendering.
- **CodeBlockPanel.kt** - Code display with `BorderLayout`: header (NORTH) with `FlowLayout` for filename+buttons, `JBScrollPane` (CENTER), footer (SOUTH) for expand/collapse; dynamic height via `updatePanelSize()`.
- **EditableUserBubble.kt** - Read/edit mode toggle with `BorderLayout`; edit mode replaces content via `removeAll()` + `add()` + `revalidate()/repaint()`.
- **MetricsView.kt** - Token/cost/failed step chips with `GridBagLayout`.
- **PlanApprovalDialog.kt** - Multi-step plan approval dialog for PLAN/AGENT modes.
- **ChangesDialog.kt** - File changes preview dialog.
- **ContentSegmentParser.kt** - Parses message content into renderable segments.
- **FilePathDetector.kt** - Extracts file paths from text.
- **FileNavigationService.kt** - Navigates to files mentioned in chat.
- **ChatConversationExportUtil.kt** - Exports conversations to various formats.
- **ContentSegment.kt** - Typed segments of assistant content (Thinking, Code, Json, Markdown) produced by `ContentSegmentParser`.
- **CodeBlock.kt** - Code block extracted from markdown, with language and optional file path (```` ```language:filepath ````).
- **ChatBubbleModels.kt** - Internal data classes for bubble metadata (questions, code changes, execution and conversation summaries).
- **ConversationToolbarFactory.kt** - Builds the conversation toolbar above the transcript.
- **SessionStatsBar.kt** - Compact one-line session stats (duration, generation time, tokens, cost) under the conversation toolbar.
- **GradientBorderPanel.kt** - Panel with an animated gradient border shown while a request is loading; wraps the prompt input.
- **ToolApprovalPanel.kt** - Inline panel for tools needing approval (ASK): tool name, arguments, Approve / Trust / Reject.
- **toolcall/ToolCallRow.kt** - One tool call as a 24px transcript line that expands on click into an IDE diff or raw output, built lazily.
- **toolcall/ToolCallRowView.kt** - Precomputed view model of a collapsed tool-call row (status, name, target, diff size, duration).

### Chat Bubble System (`ui/components/chat/bubble/`) - Swing Layout Details

The bubble system uses a multi-layout composition pattern. **This is historically problematic - layout issues are common here.**

- **BaseBubbleRenderer.kt** - Base class using `GridBagLayout` for outer containers (weightx=1.0, fill=HORIZONTAL) and `BoxLayout.Y_AXIS` for mixed content panels. Critical workarounds: all components need `alignmentX = Component.LEFT_ALIGNMENT` in BoxLayout.Y_AXIS containers; `maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)` required for expansion; `Box.createVerticalStrut()` for consistent spacing.
- **AssistantBubbleRenderer.kt** - Routes message types to specialized creators; uses `GridBagLayout` with row counters (`var row = 0; gridy = row++`) for tool bubbles, `BoxLayout.Y_AXIS` for question/approval containers, `FlowLayout.LEFT` for header/button rows.
- **UserBubbleRenderer.kt** - `BoxLayout.Y_AXIS` for outer container and inner message block; uses `isOpaque=false` throughout.
- **ToolBubbleRenderer.kt** - Two parallel methods (regular/code result) both using `GridBagLayout` with identical row-stacking `addRow()` pattern.
- **OtherBubbleRenderer.kt** - Delegates to `createUniversalBubble` (GridBagLayout).
- **ChatMessageBubbleRouter.kt** - Pure routing logic dispatching to appropriate renderer.
- **BubbleComponentFactory.kt** - Core rendering engine: `FlowLayout(LEFT, 8, 4)` for headers, `BoxLayout.Y_AXIS` for content stacking via `FlatMessageBlock`, `BorderLayout` for markdown/thinking panels. Dynamic expand/collapse via `container.revalidate()/repaint()`. Context badge uses `BoxLayout.Y_AXIS` for header + details.
- **FlatMessageBlock.kt** - Custom `JBPanel` with compound border (1px matte + padding 4,8,4,8); no layout manager set (defaults to FlowLayout) - children set their own.
- **MarkdownRenderingService.kt** - Markdown parsing + HTML rendering; `installResponsiveEditorSizing` adds `ComponentListener` for dynamic width recalculation via `setSize(editorWidth, Short.MAX_VALUE.toInt())`.
- **MessageMetadataExtractor.kt** - Pure JSON parsing utility; no layout.

**Bubble rendering pipeline**: ChatView → ChatMessageBubbleRouter → (User|Assistant|Tool|Other)BubbleRenderer → BaseBubbleRenderer.createOuterPanel(GridBagLayout) → BubbleComponentFactory creates headers (FlowLayout), content (BoxLayout.Y_AXIS + FlatMessageBlock), actions (FlowLayout.RIGHT) → addToOuter() composes final bubble.

**Key Swing layout rules for bubbles**:
1. Outer container: always `GridBagLayout` with `weightx=1.0`, `fill=HORIZONTAL`
2. Vertical stacking: `BoxLayout.Y_AXIS` - every child MUST set `alignmentX = LEFT_ALIGNMENT`
3. Expansion: set `maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)` on components that should expand
4. Spacing: use `Box.createVerticalStrut()` not borders/insets
5. Dynamic content: `removeAll()` + `add()` + `revalidate()` + `repaint()` pattern
6. Markdown panels: need responsive sizing via `ComponentListener` on parent

### Autocomplete Components
- **AutocompletePopup.kt** - Generic autocomplete dropdown popup.
- **ContextAutocompleteItem.kt** - Item model for @context suggestions.
- **PromptAutocompleteItem.kt** - Item model for /prompt (slash prompt) suggestions.
- **SubagentAutocompleteItem.kt** - Item model for !subagent suggestions.
- **ContextValidator.kt** - Validates context reference validity.

### Input Components
- **InputPanelContainer.kt** - Manages prompt input layout with editor and controls.
- **CodeSnippetCard.kt** - Visual card for code snippet in input panel.
- **SnippetsContainer.kt** - Displays added code snippets.

### Other Panels
- **ContextPanel.kt** - Project context sections updated in real-time during execution.
- **CollapsibleContextSection.kt** - Collapsible section within context panel.
- **HistoryPanel.kt** - Previous sessions list for quick access and switching.
- **history/SessionRow.kt** - Precomputed flat view model for one row of the session list.
- **LogsPanel.kt** - Debug logs from PluginLogger.
- **RagViewPanel.kt** - RAG indexing/embedding progress and controls.
- **StepsQueueView.kt** - Execution steps queue with status and controls.
- **steps/PlanSummaryPanel.kt** - Header above the step list summarizing how the plan as a whole went (failures, duration).
- **steps/StepRowView.kt** - One execution step reduced to what a single 26px row shows.
- **steps/StepListRenderer.kt** - One-line list renderer for steps: status icon, number, tool, description, right-aligned duration.
- **debug/DebugPanel.kt** - Debug screen showing internal plugin state, usage analytics and API data (~970 LOC).
- **context/ContextInspectorPanel.kt** - Context Inspector: what entered the LLM prompt, token usage per section and what was dropped.
- **context/ContextInspectorViewModel.kt** - Observes the `PromptSnapshot` from `SessionManager` for the Context Inspector.
- **agents/AgentExecutionPanel.kt** - Agent execution screen with Trace and Graph tabs (single session or multi-agent run).
- **agents/SessionTracePanel.kt** - Hierarchical session trace (turns, LLM calls, tool calls, nested subagents) with tokens, cost and timing.
- **agents/AgentGraphPanel.kt** - Agent execution DAG as a vertical list of nodes indented by depth.
- **agents/AgentNodeComponent.kt** - One agent node: name, status, iteration count, duration.
- **agents/EventTimelinePanel.kt** - Table timeline of agent events with per-session totals from `GlobalMetrics`.
- **execution/NowRunningBar.kt** - Sticky bar above the transcript while a turn runs: current tool, step, elapsed time, stop button.
- **execution/NowRunningState.kt** - Pure mapping from turn state to what the now-running bar shows.
- **common/PromptDialog.kt** - Reusable multiline prompt dialog (add steps, re-plan).
- **ApiLogsPanel.kt** - API call logs with timestamps and durations.
- **ApiLogDetailsDialog.kt** - Detailed API call information dialog.
- **LLMPromptViewerDialog.kt** - Shows actual LLM prompt sent to model.
- **StatusBar.kt** - Operation progress, token usage, cost.

### Settings Panels
- **SettingsView.kt** - Main settings panel coordinating all tabs.
- **GeneralSettingsPanel.kt** - General preferences: format markdown, streaming, advanced view, thinking mode, no-egress, execution mode (AUTO/INTERACTIVE). All six write to `general.*` config keys.
- **AdvancedSettingsPanel.kt** - Advanced config options: read-only mode, timeouts, size limits, auto-optimize threshold. (No-egress toggle moved to General.)
- **ModelsSettingsPanel.kt** - Model selection/config.
- **ProvidersSettingsPanel.kt** - LLM provider config and API keys; per-provider context window and the custom-provider raw-request toggle.
- **NumericDropdownValue.kt** - Picks what a numeric dropdown shows for a configured value outside its option set (largest option not exceeding it), so a hand-set context window is not misrepresented.
- **ContextSizeOptions.kt** - Context-window sizes offered per provider (Ollama / LM Studio / custom OpenAI-compatible), kept as independent sets so one runtime's limit does not constrain the others.
- **history/SessionListRenderer.kt** - Session row in two shapes: stacked while docked, metadata beside the title once the row passes the wide boundary; title length derived from the row width instead of a fixed character count.
- **ContextSettingsPanel.kt** - Context building and RAG config.
- **PromptsSettingsPanel.kt** - Prompt template management.
- **ToolsSettingsPanel.kt** - Tool registry and config.
- **SubagentSettingsPanel.kt** - Subagent config.
- **DocsSettingsPanel.kt** - Documentation integration.
- **MCPSettingsPanel.kt** - MCP settings.
- **BuiltinPromptEditDialog.kt** - Edit built-in prompt templates.
- **SystemPromptEditDialog.kt** - Edit system prompt.
- **PromptEditDialog.kt** - Add/edit a slash prompt.
- **RuleEditDialog.kt** - Edit context rules.
- **ThemeSettingsPanel.kt** - Visual preview of `LCATheme` constants and standard components for checking dark/light compatibility.
- **SettingsLayout.kt** - Shared layout support for settings pages in a narrow docked tool window (minimum widths, scrolling).

### Theme
- **LCATheme.kt** - Theme definitions: colors, fonts, borders, styling constants.
- **ContextSectionColorPalette.kt** - Color palette for context section visualization.

### Completion
- **RefioCompletionContributor.kt** - IntelliJ completion provider for slash commands and context.

### Listeners
- **SwingWorkflowListener.kt** - Bridges core workflow events to Swing UI updates.

## `cli/` - CLI / TUI

### Entry Points
- **main.kt** - `RefioCommand` (Clikt): handles `--project`, `--mode`, `--headless`, `--prompt`, `--multi-agent`, `--config`, `--print-config` and more; implements `runHeadless()`, `runMultiAgent()`, `runMcpProbe()`, `runPrintConfig()` and `runGate()`.
- **HeadlessResult.kt** - Exit-code contract for headless and multi-agent runs: 0 only for a delivered (SUCCESS) turn, non-zero otherwise.
- **HeadlessTurnListener.kt** - Mirrors headless turn/tool progress to the log file and to stderr, keeping stdout for `run.json`.
- **AutoApproveListener.kt** - Headless auto-approval: resolves pending tool approvals via `AutoApprover` so a run never blocks on the approval timeout.
- **McpProbe.kt** - Connects the declared MCP servers and reports what they expose without any LLM call, to tell a dead server from a model that did not call its tool.
- **StandaloneCoreBootstrap.kt** - Two-tier init (app + project routers) for non-IntelliJ; SQLite at `~/.refio/data/database.sqlite`; registers standalone context providers and optional MCP.

### TUI Application
- **tui/TuiApp.kt** - Main TUI entry: interactive (raw mode) vs simple (line-based) fallback; alternate screen buffer, resize detection, input/render loops.
- **tui/TuiScreenManager.kt** - Manages overlay screens (History, Settings) on top of main content.
- **tui/TuiTabBar.kt** - 8-tab header bar with F-key labels (F1 Help, F2-F7 tabs, F8 Files) and ANSI colors.

### TUI Components
- **tui/components/TuiCollapsible.kt** - Expandable section with arrow indicator (▼/▶).
- **tui/components/TuiDialog.kt** - Modal confirmation dialog with bordered box and Y/N prompts.
- **tui/components/TuiMessageBubble.kt** - Message renderer with role-based routing; parses code blocks, thinking tags, JSON, markdown.
- **tui/components/TuiProgressBar.kt** - Text-based `[████░░░░ 45%]` with color styling.
- **tui/components/TuiPromptInput.kt** - Prompt input showing mode, model, execution toggles, current input.
- **tui/components/TuiSpinner.kt** - Braille Unicode spinner with 10-frame animation.
- **tui/components/TuiStatusBar.kt** - Bottom bar: mode, model, streaming, cost/tokens, quit hint.
- **tui/components/TuiTable.kt** - Generic table renderer via Mordant with scrolling.

### TUI Input
- **tui/input/TuiCompleter.kt** - JLine3 completer for @mentions, /commands, !subagents.
- **tui/input/TuiContextValidator.kt** - Validates @file/@folder references; 100KB file limit, depth 3.
- **tui/input/TuiInputHandler.kt** - Dual-mode: raw (F-keys, Ctrl) for TTY, line mode (stdin) for non-interactive; slash command handlers.
- **tui/input/TuiKeybindings.kt** - Key bindings: escape sequences, F-keys, control chars → `TuiAction` sealed class.

### TUI Rendering
- **tui/rendering/TuiColors.kt** - ANSI palette: role colors (user=brightGreen, assistant=brightCyan), status, log levels, per-agent color indices.
- **tui/rendering/TuiContentParser.kt** - Parses assistant messages into segments (thinking, code, JSON, markdown) via regex.
- **tui/rendering/TuiContentSegment.kt** - Sealed interface: Thinking, Code (with language/path), Json, Markdown.
- **tui/rendering/TuiLayout.kt** - Split-pane dimensions (55%/45%); MIN_WIDTH=80, MIN_HEIGHT=24.
- **tui/rendering/TuiMarkdown.kt** - Markdown rendering wrapper using Mordant's widget.
- **tui/rendering/TuiRenderBuffer.kt** - Fixed-width line buffer: ANSI-aware measurement, truncation, word-wrapping, side-by-side merging.
- **tui/rendering/TuiRenderer.kt** - Full-screen framebuffer with split-pane compositor; overlays, tab bar assembly, atomic screen flushes.
- **tui/rendering/TuiScreenBuffer.kt** - Full-screen framebuffer: ANSI-padded rows, overlay splicing, atomic flush with clear-to-EOL, cursor positioning.

### TUI Screens
- **tui/screens/TuiHistoryScreen.kt** - Session history table (ID, Mode, Status, Date, Tokens, Cost, Name).
- **tui/screens/TuiHelpScreen.kt** - Scrollable keybindings reference and usage guide (F1 to open/close).
- **tui/screens/TuiSettingsScreen.kt** - 11-tab settings screen with config read/write via `TuiViewModel`.

### TUI State
- **tui/state/TuiChatMessageMapper.kt** - Maps `AgentEvent` to `TuiChatMessage` with per-agent color index assignment.
- **tui/state/TuiLogSink.kt** - `LogSink` capturing log messages into TUI state for Logs tab (max 500 entries).
- **tui/state/TuiState.kt** - Unified state: screen, activeTab, messages, agents, steps, context, logs, approvals, sessions, mode, model, toggles.
- **tui/state/TuiViewModel.kt** - Coordinator ViewModel (~1590 LOC): creates and wires 3 sub-VMs, manages tabs/screens/settings, lifecycle; exposes `chat`, `session`, `obs` sub-VMs.
- **tui/state/TuiChatViewModel.kt** - Chat sub-VM (~980 LOC): messages, input, autocomplete, approvals, slash commands, sendMessage.
- **tui/state/TuiSessionViewModel.kt** - Session sub-VM (~690 LOC): session history, execution/steps, model selector, mode cycling.
- **tui/state/TuiObservabilityViewModel.kt** - Observability sub-VM (~1020 LOC): RAG, logs, debug, API logs, file browser, content viewer.
- **tui/state/TuiToolCallListener.kt** - `AbstractToolCallLifecycleListener` variant that renders tool-call lifecycle as `TuiChatMessage` entries.
- **tui/state/TuiWorkflowListener.kt** - `WorkflowEventListener` with 500ms debounce on stream chunks; accumulates content, marks steps complete, extracts metrics.

### TUI Views
- **tui/views/TuiChatView.kt** - Messages (bottom-aligned auto-scroll) and prompt input.
- **tui/views/TuiStepsView.kt** - Subtasks with status badges (NEW/PENDING/RUNNING/COMPLETED/FAILED).
- **tui/views/TuiContextView.kt** - Token usage: colored segmented bar with section legend and category-colored dots.
- **tui/views/TuiDebugView.kt** - Session state display with core health indicator.
- **tui/views/TuiLogsView.kt** - Scrollable color-coded log list (DEBUG=gray, INFO=white, WARN=yellow, ERROR=red).
- **tui/views/TuiRagView.kt** - RAG stats: indexed files, chunks, embeddings with completion percentage.
- **tui/views/TuiApiLogsView.kt** - API call table: timestamp, provider, model, tokens, cost.
- **tui/views/TuiAgentFlowView.kt** - ASCII DAG of a multi-agent session: agent boxes with dependency arrows, status and cost.
- **tui/views/TuiFilesView.kt** - File browser tab (Midnight Commander style): directory listing, navigation, file info, quick actions.
- **tui/views/TuiFileViewerOverlay.kt** - Full-screen content viewer overlay for files, log details and API payloads (scroll, line numbers, wrap, copy, add as context).
