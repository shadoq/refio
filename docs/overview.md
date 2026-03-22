# Refio - Technical Architecture Overview

> **Last Updated:** 2026-02-11
> **Version:** 0.0.1
> **Status:** Active Development

This document provides a comprehensive technical overview of Refio - a local-first AI coding assistant for IntelliJ IDEA.

---

## Table of Contents

1. [Project Philosophy](#1-project-philosophy)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Execution Modes](#3-execution-modes)
4. [AgentTurnLoop](#4-agentturnloop)
5. [Context System](#5-context-system)
6. [RAG System](#6-rag-system)
7. [Tools System](#7-tools-system)
8. [LLM Integration](#8-llm-integration)
9. [MCP Protocol](#9-mcp-protocol)
10. [Subagents System](#10-subagents-system)
11. [Session Management](#11-session-management)
12. [Database Schema](#12-database-schema)
13. [Security Model](#13-security-model)
14. [Key Data Flows](#14-key-data-flows)

---

## 1. Project Philosophy

### Core Principle: Minimize LLM Context

Unlike tools that send entire codebases to LLMs, Refio uses **selective context injection**:

```
Traditional Approach:          Refio Approach:
┌─────────────────────┐       ┌─────────────────────┐
│ Entire Codebase     │       │ Semantic Search     │
│ + Full History      │  →→→  │ + Relevant Chunks   │
│ + All Dependencies  │       │ + Smart Summaries   │
│ = Massive Context   │       │ = Focused Context   │
└─────────────────────┘       └─────────────────────┘
       ~500K tokens                  ~20K tokens
```

**Benefits:**
- Lower API costs (50-70% token reduction)
- Faster responses
- Works with smaller context windows (local models)
- Privacy-first (no-egress mode)

### Design Goals

1. **Local-First:** Full functionality with Ollama, optional cloud providers
2. **IDE-Native:** Native IntelliJ components, no webview
3. **Modular:** Clean separation of concerns, testable components
4. **Secure:** Path sandboxing, tool permissions, secret redaction
5. **Cost-Aware:** Designed to minimize usage costs

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         IntelliJ IDEA Plugin                            │
├─────────────────────────────────────────────────────────────────────────┤
│  UI Layer (Swing)                                                       │
│  ├── RefioToolWindowFactory → RefioMainPanel → ChatView                 │
│  ├── PromptInputPanel (with @ autocomplete)                             │
│  └── Settings Panels (12+)                                              │
├─────────────────────────────────────────────────────────────────────────┤
│  Service Layer (Project-Scoped)                                         │
│  ├── SessionManager (facade with 6 components)                          │
│  │   ├── SessionStateManager (11 StateFlows for reactive UI)            │
│  │   ├── SessionLifecycleService (create/switch/load)                   │
│  │   ├── MessageDispatcher (mode-specific routing)                      │
│  │   └── SubtaskTracker (subtask CRUD)                                  │
│  └── CoreConnectionManager (router factory)                             │
├─────────────────────────────────────────────────────────────────────────┤
│  Execution Layer                                                        │
│  ├── CHAT mode → WorkflowOrchestrator → ChatExecutor                    │
│  │   ├── IntentRouter (fast paths: subagent, answer question)           │
│  │   └── WorkflowEventListener (streaming/progress callbacks)           │
│  └── PLAN/AGENT/SUBAGENT profile → AgentTurnLoop (Codex CLI-style)      │
│      ├── TurnEventListener (progress callbacks)                         │
│      └── ToolResultSummarizer (context optimization)                    │
├─────────────────────────────────────────────────────────────────────────┤
│  Core Layer (In-Process API)                                            │
│  ├── CoreApiRouter (facade + 9 domain routers)                          │
│  │   ├── ChatRouter → ChatService                                       │
│  │   ├── TaskRouter, SubtaskRouter                                      │
│  │   ├── RagRouter → RagSearchService                                   │
│  │   ├── ToolRouter, PromptsRouter, ApiLogsRouter                       │
│  │   └── ContextService (dynamic context building)                      │
│  └── ContextProviderRegistry (14 providers + MCP dynamic)               │
├─────────────────────────────────────────────────────────────────────────┤
│  Infrastructure Layer                                                   │
│  ├── LLMClient (unified) → 6 provider adapters                          │
│  ├── ToolRegistry → 12 registered tools (6 read-only, 6 write)          │
│  ├── MCPManager → MCP server lifecycle (STDIO/HTTP)                     │
│  ├── EmbeddingsService → Ollama/OpenAI embeddings                       │
│  └── DatabaseFactory → SQLite (WAL) + Exposed ORM                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### Package Structure

```
src/main/kotlin/pl/jclab/refio/
├── core/                     # Embedded core (no IDE dependencies)
│   ├── api/                  # Router layer (9 domain routers)
│   ├── context/              # Context providers + MCP
│   │   ├── providers/        # 14 built-in providers
│   │   └── mcp/              # Model Context Protocol
│   ├── db/                   # Database tables & repositories
│   ├── llm/                  # LLM integration (6 adapters)
│   ├── services/             # Core services (RAG, context, analysis)
│   │   └── analysis/         # Language analyzers
│   ├── subagents/            # Subagent system
│   ├── tools/                # Tool system (12 registered implementations)
│   │   ├── implementations/
│   │   └── security/
│   └── prompts/              # Prompt templates
├── services/                 # Plugin services (project-scoped)
│   └── session/              # SessionManager (6 components)
└── ui/                       # IntelliJ UI components
    ├── toolwindow/           # Tool window factory
    ├── components/           # Chat, toolbar, autocomplete
    └── settings/             # 12+ settings panels
```

### Prompt TPL Variables (PromptInputPanel)

`PromptInputPanel` resolves `{{...}}` placeholders before sending the final prompt to the backend.
This applies to both direct user input and slash-command-expanded templates.

Supported placeholders:

- `{{MODEL_ID}}` -> selected model in filesystem-safe `provider_model` format
- `{{MODEL_RAW}}` -> selected model in raw `provider/model` format
- `{{PROVIDER}}` -> provider id (filesystem-safe)
- `{{MODE}}` -> current mode (`CHAT`, `PLAN`, `AGENT`)
- `{{EXECUTION_MODE}}` -> current execution mode (`AUTO`, `INTERACTIVE`)
- `{{TIMESTAMP}}` -> local timestamp (`yyyyMMdd_HHmmss`)
- `{{DATE}}` -> local date (`yyyy-MM-dd`)
- `{{TIME}}` -> local time (`HHmmss`)
- `{{PROJECT_NAME}}` -> IntelliJ project name (filesystem-safe)
- `{{SESSION_ID}}` -> active session id (filesystem-safe)

Example:

```text
Create file reports/{{MODEL_ID}}_run_{{TIMESTAMP}}.md
```

Unknown placeholders remain unchanged.

---

## 3. Execution Modes

### Mode Comparison

| Mode | Executor | Tools Available | Subtasks | Use Case |
|------|----------|-----------------|----------|----------|
| **CHAT** | ChatExecutor | None | No | Conversation, explanations |
| **PLAN** | AgentTurnLoop | READ_ONLY (6) | Yes | Code review, analysis |
| **AGENT** | AgentTurnLoop | ALL (12) | Yes | Code generation, refactoring |
| **SUBAGENT** | AgentTurnLoop (`runProfile=SUBAGENT`) | Profile-filtered | Yes | Specialized delegated tasks |

`ToolRegistry` has 12 registered tools; `run_terminal_command` is enabled by default in AGENT mode and restricted by terminal whitelist rules.
`invoke_subagent` is enabled by default in PLAN and AGENT, and is displayed as `subagent` in Tools Settings.

### CHAT Mode Flow

```
User Input
    ↓
SessionManager.sendMessage()
    ↓
WorkflowOrchestrator.execute()
    ├→ IntentRouter.determineIntent()
    │   ├─ Fast Path 1: Subagent (!name pattern)
    │   └─ Fallback: Chat intent
    ├→ ChatExecutor.execute()
    │   ├→ ChatService.chat()
    │   └→ LLMClient.complete()
    └→ StreamChunk callbacks to UI
    ↓
MessageDispatcher.loadMessages()
    ↓
UI Update via StateFlow
```

### PLAN/AGENT/SUBAGENT Profile Flow

```
User Input
    ↓
SessionManager.sendMessage()
    ↓
AgentTurnLoop.runTurn()
    ├─ Save user message to history
    ├─ LOOP (max 25 iterations):
    │   ├→ Build prompt (history + context + tool descriptions)
    │   ├→ Call LLM
    │   ├→ If tool calls in response:
    │   │   ├─ Create subtasks (PENDING)
    │   │   ├─ For each tool:
    │   │   │   ├─ Update subtask (RUNNING)
    │   │   │   ├─ Execute tool
    │   │   │   ├─ Summarize result (last tool = raw)
    │   │   │   └─ Update subtask (SUCCESS/FAILED)
    │   │   └─ Continue loop
    │   └→ If text response: Exit loop
    └─ Save final response
    ↓
TurnResult (success, response, iterations, tokens, cost)
    ↓
UI Update via StateFlow
```

---

## 4. AgentTurnLoop

**Location:** `core/services/AgentTurnLoop.kt`

The primary execution engine for PLAN, AGENT, and SUBAGENT run profiles, implementing a Codex CLI-style turn-based pattern with ADR-0028/ADR-0029 enhancements for optimized performance and unified execution.

### Key Features

| Feature | Description |
|---------|-------------|
| **Self-Directing Model** | Model decides what tools to call based on context |
| **Context Growth** | Tool calls and results added to conversation within turn |
| **Tool Filtering** | PLAN = READ_ONLY, AGENT = all tools |
| **Subtask Tracking** | Each tool call creates database entry with status lifecycle |
| **Result Summarization** | Tool results summarized to reduce context size |
| **Safety Limits** | Max 25 iterations, loop detection, error rate monitoring |
| **Auto-Compaction** | Automatic conversation compression at 80-85% context window (ADR-0028) |
| **Prompt Caching** | Static prompt components cached for faster execution (ADR-0028) |
| **Parallel Tools** | READ_ONLY tools execute concurrently for better performance (ADR-0028) |
| **Retry Logic** | Exponential backoff for transient API errors (ADR-0028) |
| **Token Estimation** | Pre-flight token counting prevents context overflow (ADR-0028) |
| **Run Profiles** | `DEFAULT` and `SUBAGENT` with per-run overrides (ADR-0029) |
| **Nested Metadata** | `runId`, `parentRunId`, `depth` attached to turn lifecycle (ADR-0029) |

### Subtask Status Lifecycle

```
PENDING → RUNNING → SUCCESS/FAILED
   ↑         ↑            ↑
   |         |            |
   |         |            +-- Store result/error message
   |         +-- Tool execution started
   +-- Subtask created for tool call
```

### ADR-0028 Enhancements (2026-02-02)

The turn loop has been enhanced with six major optimizations inspired by Codex CLI, Claude Code, and industry research:

#### 1. Automatic Conversation Compaction

```
Context Window Filling → Token Estimation → Compaction Trigger
    ↓
ConversationCompactor
    ├── Keep last 4 messages raw (continuity)
    ├── Summarize older messages using weak model
    └── Replace with single <conversation_summary> message
    ↓
Context reduced by ~60%, conversation continues
```

**Configuration:**
- PLAN mode: Triggers at 85% of context window
- AGENT mode: Triggers at 80% of context window

#### 2. Prompt Caching

Static prompt components (system prompt + tool descriptions) are cached for 5 minutes:

```
PromptCache.getOrBuildStaticPrefix(mode, taskId)
    ├── Cache Hit → Reuse (~3-5K tokens)
    └── Cache Miss → Build and cache
    ↓
Reduced LLM prompt construction overhead
```

**Benefits:**
- Faster prompt building (cache hit: ~0ms vs ~50ms)
- Consistent prompt structure
- Automatic invalidation on config changes

#### 3. Parallel Tool Execution

READ_ONLY tools execute concurrently while WRITE tools remain sequential:

```
Tool Calls: [read_file(a.kt), read_file(b.kt), grep_search("TODO")]
    ↓
Partition by ToolMode
    ├── READ_ONLY: [read_file(a.kt), read_file(b.kt), grep_search("TODO")]
    │   └── Execute in parallel with async/awaitAll
    └── WRITE: []
    ↓
Results sorted by original order
```

**Performance:** ~2-3x faster for multiple READ_ONLY tools

#### 4. LLM Retry with Exponential Backoff

Automatic retry for transient API errors:

```
Retryable Errors:
├── Rate limits (429)
├── Timeouts
├── Server errors (502/503)
└── Overloaded messages
    ↓
Retry Strategy: 1s → 2s → 4s (exponential backoff)
Max retries: 3 (configurable)
```

#### 5. Token Estimation

Pre-flight token counting prevents unexpected context overflow:

```
Before LLM Call:
    ↓
TokenEstimator.checkFits(prompt, maxTokens)
    ├── Estimate: ~3.5 chars/token (provider-adjusted)
    └── Reserve: 4K tokens for output
    ↓
If doesn't fit → Trigger compaction
```

**Provider Multipliers:**
- Anthropic: 1.1x (Claude uses more tokens)
- OpenAI/Ollama: 1.0x
- Gemini: 1.05x

#### 6. Working Memory Integration

Automatic knowledge extraction and injection:

```
Tool Execution → Extract Knowledge → Store in Working Memory
    ↓
Next Prompt → Build Working Memory Section → Inject as Reminder
```

**Categories:** Files read, patterns found, errors encountered, decisions made

### TurnLoopConfig (Per-Mode)

| Parameter | PLAN | AGENT | Description |
|-----------|------|-------|-------------|
| maxIterations | 15 | 25 | Max LLM calls per turn |
| compactionThreshold | 0.85 | 0.80 | Trigger at % of context |
| parallelReadTools | true | true | Concurrent READ_ONLY |
| enableSnapshots | false | true | File backups before write |
| toolTimeout | 30s | 2min | Per-tool execution limit |
| maxRetries | 3 | 3 | LLM retry attempts |
| errorRateThreshold | 0.7 | 0.7 | Abort at 70% failure rate |
| enableWorkingMemory | true | true | Knowledge extraction |

### TurnEventListener Interface

```kotlin
interface TurnEventListener {
    fun onTurnStarted(taskId: String, mode: TaskMode, runId: String, parentRunId: String?, depth: Int)
    fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData)
    fun onToolExecutionCompleted(taskId: String, toolCall: ToolCallData, result: String, success: Boolean)
    fun onStreamChunk(taskId: String, delta: String, accumulated: String)
    fun onTurnCompleted(taskId: String, result: TurnResult, runId: String, parentRunId: String?, depth: Int)
}
```

### ToolResultSummarizer

**Location:** `core/services/ToolResultSummarizer.kt`

Summarizes tool execution results to reduce context size:
- Successful results: Summarized using weak model
- Last tool result: Preserved as RAW for precision
- Errors: Never summarized (kept as-is)

### Safety Mechanisms

| Mechanism | Description |
|-----------|-------------|
| **Loop Detection** | Prevents same tool call 3+ consecutive times or 5+ total |
| **Error Rate Monitoring** | Aborts if >70% failure rate AND >= 5 operations |
| **Max Iterations** | Hard limit of 25 LLM calls per turn |
| **Timeout** | Configurable per-tool execution timeout |

---

## 5. Context System

### Context Provider Architecture

```
ContextService.buildProjectContext()
    ├── 1. Load cached project analysis (architecture, dependencies)
    ├── 2. Deduplicate user context refs
    ├── 3. Extract conversation history (with summarization)
    ├── 4. Build subtask summaries (completed steps)
    ├── 5. Load RAG fragments (code + docs)
    ├── 6. Load MCP resources
    ├── 7. Resolve @mentions via ContextProviderRegistry
    └── 8. Combine into ProjectContextDTO with token budgets
```

### Provider Types

| Type | Description | Examples |
|------|-------------|----------|
| **NORMAL** | No user input required | @clipboard, @current, @terminal |
| **QUERY** | Requires search term | @codebase:auth, @grep:TODO |
| **SUBMENU** | Interactive selection | @file, @folder, @recent |

### Built-in Context Providers (14)

| Provider | Type | Description |
|----------|------|-------------|
| `@file` | SUBMENU | File picker with search |
| `@folder` | SUBMENU | Directory browser (depth 3, max 30) |
| `@current` | NORMAL | Currently active editor file |
| `@recent` | SUBMENU | Recently edited files (15-file history) |
| `@open_files` | NORMAL | All open editor tabs |
| `@clipboard` | NORMAL | System clipboard content |
| `@terminal` | NORMAL | Recent terminal output (max 200 lines) |
| `@problems` | NORMAL | Compilation errors/warnings (max 20 files) |
| `@diff` | NORMAL | Git uncommitted changes (max 20 files) |
| `@codebase` | QUERY | Semantic search via RAG (top 5 results) |
| `@grep` | QUERY | Regex search across project (max 50 results) |
| `@url` | QUERY | Fetch web content (100KB max, 30s timeout) |
| `@commit` | QUERY | Git commit details by hash/message |
| `@docs` | SUBMENU | Documentation search with semantic ranking |

### Context Budget (Token Limits)

| Section | Default | Small Model |
|---------|---------|-------------|
| SYSTEM_PROMPT | 3,000 | 2,500 |
| TOOL_DESCRIPTIONS | 2,000 | 1,500 |
| WORKING_MEMORY | 3,000 | 4,000 |
| PROJECT_CONTEXT | 1,500 | 1,000 |
| PROJECT_INSTRUCTIONS | 2,000 | 1,500 |
| RECENT_WORK | 4,000 | 5,000 |
| USER_CONTEXT | 5,000 | 4,000 |
| RAG_FRAGMENTS | 3,000 | 2,000 |
| CONVERSATION | 4,000 | 3,500 |
| **Total** | ~28,500 | ~23,000 |

Small models: ≤32KB context window

### Project Instructions

Refio automatically loads project-level instruction files into the LLM context (TIER 1 STABLE, section `PROJECT_INSTRUCTIONS`). These provide project-specific conventions, rules, and guidance to the model.

**Supported files (loaded in priority order):**

| File | Priority | Description |
|------|----------|-------------|
| `.refio/agent.md` | 1 | Refio-specific project instructions |
| `AGENTS.md` | 2 | Universal standard (supported by Codex, Copilot, Cursor, Windsurf) |
| Subdirectory `AGENTS.md` | 3 | Cascading — loaded when agent works in a subdirectory |

**Conditional rules (`.refio/rules/*.md`):**

Cursor-style rules with YAML frontmatter for selective activation:

```markdown
---
description: Kotlin coding conventions
globs: "*.kt,*.kts"
alwaysApply: false
---
Use data classes for DTOs.
Prefer val over var.
```

| Frontmatter | Effect |
|-------------|--------|
| `alwaysApply: true` | Always included in context |
| `globs: "*.kt"` | Included when active files match the glob pattern |
| `description` only | Included (LLM decides relevance based on description) |
| No frontmatter | Treated as always-apply |

Files are cached (30s TTL) and invalidated on modification. Max 4000 chars per file, 8000 total.

---

## 6. RAG System

### Indexing Pipeline

```
Project Files
    ↓
RagIndexingService.indexProject() [background, at startup]
    ├── Scan files (40+ extensions)
    ├── Compute SHA-256 checksum (incremental detection)
    └── Classify: NEW / MODIFIED / UNCHANGED
    ↓
FileAnalyzerService.analyze()
    ├── Language detection
    ├── Regex-based AST parsing
    └── Extract: classes, functions, imports, annotations
    ↓
ChunkingStrategy.createChunks()
    ├── SemanticChunkingStrategy (structure-aware)
    │   ├── Full-file chunks (max 2,000 chars)
    │   ├── Class-level chunks
    │   └── Function-level chunks
    └── DefaultChunkingStrategy (line-based, 2,000 chars, 400 overlap)
    ↓
EmbeddingsService.generateBatch()
    ├── Ollama: nomic-embed-text (768 dims), mxbai-embed-large (1024 dims)
    └── OpenAI: text-embedding-3-small (1536 dims), text-embedding-3-large (3072 dims)
    ↓
SQLite Storage
    ├── IndexFilesTable (metadata + checksum)
    ├── IndexChunksTable (content + line positions)
    └── EmbeddingsTable (vector BLOB, little-endian float32)
```

### Search Pipeline

```
Query: "authentication logic"
    ↓
EmbeddingProvider.generateEmbedding(query)
    ↓
RagRepository.getEmbeddings(projectRoot, model)
    ↓
Cosine Similarity: cos(q, e) = (q · e) / (||q|| × ||e||)
    ↓
Filter: similarity >= threshold (default 0.5)
    ↓
Sort by similarity, take topK (default 5)
    ↓
Optional: Hybrid search (70% semantic + 30% keyword)
    ↓
Optional: Context chunks (adjacent chunks from top files)
    ↓
RagSearchResult[]
```

### Language Analyzers

| Language | Analyzer | Extracts |
|----------|----------|----------|
| Kotlin | KotlinLanguageAnalyzer | Classes, objects, functions, data classes, extensions, coroutines |
| Java | JavaLanguageAnalyzer | Classes, interfaces, methods, annotations (Spring detection) |
| Python | PythonLanguageAnalyzer | Classes, functions, decorators, type hints (FastAPI/Django) |
| TypeScript | TypeScriptLanguageAnalyzer | Classes, interfaces, functions, React components, hooks |
| HTML | HtmlLanguageAnalyzer | Structure, scripts, styles |

### Search Presets

| Use Case | Threshold | TopK | Hybrid | Notes |
|----------|-----------|------|--------|-------|
| Code Search | 0.65 | 10 | No | Context chunks included |
| Documentation | 0.45 | 15 | Yes | Keyword boosting |
| Exploration | 0.35 | 20 | Yes | Low threshold, broad results |
| Debugging | 0.70 | 8 | No | High precision |

---

## 7. Tools System

### Tool Architecture

```kotlin
interface Tool {
    val name: String
    val description: String
    val mode: ToolMode        // READ_ONLY or WRITE
    val category: ToolCategory // DATA_PRODUCING, FILE_MODIFYING, EXECUTION

    suspend fun execute(params: Map<String, Any>): ToolResult
    fun getParameterSchema(): Map<String, Any>  // JSON schema for LLM
}
```

### READ_ONLY Tools (6)

| Tool | Parameters | Description | Limits |
|------|------------|-------------|--------|
| `read_file` | path | Read file content | 2MB max |
| `read_directory` | path, recursive, max_depth | List directory tree | Depth 10 |
| `file_search` | pattern, path, offset, limit | Glob pattern search | 100 results |
| `grep_search` | pattern, path, case_sensitive | Regex content search | 500 results |
| `view_diff` | file1, file2/content2 | Line-by-line diff | - |
| `invoke_subagent` | subagent_name, goal, context_refs? | Run nested child loop with a specialized subagent (dynamic description: active subagents + allowed tools/inherit) | Depth <= 3 |

### WRITE Tools (6)

| Tool | Parameters | Description | Cost |
|------|------------|-------------|------|
| `create_new_file` | path, content | Create with parent dirs | Free |
| `code_editing` | path, old_string, new_string, replace_all | Search-and-replace | Free |
| `multi_edit` | edits[] | Atomic multi-file edit | Free |
| `multi_line_editor` | path, edit_description | LLM identifies line ranges | ~$0.02 |
| `advance_code_editing` | path, edit_description | Full file regeneration | ~$0.06 |
| `run_terminal_command` | command | Shell execution (whitelist-protected) | **AGENT: ON (default)** |

### Security Layers

```
Layer 1: PathSandbox
├── All file operations restricted to project root
├── Path normalization (prevents ".." traversal)
├── Symlink detection (P0: known escape vulnerability)
└── Real path resolution after following symlinks

Layer 2: FileLimits
├── Max file size: 2 MB
├── Max files in directory: 1,000
├── Max search depth: 10
├── Excluded directories: 24 (.git, node_modules, build, etc.)
└── Excluded extensions: 34 (.class, .jar, .exe, .dll, etc.)

Layer 3: Terminal Whitelist + Filters
├── Allowlist for programs/subcommands/flags
├── Global blocked patterns (pipes to shell, command substitution, etc.)
└── Optional denylist fallback mode

Layer 4: ToolPermissions
├── Per-mode permissions (PLAN=read-only, AGENT=read-write)
├── Per-task overrides
└── Smart defaults (terminal ON in AGENT, subagent ON in PLAN/AGENT)
```

---

## 8. LLM Integration

### Unified Client

```kotlin
suspend fun LLMClient.complete(
    provider: String,          // "ollama", "openai", "anthropic", etc.
    model: String,
    messages: List<LLMMessage>,
    systemPrompt: String?,
    maxTokens: Int?,
    temperature: Double = 0.7,
    stream: Boolean = false,
    onChunk: StreamCallback?,
    noEgressEnabled: Boolean = false
): LLMResponse
```

### Provider Adapters (6)

| Provider | Models | Features |
|----------|--------|----------|
| **Ollama** | Local models | Free, NDJSON streaming, JSON mode |
| **OpenAI** | GPT-4o, GPT-4o-mini, o1, o3, GPT-5 | Responses API, reasoning models |
| **Anthropic** | Claude 3.5/3.7, Opus 4.1 | Thinking mode, top-level system |
| **Gemini** | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| **OpenRouter** | 100+ models | Unified gateway, dynamic pricing |
| **LM Studio** | Local models | OpenAI-compatible, free |

### Pricing (per 1M tokens, USD)

| Model | Input | Output |
|-------|-------|--------|
| gpt-4o-mini | $0.15 | $0.60 |
| gpt-4o | $2.50 | $10.00 |
| claude-3-5-sonnet | $3.00 | $15.00 |
| claude-opus-4-1 | $15.00 | $75.00 |
| o1-preview | $15.00 | $60.00 |
| Ollama/LM Studio | $0.00 | $0.00 |

### Streaming Formats

| Provider | Format | End Signal |
|----------|--------|------------|
| OpenAI/OpenRouter/LMStudio | SSE | `data: [DONE]` |
| Anthropic | SSE | `message_stop` event |
| Ollama | NDJSON | `done: true` |
| Gemini | SSE | Implicit end |

### No-Egress Mode

When enabled, blocks all cloud providers:
- **Allowed:** Ollama, LM Studio (local)
- **Blocked:** OpenAI, Anthropic, Gemini, OpenRouter

---

## 9. MCP Protocol

**Location:** `core/context/mcp/`

Full Model Context Protocol implementation with STDIO and HTTP/SSE transports.

### Architecture

```
MCPManager (singleton)
├── MCPProjectState (per-project isolation)
│   ├── connections: ConcurrentHashMap<String, MCPConnection>
│   ├── serverConfigs: ConcurrentHashMap<String, MCPServerConfig>
│   └── registeredTools: ConcurrentHashMap<String, List<String>>
├── MCPConnection (JSON-RPC 2.0 handler)
│   ├── MCPStdioTransport (subprocess)
│   └── MCPHttpTransport (HTTP/SSE)
├── MCPContextProvider (@ mention integration)
├── MCPToolWrapper (tool registry integration)
└── MCPToolWorkflowExecutor (workflow integration)
```

### Transport Types

| Type | Description |
|------|-------------|
| **STDIO** | Subprocess with JSON-RPC over stdin/stdout |
| **HTTP/SSE** | HTTP POST for requests, SSE for long-lived connections |

### 16 Built-in Presets

| Category | Servers |
|----------|---------|
| **VCS** | GitHub, GitLab |
| **Databases** | PostgreSQL, SQLite |
| **Search** | Brave Search, Exa |
| **Docs** | Context7 |
| **DevOps** | Sentry, AWS |
| **Storage** | Google Drive, Filesystem |
| **Development** | Puppeteer, Sequential Thinking |
| **Collaboration** | Slack |
| **Other** | Memory, Custom API |

### Capabilities

| Capability | Description |
|------------|-------------|
| `resources` | List and read resources |
| `tools` | List and call tools |
| `prompts` | System prompts (not implemented) |

### Tool Exposure Modes

| Mode | Behavior |
|------|----------|
| `TOOLS` | MCP tools registered in ToolRegistry (Agent mode) |
| `CONTEXT` | MCP tools executed as context providers |

---

## 10. Subagents System

**Location:** `core/subagents/`

### Architecture

```
SubagentRouter (API boundary)
├── SubagentRegistry (lazy loading + cache)
│   ├── Built-in: resources/subagents/*.md
│   ├── User: ~/.refio/agents/*.md
│   └── Project: .refio/agents/*.md
├── SubagentParser (Markdown + YAML frontmatter)
├── AgentTurnLoop (SUBAGENT profile, shared loop engine)
├── invoke_subagent tool (nested child loop execution + dynamic runtime description)
└── SubagentToolFilter (tool permissions)
```

### Hierarchy (Override Order)

```
PROJECT (.refio/agents/) → USER (~/.refio/agents/) → BUILTIN (resources/)
```

### Subagent Definition Format

```markdown
---
name: security-reviewer
description: Security audit specialist
tools: read_file, grep_search, file_search
model: weak
priority: 10
enabled: true
---

You are a security expert specializing in code security audits.
## Your Expertise
- Authentication and authorization vulnerabilities
- OWASP Top 10 security risks
...
```

### Frontmatter Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | string | required | Subagent identifier |
| `description` | string | required | Short description |
| `tools` | string | inherit | Comma-separated tool names |
| `disallowedTools` | string | - | Tools to exclude |
| `model` | string | default | Model alias |
| `priority` | int | 0 | Auto-delegation priority |
| `enabled` | bool | true | Whether active |

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

### Built-in Subagents (21)

#### Quality & Security

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `code-reviewer` | default | 5 | Code quality, patterns, bugs, maintainability |
| `security-engineer` | default | 8 | DevSecOps, vulnerability management, zero-trust |
| `architect-reviewer` | default | 8 | System design evaluation, tech stack decisions |

> Legacy aliases: `!security-reviewer` and `!security-auditor` map to `security-engineer`.

#### Core Development

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `api-designer` | default | 5 | REST/GraphQL API design, OpenAPI specs |
| `frontend-developer` | coding | 5 | UI components, state management, frontend architecture |
| `fullstack-developer` | coding | 5 | End-to-end features (DB → API → UI) |
| `ui-designer` | default | 5 | Design systems, component libraries, visual consistency |
| `refactoring-specialist` | coding | 5 | Code smell elimination, incremental refactoring |

#### Documentation

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `documentation-engineer` | weak | 3 | Documentation systems, API docs, tutorials |
| `api-documenter` | weak | 3 | OpenAPI specs, interactive API docs, code examples |
| `technical-writer` | weak | 3 | User guides, API references, tutorials |

#### Infrastructure

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `sre-engineer` | default | 5 | SLO/SLI, reliability, toil reduction |

#### Business & Product

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `business-analyst` | default | 3 | Requirements, process modeling, gap analysis |
| `product-manager` | weak | 3 | Feature prioritization, roadmap, product strategy |
| `project-manager` | weak | 3 | Project planning, task breakdown, risk tracking |
| `legal-advisor` | default | 3 | Licensing, privacy compliance, IP protection |
| `ux-researcher` | default | 3 | Usability analysis, persona development, UX insights |
| `risk-manager` | default | 5 | Risk identification, scoring, mitigation planning |

#### Orchestration & Research

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `multi-agent-coordinator` | default | 8 | Coordinating multiple subagents, task decomposition |
| `workflow-orchestrator` | default | 5 | Business process design, state machines, error handling |
| `research-analyst` | default | 3 | Multi-source research, trend analysis, evidence-based reporting |

### Invocation

```
User: !security-reviewer Review this authentication code
    ↓
SubagentRouter.parseSubagentInvocation()
    → ("security-reviewer", "Review this authentication code")
    ↓
SubagentRouter.invoke()
    ├── SubagentRegistry.getSubagent("security-reviewer")
    ├── map definition → TurnProfileOverrides
    └── CoreApiRouter.runTurn(runProfile=SUBAGENT)
        └── AgentTurnLoop.runTurn() [shared loop]
```

---

## 11. Session Management

**Location:** `services/session/`

### SessionManager Components (6)

| Component | Purpose |
|-----------|---------|
| `SessionManager` | Facade, exposes public API |
| `SessionStateManager` | 11 StateFlows for reactive UI binding |
| `SessionLifecycleService` | Create/switch/load sessions |
| `MessageDispatcher` | Mode-specific routing (CHAT vs PLAN/AGENT) |
| `SubtaskTracker` | Subtask CRUD operations |
| `PromptStateTracker` | Pending context refs, user input |

### Message Routing

```kotlin
// SessionManager.sendMessage()
when (session.mode) {
    TaskMode.CHAT -> sendMessageUsingChatWorkflow()
        → WorkflowOrchestrator → ChatExecutor
    TaskMode.PLAN, TaskMode.AGENT -> sendMessageUsingTurnLoop()
        → AgentTurnLoop.runTurn()
}
```

### StateFlows (11)

- `currentSession`: Current session data
- `messages`: List of messages
- `subtasks`: List of subtasks
- `isGenerating`: Boolean loading state
- `error`: Error state
- `activePlan`: Current plan (if any)
- `totalEstimatedTokens`: Token count
- And more...

---

## 12. Database Schema

### Core Tables

```sql
-- Sessions (Chat/Plan/Agent)
TasksTable (id, project_id, name, mode, description, status, ui_state_json,
            tokens_in, tokens_out, cost_usd, created_at, updated_at)

-- Execution steps
SubtasksTable (id, task_id, order_index, kind, status, description,
               params_json, result, summary, error_message, metrics_json)
    -- status: PENDING, RUNNING, SUCCESS, FAILED

-- Conversation
ChatMessagesTable (id, task_id, role, content, metadata_json, metrics_json,
                   tool_calls_json, tool_call_id, is_summarized, raw_output, created_at)

-- File snapshots (for rollback)
SnapshotsTable (id, task_id, subtask_id, file_path, content, checksum, created_at)

-- API call logs
ApiLogsTable (id, task_id, subtask_id, provider, model, source, request_json,
              response_json, status_code, tokens_in, tokens_out, cost_usd,
              latency_ms, created_at)
```

### RAG Tables

```sql
-- Indexed files
IndexFilesTable (id, project_root, file_path, file_hash, checksum, file_size,
                 content_type, metadata_json, indexed_at, last_modified)

-- Code chunks
IndexChunksTable (id, file_id, chunk_index, content, content_hash, metadata_json,
                  start_line, end_line, start_char, end_char)

-- Vector embeddings
EmbeddingsTable (id, chunk_id, model, vector BLOB, dimensions)
    -- UNIQUE(chunk_id, model)
```

### Configuration Tables

```sql
-- Key-value config
ConfigTable (key, value, scope, created_at, updated_at)

-- System prompts
PromptsTable (id, type, name, content, is_active, created_at, updated_at)

-- MCP servers
MCPServersTable (id, project_id, config_json, enabled, created_at, updated_at)
```

---

## 13. Security Model

### Security Layers

```
Layer 1: PathSandbox
├── All file operations restricted to project root
├── Symlink detection (P0: known escape vulnerability)
└── Path normalization

Layer 2: FileLimits
├── Size limits (2 MB files)
├── Excluded directories (24)
└── Excluded extensions (34)

Layer 3: Terminal Whitelist + Filters
├── Program/subcommand/flag policy
├── Global blocked pattern checks
└── Optional denylist fallback mode

Layer 4: ToolPermissions
├── Per-mode permissions
└── Per-task overrides

Layer 5: No-Egress Mode
├── Blocks cloud providers
└── Allows only local (Ollama, LM Studio)

Layer 6: Secret Redaction
├── API keys masked in logs
└── Sensitive config values hidden
```

### Known Security Issues (P0)

| Issue | Location | Impact | Status |
|-------|----------|--------|--------|
| Symlink Escape | PathSandbox.kt | Can escape project root | Detection in place |
| Whitelist Coverage | CommandWhitelistDefaults.kt | Missing command entries can block harmless commands | Add via config/UI whitelist |

---

## 14. Key Data Flows

### Chat Message Flow

```
User: "How does authentication work?"
    ↓
SessionManager.sendMessage()
    ↓
WorkflowOrchestrator.execute()
    ↓
ChatExecutor.execute()
    ├── ContextService.buildProjectContext()
    │   ├── Project analysis (cached)
    │   ├── Conversation history
    │   ├── RAG fragments
    │   └── @mention resolution
    ├── Build system prompt + context
    ├── LLMClient.complete()
    └── Save assistant response
    ↓
ChatResponse to UI
```

### Agent Execution Flow

```
User: "Add authentication to the API"
    ↓
AgentTurnLoop.runTurn()
    ↓
Loop (max 25):
├── Build prompt with tools
├── LLM response with tool calls
├── Create subtasks (PENDING)
├── Execute tools (RUNNING → SUCCESS/FAILED)
│   └── SnapshotService.createSnapshot() [before write]
├── Summarize results
├── Add to conversation history
└── Continue if more tool calls
    ↓
Final text response
    ↓
TurnResult to UI
```

### RAG Indexing Flow

```
IDE Startup
    ↓
ProjectStartupActivity.runActivity()
    ↓
RagIndexingService.indexProject() [background]
    ↓
For each file:
├── Compute SHA-256 checksum
├── Skip if checksum unchanged
├── FileAnalyzerService.analyze()
├── ChunkingStrategy.createChunks()
├── EmbeddingsService.generateBatch()
└── Store in SQLite
    ↓
IndexingProgress via Flow
```

---

## Quick Reference

### Build Commands

```bash
./gradlew runIde              # Run in sandbox IDE
./gradlew buildPlugin         # Build ZIP distribution
./gradlew test                # Run tests
./gradlew detekt              # Static analysis
./gradlew ktlintCheck         # Lint check
```

### Configuration Paths

```
~/.refio/config.yaml          # User config (Linux/macOS)
%USERPROFILE%\.refio\config.yaml  # User config (Windows)
<project>/.refio/config.yaml  # Project config
<project>/.aiignore           # RAG ignore patterns
refio_poc.db                  # SQLite database (project root)
```

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| SessionManager | services/session/ | Session coordination facade |
| AgentTurnLoop | core/services/ | Turn-based execution loop |
| ToolResultSummarizer | core/services/ | Context reduction |
| ContextService | core/services/ | Context building |
| RagSearchService | core/services/ | Semantic search |
| LLMClient | core/llm/ | LLM provider abstraction |
| ToolRegistry | core/tools/base/ | Tool catalog |
| MCPManager | core/context/mcp/ | MCP server lifecycle |
| SubagentRouter | core/subagents/ | Subagent operations |

### Environment Variables

```
OPENAI_API_KEY       # OpenAI provider
ANTHROPIC_API_KEY    # Anthropic provider
GEMINI_API_KEY       # Google Gemini
OPENROUTER_API_KEY   # OpenRouter
OLLAMA_BASE_URL      # Custom Ollama endpoint
LMSTUDIO_BASE_URL    # Custom LM Studio endpoint
```
