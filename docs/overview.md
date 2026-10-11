# Refio - Technical Architecture Overview

> **Last Updated:** 2026-10-11
> **Version:** 0.0.2.1
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
- Lower API costs (less context sent per call)
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
│  ├── SessionManager (facade; session components live in :core)          │
│  │   ├── SessionStateManager (16 StateFlows for reactive UI)            │
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
│  ├── TuiViewModel (coordinator ~1588 LOC, delegates to 3 sub-VMs)      │
│  │   ├── TuiChatViewModel, TuiSessionViewModel, TuiObservabilityVM    │
│  │   └── TuiWorkflowListener (streaming bridge)                        │
│  └── TuiColors (ANSI palette: 8 agent colors, roles, status)           │
│  StandaloneCoreBootstrap (initializes core without IntelliJ SDK)        │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ shared
┌──────────────────────────────┴──────────────────────────────────────────┐
│  Execution Layer                                                        │
│  ├── CoreSessionService (:core) routes by mode                          │
│  ├── CHAT mode → ChatRouter → ChatService (no tools)                    │
│  │   └── DefaultWorkflowStreamingListener (streaming callbacks)         │
│  └── PLAN/AGENT/SUBAGENT profile → AgentRouter.runTurn → AgentTurnLoop  │
│      ├── TurnEventListener (progress callbacks)                         │
│      └── ToolResultSummarizer (context optimization)                    │
├─────────────────────────────────────────────────────────────────────────┤
│  Core Layer (In-Process API) - :core module                             │
│  ├── CoreApiRouter (thin composition root ~320 LOC, 13 domain routers)   │
│  │   ├── ChatRouter → ChatService                                       │
│  │   ├── AgentRouter → AgentTurnLoop                                    │
│  │   ├── TaskRouter, SubtaskRouter, ConfigRouter, SnapshotRouter        │
│  │   ├── RagRouter → RagSearchService                                   │
│  │   ├── ToolRouter, PromptsRouter, ApiLogsRouter                       │
│  │   ├── SubagentRouter, MultiAgentRouter, ProjectContextRouter         │
│  │   └── ContextService (delegates to 5 sub-services:                   │
│  │       McpContextLoader, ContextReferenceResolver,                    │
│  │       ProjectContextSummarizer, ConversationContextBuilder,          │
│  │       TaskContextExtractor)                                          │
│  └── ContextProviderRegistry (14 providers + MCP dynamic)               │
├─────────────────────────────────────────────────────────────────────────┤
│  Infrastructure Layer                                                   │
│  ├── LLMClient (unified) → 8 provider adapters                          │
│  ├── ToolRegistry → ~30 registered tools across 6 groups                 │
│  ├── MCPManager → MCP server lifecycle (STDIO/HTTP)                     │
│  ├── EmbeddingsService → Ollama/OpenAI embeddings                       │
│  └── DatabaseFactory → SQLite (WAL) + Exposed ORM                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### Package Structure

```
Gradle modules (three separate source trees):
├── :core                     # Pure Kotlin/JVM - no IntelliJ SDK dependency
│   └── core/src/main/kotlin/pl/jclab/refio/
├── :intellij-plugin          # IntelliJ IDEA plugin (depends on :core)
│   └── intellij-plugin/src/main/kotlin/pl/jclab/refio/
└── :cli                      # Standalone CLI + TUI (depends on :core)
    └── cli/src/main/kotlin/pl/jclab/refio/

core/src/main/kotlin/pl/jclab/refio/
├── core/                     # Embedded core (:core module)
│   ├── api/                  # Router layer (13 domain routers)
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
│   ├── session/              # CoreSessionService, SessionStateManager, MessageDispatcher, ...
│   ├── security/             # NetworkPolicy, UrlPolicy, SecureLogger, ExecutionSandbox
│   ├── tools/                # Tool system (~30 registered tools)
│   │   ├── implementations/
│   │   └── security/
│   └── prompts/              # Prompt templates
└── api/                      # Shared API models

intellij-plugin/src/main/kotlin/pl/jclab/refio/
├── services/                 # Plugin services (project-scoped)
│   └── session/              # SessionManager (facade over :core session components)
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
| **CHAT** | ChatService | None | No | Conversation, explanations |
| **PLAN** | AgentTurnLoop | READ_ONLY subset (~20) | Yes | Code review, analysis |
| **AGENT** | AgentTurnLoop | ALL (~30) | Yes | Code generation, refactoring |
| **SUBAGENT** | AgentTurnLoop (`runProfile=SUBAGENT`) | Profile-filtered | Yes | Specialized delegated tasks |

`ToolRegistry` has ~30 registered tools across 6 functional groups (reading & search, web & HTTP, system, editing, execution, delegation); PLAN exposes the read-only subset, AGENT the full set. `run_terminal_command` and `run_process_background` are `ASK` by default in AGENT mode and validated by `CommandRule` (regex `ALLOW` / `BLOCK` / `ASK`).
`invoke_subagent` is enabled by default in PLAN and AGENT, and is displayed as `subagent` in Tools Settings.
`delegate_to_strong_model` is registered only when `models.defaults.strong` is configured; it delegates complex tasks to a more capable model (single-shot or tool-enabled sub-agent mode).

### CHAT Mode Flow

```
User Input
    ↓
SessionManager.sendMessage()
    ↓
CoreSessionService.sendMessage()  (mode == CHAT)
    ├→ ChatRouter.chat()
    │   ├→ ChatService.chat()  (ContextService.buildProjectContext + history)
    │   └→ LLMClient.complete()
    └→ StreamChunk callbacks to UI (DefaultWorkflowStreamingListener)
    ↓
MessageDispatcher.loadMessagesAndWait()
    ↓
UI Update via StateFlow
```

### PLAN/AGENT/SUBAGENT Profile Flow

```
User Input
    ↓
SessionManager.sendMessage()
    ↓
CoreSessionService.sendMessage()  (mode == PLAN/AGENT; `!name` input → SubagentRouter)
    ↓
AgentRouter.runTurn() → AgentTurnLoop.runTurn()
    ├─ Save user message to history
    ├─ LOOP (max 200 iterations, plus guardrails below):
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

