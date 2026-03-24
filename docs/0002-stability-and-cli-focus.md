# 0002 — Analiza stabilnosci core + sciezka CLI z multi-agent

> **Data:** 2026-03-23
> **Status:** Analiza ukierunkowana
> **Kontekst:** Dwa rownoczesne priorytety: (1) stabilny core w IntelliJ, (2) CLI z GUI i multi-agent do kursu

---

## Spis tresci

1. [Streszczenie — co blokuje, co jest gotowe](#1-streszczenie)
2. [Audyt stabilnosci core w IntelliJ](#2-audyt-stabilnosci-core-w-intellij)
3. [Mapa pokrycia testami](#3-mapa-pokrycia-testami)
4. [Minimalna sciezka ekstrakcji core](#4-minimalna-sciezka-ekstrakcji-core)
5. [CLI z GUI — konkretny plan](#5-cli-z-gui--konkretny-plan)
6. [Multi-agent MVP do kursu](#6-multi-agent-mvp-do-kursu)
7. [Harmonogram — co kiedy](#7-harmonogram--co-kiedy)
8. [Ryzyka krytyczne](#8-ryzyka-krytyczne)

---

## 1. Streszczenie

### Stan faktyczny

| Metryka | Wartosc | Ocena |
|---------|---------|-------|
| Commitow ogolem | 31 | — |
| Commitow "fix" | 19 (61%) | **Czerwona flaga** — wysoki churn |
| Plikow testowych | 65 | Dobra baza |
| Pokrycie krytycznych serwisow | ~60% | Luki w ChatService, PlanningService, LLMClient |
| JaCoCo threshold | 40% | Podniesiony z 20%, ale nadal niski |
| Uzycia `!!` (non-null assert) w core | 37 | **Ryzyko NPE** — do przeglądu |
| TODO/FIXME w core | 2 | Bardzo czysto |
| Plikow core z IntelliJ import | 17/240 (7%) | Dobrze — mala powierzchnia |
| Juz istniejace abstrakcje | `isIdeEnvironment`, `UIAdapter`, nullable `Project` | Duzo pracy juz zrobionej |
| Agent Turn Loop fixy | 3 dedykowane commity | **Obszar ryzyka #1** |

### Wnioski kluczowe

1. **Core jest bliski ekstrakcji** — 93% plikow nie importuje IntelliJ, istniejace abstrakcje (`isIdeEnvironment`, nullable `Project`) znaczaco zmniejszaja prace
2. **Agent Turn Loop to najslabsze ogniwo** — 3 dedykowane fix commity, 971 linii, test istnieje ale kompleksowosc jest wysoka
3. **GlobalMetrics to singleton** — blokuje multi-agent (jeden `currentOperation`, jeden `isCancelled`)
4. **OllamaRequestGate: Semaphore(1)** per endpoint — blokuje rownolegle requesty do Ollama (ale nie do cloud providerow)
5. **Modularyzacja Gradle to warunek konieczny** ale mozna ja zrobic bezpiecznie dzieki dobrej separacji

### Rekomendowana kolejnosc

```
Tydzien 1-2: Stabilizacja core + testy krytycznych luk
Tydzien 3-4: Modularyzacja Gradle (core / intellij-plugin / cli)
Tydzien 5-7: CLI z Compose Desktop GUI
Tydzien 8-10: Multi-agent MVP
```

---

## 2. Audyt stabilnosci core w IntelliJ

### 2.1 Analiza commitow — wzorce bledow

```
e878e97  fix: Memory leak warn, GH test
f039e97  fix: chat UI race condition, nested JSON unwrap, subagent indicator
e0d6b28  fix: Z.ai config, UI layout and freeze
4c7d796  chore: Stabilize startup, streaming UI, LLM error handling
6fc3105  fix: Agent flow, UI stability, context refresh, security
d4a7cb1  fix: Agent turn loop message
d2e5edf  fix: Agent turn loop, slash commands
e915d2c  fix: Agent turn loop, add: New command
```

**Wzorce bledow:**

| Kategoria | Liczba fixow | Komponenty | Priorytet stabilizacji |
|-----------|-------------|------------|----------------------|
| Agent Turn Loop | 3 | `AgentTurnLoop.kt` (971 LOC), message handling, slash commands | **P0 — krytyczny** |
| UI race conditions / freeze | 3 | ChatView, streaming display, layout | P1 (nie blokuje CLI) |
| LLM error handling | 2 | Startup, streaming, error mapping | P0 |
| JSON parsing | 1 | Nested JSON unwrap | P1 |
| Config/Provider | 2 | Z.AI config, model defaults | P2 |

### 2.2 Niebezpieczne `!!` (non-null assertions)

W core znaleziono 37 użyć operatora `!!` — każde to potencjalny `NullPointerException` w runtime. Kluczowy przypadek:

- `CoreApiRouter.kt:2048` — `projectRoot!!` w `analyzeProject()` — crash jeśli router utworzony bez projectRoot
- Rekomendacja: **Audit i zamiana na safe calls** (`?.let {}`, `?: return`, `requireNotNull()` z komunikatem)

### 2.3 Obszary najwyzszego ryzyka

#### Risk #1: AgentTurnLoop (971 LOC, 3 fixy)

Najwiekszy plik wykonawczy, zlozone stany, wiele sciezek bledow. Jest testem (`AgentTurnLoopTest.kt`) ale z uwagi na 3 dedykowane fix commity — **pokrycie jest niewystarczajace**.

**Rekomendacja:** Dodac testy dla:
- Edge case: LLM zwraca pusty response
- Edge case: Tool call z malformed JSON
- Edge case: Cancellation w srodku tool execution
- Edge case: Max iterations reached
- Edge case: Subagent invocation z nested depth > 3
- Scenariusz: Slash command routing
- Scenariusz: Working memory integration failure

#### Risk #2: Streaming + UI race condition

Commit `f039e97` naprawil race condition w chat UI. Streaming jest callback-based, update-y na `MutableStateFlow` z Mutex. Problem: debounce 200ms moze gubic chunki przy szybkim streamingu.

**Rekomendacja:** To jest problem IntelliJ UI, nie core. CLI z Compose Desktop bedzie mial wlasny rendering pipeline — **nie blokuje ekstrakcji**.

#### Risk #3: LLM Error Handling

Commit `4c7d796` stabilizowal startup i LLM errors. `LLMErrorMapper` mapuje HTTP errors na typed exceptions, ale:
- Brak testu dla timeout recovery
- Brak testu dla partial streaming failure (connection drop mid-stream)
- `LLMRetryHandler` nie ma testu

**Rekomendacja:** Dodac testy:
- `LLMRetryHandlerTest` — exponential backoff, max retries, non-retryable errors
- `LLMClient` integration test z mock HTTP — timeout, 429, 502, connection drop

### 2.3 Co jest STABILNE

| Komponent | Testy | LOC | Ocena |
|-----------|-------|-----|-------|
| Database layer (SQLite/Exposed) | 7 repo testow | ~2000 | **Solidny** — WAL mode, retry, migracje |
| Tools (12 implementacji) | 10 testow | ~3500 | **Solidny** — sandbox, limits, security |
| Security (PathSandbox, CommandWhitelist) | 2 testy | ~800 | **Solidny** |
| Config (ConfigService, YAML loader) | 2 testy | ~2500 | **Stabilny** — 4-tier hierarchy dziala |
| Context budget/estimation | 3 testy | ~400 | **Stabilny** |
| Workflow orchestration | 3 testy (incl. integration) | ~600 | **Stabilny** — intent routing przetestowany |
| Subagent system | 3 testy (router, parser, invoke tool) | ~500 | **Stabilny** |
| Tool call parsing | 1 test | ~300 | **Stabilny** — krytyczny komponent dobrze pokryty |

---

## 3. Mapa pokrycia testami

### 3.1 Co MA testy (65 plikow)

```
core/api/routers/          AgentRouter, ChatRouter, ConfigRouter, RagRouter, ToolRouter
core/db/repositories/      ChatMessage, Config(2), Rag, Snapshot, Subtask, Task
core/errors/               LLMErrorMapper, RefioError
core/llm/adapters/         LLMAdaptersTest (generic)
core/security/             SecureLogger
core/services/             AgentExecutor, AgentTurnLoop, ChunkingMode, ConfigService,
                           ContextService, ProjectAnalyzerIntegration, RagSearch,
                           StepPlanner, ToolPermissions, ToolResultSummarizer, TurnLoopConfig
core/services/analysis/    LanguageAnalyzer, CppLanguageAnalyzer, FrameworkAnalyzer
core/services/context/     ContextBudget, ContextTokenEstimator, WorkingMemory(2),
                           ContextLayer, ProjectInstructionsLoader
core/services/turn/        ToolCallParser, TurnLLMCaller
core/services/rag/         BM25Scorer
core/subagents/            SubagentRouter, SubagentParserContextProfile
core/tools/                PathSandbox
core/tools/implementations/ CodeEditing, CreateNewFile, FileSearch, GrepSearch,
                           MultiEdit, ReadDirectory, ReadFile, RunTerminalCommand,
                           ViewDiff, InvokeSubagent, AdvanceCodeEditing, MultiLineEditor
core/tools/security/       CommandWhitelist
core/workflow/             IntentRouter, WorkflowOrchestrator, WorkflowIntegration
services/session/          SessionLifecycle, SessionStateManager,
                           IncrementalToolCallStreamFilter, ToolMessageDisplayResolver
```

### 3.2 Co NIE MA testow — luki krytyczne dla stabilnosci

| Komponent | Ryzyko | Priorytet testu | Uzasadnienie |
|-----------|--------|-----------------|--------------|
| **LLMClient.kt** | Wysokie | **P0** | Centralny punkt dla WSZYSTKICH zapytan LLM. Brak testu = brak ochrony przed regresja. |
| **ChatService.kt** | Wysokie | **P0** | Glowny flow chatu. History management, context optimization. |
| **PlanningService.kt** | Srednie | P1 | Generowanie planow, parsowanie JSON subtasks. |
| **LLMRetryHandler.kt** | Wysokie | **P0** | Exponential backoff — krytyczny dla niezawodnosci. Kazdy fix tu moze zepsuc caly retry. |
| **ConversationCompactor.kt** | Srednie | P1 | Auto-compaction — jezeli sie zepsuje, kontekst eksploduje. |
| **PromptsService.kt** | Niskie | P2 | 1700+ LOC ale stabilny. Glownie template rendering. |
| **SnapshotService.kt** | Srednie | P1 | Rollback agent changes — krytyczny dla AGENT mode. |
| **ContextProviderRegistry.kt** | Srednie | P1 | Inicjalizacja providerow, isIdeEnvironment logic. |
| **WorkingMemoryIntegration.kt** | Niskie | P2 | Wspomagajacy serwis, nie krytyczny. |
| **EmbeddingCircuitBreaker.kt** | Niskie | P2 | Circuit breaker — prosty state machine. |
| Poszczegolne LLM adaptery | Srednie | P1 | Kazdy adapter (Anthropic, OpenAI, Gemini) powinien miec unit test z mock HTTP |
| **GlobalMetrics.kt** | Niskie | P2 | Singleton, ale atomic operations — maly risk. |
| **TurnGuardrails.kt** | Wysokie | **P0** | Loop detection + error tracking — bez testu ryzyko nieskonczonej petli |
| **TurnToolExecutor.kt** | Wysokie | P1 | Wykonanie narzedzi w turn loop — brak testu dla failure modes |
| **TurnPromptBuilder.kt** | Srednie | P1 | Budowanie promptow z kontekstem — blad = zle dane do LLM |
| **TurnFinalizer.kt** | Srednie | P2 | Finalizacja turnu — brak testu dla niekompletnego stanu |
| **TurnResponseProcessor.kt** | Srednie | P1 | Parsing odpowiedzi LLM — brak testu = utrata danych z thinking |
| **ParallelToolExecutor.kt** | Wysokie | P1 | Rownolegle narzedzia — brak testu race conditions |

### 3.3 Priorytetowa lista testow do napisania

**Sprint 1 (blokuje ekstrakcje core):**
1. `LLMClientTest.kt` — adapter selection, no-egress enforcement, streaming callback, error mapping
2. `ChatServiceTest.kt` — chat flow, history management, summary integration
3. `LLMRetryHandlerTest.kt` — backoff timing, max retries, non-retryable error types
4. `ContextProviderRegistryTest.kt` — inicjalizacja z `isIdeEnvironment=false`, SPI pattern

**Sprint 2 (wzmacnia stabilnosc):**
5. `PlanningServiceTest.kt` — plan generation, JSON parsing, cost calculation
6. `SnapshotServiceTest.kt` — create/restore, compression, hash verification
7. `ConversationCompactorTest.kt` — auto-compact trigger, summary quality
8. Adapter-specific testy: `AnthropicAdapterTest.kt`, `OpenAIAdapterTest.kt` z mock HTTP

---

## 4. Minimalna sciezka ekstrakcji core

### 4.1 Zasada: nie zmieniaj tego co dziala w IntelliJ

Ekstrakcja MUSI byc bezpieczna — kazda zmiana w core moze zepsuc dzialajacy plugin. Strategia:

```
1. Dodajemy abstrakcje (interfejsy) — NIE zmieniamy istniejacego kodu
2. Nowe moduly Gradle OBOK istniejacego — NIE przenosimy plikow
3. CI sprawdza OBA moduly — kazdy PR musi przechodzic testy IntelliJ i core
```

### 4.2 Krok po kroku

#### Krok 1: Multi-module Gradle (bez przenoszenia plikow)

```kotlin
// settings.gradle.kts
include(":core")
include(":intellij-plugin")
include(":cli")

// core/build.gradle.kts — symlink lub sourceSet pointing do src/main/kotlin/pl/jclab/refio/core/
// intellij-plugin/build.gradle.kts — zalezy od :core, buduje plugin
// cli/build.gradle.kts — zalezy od :core, buduje standalone
```

**UWAGA:** Na poczatku mozna uzyc Gradle source sets zamiast fizycznego przenoszenia plikow:

```kotlin
// core/build.gradle.kts
sourceSets {
    main {
        kotlin {
            srcDir("../src/main/kotlin")
            include("pl/jclab/refio/core/**")
        }
    }
}
```

To pozwala budowac `:core` osobno BEZ przenoszenia plikow — IntelliJ plugin nadal dziala jak dotychczas.

#### Krok 2: ProjectHandle interface

```kotlin
// core/.../project/ProjectHandle.kt (NOWY plik)
interface ProjectHandle {
    val id: String
    val name: String
    val rootPath: java.nio.file.Path
}
```

Potem STOPNIOWO zamieniamy `com.intellij.openapi.project.Project?` na `ProjectHandle?` w 6 plikach core. Kazda zamiana to osobny, maly PR z testem.

#### Krok 3: Standalone inicjalizacja

```kotlin
// cli/.../StandaloneCoreBootstrap.kt (NOWY plik)
class StandaloneCoreBootstrap(
    private val projectPath: Path,
    private val dbPath: Path = projectPath.resolve(".refio/database.sqlite")
) {
    fun createRouter(): CoreApiRouter {
        val projectHandle = StandaloneProjectHandle(projectPath)
        DatabaseFactory.init(dbPath.toString())

        val toolRegistry = ToolRegistry()
        ToolFactory(/* ... */).registerAll(toolRegistry)

        return CoreApiRouter(
            toolRegistry = toolRegistry,
            projectRoot = projectPath,
            ideProject = null  // <-- juz dzisiaj wspierane!
        ).also { it.initialize(dbPath.toString()) }
    }
}
```

### 4.3 Pliki do zmiany (minimalne, bezpieczne)

| Plik | Zmiana | Ryzyko regresji |
|------|--------|----------------|
| `settings.gradle.kts` | Dodaj include modules | **Zerowe** — nowe moduly |
| Nowy: `core/build.gradle.kts` | Source set na istniejace pliki | **Zerowe** — nie rusza buildu |
| Nowy: `cli/build.gradle.kts` | Compose Desktop + Clikt | **Zerowe** |
| `CoreApiRouter.kt:73` | `Project?` → `ProjectHandle?` | **Niskie** — juz nullable |
| 5 serwisow core | `Project?` → `ProjectHandle?` | **Niskie** — juz nullable |
| `ContextProviderRegistry.kt` | Usun PluginManagerCore, uzyj SPI | **Srednie** — wymaga testu |
| Nowy: `ProjectHandle.kt` | Interface + 2 implementacje | **Zerowe** |
| Nowy: `StandaloneCoreBootstrap.kt` | Bootstrap CLI | **Zerowe** |

**Dodatkowe znalezisko:** `BaseContextProvider.LoadSubmenuItemsArgs` ma **non-nullable** `project: Project` — wymaga zmiany na `projectProxy: ProjectProxy?` lub `Any?`. To jedyny non-nullable IntelliJ typ w core interfaces.

**Lacznie: 8 zmienionych plikow + 5 nowych.** Reszta core BEZ ZMIAN.

---

## 5. CLI z GUI — konkretny plan

### 5.1 Architektura CLI

```
refio-cli.jar (lub natywny pakiet)
│
├── main.kt                          # Clikt entry point
│   ├── --project /path
│   ├── --mode chat|plan|agent
│   ├── --model provider/model
│   ├── --headless (bez GUI)
│   └── --benchmark suite.yaml
│
├── StandaloneCoreBootstrap.kt       # Inicjalizacja core bez IntelliJ
│
├── Compose Desktop App              # GUI
│   ├── App.kt                       # Root composable
│   ├── ChatPanel.kt                 # 2/3 ekranu — chat
│   ├── StatusPanel.kt               # 1/3 ekranu — status + taski
│   ├── AgentDashboard.kt            # Panel multi-agent (Faza 2)
│   └── theme/Theme.kt               # Material 3 theme
│
├── ComposeWorkflowListener.kt       # Odpowiednik SwingWorkflowListener
│   └── (odbiera eventy z core, aktualizuje Compose state)
│
└── StandaloneContextProviders.kt    # git diff via ProcessBuilder, grep via ripgrep
```

### 5.2 Compose Desktop — key composables

```kotlin
@Composable
fun RefioApp(coreRouter: CoreApiRouter) {
    val viewModel = remember { RefioViewModel(coreRouter) }

    MaterialTheme(colorScheme = refioColorScheme) {
        Row(Modifier.fillMaxSize()) {
            // 2/3 — Chat
            ChatPanel(
                messages = viewModel.messages.collectAsState(),
                onSend = viewModel::sendMessage,
                isStreaming = viewModel.isStreaming.collectAsState(),
                modifier = Modifier.weight(2f)
            )

            // 1/3 — Status
            StatusPanel(
                agents = viewModel.agents.collectAsState(),
                tasks = viewModel.tasks.collectAsState(),
                metrics = viewModel.metrics.collectAsState(),
                approvals = viewModel.pendingApprovals.collectAsState(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
```

### 5.3 ViewModel — bridge miedzy core a Compose

```kotlin
class RefioViewModel(private val coreRouter: CoreApiRouter) {
    // State (Compose obserwuje)
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _agents = MutableStateFlow<List<AgentStatus>>(emptyList())
    val agents: StateFlow<List<AgentStatus>> = _agents.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

    // Workflow listener — bridge z core events na Compose state
    private val workflowListener = object : WorkflowEventListener {
        override fun onStreamChunk(chunk: String) {
            _isStreaming.value = true
            _messages.update { msgs ->
                // update last assistant message
            }
        }
        override fun onStreamComplete(content: String) {
            _isStreaming.value = false
        }
    }

    fun sendMessage(input: String, mode: TaskMode, model: String?) {
        viewModelScope.launch {
            val request = WorkflowRequest(/* ... */)
            coreRouter.orchestrate(request, workflowListener)
            refreshMessages()
        }
    }
}
```

### 5.4 Context providers dla standalone

| Provider IntelliJ | Zamiennik CLI | Implementacja |
|-------------------|--------------|---------------|
| `CurrentFileContextProvider` | Brak (nie ma edytora) | Pominiety |
| `OpenFilesContextProvider` | Brak | Pominiety |
| `RecentFilesContextProvider` | Brak | Pominiety |
| `TerminalContextProvider` | `StandaloneTerminalProvider` | Czyta output z `run_terminal_command` tool |
| `ProblemsContextProvider` | Brak (nie ma kompilatora IDE) | Pominiety |
| `GitDiffContextProvider` | `StandaloneGitDiffProvider` | `git diff` via ProcessBuilder |
| `GrepSearchContextProvider` | `StandaloneGrepProvider` | ripgrep lub `grep_search` tool |
| `FileContextProvider` | `StandaloneFileProvider` | java.nio.file walk |
| `FolderContextProvider` | Dziala as-is | java.nio.file |
| `ClipboardContextProvider` | Compose clipboard API | Trywialne |
| `CodebaseContextProvider` | Dziala as-is | RAG search (no IDE deps) |
| `UrlContextProvider` | Dziala as-is | Ktor HTTP client |
| `DocsContextProvider` | Dziala as-is | Filesystem |
| `GitCommitContextProvider` | Dziala as-is | git log via ProcessBuilder |

**7 providerow dziala as-is, 3 wymagaja prostych zamiennikow, 4 pomiete (IDE-specific).**

---

## 6. Multi-agent MVP do kursu

### 6.1 Co jest potrzebne do kursu?

Zakladam ze kurs wymaga:
- Uruchomienie zadania z plikiem YAML/MD z opisem
- Wielu agentow pracujacych rownolegle nad czesciami zadania
- Widocznosc postepow kazdego agenta w GUI
- Zatwierdzanie krytycznych operacji (file write, terminal command)
- Raport/benchmark z wynikami

### 6.2 Minimum Viable Multi-Agent

**NIE budujemy pelnego systemu z 0001.** Budujemy **minimalny overlay** na istniejacy `AgentTurnLoop`:

```
Orchestrator (nowy, prosty)
  │
  ├── Agent Session 1 (istniejacy AgentTurnLoop + dedykowany Task w DB)
  ├── Agent Session 2 (istniejacy AgentTurnLoop + dedykowany Task w DB)
  └── Agent Session 3 (istniejacy AgentTurnLoop + dedykowany Task w DB)
      │
      └── Kazdy agent ma wlasne: task, messages, subtasks, metrics
          Wspoldzielone: ToolRegistry, LLMClient, DB, FileSystem
```

**Kluczowy insight:** Kazdy `AgentTurnLoop` juz dzisiaj jest self-contained — przyjmuje `taskId` i operuje na swoim zbiorze wiadomosci/subtaskow. Wystarczy:

1. Stworzyc N taskow w DB (po jednym per agent)
2. Uruchomic N instancji AgentTurnLoop w osobnych coroutine scope
3. Dodac event bus do komunikacji miedzy agentami
4. Dodac file locking do zapobiegania konfliktom

### 6.3 Wymagane zmiany w istniejacym kodzie

#### Zmiana 1: GlobalMetrics — z singletona na per-agent + agregat

```kotlin
// PRZED (singleton):
object GlobalMetrics {
    private val _currentOperation = MutableStateFlow<OperationInfo>(OperationInfo.Idle)
    private val _isCancelled = AtomicBoolean(false)
}

// PO (per-agent + global):
class AgentMetrics(val agentId: String) {
    private val _currentOperation = MutableStateFlow<OperationInfo>(OperationInfo.Idle)
    val currentOperation: StateFlow<OperationInfo> = _currentOperation.asStateFlow()
    private val _isCancelled = AtomicBoolean(false)
    // ... reszta jak w GlobalMetrics ale per-instancja
}

object GlobalMetrics {
    // Deleguje do aktywnych AgentMetrics
    private val agentMetrics = ConcurrentHashMap<String, AgentMetrics>()

    fun forAgent(agentId: String): AgentMetrics =
        agentMetrics.getOrPut(agentId) { AgentMetrics(agentId) }

    // Backward compat — deleguje do "default" agenta
    fun setCurrentOperation(op: OperationInfo) = forAgent("default").setCurrentOperation(op)
    fun isCancelled() = forAgent("default").isCancelled()
    fun requestCancellation() = forAgent("default").requestCancellation()
}
```

**Wsteczna kompatybilnosc:** Istniejacy kod ktory wola `GlobalMetrics.setCurrentOperation()` dziala bez zmian (deleguje do "default"). Nowy multi-agent kod uzywa `GlobalMetrics.forAgent(agentId)`.

#### Zmiana 2: OllamaRequestGate — konfigurowalny concurrency

```kotlin
object OllamaRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    var maxConcurrentPerEndpoint: Int = 1  // NOWE: konfigurowalne

    suspend fun <T> withPermit(endpoint: String, block: suspend () -> T): T {
        val normalizedEndpoint = endpoint.trim().removeSuffix("/")
        val semaphore = semaphores.computeIfAbsent(normalizedEndpoint) {
            Semaphore(maxConcurrentPerEndpoint)
        }
        return semaphore.withPermit { block() }
    }
}
```

**Dla cloud providerow (Anthropic, OpenAI):** Nie uzywa OllamaRequestGate — **zero zmian potrzebnych**.

#### Zmiana 3: File locking dla concurrent write

```kotlin
// NOWY plik: core/tools/FileLockManager.kt
object FileLockManager {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val mutex = locks.getOrPut(path.normalize()) { Mutex() }
        return mutex.withLock { block() }
    }
}
```

Uzycie w `CodeEditingTool`, `CreateNewFileTool`, `AdvanceCodeEditingTool`, `MultiEditTool`, `MultiLineEditorTool`.

#### Zmiana 4: Event Bus (prosty, SharedFlow-based)

```kotlin
// NOWY plik: core/agents/AgentEventBus.kt
data class AgentEvent(
    val sourceAgentId: String,
    val type: AgentEventType,
    val payload: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class AgentEventType {
    STARTED, COMPLETED, FAILED, ARTIFACT_PRODUCED,
    APPROVAL_REQUIRED, APPROVAL_GRANTED, APPROVAL_DENIED,
    MESSAGE  // komunikat tekstowy miedzy agentami
}

class AgentEventBus {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 50,
        extraBufferCapacity = 100
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun emit(event: AgentEvent) = _events.emit(event)

    fun subscribe(filter: (AgentEvent) -> Boolean): Flow<AgentEvent> =
        events.filter(filter)
}
```

#### Zmiana 5: MultiAgentRunner (nowy orkiestrator)

```kotlin
// NOWY plik: core/agents/MultiAgentRunner.kt
class MultiAgentRunner(
    private val coreRouter: CoreApiRouter,
    private val eventBus: AgentEventBus,
    private val scope: CoroutineScope
) {
    data class AgentSpec(
        val name: String,
        val subagentProfile: String?,  // np. "code-reviewer"
        val task: String,              // prompt/zadanie
        val mode: TaskMode = TaskMode.AGENT,
        val model: String? = null,
        val dependsOn: List<String> = emptyList()  // nazwy agentow
    )

    suspend fun run(specs: List<AgentSpec>): Map<String, AgentResult> {
        val results = ConcurrentHashMap<String, AgentResult>()
        val completedAgents = MutableStateFlow(setOf<String>())

        supervisorScope {
            for (spec in specs) {
                launch {
                    // Czekaj na zależności
                    if (spec.dependsOn.isNotEmpty()) {
                        completedAgents.first { completed ->
                            spec.dependsOn.all { it in completed }
                        }
                    }

                    // Stworz dedykowany task w DB
                    val task = coreRouter.createTask(CreateTaskRequest(
                        name = "agent:${spec.name}",
                        mode = spec.mode
                    ))

                    eventBus.emit(AgentEvent(spec.name, AgentEventType.STARTED))

                    try {
                        // Uruchom AgentTurnLoop na tym tasku
                        val result = coreRouter.orchestrate(
                            WorkflowRequest(UIState(
                                taskId = task.id,
                                mode = spec.mode,
                                input = spec.task,
                                model = spec.model
                            )),
                            listener = AgentWorkflowListener(spec.name, eventBus)
                        )

                        results[spec.name] = AgentResult.Success(result)
                        eventBus.emit(AgentEvent(spec.name, AgentEventType.COMPLETED))
                    } catch (e: Exception) {
                        results[spec.name] = AgentResult.Failure(e)
                        eventBus.emit(AgentEvent(spec.name, AgentEventType.FAILED,
                            mapOf("error" to e.message.orEmpty())))
                    }

                    completedAgents.update { it + spec.name }
                }
            }
        }

        return results
    }
}
```

### 6.4 GUI — panel multi-agent

W StatusPanel (1/3 ekranu) dodajemy sekcje:

```
┌────────────────────┐
│ Agenci             │
│                    │
│ ● Analyst          │  RUNNING — "Analizuje strukture..."
│   Model: Claude    │  Tokens: 1.2K / Cost: $0.01
│                    │
│ ● Coder            │  WAITING — czeka na Analyst
│   Model: GPT-5.1   │
│                    │
│ ○ Reviewer         │  IDLE — uruchomi sie po Coder
│   Model: Claude    │
│                    │
│ ▶ Zatwierdzenia    │
│   Coder chce       │
│   zapisac plik:    │
│   src/User.kt      │
│   [Zatwierdz] [Odrzuc] [Zatw. wszystkie]
└────────────────────┘
```

### 6.5 Definicja multi-agent zadania (YAML)

```yaml
# task-definition.yaml
name: "Implement REST API"
description: "Zaimplementuj CRUD API dla encji User"
project: "."

agents:
  - name: analyst
    profile: business-analyst
    task: |
      Przeanalizuj wymagania i stworz specyfikacje techniczna
      dla REST API encji User (CRUD, walidacja, error handling).
    model: anthropic/claude-sonnet-4-6

  - name: architect
    profile: architect-reviewer
    task: |
      Na podstawie specyfikacji zaproponuj architekture:
      pakiety, klasy, interfejsy, endpointy.
    depends_on: [analyst]
    model: anthropic/claude-sonnet-4-6

  - name: coder
    profile: fullstack-developer
    task: |
      Zaimplementuj API zgodnie ze specyfikacja i architektura.
    depends_on: [architect]
    model: anthropic/claude-sonnet-4-6
    mode: agent  # pelen dostep do narzedzi

  - name: reviewer
    profile: code-reviewer
    task: |
      Przejrzyj implementacje pod katem jakosci, bezpieczenstwa
      i zgodnosci ze specyfikacja.
    depends_on: [coder]
    model: anthropic/claude-sonnet-4-6

validation:
  - command: "./gradlew build"
    description: "Kompilacja"
  - command: "./gradlew test"
    description: "Testy"

scoring:
  - metric: compilation
    weight: 0.3
  - metric: tests_passed
    weight: 0.4
  - metric: review_score
    weight: 0.2
  - metric: cost
    weight: 0.1
    lower_is_better: true
```

### 6.6 Podsumowanie zmian dla multi-agent MVP

| Co | Typ | LOC szacunkowo | Ryzyko regresji IntelliJ |
|----|-----|---------------|--------------------------|
| `GlobalMetrics` refactor | Zmiana | +40 LOC | **Niskie** — backward compat |
| `OllamaRequestGate` concurrency | Zmiana | +3 LOC | **Zerowe** — default = 1 |
| `FileLockManager` | Nowy | ~30 LOC | **Zerowe** |
| `AgentEventBus` | Nowy | ~40 LOC | **Zerowe** |
| `MultiAgentRunner` | Nowy | ~100 LOC | **Zerowe** |
| 5 write tools — add file locking | Zmiana | +10 LOC kazdy | **Niskie** — mutex wrapping |
| Compose `AgentDashboard` | Nowy (CLI only) | ~200 LOC | **Zerowe** |
| `UserApprovalQueue` | Nowy | ~60 LOC | **Zerowe** |
| YAML task parser | Nowy | ~80 LOC | **Zerowe** |

**Lacznie: ~460-610 LOC nowego kodu, ~60-100 LOC zmian w istniejacym.**

> **Wazne:** Analiza rownoleglosci potwierdza ze ToolRegistry, ParallelToolExecutor, PathSandbox, MCPManager, SubagentRegistry, LLMClient, ConfigService — **wszystkie sa bezpieczne dla concurrent access** (stateless, ConcurrentHashMap, albo per-call). UserInteraction jest UUID-keyed — dziala as-is. Jedyne punkty konfliktu to GlobalMetrics (singleton state), ChatMessageRepository (wspolddzielona historia) i filesystem (concurrent writes).

---

## 7. Harmonogram — co kiedy

### Oś 1: Stabilizacja core (rownolegle z CLI)

```
Tydzien 1:
  ☐ LLMClientTest.kt (P0)
  ☐ ChatServiceTest.kt (P0)
  ☐ LLMRetryHandlerTest.kt (P0)
  ☐ ContextProviderRegistryTest.kt (P0)

Tydzien 2:
  ☐ PlanningServiceTest.kt (P1)
  ☐ SnapshotServiceTest.kt (P1)
  ☐ ConversationCompactorTest.kt (P1)
  ☐ AgentTurnLoop — dodatkowe edge case testy (P0)

Ongoing:
  ☐ Adapter-specific testy (Anthropic, OpenAI, Gemini)
  ☐ JaCoCo threshold podniesc z 20% do 40%
```

### Oś 2: CLI z multi-agent (sekwencyjnie)

```
Tydzien 1-2: Modularyzacja Gradle
  ☐ settings.gradle.kts z 3 modulami
  ☐ core/build.gradle.kts (source set na istniejace pliki)
  ☐ cli/build.gradle.kts (Compose Desktop + Clikt)
  ☐ ProjectHandle interface + implementacje
  ☐ ContextProviderSPI
  ☐ CI: ./gradlew :core:test MUSI przechodzic
  ☐ StandaloneCoreBootstrap.kt

Tydzien 3-4: CLI GUI MVP
  ☐ main.kt z Clikt
  ☐ Compose App.kt — 2-kolumnowy layout
  ☐ ChatPanel.kt — lista wiadomosci + input
  ☐ StatusPanel.kt — taski, metryki
  ☐ ComposeWorkflowListener.kt
  ☐ Standalone context providers (GitDiff, Grep, File)
  ☐ Tryb Chat dzialajacy end-to-end
  ☐ Tryb headless z JSON output

Tydzien 5-6: Pelna funkcjonalnosc CLI
  ☐ Tryby Plan i Agent
  ☐ Streaming display w Compose
  ☐ Code block rendering
  ☐ @mention autocomplete
  ☐ Subagent invocation (!name)
  ☐ Packaging (Shadow JAR + launch script)

Tydzien 7-8: Multi-agent MVP
  ☐ GlobalMetrics refactor (per-agent + compat)
  ☐ FileLockManager
  ☐ AgentEventBus
  ☐ MultiAgentRunner
  ☐ YAML task parser
  ☐ AgentDashboard w Compose
  ☐ UserApprovalQueue + GUI
  ☐ OllamaRequestGate concurrency

Tydzien 9-10: Benchmark + kurs
  ☐ BenchmarkRunner
  ☐ Validation framework (kompilacja, testy)
  ☐ Scoring engine
  ☐ Raport generator (JSON, CSV, Markdown)
  ☐ Przykladowe task definitions do kursu
  ☐ Dokumentacja uzytkowa
```

**Lacznie: ~10 tygodni.** Obie osie moga byc prowadzone rownolegle (stabilizacja testow + build CLI).

---

## 8. Ryzyka krytyczne

| Ryzyko | Prawdop. | Wplyw | Mitigacja |
|--------|----------|-------|-----------|
| Modularyzacja zlamie IntelliJ build | Srednie | **Krytyczny** | Source set approach (nie przenosimy plikow), CI per-module |
| AgentTurnLoop regresja przy multi-agent | Wysokie | Wysoki | Per-agent task isolation (kazdy agent = osobny taskId), nie zmieniamy AgentTurnLoop |
| File conflicts miedzy agentami | Wysokie | Sredni | FileLockManager + Mutex per-path, user approval dla write |
| OOM przy 5+ agentach | Srednie | Wysoki | Resource limit w MultiAgentRunner (max 5), monitoring pamieci |
| Compose Desktop bugs na Linuxie | Niskie | Sredni | Fallback: headless mode (zawsze dziala) |
| Cloud provider rate limits przy multi-agent | Wysokie | Sredni | Sequential fallback, exponential backoff, budget per agent |
| SQLite lock contention | Srednie | Wysoki | WAL mode juz wlaczony, retry logic istnieje, ale dodac monitoring |

### Mitygacja kluczowa: Izolacja agentow

Kazdy agent to osobny `Task` w bazie z osobnymi messages, subtasks, metrics. **Nie dotykamy AgentTurnLoop.** Wieloagentowość dziala na poziomie orkiestracji (wiele rownoleglych `AgentTurnLoop` instancji), nie na poziomie zmian w petli.

To oznacza ze:
- **Bug w agencie A nie moze zepsuc agenta B** (osobne task scope)
- **IntelliJ plugin nie widzi multi-agent** (uzywa istniejacego flow)
- **Kazdy agent moze byc cancelled niezaleznie** (per-agent `isCancelled`)

---

## Podsumowanie

**Dwie osie, jeden cel:**

1. **Stabilnosc core:** 4 krytyczne testy (LLMClient, ChatService, LLMRetryHandler, ContextProviderRegistry) + edge cases AgentTurnLoop. Szacunek: 2 tygodnie.

2. **CLI + multi-agent:** Source-set modularyzacja → Compose Desktop GUI → MultiAgentRunner overlay na istniejacy AgentTurnLoop. ~610 LOC nowego kodu, ~60 LOC zmian. Szacunek: 8 tygodni.

**Kluczowa decyzja architektoniczna:** Multi-agent NIE modyfikuje AgentTurnLoop — buduje na nim. Kazdy agent to osobny Task w DB z osobna instancja AgentTurnLoop w coroutine scope. To chroni stabilnosc IntelliJ i minimalizuje ryzyko regresji.
