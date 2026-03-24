# Refio — Standalone CLI + Multi-Agent: Kompletny przewodnik implementacji

> **Data:** 2026-03-24
> **Wersja:** 2.3 (2026-03-24 — 10 iteracji, 11 etapow, 9/14 providers, 0 TODOs, 0 test failures, ~290 nowych testow)
> **Cel:** Dokument dla agenta AI (juniora) — dlaczego, co i jak zrobic, krok po kroku

---

## Stan implementacji (2026-03-24)

| Etap | Status | Kompletnosc | Uwagi |
|------|--------|-------------|-------|
| **1. Testy P0** | ✅ DONE | 100% | 5 testow P0 + dodatkowe. Lacznie ~220 nowych testow w 23 plikach testowych. |
| **2. ProjectHandle** | ✅ DONE | 100% | Interface + StandaloneProjectHandle + IntelliJProjectHandle + CoreApiRouter integracja. |
| **3. Gradle modularyzacja** | ✅ DONE | 100% | 3 moduly: `:core` (pure Kotlin/JVM), `:intellij-plugin`, `:cli`. Core kompiluje BEZ IntelliJ SDK. |
| **4. Standalone bootstrap** | ✅ DONE | 100% | `StandaloneCoreBootstrap` + `main.kt` (Clikt CLI) + `CoreApiRouter.createProjectRouter()`. Modul `:cli` kompiluje i inicjalizuje core bez IntelliJ. |
| **5. CLI + Compose GUI** | ⚠️ ZASTAPIONE PRZEZ ETAP 11 | 100%→0% | Compose Desktop GUI zostaje **usuniety** w Etapie 11. Zastapiony przez TUI (Mordant+JLine3) w terminalu. Pliki Compose do usuniecia: App.kt, ChatPanel.kt, StatusPanel.kt, RefioViewModel, ComposeWorkflowListener, ChatMessageMapper, UIChatMessage. |
| **6. Interleaved chat** | ✅ DONE (logika) | 100% | UIChatMessage, ChatMessageMapper (8 kolorow per-agent, 10 typow eventow). Compose UI **usuniety w Etapie 11** — logika przeniesiona do TUI. |
| **7. GlobalMetrics + FileLock** | ✅ DONE | 100% | AgentMetrics per-agent + backward compat, FileLockManager w 5 write tools, OllamaRequestGate.maxConcurrentPerEndpoint. |
| **8. AgentEventBus + DB** | ✅ DONE | 100% | 12 typow eventow, SharedFlow bus, AgentEventHandler, 3 tabele DB, repozytoria, **AgentEventSqlRepository**, **agent_instance_id w ChatMessagesTable**. |
| **9. MultiAgentRunner** | ✅ DONE | 100% | Parallel execution z DAG deps, event emission, per-agent metrics, YAML task parser, **cycle detection w dependency graph**. |
| **10. GUI multi-agent** | ✅ DONE (logika) | 100% | AgentFlowPanel, AgentNode, ApprovalPanel, MetricsCard. Compose UI **usuniety w Etapie 11** — multi-agent GUI przeniesione do TUI (TuiStepsView, TuiStatusBar). |
| **11. TUI — Terminal User Interface** | 🔲 TODO | 0% | Wersja CLI z GUI rysowanym w terminalu (Lanterna/Mordant). Dziala na macOS Terminal, Windows Terminal, PowerShell, iTerm2. Bez okna desktopowego — caly UI w konsoli. |

**Ogolny postep: Etapy 1-10 zrealizowane. Etap 11 (TUI zastepujacy Compose Desktop) — do implementacji. Compose Desktop GUI zostaje USUNIETY — TUI jest jedynym GUI w module `:cli`.**

### Testy P1 (z doc 0002):
- PlanningServiceTest ✅ DONE (16 testow)
- SnapshotServiceTest ✅ DONE (13 testow)
- ConversationCompactorTest ✅ DONE (8 testow)

### Pliki utworzone/zmienione — iteracja 1 (Etapy 1-2, 7-9):

**Nowe pliki zrodlowe (12):**
- `core/project/ProjectHandle.kt` — interfejs abstrakcji projektu
- `core/project/StandaloneProjectHandle.kt` — implementacja CLI
- `core/tools/FileLockManager.kt` — mutex per-sciezka pliku
- `core/agents/events/AgentEvent.kt` — 12 typow eventow sealed interface
- `core/agents/events/AgentEventBus.kt` — SharedFlow event bus
- `core/agents/events/AgentEventHandler.kt` — per-agent async handler
- `core/agents/AgentSpec.kt` — spec + result data classes
- `core/agents/MultiAgentRunner.kt` — orkiestrator rownoleglych agentow
- `core/agents/MultiAgentTaskParser.kt` — YAML parser definicji zadan
- `core/db/AgentTables.kt` — 3 tabele + data classes + enum
- `core/db/repositories/AgentSessionRepository.kt`
- `core/db/repositories/AgentInstanceRepository.kt`

### Pliki utworzone/zmienione — iteracja 2 (Etap 2 dokonczenie, Etap 3, Etap 8 dokonczenie, testy P1):

**Nowe pliki zrodlowe (4):**
- `services/project/IntelliJProjectHandle.kt` — wrapper na `com.intellij.openapi.project.Project`, typed accessor `intellijProject`
- `core/db/repositories/AgentEventSqlRepository.kt` — SQL implementacja `AgentEventRepository`, Gson serialization/deserialization 12 typow eventow
- `core/api/RagModels.kt` — RAG DTOs przeniesione z UI do core (RagIndexedFileDto, RagStatisticsDto, RagChunkDto)
- `core/build.gradle.kts` — modul `:core` pure Kotlin/JVM, source-set exclude'y dla 13 plikow IDE-zaleznych
- `intellij-plugin/build.gradle.kts` — modul `:intellij-plugin` z pelnym IntelliJ SDK

**Zmienione pliki zrodlowe — Etap 3 refaktoryzacja (IDE independence):**
- `settings.gradle.kts` — 3 moduly: `:core`, `:intellij-plugin`, `:cli`
- `build.gradle.kts` — cienki root project (deleguje do submodulow)
- `core/api/CoreApiRouter.kt` — `ideProject: Any?`, `resolvedIdeProject: Any?`, `codebaseCacheInvalidator` callback, RAG DTOs z core
- `core/api/routers/AgentRouter.kt` — `ideProject: Any?`, usunieto `import com.intellij`
- `core/api/routers/RagRouter.kt` — `codebaseCacheInvalidator` callback zamiast `CodebaseContextProvider.invalidateCache()`, RAG DTOs z core
- `core/services/ChatService.kt` — `ideProject: Any?`
- `core/services/PlanningService.kt` — `ideProject: Any?`
- `core/services/ContextService.kt` — `project: Any?` (10 metod)
- `core/services/EmbeddingProvider.kt` — usunieto nieuzywany import `com.jetbrains.rd`
- `core/subagents/SubagentRouter.kt` — `ideProject: Any?`
- `core/context/ContextProviderRegistry.kt` — usunieto PluginManagerCore import, dodano `providerFactory` lambda
- `services/core/CoreConnectionManager.kt` — ustawia `providerFactory` z pelna lista IntelliJ providers
- `ui/components/rag/RagViewPanel.kt` — DTOs zamienione na typealias do core

**Zmienione pliki zrodlowe — Etap 8 dokonczenie:**
- `core/db/ChatMessagesTable.kt` — dodano kolumne `agent_instance_id` (nullable)
- `core/db/repositories/ChatMessageRepository.kt` — param `agentInstanceId`, `findByAgentInstanceId()`, mapping

**Zmienione pliki zrodlowe — MultiAgentRunner upgrade:**
- `core/agents/MultiAgentRunner.kt` — `validateDependencies()` z DFS cycle detection, walidacja nieznanych agentow

**Nowe pliki testowe — iteracja 1 (18 plikow, ~166 testow):**
- `core/llm/LLMClientTest.kt` (14 testow)
- `core/services/ChatServiceTest.kt` (9 testow)
- `core/services/LLMRetryHandlerTest.kt` (11 testow)
- `core/context/ContextProviderRegistryTest.kt` (11 testow)
- `core/services/turn/TurnGuardrailsTest.kt` (17 testow)
- `core/project/ProjectHandleTest.kt` (6 testow)
- `core/services/monitoring/GlobalMetricsMultiAgentTest.kt` (10 testow)
- `core/tools/FileLockManagerTest.kt` (5 testow)
- `core/tools/FileLockManagerIntegrationTest.kt` (4 testy)
- `core/agents/events/AgentEventBusTest.kt` (8 testow)
- `core/agents/events/AgentEventTest.kt` (15 testow)
- `core/agents/events/AgentEventHandlerTest.kt` (10 testow)
- `core/agents/MultiAgentRunnerTest.kt` (9 testow)
- `core/agents/MultiAgentTaskParserTest.kt` (12 testow)
- `core/db/repositories/AgentSessionRepositoryTest.kt` (5 testow)
- `core/db/repositories/AgentInstanceRepositoryTest.kt` (6 testow)
- `core/services/OllamaRequestGateTest.kt` (5 testow)
- `core/api/CoreApiRouterProjectHandleTest.kt` (4 testy)

**Nowe pliki testowe — iteracja 2 (5 plikow, ~55 testow):**
- `services/project/IntelliJProjectHandleTest.kt` (4 testy)
- `core/db/repositories/AgentEventSqlRepositoryTest.kt` (6 testow)
- `core/agents/MultiAgentRunnerCycleDetectionTest.kt` (12 testow)
- `core/services/SnapshotServiceTest.kt` (13 testow)
- `core/services/ConversationCompactorTest.kt` (8 testow)

### Pliki utworzone/zmienione — iteracja 3 (Etap 4 + PlanningServiceTest):

**Nowe pliki zrodlowe (3):**
- `cli/build.gradle.kts` — modul `:cli` z Clikt, zaleznoscią od `:core`
- `cli/src/main/kotlin/pl/jclab/refio/cli/StandaloneCoreBootstrap.kt` — inicjalizacja core bez IntelliJ (dwu-tierowa: app router + project router)
- `cli/src/main/kotlin/pl/jclab/refio/cli/main.kt` — Clikt CLI entry point z --project, --headless, --prompt

**Zmienione pliki zrodlowe:**
- `settings.gradle.kts` — odkomentowano `include(":cli")`
- `core/api/CoreApiRouter.kt` — dodano publiczna metode `createProjectRouter()` tworzaca project-level router z toolami

**Nowe pliki testowe — iteracja 3 (2 pliki, ~19 testow):**
- `cli/src/test/kotlin/pl/jclab/refio/cli/StandaloneCoreBootstrapTest.kt` (3 testy — lifecycle, pre-init error, graceful shutdown)
- `core/services/PlanningServiceTest.kt` (16 testow — plan creation, validation, JSON parsing, standalone compat, subtask creation)
- `core/agents/MultiAgentRunnerCycleDetectionTest.kt` (12 testow)
- `core/services/SnapshotServiceTest.kt` (13 testow)
- `core/services/ConversationCompactorTest.kt` (8 testow)

### Pliki utworzone/zmienione — iteracja 4 (Etapy 5, 6, 10: Compose Desktop GUI + Interleaved chat + Multi-agent GUI):

**Nowe pliki zrodlowe (7):**
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/App.kt` — root Compose composable, 2/3+1/3 layout, dark theme, loading/error screens, agent filtering
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ChatPanel.kt` — LazyColumn messages + input z Enter/Shift+Enter, MessageBubble z agent colors, StreamingIndicator, auto-scroll
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/StatusPanel.kt` — AgentFlowPanel (DAG z dependencies), AgentNode (status icons/colors/progress), ApprovalPanel (approve/reject z risk), MetricsCard (tokens/cost/agents/duration)
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/RefioViewModel.kt` — bridge core↔Compose: StateFlows, WorkflowOrchestrator integration, AgentEventBus subscription, approve/reject, agentFilter, AgentState/MetricsInfo/PendingApproval DTOs
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/UIChatMessage.kt` — UI message model z agentId/agentName/agentColor, MessageType enum (7 typow)
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ComposeWorkflowListener.kt` — WorkflowEventListener impl, streaming accumulation z StringBuilder, error handling
- `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ChatMessageMapper.kt` — mapuje 10 typow AgentEvent → UIChatMessage, 8 kolorow per-agent, singleton z reset()

**Zmienione pliki zrodlowe (2):**
- `cli/build.gradle.kts` — Kotlin 2.0.21 + Compose Desktop 1.7.3 (kotlin.plugin.compose + compose.desktop.currentOs + compose.material3). Kotlin 2.0 w `:cli` konsumuje `:core` (1.9.25) bez problemow — binarna kompatybilnosc.
- `cli/src/main/kotlin/pl/jclab/refio/cli/main.kt` — dodano --mode, --model, --no-egress opcje, `launchComposeApp()` call zamiast "not yet implemented"

### Pliki utworzone/zmienione — iteracja 5 (MultiAgentRunner → CoreApiRouter + CHANGELOG):

**Zmienione pliki zrodlowe (2):**
- `core/api/ApiModels.kt` — dodano `MultiAgentSessionRequest`, `MultiAgentSessionResponse`, `MultiAgentInstanceResponse`
- `core/api/CoreApiRouter.kt` — dodano `agentEventBus`, `agentSessionRepository`, `agentInstanceRepository`, `multiAgentRunner` (lazy), `launchMultiAgentSession()`, `getMultiAgentSession()`, `listMultiAgentSessions()`

**Zmienione pliki dokumentacji (1):**
- `CHANGELOG.md` — sekcje: Multi-Agent API, Standalone CLI + Compose Desktop GUI

**Nowe pliki testowe (1, 6 testow):**
- `core/api/CoreApiRouterMultiAgentTest.kt`

### Pliki utworzone/zmienione — iteracja 6 (PathSandbox fix + headless mode + Compose UI tests + integration tests):

**Zmienione pliki zrodlowe (2):**
- `core/tools/PathSandbox.kt` — `normalizedRoot = projectRoot.toRealPath()`, nowa `resolveComparablePath()`, fix `rejectSymlinks()` z real-path parent walk. Naprawiono ~181 pre-existing test failures (macOS `/var` → `/private/var` symlink).
- `cli/main.kt` — pełna implementacja `runHeadless()`: `createTask()` → `UIState` → `WorkflowOrchestrator.execute()` → stdout. Streaming chunks na bieżąco.

**Nowe pliki testowe (5, ~31 testów):**
- `cli/ui/ChatPanelTest.kt` (6 testów — wiadomości, streaming indicator, agent events)
- `cli/ui/StatusPanelTest.kt` (8 testów — empty state, agents, metrics, approvals, callback)
- `cli/ui/AppTest.kt` (5 testów — loading/error screen, model, ChatMessageMapper)
- `core/api/MultiAgentIntegrationTest.kt` (6 testów — real DB, cyclic deps, session lifecycle)

**Zmienione pliki testowe (3):**
- `core/tools/PathSandboxTest.kt` — asercje zaktualizowane do `toRealPath()` (3 testy)
- `MultiEditToolTest.kt` — asercja cumulative edits
- `RunTerminalCommandToolTest.kt` — cross-platform `ls`/`dir`

**Zmienione pliki build (1):**
- `cli/build.gradle.kts` — dodano `compose.desktop.uiTestJUnit4` + `junit-vintage-engine`

### Pliki utworzone/zmienione — iteracja 7 (EventBus wiring + --multi-agent + ConfigRouter + bug fixes):