The primary execution engine for PLAN, AGENT, and SUBAGENT run profiles, implementing a Codex CLI-style turn-based pattern with performance and unified-execution enhancements.

### Key Features

| Feature | Description |
|---------|-------------|
| **Self-Directing Model** | Model decides what tools to call based on context |
| **Context Growth** | Tool calls and results added to conversation within turn |
| **Tool Filtering** | PLAN = READ_ONLY, AGENT = all tools |
| **Subtask Tracking** | Each tool call creates database entry with status lifecycle |
| **Result Summarization** | Tool results summarized to reduce context size |
| **Safety Limits** | Max 200 iterations (a backstop), loop/repetition trackers, error rate monitoring, cost and wall-clock budgets, completion guardian |
| **Auto-Compaction** | Automatic conversation compression when the conversation tail exceeds 80% of its budget |
| **Prompt Caching** | Static prompt components cached for faster execution |
| **Parallel Tools** | READ_ONLY tools execute concurrently for better performance |
| **Retry Logic** | Exponential backoff for transient API errors |
| **Token Estimation** | Pre-flight token counting prevents context overflow |
| **Run Profiles** | `DEFAULT` and `SUBAGENT` with per-run overrides |
| **Nested Metadata** | `runId`, `parentRunId`, `depth` attached to turn lifecycle |
| **Centralized Metric Tracking** | `LLMClient` accepts `taskRepository` + `subtaskRepository` and auto-increments `tokens_in` / `tokens_out` / `cost_usd` on the `task` and `subtask` rows after every successful call. The `task` row is the single source of truth for live stats - UI reads it directly instead of summing per-message tokens. |
| **Task Status Transitions** | AGENT turns flip `task.status` `RUNNING` on entry, `SUCCESS` / `FAILED` / `INCOMPLETE` on completion (was previously stuck at `NEW`). `INCOMPLETE` marks a turn that stopped without delivering the request (a completion guardian gave up, or a no-op-write / read-spree abort fired) - distinct from `FAILED` (an error) and `SUCCESS` (delivered). Followed by a `pushSessionRefresh()` that re-reads the row and re-publishes via the `activeSession` `StateFlow`. |
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

### Turn-loop Enhancements (2026-02-02)

The turn loop has been enhanced with six major optimizations inspired by Codex CLI, Claude Code, and industry research:

#### 1. Automatic Conversation Compaction

```
Context Window Filling → Token Estimation → Compaction Trigger
    ↓
ConversationSummaryService
    ├── Only messages after the last summary are considered (never summarize a summary)
    ├── Keep at least the last 4 messages raw (continuity)
    ├── Summarize older messages using the WEAK model
    └── Replace with a single <conversation_summary> message
    ↓
Conversation tail brought back to ~45% of the budget, conversation continues
```

**Configuration:** triggers when the uncompressed tail exceeds 80% of the conversation budget, in every mode (`ConversationSummaryService.SUMMARY_THRESHOLD_RATIO = 0.80`, target `SUMMARY_TARGET_RATIO = 0.45`, `MIN_KEEP_RECENT_MESSAGES = 4`). `TurnLoopConfig` has no compaction fields. Building a context preview never compacts (`allowSummarization = false`), and a failed compaction falls back to the uncompacted history instead of dropping the project context (`ContextService`).

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

READ_ONLY tools execute concurrently while WRITE tools remain sequential (`TurnToolExecutor`):

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
├── Timeouts, refused/reset connections, streams cut off mid-flight
├── Server errors (502/503, plus bare 500/520/529 via TransientErrorClassifier)
└── Overloaded messages
    ↓
Retry Strategy: 1s → 2s → 4s (exponential backoff)
Max attempts: 3 in total (`limits.max_retries`, configurable)
```

Concurrent cloud calls are additionally capped per provider by `ProviderRequestGate` (a semaphore around the adapter call in `LLMClient`, `providers.max_concurrent`, default 4); Ollama is excluded because `OllamaRequestGate` already serializes it per endpoint. A streamed response's idle (socket) timeout is clamped to `LLMKtorClientFactory.STREAM_IDLE_CEILING_MS` (600 s), so a dead stream cannot hang for the whole total timeout.

#### 5. Token Estimation

Pre-flight token counting prevents unexpected context overflow:

```
Before LLM Call:
    ↓
ModelWindow.resolve(provider, model)  → effective context window
    ↓
ContextBudget.forContextSize(window, staticPrefixTokens)  → per-section budgets
    ├── Estimate: ~3.5 chars/token base (provider-adjusted)
    └── Conversation tail over 80% of its budget → ConversationSummaryService compaction
