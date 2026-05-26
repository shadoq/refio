# Refio - Technical Architecture Overview

> **Last Updated:** 2026-05-03
> **Version:** 0.0.1.10
> **Status:** Active Development

This document provides a comprehensive technical overview of Refio - a local-first AI coding assistant for IntelliJ IDEA and the terminal.

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
15. [Terminal User Interface (TUI)](#15-terminal-user-interface-tui)

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

Refio runs in two environments sharing the same `:core` module:

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
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────────────┐
│                  Standalone CLI + TUI                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  TUI Layer (Mordant 3.0.1 + JLine3 3.26.3)                             │
│  ├── TuiRenderer (full-screen compositor, split-pane layout)            │
│  │   ├── TuiRenderBuffer (ANSI-aware line buffers)                      │
│  │   └── 7 view renderers + Settings screen (11 sub-tabs)              │
│  ├── TuiInputHandler (raw TTY / line mode dual input)                   │
│  │   └── TuiKeybindings (F1-F8, Ctrl+combinations)                     │
│  ├── TuiViewModel (coordinator ~1289 LOC, delegates to 3 sub-VMs)      │
│  │   ├── TuiChatViewModel, TuiSessionViewModel, TuiObservabilityVM    │
│  │   └── TuiWorkflowListener (streaming bridge)                        │
│  └── TuiColors (ANSI palette: 8 agent colors, roles, status)           │
│  StandaloneCoreBootstrap (initializes core without IntelliJ SDK)        │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ shared
┌──────────────────────────────┴──────────────────────────────────────────┐
│  Execution Layer                                                        │
│  ├── CHAT mode → WorkflowOrchestrator → ChatExecutor                    │
│  │   ├── IntentRouter (fast paths: subagent, answer question)           │
│  │   └── WorkflowEventListener (streaming/progress callbacks)           │
│  └── PLAN/AGENT/SUBAGENT profile → AgentTurnLoop (Codex CLI-style)      │
│      ├── TurnEventListener (progress callbacks)                         │
│      └── ToolResultSummarizer (context optimization)                    │
├─────────────────────────────────────────────────────────────────────────┤
│  Core Layer (In-Process API) — :core module                             │
│  ├── CoreApiRouter (thin composition root ~305 LOC, 12 domain routers)   │
│  │   ├── ChatRouter → ChatService                                       │
│  │   ├── TaskRouter, SubtaskRouter                                      │
│  │   ├── RagRouter → RagSearchService                                   │
│  │   ├── ToolRouter, PromptsRouter, ApiLogsRouter                       │
│  │   └── ContextService (delegates to 6 sub-services:                   │
│  │       RagContextLoader, McpContextLoader, ContextReferenceResolver,  │
│  │       ProjectContextSummarizer, ConversationContextBuilder,          │
│  │       TaskContextExtractor)                                          │
│  └── ContextProviderRegistry (14 providers + MCP dynamic)               │
├─────────────────────────────────────────────────────────────────────────┤
│  Infrastructure Layer                                                   │
│  ├── LLMClient (unified) → 8 provider adapters                          │
│  ├── ToolRegistry → 24 registered tools (14 read-only, 10 write)         │
│  ├── MCPManager → MCP server lifecycle (STDIO/HTTP)                     │
│  ├── EmbeddingsService → Ollama/OpenAI embeddings                       │
│  └── DatabaseFactory → SQLite (WAL) + Exposed ORM                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### Package Structure

```
Gradle modules (three separate source trees):
├── :core                     # Pure Kotlin/JVM — no IntelliJ SDK dependency
│   └── core/src/main/kotlin/pl/jclab/refio/
├── :intellij-plugin          # IntelliJ IDEA plugin (depends on :core)
│   └── intellij-plugin/src/main/kotlin/pl/jclab/refio/
└── :cli                      # Standalone CLI + TUI (depends on :core)
    └── cli/src/main/kotlin/pl/jclab/refio/

core/src/main/kotlin/pl/jclab/refio/
├── core/                     # Embedded core (:core module)
│   ├── api/                  # Router layer (12 domain routers)
│   ├── context/              # Context providers + MCP
│   │   ├── providers/        # 14 built-in providers
│   │   │   └── standalone/   # 7 CLI-compatible providers (no IDE)
│   │   └── mcp/              # Model Context Protocol
│   ├── db/                   # Database tables & repositories
│   ├── llm/                  # LLM integration (8 adapters)
│   ├── services/             # Core services (RAG, context, analysis)
│   │   └── analysis/         # Language analyzers
│   ├── agents/               # Multi-agent system
│   │   └── events/           # AgentEventBus, AgentEvent sealed interface
│   ├── subagents/            # Subagent system (21 built-in)
│   ├── tools/                # Tool system (15 registered implementations)
│   │   ├── implementations/
│   │   └── security/
│   └── prompts/              # Prompt templates
└── api/                      # Shared API models

intellij-plugin/src/main/kotlin/pl/jclab/refio/
├── services/                 # Plugin services (project-scoped)
│   └── session/              # SessionManager (6 components)
└── ui/                       # IntelliJ UI components
    ├── toolwindow/           # Tool window factory
    ├── components/           # Chat, toolbar, autocomplete
    └── settings/             # 12+ settings panels

cli/src/main/kotlin/pl/jclab/refio/cli/
└── tui/                      # Terminal User Interface
    ├── rendering/            # TuiRenderer, TuiRenderBuffer, TuiLayout, TuiColors
    ├── views/                # 7 tab views (Chat, Steps, Context, RAG, Logs, Debug, API)
    ├── screens/              # Settings (11 sub-tabs), History
    ├── components/           # MessageBubble, PromptInput, ProgressBar, Table
    ├── input/                # TuiInputHandler, TuiKeybindings
    └── state/                # TuiViewModel, TuiState, TuiWorkflowListener
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
| **PLAN** | AgentTurnLoop | READ_ONLY (14) | Yes | Code review, analysis |
| **AGENT** | AgentTurnLoop | ALL (24) | Yes | Code generation, refactoring |
| **SUBAGENT** | AgentTurnLoop (`runProfile=SUBAGENT`) | Profile-filtered | Yes | Specialized delegated tasks |

`ToolRegistry` has 24 registered tools (14 read-only, 10 write); `run_terminal_command` is enabled by default in AGENT mode and restricted by terminal whitelist rules.
`invoke_subagent` is enabled by default in PLAN and AGENT, and is displayed as `subagent` in Tools Settings.
`delegate_to_strong_model` is registered only when `models.defaults.strong` is configured; it delegates complex tasks to a more capable model (single-shot or tool-enabled sub-agent mode).

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
| **Centralized Metric Tracking** | `LLMClient` accepts `taskRepository` + `subtaskRepository` and auto-increments `tokens_in` / `tokens_out` / `cost_usd` on the `task` and `subtask` rows after every successful call. The `task` row is the single source of truth for live stats — UI reads it directly instead of summing per-message tokens. |
| **Task Status Transitions** | AGENT turns flip `task.status` `RUNNING` on entry, `SUCCESS` / `FAILED` on completion (was previously stuck at `NEW`). Followed by a `pushSessionRefresh()` that re-reads the row and re-publishes via the `activeSession` `StateFlow`. |
| **Skip Redundant Config Writes** | `SessionLifecycleService.updateSession(persistSettings = false)` skips the 5 `ConfigRepository` writes that `saveCurrentSessionState()` would otherwise emit on every token-only refresh (~10 fewer DB writes per turn). |

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
| maxIterations | 25 | 50 | Max LLM calls per turn |
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
| **Max Iterations** | Hard limit per turn (PLAN: 25, AGENT: 50) |
| **Timeout** | Configurable per-tool execution timeout |

---

## 5. Context System

### Context Provider Architecture

```
ContextService.buildProjectContext()
    Delegates to 6 sub-services:
    ├── ProjectContextSummarizer — cached project analysis (architecture, dependencies)
    ├── ContextReferenceResolver — deduplicate + resolve @mentions via ContextProviderRegistry
    ├── ConversationContextBuilder — extract conversation history (with summarization)
    ├── TaskContextExtractor — build subtask summaries (completed steps)
    ├── RagContextLoader — load RAG fragments (code + docs)
    ├── McpContextLoader — load MCP resources
    └── Combine into ProjectContextDTO with token budgets
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

#### Tool Result Compression Pipeline

Tool results pass through a graceful step-down before reaching the prompt:

```
ToolResultCompression.compress(rawOutput, summary, level, config, subtaskId?)
  ├── FULL      → DiffCompressor.compress(raw, subtaskId)
  ├── DETAILED  → DiffCompressor → smartCompress(head+tail, detailedMaxChars)
  └── SUMMARY   → headTailTruncate(summaryOrRaw, summaryMaxChars)
```

`DiffCompressor` operates only on ` ```diff ` fenced blocks and has three paths:

| Path | Trigger | Behaviour |
|------|---------|-----------|
| **Pass-through** | Diff body < 100 lines | Returned untouched. |
| **Pure-create** | No `-` lines, `+` lines > threshold | Keep file path + hunk headers + 15-line head + 8-line tail; insert `<!-- N added line(s) elided. Full content: memory(action="get_subtask_output", subtask_id="…") -->`. |
| **Large mixed** | Has both `+` and `-` lines | Keep every `+` and `-` line verbatim (semantic changes never dropped); collapse runs of context lines to 1 representative line per hunk run. |

The marker embeds the literal `subtaskId` so the agent can copy-paste it straight into `memory(get_subtask_output)` without scanning attributes on the surrounding tag. Compression is idempotent — a second pass over an already-compressed marker is a no-op (the marker line doesn't begin with `+` / `-`, so the next-iteration check sees only the head + tail and falls below the small-diff threshold).

DiffCompressor is also applied in `TurnPromptBuilder` and `ContextService.resolveToolConversationContent` before TOOL messages are mapped to the LLM payload — so summarized tool results in CONVERSATION are compressed too, not just RECENT_WORK entries.

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

### READ_ONLY Tools (14)

| Tool | Parameters | Description | Limits |
|------|------------|-------------|--------|
| `read_file` | path | Read file content | 2MB max |
| `read_directory` | path, recursive, max_depth | List directory tree | Depth 10 |
| `file_search` | pattern, path, offset, limit | Glob pattern search | 100 results |
| `grep_search` | pattern, path, case_sensitive | Regex content search | 500 results |
| `view_diff` | file1, file2/content2 | Line-by-line diff | - |
| `invoke_subagent` | subagent_name, goal, context_refs? | Run nested child loop with a specialized subagent | Depth <= 3 |
| `delegate_to_strong_model` | task, context?, allow_tools?, response_format? | Delegate complex task to a stronger model | - |
| `web_search` | query, max_results? | Search the web (Brave/SerpAPI/DuckDuckGo) | 20 results max |
| `fetch_webpage` | url, prompt, max_content_chars? | Fetch URL → Markdown → LLM processing | 50K chars max |
| `code_intelligence` | action, symbol?, path?, language? | Find usages, definitions, list symbols, compiler diagnostics | ctags optional |
| `monitor_process` | process_id, max_lines? | Read output from background process | 1000 lines max |
| `ask_user` | question, options? | Ask user a question and wait for response | 10 min timeout |
| `sleep` | duration_ms | Pause execution | 30s max |
| `think` | thought | Explicit reasoning slot | - |

### WRITE Tools (10)

| Tool | Parameters | Description | Cost |
|------|------------|-------------|------|
| `create_new_file` | path, content | Create with parent dirs | Free |
| `code_editing` | path, old_string, new_string, replace_all | Search-and-replace | Free |
| `multi_edit` | edits[] | Atomic multi-file edit | Free |
| `multi_line_editor` | path, edit_description | LLM identifies line ranges | ~$0.02 |
| `advance_code_editing` | path, edit_description | Full file regeneration | ~$0.06 |
| `run_terminal_command` | command | Shell execution (whitelist-protected) | **AGENT: ON (default)** |
| `http_request` | url, method, headers, body, save_to_file | HTTP requests (GET/POST/PUT/DELETE), 5 MB limit, 60s timeout | Free |
| `run_code` | language, code | Execute Python/JavaScript/Kotlin Script snippets, 120s timeout | **OFF by default** |
| `run_process_background` | command | Start command in background, return process_id | Free |
| `llm_call` | prompt, data?, file_path?, model? | Raw single-turn LLM call | ~$0.01 |

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

### Provider Adapters (8)

| Provider | Models | Features |
|----------|--------|----------|
| **Ollama** | Local models | Free, NDJSON streaming, JSON mode |
| **OpenAI** | GPT-4o, GPT-4o-mini, o1, o3, GPT-5 | Responses API, reasoning models |
| **Anthropic** | Claude 3.5/3.7, Opus 4.1 | Thinking mode, top-level system |
| **Gemini** | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| **OpenRouter** | 100+ models | Unified gateway, dynamic pricing |
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
| o1-preview | $15.00 | $60.00 |
| Ollama/LM Studio | $0.00 | $0.00 |

### Streaming Formats

| Provider | Format | End Signal |
|----------|--------|------------|
| OpenAI/OpenRouter/LMStudio | SSE | `data: [DONE]` |
| Anthropic | SSE | `message_stop` event |
| Ollama | NDJSON | `done: true` |
| Gemini | SSE | Implicit end |

### Native Function Calling

From 0.0.1.8 Refio supports the providers' structured `tools` API as an alternative to
the JSON-in-text envelope. Controlled by `tools.native_tools` in `config.yaml`:

| Value | Behaviour |
|-------|-----------|
| `auto` *(default)* | Use native tools when `ModelDefinition.supportsFunctionCalling=true` |
| `always` | Force native tools even for unlisted models |
| `never` | Always fall back to JSON-in-text path |

**Dispatch path:**

```
AgentTurnLoop
  └─→ NativeToolsResolver (mode + ModelDefinition + fallback cache)
        ├─→ NATIVE path  → adapter.chat(native_tools=[...])
        │                   → LLMResponse.nativeToolCalls  (skips ToolCallParser)
        │
        └─→ JSON path    → ToolCallParser.extractToolCalls(content)
                            ← existing behaviour unchanged
```

`ToolSchemaSanitizer` normalizes each tool's JSON Schema per provider before the request:
- **OpenAI** — strips `$schema`/`default`; strict-mode compatibility check; Responses API uses flat `name/description/parameters/strict` shape.
- **Anthropic** — strips composition keywords (`oneOf`, `allOf`, `anyOf`).
- **Gemini** — strips `additionalProperties`; converts array `type` values to a single uppercase string; adds `nullable: true` for optional fields.

`NativeToolsFallbackTracker` is a process-wide set with **persistent backing**: when
`bind(configService)` is called at startup, the tracker hydrates from the
`models.native_tools_fallbacks` config key (comma-separated model ids) and every
subsequent `markFallback()` mirrors back to disk. If a provider returns HTTP 400
"tools not supported", the model is recorded once and skipped on every future
process — users no longer pay the 2-nudge probe cost on every fresh session.
Single-entry removal is exposed via `unmark(modelId)`; `clear()` clears both
in-memory and persisted state (intended for the user-facing "retry native tools
for all models" action).

The OpenAI-compatible adapters (OpenRouter, Z.AI, Generic OpenAI, LM Studio)
share a single native-tools wiring through `OpenAICompatibleHelpers`:

- `buildOpenAIToolsArray(tools)` produces the canonical `[{type:function, function:{name, description, parameters}}]` shape, sanitized via `ToolSchemaSanitizer.forOpenAI`.
- `parseOpenAIToolCalls(rawToolCalls)` extracts the `tool_calls` field from a chat-completions response into provider-agnostic `NativeToolCall` entries (id, name, argumentsJson).
- The streaming path accumulates `tool_calls` deltas via `ToolCallAccumulator` and converts to `NativeToolCall` only when `tools` were actually requested in the request body.

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

### 17 Built-in Presets

| Category | Servers |
|----------|---------|
| **VCS** | GitHub, GitLab |
| **Databases** | PostgreSQL, SQLite |
| **Search** | Brave Search, Exa |
| **Docs** | Context7 |
| **DevOps** | Sentry, AWS |
| **Storage** | Google Drive, Filesystem |
| **Development** | Puppeteer, Sequential Thinking, Custom API |
| **Collaboration** | Slack |
| **Memory** | Memory |

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
-- Sessions (Chat/Plan/Agent) — also the canonical row for live token/cost stats
TasksTable (id, project_id, name, mode, description, status, ui_state_json,
            tokens_in, tokens_out, cost_usd, created_at, updated_at)
    -- status: NEW, RUNNING, SUCCESS, FAILED  (AGENT turns now flip RUNNING/SUCCESS/FAILED)

-- Execution steps — also carry per-subtask LLM cost (sub-LLM tools like
-- advance_code_editing, multi_line_editor, fetch_webpage)
SubtasksTable (id, task_id, order_index, kind, status, description,
               params_json, result, summary, error_message, metrics_json,
               llm_model, llm_provider, tokens_in, tokens_out, cost_usd, latency_ms)
    -- status: PENDING, RUNNING, SUCCESS, FAILED

-- Conversation
ChatMessagesTable (id, task_id, role, content, metadata_json, metrics_json,
                   tool_calls_json, tool_call_id, is_summarized, raw_output, created_at)

-- File snapshots (for rollback)
SnapshotsTable (id, task_id, subtask_id, file_path, content, checksum, created_at)

-- API call logs (audit trail; NOT a stats source)
ApiLogsTable (id, task_id, subtask_id, provider, model, source, request_json,
              response_json, status_code, tokens_in, tokens_out, cost_usd,
              latency_ms, created_at)
```

**Stats source-of-truth:** `LLMClient` writes directly to `task.tokens_in/out/cost_usd`
(via `taskRepository.incrementMetrics(...)`) and, when `subtaskId` is supplied, to
`subtask.tokens_in/out/cost_usd` (via `subtaskRepository.incrementLlmMetrics(...)`).
UI surfaces (`SessionStatsBar`, History panel) read the `task` row directly.
`api_logs` is an audit trail and is no longer aggregated for live stats.

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

Layer 3: CommandRule System (regex-based)
├── 22 BLOCK rules (destructive: rm -r, mkfs, dd, git reset --hard, npm publish, fork bombs)
├── ALLOW rules (auto-generated from legacy whitelist, 154 safe commands)
├── ASK rules (docker, kubectl, ssh, sudo, unknown commands)
└── Priority: BLOCK > ALLOW > ASK > default ASK

Layer 4: ToolPermissions (3-level)
├── Per-mode permissions: ON / ASK / OFF
├── Per-task overrides via ConfigRouter
├── Smart defaults (run_terminal_command = ASK in AGENT, run_code = OFF by default)
└── ToolApprovalService: user approval flow with session trust rules and 5-min timeout

Layer 5: Mid-Execution Control
├── PendingUserMessageQueue: user can send messages while agent runs
├── ToolRejectedException: user rejection breaks loop and returns to prompt
└── ExecutionMode: AUTO (autonomous) / INTERACTIVE (step-by-step approval)

Layer 6: No-Egress Mode
├── Blocks cloud providers
└── Allows only local (Ollama, LM Studio)

Layer 7: Secret Redaction
├── API keys masked in logs
└── Sensitive config values hidden
```

### Known Security Issues (P0)

| Issue | Location | Impact | Status |
|-------|----------|--------|--------|
| Symlink Escape | PathSandbox.kt | Can escape project root | Detection in place |
| Command Rule Coverage | `CommandRuleDefaults.kt` | Missing rules may require confirmation (`ASK`) for otherwise harmless commands | Add project-specific rules via Tools Settings → Terminal Command Rules |

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

## 15. Terminal User Interface (TUI)

Refio includes a standalone CLI with a full-screen TUI that mirrors the IntelliJ plugin GUI. The CLI uses the same `:core` module as the IntelliJ plugin — all execution modes, tools, context providers, and RAG capabilities are available.

### TUI Layout Design

```
┌─F1:Help│F2:Steps│F3:Context│F4:RAG│F5:Logs│F6:Debug│F7:API│F8:Files  F9:Set  [CHAT|model] $0.02│5K tok─┐
├────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Chat messages (scrollable)             │ Right panel (active tab content)                                 │
│                                        │                                                                  │
│ [user] describe the architecture       │ Steps:                                                           │
│                                        │   [OK] analyze_structure                                         │
│ [assistant] The project uses a         │   [>>] generate_report      (streaming)                         │
│ layered architecture with:             │   [ ] review_output                                              │
│  1. API Layer (routers)                │                                                                  │
│  2. Service Layer                      │ Context sections with token progress bars:                       │
│  3. Domain Layer                       │   [project] 2,400/4,000 tok ████████░░░                         │
│                                        │   [rag]     1,200/4,000 tok ████░░░░░░░                         │
│────────────────────────────────────────│                                                                  │
│ [CHAT] [ollama/qwen3.5:9b]      │                                                                  │
│ > your message here_                   │                                                                  │
└────────────────────────────────────────┴──────────────────────────────────────────────────────────────────┘
```

**Key design decisions:**
- **No separate status bar** — mode, cost, streaming indicator, and token count are displayed in the tab bar (right-aligned). The cursor always blinks at the input prompt, never at the bottom of the screen.
- **Split-pane** when any tab is active (55% left / 45% right). **Full-width** chat when no tab is selected.
- **Responsive** — layout adapts to terminal resize in real time (300ms polling).

### TUI Technology Stack

| Component | Library | Purpose |
|-----------|---------|---------|
| Terminal rendering | Mordant 3.0.1 | ANSI colors, styled text, terminal size detection |
| Raw input | JLine3 3.26.3 | F-keys, Ctrl+combinations, escape sequence parsing |
| CLI arguments | Clikt 5.0.2 | `--project`, `--mode`, `--model`, `--no-egress` |
| State management | Kotlin Coroutines Flow | 20 StateFlows → merge → reactive TuiState |

### TUI Architecture

```
TuiApp (entry point — launchTuiApp())
│
├── Detects interactive mode: System.console() != null
│   ├── Interactive: alternate screen buffer, raw JLine3 input, F-key navigation
│   └── Non-interactive: inline rendering, line-based input (`/prompt`, `:shortcuts`)
│
├── Three concurrent coroutines:
│   ├── Render loop — stateFlow.collect { renderer.render(state) }
│   ├── Resize watcher — polls terminal.size every 300ms, forceRender on change
│   └── Input loop — TuiInputHandler.startInputLoop(viewModel)
│
├── TuiViewModel (coordinator ~1289 LOC, MVVM pattern)
│   ├── Delegates to 3 sub-ViewModels:
│   │   ├── TuiChatViewModel (messages, streaming, autocomplete)
│   │   ├── TuiSessionViewModel (session lifecycle, history, settings)
│   │   └── TuiObservabilityViewModel (logs, API logs, debug state)
│   ├── 20 MutableStateFlows (merged from coordinator + sub-ViewModels)
│   ├── All flows merged via merge().map { buildCurrentState() }
│   ├── Any flow change triggers stateFlow emission → re-render
│   ├── sendMessage() → WorkflowOrchestrator.execute(request, workflowListener)
│   ├── Settings: getConfigSection(), updateConfig() via ConfigRouter
│   └── Autocomplete: triggerAutocomplete(), updateAutocompleteFilter()
│
├── TuiRenderer (full-screen compositor)
│   ├── Hash-based skip: only re-renders when state.hashCode() changes or terminal resizes
│   ├── Cursor hidden during redraw (\u001b[?25l), shown at input after (\u001b[?25h)
│   ├── In-place overwrite (\u001b[H) — no flicker. Full clear (\u001b[2J) only on resize.
│   ├── renderTabBar() — tabs + right-aligned status info (mode, cost, streaming)
│   ├── renderFullWidthChat() — chat messages buffer + prompt buffer (no split)
│   ├── renderSplitPane() — left (chat+prompt) + right (tab view), merged side-by-side
│   ├── renderAutocompletePopup() — ANSI cursor-positioned overlay above prompt
│   └── positionCursorAtInput() — places cursor at "> {input}_" position
│
├── TuiRenderBuffer (ANSI-aware line composition)
│   ├── visibleLength() — measures string width ignoring ANSI escape codes
│   ├── fitToWidth() — pads or truncates preserving ANSI state
│   ├── mergeSideBySide() — combines left + right buffers with separator column
│   └── Each view renders into a TuiRenderBuffer → compositor merges them
│
├── TuiInputHandler (dual-mode input)
│   ├── Raw mode (real TTY): JLine3 reader, single-char dispatch, escape sequence parsing
│   ├── Line mode (IDE/pipe): BufferedReader from System.in, `/prompt`, `:tab` shortcuts
│   ├── dispatchAction() handles: tab switching, typing, backspace, send, autocomplete
│   └── Slash commands: /quit, /clear, /help, /mode, /history, /settings, /set section.key value
│
├── TuiWorkflowListener (streaming bridge)
│   ├── synchronized(accumulatedContent) on all methods — no race conditions
│   ├── @Volatile completed flag — prevents late chunks after onStreamComplete
│   ├── reset() called before each new request — clears accumulated content
│   └── Stream message identified by streamId, replaced in-place, gets UUID on completion
│
└── TuiColors (ANSI color palette)
    ├── Message roles: user (bright green), assistant (bright cyan), tool (yellow), system (red)
    ├── Agent colors: 8-color cycle (cyan, green, magenta, yellow, blue, white, red, bright cyan)
    ├── Status: new (white), pending (yellow), running (blue), success (green), failed (red)
    ├── Log levels: debug (gray), info (white), warn (yellow), error (red)
    └── Context categories: project (cyan), user (green), rag (magenta), conversation (yellow)
```

### TUI Tabs

| Tab/Screen | Key | View | Content |
|-----|-----|------|---------|
| Help | F1 | TuiHelpScreen | Keyboard shortcuts and command reference |
| Steps | F2 | TuiStepsView | Subtask list with status icons and expand/collapse |
| Context | F3 | TuiContextView | Context sections with token usage progress bars |
| RAG | F4 | TuiRagView | RAG indexing status and search results |
| Logs | F5 | TuiLogsView | Application log stream with level coloring |
| Debug | F6 | TuiDebugView | Session info, core health, connection status |
| API | F7 | TuiApiLogsView | API call table with provider, tokens, cost, totals |
| Files | F8 | TuiFilesView | Project files browser with content viewer overlay |
| Settings | F9 | TuiSettingsScreen | 11 sub-tabs (General, Providers, Models, Prompts, Context, MCP, Docs, Tools, Subagents, Advanced, Theme) |

### TUI Settings Screen

The Settings screen provides full configuration access via `ConfigRouter`, matching the IntelliJ plugin's settings panels:

| Sub-tab | Section | Content |
|---------|---------|---------|
| General | `general` | Markdown rendering, streaming, advanced view toggles |
| Providers | `providers` | 8 providers (Ollama, Anthropic, OpenAI, OpenRouter, Gemini, LM Studio, Custom OpenAI, Z.AI) with masked API keys and status indicators |
| Models | `models` | Model assignments: default, planning, coding, auxiliary, embeddings |
| Prompts | `prompts` | Custom system prompts and slash prompts (reusable `/name` prompt templates) |
| Context | `index` | RAG search tuning (similarity threshold, top-k, hybrid search) and indexing settings |
| MCP | `mcp` | MCP server list with enable/disable and type |
| Docs | `docs` | Documentation sources for @docs context provider |
| Tools | `tools` | 15 tools × Plan/Agent mode permission matrix (ON/ASK/OFF) |
| Subagents | `subagents` | Enable/disable individual subagent profiles |
| Advanced | `advanced`+`limits` | Security (no-egress, read-only), timeouts, limits, performance |
| Theme | — | ANSI color preview (roles, status, agents, log levels) |

---

### TUI Keyboard Reference

#### Navigation & Tabs

| Key | Action |
|-----|--------|
| F1 | Help screen |
| F2-F7 | Switch tabs: Steps, Context, RAG, Logs, Debug, API |
| F8 | Files tab |
| F9 | Settings screen (←/→ switch sub-tabs, ↑/↓ navigate fields) |
| Ctrl+S | Settings (alternative) |
| Alt+H | Session history browser |
| Tab | Toggle panel/input focus |
| Escape | Back to main screen / dismiss popup |
| Ctrl+Q | Quit |
| Arrow Up/Down | Scroll chat messages / navigate lists |
| Page Up/Down | Scroll chat (10 lines at a time) |

#### Session Management

| Key | Action | Details |
|-----|--------|---------|
| Ctrl+W | **New session** | Starts a fresh conversation. Previous session is saved and can be restored from history. |
| Alt+H | **History** | Browse all previous sessions. Select one and press Enter to switch. Shows mode, status, date, tokens, cost, and name. |
| Ctrl+L | **Continue** | Resume current conversation after interruption (e.g. if agent stopped mid-task). |
| Ctrl+D | **Summarize** | Compact long conversation history to save context window space. Uses LLM to generate a summary of older messages. |

You can also use system commands (note: these are TUI session commands, distinct from user-defined slash prompts managed in Settings → Prompts):
- `/history` — Open session history
- `/export <path>` — Export conversation to Markdown file
- `/resend` — Resend last user message
- `/rewind [N]` — Rewind to message N and resend
- `/edit [N]` — Edit user message N (loads into input for re-editing)
- `/copyall` — Copy entire conversation to clipboard
- `/rate +/-` — Rate conversation quality

#### Mode, Model & Toggles

| Key | Action | Details |
|-----|--------|---------|
| Shift+Tab | Cycle mode | CHAT → PLAN → AGENT → CHAT. Mode determines available tools. |
| Ctrl+O | Select model | Opens popup with available models. Arrow keys to select, Enter to confirm. |
| Ctrl+T | Toggle thinking | Enable/disable reasoning mode (extended thinking for supported models). |
| Ctrl+E | Toggle execution | AUTO (agent runs autonomously) / INTERACTIVE (step-by-step approval). |
| Ctrl+N | Toggle no-egress | Local-only mode — blocks all cloud provider API calls. |

#### Chat & Input

| Key | Action |
|-----|--------|
| Enter | Send message |
| Ctrl+C | Cancel current operation (streaming/agent execution) |
| Arrow Left/Right | Move cursor within input |
| Backspace | Delete character before cursor |

**Multi-line input:** The input area expands from 1 to 4 lines as you type. Long lines wrap automatically. An overflow indicator shows when content exceeds 4 lines.

**Paste support:** Large pastes (>200 characters) display a preview marker showing character count and first 30 characters. The full content is sent when you press Enter.

#### Message Selection & Clipboard

| Key | Action |
|-----|--------|
| Ctrl+P | Select previous message (move selection up) |
| Ctrl+B | Select next message (move selection down) |
| Ctrl+Y | Copy selected (or last) message to clipboard |
| Ctrl+F | Cycle agent filter (multi-agent mode — filter chat by agent) |

#### Autocomplete

| Trigger | Action | Candidates |
|---------|--------|------------|
| `@` | Context autocomplete | @file, @folder, @codebase, @grep, @diff, @url, @docs, @clipboard, etc. |
| `!` | Subagent autocomplete | !review, !security, !architect, !docs, and custom subagents |
| `/` | Slash prompt autocomplete | /explain, /refactor, /test, /fix, /implement, /optimize, /security-review, etc. |

When the autocomplete popup is visible:
- **Arrow Down / Tab** — Next candidate
- **Arrow Up** — Previous candidate
- **Enter** — Accept selection
- **Escape** — Dismiss
- **Keep typing** — Filters candidates in real-time

#### Slash Prompts Reference

**Prompt templates** (sent to LLM with your input as context). The feature was previously called "Slash Commands"; the `/name` syntax is unchanged, only the UI/code label changed to reflect that these are prompts, not plugin/CLI commands.

| Command | Description |
|---------|-------------|
| /explain | Explain what this code does |
| /refactor | Suggest focused refactoring |
| /test | Generate unit tests |
| /fix | Fix a bug with root cause analysis |
| /implement | Implement a change with plan |
| /optimize | Analyze performance, propose optimizations |
| /simplify | Simplify complex code |
| /document | Generate documentation |
| /security-review | Security vulnerability analysis |
| /translate | Translate code to another language |

**System commands** (executed locally, not sent to LLM):

| Command | Description |
|---------|-------------|
| /help, /? | Show help with all commands and shortcuts |
| /quit, /q | Exit Refio |
| /clear | Clear input buffer |
| /history | Browse session history |
| /history-delete \<id\> | Delete a session |
| /export \<path\> | Export conversation to Markdown |
| /resend | Resend last user message |
| /rewind [N] | Rewind to message N and resend |
| /edit [N] | Edit user message N |
| /copyall | Copy entire conversation to clipboard |
| /rate +/- | Rate conversation quality |
| /prompt | Show current system prompt |
| /add-step \<desc\> | Add a new step to the plan |
| /snippet \<file\> [start] [end] | Add file snippet as context |
| /open \<file\> | Open file in external editor |
| /clearctx | Remove all @context references from input |
| /removectx \<name\> | Remove specific @context reference |
| /docs-add \<url\> [depth] | Add documentation source for indexing |
| /docs-delete \<id\> | Delete documentation source |
| /docs-reindex \<id\> | Reindex documentation source |
| /rag-search \<query\> | Search RAG index |
| /mcp-add \<type\> \<name\> \<cmd\> | Add MCP server (stdio/http) |
| /mcp-edit \<id\> \<field\> \<value\> | Edit MCP server field |
| /mcp-remove \<id\> | Remove MCP server |
| /mcp-list | List MCP servers with status |

---

## Quick Reference

### Build Commands

```bash
./gradlew runIde              # Run in sandbox IDE
./gradlew buildPlugin         # Build ZIP distribution
./gradlew :cli:installDist    # Build standalone CLI
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
~/.refio/data/database.sqlite # SQLite database (shared across projects)
```

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| SessionManager | services/session/ | Session coordination facade |
| AgentTurnLoop | core/services/ | Turn-based execution loop |
| ToolResultSummarizer | core/services/ | Context reduction |
| ContextService | core/services/ | Context building (delegates to 6 sub-services) |
| RagSearchService | core/services/ | Semantic search |
| LLMClient | core/llm/ | LLM provider abstraction; also the central writer of `task` / `subtask` token + cost metrics on every successful call |
| DiffCompressor | core/services/context/ | Content-aware diff body elision (small / pure-create / mixed paths) for tool results |
| NativeToolsFallbackTracker | core/llm/ | Persistent set of model ids that failed native function-calling; hydrated from `models.native_tools_fallbacks` on startup |
| ToolRegistry | core/tools/base/ | Tool catalog |
| MCPManager | core/context/mcp/ | MCP server lifecycle |
| SubagentRouter | core/subagents/ | Subagent operations |
| TuiApp | cli/tui/ | TUI entry point (launchTuiApp) |
| TuiViewModel | cli/tui/state/ | TUI coordinator (~1289 LOC, delegates to TuiChatViewModel, TuiSessionViewModel, TuiObservabilityViewModel) |
| TuiRenderer | cli/tui/rendering/ | Full-screen split-pane compositor |
| TuiRenderBuffer | cli/tui/rendering/ | ANSI-aware line buffer composition |
| TuiInputHandler | cli/tui/input/ | Dual-mode input (raw TTY / line) |
| StandaloneCoreBootstrap | cli/ | Core initialization without IntelliJ SDK |

### Environment Variables

```
OPENAI_API_KEY       # OpenAI provider
ANTHROPIC_API_KEY    # Anthropic provider
GEMINI_API_KEY       # Google Gemini
OPENROUTER_API_KEY   # OpenRouter
OLLAMA_BASE_URL      # Custom Ollama endpoint
LMSTUDIO_BASE_URL    # Custom LM Studio endpoint
```