**Zmienione pliki zrodlowe (4):**
- `cli/ui/RefioViewModel.kt` — dodano `bridgeBackendEventBus()`: `router.agentEventBus.events.collect { agentEventBus.emit(it) }` w `initialize()`
- `cli/main.kt` — dodano `--multi-agent <file>` flag, `runMultiAgent()` z YAML parsing → `launchMultiAgentSession()` → per-agent stdout, summary stderr
- `core/api/routers/ConfigRouter.kt` — `resetAllSettingsToDefaults()` zaimplementowany: `deleteByScope(APP)` + `deleteByScope(PROJECT)` + `initializeDefaults()`
- `core/db/repositories/ConfigRepository.kt` — dodano `deleteByScope(scope, projectId)`

### Pliki utworzone/zmienione — iteracja 8 (Standalone context providers + Help + TODO cleanup):

**Nowe pliki zrodlowe (7):**
- `core/context/providers/standalone/StandaloneFileContextProvider.kt` — `Files.walk()` zamiast `FilenameIndex`, pattern search, file reading z PathSandbox
- `core/context/providers/standalone/StandaloneFolderContextProvider.kt` — directory tree via filesystem API, top-level + pattern search
- `core/context/providers/standalone/StandaloneGitDiffContextProvider.kt` — `git diff --stat` + `git diff HEAD` + `git ls-files --others` via CLI
- `core/context/providers/standalone/StandaloneGitCommitContextProvider.kt` — `git log/show/rev-parse` via CLI, hash resolve + message search
- `core/context/providers/standalone/StandaloneGrepSearchContextProvider.kt` — Java `Pattern` regex search z `Files.walk()`, 50 result limit, binary exclusion
- `core/context/providers/standalone/StandaloneCodebaseContextProvider.kt` — RAG semantic search bez `com.intellij.openapi.project.Project`
- `core/context/providers/standalone/StandaloneDocsContextProvider.kt` — documentation RAG search z `workspacePath`

**Zmienione pliki zrodlowe (8):**
- `core/context/providers/CurrentFileContextProvider.kt` — dodano `override val environment = IDE_ONLY`
- `core/context/providers/OpenFilesContextProvider.kt` — dodano `override val environment = IDE_ONLY`
- `core/context/providers/RecentFilesContextProvider.kt` — dodano `override val environment = IDE_ONLY`
- `core/context/providers/ProblemsContextProvider.kt` — usunieto duplikat `environment` (juz bylo)
- `core/context/providers/TerminalContextProvider.kt` — usunieto duplikat `environment` (juz bylo)
- `cli/StandaloneCoreBootstrap.kt` — `providerFactory` rozszerzone o 7 standalone providers
- `ui/toolwindow/RefioMainPanel.kt` — `showHelp()` → `BrowserUtil.browse()`, steps visibility no-op comment
- `ui/components/toolbar/ToolbarComponent.kt` — `onHelpClicked()` → `BrowserUtil.browse()`
- `ui/components/toolbar/SessionContextBar.kt` — TODO zamienione na design comment
- `ui/components/steps/StepsQueueView.kt` — TODO zamieniony na design comment

**Zmienione pliki build (1):**
- `core/build.gradle.kts` — komentarz ze standalone providers sa included

**Nowe pliki testowe (1, 15 testów):**
- `core/context/providers/standalone/StandaloneContextProvidersTest.kt` — 15 testów: file list/search/read, folder tree/list, git diff, git commit search, grep regex/pattern, codebase/docs metadata

### Pliki utworzone/zmienione — iteracja 9 (result_summary + ostatni TODO):

**Zmienione pliki zrodlowe (5):**
- `core/models/api/PlanningModels.kt` — dodano `resultSummary: String? = null` do `SubtaskResponse`
- `core/api/PlanningModels.kt` — dodano `resultSummary: String? = null` do `SubtaskResponse`
- `core/services/PlanningService.kt` — mapping `subtask.summary ?: subtask.result?.take(500)` → `resultSummary`
- `api/models/TaskPlanModels.kt` — dodano `@SerializedName("result_summary") val resultSummary: String? = null` do `SubtaskDto`
- `services/session/SubtaskTracker.kt` — mapping `coreSubtask.resultSummary` → `SubtaskDto`
- `ui/components/steps/StepsQueueView.kt` — wyswietlanie `resultSummary` w sekcji "Result:"
- `core/llm/Base.kt` — TODO zamieniony na komentarz designowy

---

## Spis tresci