```

`ModelWindow.resolve` is the single resolver for "how many tokens can this model accept": provider-specific override (`providers.ollama.ollama_context_size`, ...) → `ModelDefinitions.maxContext` → window discovered from the provider's model listing → `MAX_CONTEXT_SIZE` fallback (an explicitly set `MAX_CONTEXT_SIZE` also acts as a ceiling). `TokenEstimator.checkFits` (4096-token output reserve) still exists but has no callers.

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
| maxIterations | 200 | 200 | Max LLM calls per turn (backstop; guardrails bound the real cost) |
| parallelReadTools | true | true | Concurrent READ_ONLY |
| maxParallelReadTools | 6 | 6 | Fan-out cap per READ_ONLY batch |
| enableSnapshots | false | true | File backups before write |
| toolTimeout | 2min | 5min | Per-tool limit for in-process tools |
| networkToolTimeout | 3min | 3min | Per-tool limit for network / local-model tools (rag_search, web, HTTP, MCP) |
| maxRetries | 3 | 3 | LLM attempts in total |
| retryBackoffMs | 1000 | 1000 | Base backoff delay |
| errorRateThreshold | 0.7 | 0.7 | Abort above 70% failure rate |
| errorWindowSize | 10 | 10 | Sliding window for the error rate |

### TurnEventListener Interface

```kotlin
interface TurnEventListener : TurnCompletionListener {  // adds onTurnCompleted(taskId, result, runId, parentRunId, depth)
    fun onTurnStarted(taskId: String, mode: TaskMode, runId: String, parentRunId: String?, depth: Int)
    fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData)
    fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String)
    fun onToolExecutionCompleted(taskId: String, toolCall: ToolCallData, result: String, success: Boolean)
    fun onStreamChunk(taskId: String, delta: String, accumulated: String)
    fun onLlmToolCallProgress(taskId: String, index: Int, toolName: String?, accumulatedArguments: String)
    fun onToolBatchCompleted(taskId: String, summary: ToolBatchSummary.BatchSummary)
}
```

All methods have empty default bodies.

### ToolResultSummarizer

**Location:** `core/services/ToolResultSummarizer.kt`

Summarizes tool execution results to reduce context size:
- Successful results: Summarized using weak model
- Last tool result: Preserved as RAW for precision
- Errors: Never summarized (kept as-is)

### Safety Mechanisms

| Mechanism | Description |
|-----------|-------------|
| **Loop Detection** | `TurnRepetitionTracker`: aborts when a tool returns byte-identical output 4x in a row on the same target (hash-based; subagents abort write/exec loops at 2) |
| **No-op Write Streak** | Same tracker: 3 consecutive no-op writes ("identical content") on the same tool and path abort the turn as `INCOMPLETE`; a no-op write never counts as progress |
| **Text Repetition** | `ContentChantingDetector` (a phrase repeated 10x in a row inside one response) and `ConsecutiveTextRepetitionTracker` (the same or near-identical answer text on 2 consecutive iterations without tool calls) |
| **Error Rate Monitoring** | `ToolErrorTracker`: aborts when the failure rate is above 70% over the last 10 tool calls (needs at least 5 results, and the latest call must have failed) |
| **Blocked / Denied Tools** | `ConsecutiveBlockedToolTracker` (2 calls in a row to a tool outside the profile) and `ConsecutiveDeniedToolTracker` (3 calls in a row refused by the approval policy); denials do not feed the error rate |
| **Read Spree Nudge** | After 14 consecutive gathering calls without a write or delivery (top-level AGENT), a non-blocking SYSTEM nudge asks the model to consolidate or deliver |
| **Cost Budget** | `CostLimitGuard`: stops before the next LLM call once session cost reaches `agent.max_cost_usd` (CLI `--max-cost`; `0` = off, default) |
| **Wall-clock Budget** | `TurnBudgetGuard`: stops a turn after `agent.max_turn_minutes` (default 45; `0` = off) |
| **Completion Guardian** | `NextSpeakerJudgeGuardian`: at the terminal point a cheap judge checks whether the request was delivered and allows one bounded re-entry; if not delivered, the task ends as `INCOMPLETE` |
| **Max Iterations** | Hard limit per turn (PLAN: 200, AGENT: 200) |
| **Timeout** | Per-tool execution timeout (`toolTimeout` / `networkToolTimeout`) |

Trackers live in `core/services/turn/TurnGuardrails.kt`; the budget guards and the guardian sit next to it in `core/services/turn/`.

---

## 5. Context System

### Context Provider Architecture

```
ContextService.buildProjectContext()
    Delegates to 5 sub-services (+ ProjectInstructionsLoader, ContextPruner, ContextFormatter):
    ├── ProjectContextSummarizer - cached project analysis (architecture, dependencies)
    ├── ContextReferenceResolver - deduplicate + resolve @mentions via ContextProviderRegistry
    ├── ConversationContextBuilder - extract conversation history (with summarization)
    ├── TaskContextExtractor - build subtask summaries (completed steps)
    ├── McpContextLoader - load MCP resources
    └── Combine into ProjectContextDTO with token budgets
