# Refio - Technical Architecture Overview

> **Last Updated:** 2026-01-11
> **Version:** 0.0.1
> **Status:** Active Development

This document provides a comprehensive technical overview of Refio - a local-first AI coding assistant for IntelliJ IDEA.

---

## Table of Contents

1. [Project Philosophy](#1-project-philosophy)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Core Components](#3-core-components)
4. [Execution System](#4-execution-system)
5. [RAG System](#5-rag-system)
6. [Context System](#6-context-system)
7. [Tools System](#7-tools-system)
8. [LLM Integration](#8-llm-integration)
9. [MCP Protocol](#9-mcp-protocol)
10. [Subagents System](#10-subagents-system)
11. [Database Schema](#11-database-schema)
12. [Security Model](#12-security-model)
13. [Key Data Flows](#13-key-data-flows)

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
5. **Cost-Aware:** Tested with local and smaller cloud-hosted models, designed to minimize usage costs

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         IntelliJ IDEA Plugin                            │
├─────────────────────────────────────────────────────────────────────────┤
│  UI Layer (Swing)                                                       │
│  ├── RefioToolWindowFactory → RefioMainPanel → ChatView                 │
│  ├── SwingWorkflowListener (Swing adapter for WorkflowEventListener)    │
│  └── Settings / StatusBar / Autocomplete                                │
├─────────────────────────────────────────────────────────────────────────┤
│  Service Layer (Project-Scoped)                                         │
│  ├── SessionManager (orchestrates session state)                        │
│  │   ├── SessionStateManager (reactive state)                           │
│  │   ├── SessionLifecycleService (create/switch/load)                   │
│  │   ├── MessageDispatcher (mode-specific routing)                      │
│  │   ├── ExecutionMonitor (step execution lifecycle)                    │
│  │   └── SubtaskTracker (subtask CRUD)                                  │
│  ├── CoreConnectionManager (router factory)                             │
│  └── StepExecutionService (UI ↔ execution bridge)                       │
├─────────────────────────────────────────────────────────────────────────┤
│  Workflow Layer (Intent-Based Routing) - NEW                            │
│  ├── WorkflowOrchestrator (orchestrates execution)                      │
│  │   ├── IntentRouter (fast paths + LLM classification)                 │
│  │   ├── ChatExecutor, PlanExecutor, StepExecutor                       │
│  │   ├── SubagentExecutor, SingleToolExecutor                           │
│  │   └── WorkflowEventListener (streaming/progress callbacks)           │
├─────────────────────────────────────────────────────────────────────────┤
│  Core Layer (In-Process API)                                            │
│  ├── CoreApiRouter (facade + domain delegation via CoreDependencies)    │
│  │   ├── ChatRouter → ChatService                                       │
│  │   ├── PlanningRouter → PlanningService                               │
│  │   ├── PlanRouter → PlanRepository (plan specifications)              │
│  │   ├── RagRouter → RagSearchService                                   │
│  │   ├── TaskRouter, SubtaskRouter, ToolRouter                          │
│  │   ├── PromptsRouter → PromptsService                                 │
│  │   └── ApiLogsRouter → ApiLogRepository                               │
│  ├── UnifiedStepExecutor (strategy-based execution)                     │
│  ├── ContextService (dynamic context building)                          │
│  └── ToolRegistry (tool catalog)                                        │
├─────────────────────────────────────────────────────────────────────────┤
│  Infrastructure Layer                                                   │
│  ├── LLMClient (unified provider interface)                             │
│  │   └── Adapters: Anthropic, OpenAI, Gemini, Ollama, OpenRouter, LMStudio
│  ├── DatabaseFactory (SQLite + Exposed ORM)                             │
│  ├── MCPManager (core/context/mcp/) - Model Context Protocol            │
│  └── EmbeddingsService (vector generation)                              │
└─────────────────────────────────────────────────────────────────────────┘
```

### Package Structure (After Refactor)

```
src/main/kotlin/pl/jclab/refio/
├── api/                    # Public API models
│   └── models/             # Session, ExecutionMode, CodeSnippet
├── core/                   # Core business logic
│   ├── api/                # Router layer (9 routers)
│   │   ├── Router.kt       # Base interface for domain routers
│   │   ├── CoreDependencies.kt # Dependency container
│   │   └── routers/        # ChatRouter, PlanRouter, etc.
│   ├── workflow/           # Workflow v2 (Intent-Based Routing)
│   │   ├── IntentRouter.kt         # Intent resolution (fast paths + LLM)
│   │   ├── IntentClassificationService.kt # LLM classification
│   │   ├── WorkflowOrchestrator.kt # Routes to executors
│   │   ├── WorkflowEventListener.kt # Event callbacks
│   │   ├── executors/      # ChatExecutor, PlanExecutor, StepExecutor, etc.
│   │   └── models/         # WorkflowIntent, WorkflowRequest, UIState
│   ├── context/            # Context providers
│   │   ├── providers/      # @file, @folder, @current, @recent, etc.
│   │   └── mcp/            # Model Context Protocol (MCPManager, etc.)
│   ├── db/                 # Database tables & repositories
│   │   ├── PlansTable.kt, PlanStepsTable.kt # Plan specifications
│   │   ├── migrations/     # Schema migrations
│   │   └── repositories/   # PlanRepository, PlanStepRepository, etc.
│   ├── llm/                # LLM integration
│   │   └── adapters/       # Provider-specific adapters (in separate package)
│   ├── models/             # DTOs and data models
│   │   ├── api/            # API request/response
│   │   ├── context/        # Context DTOs
│   │   └── streaming/      # Streaming models
│   ├── prompts/            # Prompt templates
│   ├── subagents/          # Subagent system
│   │   ├── models/         # SubagentDefinition, SubagentInvocation
│   │   ├── SubagentRouter.kt       # API boundary
│   │   ├── SubagentRegistry.kt     # Lazy loading + cache
│   │   ├── SubagentParser.kt       # Markdown parsing
│   │   ├── SubagentExecutor.kt     # Execution engine
│   │   └── SubagentToolFilter.kt   # Tool permissions
│   ├── services/           # Core services
│   │   ├── analysis/       # Code analysis, embeddings
│   │   │   └── project/    # RichProjectAnalysisEngine
│   │   ├── execution/unified/ # UnifiedStepExecutor, strategies
│   │   ├── orchestration/  # ReflectionEngine, PlanModifier
│   │   ├── monitoring/     # GlobalMetrics, SystemMonitor
│   │   ├── logging/        # CoreLogger
│   │   └── rag/            # RagSearchConfig
│   ├── tools/              # Tool system
│   │   ├── PathSandbox.kt  # Project root restriction
│   │   ├── base/           # Tool, ToolRegistry, ToolFactory
│   │   ├── implementations/# 10 tool implementations
│   │   └── security/       # FileLimits, CommandDenylist
│   └── security/           # SecureLogger
├── services/               # Plugin services (project-scoped)
│   ├── session/            # SessionManager (8 components)
│   ├── core/               # CoreConnectionManager
│   ├── execution/          # StepExecutionService
│   ├── logging/            # DualLogger, PluginLogger
│   ├── notification/       # NotificationService
│   └── rag/                # BackgroundIndexingTask, RagProgressService
├── startup/                # ProjectStartupActivity
└── ui/                     # UI components
    ├── toolwindow/         # RefioToolWindowFactory, RefioMainPanel
    ├── listeners/          # SwingWorkflowListener
    ├── components/         # chat/, toolbar/, autocomplete/, etc.
    ├── settings/           # 12+ settings panels
    ├── theme/              # LCATheme
    └── completion/         # RefioCompletionContributor
```

---

## 3. Core Components

### 3.1 CoreApiRouter

**Location:** `core/api/CoreApiRouter.kt` (~1,735 LOC after split)

The API boundary between plugin UI and core logic. Follows **facade pattern** with domain delegation:

```kotlin
class CoreApiRouter(
    private val toolRegistry: ToolRegistry? = null,     // Optional, project-specific
    private val projectRoot: Path? = null,              // For RAG/context
    private val ideProject: Project? = null             // For IDE integration
) {
    // 11 domain routers (lazy-initialized)
    val chatRouter by lazy { ChatRouter(...) }
    val planningRouter by lazy { PlanningRouter(...) }
    val planRouter by lazy { PlanRouter(...) }          // NEW: Plan specifications
    val agentRouter by lazy { AgentRouter(...) }
    val configRouter by lazy { ConfigRouter(...) }
    val toolRouter by lazy { ToolRouter(...) }
    val ragRouter by lazy { RagRouter(...) }
    val taskRouter by lazy { TaskRouter(...) }
    val subtaskRouter by lazy { SubtaskRouter(...) }
    val promptsRouter by lazy { PromptsRouter(...) }
    val apiLogsRouter by lazy { ApiLogsRouter(...) }
}
```

**Key Design Decisions:**
- In-process calls (no HTTP transport yet)
- Optional dependencies for CLI/headless modes
- Lazy router initialization
- Both facade methods and direct router access

### 3.2 Workflow v2 (Intent-Based Routing)

**Location:** `core/workflow/`

The Workflow v2 system provides intent-based routing for all user interactions, replacing the previous mode-based dispatch.

**Key Components:**

```kotlin
// IntentRouter - Determines workflow intent
class IntentRouter(
    private val subtaskRepository: SubtaskRepository,
    private val subagentRouter: SubagentRouter?,
    private val classificationService: IntentClassificationService?,
    private val configService: ConfigService?
) {
    suspend fun determineIntent(uiState: UIState, projectAnalysis: String?): WorkflowIntent
}

// WorkflowOrchestrator - Orchestrates execution
class WorkflowOrchestrator(
    private val intentRouter: IntentRouter,
    private val chatExecutor: ChatExecutor,
    private val planExecutor: PlanExecutor,
    private val stepExecutor: StepExecutor,
    private val subagentExecutor: SubagentExecutor?,
    private val singleToolExecutor: SingleToolExecutor?
) {
    suspend fun execute(request: WorkflowRequest, listener: WorkflowEventListener): IntentResult
}
```

**Intent Resolution Priority:**
1. **Subagent invocation** (`!subagent-name`) - always fast path
2. **Pending subtasks in AUTO mode** - continuation fast path
3. **LLM classification** (when enabled via config) - analyzes any mode
4. **Mode-based fallback** (CHAT → Chat, PLAN/AGENT → Plan)

**WorkflowIntent Types:**
| Intent | Description |
|--------|-------------|
| `Chat` | Direct LLM conversation |
| `Plan` | Multi-step plan generation |
| `ExecuteStep` | Execute a specific subtask |
| `Subagent` | Invoke a specialized subagent |
| `AskClarification` | Request clarification from user |
| `AnswerQuestion` | Provide answer to pending question |
| `ExecuteTool` | Direct single-tool execution |

**Executors:**
- `ChatExecutor` - Chat mode with streaming
- `PlanExecutor` - Planning with streaming
- `StepExecutor` - Step execution with orchestration
- `SubagentExecutor` - Subagent invocation
- `SingleToolExecutor` - Direct tool execution

**UI Integration:**
`SwingWorkflowListener` (in `ui/listeners/`) bridges workflow events to Swing UI:
- `onChatStarted()`, `onPlanningStarted()`, `onSubagentStarted()`
- `onStreamChunk()`, `onStreamComplete()`
- `onIntentClassificationStarted()`, `onIntentClassificationResult()`

### 3.3 SessionManager (Refactored)

**Location:** `services/session/` (8 components)

Project-level service managing session state and execution:

```
SessionManager (facade)
├── SessionStateManager         # 11 StateFlows for UI binding
├── SessionLifecycleService     # Create/switch/load sessions
├── SessionMessageCoordinator   # Entry point for messages
├── MessageDispatcher           # Mode-specific routing (CHAT vs PLAN/AGENT)
├── ExecutionMonitor            # Step execution lifecycle
├── SubtaskTracker              # Subtask CRUD operations
├── PromptStateTracker          # Pending context/input state
└── StatusBarIntegration        # UI status bar reference
```

**State Management:**
- All state in `SessionStateManager` via `StateFlow`
- Thread-safe updates via `Mutex`
- UI observes flows reactively

**Settings Cascade:**
```
TASK (session) → PROJECT → APP → DEFAULT
```

**Plan Management Methods:**
- `loadPlan()` - Load active plan for PLAN session
- `addPlanStep()` - Add new step to plan
- `updatePlanStep()` - Modify existing step
- `deletePlanStep()` - Remove step from plan
- `reorderPlanSteps()` - Reorder steps
- `finalizePlan()` - Mark plan as READY for execution
- `executePlan()` - Create AGENT session and copy PlanSteps → Subtasks

### 3.4 UnifiedStepExecutor

**Location:** `core/services/execution/unified/`

Single execution loop with pluggable strategies:

```kotlin
interface ExecutionStrategy {
    suspend fun findNextStep(taskId: String): Subtask?
    suspend fun preparePlan(subtask: Subtask, listener: ExecutionEventListener?): StepPlan
    suspend fun executeStep(subtask: Subtask, plan: StepPlan, listener: ExecutionEventListener?): StepResult
    suspend fun shouldContinue(result: StepResult, listener: ExecutionEventListener?): Boolean
    suspend fun onExecutionComplete(taskId: String, stats: ExecutionStats)
}
```

**Strategies:**
| Strategy | Reflection | Plan Modification | User Questions |
|----------|------------|-------------------|----------------|
| `SimpleAutoStrategy` | No | No | No |
| `OrchestrationStrategy` | Yes | Yes | Yes |
| `InteractiveStrategy` | Planned | Planned | Planned |

---

## 4. Execution System

### 4.1 Execution Modes

**Chat Mode:**
- Direct LLM conversation
- Full project context attached
- No tool execution

**Plan Mode:**
- Creates editable plan specifications (stored separately from execution)
- Read-only step generation via LLM conversation
- Steps stored in PlansTable/PlanStepsTable
- Supports iterative refinement (add/edit/delete/reorder steps)
- Only READ_ONLY tools available
- Finalizes plan to READY status before execution

**Agent Mode:**
- Full read/write execution
- Interactive or auto-approve
- Auto mode requires a generated plan; optional plan approval gate when enabled
- Snapshot before each write
- Rollback capability

### 4.2 Plan as Specification Pattern

Refio implements a **clean separation** between plan creation (specification) and plan execution:

```
PLAN Mode (Specification)           AGENT Mode (Execution)
┌──────────────────────┐           ┌──────────────────────┐
│ PlanSteps            │           │ Subtasks             │
│ - Editable           │  Snapshot │ - Immutable during   │
│ - Stored separately  │  ══════>  │   execution          │
│ - Versioned          │   Copy    │ - Execution context  │
│ - Iterative refine   │           │ - Separate session   │
└──────────────────────┘           └──────────────────────┘
```

**Key Components:**

**PlansTable:**
- `id` - Plan specification ID
- `sessionId` - References PLAN session
- `name` - Plan name
- `status` - DRAFT → READY → EXECUTING → EXECUTED
- `version` - Incremented on modifications

**PlanStepsTable:**
- `planId` - References plan specification
- `orderIndex` - Step ordering (unique constraint)
- `kind` - Tool name (string)
- `description` - Step description
- `paramsJson` - Tool parameters
- `isWriteOp` - Write operation flag
- `createdBy` - LLM or USER

**Execution Flow:**

```
1. User creates PLAN session
   ↓
2. Iterative conversation with LLM
   - Add/modify/delete steps
   - Reorder steps
   - Finalize plan (DRAFT → READY)
   ↓
3. executePlan() triggers:
   - Creates new AGENT session
   - Sets sourcePlanId and planVersion
   - SNAPSHOT: Copies PlanSteps → Subtasks
   - Maps tool names to SubtaskKind enum
   - Switches to AGENT session
   ↓
4. AGENT session execution
   - Independent execution context
   - Subtasks can be modified by orchestration
   - Original plan remains unchanged
```

**Benefits:**
- **Separation of Concerns:** Planning context ≠ execution context
- **Versioning:** Track plan changes over time
- **Reusability:** Execute same plan multiple times
- **Auditability:** Original plan preserved after execution
- **Iterative Refinement:** Edit plan before execution

### 4.3 Execution Flow

```
User Input
    ↓
SessionManager.sendMessage()
    ↓
MessageDispatcher (routes by mode)
    ├── CHAT → ChatService.chat()
    └── PLAN/AGENT → PlanningService.plan()
                         ↓
                    ExecutionMonitor.startExecutionFromPlan()
                         ↓
                    UnifiedStepExecutor.execute(strategy)
                         ↓
                    Loop: findNextStep → preparePlan → executeStep → shouldContinue
                         ↓
                    ExecutionResult
```

### 4.4 Orchestration (Reflection Loop)

When `OrchestrationStrategy` is active:

```
Step Execution Result
    ↓
ReflectionEngine.reflect() [LLM analyzes result]
    ↓
ReflectionDecision:
├── CONTINUE     → Execute next step
├── MODIFY_PLAN  → PlanModifier.addSubtask/skipSubtask/modifySubtask/retrySubtask
├── ASK_USER     → Suspend via CompletableDeferred, await user response
└── ABORT        → Stop execution, mark failed
```

**Safeguards:**
- `MAX_PLAN_MODIFICATIONS = 10` per task
- `MAX_ADD_STEPS_PER_CYCLE = 3` per reflection
- `MAX_CONSECUTIVE_FAILURES = 3` before stopping

---

## 5. RAG System

### 5.1 Pipeline Overview

```
Project Files
    ↓
[1] RagIndexingService.indexProject()
    ├── Scan files (56+ extensions)
    ├── SHA-256 checksum (incremental detection)
    └── Classify: new/modified/unchanged
    ↓
[2] FileAnalyzerService.analyze()
    ├── Language detection
    ├── AST parsing (Kotlin/Java/Python/TypeScript/HTML)
    └── CodeElements extraction (classes, functions, imports)
    ↓
[3] ChunkingStrategy.createChunks()
    ├── SemanticChunkingStrategy (AST-aware)
    │   ├── Full-file chunks
    │   ├── Class-level chunks
    │   └── Function-level chunks
    └── DefaultChunkingStrategy (line-based fallback)
    ↓
[4] EmbeddingsService.generateBatch()
    ├── OpenAI: text-embedding-3-small (1536 dims)
    └── Ollama: nomic-embed-text (768 dims)
    ↓
[5] SQLite Storage
    ├── IndexFilesTable (file metadata + checksum)
    ├── IndexChunksTable (chunk content + positions)
    └── EmbeddingsTable (vector BLOB, little-endian float32)
```

### 5.2 Search Pipeline

```
Query: "authentication logic"
    ↓
[1] EmbeddingProvider.generateEmbedding(query)
    → FloatArray[768]
    ↓
[2] RagRepository.getEmbeddings(projectRoot)
    → All embeddings for project
    ↓
[3] Cosine Similarity Calculation
    cos(q, e) = (q · e) / (||q|| × ||e||)
    ↓
[4] Filter: similarity >= threshold (default 0.5)
    ↓
[5] Batch Prefetch: chunks + files (2 queries vs N+2)
    ↓
[6] Sort by similarity, take topK
    → RagSearchResult[]
```

### 5.3 Language Analyzers

| Language | Analyzer | Extraction |
|----------|----------|------------|
| Kotlin | KotlinLanguageAnalyzer | Classes, objects, functions, data classes, extensions |
| Java | JavaLanguageAnalyzer | Classes, interfaces, methods, annotations |
| Python | PythonLanguageAnalyzer | Classes, functions, decorators, type hints |
| TypeScript | TypeScriptLanguageAnalyzer | Classes, interfaces, functions, React components |
| HTML | HtmlLanguageAnalyzer | Structure, scripts, styles |

---

## 6. Context System

### 6.1 Context Providers

```
User Input: "@file:src/App.kt @codebase:authentication"
    ↓
ContextService.resolveAndConvertUserContextRefs()
    ↓
ContextProviderRegistry.getProvider("file")
    ↓
FileContextProvider.getContextItems("src/App.kt", extras)
    → ContextItem { content, uri, description }
```

**Provider Types:**
- `NORMAL` - Passive collection (no user input)
- `QUERY` - User provides input (e.g., `@codebase:search term`)
- `SUBMENU` - Interactive picker (e.g., `@file` shows file browser)

**Available Providers:**
| Provider | Type | Description |
|----------|------|-------------|
| `@file` | SUBMENU | File picker with search |
| `@folder` | SUBMENU | Folder browser |
| `@current` | NORMAL | Currently active editor file |
| `@recent` | SUBMENU | Recently edited files |
| `@docs` | SUBMENU | Indexed documentation (URLs + local files) |
| `@url` | QUERY | Fetch web content |
| `@commit` | QUERY | Git commit details |
| `@diff` | NORMAL | Uncommitted changes |
| `@terminal` | NORMAL | Terminal output |
| `@problems` | NORMAL | IDE compilation errors |
| `@clipboard` | NORMAL | Clipboard content |

### 6.2 Context Building

```kotlin
suspend fun buildProjectContext(
    projectRoot: Path,
    taskId: String,
    query: String?,
    userContextRefs: List<ContextReference>
): ProjectContextDTO {
    // 1. Load cached project analysis
    // 2. Deduplicate user context refs
    // 3. Extract conversation history (with summarization)
    // 4. Build subtask summaries
    // 5. Load RAG fragments (code + docs)
    // 6. Load MCP resources
    // 7. Resolve @ mentions
    // 8. Combine into ProjectContextDTO
}
```

---

## 7. Tools System

### 7.1 Tool Architecture

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

### 7.2 Tool Inventory

| Tool | Mode | Category | Description |
|------|------|----------|-------------|
| `read_file` | READ | DATA | Read file content |
| `read_directory` | READ | DATA | List directory contents |
| `file_search` | READ | DATA | Glob pattern search |
| `grep_search` | READ | DATA | Regex content search |
| `view_diff` | READ | DATA | Compare files |
| `create_new_file` | WRITE | FILE | Create new file |
| `code_editing` | WRITE | FILE | Search-and-replace edit |
| `multi_edit` | WRITE | FILE | Atomic multi-file edit |
| `multi_line_editor` | WRITE | FILE | LLM-assisted line editing (~$0.02) |
| `advance_code_editing` | WRITE | FILE | Full file regeneration (~$0.06) |
| `run_terminal_command` | WRITE | EXEC | Shell command (DISABLED by default) |

### 7.3 Security Layers

**PathSandbox:**
- Restricts all operations to project root
- Validates normalized and real paths
- Detects symlinks (known escape vulnerability - P0)

**FileLimits:**
- Max file size: 2 MB
- Max files in directory: 1,000
- Max search depth: 10
- 24 excluded directories (.git, node_modules, etc.)
- 30+ excluded binary extensions

**CommandDenylist:**
- Blocks destructive operations (rm -rf, format)
- Blocks network download & execute (curl | sh)
- Blocks system modification (sudo, chmod 777)
- Blocks credential access (cat ~/.ssh)

**ToolPermissionsService:**
- Per-tool allow/deny rules
- Mode-specific permissions (PLAN vs AGENT)
- Task-level overrides

---

## 8. LLM Integration

### 8.1 Unified Client

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

### 8.2 Provider Adapters

| Provider | Models | Special Features |
|----------|--------|------------------|
| **Anthropic** | Claude 3.7/3.5, Opus 4.1 | Thinking mode, top-level system |
| **OpenAI** | GPT-5.x, GPT-4o, O1/O3 | Responses API, reasoning models |
| **Gemini** | 2.5 Flash/Pro | system_instruction, thinkingConfig |
| **Ollama** | Local models | Free, JSON format mode |
| **OpenRouter** | All providers | Dynamic pricing, unified gateway |
| **LM Studio** | Local models | OpenAI-compatible, free |

### 8.3 Streaming

```kotlin
data class StreamChunk(
    val delta: String,           // Incremental content
    val finishReason: String?,   // "stop", "length", "cancelled"
    val usage: LLMUsage?         // Only on final chunk
)
```

**Streaming Formats:**
- OpenAI/OpenRouter/LMStudio: SSE with `data: [DONE]`
- Anthropic: SSE with `message_stop` event
- Ollama: NDJSON with `done: true`
- Gemini: SSE (implicit end)

### 8.4 No-Egress Mode

When enabled, blocks all cloud providers:
- **Allowed:** Ollama, LM Studio (local)
- **Blocked:** OpenAI, Anthropic, Gemini, OpenRouter

---

## 9. MCP Protocol

**Location:** `core/context/mcp/`

### 9.1 Architecture

```
MCPManager (singleton) - core/context/mcp/MCPManager.kt
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

### 9.2 Transports

**STDIO:**
- Subprocess with JSON-RPC over stdin/stdout
- Line-buffered I/O
- Environment variable substitution

**HTTP:**
- SSE for long-lived connections
- POST for request/response
- Auth: Bearer token, OAuth, custom headers

### 9.3 Capabilities

| Capability | Description |
|------------|-------------|
| `resources` | List and read resources |
| `tools` | List and call tools |
| `prompts` | System prompts (not implemented) |

### 9.4 Tool Exposure Modes

| Mode | Behavior |
|------|----------|
| `TOOLS` | MCP tools registered in ToolRegistry (Agent mode) |
| `CONTEXT` | MCP tools executed as context providers |

### 9.5 Presets

13+ built-in server templates:
- **Filesystem:** Filesystem, Google Drive
- **VCS:** GitHub, GitLab (READ_WRITE)
- **Databases:** PostgreSQL, SQLite
- **Search:** Brave Search, Exa
- **Docs:** Context7 (with workflow)
- **DevOps:** Sentry, AWS
- **Development:** Puppeteer, Sequential Thinking

---

## 10. Subagents System

### 10.1 Architecture

```
SubagentRouter (API boundary)
├── SubagentRegistry (lazy loading + cache)
│   ├── Built-in: resources/subagents/*.md
│   ├── User: ~/.refio/agents/*.md
│   └── Project: .refio/agents/*.md
├── SubagentParser (Markdown + YAML frontmatter)
├── SubagentExecutor (execution engine)
└── SubagentToolFilter (tool permissions)
```

**Hierarchy (override order):**
```
PROJECT (.refio/agents/) → USER (~/.refio/agents/) → BUILTIN (resources/)
```

### 10.2 Subagent Definition Format

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

**Frontmatter Fields:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | string | required | Subagent identifier |
| `description` | string | required | Short description |
| `tools` | string | inherit | Comma-separated tool names |
| `disallowedTools` | string | - | Tools to exclude |
| `model` | string | default | Model alias (default, plan, coding, weak, inherit) |
| `priority` | int | 0 | Auto-delegation priority (higher = preferred) |
| `enabled` | bool | true | Whether subagent is active |
| `executionMode` | string | single_shot | Execution mode (single_shot, multi_step) |
| `maxSteps` | int | 5 | Max steps for multi_step mode |

### 10.3 Invocation

**User invocation via prompt:**
```
!security-reviewer Review this authentication code for vulnerabilities
```

**Programmatic invocation:**
```kotlin
val result = subagentRouter.invoke(
    taskId = taskId,
    name = "security-reviewer",
    prompt = "Review this code...",
    contextItems = contextItems,
    stream = true,
    onChunk = { chunk -> ... }
)
```

### 10.4 Tool Filtering

SubagentToolFilter applies tool restrictions based on:
1. **TaskMode:** CHAT/PLAN = READ_ONLY tools only, AGENT = all tools
2. **Allowed tools:** Whitelist from definition
3. **Disallowed tools:** Blacklist from definition
4. **Inheritance:** "inherit" uses parent's tools

```kotlin
// In CHAT/PLAN mode
filtered = tools.filter { it.mode == ToolMode.READ_ONLY }

// Then apply subagent restrictions
if (allowedTools != null) {
    filtered = filtered.filter { it.name in allowedTools }
}
if (disallowedTools != null) {
    filtered = filtered.filter { it.name !in disallowedTools }
}
```

### 10.5 Model Resolution

Model aliases map to ConfigService operations:

| Alias | ModelOperation | Description |
|-------|----------------|-------------|
| `default` | DEFAULT | General-purpose model |
| `plan` | PLAN | Planning/reasoning model |
| `coding` | CODING | Code generation model |
| `weak` | WEAK | Fast/cheap model |
| `inherit` | - | Use parent conversation model |

### 10.6 Built-in Subagents

| Name | Model | Priority | Purpose |
|------|-------|----------|---------|
| `security-reviewer` | weak | 10 | Security audits, OWASP vulnerabilities |
| `code-reviewer` | default | 5 | Code quality, patterns, bugs |

### 10.7 UI Integration

**Autocomplete (PromptInputPanel):**
- Type `!` to trigger subagent popup
- Shows enabled subagents sorted by priority
- Inserts `!agent-name ` on selection

**Settings Panel (SubagentSettingsPanel):**
- View all subagents (including built-in)
- Create/Edit/Delete project and user subagents
- Toggle enabled state
- View system prompts (read-only for built-in)

---

## 11. Database Schema

### 11.1 Core Tables

```sql
-- Sessions (Chat/Plan/Agent)
TasksTable (id, project_id, name, mode, description, status, ui_state_json,
            source_plan_id, plan_version, created_at, updated_at)

-- Plan specifications (PLAN mode)
PlansTable (id, session_id, name, status, version, created_at, updated_at)
  -- status: DRAFT → READY → EXECUTING → EXECUTED

-- Plan steps (editable specifications)
PlanStepsTable (id, plan_id, order_index, kind, description, params_json,
                is_write_op, created_by, created_at, updated_at)
  -- UNIQUE INDEX: (plan_id, order_index)
  -- created_by: LLM | USER

-- Execution steps (snapshot from PlanSteps)
SubtasksTable (id, task_id, order_index, kind, status, approval_status, description,
               step_plan_json, result_json, error_message, metrics_json)

-- Conversation
ChatMessagesTable (id, task_id, role, content, metadata_json, metrics_json, created_at)

-- File snapshots (for rollback)
SnapshotsTable (id, task_id, subtask_id, file_path, content, checksum, created_at)

-- API call logs
ApiLogsTable (id, task_id, subtask_id, provider, model, source, request_json, response_json,
              status_code, tokens_in, tokens_out, cost_usd, latency_ms, created_at)
```

### 11.2 RAG Tables

```sql
-- Indexed files
IndexFilesTable (id, project_root, file_path, file_hash, checksum, file_size,
                 content_type, metadata_json, indexed_at, last_modified)

-- Code chunks
IndexChunksTable (id, file_id, chunk_index, content, content_hash, metadata_json,
                  start_line, end_line, start_char, end_char)

-- Vector embeddings
EmbeddingsTable (id, chunk_id, model, vector BLOB, dimensions)
```

### 11.3 Configuration Tables

```sql
-- Key-value config
ConfigTable (key, value, scope, created_at, updated_at)

-- System prompts
PromptsTable (id, type, name, content, is_active, created_at, updated_at)

-- MCP servers
MCPServersTable (id, project_id, config_json, enabled, created_at, updated_at)
```

---

## 12. Security Model

### 12.1 Security Layers

```
Layer 1: PathSandbox
├── All file operations restricted to project root
├── Path normalization and validation
└── Symlink detection (P0: known escape vulnerability)

Layer 2: FileLimits
├── Size limits (2 MB files, 100 KB command output)
├── Count limits (1000 files per directory)
└── Excluded directories and extensions

Layer 3: CommandDenylist
├── Pattern-based command blocking
├── 50+ dangerous patterns (rm -rf, sudo, curl|sh)
└── Configurable strictness levels

Layer 4: ToolPermissions
├── Per-mode permissions (PLAN: read-only, AGENT: read-write)
├── Per-task overrides
└── Smart defaults (terminal disabled)

Layer 5: No-Egress Mode
├── Blocks all cloud LLM providers
└── Allows only local (Ollama, LM Studio)

Layer 6: Secret Redaction
├── API keys redacted in logs
├── Bearer tokens masked
└── Sensitive config values hidden
```

### 12.2 Known Security Issues (P0)

| Issue | Location | Impact |
|-------|----------|--------|
| Symlink Escape | PathSandbox.kt | Can escape project root via symlink chains |
| Denylist Bypass | RunTerminalCommandTool.kt | Pattern matching may be bypassed |

---

## 13. Key Data Flows

### 13.1 Chat Message Flow

```
User: "How does authentication work?"
    ↓
SessionManager.sendMessage()
    ↓
MessageDispatcher.sendChatMessage()
    ↓
ChatService.chat()
├── Load/create task
├── Save user message
├── Build context:
│   ├── Project analysis (cached)
│   ├── Conversation history
│   ├── RAG fragments
│   ├── MCP resources
│   └── @ mention resolution
├── Build system prompt + context
├── LLMClient.complete()
└── Save assistant response
    ↓
ChatResponse to UI
```

### 13.2 Agent Execution Flow

**Option A: Direct Agent Execution**

```
User: "Add authentication to the API"
    ↓
MessageDispatcher.sendPlanningMessage()
    ↓
PlanningService.plan()
    → Creates subtasks (PENDING status)
    ↓
ExecutionMonitor.startExecutionFromPlan()
    ↓
UnifiedStepExecutor.execute(OrchestrationStrategy)
    ↓
Loop:
├── findNextStep() → PENDING subtask
├── preparePlan() → LLM generates tool calls
├── SnapshotService.createSnapshot() [before write]
├── executeStep() → ToolExecutor runs tools
├── shouldContinue() → ReflectionEngine.reflect()
│   ├── CONTINUE → next step
│   ├── MODIFY_PLAN → add/skip/modify/retry
│   ├── ASK_USER → suspend, await response
│   └── ABORT → stop
└── Repeat until complete
    ↓
ExecutionResult with stats
```

**Option B: Plan Specification Execution**

```
User: Creates PLAN session, iterates with LLM
    ↓
PlanRouter.createPlan() / updatePlanStep() / addPlanStep()
    → Stores editable PlanSteps in database
    ↓
PlanRouter.finalizePlan()
    → Plan status: DRAFT → READY
    ↓
PlanRouter.executePlan()
    ├── Creates new AGENT session (mode=AGENT)
    ├── Sets sourcePlanId and planVersion on task
    ├── SNAPSHOT: Copies all PlanSteps → Subtasks
    │   └── Maps tool names (string) to SubtaskKind (enum)
    ├── Updates plan status: READY → EXECUTING
    └── Switches to AGENT session
    ↓
UnifiedStepExecutor.execute()
    → Executes copied subtasks (original plan unchanged)
    ↓
ExecutionResult with stats
```

### 13.3 RAG Indexing Flow

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
IndexingProgress emitted via Flow
```

---

## Appendix: Quick Reference

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
refio_poc.db                  # SQLite database (project root)
```

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| WorkflowOrchestrator | core/workflow/ | Intent-based execution routing |
| IntentRouter | core/workflow/ | Intent resolution (fast paths + LLM) |
| CoreApiRouter | core/api/ | API boundary |
| PlanRouter | core/api/routers/ | Plan specification management |
| SessionManager | services/session/ | Session orchestration |
| UnifiedStepExecutor | core/services/execution/unified/ | Execution loop |
| LLMClient | core/llm/ | LLM provider abstraction |
| ContextService | core/services/ | Context building |
| RagSearchService | core/services/ | Semantic search |
| PlanRepository | core/db/repositories/ | Plan CRUD operations |
| ToolRegistry | core/tools/base/ | Tool catalog |
| MCPManager | core/context/mcp/ | MCP server lifecycle |
| SwingWorkflowListener | ui/listeners/ | Swing adapter for workflow events |

### Environment Variables

```
OPENAI_API_KEY       # OpenAI provider
ANTHROPIC_API_KEY    # Anthropic provider
GEMINI_API_KEY       # Google Gemini
OPENROUTER_API_KEY   # OpenRouter
OLLAMA_BASE_URL      # Custom Ollama endpoint
LMSTUDIO_BASE_URL    # Custom LM Studio endpoint
```