- [Czesc I: Kontekst i cele](#czesc-i-kontekst-i-cele)
- [Czesc II: Stan obecny — co mamy](#czesc-ii-stan-obecny--co-mamy)
- [Czesc III: Plan implementacji — 10 etapow](#czesc-iii-plan-implementacji--10-etapow)
  - [Etap 1: Testy krytycznych luk](#etap-1-testy-krytycznych-luk)
  - [Etap 2: ProjectHandle — abstrakcja projektu](#etap-2-projecthandle--abstrakcja-projektu)
  - [Etap 3: Modularyzacja Gradle](#etap-3-modularyzacja-gradle)
  - [Etap 4: Standalone bootstrap](#etap-4-standalone-bootstrap)
  - [Etap 5: CLI entry point + Compose Desktop GUI](#etap-5-cli-entry-point--compose-desktop-gui)
  - [Etap 6: Interleaved chat i streaming](#etap-6-interleaved-chat-i-streaming)
  - [Etap 7: GlobalMetrics per-agent + FileLockManager](#etap-7-globalmetrics-per-agent--filelockmanager)
  - [Etap 8: AgentEventBus + event system](#etap-8-agenteventbus--event-system)
  - [Etap 9: MultiAgentRunner + orchestrator](#etap-9-multiagentrunner--orchestrator)
  - [Etap 10: GUI multi-agent — DAG, approvals, interleaved chat](#etap-10-gui-multi-agent--dag-approvals-interleaved-chat)
  - [Etap 11: TUI — Terminal User Interface](#etap-11-tui--terminal-user-interface)
- [Czesc IV: Architektura docelowa](#czesc-iv-architektura-docelowa)
- [Czesc V: Zasady bezpieczenstwa implementacji](#czesc-v-zasady-bezpieczenstwa-implementacji)

---

# Czesc I: Kontekst i cele

## Co to jest Refio

Refio to plugin IntelliJ IDEA (~346 plikow Kotlin, ~118K LOC) — lokalny asystent AI z trzema trybami pracy:
- **Chat** — rozmowa z LLM + kontekst projektu
- **Plan** — analiza read-only z narzędziami
- **Agent** — pelny read/write z autonomicznym wykonywaniem

Architektura: `UI (Swing/IntelliJ)` → `Services (plugin)` → `Core (logika biznesowa)` → `Infrastructure (SQLite, HTTP, LLM)`.

## Dlaczego robimy te zmiany

1. **Potrzebujemy wersji standalone (CLI z GUI)** — do kursu AI i benchmarkow, bez IntelliJ
2. **Potrzebujemy multi-agentowosci** — wielu agentow pracujacych rownolegle, z komunikacja miedzy nimi
3. **GUI musi byc uzyteczne dla nie-programistow** — prosty uklad, zrozumiale etykiety
4. **W przyszlosci moze powstac wersja Web (React)** — trzeba to uwzglednic w architekturze

## Co konkretnie budujemy

```
PRZED:                              PO:
┌─────────────────┐                ┌─────────────────┐  ┌──────────────────────┐
│  IntelliJ Plugin│                │  IntelliJ Plugin│  │  CLI TUI             │
│  (Swing UI)     │                │  (Swing UI)     │  │  (Mordant+JLine3     │
└────────┬────────┘                └────────┬────────┘  │  w terminalu)        │
         │                                  │           └──────────┬───────────┘
┌────────▼────────┐                ┌────────▼──────────────────────▼───────────┐
│  Core (coupled  │                │  Core Module (0 IntelliJ deps)            │
│  to IntelliJ)   │                │  + Multi-Agent Event Bus                  │
└─────────────────┘                │  + Agent Orchestrator                     │
                                   └──────────────────────────────────────────┘
```

---

# Czesc II: Stan obecny — co mamy

## Struktura plikow

```
src/main/kotlin/pl/jclab/refio/
├── core/           # 240 plikow — logika biznesowa (93% BEZ IntelliJ imports)
│   ├── api/        # CoreApiRouter + 9 domain routerow
│   ├── services/   # 35 serwisow (AgentTurnLoop, ChatService, etc.)
│   ├── llm/        # 8 adapterow LLM (Ollama, OpenAI, Anthropic, Gemini...)
│   ├── tools/      # 12 narzedzi + security (sandbox, whitelist)
│   ├── db/         # SQLite/Exposed ORM, 12 repozytoriow
│   ├── context/    # 14 context providerow + MCP
│   ├── workflow/   # WorkflowOrchestrator, IntentRouter
│   ├── subagents/  # 21 wbudowanych subagentow (.md)
│   └── config/     # Hierarchiczna konfiguracja YAML
├── ui/             # 70 plikow — Swing/IntelliJ UI
├── services/       # 20 plikow — plugin services (IntelliJ-specific)
└── actions/        # 8 plikow — IDE actions
```

## Co juz dziala na nasza korzysc

| Mechanizm | Gdzie | Jak pomaga |
|-----------|-------|------------|
| `ideProject: Project? = null` | CoreApiRouter, ChatService, PlanningService (6 plikow) | Juz nullable — mozna przekazac null w standalone |
| `ContextProviderRegistry.initialize(isIdeEnvironment=false)` | ContextProviderRegistry.kt | Pomija IDE-only providery (Terminal, Problems, OpenFiles) |
| `ContextProviderEnvironment.IDE_ONLY` enum | BaseContextProvider.kt | Providery juz oznaczone — skip przy non-IDE |
| `UIAdapter` interface | core/api/UIAdapter.kt | Abstrakcja UI — gotowa na implementacje CLI |
| `runId` + `parentRunId` + `depth` | AgentTurnLoop, TurnProfileOverrides | Sledzenie hierarchii subagentow |
| `subagentChain` | TurnProfileOverrides | Zapobieganie rekursji |
| `metadata` JSON w `chat_messages` | ChatMessagesTable.metadata | Elastyczne — mozna dodac agentId bez migracji |
| `StreamCallback` typealias | StreamTypes.kt | Framework-agnostic streaming |
| `WorkflowEventListener` interface | workflow/ | Czyste eventy bez Swing |

## Co trzeba zmienic (i ile to LOC)

| Zmiana | Typ | LOC | Ryzyko |
|--------|-----|-----|--------|
| `ProjectHandle` interface | Nowy plik | ~30 | Zerowe |
| Zamiana `Project?` → `ProjectHandle?` w 6 plikach | Refactor | ~20 | Niskie |
| `ContextProviderSPI` | Nowy plik | ~20 | Zerowe |
| `StandaloneCoreBootstrap` | Nowy plik | ~60 | Zerowe |
| `GlobalMetrics` per-agent | Zmiana | ~70 | Niskie (backward compat) |
| `FileLockManager` | Nowy plik | ~30 | Zerowe |
| `AgentEventBus` | Nowy plik | ~80 | Zerowe |
| `MultiAgentRunner` | Nowy plik | ~120 | Zerowe |
| DB: 3 nowe tabele + 1 kolumna | Migracja | ~100 | Niskie |
| Compose Desktop GUI | Nowe pliki | ~800 | Zerowe (osobny modul) |
| CLI entry point (Clikt) | Nowy plik | ~50 | Zerowe |

**Lacznie: ~1380 LOC nowego kodu, ~90 LOC zmian w istniejacym.**

## Pokrycie testami — luki ktore MUSZA byc zatkane

| Komponent | Dlaczego krytyczny | Priorytet |
|-----------|-------------------|-----------|
| `LLMClient.kt` | Centralny punkt WSZYSTKICH zapytan LLM, brak testu | **P0** |
| `ChatService.kt` | Glowny flow chatu, brak testu | **P0** |
| `LLMRetryHandler.kt` | Exponential backoff, brak testu | **P0** |
| `ContextProviderRegistry.kt` | Inicjalizacja z `isIdeEnvironment=false` — krytyczne dla CLI | **P0** |
| `TurnGuardrails.kt` | Loop detection — bez testu ryzyko nieskonczonej petli | **P0** |
| `PlanningService.kt` | Generowanie planow, JSON parsing | P1 |
| `SnapshotService.kt` | Rollback — krytyczne dla AGENT mode | P1 |
| `ParallelToolExecutor.kt` | Rownolegle narzedzia — brak testu race conditions | P1 |

## Obecny flow subagenta (InvokeSubagentTool)

```
InvokeSubagentTool.execute(params)
  → walidacja: recursion (subagentChain), depth (max 3), enabled
  → tworzy TurnRequest z TurnRunProfile.SUBAGENT
    ├─ systemPromptOverride, allowedTools/disallowedTools
    ├─ modelOverride, contextProfile
    └─ parentRunId, depth+1, subagentChain + currentName
  → runTurnCallback(request, turnListener, streamCallback)
    └─ AgentTurnLoop uruchamia pelny cykl: prompt → LLM → tools → loop
  → zwraca TurnResult {success, response, iterations, tokens, cost, toolsUsed}
  → wynik persystowany jako TOOL message w chat_messages
    └─ metadata: {"subagent_name":"...", "depth":N}
```

**Kluczowy insight:** Kazdy `AgentTurnLoop` juz dzisiaj jest self-contained — przyjmuje `taskId` i operuje na swoim zbiorze wiadomosci. Wystarczy uruchomic N instancji rownolegle z osobnymi taskId.

## Singletons ktore blokuja multi-agent

| Singleton | Problem | Rozwiazanie |
|-----------|---------|-------------|
| `GlobalMetrics._currentOperation` | Jeden `StateFlow`, nadpisywany przez kazdego agenta | Per-agent metrics + global agregat |
| `GlobalMetrics._isCancelled` | Jeden `AtomicBoolean`, cancel jednego cancelluje wszystkich | Per-agent cancellation flags |
| `OllamaRequestGate` | `Semaphore(1)` per endpoint | Konfigurowalny (default 1, wiekszy dla multi-agent) |

## Zasoby ktore sa BEZPIECZNE dla concurrent access

Potwierdzone analiza kodu — **NIE wymagaja zmian:**
- `ToolRegistry` — read-only catalog, ConcurrentHashMap
- `LLMClient` — stateless, kazde wywolanie niezalezne
- `PathSandbox` — walidacja sciezek, brak stanu
- `ConfigService` — cache z ConcurrentHashMap, thread-safe reads
- `MCPManager` — ConcurrentHashMap per-project
- `SubagentRegistry` — read-only po inicjalizacji
- `UserInteraction` — UUID-keyed ConcurrentHashMap
- `RichProjectAnalysisEngine` — per-project Mutex (juz chroniony)
- `FileAnalyzerService` — cache keyed by file path, nie agent

---

# Czesc III: Plan implementacji — 10 etapow

Kazdy etap opisany jako: **DLACZEGO → CO → JAK → WERYFIKACJA**.

---

## Etap 1: Testy krytycznych luk

> **STATUS: ✅ DONE (2026-03-23)**
> Wszystkie 5 testow P0 napisane i przechodzace. Dodatkowo napisano 13 kolejnych plikow testowych pokrywajacych nowe komponenty (AgentEventHandler, AgentEvent, OllamaRequestGate, CoreApiRouter projectHandle, FileLockManager integration). Lacznie **166 nowych testow, 0 failures**.
> Brakujace testy P1 z doc 0002: PlanningServiceTest, SnapshotServiceTest, ConversationCompactorTest.

### DLACZEGO
61% commitow to fixy. Agent Turn Loop mial 3 dedykowane fix commity. Bez testow nie mozemy bezpiecznie refaktorowac core. Testy to **warunek konieczny** przed jakimikolwiek zmianami.

### CO
Napisac 5 testow dla komponentow bez pokrycia:

1. `LLMClientTest.kt`
2. `ChatServiceTest.kt`
3. `LLMRetryHandlerTest.kt`
4. `ContextProviderRegistryTest.kt`
5. `TurnGuardrailsTest.kt`

### JAK

**Plik:** `src/test/kotlin/pl/jclab/refio/core/llm/LLMClientTest.kt`

```kotlin
class LLMClientTest {
    // Uzyj MockK do mockowania adapterow
    private val mockAdapter = mockk<BaseLLMAdapter>()
    private val configService = mockk<ConfigService>()

    @Test
    fun `should select correct adapter based on provider`() { ... }

    @Test
    fun `should enforce no-egress mode`() { ... }

    @Test
    fun `should call stream callback on streaming response`() { ... }

    @Test
    fun `should map errors via LLMErrorMapper`() { ... }

    @Test
    fun `should estimate tokens before sending`() { ... }
}
```

**Plik:** `src/test/kotlin/pl/jclab/refio/core/services/ChatServiceTest.kt`

```kotlin
class ChatServiceTest {
    // Uzyj TestDatabase (in-memory SQLite) z testutil/
    private val db = TestDatabase()

    @Test
    fun `should persist user and assistant messages`() { ... }

    @Test
    fun `should build context with project analysis`() { ... }

    @Test
    fun `should handle summarization flow`() { ... }

    @Test
    fun `should work with ideProject=null`() { ... }  // Krytyczne dla CLI!
}
```

**Plik:** `src/test/kotlin/pl/jclab/refio/core/context/ContextProviderRegistryTest.kt`

```kotlin
class ContextProviderRegistryTest {
    @Test
    fun `should skip IDE-only providers when isIdeEnvironment is false`() {
        val registry = ContextProviderRegistry()
        registry.initialize(isIdeEnvironment = false)

        // Nie powinno byc Terminal, Problems, CurrentFile, OpenFiles, RecentFiles
        val providers = registry.getAllProviders()
        assertFalse(providers.any { it is TerminalContextProvider })
        assertFalse(providers.any { it is ProblemsContextProvider })

        // Powinny byc: Codebase, URL, Docs, GitCommit, Clipboard, Folder
        assertTrue(providers.any { it is CodebaseContextProvider })
    }

    @Test
    fun `should not crash without IntelliJ terminal plugin`() {
        // Kluczowe — ContextProviderRegistry uzywa PluginManagerCore
        // W standalone nie ma tego API
    }
}
```

**Plik:** `src/test/kotlin/pl/jclab/refio/core/services/turn/TurnGuardrailsTest.kt`

```kotlin
class TurnGuardrailsTest {
    @Test
    fun `ToolErrorTracker should detect high error rate`() {
        val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
        repeat(8) { tracker.record(false) } // 8 failures
        repeat(2) { tracker.record(true) }  // 2 successes
        assertTrue(tracker.errorRate() > 0.7) // 80% > threshold 70%
    }

    @Test
    fun `LoopDetector should detect repeated tool calls`() {
        val detector = TurnGuardrails.LoopDetector()
        repeat(3) { detector.record("read_file", mapOf("path" to "same.kt")) }
        assertEquals(TurnGuardrails.LoopStatus.ABORT, detector.status())
    }

    @Test
    fun `LoopDetector should not flag different tool calls`() {
        val detector = TurnGuardrails.LoopDetector()
        detector.record("read_file", mapOf("path" to "a.kt"))
        detector.record("read_file", mapOf("path" to "b.kt"))
        detector.record("grep_search", mapOf("pattern" to "foo"))
        assertEquals(TurnGuardrails.LoopStatus.OK, detector.status())
    }
}
```

### WERYFIKACJA
```bash
./gradlew test --tests "*.LLMClientTest"
./gradlew test --tests "*.ChatServiceTest"
./gradlew test --tests "*.LLMRetryHandlerTest"
./gradlew test --tests "*.ContextProviderRegistryTest"
./gradlew test --tests "*.TurnGuardrailsTest"
```
Wszystkie musza przechodzic. CI (`./gradlew test`) musi byc zielone.

---

## Etap 2: ProjectHandle — abstrakcja projektu

> **STATUS: ✅ DONE (2026-03-23) — 100%**
> IntelliJProjectHandle dodany w `services/project/IntelliJProjectHandle.kt` z typed accessor `intellijProject`.
> Zaimplementowano: `ProjectHandle` interface, `StandaloneProjectHandle`, `CoreApiRouter` z parametrem `projectHandle` + `resolvedIdeProject` (backward compat), `BaseContextProvider` zmieniony na `Any?`, 12 context providerow zaktualizowanych z safe cast.
> **Brak:** `IntelliJProjectHandle` wrapper w module plugin (nie krytyczne — istniejacy `ideProject` parametr nadal dziala).
> Pliki: `core/project/ProjectHandle.kt`, `core/project/StandaloneProjectHandle.kt`, zmiany w CoreApiRouter.kt, BaseContextProvider.kt, 12 providerow.

### DLACZEGO
Core uzywa `com.intellij.openapi.project.Project` w 6 plikach. Zeby zbudowac standalone, musimy zastapic go interfejsem. To jest **najmniejsza i najbezpieczniejsza zmiana** ktora odblokuje cala reszta.

### CO
1. Nowy interfejs `ProjectHandle`
2. Dwie implementacje: `StandaloneProjectHandle`, `IntelliJProjectHandle`
3. Zamiana `Project?` → `ProjectHandle?` w 6 plikach core
4. Zamiana `Project` → `Any?` w `BaseContextProvider` (LoadSubmenuItemsArgs ma non-nullable `Project`)

### JAK

**Nowy plik:** `src/main/kotlin/pl/jclab/refio/core/project/ProjectHandle.kt`

```kotlin
package pl.jclab.refio.core.project

import java.nio.file.Path

/**
 * Platform-agnostic project abstraction.
 * IntelliJ plugin uses IntelliJProjectHandle, CLI uses StandaloneProjectHandle.
 */
interface ProjectHandle {
    val id: String           // Deterministyczny hash sciezki (ProjectIdGenerator)
    val name: String         // Nazwa projektu/katalogu
    val rootPath: Path       // Sciezka do katalogu projektu
}
```

**Nowy plik:** `src/main/kotlin/pl/jclab/refio/core/project/StandaloneProjectHandle.kt`

```kotlin
package pl.jclab.refio.core.project

import pl.jclab.refio.core.utils.ProjectIdGenerator
import java.nio.file.Path

class StandaloneProjectHandle(override val rootPath: Path) : ProjectHandle {
    override val id: String = ProjectIdGenerator.generate(rootPath.toString())
    override val name: String = rootPath.fileName?.toString() ?: "project"
}
```

**Zmiana w:** `src/main/kotlin/pl/jclab/refio/core/api/CoreApiRouter.kt`

```kotlin
// PRZED (linia ~73):
private val ideProject: com.intellij.openapi.project.Project? = null,

// PO:
private val projectHandle: ProjectHandle? = null,
```

**Zmiana w:** `ChatService.kt`, `PlanningService.kt`, `ContextService.kt`, `AgentRouter.kt`, `SubagentRouter.kt` — analogicznie.

**Zmiana w:** `BaseContextProvider.kt`

```kotlin
// PRZED:
data class ContextProviderExtras(
    val project: com.intellij.openapi.project.Project? = null,
    ...
)
data class LoadSubmenuItemsArgs(
    val query: String = "",
    val project: com.intellij.openapi.project.Project  // NON-NULLABLE!
)

// PO:
data class ContextProviderExtras(
    val projectHandle: Any? = null,  // Opaque — IntelliJ casts to Project, CLI ignores
    ...
)
data class LoadSubmenuItemsArgs(
    val query: String = "",
    val projectHandle: Any? = null  // Nullable teraz
)
```

**W module IntelliJ plugin** (services/core/CoreConnectionManager.kt) — wrapper:

```kotlin
class IntelliJProjectHandle(private val ideProject: Project) : ProjectHandle {
    override val id = ProjectIdGenerator.generate(ideProject.basePath ?: "")
    override val name = ideProject.name
    override val rootPath = Path.of(ideProject.basePath ?: "")

    /** Dostep do oryginalnego Project dla IDE-specific providerow */
    val intellijProject: Project get() = ideProject
}
```

### WERYFIKACJA
```bash
./gradlew test  # Wszystkie istniejace testy MUSZA przechodzic
```
Szukaj: zadnych `com.intellij.openapi.project.Project` importow w `core/api/`, `core/services/ChatService.kt`, `core/services/PlanningService.kt`.

---

## Etap 3: Modularyzacja Gradle

> **STATUS: ✅ DONE (2026-03-23)**
> Trzy moduly Gradle: `:core` (pure Kotlin/JVM, 0 importow IntelliJ), `:intellij-plugin` (pelny plugin), `:cli` (placeholder).
> Source-set approach bez przenoszenia plikow. 13 plikow z bezposrednimi IntelliJ API calls wykluczonych z `:core`.
> Refaktoryzacja: `ideProject: Project?` → `Any?` w 7 plikach core, ContextProviderRegistry.providerFactory lambda,
> RagRouter.codebaseCacheInvalidator callback, RAG DTOs przeniesione do core/api/.
> **Weryfikacja:**
> - `./gradlew :core:compileKotlin` — PASS (bez IntelliJ SDK)
> - `./gradlew :intellij-plugin:compileKotlin` — PASS
> - `./gradlew :core:test` (key tests) — PASS
> - `./gradlew :intellij-plugin:test` — PASS

### DLACZEGO
Zeby zbudowac CLI, musimy moc kompilowac `core/` BEZ IntelliJ Platform SDK. Robimy to przez Gradle multi-module **bez przenoszenia plikow** — source sets wskazuja na istniejace katalogi.

### CO
1. Nowy `settings.gradle.kts` z modulami
2. `core/build.gradle.kts` — kompiluje TYLKO core, bez IntelliJ
3. `cli/build.gradle.kts` — Compose Desktop + Clikt
4. Istniejacy `build.gradle.kts` staje sie modulem `:intellij-plugin`

### JAK

**Zmiana:** `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()  // Dla Compose
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "refio"

include(":core")
include(":intellij-plugin")
include(":cli")
```

**Nowy plik:** `core/build.gradle.kts`

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
}

// KLUCZOWE: source set wskazuje na istniejace pliki, nie przenosimy niczego
sourceSets {
    main {
        kotlin {
            srcDir("../src/main/kotlin")
            include("pl/jclab/refio/core/**")
        }
        resources {
            srcDir("../src/main/resources")
            include("subagents/**")
        }
    }
    test {
        kotlin {
            srcDir("../src/test/kotlin")
            include("pl/jclab/refio/core/**")
            include("pl/jclab/refio/testutil/**")
        }
    }
}

dependencies {
    // Coroutines — JAWNIE (w IntelliJ plugin dostarczane przez platform)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Reszta identyczna jak w glownym build.gradle.kts
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-client-logging:2.3.7")
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.charleskorn.kaml:kaml:0.55.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testy
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
}

// BEZ intellij {} — to jest czysty Kotlin/JVM modul
```

**Nowy plik:** `cli/build.gradle.kts`

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.compose") version "1.7.0"
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

compose.desktop {
    application {
        mainClass = "pl.jclab.refio.cli.MainKt"
    }
}
```

### WERYFIKACJA
```bash
./gradlew :core:test          # Testy core BEZ IntelliJ Platform — MUSI byc zielone
./gradlew :intellij-plugin:test  # Istniejace testy pluginu — MUSI byc zielone
./gradlew :cli:compileKotlin  # CLI sie kompiluje
```

**KRYTYCZNE:** Jezeli `./gradlew :core:test` nie przechodzi z powodu `com.intellij` importow — wroc do Etapu 2 i usun pozostale zaleznosci.

---

## Etap 4: Standalone bootstrap

> **STATUS: ✅ DONE (2026-03-24)**
> `StandaloneCoreBootstrap` inicjalizuje core bez IntelliJ: database, config, tools, context providers.
> `CoreApiRouter.createProjectRouter()` — publiczna metoda tworzaca project-level router z toolami (wczesniej `internal` serwisy blokowaly dostep z `:cli`).
> CLI entry point z Clikt: `--project`, `--headless`, `--prompt`.
> Weryfikacja: `:cli:compileKotlin` PASS, `:cli:test` PASS (3 testy), `:core:test` PASS (0 nowych failures), `:intellij-plugin:compileKotlin` PASS.

### DLACZEGO
Core potrzebuje inicjalizacji (baza danych, toole, config). W IntelliJ robi to `CoreConnectionManager`. W standalone potrzebujemy odpowiednika.

### CO
Nowy plik `StandaloneCoreBootstrap` ktory inicjalizuje caly core bez IntelliJ.

### JAK

**Nowy plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/StandaloneCoreBootstrap.kt`

```kotlin
package pl.jclab.refio.cli

import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.project.ProjectHandle
import pl.jclab.refio.core.project.StandaloneProjectHandle
import pl.jclab.refio.core.tools.base.ToolFactory
import pl.jclab.refio.core.tools.base.ToolRegistry
import java.nio.file.Path

class StandaloneCoreBootstrap(
    private val projectPath: Path
) {
    private var router: CoreApiRouter? = null

    suspend fun initialize(): CoreApiRouter {
        val projectHandle = StandaloneProjectHandle(projectPath)

        // 1. Baza danych w katalogu projektu
        val dbDir = projectPath.resolve(".refio").toFile()
        if (!dbDir.exists()) dbDir.mkdirs()
        val dbPath = dbDir.resolve("database.sqlite").absolutePath
        DatabaseFactory.init(dbPath)

        // 2. Registry narzedzi
        val toolRegistry = ToolRegistry()

        // 3. Router — ideProject = null
        val coreRouter = CoreApiRouter(
            toolRegistry = toolRegistry,
            projectRoot = projectPath,
            projectHandle = null  // Standalone, brak IntelliJ Project
        )
        coreRouter.initialize(dbPath)

        // 4. Context providers — BEZ IDE
        ContextProviderRegistry.initialize(isIdeEnvironment = false)

        router = coreRouter
        return coreRouter
    }

    suspend fun shutdown() {
        router?.shutdown()
    }
}
```

### WERYFIKACJA
Napisz test:
```kotlin
class StandaloneCoreBootstrapTest {
    @Test
    fun `should initialize core without IntelliJ`() = runTest {
        val tmpDir = Files.createTempDirectory("refio-test")
        val bootstrap = StandaloneCoreBootstrap(tmpDir)
        val router = bootstrap.initialize()
        assertNotNull(router)
        bootstrap.shutdown()
        tmpDir.toFile().deleteRecursively()
    }
}
```

---

## Etap 5: CLI entry point + Compose Desktop GUI

> **STATUS: ✅ DONE (2026-03-24)**
> Compose Desktop 1.7.3 z Kotlin 2.0.21 w module `:cli` (core pozostaje na 1.9.25 — Kotlin 2.0 konsumuje 1.9.x artefakty bez problemow).
> Zaimplementowano: App.kt (root composable, dark theme, loading/error screens), ChatPanel.kt (LazyColumn + input z Enter/Shift+Enter), StatusPanel.kt (metryki + lista agentow), RefioViewModel (StateFlows, WorkflowOrchestrator integration), ComposeWorkflowListener (streaming chunks, accumulated content, error handling).
> CLI entry point rozszerzony o --mode, --model, --no-egress opcje. GUI launch via `launchComposeApp()`.

### DLACZEGO
Uzytkownik uruchamia `refio --project /path` z terminala. Otwiera sie okno z 2-kolumnowym layoutem: 2/3 chat, 1/3 status.

### CO
1. `main.kt` z Clikt parserem argumentow
2. `App.kt` — root Compose composable
3. `ChatPanel.kt` — panel chatu (lista wiadomosci + input)
4. `StatusPanel.kt` — panel statusu (taski, metryki)
5. `RefioViewModel.kt` — bridge miedzy core a Compose state
6. `ComposeWorkflowListener.kt` — odpowiednik `SwingWorkflowListener`

### JAK

**Plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/main.kt`

```kotlin
package pl.jclab.refio.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path

class RefioCommand : CliktCommand(name = "refio") {
    val project by option("--project", "-p").path().default(Path.of("."))
    val mode by option("--mode", "-m").enum<TaskMode>().default(TaskMode.CHAT)
    val model by option("--model")
    val headless by option("--headless").flag()
    val prompt by option("--prompt")  // Dla headless
    val noEgress by option("--no-egress").flag()

    override fun run() {
        if (headless && prompt != null) {
            HeadlessRunner(project, mode, model, prompt!!, noEgress).run()
        } else {
            launchComposeApp(project, mode, model, noEgress)
        }
    }
}

fun main(args: Array<String>) = RefioCommand().main(args)
```

**Plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/ui/App.kt`

```kotlin
package pl.jclab.refio.cli.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun launchComposeApp(projectPath: Path, mode: TaskMode, model: String?, noEgress: Boolean) {
    application {
        val viewModel = remember { RefioViewModel(projectPath, mode, model, noEgress) }

        // Inicjalizacja core w coroutine
        LaunchedEffect(Unit) { viewModel.initialize() }

        Window(
            onCloseRequest = { viewModel.shutdown(); exitApplication() },
            title = "Refio — ${projectPath.fileName}"
        ) {
            MaterialTheme {
                Row(Modifier.fillMaxSize()) {
                    // 2/3 — Chat
                    ChatPanel(
                        messages = viewModel.messages.collectAsState().value,
                        isStreaming = viewModel.isStreaming.collectAsState().value,
                        onSend = { input -> viewModel.sendMessage(input) },
                        modifier = Modifier.weight(2f)
                    )

                    // 1/3 — Status
                    StatusPanel(
                        agents = viewModel.agents.collectAsState().value,
                        metrics = viewModel.metrics.collectAsState().value,
                        approvals = viewModel.pendingApprovals.collectAsState().value,
                        onApprove = { id -> viewModel.approve(id) },
                        onReject = { id -> viewModel.reject(id) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
```

**Plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ChatPanel.kt`

```kotlin
@Composable
fun ChatPanel(
    messages: List<UIChatMessage>,
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight().padding(8.dp)) {
        // Lista wiadomosci (scrollowalna)
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = false
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (isStreaming) {
                item { StreamingIndicator() }
            }
        }

        Divider()

        // Input
        var input by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Opisz co chcesz zrobic...") },
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
                enabled = !isStreaming
            ) {
                Text("Wyslij")
            }
        }
    }
}

@Composable
fun MessageBubble(msg: UIChatMessage) {
    val bgColor = when {
        msg.agentColor != null -> msg.agentColor.copy(alpha = 0.1f)
        msg.role == "user" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Naglowek: agent name + timestamp
            if (msg.agentName != null) {
                Row {
                    Text(
                        msg.agentName,
                        style = MaterialTheme.typography.labelMedium,
                        color = msg.agentColor ?: MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatTime(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // Tresc
            SelectionContainer {
                Text(msg.content)
            }

            // Artefakty
            msg.artifacts.forEach { artifact ->
                ArtifactChip(artifact)
            }

            // Approval buttons (inline)
            if (msg.approval != null) {
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { /* approve */ }) { Text("Zatwierdz") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { /* reject */ }) { Text("Odrzuc") }
                }
            }
        }
    }
}
```

### WERYFIKACJA
```bash
./gradlew :cli:run --args="--project /tmp/test-project --mode chat"
# Powinno otworzyc okno Compose z 2-kolumnowym layoutem
```

---

## Etap 6: Interleaved chat i streaming

> **STATUS: ✅ DONE (2026-03-24)**
> UIChatMessage model + ComposeWorkflowListener (Etap 5). ChatMessageMapper z 8 kolorami per-agent, mapuje 10 typow AgentEvent → UIChatMessage. Agent filtering w App.kt (agentFilter StateFlow).

### DLACZEGO
W multi-agent sesji wiadomosci od roznych agentow musza sie przeplatac w jednym strumieniu, oznaczone kolorami.

### CO
1. `UIChatMessage` — model wiadomosci z `agentId`, `agentName`, `agentColor`
2. `ComposeWorkflowListener` — odbiera eventy z core, aktualizuje Compose state
3. Filtrowanie widoku per agent / per typ

### JAK

**Plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/ui/models/UIChatMessage.kt`

```kotlin
data class UIChatMessage(
    val id: String,
    val timestamp: Long,
    val role: String,                // "user", "assistant", "system", "tool", "agent_event"
    val content: String,
    val agentId: String? = null,     // null = user/system
    val agentName: String? = null,
    val agentColor: Color? = null,
    val isStreaming: Boolean = false,
    val artifacts: List<Artifact> = emptyList(),
    val approval: PendingApproval? = null,
    val messageType: MessageType = MessageType.TEXT
)

enum class MessageType {
    TEXT,               // Zwykla wiadomosc
    AGENT_STARTED,      // Agent rozpoczal prace
    AGENT_COMPLETED,    // Agent zakonczyl
    AGENT_FAILED,       // Agent zawiodl
    DATA_EXCHANGE,      // Komunikacja miedzy agentami
    APPROVAL_REQUEST,   // Prosba o zatwierdzenie
    ARTIFACT            // Wyprodukowany artefakt
}
```

**Plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/ComposeWorkflowListener.kt`

```kotlin
class ComposeWorkflowListener(
    private val agentId: String,
    private val agentName: String,
    private val agentColor: Color,
    private val messagesState: MutableStateFlow<List<UIChatMessage>>,
    private val scope: CoroutineScope
) : WorkflowEventListener {

    override fun onStreamChunk(chunk: String) {
        scope.launch {
            messagesState.update { messages ->
                val streamId = "$agentId-stream"
                val existing = messages.indexOfLast { it.id == streamId }
                val msg = UIChatMessage(
                    id = streamId,
                    timestamp = System.currentTimeMillis(),
                    role = "assistant",
                    content = chunk,
                    agentId = agentId,
                    agentName = agentName,
                    agentColor = agentColor,
                    isStreaming = true
                )
                if (existing >= 0) {
                    messages.toMutableList().also { it[existing] = msg }
                } else {
                    messages + msg
                }
            }
        }
    }

    override fun onStreamComplete(content: String) {
        scope.launch {
            messagesState.update { messages ->
                messages.map {
                    if (it.id == "$agentId-stream") it.copy(
                        content = content,
                        isStreaming = false,
                        id = UUID.randomUUID().toString()  // Staly ID po zakonczeniu
                    ) else it
                }
            }
        }
    }
}
```

### WERYFIKACJA
Uruchom chat w trybie Chat, wyslij wiadomosc. Powinna pojawic sie odpowiedz z streaming (tekst pojawia sie token po tokenie).

---

## Etap 7: GlobalMetrics per-agent + FileLockManager

> **STATUS: ✅ DONE (2026-03-23)**
> Zaimplementowano:
> - `AgentMetrics` class z per-agent operation/cancellation/tokens
> - `GlobalMetrics.forAgent(agentId)` z pelna backward compat (delegacja do "default")
> - `FileLockManager` obiekt z Mutex per-sciezka, uzyty w 5 write tools
> - `OllamaRequestGate.maxConcurrentPerEndpoint` (default 1, konfigurowalne)
> Testy: GlobalMetricsMultiAgentTest (10), FileLockManagerTest (5), FileLockManagerIntegrationTest (4), OllamaRequestGateTest (5).

### DLACZEGO
`GlobalMetrics` jest singletonem z jednym `_currentOperation` i jednym `_isCancelled`. Przy wielu agentach rownolegle — jeden nadpisuje drugiego. Potrzebujemy per-agent tracking z backward compatibility.

`FileLockManager` zapobiega sytuacji gdzie 2 agenty jednoczesnie edytuja ten sam plik.

### CO
1. Refactor `GlobalMetrics` — per-agent `AgentMetrics` + backward-compat delegacja
2. Nowy `FileLockManager` — Mutex per-sciezka pliku
3. Dodanie file locking do 5 write tools

### JAK

**Zmiana w:** `src/main/kotlin/pl/jclab/refio/core/services/monitoring/GlobalMetrics.kt`

```kotlin
object GlobalMetrics {
    // ── NOWE: per-agent tracking ──
    private val agentMetrics = ConcurrentHashMap<String, AgentMetrics>()

    fun forAgent(agentId: String): AgentMetrics =
        agentMetrics.getOrPut(agentId) { AgentMetrics(agentId) }

    fun removeAgent(agentId: String) = agentMetrics.remove(agentId)

    /** Wszystkie aktywne agenty — do GUI */
    fun allAgentMetrics(): Map<String, AgentMetrics> = agentMetrics.toMap()

    // ── BACKWARD COMPAT: istniejacy kod dziala bez zmian ──
    // Deleguje do "default" agenta
    fun setCurrentOperation(operation: OperationInfo) = forAgent("default").setCurrentOperation(operation)
    fun clearCurrentOperation() = forAgent("default").clearCurrentOperation()
    val currentOperation: StateFlow<OperationInfo> get() = forAgent("default").currentOperation

    fun requestCancellation() = forAgent("default").requestCancellation()
    fun resetCancellation() = forAgent("default").resetCancellation()
    fun isCancelled(): Boolean = forAgent("default").isCancelled()

    // Istniejace globalne countery — bez zmian
    // ...
}

/** Per-agent metrics — kazdy agent ma swoj stan */
class AgentMetrics(val agentId: String) {
    private val _currentOperation = MutableStateFlow<OperationInfo>(OperationInfo.Idle)
    val currentOperation: StateFlow<OperationInfo> = _currentOperation.asStateFlow()

    private val _isCancelled = AtomicBoolean(false)

    fun setCurrentOperation(op: OperationInfo) { _currentOperation.value = op }
    fun clearCurrentOperation() { _currentOperation.value = OperationInfo.Idle }
    fun requestCancellation() { _isCancelled.set(true) }
    fun resetCancellation() { _isCancelled.set(false) }
    fun isCancelled(): Boolean = _isCancelled.get()
}
```

**Nowy plik:** `src/main/kotlin/pl/jclab/refio/core/tools/FileLockManager.kt`

```kotlin
package pl.jclab.refio.core.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents concurrent writes to the same file by multiple agents.
 * Keyed by normalized absolute path.
 */
object FileLockManager {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val normalizedPath = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString()
        val mutex = locks.getOrPut(normalizedPath) { Mutex() }
        return mutex.withLock { block() }
    }
}
```

**Zmiana w write tools** (CodeEditingTool, CreateNewFileTool, AdvanceCodeEditingTool, MultiEditTool, MultiLineEditorTool):

```kotlin
// PRZED:
override suspend fun execute(params: Map<String, Any>): ToolResult {
    val path = params["file"] as String
    // ... write logic
}

// PO:
override suspend fun execute(params: Map<String, Any>): ToolResult {
    val path = params["file"] as String
    return FileLockManager.withFileLock(path) {
        // ... write logic (bez zmian)
    }
}
```

### WERYFIKACJA
```bash
./gradlew test  # Istniejace testy musza przechodzic (backward compat)
```
Nowe testy:
```kotlin
class GlobalMetricsMultiAgentTest {
    @Test
    fun `per-agent cancellation does not affect other agents`() {
        GlobalMetrics.forAgent("a").resetCancellation()
        GlobalMetrics.forAgent("b").resetCancellation()
        GlobalMetrics.forAgent("a").requestCancellation()
        assertTrue(GlobalMetrics.forAgent("a").isCancelled())
        assertFalse(GlobalMetrics.forAgent("b").isCancelled())
    }

    @Test
    fun `backward compat - default agent`() {
        GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
        assertEquals(OperationInfo.Idle, GlobalMetrics.currentOperation.value)
    }
}
```

---

## Etap 8: AgentEventBus + event system

> **STATUS: ✅ DONE (2026-03-23) — 100%**
> Dokonczono: AgentEventSqlRepository (Gson serialization, 12 typow eventow), agent_instance_id w ChatMessagesTable + ChatMessageRepository.findByAgentInstanceId().
> Zaimplementowano:
> - `AgentEvent` sealed interface z 12 typami eventow + `Artifact` data class
> - `AgentEventBus` z SharedFlow (replay=200, buffer=500), 6 metod filtrujacych
> - `AgentEventHandler` z CompletableDeferred dla requestData/requestApproval + auto-approve timeout
> - 3 tabele DB: `agent_sessions`, `agent_instances`, `agent_events` z indeksami
> - `AgentSessionRepository` i `AgentInstanceRepository` z CRUD
> - `AgentEventRepository` interface zdefiniowany
> **Brak (drobne):**
> - Konkretna implementacja `AgentEventRepository` (klasa SQL persystujaca eventy) — interface jest, brak impl
> - `agent_instance_id` kolumna w `ChatMessagesTable` — eventy sa w osobnej tabeli, ale brak dedykowanej kolumny do filtrowania wiadomosci per agent
> Testy: AgentEventBusTest (8), AgentEventTest (15), AgentEventHandlerTest (10), AgentSessionRepositoryTest (5), AgentInstanceRepositoryTest (6).

### DLACZEGO
Agenty musza moc: informowac o starcie/zakonczeniu, prosic o dane od siebie nawzajem, prosic uzytkownika o zatwierdzenie, spawnowac nowych agentow. Potrzebujemy centralnego event bus z persystencja.

### CO
1. `AgentEvent` sealed interface (12 typow eventow)
2. `AgentEventBus` (SharedFlow z replay)
3. 3 nowe tabele DB: `agent_sessions`, `agent_instances`, `agent_events`
4. `agent_instance_id` kolumna w `chat_messages`
5. `AgentEventHandler` per-agent — obsługa DataRequest/Response z CompletableDeferred

### JAK

**Nowy plik:** `src/main/kotlin/pl/jclab/refio/core/agents/events/AgentEvent.kt`

```kotlin
package pl.jclab.refio.core.agents.events

sealed interface AgentEvent {
    val id: String
    val sessionId: String
    val sourceAgentId: String
    val timestamp: Long
    val correlationId: String

    // LIFECYCLE
    data class AgentStarted(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val agentName: String, val profile: String?, val task: String,
        val model: String?, val dependsOn: List<String>
    ) : AgentEvent

    data class AgentCompleted(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val summary: String, val artifacts: List<Artifact>,
        val tokensUsed: Long, val costUsd: Double, val durationMs: Long
    ) : AgentEvent

    data class AgentFailed(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val error: String, val recoverable: Boolean
    ) : AgentEvent

    // DATA EXCHANGE
    data class DataRequest(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val targetAgentId: String?, val query: String,
        val context: Map<String, String> = emptyMap()
    ) : AgentEvent

    data class DataResponse(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val targetAgentId: String, val requestId: String,
        val response: String, val artifacts: List<Artifact> = emptyList()
    ) : AgentEvent

    // COORDINATION
    data class ArtifactProduced(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val artifact: Artifact
    ) : AgentEvent

    data class SpawnAgentRequest(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val requestedProfile: String, val task: String
    ) : AgentEvent

    data class AgentSpawned(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val spawnedAgentId: String, val requestId: String
    ) : AgentEvent

    // APPROVAL
    data class ApprovalRequired(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val action: String, val actionType: String,
        val risk: String, val details: Map<String, String>
    ) : AgentEvent

    data class ApprovalDecision(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val approvalId: String, val approved: Boolean, val reason: String?
    ) : AgentEvent

    // PROGRESS (do GUI)
    data class ProgressUpdate(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val phase: String, val message: String, val progress: Float?
    ) : AgentEvent

    data class StreamChunk(
        override val id: String, override val sessionId: String,
        override val sourceAgentId: String, override val timestamp: Long,
        override val correlationId: String,
        val delta: String, val accumulated: String, val isComplete: Boolean
    ) : AgentEvent
}

data class Artifact(
    val type: String,    // FILE_CREATED, ANALYSIS, SPECIFICATION, CODE_REVIEW...
    val name: String,
    val content: String? = null,
    val path: String? = null
)
```

**Nowy plik:** `src/main/kotlin/pl/jclab/refio/core/agents/events/AgentEventBus.kt`

```kotlin
package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow

class AgentEventBus {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 200,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun emit(event: AgentEvent) {
        _events.emit(event)
    }

    /** Eventy danej sesji multi-agent */
    fun sessionEvents(sessionId: String): Flow<AgentEvent> =
        events.filter { it.sessionId == sessionId }

    /** Eventy danego agenta */
    fun agentEvents(agentId: String): Flow<AgentEvent> =
        events.filter { it.sourceAgentId == agentId }

    /** Lifecycle eventy (do DAG visualization) */
    fun lifecycleEvents(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.AgentStarted || it is AgentEvent.AgentCompleted ||
                it is AgentEvent.AgentFailed || it is AgentEvent.AgentSpawned
            )
        }

    /** Stream chunks + lifecycle (do interleaved chat) */
    fun chatStream(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.StreamChunk || it is AgentEvent.AgentStarted ||
                it is AgentEvent.AgentCompleted || it is AgentEvent.AgentFailed ||
                it is AgentEvent.ArtifactProduced || it is AgentEvent.ApprovalRequired ||
                it is AgentEvent.DataRequest || it is AgentEvent.DataResponse
            )
        }

    /** Pending approvals */
    fun approvalEvents(sessionId: String): Flow<AgentEvent.ApprovalRequired> =
        events.filterIsInstance<AgentEvent.ApprovalRequired>()
            .filter { it.sessionId == sessionId }
}
```

### WERYFIKACJA
```kotlin
class AgentEventBusTest {
    @Test
    fun `should filter events by session`() = runTest {
        val bus = AgentEventBus()
        val collected = mutableListOf<AgentEvent>()

        val job = launch { bus.sessionEvents("s1").take(1).toList(collected) }
        bus.emit(AgentEvent.ProgressUpdate("1","s2","a1",0,"c1","p","msg",null)) // wrong session
        bus.emit(AgentEvent.ProgressUpdate("2","s1","a1",0,"c1","p","msg",null)) // right session
        job.join()

        assertEquals(1, collected.size)
        assertEquals("s1", collected[0].sessionId)
    }
}
```

---

## Etap 9: MultiAgentRunner + orchestrator

> **STATUS: ✅ DONE (2026-03-23)**
> Zaimplementowano:
> - `MultiAgentRunner` z parallel execution w `supervisorScope`, DAG dependency resolution, event emission, per-agent metrics
> - `AgentSpec` + `AgentResult` data classes
> - `MultiAgentTaskParser` — YAML parser z KAML (agenci, walidacja, scoring)
> - Runner przyjmuje `executor` lambda — decouplowany od CoreApiRouter
> **Brak:** Bezposrednia integracja z CoreApiRouter (endpoint API do uruchomienia multi-agent session). Runner jest gotowy, ale nie ma jeszcze metody w routerze ktora go wywoluje.
> Testy: MultiAgentRunnerTest (9 — single/multi agent, failures, DAG, diamond deps, events), MultiAgentTaskParserTest (12 — valid/invalid YAML, edge cases).

### DLACZEGO
Potrzebujemy sposobu uruchomienia N agentow rownolegle, z respektowaniem zaleznosci (agent B czeka na agenta A), z event emission i user approval flow.

### CO
`MultiAgentRunner` — uruchamia agentow wg specyfikacji YAML, zarzadza lifecycle, emituje eventy.

### JAK

**Nowy plik:** `src/main/kotlin/pl/jclab/refio/core/agents/MultiAgentRunner.kt`

```kotlin
package pl.jclab.refio.core.agents

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pl.jclab.refio.core.agents.events.*
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.db.TaskMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AgentSpec(
    val name: String,
    val profile: String? = null,
    val task: String,
    val mode: TaskMode = TaskMode.AGENT,
    val model: String? = null,
    val dependsOn: List<String> = emptyList()
)

data class AgentResult(
    val agentName: String,
    val success: Boolean,
    val response: String,
    val tokensUsed: Long = 0,
    val costUsd: Double = 0.0,
    val durationMs: Long = 0,
    val error: String? = null
)

class MultiAgentRunner(
    private val coreRouter: CoreApiRouter,
    private val eventBus: AgentEventBus
) {
    suspend fun run(
        sessionId: String,
        specs: List<AgentSpec>,
        scope: CoroutineScope
    ): Map<String, AgentResult> {
        val results = ConcurrentHashMap<String, AgentResult>()
        val completedAgents = MutableStateFlow(setOf<String>())

        supervisorScope {
            for (spec in specs) {
                launch {
                    // Czekaj na zaleznosci
                    if (spec.dependsOn.isNotEmpty()) {
                        completedAgents.first { completed ->
                            spec.dependsOn.all { it in completed }
                        }
                    }

                    val agentId = "${spec.name}-${UUID.randomUUID().toString().take(8)}"
                    val startTime = System.currentTimeMillis()

                    // Emituj start
                    eventBus.emit(AgentEvent.AgentStarted(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        sourceAgentId = agentId,
                        timestamp = startTime,
                        correlationId = sessionId,
                        agentName = spec.name,
                        profile = spec.profile,
                        task = spec.task,
                        model = spec.model,
                        dependsOn = spec.dependsOn
                    ))

                    try {
                        // Stworz dedykowany task w DB
                        val task = coreRouter.taskRouter.createTask(
                            name = "agent:${spec.name}",
                            mode = spec.mode,
                            projectId = coreRouter.projectId
                        )

                        // Uruchom turn loop na tym tasku
                        // (uzyj istniejacego AgentTurnLoop — kazdy agent = osobny task)
                        val turnResult = coreRouter.executeTurn(
                            taskId = task.id,
                            input = spec.task,
                            mode = spec.mode,
                            model = spec.model,
                            // Subagent profile overrides
                            profileOverrides = spec.profile?.let {
                                coreRouter.buildProfileOverrides(it)
                            },
                            streamCallback = { chunk ->
                                scope.launch {
                                    eventBus.emit(AgentEvent.StreamChunk(
                                        id = UUID.randomUUID().toString(),
                                        sessionId = sessionId,
                                        sourceAgentId = agentId,
                                        timestamp = System.currentTimeMillis(),
                                        correlationId = sessionId,
                                        delta = chunk.delta,
                                        accumulated = chunk.accumulated,
                                        isComplete = chunk.isComplete
                                    ))
                                }
                            }
                        )

                        val duration = System.currentTimeMillis() - startTime
                        val result = AgentResult(
                            agentName = spec.name,
                            success = turnResult.success,
                            response = turnResult.response,
                            tokensUsed = (turnResult.tokensIn + turnResult.tokensOut).toLong(),
                            costUsd = turnResult.cost,
                            durationMs = duration
                        )
                        results[spec.name] = result

                        eventBus.emit(AgentEvent.AgentCompleted(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            sourceAgentId = agentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = sessionId,
                            summary = turnResult.response.take(200),
                            artifacts = emptyList(),
                            tokensUsed = result.tokensUsed,
                            costUsd = result.costUsd,
                            durationMs = duration
                        ))
                    } catch (e: Exception) {
                        results[spec.name] = AgentResult(
                            agentName = spec.name, success = false,
                            response = "", error = e.message
                        )
                        eventBus.emit(AgentEvent.AgentFailed(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            sourceAgentId = agentId,
                            timestamp = System.currentTimeMillis(),
                            correlationId = sessionId,
                            error = e.message ?: "Unknown error",
                            recoverable = true
                        ))
                    }

                    completedAgents.update { it + spec.name }
                }
            }
        }

        return results
    }
}
```

### WERYFIKACJA
```kotlin
class MultiAgentRunnerTest {
    @Test
    fun `should respect dependencies`() = runTest {
        val events = mutableListOf<AgentEvent>()
        val bus = AgentEventBus()
        launch { bus.events.take(4).toList(events) } // 2x Started + 2x Completed

        val specs = listOf(
            AgentSpec("analyst", task = "Analyze"),
            AgentSpec("coder", task = "Code", dependsOn = listOf("analyst"))
        )

        // ... run with mock coreRouter

        // Verify: analyst started before coder
        val starts = events.filterIsInstance<AgentEvent.AgentStarted>()
        assertTrue(starts[0].agentName == "analyst")
        assertTrue(starts[1].agentName == "coder")
    }
}
```

---

## Etap 10: GUI multi-agent — DAG, approvals, interleaved chat

> **STATUS: ✅ DONE (2026-03-24)**
> Zaimplementowano: AgentFlowPanel (DAG z listą agentów i dependencies), AgentNode (status icons [>+x?~.], kolorowe tła, progress bar, cost per agent), ApprovalPanel (approve/reject per action z risk level), MetricsCard (tokens/cost/agents/duration).
> RefioViewModel subskrybuje AgentEventBus: aktualizuje agents StateFlow, metrics, pendingApprovals. Approve/reject emituje ApprovalDecision do event bus.

### DLACZEGO
Uzytkownik musi widziec: ktory agent co robi, jak plyna dane miedzy nimi, co wymaga zatwierdzenia — wszystko w jednym oknie.

### CO
1. `AgentFlowPanel` — wizualizacja DAG (graph agentow z kolorami i strzalkami)
2. `ApprovalPanel` — kolejka zatwierdzen z "Zaufaj agentowi"
3. Chat z filtrami per agent i kolorowymi labelami
4. Metryki agregowane (tokens, cost, time per session)

### JAK

**Plik:** `cli/src/main/kotlin/pl/jclab/refio/cli/ui/StatusPanel.kt`

```kotlin
@Composable
fun StatusPanel(
    agents: List<AgentState>,
    metrics: SessionMetrics,
    approvals: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight().padding(8.dp)) {
        // Sekcja 1: Agent Flow (DAG)
        Text("Agenci", style = MaterialTheme.typography.titleMedium)
        AgentFlowPanel(agents, Modifier.weight(1f))

        Divider(Modifier.padding(vertical = 8.dp))

        // Sekcja 2: Zatwierdzenia
        if (approvals.isNotEmpty()) {
            Text("Zatwierdzenia (${approvals.size})", style = MaterialTheme.typography.titleMedium)
            ApprovalPanel(approvals, onApprove, onReject, Modifier.weight(1f))
            Divider(Modifier.padding(vertical = 8.dp))
        }

        // Sekcja 3: Metryki
        MetricsCard(metrics)
    }
}

@Composable
fun AgentFlowPanel(agents: List<AgentState>, modifier: Modifier) {
    LazyColumn(modifier) {
        items(agents) { agent ->
            AgentNode(agent)
            // Strzalka do nastepnego jezeli sa zaleznosci
            if (agent.dependsOn.isNotEmpty()) {
                Text("  ↑ czeka na: ${agent.dependsOn.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray)
            }
        }
    }
}

@Composable
fun AgentNode(agent: AgentState) {
    val (icon, color) = when (agent.status) {
        "RUNNING" -> "🟢" to Color.Green
        "COMPLETED" -> "✅" to Color.Blue
        "FAILED" -> "❌" to Color.Red
        "WAITING_APPROVAL" -> "⚠️" to Color(0xFFFF9800)
        "WAITING_DATA" -> "💬" to Color.Yellow
        "PENDING" -> "⏳" to Color.Gray
        else -> "○" to Color.Gray
    }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.name, fontWeight = FontWeight.Bold)
                if (agent.currentPhase != null) {
                    Text(agent.currentPhase, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (agent.costUsd > 0) {
                Text("$${"%.3f".format(agent.costUsd)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (agent.status == "RUNNING") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ApprovalPanel(
    approvals: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier) {
        items(approvals) { approval ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("${approval.agentName}: ${approval.action}", fontWeight = FontWeight.Bold)
                    approval.details.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        Button(onClick = { onApprove(approval.id) }, Modifier.weight(1f)) { Text("Zatwierdz") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = { onReject(approval.id) }, Modifier.weight(1f)) { Text("Odrzuc") }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricsCard(metrics: SessionMetrics) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text("Metryki sesji", style = MaterialTheme.typography.titleSmall)
            Text("Tokeny: ${metrics.totalTokens}")
            Text("Koszt: $${"%.4f".format(metrics.totalCostUsd)}")
            Text("Czas: ${metrics.totalDurationMs / 1000}s")
            Text("Agenci: ${metrics.completedAgents}/${metrics.totalAgents}")
        }
    }
}
```

### WERYFIKACJA
Uruchom multi-agent sesje z 2-3 agentami. Sprawdz:
- Agenci pokazuja sie w panelu bocznym z kolorowymi statusami
- Wiadomosci od roznych agentow przeplataja sie w chacie z kolorowymi labelami
- Zatwierdzenia pojawiaja sie w panelu i blokuja agenta do decyzji
- Metryki sumuja sie poprawnie

---

## Etap 11: TUI — Terminal User Interface (zastepuje Compose Desktop GUI)

> **STATUS: 🔲 TODO**
> Obecna wersja CLI otwiera okno Compose Desktop (Swing/AWT) — to jest **bledne podejscie**. CLI musi rysowac GUI w terminalu (TUI). Compose Desktop GUI zostaje **usuniety** — jedynym GUI w module `:cli` bedzie TUI. Interfejs TUI ma **odwzorowywac pelen wyglad plugina IntelliJ**: 7 zakladek (Chat, Steps, Context, RAG, Logs, Debug, API Logs), panel ustawien (11 zakladek), historie sesji, status bar, autocomplete — wszystko w terminalu.

### DLACZEGO

1. **CLI = terminal, nie okno** — użytkownik wpisuje `refio -p .` w terminalu i oczekuje, że GUI zostanie w tym terminalu, a nie otworzy się osobne okno desktopowe. Obecna implementacja uruchamia Compose Desktop (okienkowy Swing), co jest nieintuicyjne dla narzędzia CLI.
2. **Serwery bez GUI** — na serwerach CI/CD, SSH, kontenerach Docker nie ma display serwera. Compose Desktop wymaga `DISPLAY` lub Windows Desktop — TUI działa wszędzie gdzie jest terminal.
3. **Spójność z innymi narzędziami CLI** — narzędzia jak `htop`, `lazygit`, `k9s`, `claude` (Claude Code) rysują GUI w terminalu. Użytkownicy oczekują tego samego od Refio CLI.
4. **Kurs AI i demo** — łatwiej pokazać TUI w nagraniu terminala niż okno desktopowe.
5. **Parzytosc z pluginem IntelliJ** — TUI ma oferowac te same funkcjonalnosci co plugin: 7 zakladek, ustawienia, historia, debug. Uzytkownik przechodzacy z IntelliJ na CLI nie traci zadnych funkcji.

### CO — USUWANIE COMPOSE DESKTOP

**Pliki do USUNIECIA (7 plikow z Etapu 5):**

| Plik | Powod usuniecia |
|------|----------------|
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/App.kt` | Compose Desktop Window — zastapione przez TuiApp |
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ChatPanel.kt` | Compose LazyColumn — zastapione przez TuiChatView |
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/StatusPanel.kt` | Compose Material3 — zastapione przez TuiStatusPanel |
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/RefioViewModel.kt` | Zalezy od `androidx.compose.ui.graphics.Color` — TuiViewModel uzywa ANSI |
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ComposeWorkflowListener.kt` | Compose-specific — zastapione przez TuiWorkflowListener |
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/ChatMessageMapper.kt` | Compose Color mapping — zastapione przez TuiColors |
| `cli/src/main/kotlin/pl/jclab/refio/cli/ui/UIChatMessage.kt` | Moze zostac (czyste data class) lub przeniesc do `tui/` |

**Pliki testowe do USUNIECIA (4 pliki z Etapu 5/6):**

| Plik | Powod |
|------|-------|
| `cli/src/test/kotlin/pl/jclab/refio/cli/ui/AppTest.kt` | Testy Compose UI |
| `cli/src/test/kotlin/pl/jclab/refio/cli/ui/ChatPanelTest.kt` | Testy Compose UI |
| `cli/src/test/kotlin/pl/jclab/refio/cli/ui/StatusPanelTest.kt` | Testy Compose UI |
| (ChatMessageMapper testy w AppTest) | Compose-specific |

**Zmiany w `cli/build.gradle.kts` — usunac Compose:**

```kotlin
// USUNAC te linie:
plugins {
    // id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"  ← USUNAC
    // id("org.jetbrains.compose") version "1.7.3"                 ← USUNAC
}

dependencies {
    // USUNAC:
    // implementation(compose.desktop.currentOs)
    // implementation(compose.material3)
    // testImplementation(compose.desktop.uiTestJUnit4)
    // testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")

    // DODAC:
    implementation("com.github.ajalt.mordant:mordant:3.0.1")
    implementation("org.jline:jline:3.26.3")
}
```

**Zmiana w `main.kt` — usunac `--gui` flag:**

```kotlin
class RefioCommand : CliktCommand(name = "refio") {
    // ... istniejace opcje (--project, --mode, --model, --headless, --prompt, --multi-agent, --no-egress) ...
    // BRAK --gui — Compose Desktop USUNIETY

    override fun run() {
        when {
            headless && multiAgent != null -> runMultiAgent()
            headless && prompt != null -> runHeadless()
            headless -> echo("Error: --headless requires --prompt or --multi-agent", err = true)
            else -> launchTuiApp(project, mode, model, noEgress) // TUI — jedyne GUI
        }
    }
}
```

### CO — TUI ODWZOROWUJACY PLUGIN INTELLIJ

TUI ma byc **pelnym odpowiednikiem plugina IntelliJ** — kazdy panel, zakladka, dialog z plugina musi miec swoj odpowiednik w TUI.

#### Hierarchia UI plugina IntelliJ → mapowanie na TUI

```
RefioMainPanel (IntelliJ Tool Window)           →  TuiApp (pelny ekran terminala)
├── CardLayout middlePanel                       →  TuiScreenManager (przelaczanie ekranow)
│   ├── normalContentPanel (default)             →  TuiMainScreen
│   │   └── JTabbedPane (7 zakladek)            →  Tab bar (F1-F7 lub Ctrl+1..7)
│   │       ├── [1] Chat                         →  TuiChatView
│   │       │   ├── ChatView (wiadomosci)        →    TuiMessageList (scrollowana lista)
│   │       │   │   ├── UserBubble               →      [USER] kolorowana sekcja
│   │       │   │   ├── AssistantBubble          →      [ASSISTANT] z markdown rendering
│   │       │   │   ├── ToolBubble               →      [TOOL] nazwa + parametry + wynik
│   │       │   │   └── MetricsView              →      metryki inline (model, tokens, cost)
│   │       │   └── PromptInputPanel             →    TuiPromptInput (dolna czesc ekranu)
│   │       │       ├── Mode selector            →      [M:Chat/Plan/Agent] przelacznik
│   │       │       ├── Model selector           →      [model:gpt-4o] przelacznik
│   │       │       ├── Execution mode toggle    →      [Auto⚡/Interactive🤚]
│   │       │       ├── Thinking toggle          →      [🧠 on/off]
│   │       │       ├── No-egress toggle         →      [🌐 on/off]
│   │       │       ├── Autocomplete (@mentions) →      JLine3 completer: @file, @codebase, etc.
│   │       │       └── Snippets container       →      Ctrl+J → wklej snippet
│   │       ├── [2] Steps                        →  TuiStepsView
│   │       │   ├── Step list (collapsible)      →    Lista z expand/collapse (Enter)
│   │       │   ├── Status badges                →    [NEW] [PENDING] [RUNNING] [OK] [FAIL]
│   │       │   ├── Step details                 →    Tool name, params, result, metrics
│   │       │   └── Controls (Resume/Replan/Cancel) → Komendy: /resume, /replan, /cancel
│   │       ├── [3] Context                      →  TuiContextView
│   │       │   ├── Collapsible sections         →    Sekcje z kolorami (fold/unfold)
│   │       │   ├── Token usage per section      →    [████░░░░ 1.2K/3K tokens]
│   │       │   └── TokenUsageVisualizationPanel →    Pasek tokenow na gorze
│   │       ├── [4] RAG                          →  TuiRagView
│   │       │   ├── Indexed files table          →    Tabela: path | chunks | status
│   │       │   ├── Statistics                   →    Total files, chunks, embeddings
│   │       │   ├── Search UI                    →    /rag-search <query>
│   │       │   └── Controls (Refresh/Chunks)    →    /rag-refresh, /rag-chunks <file>
│   │       ├── [5] Logs                         →  TuiLogsView
│   │       │   ├── Log table (timestamp, level, msg) → Scrollowana tabela z kolorami
│   │       │   ├── Color by level               →    DEBUG=gray, INFO=white, WARN=yellow, ERROR=red
│   │       │   └── Controls (Refresh/Copy)      →    /logs-refresh, /logs-copy
│   │       ├── [6] Debug                        →  TuiDebugView
│   │       │   ├── Session state                →    ID, Mode, Model, Status, Tokens, Cost
│   │       │   ├── Core health                  →    Connection, URL, Latency, Health
│   │       │   ├── Project info                 →    Root, DB path, Last update
│   │       │   ├── LLM Statistics               →    API calls, Tokens, Cost, Avg latency
│   │       │   └── Recent API logs              →    Ostatnie 10 wywolan
│   │       └── [7] API Logs                     →  TuiApiLogsView
│   │           ├── Statistics summary           →    Total calls, cost, tokens, latency
│   │           ├── Filters (provider/model)     →    /api-filter provider=anthropic
│   │           ├── Logs table                   →    Timestamp | Provider | Model | Tokens | Cost
│   │           └── Export (CSV/JSON)            →    /api-export csv, /api-export json
│   ├── HistoryPanel                             →  TuiHistoryScreen
│   │   ├── Search field                         →    /history <search>
│   │   ├── Filter tabs (All/Chat/Plan/Agent)    →    /history --mode chat
│   │   ├── Session cards                        →    Lista: name | mode | status | messages | date
│   │   └── Load/Delete/Pin                      →    /history-load <id>, /history-delete <id>
│   └── SettingsView (11 zakladek)               →  TuiSettingsScreen
│       ├── [1] General                          →    Format markdown, Streaming, Advanced view
│       ├── [2] Providers                        →    Provider cards z API key, URL, test connection
│       ├── [3] Models                           →    Model presets, add/edit/delete
│       ├── [4] Prompts                          →    System prompts + slash commands
│       ├── [5] Context                          →    RAG settings, ignore paths, chunk size
│       ├── [6] MCP                              →    MCP servers: add/remove/configure/test
│       ├── [7] Docs                             →    Documentation URLs, indexing status
│       ├── [8] Tools                            →    Tool permissions per mode
│       ├── [9] Subagents                        →    Subagent list: name, model, tools, enabled
│       ├── [10] Advanced                        →    Security, limits, performance
│       └── [11] Theme                           →    TUI colors preview (ANSI paleta)
└── StatusBar (bottom)                           →  TuiStatusBar (ostatnia linia terminala)
    ├── Session status                           →    [CHAT|gpt-4o] lub [AGENT|claude-3.5]
    ├── Progress bar                             →    [████░░░░ 45%] lub spinner
    ├── Cost/tokens                              →    [$0.12 | 4.2K tokens]
    └── Stop button                              →    Ctrl+C = cancel operation
```

### WYBOR BIBLIOTEKI TUI

| Biblioteka | Zalety | Wady | Rekomendacja |
|------------|--------|------|--------------|
| **[Mordant](https://github.com/ajalt/mordant)** | Ten sam autor co Clikt (juz uzywany), Kotlin-native, rich text + layouts + animations, aktywnie rozwijana, lekka (~200KB) | Mniej zaawansowane widgety niz Lanterna (brak wbudowanego TextBox) | **✅ Preferowana** — spójna z Clikt, wystarczajaca funkcjonalnosc |
| **[Lanterna](https://github.com/mabe02/lanterna)** | Pełny terminal GUI framework (TextBox, Panel, Window), dojrzała (10+ lat) | Java-centric API, cięższa (~500KB), mniej kotlinowa | Dobra alternatywa jeśli Mordant nie wystarczy |
| **[Mosaic](https://github.com/JakeWharton/mosaic)** | Compose-like API (React w terminalu), reactive | Wczesna faza, mała społeczność, eksperymentalna | Nie rekomendowana (ryzyko) |

**Rekomendacja: Mordant + JLine3** — z tych powodów:
- Clikt (juz uzywany) jest tego samego autora → spójne API, testowane razem
- Mordant 3.x ma `Terminal`, `Widget`, `Layout`, `Animation` — wystarczające do TUI
- Wsparcie ANSI, truecolor, hyperlinks, markdown rendering
- JLine3 daje raw terminal input (strzalki, Ctrl+kombinacje, autocomplete, tab completion)
- CrossPlatform: Windows Terminal (ConPTY/JAnsi), macOS Terminal, iTerm2, Linux TTY

### JAK

#### 11.1 Nowe pliki TUI — pelna struktura

```
src/main/kotlin/pl/jclab/refio/cli/tui/
├── TuiApp.kt                  # Entry point: launchTuiApp(), glowna petla, lifecycle
├── TuiScreenManager.kt        # Przelaczanie ekranow: Main/History/Settings (jak CardLayout)
├── TuiTabBar.kt               # Pasek zakladek: [Chat][Steps][Context][RAG][Logs][Debug][API] + nawigacja F1-F7
│
├── views/                     # 7 zakladek — odpowiedniki paneli IntelliJ
│   ├── TuiChatView.kt         # Chat: wiadomosci + prompt input (odpowiednik ChatView + PromptInputPanel)
│   ├── TuiStepsView.kt        # Steps: lista subtaskow z expand/collapse (odpowiednik StepsQueueView)
│   ├── TuiContextView.kt      # Context: sekcje kontekstu z tokenami (odpowiednik ContextPanel)
│   ├── TuiRagView.kt          # RAG: indexed files, search, stats (odpowiednik RagViewPanel)
│   ├── TuiLogsView.kt         # Logs: tabela logow z kolorami (odpowiednik LogsPanel)
│   ├── TuiDebugView.kt        # Debug: session/core/LLM state (odpowiednik DebugPanel)
│   └── TuiApiLogsView.kt      # API Logs: tabela + statystyki + filtry (odpowiednik ApiLogsPanel)
│
├── screens/                   # Ekrany nakladane (jak CardLayout w IntelliJ)
│   ├── TuiHistoryScreen.kt    # Historia sesji: search, filter, load/delete (odpowiednik HistoryPanel)
│   └── TuiSettingsScreen.kt   # Ustawienia: 11 podstron (odpowiednik SettingsView)
│
├── components/                # Reusable TUI components
│   ├── TuiStatusBar.kt        # Status bar: mode, model, progress, cost (odpowiednik StatusBar)
│   ├── TuiMessageBubble.kt    # Wiadomosc chat: role, content, metrics, agent color
│   ├── TuiPromptInput.kt      # Prompt input z autocomplete @mentions, mode/model selectors
│   ├── TuiTable.kt            # Generyczna tabela z kolumnami i scrollowaniem
│   ├── TuiCollapsible.kt      # Collapsible section (expand/fold jak w IntelliJ)
│   ├── TuiProgressBar.kt      # Pasek postepu [████░░░░ 45%]
│   ├── TuiSpinner.kt          # Animated spinner dla operacji async
│   ├── TuiDialog.kt           # Modal dialog: confirm, input, details
│   └── TuiAutocomplete.kt     # Autocomplete popup: @file, @codebase, /commands, !subagents
│
├── input/                     # Input handling
│   ├── TuiInputHandler.kt     # JLine3 wrapper: raw mode, key events, readline
│   ├── TuiKeybindings.kt      # Mapowanie klawiszy: F1-F7 tabs, Ctrl+S settings, Ctrl+H history
│   └── TuiCompleter.kt        # JLine3 Completer: @mentions, /commands, !subagents
│
├── rendering/                 # Rendering engine
│   ├── TuiRenderer.kt         # Mordant Terminal wrapper, full-screen layout, regions
│   ├── TuiLayout.kt           # Layout engine: split panels, regions, resize
│   ├── TuiColors.kt           # ANSI color palette (odpowiednik LCATheme)
│   └── TuiMarkdown.kt         # Mordant markdown rendering (odpowiednik MarkdownRenderingService)
│
└── state/                     # State management
    ├── TuiViewModel.kt        # Glowny ViewModel: laczy core z TUI state
    ├── TuiState.kt            # Unified state: messages, agents, metrics, tabs, scroll offsets
    └── TuiWorkflowListener.kt # WorkflowEventListener impl dla TUI (streaming, errors)
```

**Lacznie: ~25 nowych plikow TUI** (zastepuje 7 plikow Compose)

#### 11.2 Zmiana flag CLI w `main.kt`

```kotlin
class RefioCommand : CliktCommand(name = "refio") {
    val project by option("--project", "-p", help = "Path to project directory")
        .path(mustExist = true, canBeFile = false)
        .default(Path.of("."))
    val mode by option("--mode", "-m", help = "Task mode: CHAT, PLAN, AGENT")
        .enum<TaskMode>()
        .default(TaskMode.CHAT)
    val model by option("--model", help = "LLM model override")
    val headless by option("--headless", help = "Run without GUI (stdout only)").flag()
    val prompt by option("--prompt", help = "Prompt for headless mode")
    val multiAgent by option("--multi-agent", help = "Multi-agent YAML definition file")
        .path(mustExist = true, canBeDir = false)
    val noEgress by option("--no-egress", help = "Block cloud LLM providers").flag()
    // BRAK --gui — Compose Desktop USUNIETY

    override fun run() {
        when {
            headless && multiAgent != null -> runMultiAgent()
            headless && prompt != null -> runHeadless()
            headless -> echo("Error: --headless requires --prompt or --multi-agent", err = true)
            else -> launchTuiApp(project, mode, model, noEgress)  // TUI — jedyne GUI
        }
    }
}
```

#### 11.3 Zmiany w `cli/build.gradle.kts`

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    // USUNAC: id("org.jetbrains.kotlin.plugin.compose")
    // USUNAC: id("org.jetbrains.compose")
    application
}

dependencies {
    // Core module (pure Kotlin/JVM, no IntelliJ)
    implementation(project(":core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.1")

    // USUNAC:
    // implementation(compose.desktop.currentOs)
    // implementation(compose.material3)

    // DODAC — TUI rendering (ten sam autor co Clikt)
    implementation("com.github.ajalt.mordant:mordant:3.0.1")

    // DODAC — Raw terminal input (key events, arrow keys, Ctrl+combinations, autocomplete)
    implementation("org.jline:jline:3.26.3")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // USUNAC:
    // testImplementation(compose.desktop.uiTestJUnit4)
    // testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}
```

#### 11.4 TuiApp.kt — entry point

```kotlin
package pl.jclab.refio.cli.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.*
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.cli.StandaloneCoreBootstrap
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.rendering.TuiRenderer
import pl.jclab.refio.cli.tui.state.TuiViewModel
import java.nio.file.Path

fun launchTuiApp(projectPath: Path, mode: TaskMode, model: String?, noEgress: Boolean) {
    val terminal = Terminal()
    val renderer = TuiRenderer(terminal)
    val viewModel = TuiViewModel(projectPath, mode, model, noEgress)
    val inputHandler = TuiInputHandler(terminal)

    runBlocking {
        renderer.showLoading("Initializing Refio...")
        viewModel.initialize()
        renderer.enterFullScreen()

        // Render loop: re-render on state change
        val renderJob = launch {
            viewModel.stateFlow.collect { state ->
                renderer.render(state)
            }
        }

        // Input loop: raw key events via JLine3
        val inputJob = launch(Dispatchers.IO) {
            inputHandler.startInputLoop(viewModel)
        }

        joinAll(renderJob, inputJob)
        renderer.exitFullScreen()
    }
}
```

#### 11.5 TuiKeybindings — nawigacja

```kotlin
package pl.jclab.refio.cli.tui.input

/**
 * Globalne skroty klawiszowe — odpowiedniki akcji w IntelliJ UI.
 *
 * NAWIGACJA ZAKLADEK:
 *   F1 / Ctrl+1  → Chat
 *   F2 / Ctrl+2  → Steps
 *   F3 / Ctrl+3  → Context
 *   F4 / Ctrl+4  → RAG
 *   F5 / Ctrl+5  → Logs
 *   F6 / Ctrl+6  → Debug
 *   F7 / Ctrl+7  → API Logs
 *
 * EKRANY:
 *   Ctrl+S       → Settings (odpowiednik Settings button w tytule okna)
 *   Ctrl+H       → History (odpowiednik History button w tytule okna)
 *   Escape       → Back to Chat (z Settings/History)
 *
 * W ZAKLADCE CHAT:
 *   Enter         → Wyslij wiadomosc
 *   Shift+Enter   → Nowa linia w input
 *   Tab           → Autocomplete (@mentions, /commands, !subagents)
 *   Ctrl+J        → Wklej snippet
 *   Ctrl+M        → Przelacz mode (Chat→Plan→Agent)
 *   Ctrl+T        → Przelacz thinking mode
 *   Ctrl+E        → Przelacz execution mode (Auto/Interactive)
 *   Arrow Up/Down → Scroll wiadomosci
 *   Page Up/Down  → Szybki scroll
 *
 * W ZAKLADCE STEPS:
 *   Enter         → Expand/collapse step
 *   R             → Resume
 *   P             → Re-plan
 *   C             → Cancel all
 *   A             → Add step
 *
 * W DOWOLNEJ ZAKLADCE:
 *   Ctrl+C        → Cancel biezaca operacje LLM
 *   Ctrl+Q        → Quit (z potwierdzeniem)
 *   Ctrl+N        → Nowa sesja
 *   ?             → Help (lista skrotow)
 */
object TuiKeybindings {
    // ... mapowanie key codes na akcje
}
```

#### 11.6 TuiColors — odpowiednik LCATheme

```kotlin
package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*

/**
 * Paleta kolorow TUI — odpowiednik LCATheme z plugina IntelliJ.
 * Mapowanie Swing Color → ANSI TextColors.
 */
object TuiColors {
    // === Kolory rol wiadomosci (odpowiednik ChatView bubble colors) ===
    val user = brightGreen
    val assistant = brightCyan
    val tool = brightYellow
    val system = brightRed
    val streaming = gray

    // === Kolory agentow (odpowiednik ChatMessageMapper.agentColors) ===
    val agentColors = listOf(
        brightCyan, brightGreen, brightYellow, brightMagenta,
        brightBlue, brightRed, brightWhite, yellow
    )
    fun forAgent(index: Int) = agentColors[index % agentColors.size]

    // === Kolory statusow (odpowiednik StepsQueueView badges) ===
    val statusNew = white
    val statusPending = yellow
    val statusRunning = brightBlue
    val statusSuccess = brightGreen
    val statusFailed = brightRed

    // === Kolory logow (odpowiednik LogsPanel row colors) ===
    val logDebug = gray
    val logInfo = white
    val logWarn = yellow
    val logError = brightRed

    // === Kolory kontekstu (odpowiednik ContextSectionColorPalette) ===
    val contextProject = brightCyan
    val contextUser = brightGreen
    val contextRag = brightMagenta
    val contextConversation = brightYellow
    val contextTools = brightBlue

    // === UI elements ===
    val tabActive = bold + brightWhite
    val tabInactive = gray
    val border = gray
    val accent = brightCyan
    val progressFilled = brightGreen
    val progressEmpty = gray

    // === Markdown code blocks ===
    val codeBlock = gray
    val codeKeyword = brightBlue
}
```

#### 11.7 TuiChatView — odpowiednik ChatView + PromptInputPanel

```kotlin
package pl.jclab.refio.cli.tui.views

/**
 * TUI odpowiednik ChatView + PromptInputPanel z plugina IntelliJ.
 *
 * Layout (w terminalu):
 * ┌─────────────────────────────────────────────────────┐
 * │ [Chat] [Steps] [Context] [RAG] [Logs] [Debug] [API]│  ← TuiTabBar
 * ├─────────────────────────────────────────────────────┤
 * │ ┌─ USER ──────────────────────────── 10:23:45 ────┐ │
 * │ │ Explain the architecture                         │ │
 * │ └─────────────────────────────────────────────────┘ │
 * │ ┌─ ASSISTANT (gpt-4o) ──────────── 10:23:48 ────┐ │
 * │ │ The architecture follows a layered pattern:     │ │
 * │ │ - **UI Layer** — Swing components              │ │
 * │ │ - **Core Layer** — Business logic              │ │
 * │ │ ```kotlin                                       │ │
 * │ │ class CoreApiRouter { ... }                     │ │
 * │ │ ```                                             │ │
 * │ │ ──── 1.2K in / 340 out · $0.003 · 2.1s ────── │ │  ← MetricsView
 * │ └─────────────────────────────────────────────────┘ │
 * │ ┌─ TOOL: read_file ──────────────── 10:23:50 ────┐ │
 * │ │ path: src/main/kotlin/.../CoreApiRouter.kt      │ │
 * │ │ result: [245 lines, 8.2KB]                      │ │
 * │ └─────────────────────────────────────────────────┘ │
 * │                                                     │
 * │ ⣿ Thinking...                                       │  ← streaming indicator
 * ├─────────────────────────────────────────────────────┤
 * │ [Chat] [model:gpt-4o] [Auto⚡] [🧠off]             │  ← selektory
 * │ > wpisz wiadomosc...                                │  ← input line
 * ├─────────────────────────────────────────────────────┤
 * │ [CHAT|gpt-4o] ████░░ $0.12 | 4.2K tok    [Ctrl+Q] │  ← TuiStatusBar
 * └─────────────────────────────────────────────────────┘
 *
 * Funkcjonalnosci przeniesione z IntelliJ:
 * - Markdown rendering (Mordant ma wbudowany markdown)
 * - Code blocks z syntax highlighting (ANSI kolory)
 * - Metryki per-wiadomosc (model, tokens, cost, time)
 * - Streaming z animated spinner
 * - Autocomplete @mentions z JLine3 Tab completion
 * - Mode selector: Ctrl+M cyklicznie Chat→Plan→Agent
 * - Model selector: /model <name> lub Ctrl+O
 * - Execution mode: Ctrl+E toggle Auto/Interactive
 * - Thinking mode: Ctrl+T toggle
 * - No-egress: /no-egress toggle
 * - Snippets: Ctrl+J wkleja ze schowka jako snippet
 */
class TuiChatView { /* ... */ }
```

#### 11.8 TuiSettingsScreen — odpowiednik SettingsView (11 zakladek)

```kotlin
package pl.jclab.refio.cli.tui.screens

/**
 * TUI odpowiednik SettingsView z 11 zakladkami.
 * Nawigacja: strzalki lewo/prawo miedzy zakladkami, gora/dol scroll w zakladce.
 *
 * Kazda zakladka wyswietla formularz edycji analogiczny do IntelliJ:
 *
 * [1] General     — Format markdown [x], Streaming [x], Advanced view [ ]
 * [2] Providers   — Lista providerow, per-provider: API key (masked), URL, /test-connection
 * [3] Models      — Tabela modeli, /model-add, /model-edit, /model-delete
 * [4] Prompts     — System prompts tabela + Commands tabela, /prompt-edit
 * [5] Context     — RAG settings: ignore paths, chunk size, /reindex, /stop-index
 * [6] MCP         — MCP servers: /mcp-add, /mcp-remove, /mcp-test, 16 presetow
 * [7] Docs        — Documentation URLs, /docs-add, /docs-reindex, /docs-delete
 * [8] Tools       — Tabela narzedzi z uprawnieniami per-mode (Plan/Agent)
 * [9] Subagents   — Lista subagentow: name, model, tools, enabled, /subagent-edit
 * [10] Advanced   — Security (no-egress, read-only), Limits (timeouts, max sizes)
 * [11] Theme      — Preview kolorow ANSI, /theme-reset
 *
 * Nawigacja:
 *   Tab / Shift+Tab  → nastepna/poprzednia zakladka
 *   Arrow Up/Down    → scroll w zakladce
 *   Enter            → edytuj pole / toggle checkbox
 *   Escape           → powrot do Chat
 *   /settings-reset  → reset to defaults (odpowiednik przycisku w IntelliJ)
 */
class TuiSettingsScreen { /* ... */ }
```

#### 11.9 TuiDebugView — odpowiednik DebugPanel

```kotlin
package pl.jclab.refio.cli.tui.views

/**
 * TUI odpowiednik DebugPanel. Wyswietla stan wewnetrzny plugina.
 *
 * Layout:
 * ┌─ Session State ──────────────────────────────────────┐
 * │ ID:       abc-123-def                                │
 * │ Mode:     AGENT                                      │
 * │ Model:    claude-3.5-sonnet                          │
 * │ Status:   RUNNING                                    │
 * │ Tokens:   12.4K in / 3.1K out                        │
 * │ Cost:     $0.0234                                    │
 * │ Messages: 14                                         │
 * │ Subtasks: 5 (3 done, 1 running, 1 pending)          │
 * ├─ Core Health ────────────────────────────────────────┤
 * │ Connection: ✅ Connected                              │
 * │ DB Path:    /project/refio_poc.db                    │
 * │ Latency:    23ms                                     │
 * ├─ LLM Statistics ─────────────────────────────────────┤
 * │ Total API calls:  47                                 │
 * │ Total tokens:     142.3K in / 38.1K out              │
 * │ Total cost:       $1.23                              │
 * │ Avg latency:      1.4s                               │
 * │ Errors:           2                                  │
 * ├─ Recent API Calls ───────────────────────────────────┤
 * │ 10:23:45  anthropic  claude-3.5  1.2K/340  $0.003   │
 * │ 10:23:48  anthropic  claude-3.5  890/210   $0.002   │
 * │ ...                                                  │
 * └──────────────────────────────────────────────────────┘
 *
 * Komendy:
 *   /debug-refresh  → odswiez dane
 *   /debug-copy     → kopiuj do schowka
 */
class TuiDebugView { /* ... */ }
```

### ARCHITEKTURA TUI vs HEADLESS

```
refio -p /project                     → TUI (jedyne GUI w CLI)
refio -p /project --headless --prompt  → stdout/stderr (pipe-friendly)
refio -p /project --headless --multi-agent file.yaml → stdout/stderr

                    ┌────────────────────────────────────┐
                    │      TuiViewModel (TUI-only)       │
                    │  StateFlows: messages, agents,      │
                    │  metrics, tabs, settings, history   │
                    ├────────────────────────────────────┤
                    │     TuiWorkflowListener             │
                    │     AgentEventBus subscription      │
                    └──────────────┬─────────────────────┘
                                   │
                    ┌──────────────▼─────────────────────┐
                    │   TUI (Mordant + JLine3)            │
                    │                                     │
                    │   TuiApp.kt (lifecycle)             │
                    │   TuiScreenManager (Main/Hist/Set)  │
                    │   TuiTabBar (7 tabs, F1-F7)        │
                    │   views/ (7 zakladek)               │
                    │   screens/ (History, Settings)      │
                    │   components/ (reusable widgets)    │
                    │   input/ (JLine3 key handling)      │
                    │   rendering/ (Mordant output)       │
                    └────────────────────────────────────┘
```

### PLAN TESTOW

| Plik testowy | Testy | Opis |
|-------------|-------|------|
| `TuiRendererTest.kt` | 10 | Full-screen layout, tab bar, split panels, resize, scroll offset |
| `TuiChatViewTest.kt` | 8 | Wiadomosci, markdown rendering, streaming indicator, metrics inline |
| `TuiStepsViewTest.kt` | 6 | Step list, expand/collapse, status badges, resume/replan/cancel |
| `TuiContextViewTest.kt` | 5 | Collapsible sections, token bars, color coding |
| `TuiRagViewTest.kt` | 4 | Files table, search, statistics display |
| `TuiLogsViewTest.kt` | 4 | Log table, color by level, refresh |
| `TuiDebugViewTest.kt` | 4 | Session state, core health, LLM stats |
| `TuiApiLogsViewTest.kt` | 5 | Statistics, filters, table, export |
| `TuiSettingsScreenTest.kt` | 8 | 11 zakladek, edit fields, toggle checkboxes, reset |
| `TuiHistoryScreenTest.kt` | 5 | Search, filter, load/delete session |
| `TuiInputHandlerTest.kt` | 8 | Key events: F1-F7 tabs, Enter send, Ctrl+C cancel, Ctrl+Q quit, Tab autocomplete |
| `TuiCompleterTest.kt` | 6 | @file, @codebase, /commands, !subagents completion |
| `TuiColorsTest.kt` | 4 | Agent colors, status colors, log level colors |
| `TuiViewModelTest.kt` | 6 | State combine, tab switching, settings CRUD, history load |
| `TuiStatusBarTest.kt` | 4 | Mode/model display, progress bar, cost/tokens |
| `TuiAppIntegrationTest.kt` | 3 | Startup → loading → ready, tab navigation, quit |

**Lacznie: ~90 testow w 16 plikach** (zastepuje ~19 testow Compose UI)

### WERYFIKACJA

```bash
# Kompilacja (bez Compose!)
./gradlew :cli:compileKotlin

# Testy TUI
./gradlew :cli:test --tests "*.tui.*"

# Pelny test suite
./gradlew :cli:test

# Sprawdz ze Compose nie jest w dependencies
./gradlew :cli:dependencies | grep -i compose  # powinno byc puste

# Manualne testy na roznych terminalach:
# macOS Terminal.app
# macOS iTerm2
# Windows Terminal (PowerShell)
# Windows cmd.exe (basic ANSI)
# Linux gnome-terminal
# SSH session (bez DISPLAY)
# tmux / screen
```

### WAZNE UWAGI IMPLEMENTACYJNE

1. **Compose Desktop jest USUNIETY** — nie ma opcji `--gui`. TUI jest jedynym GUI w module `:cli`. To zmniejsza rozmiar binarki (~30MB mniej bez Compose/Skia) i upraszcza build.
2. **TUI odwzorowuje PELNY plugin IntelliJ** — 7 zakladek, ustawienia (11 podstron), historia, status bar, autocomplete. Uzytkownik przechodzacy z IntelliJ na CLI nie traci zadnych funkcji.
3. **Nie reusujemy RefioViewModel** — stary ViewModel zalezal od `androidx.compose.ui.graphics.Color`. Nowy `TuiViewModel` jest czysty (bez Compose deps), uzywa ANSI colors.
4. **Streaming w TUI** — Mordant `Terminal.print()` + `\r` overwrite dla streaming chunks. Region ekranu aktualizowany co 100ms (debounce). JLine3 `Terminal.writer()` dla raw output.
5. **Windows compatibility** — JLine3 z JAnsi obsluguje Windows Terminal, PowerShell, cmd.exe (ConPTY). Mordant auto-detectuje Windows i dostosowuje ANSI. Fallback: plain text bez kolorow.
6. **Rozmiar terminala** — `JLine3 Terminal.getWidth()/getHeight()` + SIGWINCH handler. Minimalny rozmiar: 80x24. Re-render na resize.
7. **Markdown w terminalu** — Mordant ma wbudowany `Markdown` widget: headings, bold, italic, code blocks, lists. Wystarczajacy do renderowania odpowiedzi LLM.
8. **Autocomplete @mentions** — JLine3 `Completer` interface: dynamicznie laduje liste providerow z `ContextProviderRegistry`, subagentow z `SubagentRegistry`, komend z `PromptsRouter`.

---

# Czesc IV: Architektura docelowa

> **STATUS: ✅ COMPLETE (etapy 1-10)** — Caly diagram jest zaimplementowany. Etap 11 zastepuje Compose Desktop przez TUI.

Po zakonczeniu wszystkich 11 etapow:

```
┌──────────────────────────────────────────────────────────────────────┐
│  CLI TUI (Mordant+JLine3, terminal)          IntelliJ Plugin (Swing) │
│  ┌──────────────────────────────────┐       ┌──────────────────────┐ │
│  │ TuiTabBar [Chat|Steps|Ctx|...]  │       │ JTabbedPane (7 tabs) │ │
│  │ TuiChatView + TuiPromptInput    │       │ ChatView + PromptInput│ │
│  │ TuiStepsView, TuiContextView    │       │ StepsQueue, Context  │ │
│  │ TuiRagView, TuiLogsView         │       │ RagView, LogsPanel   │ │
│  │ TuiDebugView, TuiApiLogsView    │       │ DebugPanel, ApiLogs  │ │
│  │ TuiSettingsScreen (11 tabs)     │       │ SettingsView (11 tabs)│ │
│  │ TuiHistoryScreen                │       │ HistoryPanel         │ │
│  │ TuiStatusBar                    │       │ StatusBar            │ │
│  └──────────────┬───────────────────┘       └──────────┬───────────┘ │
│                 │                                       │            │
│  TuiWorkflowListener  AgentEventBus (SharedFlow)  SwingWorkflowListener│
└─────────────────┼──────────────┬────────────────────────┼────────────┘
                  │              │                        │
┌─────────────────▼──────────────▼────────────────────────▼────────────┐
│  Core Module (0 IntelliJ deps)                                       │
│                                                                      │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────┐        │
│  │ WorkflowOrchest │  │ MultiAgentRunner │  │ AgentEvent  │        │
│  │ (istniejacy)    │  │ (nowy)           │  │ Bus (nowy)  │        │
│  └────────┬────────┘  └────────┬─────────┘  └──────┬──────┘        │
│           │                    │                     │               │
│  ┌────────▼────────────────────▼─────────────────────▼──────┐       │
│  │ AgentTurnLoop (istniejacy, BEZ ZMIAN)                    │       │
│  │ kazdy agent = osobny Task z osobnym turn loop            │       │
│  └──────────────────────────────────────────────────────────┘       │
│                                                                      │
│  Tools │ LLM Adapters │ DB │ Config │ RAG │ Context │ MCP           │
└──────────────────────────────────────────────────────────────────────┘
```

---

# Czesc V: Zasady bezpieczenstwa implementacji

## Reguly dla agenta AI (juniora)

### 1. NIE ZMIENIAJ AgentTurnLoop
To jest najkruchszy komponent (3 fix commity). Multi-agent dziala OBOK niego, nie WEWNATRZ. Kazdy agent to osobny Task z osobna instancja AgentTurnLoop.

### 2. BACKWARD COMPATIBILITY jest obowiazkowa
Kazda zmiana w `GlobalMetrics`, `CoreApiRouter`, `BaseContextProvider` musi dzialac identycznie dla istniejacego kodu. Uzyj delegacji do "default", nullable parametrow, rozszerzenia interfejsow.

### 3. Testy PRZED zmianami
Najpierw napisz test dla komponentu ktory bedziesz zmieniac. Potem zmien. Potem uruchom test. Jezeli nie przechodzi — cofnij zmiane.

### 4. Nowe pliki > zmiany w istniejacych
Preferuj tworzenie nowych plikow (zerowe ryzyko regresji) nad modyfikacja istniejacych. 90% tej implementacji to nowe pliki.

### 5. Jeden PR = jeden etap
Nie mieszaj etapow. Kazdy etap to osobny PR z testami. Jezeli CI jest czerwone — napraw przed przejsciem dalej.

### 6. Source sets, nie przenoszenie plikow
W Etapie 3 (Gradle) uzywamy source sets wskazujacych na istniejace pliki. NIE przenosimy plikow fizycznie — to zachowuje dzialajacy build IntelliJ pluginu.

### 7. Zawsze sprawdz CI
Po kazdej zmianie:
```bash
./gradlew :core:test           # Core bez IntelliJ
./gradlew test                 # Pelny build z IntelliJ (istniejacy)
./gradlew :cli:compileKotlin   # CLI sie kompiluje
```

### 8. Jezeli cos nie dziala — nie naprawiaj brute force
Cofnij zmiane, przeczytaj blad, zrozum przyczyne, napraw systemowo. Nigdy `!!`, nigdy `catch (e: Exception) {}`, nigdy `@Suppress`.

---

# Czesc VI: Podsumowanie implementacji

> Kompletna implementacja zakonczona 2026-03-24 w 9 iteracjach.

## Sciezka krytyczna — DONE

```
Etap 1: Testy P0                            ✅ DONE (iter 1)
Etap 2: ProjectHandle                       ✅ DONE (iter 1-2)
Etap 3: Gradle modularyzacja                ✅ DONE (iter 2)
  │
  ├── Etap 4: StandaloneCoreBootstrap       ✅ DONE (iter 3)
  │     │
  │     └── Etap 5: CLI + Compose Desktop   ✅ DONE (iter 4)
  │           │
  │           ├── Etap 6: Interleaved chat  ✅ DONE (iter 4)
  │           └── Etap 10: GUI multi-agent  ✅ DONE (iter 4)
  │
  ├── IntelliJProjectHandle                 ✅ DONE (iter 2)
  │
  └── Etap 7-9: Metrics, EventBus, Runner   ✅ DONE (iter 1-2)

Post-plan (iteracje 5-9):
  ├── MultiAgentRunner → CoreApiRouter API  ✅ DONE (iter 5)
  ├── PathSandbox fix (181 test failures)   ✅ DONE (iter 6)
  ├── Headless mode + --multi-agent CLI     ✅ DONE (iter 6-7)
  ├── EventBus wiring CLI↔CoreApiRouter     ✅ DONE (iter 7)
  ├── ConfigRouter reset implementation     ✅ DONE (iter 7)
  ├── Standalone context providers (9/14)   ✅ DONE (iter 8)
  ├── Help dialog + TODO cleanup (0 left)   ✅ DONE (iter 8)
  └── resultSummary schema + UI wiring      ✅ DONE (iter 9)
```

## 5 providerow IDE_ONLY — nie portowane (by design)

| Provider | Powod | Alternatywa w CLI |
|----------|-------|-------------------|
| `@current` | Wymaga aktywnego edytora IDE | Uzyj `@file:<path>` |
| `@open_files` | Wymaga listy otwartych zakladek IDE | Uzyj `@file:<pattern>` |
| `@recent` | Wymaga historii IDE (`EditorHistoryManager`) | Uzyj `@file` submenu |
| `@problems` | Wymaga live compiler/inspections (`WolfTheProblemSolver`) | Uzyj zewnetrznego lintera |
| `@terminal` | Wymaga IDE terminal widget (reflection) | Uzyj `@grep` lub `run_terminal_command` tool |

## Zadania niezalezne (mozna robic rownolegle)

| Zadanie | Priorytet | Status | Opis |
|---------|-----------|--------|------|
| ~~PlanningServiceTest~~ | ~~P1~~ | ✅ DONE | 16 testow (plan creation, validation, JSON parsing, standalone compat, subtask creation) |
| ~~SnapshotServiceTest~~ | ~~P1~~ | ✅ DONE | 13 testow (create/restore, hash, multiple files) |
| ~~ConversationCompactorTest~~ | ~~P1~~ | ✅ DONE | 8 testow (threshold, summarize, WEAK model) |
| ~~AgentEventRepository impl~~ | ~~P1~~ | ✅ DONE | AgentEventSqlRepository z Gson serialization |
| ~~agent_instance_id w ChatMessagesTable~~ | ~~P1~~ | ✅ DONE | Kolumna + mapping + findByAgentInstanceId() |
| ~~IntelliJProjectHandle~~ | ~~P1~~ | ✅ DONE | `services/project/IntelliJProjectHandle.kt` |
| ~~MultiAgentRunner → CoreApiRouter~~ | ~~P2~~ | ✅ DONE | `launchMultiAgentSession()`, `getMultiAgentSession()`, `listMultiAgentSessions()` w CoreApiRouter. AgentEventBus + repos + MultiAgentRunner jako lazy vals. DTOs: `MultiAgentSessionRequest/Response`, `MultiAgentInstanceResponse`. 6 testow. |
| ~~Cycle detection w MultiAgentRunner~~ | ~~P2~~ | ✅ DONE | DFS walidacja + unknown agent detection (12 testow) |
| ~~CHANGELOG/README update~~ | ~~P2~~ | ✅ DONE | CHANGELOG.md: Multi-Agent API, Standalone CLI + Compose Desktop GUI, Platform Abstraction, Concurrency, Tests |

## Wszystkie zadania dodatkowe — zrealizowane (iteracje 5-9)

### Bugi naprawione (iter 6-7)

| Zadanie | Iter | Opis |
|---------|------|------|
| PathSandbox symlink | 6 | `toRealPath()` dla `normalizedRoot` + `resolveComparablePath()` — naprawiono 181 test failures |
| CLI EventBus wiring | 7 | Bridge `router.agentEventBus` → `viewModel.agentEventBus` w `initialize()` |
| MultiEditTool test | 6 | Asercja poprawiona na cumulative `"qux zap foo baz"` |
| RunTerminalCommand test | 6 | Cross-platform `ls`/`dir` |
| ConfigRouter reset | 7 | `deleteByScope(APP/PROJECT)` + `initializeDefaults()` |

### Funkcjonalnosci dodane (iter 5-9)

| Zadanie | Iter | Opis |
|---------|------|------|
| Multi-Agent API | 5 | `launchMultiAgentSession()`, `getMultiAgentSession()`, `listMultiAgentSessions()` + DTOs |
| Headless mode | 6 | `WorkflowOrchestrator.execute()` z streaming chunks na stdout |
| `--multi-agent` CLI | 7 | YAML file parsing → `launchMultiAgentSession()` → per-agent results |
| 7 standalone providers | 8 | `@file`, `@folder`, `@grep`, `@diff`, `@commit`, `@codebase`, `@docs` — nowy pakiet `providers/standalone/` |
| 5 IDE_ONLY markers | 8 | `@current`, `@open_files`, `@recent`, `@problems`, `@terminal` |
| Help dialog | 8 | `BrowserUtil.browse()` w RefioMainPanel + ToolbarComponent |
| `resultSummary` | 9 | Pole w `SubtaskResponse` → `SubtaskDto` → `StepsQueueView` display |

### TODO w kodzie — 0 pozostalych (9 rozwiazanych)

| Plik | Rozwiazanie | Iter |
|------|-------------|------|
| `ConfigRouter.kt` | Zaimplementowany reset | 7 |
| `RefioMainPanel.kt` (help) | `BrowserUtil.browse()` | 8 |
| `RefioMainPanel.kt` (steps vis) | No-op by design | 8 |
| `ToolbarComponent.kt` | `BrowserUtil.browse()` | 8 |
| `SessionContextBar.kt` | Deferred by design | 8 |
| `StepsQueueView.kt` | Schema + UI wired | 9 |
| `Base.kt` | Komentarz designowy | 9 |

### Architektura — stan standalone

```
CoreApiRouter.agentEventBus ──> MultiAgentRunner ──> AgentStarted/Completed/Failed
       │
       ├─> IntelliJ Plugin SessionManager ✅ (connected)
       │
       └─> CLI RefioViewModel.agentEventBus ✅ (bridge w initialize(), iter 7)
```

Context providers w standalone (9/14):
- ✅ `@clipboard`, `@url` — oryginalne (platformowo-niezalezne)
- ✅ `@file`, `@folder`, `@grep`, `@diff`, `@commit`, `@codebase`, `@docs` — standalone wersje w `providers/standalone/`
- ❌ `@current`, `@open_files`, `@recent`, `@problems`, `@terminal` — IDE_ONLY (wymagaja IntelliJ editor/compiler/terminal)

## Metryki jakosci

| Metryka | Przed | Finalne (iter 9) |
|---------|-------|------------------|
| Nowe pliki zrodlowe | 0 | **31** (7 CLI UI, 7 standalone providers, 12 core agents/events/db, 5 inne) |
| Zmienione pliki zrodlowe | 0 | **~55** |
| Nowe pliki testowe | 0 | **32** |
| Nowe testy | 0 | **~290** |
| Pre-existing test failures | ~183 | **0** (PathSandbox fix) |
| Failures w nowych testach | — | **0** |
| Moduly Gradle | 1 | **3** (`:core`, `:intellij-plugin`, `:cli`) |
| Pliki core z `com.intellij` import | 17 | **13** (source-set excluded) |
| `:core` kompiluje bez IntelliJ SDK | ❌ | **✅** |
| `:cli` kompiluje i testuje | — | **✅** (22 testy w tym 19 Compose UI) |
| Standalone context providers | 2/14 | **9/14** (+7 standalone, 5 IDE_ONLY) |
| Headless mode | ❌ | **✅** (single prompt + `--multi-agent` YAML) |
| CLI EventBus wiring | ❌ | **✅** (bridge w `RefioViewModel.initialize()`) |
| TODO w kodzie | 9 | **0** |
| `resultSummary` w StepsQueueView | ❌ | **✅** (schema → DTO → UI) |
| Config reset | ❌ stub | **✅** (`deleteByScope` + `initializeDefaults`) |
| Help dialog | ❌ stub | **✅** (`BrowserUtil.browse()`) |

### Podsumowanie iteracji

| Iteracja | Zakres | Kluczowe deliverables |
|----------|--------|----------------------|
| 1 | Etapy 1, 2, 7, 8, 9 | Testy P0, ProjectHandle, GlobalMetrics, AgentEventBus, MultiAgentRunner |
| 2 | Etapy 2, 3, 8 (dokonczenie) | Gradle modularyzacja, IntelliJProjectHandle, AgentEventSqlRepository |
| 3 | Etap 4 + testy P1 | StandaloneCoreBootstrap, PlanningServiceTest, SnapshotServiceTest |
| 4 | Etapy 5, 6, 10 | Compose Desktop GUI, interleaved chat, multi-agent GUI panels |
| 5 | P2 backlog | MultiAgentRunner→CoreApiRouter API, CHANGELOG |
| 6 | Bug fixes + testy | PathSandbox fix (181 tests), headless mode, Compose UI tests |
| 7 | Features + fixes | EventBus wiring, `--multi-agent` CLI, ConfigRouter reset |
| 8 | Context providers | 7 standalone providerow, 5 IDE_ONLY, help dialog, TODO cleanup |
| 9 | Finalizacja | `resultSummary` wiring, ostatni TODO, dokumentacja |