```

RAG fragments are not injected automatically: the model reaches the index through the `rag_search` tool, and the user through `@codebase` / `@docs` references.

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

| Section | Baseline budget |
|---------|-----------------|
| WORKING_MEMORY | 3,000 |
| PROJECT_CONTEXT | 1,500 |
| RECENT_WORK | 8,000 |
| USER_CONTEXT | 5,000 |
| CONVERSATION | 14,000 |
| REFERENCE | 2,500 |
| **Total** | 34,000 |

`ContextBudget.forContextSize` takes 85% of the resolved context window as input budget and subtracts the tokens already committed to the system prompt (`staticPrefixTokens`). If what is left exceeds the 34,000 baseline, every section is scaled up proportionally; if it is smaller, every section is scaled down to fit. Unused budget is redistributed in the order RECENT_WORK, WORKING_MEMORY, CONVERSATION, USER_CONTEXT. SYSTEM_PROMPT and TOOL_DESCRIPTIONS are built by `TurnPromptBuilder` and get no section budget here; `AGENT_PLANS` and `PROJECT_INSTRUCTIONS` are sections without a baseline entry.

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

The marker embeds the literal `subtaskId` so the agent can copy-paste it straight into `memory(get_subtask_output)` without scanning attributes on the surrounding tag. Compression is idempotent - a second pass over an already-compressed marker is a no-op (the marker line doesn't begin with `+` / `-`, so the next-iteration check sees only the head + tail and falls below the small-diff threshold).

DiffCompressor is also applied in `TurnPromptBuilder` and `ContextService.resolveToolConversationContent` before TOOL messages are mapped to the LLM payload - so summarized tool results in CONVERSATION are compressed too, not just RECENT_WORK entries.

### Project Instructions

Refio automatically loads project-level instruction files into the LLM context (TIER 1 STABLE, section `PROJECT_INSTRUCTIONS`). These provide project-specific conventions, rules, and guidance to the model.

**Supported files (loaded in priority order):**

| File | Priority | Description |
|------|----------|-------------|
| `.refio/agent.md` | 1 | Refio-specific project instructions |
| `AGENTS.md` | 2 | Universal standard (supported by Codex, Copilot, Cursor, Windsurf) |
| Subdirectory `AGENTS.md` | 3 | Cascading - loaded when agent works in a subdirectory |

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
RagRepository.getEmbeddingsBatch(projectRoot, model, contentType, afterId, limit=500)
    (paged scan, never loads the whole index into memory)
    ↓
Cosine Similarity: cos(q, e) = (q · e) / (||q|| × ||e||)
    ↓
Filter: similarity >= threshold (default 0.65)
    ↓
Bounded candidate heap, sort by similarity, take topK (default 5)
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
| Examples | 0.50 | 12 | Yes | Optional keyword list |
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

### READ_ONLY Tools (~21)

13 come from `ToolFactory.createReadOnlyTools()`; the rest are registered at bootstrap: `invoke_subagent`, `delegate_to_strong_model` (only with a strong model configured) and the system tools `tasks`, `memory`, `manage_subagent`, `send_message`, `answer_message` (`SystemToolsRegistrar`), plus `rag_search` (`CoreApiRouterBootstrap`).

| Tool | Parameters | Description | Limits |
|------|------------|-------------|--------|
| `read_file` | path | Read file content | 2MB max |
| `read_directory` | path, recursive, max_depth | List directory tree | Default depth 3 |
| `file_search` | pattern, path, offset, limit | Glob pattern search | 100 results |
| `grep_search` | pattern, path, case_sensitive | Regex content search | 500 results |
| `view_diff` | file1, file2/content2 | Line-by-line diff | - |
| `invoke_subagent` | subagent_name, goal, context_refs? | Run nested child loop with a specialized subagent | Depth <= 3 |
| `delegate_to_strong_model` | task, context?, allow_tools?, response_format? | Delegate complex task to a stronger model | - |
| `web_search` | query, max_results? | Search the web (Brave/SerpAPI/DuckDuckGo) | 20 results max |
| `fetch_webpage` | url, prompt, max_content_chars? | Fetch URL → Markdown → LLM processing | 50K chars max |
| `code_intelligence` | action, symbol?, path?, language? | Find usages, definitions, list symbols, compiler diagnostics | ctags optional |
| `find_usages` | symbol_name, ... | Project-wide symbol usages via the refactoring engine | - |
| `rag_search` | query, content_type? | Semantic search over the RAG index (`PROJECT_CODE` / `DOCUMENTATION`) | - |
| `tasks` | action (plan/update/list) | Manage the turn's execution plan | - |
| `memory` | action (write/read/list/get_subtask_output) | Shared working memory; recovers full raw output of a past tool call | - |
| `monitor_process` | process_id, max_lines? | Read output from background process | 1000 lines max |
| `ask_user` | question, options? | Ask user a question and wait for response | 10 min timeout |
| `sleep` | duration_ms | Pause execution | 30s max |
| `think` | thought | Explicit reasoning slot | - |

### WRITE Tools (11)

| Tool | Parameters | Description | Cost |
|------|------------|-------------|------|
| `create_new_file` | path, content | Create with parent dirs | Free |
| `code_editing` | path, old_string, new_string, replace_all | Search-and-replace | Free |
| `multi_edit` | edits[] | Atomic multi-file edit | Free |
| `multi_line_editor` | path, edit_description | LLM identifies line ranges | ~$0.02 |
| `advance_code_editing` | path, edit_description | Full file regeneration | ~$0.06 |
| `run_terminal_command` | command | Shell execution (CommandRule-protected) | **AGENT: ASK (default)** |
| `http_request` | url, method, headers, body, save_to_file | HTTP requests (GET/POST/PUT/DELETE), 5 MB limit, 60s timeout | Free |
| `run_code` | language, code | Execute Python/JavaScript/Kotlin Script snippets, 120s timeout | **OFF by default** |
| `run_process_background` | command | Start command in background, return process_id (CommandRule-protected) | **AGENT: ASK (default)** |
| `llm_call` | prompt, data?, file_path?, model? | Raw single-turn LLM call | ~$0.01 |
| `rename_symbol` | file, line, old_name, new_name | Project-wide rename in one call | Free |

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
├── Max search depth: 15
├── Excluded directories: 28 (.git, node_modules, build, etc.)
└── Excluded extensions: 47 (.class, .jar, .exe, .dll, etc.)

Layer 3: Terminal CommandRule engine
├── Regex rules with ALLOW / BLOCK / ASK actions (CommandRuleDefaults)
├── Global blocked patterns (pipes to shell, command substitution, etc.)
└── Unknown commands fall through to ASK (user approval)

Layer 4: ToolPermissions
├── Per-mode permissions (PLAN=read-only, AGENT=read-write)
├── Per-task overrides
└── Smart defaults (terminal and background process ASK in AGENT, run_code OFF, subagent ON in PLAN/AGENT)

Layer 5: NetworkPolicy
└── Single egress gate for web_search, fetch_webpage, http_request (honours no-egress)
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
    noEgressEnabled: Boolean = false,
    // ... plus responseFormat, thinking, reasoningEffort, taskId, subtaskId, source,
    //     contextContent, systemMessages, kwargs
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

Illustrative only. The current list of models and their prices lives in `ModelDefinitions` (resolved by `getModelPricing` in `Pricing.kt`; for OpenRouter the live per-model price from the model listing wins). When a provider reports the actual cost of a call in its response (for example OpenRouter `usage.cost`), that reported cost is what gets billed, not the local estimate.

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
- **OpenAI** - strips `$schema`/`default`; strict-mode compatibility check; Responses API uses flat `name/description/parameters/strict` shape.
- **Anthropic** - strips composition keywords (`oneOf`, `allOf`, `anyOf`).
- **Gemini** - strips `additionalProperties`; converts array `type` values to a single uppercase string; adds `nullable: true` for optional fields.

`NativeToolsFallbackTracker` is a process-wide set with **persistent backing**: when
`bind(configService)` is called at startup, the tracker hydrates from the
`models.native_tools_fallbacks` config key (comma-separated model ids) and every
subsequent `markFallback()` mirrors back to disk. If a provider returns HTTP 400
"tools not supported", the model is recorded once and skipped on every future
process - users no longer pay the 2-nudge probe cost on every fresh session.
Single-entry removal is exposed via `unmark(modelId)`; `clear()` clears both
in-memory and persisted state (intended for the user-facing "retry native tools
for all models" action).

A provider-side HTTP 500 on a malformed tool-call template (seen with Ollama) is treated differently: the turn retries that one call once on the JSON-in-text path and records nothing, because the failure is per-prompt, not per-model.

The OpenAI-compatible adapters (OpenRouter, Z.AI, Generic OpenAI, LM Studio)
share a single native-tools wiring through `OpenAICompatibleHelpers`:

- `buildOpenAIToolsArray(tools)` produces the canonical `[{type:function, function:{name, description, parameters}}]` shape, sanitized via `ToolSchemaSanitizer.forOpenAI`.
- `parseOpenAIToolCalls(rawToolCalls)` extracts the `tool_calls` field from a chat-completions response into provider-agnostic `NativeToolCall` entries (id, name, argumentsJson).
- The streaming path accumulates `tool_calls` deltas via `ToolCallAccumulator` and converts to `NativeToolCall` only when `tools` were actually requested in the request body.

### No-Egress Mode

When enabled, blocks all cloud providers:
- **Allowed:** Ollama, LM Studio (local)
- **Blocked:** every other provider (OpenAI, Anthropic, Gemini, OpenRouter, Custom OpenAI, Z.AI)

The same flag (`general.no_egress_enabled`) also blocks outbound traffic from tools: `NetworkPolicy` is the single gate consulted by `web_search`, `fetch_webpage` and `http_request`. It fails closed: if the config cannot be read it falls back to the last known value, and with no history it blocks.

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
| **Databases** | PostgreSQL, SQLite, Database (generic) |
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
| `TOOLS` | MCP tools registered in ToolRegistry as `mcp_<server>_<tool>` (Agent mode) |
| `CONTEXT` | MCP tools executed as context providers; never visible to the model as tools |

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

Any other value is treated as a literal model string (provider inferred from the prefix:
`gpt-`/`o1`/`o3` -> openai, `claude-` -> anthropic, `gemini-` -> gemini, else ollama).

### Built-in Subagents (21)

#### Quality & Security

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `code-reviewer` | default | 5 | Code quality, patterns, bugs, maintainability |
| `security-engineer` | default | 10 | DevSecOps, vulnerability management, zero-trust |
| `architect-reviewer` | default | 8 | System design evaluation, tech stack decisions |

> All 21 built-ins ship with `model: default`. There are no legacy name aliases: `!security-reviewer` or `!security-auditor` returns "Subagent not found" with the list of available ones.

#### Core Development

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `api-designer` | default | 5 | REST/GraphQL API design, OpenAPI specs |
| `frontend-developer` | default | 5 | UI components, state management, frontend architecture |
| `fullstack-developer` | default | 5 | End-to-end features (DB → API → UI) |
| `ui-designer` | default | 5 | Design systems, component libraries, visual consistency |
| `refactoring-specialist` | default | 5 | Code smell elimination, incremental refactoring |

#### Documentation

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `documentation-engineer` | default | 3 | Documentation systems, API docs, tutorials |
| `api-documenter` | default | 3 | OpenAPI specs, interactive API docs, code examples |
| `technical-writer` | default | 3 | User guides, API references, tutorials |

#### Infrastructure

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `sre-engineer` | default | 5 | SLO/SLI, reliability, toil reduction |

#### Business & Product

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `business-analyst` | default | 3 | Requirements, process modeling, gap analysis |
| `product-manager` | default | 3 | Feature prioritization, roadmap, product strategy |
| `project-manager` | default | 3 | Project planning, task breakdown, risk tracking |
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
User (PLAN/AGENT mode): !security-engineer Review this authentication code
    ↓
CoreSessionService → SubagentRouter.parseSubagentInvocation()
    → ("security-engineer", "Review this authentication code")
    ↓
SubagentRouter.invoke()
    ├── SubagentRegistry.getSubagent("security-engineer")
    ├── map definition → TurnProfileOverrides
    └── runTurnCallback → AgentRouter.runTurn(runProfile=SUBAGENT)
        └── AgentTurnLoop.runTurn() [shared loop]

The model itself spawns subagents through the `invoke_subagent` tool; nesting is capped at depth 3 (`MAX_SUBAGENT_DEPTH` in `InvokeSubagentTool`).
```

