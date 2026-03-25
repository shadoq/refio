# Refio — Standalone CLI + Multi-Agent: Przewodnik implementacji

> **Data:** 2026-03-25
> **Wersja:** 3.4 (20 iteracji, 12 etapów, 9/14 providerów, ~460 nowych testów, 0 failures)
> **Cel:** Dokument referencyjny — architektura, decyzje projektowe i przewodnik dla nowych developerów

---

## Spis treści

- [1. Kontekst i cele](#1-kontekst-i-cele)
- [2. Status implementacji](#2-status-implementacji)
- [3. Architektura docelowa](#3-architektura-docelowa)
- [4. Moduły Gradle](#4-moduły-gradle)
- [5. Etapy implementacji](#5-etapy-implementacji)
- [6. Architektura TUI](#6-architektura-tui)
- [7. Kluczowe decyzje projektowe](#7-kluczowe-decyzje-projektowe)
- [8. Przewodnik dla developerów](#8-przewodnik-dla-developerów)
- [9. Komendy i skróty klawiszowe TUI](#9-komendy-i-skróty-klawiszowe-tui)
- [10. Porównanie Plugin IntelliJ vs TUI](#10-porównanie-plugin-intellij-vs-tui)
- [11. Zasady bezpieczeństwa](#11-zasady-bezpieczeństwa)
- [12. Build i testowanie](#12-build-i-testowanie)

---

## 1. Kontekst i cele

### Co to jest Refio

Plugin IntelliJ IDEA (~346 plików Kotlin, ~118K LOC) — lokalny asystent AI z trzema trybami: **Chat**, **Plan** (read-only), **Agent** (read/write).

### Dlaczego te zmiany

1. **Wersja standalone (CLI z TUI)** — do kursu AI i benchmarków, bez IntelliJ
2. **Multi-agentowość** — wielu agentów pracujących równolegle z komunikacją
3. **GUI w terminalu** — `refio -p .` rysuje UI w konsoli (jak `htop`, `lazygit`, `k9s`)
4. **Przyszłość: Web (React)** — architektura core musi być niezależna od platformy

### Co zbudowaliśmy

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

## 2. Status implementacji

| Etap | Status | Opis |
|------|--------|------|
| 1. Testy P0 | ✅ DONE | 5 testów P0 + dodatkowe. Łącznie ~220 nowych testów w 23 plikach. |
| 2. ProjectHandle | ✅ DONE | Interface + StandaloneProjectHandle + IntelliJProjectHandle + CoreApiRouter. |
| 3. Gradle modularyzacja | ✅ DONE | 3 moduły: `:core`, `:intellij-plugin`, `:cli`. Core bez IntelliJ SDK. |
| 4. Standalone bootstrap | ✅ DONE | `StandaloneCoreBootstrap` + `main.kt` (Clikt CLI) + `CoreApiRouter.createProjectRouter()`. |
| 5. CLI + GUI | ✅ → Etap 11 | Compose Desktop **usunięty**. Zastąpiony przez TUI (Mordant+JLine3). |
| 6. Interleaved chat | ✅ DONE | UIChatMessage, ChatMessageMapper (8 kolorów per-agent, 10 typów eventów). |
| 7. GlobalMetrics + FileLock | ✅ DONE | AgentMetrics per-agent, FileLockManager, OllamaRequestGate. |
| 8. AgentEventBus + DB | ✅ DONE | 12 typów eventów, SharedFlow bus, 3 tabele DB. |
| 9. MultiAgentRunner | ✅ DONE | Parallel execution z DAG deps, YAML parser, cycle detection. |
| 10. GUI multi-agent | ✅ → Etap 11 | Przeniesione do TUI (TuiStepsView, TuiStatusBar). |
| 11. TUI | ✅ DONE | 39 plików, 171 testów, ~90% pokrycia plugina IntelliJ. |
| 12. Multi-Agent jako opcja | 🔲 TODO | Toggle w prompt input + załadka Agents z DAG. |

### Testy P1

- PlanningServiceTest ✅ (16 testów)
- SnapshotServiceTest ✅ (13 testów)
- ConversationCompactorTest ✅ (8 testów)

### Metryki końcowe

| Metryka | Wartość |
|---------|---------|
| Nowe pliki źródłowe | 34 |
| Zmienione pliki | ~65 |
| Nowe pliki testowe | 33 |
| Nowe testy | ~460 (w tym 171 TUI) |
| Test failures | 0 |
| Moduły Gradle | 3 |
| Standalone context providers | 9/14 |

---

## 3. Architektura docelowa

```
┌──────────────────────────────────────────────────────────────────────┐
│  CLI TUI (Mordant+JLine3, terminal)          IntelliJ Plugin (Swing) │
│  ┌──────────────────────────────────┐       ┌──────────────────────┐ │
│  │ TuiTabBar [Chat|Steps|Ctx|...]  │       │ JTabbedPane (7 tabs) │ │
│  │ TuiChatView + TuiPromptInput    │       │ ChatView + PromptInput│ │
│  │ TuiStepsView, TuiContextView    │       │ MultiAgentProgress   │ │
│  │ TuiSettingsScreen (11 tabs)     │       │ SettingsView (11 tabs)│ │
│  │ TuiStatusBar                    │       │ StatusBar            │ │
│  └──────────────┬───────────────────┘       └──────────┬───────────┘ │
│                 │                                       │            │
│  TuiWorkflowListener  AgentEventBus (SharedFlow)  SwingWorkflowListener│
└─────────────────┼──────────────┬────────────────────────┼────────────┘
                  │              │                        │
┌─────────────────▼──────────────▼────────────────────────▼────────────┐
│  Core Module (0 IntelliJ deps)                                       │
│                                                                      │
│  Mode Routing:                                                       │
│    CHAT ──→ ChatExecutor ──→ ChatService                             │
│    PLAN ──→ AgentTurnLoop (read-only tools)                          │
│    AGENT ──→ AgentTurnLoop (all tools)                               │
│    MULTI_AGENT ──→ MultiAgentRunner ──→ N × AgentTurnLoop (parallel) │
│                                                                      │
│  Tools │ LLM Adapters │ DB │ Config │ RAG │ Context │ MCP           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. Moduły Gradle

| Moduł | Opis | Zależności |
|-------|------|-----------|
| `:core` | Pure Kotlin/JVM, logika biznesowa. 0 importów IntelliJ. Source-set exclude'y dla 13 plików IDE-zależnych. | Coroutines, Ktor, Exposed, Gson, Caffeine |
| `:intellij-plugin` | Pełny plugin z IntelliJ SDK. Swing UI, IDE actions. | `:core` + IntelliJ Platform SDK |
| `:cli` | CLI z TUI. Mordant+JLine3. | `:core` + Clikt 5.0.2 + Mordant 3.0.1 + JLine3 3.26.3 |

**Kluczowe:** Source-set approach — pliki NIE zostały fizycznie przeniesione. Moduły wskazują na `../src/main/kotlin` z odpowiednimi `include()`.

---

## 5. Etapy implementacji

### Etap 1: Testy krytycznych luk

5 testów P0 pokrywających komponenty bez testów: `LLMClientTest`, `ChatServiceTest`, `LLMRetryHandlerTest`, `ContextProviderRegistryTest`, `TurnGuardrailsTest`. Warunek konieczny przed refaktoryzacją.

### Etap 2: ProjectHandle — abstrakcja projektu

Nowy interfejs `ProjectHandle` z dwoma implementacjami:
- `StandaloneProjectHandle` — dla CLI (z `Path`)
- `IntelliJProjectHandle` — wrapper na `com.intellij.openapi.project.Project`

Zamiana `Project?` → `ProjectHandle?`/`Any?` w 6 plikach core. `BaseContextProvider` zmieniony na `Any?`.

### Etap 3: Modularyzacja Gradle

3 moduły bez przenoszenia plików. Kluczowe:
- `:core` kompiluje BEZ IntelliJ SDK
- 13 plików z bezpośrednimi IntelliJ API calls wykluczonych z `:core`
- `ContextProviderRegistry.providerFactory` lambda zamiast bezpośrednich importów

### Etap 4: Standalone bootstrap

`StandaloneCoreBootstrap` — inicjalizacja core bez IntelliJ: database (SQLite w `.refio/`), config, tools, context providers (`isIdeEnvironment=false`).

`CoreApiRouter.createProjectRouter()` — publiczna metoda tworząca project-level router z toolami.

### Etap 5-6: CLI + Interleaved chat

Compose Desktop GUI + interleaved chat z kolorami per-agent. **Usunięte w Etapie 11** — zastąpione przez TUI.

### Etap 7: GlobalMetrics per-agent + FileLockManager

- `GlobalMetrics.forAgent(agentId)` — per-agent metrics z backward compat (delegacja do "default")
- `FileLockManager` — Mutex per-ścieżka pliku, użyty w 5 write tools
- `OllamaRequestGate.maxConcurrentPerEndpoint`

### Etap 8: AgentEventBus + event system

- `AgentEvent` — sealed interface z 12 typami eventów (lifecycle, data exchange, coordination, approval, progress)
- `AgentEventBus` — SharedFlow (replay=200, buffer=500), 6 metod filtrujących
- `AgentEventHandler` — CompletableDeferred dla requestData/requestApproval
- 3 tabele DB: `agent_sessions`, `agent_instances`, `agent_events`
- `agent_instance_id` kolumna w `ChatMessagesTable`

### Etap 9: MultiAgentRunner

- Parallel execution w `supervisorScope` z DAG dependency resolution
- `MultiAgentTaskParser` — YAML → `AgentSpec` lista
- Cycle detection (DFS) w dependency graph
- Event emission per agent lifecycle
- `CoreApiRouter.launchMultiAgentSession()` API

### Etap 10: GUI multi-agent

Wizualizacja DAG, approval panel, metryki. **Przeniesione do TUI w Etapie 11.**

### Etap 11: TUI — Terminal User Interface

Pełne zastąpienie Compose Desktop przez TUI w terminalu. Szczegóły w [rozdziale 6](#6-architektura-tui).

### Etap 12: Multi-Agent jako opcja (TODO)

Multi-agent to **opcja** (toggle), nie 4. tryb pracy. Nowa zakładka **Agents** z wizualizacją DAG — zarówno dla multi-agent jak i single agent flow.

Brakuje:
- Multi-agent toggle w PromptInputPanel (Plugin + TUI)
- Agent builder UI
- Zakładka Agents (8. tab) z ASCII DAG
- `AsciiDagRenderer` w core
- Single-agent event emission z `AgentTurnLoop`
- EventBus bridge do plugina IntelliJ

---

## 6. Architektura TUI

### Przepływ danych

```
Użytkownik naciska klawisz
    ↓
TuiInputHandler (raw TTY / line mode)
    ↓
TuiViewModel (aktualizuje MutableStateFlow)
    ↓
stateFlow: StateFlow<TuiState> (merge 20 flows → buildCurrentState())
    ↓
TuiApp.renderJob (collect → renderer.render(state))
    ↓
TuiRenderer → TuiScreenBuffer (framebuffer width × height)
    ├── setRows(tabBar + separator + contentLines)
    ├── overlay(autocomplete popup / modal dialog)
    └── flush(terminal) — 1 atomiczny zapis, zero migotania
    ↓
Terminal wyświetla nowy obraz
```

### Komponenty

```
TuiApp (entry point)
│
├── TuiViewModel (MVVM)
│   ├── 21 MutableStateFlows → merge() → stateFlow: StateFlow<TuiState>
│   ├── sendMessage() → WorkflowOrchestrator.execute()
│   ├── Settings via ConfigRouter
│   ├── TuiLogSink → LogSinkRegistry (core logs → Logs tab)
│   ├── refreshApiLogs(), refreshRagStats(), refreshDebugState()
│   └── startAutoRefresh() — 30s timer
│
├── TuiRenderer (framebuffer compositor)
│   ├── TuiScreenBuffer — full-screen z overlay + atomiczny flush()
│   ├── Split-pane: left (chat+prompt, 55%) + right (active tab, 45%)
│   ├── Full-width gdy Chat tab aktywny
│   └── TuiRenderBuffer — ANSI-aware line buffers
│
├── TuiInputHandler (dual-mode)
│   ├── Raw TTY: JLine3, F1-F8, Ctrl+Q/S/M, escape sequences
│   └── Line mode: /commands, :tab shortcuts (IDE/pipe fallback)
│
├── TuiWorkflowListener (streaming bridge)
│   ├── 500ms debounce (wzorzec z SwingWorkflowListener)
│   ├── Content accumulation (synchronized)
│   └── Steps tracking (onToolStarted/onStepStarted/onIntentCompleted)
│
├── 7 tab views → TuiRenderBuffer
│   ├── TuiChatView, TuiStepsView, TuiContextView, TuiRagView
│   ├── TuiLogsView, TuiDebugView, TuiApiLogsView
│
├── TuiSettingsScreen (11 sub-tabs)
├── TuiHistoryScreen (session browser)
└── TuiColors (paleta ANSI: 8 agent, 5 status, 4 log, 5 context)
```

### Struktura plików (39 plików)

```
cli/tui/
├── TuiApp.kt                  # Entry point, 3 coroutines (render, resize, input)
├── TuiScreenManager.kt        # Screen navigation (MAIN, HISTORY, SETTINGS)
├── TuiTabBar.kt               # Tab bar rendering (F1-F8)
├── rendering/
│   ├── TuiRenderer.kt         # Framebuffer compositor → TuiScreenBuffer
│   ├── TuiScreenBuffer.kt     # Full-screen framebuffer z overlay
│   ├── TuiRenderBuffer.kt     # ANSI-aware line buffers
│   ├── TuiLayout.kt           # Layout geometry (split ratio, heights)
│   ├── TuiColors.kt           # ANSI color palette
│   ├── TuiMarkdown.kt         # Markdown → ANSI via Mordant
│   ├── TuiContentSegment.kt   # Segment types: Thinking/Code/Json/Markdown
│   └── TuiContentParser.kt    # Content parser z streaming awareness
├── views/
│   ├── TuiChatView.kt         # Chat messages + prompt → buffer
│   ├── TuiStepsView.kt        # Steps queue z status colors
│   ├── TuiContextView.kt      # Context sections z progress bars
│   ├── TuiRagView.kt          # RAG stats z getRagStatistics()
│   ├── TuiLogsView.kt         # Log stream z TuiLogSink
│   ├── TuiDebugView.kt        # Debug: session + LLM stats
│   └── TuiApiLogsView.kt      # API logs z ApiLogsRouter
├── screens/
│   ├── TuiSettingsScreen.kt   # 11 sub-tabs → TuiRenderBuffer
│   └── TuiHistoryScreen.kt    # Session history z listTasks()
├── components/
│   ├── TuiMessageBubble.kt    # Bubble router + content segment rendering
│   ├── TuiPromptInput.kt      # Input: mode icon, model, hints
│   ├── TuiProgressBar.kt      # ASCII progress bar
│   ├── TuiTable.kt            # Table rendering
│   ├── TuiCollapsible.kt      # Expand/collapse sections
│   ├── TuiDialog.kt           # Dialog boxes
│   ├── TuiSpinner.kt          # Loading spinner
│   └── TuiStatusBar.kt        # (legacy, unused — info in tab bar)
├── input/
│   ├── TuiInputHandler.kt     # Dual-mode input + 12 komend
│   ├── TuiKeybindings.kt      # F-key + Ctrl+key mappings
│   ├── TuiCompleter.kt        # JLine3 completer
│   └── TuiContextValidator.kt # Walidator @file/@folder
└── state/
    ├── TuiState.kt            # 30+ pól stanu, TuiSessionEntry
    ├── TuiViewModel.kt        # 21 StateFlows, auto-refresh 30s, MVVM bridge
    ├── TuiWorkflowListener.kt # Streaming 500ms debounce + steps tracking
    ├── TuiLogSink.kt          # LogSink → _logs StateFlow
    └── TuiChatMessageMapper.kt # AgentEvent → TuiChatMessage
```

### Wzorce zaadaptowane z plugina IntelliJ

| Wzorzec | Źródło w pluginie | TUI implementacja |
|---------|-------------------|-------------------|
| Streaming debounce 500ms | `SwingWorkflowListener` | `TuiWorkflowListener.UI_UPDATE_INTERVAL_MS` |
| StreamChunk dedup | `ChatView` message caching | `TuiChatMessageMapper: StreamChunk → null` |
| Auto-refresh 30s | `DebugPanel` Timer | `TuiViewModel.startAutoRefresh()` |
| Content segment parser | `ContentSegmentParser` (232 ln) | `TuiContentParser` (175 ln) |
| Bubble routing | `ChatMessageBubbleRouter` + 5 renderers | `TuiMessageBubble` 4 renderery |
| Code block rendering | `CodeBlockPanel` (717 ln) | ANSI ramka + numery linii |
| Session history | `HistoryPanel` (551 ln) | `TuiHistoryScreen` + `listTasks()` |
| Conversation export | `ChatConversationExportUtil` (311 ln) | `/export` → Markdown |
| Steps tracking | `StepsQueueView` events | `onToolStarted`/`onStepStarted` → `_steps` |
| LogSink bridge | `PluginLoggerSink` | `TuiLogSink` → `LogSinkRegistry` |
| RAG statistics | `RagViewPanel.refreshData()` | `refreshRagStats()` |
| Debug enrichment | `DebugPanel.refreshState()` | `refreshDebugState()` → `ApiLogStatistics` |
| Framebuffer rendering | N/A (Swing repaint) | `TuiScreenBuffer` + overlay |
| Input panel features | `PromptInputPanel` dropdowns | Mode icon + model + hints |
| Plan approval | `PlanApprovalDialog` | Overlay modal (y/n/Esc) |
| Context validation | `ContextValidator` | `TuiContextValidator` (@file/@folder) |

---

## 7. Kluczowe decyzje projektowe

| Decyzja | Uzasadnienie |
|---------|-------------|
| Mordant + JLine3 zamiast Compose Desktop | CLI = terminal, nie okno. Działa na serwerach, SSH, Docker, tmux. Mordant jest tego samego autora co Clikt. |
| Source sets zamiast przenoszenia plików | Zachowuje działający build IntelliJ pluginu. Zero ryzyka regresji. |
| `merge()` zamiast `combine()` dla stateFlow | `combine()` max 5 flows — potrzebujemy 20 flows. |
| In-place overwrite (`\u001b[H`) zamiast clear+redraw | `\u001b[2J` powodował migotanie. |
| `TuiScreenBuffer` (framebuffer) | Jeden atomiczny `flush()` — zero artefaktów, gotowy na modale i overlay. |
| `synchronized(accumulatedContent)` w streaming | Race condition — wiele chunków jednocześnie modyfikowało StringBuilder. |
| Resize polling (300ms) zamiast SIGWINCH | Mordant nie dostarcza callbacku resize; polling jest cross-platform. |
| `reset()` w TuiWorkflowListener przed requestem | Listener jest reused — stary `completed=true` blokował nowe chunki. |
| StreamChunk → `null` w TuiChatMessageMapper | Eliminuje duplikaty: streaming wyłącznie przez WorkflowListener. |
| Buffer-based Settings/History | `terminal.println()` nie dodawało `\u001b[K]` — stara zawartość przeświecała. |
| `build.dependsOn("installDist")` | `gradle clean build` kasowało skrypty CLI. |
| Clikt 4.2.1 → 5.0.2 | Kompatybilność z Mordant 3.x (zmiana konstruktora `Terminal`). |
| Dual-mode input (raw TTY + line fallback) | JLine3 nie mógł utworzyć terminala w IntelliJ Gradle runner (brak TTY). |

---

## 8. Przewodnik dla developerów

### Złota zasada

TUI jest terminalowym odpowiednikiem plugina IntelliJ. **Zanim piszesz coś od zera w TUI, sprawdź jak to działa w pluginie** (`src/main/kotlin/pl/jclab/refio/ui/`). Plugin to referencja — TUI to uproszczona adaptacja.

### Jak dodać nowy widok (zakładkę)

1. Dodaj enum w `TuiState.kt` → `TuiTab` (np. `METRICS("Metrics")`)
2. Utwórz `views/TuiMetricsView.kt` z metodą `renderToBuffer(state, width, height): TuiRenderBuffer`
3. Podłącz w `TuiRenderer.renderRightPanel()` → nowy case
4. Podłącz w `TuiKeybindings` → nowy F-key
5. Dodaj dane w `TuiViewModel` → nowy `MutableStateFlow`, dodaj do `merge()` i `buildCurrentState()`
6. Testy → `TuiMetricsViewTest.kt`

### Jak dodać modal/dialog

1. Dodaj stan w `TuiState.kt` → np. `showApprovalDialog`, `approvalDialogData`
2. Zbuduj linie dialogu w `TuiRenderer` → `buildDialogLines(): List<String>`
3. Namaluj overlay → `screen.overlay(startRow, startCol, dialogLines)` (przed `flush()`)
4. Obsłuż input → w `TuiInputHandler` przechwytuj klawisze gdy dialog widoczny

### Jak podłączyć dane z core

```kotlin
// Wzorzec w TuiViewModel:
private fun refreshSomething(r: CoreApiRouter) {
    try {
        val data = r.someRouter.getSomeData()
        _someStateFlow.value = data.map { ... }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to refresh something" }
    }
}

// Wywołanie:
// 1. Po każdym workflow: w sendMessage() po execute()
// 2. W auto-refresh: w startAutoRefresh() co 30s
// 3. Na żądanie: metoda publiczna wywoływana z TuiInputHandler
```

**Dostępne routery w CoreApiRouter:** `configRouter`, `apiLogsRouter`, `ragRouter`, `workflowOrchestrator`, `agentEventBus` + sesje, subtaski, narzędzia.

### Jak dodać nowy StateFlow

1. Utwórz `private val _newFlow = MutableStateFlow(defaultValue)` w `TuiViewModel`
2. Dodaj `_newFlow.map { Unit }` do listy w `merge()` → `stateFlow`
3. Dodaj pole w `buildCurrentState()` → `newField = _newFlow.value`
4. Dodaj pole w `TuiState` data class

### Typowe błędy

| Błąd | Przyczyna | Rozwiązanie |
|------|-----------|-------------|
| Nowy StateFlow nie triggeruje re-render | Nie dodany do `merge()` | Dodaj `_newFlow.map { Unit }` do merge |
| Tab bar znika (scroll) | Ostatnia linia z `println` (dodaje `\n`) | Użyj `TuiScreenBuffer.flush()` |
| Duchy z poprzedniego renderu | Rendering `terminal.println()` | Renderuj do `TuiRenderBuffer` |
| Duplikaty wiadomości streaming | Dwa kanały: WorkflowListener + EventBus | StreamChunk → `null` w mapper |
| Migotanie przy szybkim streaming | Brak debounce | 500ms debounce gate |
| `combine()` max 5 flows | Kotlin limitation | Użyj `merge()` → `map { buildCurrentState() }` |
| Puste taby (Logs, API, RAG) | StateFlows nie populowane | Podłącz LogSink, refreshApiLogs(), refreshRagStats() |
| `gradle clean build` kasuje skrypty | Brak dependency | `build.dependsOn("installDist")` |

---

## 9. Komendy i skróty klawiszowe TUI

### Komendy (12)

| Komenda | Opis |
|---------|------|
| `/help`, `/?` | Pokaż pomoc |
| `/mode` | Cycle mode (CHAT → PLAN → AGENT) |
| `/model <name>` | Zmień model LLM |
| `/history` | Przeglądaj sesje |
| `/history-delete <id>` | Usuń sesję |
| `/settings` | Otwórz ustawienia (11 sub-tabs) |
| `/set <key> <value>` | Ustaw wartość konfiguracji |
| `/settings-reset` | Reset ustawień do domyślnych |
| `/export <path>` | Eksportuj konwersację do Markdown |
| `/resend` | Ponów ostatnią wiadomość |
| `/clear` | Wyczyść input |
| `/quit`, `/q` | Wyjście |

### Skróty klawiszowe (raw TTY)

| Skrót | Akcja |
|-------|-------|
| F1-F7 | Przełączanie zakładek (Chat, Steps, Context, RAG, Logs, Debug, API) |
| F8 | Ustawienia |
| Ctrl+M | Cycle mode |
| Ctrl+Q | Wyjście |
| Ctrl+S | Ustawienia |
| Ctrl+H | Historia |
| Escape | Powrót do głównego ekranu |
| `@` | Autocomplete kontekstu (14 prefiksów) |
| Enter | Wyślij wiadomość |
| Arrows | Nawigacja autocomplete |

### Line mode (IDE/pipe fallback)

`:chat`/`:1` → Chat tab, `:steps`/`:2` → Steps, `:context`..`:api`/`:3`..`:7` → odpowiednie zakładki. Zwykłe wiadomości → wysyłane do chatu.

---

## 10. Porównanie Plugin IntelliJ vs TUI

### Ilościowo

| Metryka | Plugin (`ui/`) | TUI (`cli/tui/`) |
|---------|---------------|-----------------|
| Pliki źródłowe | ~70 | 39 |
| Linie kodu | ~15,000 | ~5,000 |
| Pokrycie funkcjonalne | 100% | ~90% (100% możliwych w terminalu) |

### Funkcjonalnie

| Feature | Plugin | TUI | Status |
|---------|--------|-----|--------|
| Content segment parsing | ✅ | ✅ | Zaadaptowany |
| Code block rendering | ✅ (EditorFactory) | ✅ (ANSI ramka) | Uproszczony |
| Bubble routing | ✅ (5 rendererów) | ✅ (4 renderery) | Zaadaptowany |
| Streaming debounce 500ms | ✅ | ✅ | Identyczny |
| Settings (11 tabs) | ✅ | ✅ | Buffer-based |
| History z danymi | ✅ | ✅ | listTasks() |
| Steps tracking | ✅ | ✅ | WorkflowListener |
| Logs, API logs, RAG stats | ✅ | ✅ | Auto-refresh 30s |
| Model selector | ✅ (dropdown) | ✅ (/model) | Zaadaptowany |
| Conversation export | ✅ (JSON/MD) | ✅ (MD) | Zaadaptowany |
| Plan approval | ✅ (dialog) | ✅ (overlay) | Zaadaptowany |
| Context validation | ✅ | ✅ | @file/@folder |
| GFM tables | ✅ | ✅ | Mordant native |
| Message resend | ✅ | ✅ | /resend |

### Feature'y dostępne TYLKO w pluginie (wymagają IntelliJ SDK)

| Feature | Powód |
|---------|-------|
| Syntax highlighting | IntelliJ `EditorFactory` |
| File navigation (open in editor) | IntelliJ editor API |
| Native code completion | `CompletionContributor` API |
| Editable user bubbles | `EditorTextField` |
| Code snippet cards (Ctrl+J) | IntelliJ clipboard/editor |
| Gradient borders / theme switching | Swing Look&Feel |

### Context providers — standalone vs IDE

| Provider | Standalone | IDE |
|----------|-----------|-----|
| `@file`, `@folder`, `@grep`, `@diff`, `@commit` | ✅ (standalone wersje) | ✅ |
| `@codebase`, `@docs` | ✅ (standalone wersje) | ✅ |
| `@clipboard`, `@url` | ✅ (platformowo-niezależne) | ✅ |
| `@current`, `@open_files`, `@recent` | ❌ IDE_ONLY | ✅ |
| `@problems`, `@terminal` | ❌ IDE_ONLY | ✅ |

---

## 11. Zasady bezpieczeństwa

### Reguły dla developerów

1. **NIE ZMIENIAJ AgentTurnLoop** — najkruchszy komponent (3 fix commity). Multi-agent działa OBOK niego, nie WEWNĄTRZ.
2. **Backward compatibility jest obowiązkowa** — każda zmiana w `GlobalMetrics`, `CoreApiRouter`, `BaseContextProvider` musi działać identycznie dla istniejącego kodu.
3. **Testy PRZED zmianami** — najpierw test, potem zmiana, potem weryfikacja.
4. **Nowe pliki > zmiany w istniejących** — 90% implementacji to nowe pliki (zerowe ryzyko regresji).
5. **Jeden PR = jeden etap** — nie mieszaj etapów.
6. **Source sets, nie przenoszenie plików** — zachowuje działający build.
7. **Zawsze sprawdź CI** po każdej zmianie.
8. **Nie naprawiaj brute force** — cofnij, zrozum przyczynę, napraw systemowo. Nigdy `!!`, `catch (e: Exception) {}`, `@Suppress`.

### Concurrent access — co jest bezpieczne

Potwierdzone analizą kodu — **NIE wymagają zmian:**
- `ToolRegistry` — read-only, ConcurrentHashMap
- `LLMClient` — stateless
- `PathSandbox` — brak stanu
- `ConfigService` — cache z ConcurrentHashMap
- `MCPManager` — ConcurrentHashMap per-project
- `SubagentRegistry` — read-only po inicjalizacji
- `FileAnalyzerService` — cache keyed by file path

### Singletony wymagające per-agent tracking

| Singleton | Problem | Rozwiązanie |
|-----------|---------|-------------|
| `GlobalMetrics._currentOperation` | Jeden StateFlow nadpisywany | Per-agent `AgentMetrics` + backward compat |
| `GlobalMetrics._isCancelled` | Jeden AtomicBoolean | Per-agent cancellation flags |
| `OllamaRequestGate` | Semaphore(1) per endpoint | Konfigurowalny `maxConcurrentPerEndpoint` |

---

## 12. Build i testowanie

```bash
# Buduj CLI
./gradlew :cli:build              # kompilacja + testy + installDist

# Uruchom TUI
./cli/build/install/cli/bin/cli --project /ścieżka/do/projektu
./cli/build/install/cli/bin/cli -p . --mode AGENT --model ollama/qwen2.5-coder:7b --no-egress

# Headless mode
./cli/build/install/cli/bin/cli -p . --headless --prompt "Explain architecture"
./cli/build/install/cli/bin/cli -p . --headless --multi-agent agents.yaml

# Testy
./gradlew :core:test              # Core bez IntelliJ
./gradlew :cli:test               # CLI + TUI (~171 testów)
./gradlew test                    # Pełny build z pluginem
./gradlew :cli:test --tests "pl.jclab.refio.cli.tui.rendering.*"  # konkretny pakiet

# Weryfikacja braku Compose
./gradlew :cli:dependencies | grep -i compose  # powinno być puste
```

### CLI options

| Opcja | Opis |
|-------|------|
| `--project`, `-p` | Ścieżka do projektu (default: `.`) |
| `--mode`, `-m` | Tryb: CHAT, PLAN, AGENT (default: CHAT) |
| `--model` | Override modelu LLM |
| `--headless` | Bez GUI (stdout only) |
| `--prompt` | Prompt dla headless mode |
| `--multi-agent` | Plik YAML z definicją multi-agent |
| `--no-egress` | Blokuj cloud LLM providers |

---

## Appendix: Historia iteracji

| Iter | Zakres | Kluczowe deliverables |
|------|--------|----------------------|
| 1 | Etapy 1, 2, 7, 8, 9 | Testy P0, ProjectHandle, GlobalMetrics, AgentEventBus, MultiAgentRunner |
| 2 | Etapy 2, 3, 8 | Gradle modularyzacja, IntelliJProjectHandle, AgentEventSqlRepository |
| 3 | Etap 4 + testy P1 | StandaloneCoreBootstrap, PlanningServiceTest, SnapshotServiceTest |
| 4 | Etapy 5, 6, 10 | Compose Desktop GUI, interleaved chat, multi-agent GUI |
| 5 | Backlog P2 | MultiAgentRunner → CoreApiRouter API, CHANGELOG |
| 6 | Bug fixes | PathSandbox fix (181 tests), headless mode, Compose UI tests |
| 7 | Features | EventBus wiring, `--multi-agent` CLI, ConfigRouter reset |
| 8 | Context providers | 7 standalone providerów, 5 IDE_ONLY, help dialog |
| 9 | Finalizacja | `resultSummary` wiring, ostatni TODO |
| 10 | Compose → TUI | Usunięty Compose, TUI z Mordant+JLine3, Clikt 5.x upgrade |
| 11 | TUI split-pane | Layout 55/45, TuiRenderBuffer, 7 tab views |
| 12 | TUI streaming | Debounce fix, TuiSettingsScreen (11 tabs), input flicker fix |
| 13 | TUI polish | F8:Settings, stateFlow merge (20 flows), resize responsive |
| 14 | TUI framebuffer | TuiScreenBuffer, TuiLogSink, API/RAG data wiring |
| 15 | Plugin patterns | Streaming debounce 500ms, auto-refresh 30s, RAG stats |
| 16 | Content parser | TuiContentParser, code blocks, bubble router |
| 17 | History + export | listTasks(), `/model`, `/export`, `/history-delete` |
| 18 | Steps + help | WorkflowListener steps, `/help` command |
| 19 | Approval + metrics | Plan approval overlay, per-message token/cost |
| 20 | Validator + final | TuiContextValidator, GFM tables, `/resend` — 171 testów, 0 failures |