---

## 11. Session Management

**Location:** `SessionManager` is in the plugin (`intellij-plugin/.../services/session/`); the components it composes live in `:core` (`core/src/main/kotlin/pl/jclab/refio/core/session/`), so the TUI and the plugin share them.

### SessionManager Components

| Component | Purpose |
|-----------|---------|
| `SessionManager` | Plugin facade, exposes public API |
| `CoreSessionService` | Mode routing and turn execution (CHAT → ChatRouter, PLAN/AGENT → AgentRouter.runTurn), task status updates |
| `SessionStateManager` | 16 StateFlows for reactive UI binding |
| `SessionLifecycleService` | Create/switch/load sessions |
| `MessageDispatcher` | Mode-specific routing (CHAT vs PLAN/AGENT) |
| `SubtaskTracker` | Subtask CRUD operations |
| `PromptStateTracker` | Pending context refs, user input |

### Message Routing

```kotlin
// CoreSessionService.sendMessage() (called by SessionManager.sendMessage())
when (session.mode) {
    TaskMode.CHAT -> sendMessageUsingChatWorkflow()
        → ChatRouter.chat() → ChatService
    TaskMode.PLAN, TaskMode.AGENT -> sendMessageUsingTurnLoop()
        → AgentRouter.runTurn() → AgentTurnLoop.runTurn()
}
```

### StateFlows (16)

- `activeSession`, `sessions`: Current session and session list
- `messages`, `subtasks`: Conversation and tool-call steps
- `activePlan`, `planSteps`: Current plan (if any)
- `selectedModel`, `thinkingEnabled`, `noEgressEnabled`: Toolbar state
- `isPaused`, `isGenerating`: Execution state
- `pendingContextRefs`, `pendingUserInput`: Prompt state
- `contextSectionTokens`, `totalEstimatedTokens`: Token usage
- `toolCallProgress`: Live tool-call progress

### Message Reconciliation

`MessageDispatcher.reconcileMessages(currentInMemory, dbMessages, sessionId)` runs on every
mid-turn reload. During a turn several writers push transient bubbles into the in-memory list
(the user's prompt, the streaming assistant, per-subagent streams, per-tool-call bubbles)
**before** they exist in the DB, and the persisted copies come back under different ids. The
reconciler keeps a transient only until its DB counterpart exists, and never renders both.

| In-memory message | Kept after reload? |
|---|---|
| `role == "system"` (in-memory notices, guardian nudges) | Always |
| Live transient (`isStreaming`, or a tool call with `status == EXECUTING`) | Yes |
| Top-level user prompt whose text is not yet in the DB | Yes |
| Anything else (finished assistant / tool transients) | Dropped, the DB copy takes over |

One tool call appears under two ids: `temp-<toolCallId>` in memory and
`<messageId>:tc<index>` once the assistant row is persisted. Id matching never relates the
two, so both used to render. They are now deduplicated by `toolCallId`: while the call is
live the transient wins (it is the only copy carrying streamed content, the persisted display
row is built empty) and its DB twin is held back; once it stops streaming the transient is
dropped and the DB row takes over. `agentName` is deliberately not a dedup key - an agent can
be invoked many times, and holding rows back by name would hide its earlier turns.

### Live Tool-Call Bubble (`CoreMessageToolCallListener`)

| Hook | Behavior |
|---|---|
| `onCreateTempMessage` | Appends `temp-<toolCallId>` marked `isStreaming` / `isToolStreaming` **from creation**. Marking it live only at the first delta left a window where a reload dropped it; every later delta then mapped over a missing id, the list came back equal, and the `StateFlow` stopped emitting for the whole generation (the frozen char-counter bug). |
| `onUpdateTempMessage` | Pushes the accumulated text, skipping deltas that did not grow it (adapters emit many empty deltas; the text only ever grows by appending, so equal length means equal content). |
| `onFinalizeTempMessage` | Clears both live flags and attaches the result summary, so the persisted row can take over on the next reload. |

Regression coverage: `MessageDispatcherReconcileTest`, `CoreMessageToolCallListenerTest`.

### Chat Rendering (IntelliJ `ChatView`)

`ChatView` collects `SessionStateManager.messages` **directly** on `Dispatchers.IO`, renders
immediately, then applies a trailing `delay(300 ms)` - a leading-edge throttle, not a
debounce. `StateFlow` conflation guarantees the newest value still lands after the throttle,
so the final frame always renders while a burst of streaming deltas costs at most one rebuild
per interval. Layout changes revalidate the enclosing `JBScrollPane`, because
`messagesPanel.revalidate()` stops at the `JViewport` validate root. See
[ARCHITECTURE.md - Chat Render Pipeline](ARCHITECTURE.md#chat-render-pipeline-intellij) for
the full rationale and the packaging constraint (`kotlinx-coroutines` must not be bundled
with the plugin).

---

## 12. Database Schema

### Core Tables

```sql
-- Sessions (Chat/Plan/Agent) - also the canonical row for live token/cost stats
TasksTable "tasks" (id, name, mode, status, read_only, pinned, execution_mode,
            requires_plan_approval, plan_approved, rate, tokens_in, tokens_out,
            cached_tokens, cost_usd, ui_state, core_api_version, project_id,
            project_path, source_plan_id, plan_version, completion_condition,
            created_at, updated_at)
    -- status (TaskStatus): NEW, PENDING, PLANNED, RUNNING, SUCCESS, FAILED, INCOMPLETE, CANCELED
    --         (AGENT turns flip RUNNING on entry; CANCELED when the user presses Stop)

-- Execution steps - also carry per-subtask LLM cost (sub-LLM tools like
-- advance_code_editing, multi_line_editor, fetch_webpage)
SubtasksTable "subtasks" (id, task_id, order_index, kind, status, description,
               params_json, step_plan_json, summary, requires_approval,
               approval_status, approved_at, result, error_message, error_stacktrace,
               llm_model, llm_provider, input_tokens, output_tokens, cost_usd,
               latency_ms, snapshot_id_before_write, created_at, updated_at,
               started_at, completed_at)
    -- status uses the same TaskStatus enum (tool steps: PENDING, RUNNING, SUCCESS, FAILED)

-- Conversation
ChatMessagesTable "chat_messages" (id, task_id, agent_instance_id, agent_name,
                   agent_depth, role, content, thinking, metadata, tool_calls_json,
                   tool_call_id, subtask_id, is_summarized, raw_output, tokens_in,
                   tokens_out, cost, created_at, seq)

-- File snapshots (for rollback): one group per tool execution, one row per file
SnapshotGroupsTable "snapshot_groups" (id, task_id, subtask_id, created_at)
SnapshotsTable "snapshots" (id, task_id, group_id, file_path, content_hash,
               content_compressed BLOB, original_size, compression_ratio, created_at)

-- API call logs (audit trail; NOT a stats source; secrets redacted)
ApiLogsTable "api_logs" (id, task_id, subtask_id, provider, model, endpoint,
              request_source, request_payload, response_payload, http_status,
              input_tokens, output_tokens, cost_usd, latency_ms, error_message,
              error_type, created_at)
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
                 mime_type, metadata, content_type, source_url, indexed_at, last_modified)

-- Code chunks
IndexChunksTable (id, file_id, chunk_index, content, content_hash, metadata,
                  start_line, end_line, start_char, end_char, created_at)
    -- UNIQUE(file_id, chunk_index)

-- Vector embeddings
EmbeddingsTable (id, chunk_id, model, vector BLOB, dimensions, created_at)
    -- UNIQUE(chunk_id, model)

-- Also: IndexingProgressTable (indexing status per project root),
--       DocumentationSourcesTable (@docs sources: url, crawl_depth, pages_indexed, status)
```

### Configuration Tables

```sql
-- Key-value config
ConfigTable (key, value, scope, project_id, task_id, description, created_at, updated_at)
    -- scope: APP, PROJECT, TASK

-- System prompts
PromptsTable (id, name, type, content, description, is_custom, is_enabled,
              order_index, created_at, updated_at)

-- MCP servers
MCPServersTable (id, project_id, config_json, enabled, created_at, updated_at)

-- Cached project analysis
ProjectAnalysisReportsTable (id, project_root, analyzed_at, checksum, report_json, ...)
```

### Multi-Agent Tables

```sql
AgentSessionsTable (id, project_id, name, status, definition_yaml, created_at, completed_at)
AgentInstancesTable (id, session_id, task_id, name, profile, status, model,
                     task_description, depends_on, result, tokens_in, tokens_out,
                     cost_usd, started_at, completed_at, created_at)
AgentEventsTable (id, session_id, source_agent_id, event_type, correlation_id,
                  payload_json, timestamp)   -- event log replayed by AgentEventSqlRepository
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
├── Excluded directories (28)
└── Excluded extensions (47)

Layer 3: CommandRule System (regex-based, CommandRuleDefaults)
├── 25 BLOCK rules (destructive: rm -r/-f, Windows del/rmdir and PowerShell Remove-Item
│   with recurse/force, mkfs, dd, git reset --hard, git clean -f, force push, npm publish, fork bombs)
├── 26 ALLOW rules (20 read-only / VCS programs such as git, ls, cat, grep, rg + echo, printf, bare env)
├── ASK rules (docker, kubectl, ssh, scp, rsync, wget, sudo, systemctl, catch-all for unknown commands)
└── Priority: BLOCK > ALLOW > ASK > default ASK

Layer 4: ToolPermissions (3-level)
├── Per-mode permissions: ON / ASK / OFF
├── Per-task overrides via ConfigRouter
├── Smart defaults (run_terminal_command / run_process_background = ASK in AGENT, run_code = OFF by default)
└── ToolApprovalService: user approval flow with session trust rules and 5-min timeout

Layer 5: Mid-Execution Control
├── PendingUserMessageQueue: user can send messages while agent runs
├── ToolRejectedException: user rejection breaks loop and returns to prompt
└── ExecutionMode: AUTO (autonomous) / INTERACTIVE (step-by-step approval)

Layer 6: No-Egress Mode
├── Blocks cloud providers
├── Allows only local (Ollama, LM Studio)
└── NetworkPolicy blocks web_search / fetch_webpage / http_request too (fails closed)

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
SessionManager.sendMessage() → CoreSessionService
    ↓
ChatRouter.chat()
    ↓
ChatService.chat()
    ├── ContextService.buildProjectContext()
    │   ├── Project analysis (cached)
    │   ├── Conversation history
    │   └── @mention resolution (incl. @codebase RAG search)
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
Loop (max 200, guardrails may stop it earlier):
├── Build prompt with tools
├── LLM response with tool calls
├── Create subtasks (PENDING)
├── Execute tools (RUNNING → SUCCESS/FAILED)
│   └── SnapshotService.createSnapshot() [before write]
├── Summarize results
├── Add to conversation history
└── Continue if more tool calls
    ↓
Final text response → completion guardian (deliverable check, at most one re-entry)
    ↓
TurnResult to UI → task.status SUCCESS / FAILED / INCOMPLETE
```

### RAG Indexing Flow

```
IDE Startup
    ↓
RagIndexingStartup (or the manual ReindexRagAction)
    ↓
BackgroundIndexingTask → RagRouter.indexProjectForRag()
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

Refio includes a standalone CLI with a full-screen TUI that mirrors the IntelliJ plugin GUI. The CLI uses the same `:core` module as the IntelliJ plugin - all execution modes, tools, context providers, and RAG capabilities are available.

### Headless Mode

`--headless` runs a single turn without the TUI, on the same `AgentTurnLoop` path as the plugin (`agentRouter.runTurn` for PLAN/AGENT), so it can reproduce runtime behavior from a script. It needs one of `--prompt`, `--prompt-file` or `--multi-agent <file>`. Useful flags: `--output json --output-file <f>` (writes `run.json` with status, tokens, cost, iterations), `--debug-level`, `--config k=v` / `--config-file` (run-scope config overrides), `--print-config` (prints the resolved config, no LLM call), `--max-cost <usd>`, `--auto-approve "<regex>"` (approve only matching tool calls, reject the rest), `--no-egress`, `-v/--verbose`. Progress goes to stderr (`HeadlessTurnListener`); the process exit code is 0 only for `SUCCESS` (`HeadlessExit.forStatus`). In headless mode `--model` applies to every model slot, including the editing tools.

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
- **No separate status bar** - mode, cost, streaming indicator, and token count are displayed in the tab bar (right-aligned). The cursor always blinks at the input prompt, never at the bottom of the screen.
- **Split-pane** when any tab is active (55% left / 45% right). **Full-width** chat when no tab is selected.
- **Responsive** - layout adapts to terminal resize in real time (300ms polling).

### TUI Technology Stack

| Component | Library | Purpose |
|-----------|---------|---------|
| Terminal rendering | Mordant 3.0.1 | ANSI colors, styled text, terminal size detection |
| Raw input | JLine3 3.26.3 | F-keys, Ctrl+combinations, escape sequence parsing |
| CLI arguments | Clikt 5.0.2 | `--project`, `--mode`, `--model`, `--no-egress`, `--headless`, ... |
| State management | Kotlin Coroutines Flow | 20 StateFlows → merge → reactive TuiState |

### TUI Architecture

```
TuiApp (entry point - launchTuiApp())
│
├── Detects interactive mode: System.console() != null
│   ├── Interactive: alternate screen buffer, raw JLine3 input, F-key navigation
│   └── Non-interactive: inline rendering, line-based input (`/prompt`, `:shortcuts`)
│
├── Three concurrent coroutines:
│   ├── Render loop - stateFlow.collect { renderer.render(state) }
│   ├── Resize watcher - polls terminal.size every 300ms, forceRender on change
│   └── Input loop - TuiInputHandler.startInputLoop(viewModel)
│
├── TuiViewModel (coordinator ~1588 LOC, MVVM pattern)
│   ├── Delegates to 3 sub-ViewModels:
│   │   ├── TuiChatViewModel (messages, streaming, autocomplete)
│   │   ├── TuiSessionViewModel (session lifecycle, history, settings)
│   │   └── TuiObservabilityViewModel (logs, API logs, debug state)
│   ├── 20 MutableStateFlows (merged from coordinator + sub-ViewModels)
│   ├── All flows merged via merge().map { buildCurrentState() }
│   ├── Any flow change triggers stateFlow emission → re-render
│   ├── sendMessage() → TuiChatViewModel: CHAT → chatRouter.chat(), PLAN/AGENT → agentRouter.runTurn()
│   ├── Settings: getConfigSection(), updateConfig() via ConfigRouter
│   └── Autocomplete: triggerAutocomplete(), updateAutocompleteFilter()
│
├── TuiRenderer (full-screen compositor)
│   ├── Hash-based skip: only re-renders when state.hashCode() changes or terminal resizes
│   ├── Cursor hidden during redraw (\u001b[?25l), shown at input after (\u001b[?25h)
│   ├── In-place overwrite (\u001b[H) - no flicker. Full clear (\u001b[2J) only on resize.
│   ├── renderTabBar() - tabs + right-aligned status info (mode, cost, streaming)
│   ├── renderFullWidthChat() - chat messages buffer + prompt buffer (no split)
│   ├── renderSplitPane() - left (chat+prompt) + right (tab view), merged side-by-side
│   ├── renderAutocompletePopup() - ANSI cursor-positioned overlay above prompt
│   └── positionCursorAtInput() - places cursor at "> {input}_" position
│
├── TuiRenderBuffer (ANSI-aware line composition)
│   ├── visibleLength() - measures string width ignoring ANSI escape codes
│   ├── fitToWidth() - pads or truncates preserving ANSI state
│   ├── mergeSideBySide() - combines left + right buffers with separator column
│   └── Each view renders into a TuiRenderBuffer → compositor merges them
│
├── TuiInputHandler (dual-mode input)
│   ├── Raw mode (real TTY): JLine3 reader, single-char dispatch, escape sequence parsing
│   ├── Line mode (IDE/pipe): BufferedReader from System.in, `/prompt`, `:tab` shortcuts
│   ├── dispatchAction() handles: tab switching, typing, backspace, send, autocomplete
│   └── Slash commands: /quit, /clear, /help, /mode, /history, /settings, /set section.key value
│
├── TuiWorkflowListener (streaming bridge)
│   ├── synchronized(accumulatedContent) on all methods - no race conditions
│   ├── @Volatile completed flag - prevents late chunks after onStreamComplete
│   ├── reset() called before each new request - clears accumulated content
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
| Tools | `tools` | All registered tools × Plan/Agent mode permission matrix (ON/ASK/OFF) |
| Subagents | `subagents` | Enable/disable individual subagent profiles |
| Advanced | `advanced`+`limits` | Security (no-egress, read-only), timeouts, limits, performance |
| Theme | - | ANSI color preview (roles, status, agents, log levels) |

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
- `/history` - Open session history
- `/export <path>` - Export conversation to Markdown file
- `/resend` - Resend last user message
- `/rewind [N]` - Rewind to message N and resend
- `/edit [N]` - Edit user message N (loads into input for re-editing)
- `/copyall` - Copy entire conversation to clipboard
- `/rate +/-` - Rate conversation quality

#### Mode, Model & Toggles

| Key | Action | Details |
|-----|--------|---------|
| Shift+Tab | Cycle mode | CHAT → PLAN → AGENT → CHAT. Mode determines available tools. |
| Ctrl+O | Select model | Opens popup with available models. Arrow keys to select, Enter to confirm. |
| Ctrl+T | Toggle thinking | Enable/disable reasoning mode (extended thinking for supported models). |
| Ctrl+E | Toggle execution | AUTO (agent runs autonomously) / INTERACTIVE (step-by-step approval). |
| Ctrl+N | Toggle no-egress | Local-only mode - blocks all cloud provider API calls. |

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
| Ctrl+F | Cycle agent filter (multi-agent mode - filter chat by agent) |

#### Autocomplete

| Trigger | Action | Candidates |
|---------|--------|------------|
| `@` | Context autocomplete | @file, @folder, @codebase, @grep, @diff, @url, @docs, @clipboard, etc. |
| `!` | Subagent autocomplete | !review, !security, !architect, !docs, and custom subagents |
| `/` | Slash prompt autocomplete | /explain, /refactor, /test, /fix, /implement, /optimize, /security-review, etc. |

When the autocomplete popup is visible:
- **Arrow Down / Tab** - Next candidate
- **Arrow Up** - Previous candidate
- **Enter** - Accept selection
- **Escape** - Dismiss
- **Keep typing** - Filters candidates in real-time

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
./gradlew :intellij-plugin:runIde       # Run in sandbox IDE
./gradlew :intellij-plugin:buildPlugin  # Build ZIP distribution
./gradlew :cli:installDist              # Build standalone CLI
./gradlew test                          # Run tests
./gradlew :intellij-plugin:check        # Includes detectSensitiveLogging
./gradlew :core:jacocoTestCoverageVerification  # Coverage gate (part of :core:check)
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
| CoreSessionService | core/session/ | Mode routing (CHAT vs PLAN/AGENT), turn execution, task status |
| TurnGuardrails | core/services/turn/ | Loop, repetition, error-rate, blocked/denied-tool and no-op-write trackers |
| NextSpeakerJudgeGuardian | core/services/turn/ | Terminal-point deliverable check; one bounded re-entry, else `INCOMPLETE` |
| ConversationSummaryService | core/services/ | Conversation auto-compaction (80% trigger, 45% target, keep last 4) |
| ModelWindow | core/llm/ | Single resolver of a model's context window |
| ProviderRequestGate | core/services/ | Per-provider cap on concurrent LLM requests (Ollama excluded) |
| NetworkPolicy | core/security/ | Single egress gate for web tools under no-egress |
| MCPManager | core/context/mcp/ | MCP server lifecycle |
| SubagentRouter | core/subagents/ | Subagent operations |
| TuiApp | cli/tui/ | TUI entry point (launchTuiApp) |
| TuiViewModel | cli/tui/state/ | TUI coordinator (~1588 LOC, delegates to TuiChatViewModel, TuiSessionViewModel, TuiObservabilityViewModel) |
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
LM_STUDIO_BASE_URL   # Custom LM Studio endpoint (+ LM_STUDIO_API_KEY)
ZAI_API_KEY          # Z.AI
CUSTOM_OPENAI_BASE_URL / CUSTOM_OPENAI_API_KEY  # Custom OpenAI-compatible provider
```
