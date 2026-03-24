# 0001 — Refio Next Turn: Standalone CLI + Multi-Agent + Web-Ready

> **Data:** 2026-03-23
> **Status:** Analiza wstepna (Draft)
> **Autor:** Analiza architektoniczna i biznesowa

---

## Spis tresci

1. [Streszczenie wykonawcze](#1-streszczenie-wykonawcze)
2. [Stan obecny — audyt architektoniczny](#2-stan-obecny--audyt-architektoniczny)
3. [Cel 1: Standalone CLI z GUI](#3-cel-1-standalone-cli-z-gui)
4. [Cel 2: Parametry command-line i tryb standalone](#4-cel-2-parametry-command-line-i-tryb-standalone)
5. [Cel 3: Rozwiazywanie zadan kursowych i benchmarki](#5-cel-3-rozwiazywanie-zadan-kursowych-i-benchmarki)
6. [Cel 4: GUI przyjazne dla nie-programistow](#6-cel-4-gui-przyjazne-dla-nie-programistow)
7. [Cel 5: Multi-agentowość — zalozenia wstepne](#7-cel-5-multi-agentowość--zalozenia-wstepne)
8. [Cel 6: Przyszla wersja Web (React)](#8-cel-6-przyszla-wersja-web-react)
9. [Plan modularyzacji — rozdzielenie core od IntelliJ](#9-plan-modularyzacji--rozdzielenie-core-od-intellij)
10. [Wybor technologii GUI](#10-wybor-technologii-gui)
11. [Architektura docelowa](#11-architektura-docelowa)
12. [Fazy realizacji](#12-fazy-realizacji)
13. [Ryzyka i mitigacje](#13-ryzyka-i-mitigacje)
14. [Zalaczniki — macierz zmian plikow](#14-zalaczniki--macierz-zmian-plikow)

---

## 1. Streszczenie wykonawcze

Refio to plugin IntelliJ (~346 plikow Kotlin, ~118K LOC) implementujacy lokalnego asystenta AI z trzema trybami pracy (Chat, Plan, Agent). Projekt posiada dobrze wydzielona warstwe `core/` (240 plikow) ktora realizuje calą logike biznesowa, ale jest nadal sprzegnięta z IntelliJ Platform w 17 plikach (glownie context providers i przekazywanie obiektu `Project`).

Niniejszy dokument analizuje 6 celow strategicznych:

| # | Cel | Zlożoność | Priorytet |
|---|-----|-----------|-----------|
| 1 | Standalone CLI z GUI desktopowym | **Wysoka** — wymaga modularyzacji | P0 |
| 2 | Parametry command-line | **Niska** — CLI parser + config | P0 |
| 3 | Zadania kursowe + benchmarki | **Srednia** — nowy modul runner | P1 |
| 4 | GUI dla nie-programistow | **Srednia** — UX redesign | P1 |
| 5 | Multi-agentowość | **Bardzo wysoka** — nowa architektura | P2 |
| 6 | Wersja Web (React) | **Wysoka** — API HTTP + frontend | P3 (przygotowanie teraz) |

**Glowna rekomendacja architektoniczna:** Rozdzielenie projektu na moduly Gradle:
- `:core` — czysta logika biznesowa (0 zaleznosci od IntelliJ)
- `:cli` — standalone CLI z Compose Multiplatform Desktop GUI
- `:intellij-plugin` — obecny plugin (cienka warstwa UI nad core)
- `:web-api` — Ktor HTTP server (przyszlosc, dla wersji React)

---

## 2. Stan obecny — audyt architektoniczny

### 2.1 Struktura plikow

```
src/main/kotlin/pl/jclab/refio/
├── core/           # 240 plikow — logika biznesowa
│   ├── api/        # CoreApiRouter + 9 domain routerow
│   ├── services/   # 35 serwisow (turn, context, analysis, RAG, monitoring)
│   ├── llm/        # 10 plikow — 8 adapterow LLM
│   ├── tools/      # 12 narzedzi + security
│   ├── db/         # SQLite/Exposed ORM, 12 repo, migracje
│   ├── context/    # 14 context providerow + MCP
│   ├── workflow/   # Orchestracja: IntentRouter, executory
│   ├── subagents/  # System subagentow (21 built-in)
│   ├── config/     # Hierarchiczna konfiguracja YAML
│   └── models/     # DTO
├── ui/             # 70 plikow — Swing/IntelliJ UI
├── services/       # 20 plikow — plugin services (IntelliJ-specific)
├── actions/        # 8 plikow — IDE actions
└── api/            # 6 plikow — modele sesji/wiadomosci
```

### 2.2 Sprężenie z IntelliJ Platform

**Krytyczne — 17 plikow w `core/` importuje `com.intellij.*`:**

| Kategoria | Pliki | Typ zaleznosci | Trudnosc oddzielenia |
|-----------|-------|----------------|----------------------|
| Przekazywanie `Project` | CoreApiRouter, ChatService, PlanningService, ContextService, AgentRouter, SubagentRouter | `com.intellij.openapi.project.Project` jako nullable parametr | **Niska** — wystarczy interfejs abstrakcyjny |
| Context Providers IDE | 8 providerow (CurrentFile, OpenFiles, RecentFiles, Terminal, Problems, GitDiff, GrepSearch, File) | FileEditorManager, ChangeListManager, WolfTheProblemSolver, etc. | **Srednia** — trzeba stworzyc alternatywne implementacje |
| ContextProviderRegistry | 1 plik | PluginManagerCore, PluginId | **Niska** — warunkowa inicjalizacja |
| ProjectStartupActivity | 1 plik | ProjectActivity | **Niska** — przenosimy do modulu plugin |

**Wazne:** Commit `3d94c03` juz rozpoczal prace nad usuwaniem zaleznosci od IntelliJ. Wiele referencji jest juz `nullable` (`ideProject: Project? = null`), co ulatwia separacje.

**Istniejace mechanizmy gotowe na standalone:**
- `ContextProviderRegistry.initialize(isIdeEnvironment: Boolean = true)` — juz wspiera tryb non-IDE, pomijajac IDE-only providerow
- `BaseContextProvider.ContextProviderEnvironment` enum z wartosciami `ANY` i `IDE_ONLY` — providery sa juz oznaczone
- `UIAdapter` interface w `core/api/` — abstrakcja platformy UI, gotowa na implementacje CLI
- `CoreConnectionManager.getDbPath()` — jedyne uzycie `PathManager` (IntelliJ) do infrastruktury, latwo zastepowalne

### 2.3 Zaleznosci zewnetrzne (build.gradle.kts)

| Zaleznosc | Uzywana w | Przenaszalna do standalone? |
|-----------|-----------|----------------------------|
| Ktor Server 2.3.7 | Core (embedded, planowany HTTP) | Tak |
| Ktor Client 2.3.7 | Core (LLM adaptery) | Tak |
| Exposed ORM 0.46.0 | Core (database) | Tak |
| SQLite JDBC 3.44.1.0 | Core (storage) | Tak |
| Gson 2.10.1 | Core (JSON) | Tak |
| kotlinx-serialization 1.6.2 | Core (serialization) | Tak |
| KAML 0.55.0 | Core (YAML config) | Tak |
| Jsoup 1.17.2 | Core (HTML parsing) | Tak |
| HtmlUnit 4.20.0 | Core (doc crawling) | Tak |
| PDFBox 2.0.30 | Core (PDF support) | Tak |
| Caffeine 3.1.8 | Core (cache) | Tak |
| CommonMark 0.21.0 | UI (markdown rendering) | Tak |
| IntelliJ Platform SDK | UI + czesciowo Core | **Nie** — do oddzielenia |
| kotlinx-coroutines | Dostarczone przez IntelliJ | Trzeba dodac jawnie |

**Wniosek:** ~95% zaleznosci core jest juz przenaszalnych. Brakuje jawnej zaleznosci od `kotlinx-coroutines-core` (dostarczonej przez IntelliJ Platform).

### 2.4 Baza danych i konfiguracja

- **DatabaseFactory:** Sciezka DB okreslana przez `projectRoot` (String) — **przenaszalna**.
- **HierarchicalConfigLoader:** Laduje z `~/.refio/config.yaml` i `<project>/.refio/config.yaml` — **przenaszalna**.
- **ConfigService:** Klucz-wartosc w SQLite z zakresem APP/PROJECT/TASK — **przenaszalny**.
- **CoreConnectionManager:** Zalezy od `com.intellij.openapi.components.Service` — wymaga alternatywy dla standalone.

### 2.5 System subagentow — obecne ograniczenia

Aktualny system subagentow:
- 21 wbudowanych definicji (.md z YAML frontmatter)
- **Wykonanie sekwencyjne** — `InvokeSubagentTool` wywoluje jednego subagenta na raz
- **Brak rownoleglosci** — subagenty nie moga dzialac jednoczesnie
- **Brak komunikacji miedzy agentami** — kazdy subagent dziala w izolacji
- **Brak event bus** — wyniki zwracane synchronicznie
- `GlobalMetrics` to singleton — nie wspiera wielu rownoczesnych sesji agentowych
- `OllamaRequestGate` uzawa semafora z 1 permitem — blokuje rownolegle zapytania do Ollama

---

## 3. Cel 1: Standalone CLI z GUI

### 3.1 Wymagania

- Aplikacja desktopowa uruchamiana z terminala
- Uklad 2-kolumnowy: 2/3 chat, 1/3 status + lista taskow
- Brak zaleznosci od IntelliJ IDE
- Wygladajaca podobnie do wersji pluginowej

### 3.2 Rekomendacja technologiczna: Compose Multiplatform Desktop

| Kryterium | Compose Multiplatform | JavaFX | Swing (standalone) |
|-----------|----------------------|--------|-------------------|
| Dojrzalosc (2026) | Stabilna (1.7+) | Stabilna, ale maleje ecosystem | Stabilna, archaiczna |
| Deklaratywne UI | Tak (React-like) | Nie (FXML/imperatywne) | Nie |
| Chat interface | Naturalne (LazyColumn) | Mozliwe, wiecej pracy | Mozliwe, duzo pracy |
| Cross-platform | Win/Mac/Linux + WASM | Win/Mac/Linux | Win/Mac/Linux |
| Ściezka do Web | Compose for Web/WASM | Brak | Brak |
| Code sharing z React | Compose WASM lub API+React | Tylko API+React | Tylko API+React |
| Packaging | jpackage, Conveyor | jpackage | jpackage |
| Krzywa uczenia (zespol Kotlin) | Niska | Srednia | Niska |

**Rekomendacja: Compose Multiplatform Desktop** z kilku powodow:
1. **Deklaratywny model** idealny do chat UI (lista wiadomosci, streaming)
2. **JetBrains ecosystem** — natywna integracja z Kotlin, ten sam producent co IntelliJ
3. **Sciezka do WASM** — Compose for Web pozwala wspoldzielic UI z wersja webowa
4. **Nowoczesny look** — Material Design 3, latwiejszy do stylizowania dla nie-programistow

### 3.3 Architektura standalone

```
┌──────────────────────────────────────────────────────┐
│  CLI Entry Point (main.kt + Clikt parser)            │
│  --project /path --mode agent --model gpt-5.1        │
└──────────────────┬───────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────┐
│  Compose Desktop GUI                                  │
│  ┌─────────────────────────┬────────────────────────┐ │
│  │  Chat Panel (2/3)       │ Status Panel (1/3)     │ │
│  │  - Message list         │ - Task list            │ │
│  │  - Streaming display    │ - Agent status         │ │
│  │  - Code blocks          │ - Token/cost meters    │ │
│  │  - Input + mode select  │ - Tool execution log   │ │
│  │  - @mentions            │ - File changes         │ │
│  └─────────────────────────┴────────────────────────┘ │
└──────────────────┬───────────────────────────────────┘
                   │ (in-process)
┌──────────────────▼───────────────────────────────────┐
│  Core Module (bez IntelliJ)                           │
│  CoreApiRouter → Services → DB → LLM                  │
└──────────────────────────────────────────────────────┘
```

---

## 4. Cel 2: Parametry command-line i tryb standalone

### 4.1 Proponowany interfejs CLI

```bash
# Tryb interaktywny z GUI
refio --project /path/to/project

# Tryb interaktywny z wyborem trybu
refio --project . --mode agent --model anthropic/claude-sonnet-4-6

# Tryb headless (bez GUI, do automatyzacji i benchmarkow)
refio --headless --project . --mode agent \
  --prompt "Zaimplementuj endpoint REST dla users" \
  --output result.json

# Tryb benchmark
refio benchmark --suite coding-tasks.yaml --output results/

# Tryb kursowy (interaktywny z podpowiedziami)
refio course --task task-definition.yaml --project .

# Konfiguracja
refio config --set providers.openai.api_key=sk-xxx
refio config --show
```

### 4.2 Biblioteka CLI

**Rekomendacja: Clikt** (JetBrains/ajalt) — najpopularniejszy Kotlin CLI parser.

```kotlin
class RefioCommand : CliktCommand(name = "refio") {
    val project by option("--project", "-p").path().default(Path("."))
    val mode by option("--mode", "-m").enum<TaskMode>().default(TaskMode.CHAT)
    val model by option("--model")
    val provider by option("--provider")
    val headless by option("--headless").flag()
    val prompt by option("--prompt")
    val config by option("--config").path()
    val noEgress by option("--no-egress").flag()
    val thinking by option("--thinking").flag()

    override fun run() {
        if (headless) {
            HeadlessRunner(project, mode, model, provider, prompt, noEgress, thinking).run()
        } else {
            ComposeDesktopApp(project, mode, model, provider, noEgress, thinking).launch()
        }
    }
}
```

### 4.3 Tryb headless

Kluczowy dla automatyzacji i benchmarkow. Wynik na stdout (JSON) lub do pliku:

```kotlin
data class HeadlessResult(
    val success: Boolean,
    val output: String,
    val filesChanged: List<String>,
    val tokensUsed: TokenUsage,
    val cost: Double,
    val durationMs: Long,
    val steps: List<StepSummary>
)
```

---

## 5. Cel 3: Rozwiazywanie zadan kursowych i benchmarki

### 5.1 Task Runner

Nowy modul do sekwencyjnego/rownoleglego wykonywania zadan zdefiniowanych w YAML:

```yaml
# coding-tasks.yaml
suite:
  name: "Kurs AI-Assisted Development"
  version: "1.0"

tasks:
  - id: task-001
    name: "REST API endpoint"
    description: "Zaimplementuj CRUD endpoint dla entity User"
    project_template: templates/spring-boot-starter
    mode: agent
    model: anthropic/claude-sonnet-4-6
    max_turns: 20
    timeout: 300s
    validation:
      - type: compilation
        command: "./gradlew build"
      - type: tests
        command: "./gradlew test"
      - type: file_exists
        paths: ["src/main/kotlin/com/example/UserController.kt"]
    scoring:
      - metric: tests_passed
        weight: 0.5
      - metric: code_quality  # detekt score
        weight: 0.2
      - metric: cost
        weight: 0.15
        lower_is_better: true
      - metric: time
        weight: 0.15
        lower_is_better: true
```

### 5.2 Benchmark Framework

```
refio benchmark \
  --suite benchmarks/swe-bench-lite.yaml \
  --models "anthropic/claude-sonnet-4-6,openai/gpt-5.1" \
  --parallel 4 \
  --output results/2026-03-23/ \
  --format json,csv,markdown
```

Wynik:
```json
{
  "suite": "swe-bench-lite",
  "timestamp": "2026-03-23T10:00:00Z",
  "results": [
    {
      "task_id": "task-001",
      "model": "claude-sonnet-4-6",
      "status": "PASSED",
      "score": 0.85,
      "tokens_in": 12500,
      "tokens_out": 3400,
      "cost_usd": 0.048,
      "duration_ms": 45000,
      "turns": 5,
      "tools_used": ["read_file", "grep_search", "code_editing", "run_terminal_command"],
      "validation": {
        "compilation": true,
        "tests_passed": 12,
        "tests_total": 12
      }
    }
  ],
  "summary": {
    "total_tasks": 50,
    "passed": 42,
    "failed": 8,
    "avg_cost": 0.052,
    "avg_duration_ms": 38000
  }
}
```

### 5.3 Tryb kursowy

Specjalny tryb interaktywny z GUI, ktory:
- Wczytuje definicje zadania i wyswietla opis
- Prowadzi uzytkownika krok po kroku
- Pokazuje postep i podpowiedzi
- Waliduje rozwiazanie na biezaco
- Generuje raport po zakonczeniu

---

## 6. Cel 4: GUI przyjazne dla nie-programistow

### 6.1 Zasady UX

1. **Progresywne odkrywanie** — domyslnie uproszczony widok, zaawansowane opcje ukryte
2. **Wizualna informacja zwrotna** — animacje statusu, progress bary, kolorowe statusy
3. **Jezyk naturalny** — komunikaty w jezyku uzytkownika (pl/en), bez zargonu technicznego
4. **Guided workflow** — podpowiedzi co mozna zrobic, sugerowane akcje
5. **Bezpieczne domyslne** — tryb CHAT domyslny, AGENT wymaga swiadomego wyboru

### 6.2 Uklad GUI (2-kolumnowy)

```
┌────────────────────────────────────────────┬──────────────────────┐
│  CHAT (2/3 szerokosc)                      │ PANEL (1/3)          │
│                                            │                      │
│  ┌──────────────────────────────────────┐  │  ┌────────────────┐  │
│  │ [Asystent] Witaj! Opisz co chcesz   │  │  │ Status: Gotowy │  │
│  │ zrobic, a ja Ci pomoge.             │  │  │ Tryb: Chat     │  │
│  └──────────────────────────────────────┘  │  │ Model: Claude  │  │
│                                            │  │ Koszt: $0.00   │  │
│  ┌──────────────────────────────────────┐  │  └────────────────┘  │
│  │ [Ty] Stworz strone HTML z           │  │                      │
│  │ formularzem kontaktowym              │  │  ┌────────────────┐  │
│  └──────────────────────────────────────┘  │  │ Zadania        │  │
│                                            │  │ ☑ Analiza      │  │
│  ┌──────────────────────────────────────┐  │  │ ⟳ Tworzenie   │  │
│  │ [Asystent] Swietnie! Stworze...     │  │  │ ○ Walidacja    │  │
│  │ ```html                              │  │  └────────────────┘  │
│  │ <!DOCTYPE html>...                   │  │                      │
│  │ ```                                  │  │  ┌────────────────┐  │
│  │ [▶ Zastosuj] [👁 Podglad]           │  │  │ Zmienione pliki│  │
│  └──────────────────────────────────────┘  │  │ + index.html   │  │
│                                            │  │ + style.css    │  │
│  ┌──────────────────────────────────────┐  │  └────────────────┘  │
│  │ 💬 Opisz co chcesz zrobic...        │  │                      │
│  │ [Chat ▾] [Claude ▾] [Wyslij ➤]     │  │  ┌────────────────┐  │
│  └──────────────────────────────────────┘  │  │ Agenci  ●●○○   │  │
│                                            │  │ Analyst: gotowy │  │
│                                            │  │ Coder: dziala.. │  │
│                                            │  └────────────────┘  │
└────────────────────────────────────────────┴──────────────────────┘
```

### 6.3 Uproszczenia wzgledem wersji developerskiej

| Wersja developerska (IntelliJ) | Wersja uproszczona (Standalone) |
|---|---|
| Tryby: Chat / Plan / Agent | "Rozmowa" / "Analiza" / "Wykonaj" |
| @file, @codebase, @grep... | "Dolacz plik", "Szukaj w projekcie" |
| Execution mode: AUTO / INTERACTIVE | "Automatycznie" / "Pytaj przed zmianami" |
| Tool calls visible | "Agent pracuje nad..." z uproszczonym logiem |
| Token counter | "Zuzycie: 💰 niskie / srednie / wysokie" |
| Model selector (tekst) | Model selector z opisami i rekomendacjami |

### 6.4 Onboarding

Przy pierwszym uruchomieniu:
1. Kreator konfiguracji (wybor providera, klucz API)
2. Interaktywny tutorial (3 proste zadania demonstrujace tryby)
3. Tooltips i ikony informacyjne przy kazdym elemencie

---

## 7. Cel 5: Multi-agentowość — zalozenia wstepne

### 7.1 Obecne ograniczenia (do zmiany)

| Komponent | Obecny stan | Wymagana zmiana |
|-----------|------------|-----------------|
| `InvokeSubagentTool` | Sekwencyjne wywolanie 1 subagenta | Rownolegle wywolania wielu agentow |
| `GlobalMetrics` | Singleton, jedna sesja | Per-agent metrics z agregacja |
| `OllamaRequestGate` | Semafor(1) | Konfigurowalny pool requestow |
| `WorkflowOrchestrator` | Jeden intent na raz | DAG-based execution z rownolegloscia |
| Subagent Communication | Brak | Event bus miedzy agentami |
| User Confirmation | `UserInteraction` — 1 pytanie na raz | Kolejka potwierdzania z priorytetami |
| Context Sharing | Izolowane konteksty | Shared working memory + scope |

### 7.2 Architektura multi-agentowa

#### 7.2.1 Model koncepcyjny

```
                    ┌─────────────────────────────────────┐
                    │       Orchestrator Agent             │
                    │  (dekomponuje zadanie, przydziela)   │
                    └──────────┬──────────────────────────┘
                               │ tworzy agentow
                    ┌──────────▼──────────────────────────┐
                    │          Agent Registry               │
                    │  (zarzadza cyklem zycia agentow)     │
                    └──────────┬──────────────────────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
    ┌─────▼─────┐       ┌─────▼─────┐       ┌─────▼─────┐
    │  Agent A   │       │  Agent B   │       │  Agent C   │
    │ (Analyst)  │◄─────►│  (Coder)   │◄─────►│ (Reviewer) │
    └─────┬─────┘       └─────┬─────┘       └─────┬─────┘
          │                    │                    │
          └────────────────────┼────────────────────┘
                               │
                    ┌──────────▼──────────────────────────┐
                    │         Event Bus                     │
                    │  (komunikacja miedzy agentami)        │
                    └──────────┬──────────────────────────┘
                               │
                    ┌──────────▼──────────────────────────┐
                    │     User Confirmation Queue           │
                    │  (zatwierdzanie krytycznych akcji)    │
                    └─────────────────────────────────────┘
```

#### 7.2.2 Agent jako jednostka wykonawcza

```kotlin
// Nowy interfejs agenta
interface Agent {
    val id: AgentId
    val definition: AgentDefinition  // rozszerzony SubagentDefinition
    val state: StateFlow<AgentState>  // IDLE, RUNNING, WAITING_USER, DONE, FAILED
    val events: SharedFlow<AgentEvent>  // emitowane zdarzenia

    suspend fun start(task: AgentTask)
    suspend fun handleEvent(event: AgentEvent)
    suspend fun cancel()
}

enum class AgentState {
    IDLE, STARTING, RUNNING, WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL, BLOCKED, COMPLETED, FAILED, CANCELLED
}
```

#### 7.2.3 Event Bus — komunikacja miedzy agentami

```kotlin
// Zdarzenia agentowe
sealed interface AgentEvent {
    val sourceAgentId: AgentId
    val timestamp: Instant
    val correlationId: String  // do sledzenia lancucha zdarzen

    // Zdarzenia rezultatow
    data class TaskCompleted(
        override val sourceAgentId: AgentId,
        val result: AgentResult,
        val artifacts: List<Artifact>,  // pliki, snippety, analizy
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent

    data class TaskFailed(
        override val sourceAgentId: AgentId,
        val error: AgentError,
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent

    // Zdarzenia komunikacyjne
    data class InformationRequest(
        override val sourceAgentId: AgentId,
        val targetAgentId: AgentId?,  // null = broadcast
        val query: String,
        val context: Map<String, Any>,
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent

    data class InformationResponse(
        override val sourceAgentId: AgentId,
        val targetAgentId: AgentId,
        val response: String,
        val artifacts: List<Artifact>,
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent

    // Zdarzenia koordynacyjne
    data class ArtifactProduced(
        override val sourceAgentId: AgentId,
        val artifact: Artifact,  // plik, analiza, plan
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent

    data class ConflictDetected(
        override val sourceAgentId: AgentId,
        val conflictingAgentId: AgentId,
        val resource: String,  // np. sciezka pliku
        val description: String,
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent

    // Zdarzenia wymagajace potwierdzenia uzytkownika
    data class ApprovalRequired(
        override val sourceAgentId: AgentId,
        val action: PendingAction,
        val risk: RiskLevel,  // LOW, MEDIUM, HIGH, CRITICAL
        val description: String,
        override val timestamp: Instant,
        override val correlationId: String
    ) : AgentEvent
}

// Event Bus
interface AgentEventBus {
    val events: SharedFlow<AgentEvent>

    suspend fun publish(event: AgentEvent)
    fun subscribe(agentId: AgentId, filter: (AgentEvent) -> Boolean): Flow<AgentEvent>
    fun subscribeToAgent(targetAgentId: AgentId): Flow<AgentEvent>
}

// Implementacja oparta na Kotlin Coroutines
class CoroutineAgentEventBus : AgentEventBus {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 100,  // bufor ostatnich 100 zdarzen
        extraBufferCapacity = 256
    )
    override val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    override suspend fun publish(event: AgentEvent) {
        _events.emit(event)
    }

    override fun subscribe(agentId: AgentId, filter: (AgentEvent) -> Boolean): Flow<AgentEvent> {
        return events.filter(filter)
    }

    override fun subscribeToAgent(targetAgentId: AgentId): Flow<AgentEvent> {
        return events.filter {
            it.sourceAgentId == targetAgentId ||
            (it is AgentEvent.InformationRequest && it.targetAgentId == targetAgentId)
        }
    }
}
```

#### 7.2.4 Zatwierdzanie przez uzytkownika

```kotlin
// Kolejka potwierdzania
interface UserConfirmationQueue {
    val pendingApprovals: StateFlow<List<PendingApproval>>

    suspend fun requestApproval(
        agentId: AgentId,
        action: PendingAction,
        risk: RiskLevel,
        description: String,
        timeout: Duration = Duration.INFINITE
    ): ApprovalResult

    suspend fun approve(approvalId: String)
    suspend fun reject(approvalId: String, reason: String?)
    suspend fun approveAll(agentId: AgentId)  // zaufaj agentowi
}

data class PendingApproval(
    val id: String,
    val agentId: AgentId,
    val agentName: String,
    val action: PendingAction,
    val risk: RiskLevel,
    val description: String,
    val timestamp: Instant,
    val autoApproveAfter: Duration?  // opcjonalny auto-approve timeout
)

sealed interface PendingAction {
    data class FileWrite(val path: String, val description: String) : PendingAction
    data class FileDelete(val path: String) : PendingAction
    data class TerminalCommand(val command: String, val cwd: String) : PendingAction
    data class ExternalApiCall(val url: String, val method: String) : PendingAction
    data class SubagentSpawn(val agentType: String, val task: String) : PendingAction
}

enum class RiskLevel {
    LOW,      // odczyt, analiza — auto-approve
    MEDIUM,   // zapis do plikow w projekcie
    HIGH,     // terminal, zewnetrzne API
    CRITICAL  // destrukcyjne operacje, publikacja
}
```

#### 7.2.5 Orchestrator — koordynacja wielu agentow

```kotlin
class MultiAgentOrchestrator(
    private val agentRegistry: AgentRegistry,
    private val eventBus: AgentEventBus,
    private val confirmationQueue: UserConfirmationQueue,
    private val scope: CoroutineScope
) {
    // Dekomponuje zadanie na poddrzewa agentow
    suspend fun execute(task: ComplexTask): OrchestratorResult {
        // 1. Analiza zadania i plan agentow
        val plan = planAgentWorkflow(task)

        // 2. Tworzenie agentow wg planu
        val agents = plan.agents.map { agentSpec ->
            agentRegistry.createAgent(agentSpec)
        }

        // 3. Rownolegle uruchomienie niezaleznych agentow
        val jobs = mutableMapOf<AgentId, Job>()
        for (agent in agents) {
            if (plan.dependenciesOf(agent.id).all { it.isResolved() }) {
                jobs[agent.id] = scope.launch {
                    agent.start(plan.taskFor(agent.id))
                }
            }
        }

        // 4. Reaktywne uruchamianie agentow gdy zaleznosci sa spelnionie
        eventBus.events.collect { event ->
            when (event) {
                is AgentEvent.TaskCompleted -> {
                    // Uruchom agentow czekajacych na ten wynik
                    val unblocked = plan.getUnblockedAgents(event.sourceAgentId)
                    for (agent in unblocked) {
                        jobs[agent.id] = scope.launch {
                            agent.handleEvent(event)  // przekaz rezultat
                            agent.start(plan.taskFor(agent.id))
                        }
                    }
                }
                is AgentEvent.ConflictDetected -> {
                    // Rozwiaz konflikt (np. 2 agenty edytuja ten sam plik)
                    resolveConflict(event)
                }
                is AgentEvent.ApprovalRequired -> {
                    // Przekaz do kolejki uzytkownika
                    confirmationQueue.requestApproval(
                        event.sourceAgentId, event.action, event.risk, event.description
                    )
                }
            }
        }

        return aggregateResults(agents)
    }
}
```

### 7.3 Wymagane modyfikacje istniejacego kodu

| Komponent | Zmiana | Priorytet |
|-----------|--------|-----------|
| `GlobalMetrics` | Zamiana singleton → per-agent metrics + globalna agregacja | P0 |
| `OllamaRequestGate` | Konfigurowalny semafor (1→N) + kolejkowanie per-provider | P0 |
| `InvokeSubagentTool` | Wsparcie dla async invocation + event-based communication | P1 |
| `WorkflowOrchestrator` | Nowy `MultiAgentOrchestrator` obok istniejacego | P1 |
| `TurnGuardrails` | Per-agent guardrails (error tracking, loop detection) | P1 |
| `ToolExecutor` | Wsparcie dla wielu rownoleglych sesji narzedzi | P1 |
| `PathSandbox` | Lock manager — zapobieganie rownoczesnym zapisom do tego samego pliku | P2 |
| `SnapshotService` | Per-agent snapshots z mozliwoscia rollback per agent | P2 |
| `ChatMessageRepository` | Wielowatkowe sesje — agent_id jako dodatkowy klucz | P2 |
| `SubagentDefinition` | Rozszerzenie o: `subscribes_to`, `publishes`, `requires_approval` | P2 |
| Nowy: `AgentEventBus` | Implementacja event bus (SharedFlow-based) | P1 |
| Nowy: `AgentRegistry` | Zarzadzanie cyklem zycia agentow | P1 |
| Nowy: `UserConfirmationQueue` | Kolejka potwierdzania z UI | P1 |
| Nowy: `ConflictResolver` | Rozwiazywanie konfliktow miedzy agentami | P2 |

### 7.4 Implementacja: Actor Pattern z Kotlin Coroutines

Rekomendowany wzorzec implementacji oparty na natywnych prymitywach Kotlin:

```kotlin
// Kazdy agent jako actor z prywatna skrzynka (Channel)
class AgentActor(
    val id: AgentId,
    val definition: AgentDefinition,
    private val eventBus: AgentEventBus,
    private val scope: CoroutineScope
) {
    // Prywatna skrzynka — point-to-point polecenia
    private val mailbox = Channel<AgentCommand>(Channel.BUFFERED)

    // Publiczny stan — UI moze obserwowac
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    fun start() = scope.launch(SupervisorJob()) {
        // Subskrypcja na event bus (broadcast)
        launch {
            eventBus.subscribe(id) { event ->
                event is AgentEvent.InformationRequest &&
                event.targetAgentId == id
            }.collect { event -> handleEvent(event) }
        }

        // Przetwarzanie polecen z mailbox (sekwencyjnie)
        for (command in mailbox) {
            _state.value = AgentState.RUNNING
            try {
                executeCommand(command)
            } catch (e: Exception) {
                _state.value = AgentState.FAILED
                eventBus.publish(AgentEvent.TaskFailed(id, AgentError(e)))
            }
        }
    }

    suspend fun send(command: AgentCommand) = mailbox.send(command)
}
```

| Prymityw Kotlin | Semantyka | Zastosowanie w multi-agent |
|-----------------|-----------|---------------------------|
| `Channel` | Point-to-point, kolejka | Skrzynka agenta (actor mailbox) |
| `SharedFlow` | Broadcast, jeden-do-wielu | Event bus, status updates |
| `StateFlow` | Ostatnia wartosc, broadcast | Obserwacja stanu agenta przez UI |
| `SupervisorJob` | Izolacja bledow | Awaria jednego agenta nie zabija innych |

### 7.5 Wzorce komunikacji agentow

```
Wzorzec 1: Pipeline (sekwencyjny)
  Analyst → Coder → Reviewer → Fixer

Wzorzec 2: Fan-out / Fan-in (rownolegle)
  Orchestrator ──┬── Agent A (feature X)
                 ├── Agent B (feature Y)  ──→ Merger
                 └── Agent C (tests)

Wzorzec 3: Event-driven (reaktywny)
  Agent A produkuje artifact → Event Bus → Agent B reaguje

Wzorzec 4: Collaborative (wspolpraca)
  Agent A ←──── Event Bus ────→ Agent B
  (pytania, odpowiedzi, konsultacje)
```

---

## 8. Cel 6: Przyszla wersja Web (React)

### 8.1 Przygotowanie teraz

Decyzje architektoniczne podejmowane teraz musza uwzgledniac przyszla wersje webowa:

1. **Core jako niezalezny modul** — zero zaleznosci od UI framework
2. **API HTTP-ready** — CoreApiRouter juz jest projektowany pod HTTP (tech-stack.md §5.2)
3. **Streaming via WebSocket** — obecny `StreamCallback` latwo mapuje sie na WebSocket
4. **Serialization** — Gson/kotlinx-serialization juz obecne, DTO sa JSON-ready
5. **Autentykacja** — dodac token-based auth od poczatku (przyda sie dla web)

### 8.2 Architektura docelowa z React

```
┌─────────────────────────────┐     ┌─────────────────────────────┐
│  React Frontend             │     │  Compose Desktop            │
│  (SPA w przegladarce)       │     │  (standalone app)           │
└──────────┬──────────────────┘     └──────────┬──────────────────┘
           │ HTTP/WebSocket                     │ in-process
           │                                    │
┌──────────▼────────────────────────────────────▼──────────────────┐
│  Ktor HTTP Server + WebSocket                                     │
│  /api/v1/tasks, /api/v1/chat, /ws/stream                         │
└──────────┬───────────────────────────────────────────────────────┘
           │
┌──────────▼───────────────────────────────────────────────────────┐
│  Core Module                                                      │
│  CoreApiRouter → Services → DB → LLM                              │
└──────────────────────────────────────────────────────────────────┘
```

### 8.3 API endpoints (planowane)

```
POST   /api/v1/tasks                    # Tworzenie taska
GET    /api/v1/tasks                    # Lista taskow
GET    /api/v1/tasks/:id                # Szczegoly taska
POST   /api/v1/tasks/:id/chat          # Wyslanie wiadomosci chat
POST   /api/v1/tasks/:id/plan          # Wygenerowanie planu
POST   /api/v1/tasks/:id/execute       # Wykonanie kroku agenta
GET    /api/v1/tasks/:id/messages       # Historia wiadomosci
WS     /ws/tasks/:id/stream             # Streaming (token-by-token)
WS     /ws/agents/events                # Multi-agent event stream

POST   /api/v1/config                   # Ustawienia
GET    /api/v1/config                   # Odczyt ustawien
POST   /api/v1/rag/index                # Indeksowanie projektu
GET    /api/v1/rag/search               # Wyszukiwanie RAG

# Multi-agent
POST   /api/v1/agents/orchestrate       # Uruchomienie multi-agent workflow
GET    /api/v1/agents                    # Lista aktywnych agentow
GET    /api/v1/agents/:id/state          # Stan agenta
POST   /api/v1/agents/approvals/:id      # Zatwierdzenie/odrzucenie
GET    /api/v1/agents/approvals/pending   # Lista oczekujacych zatwierdzen
WS     /ws/agents/:id/events             # Streaming zdarzen agenta
```

---

## 9. Plan modularyzacji — rozdzielenie core od IntelliJ

### 9.1 Docelowa struktura modulow Gradle

```
refio/
├── settings.gradle.kts          # includes: core, cli, intellij-plugin, web-api
├── core/                        # Modul :core
│   ├── build.gradle.kts         # Kotlin, Exposed, Ktor Client, SQLite, Gson
│   └── src/main/kotlin/
│       └── pl/jclab/refio/core/
│           ├── api/             # CoreApiRouter (bez IntelliJ imports)
│           ├── services/        # Cala logika biznesowa
│           ├── llm/             # Adaptery LLM
│           ├── tools/           # Narzedzia
│           ├── db/              # Baza danych
│           ├── context/         # Context providers (abstrakcyjne)
│           │   ├── providers/   # Standalone implementations (grep, url, docs, codebase)
│           │   └── spi/         # SPI interfejsy dla IDE-specific providerow
│           ├── workflow/        # Orchestracja
│           ├── subagents/       # System subagentow
│           ├── agents/          # NOWY: Multi-agent system
│           │   ├── AgentEventBus.kt
│           │   ├── AgentRegistry.kt
│           │   ├── MultiAgentOrchestrator.kt
│           │   └── UserConfirmationQueue.kt
│           ├── config/          # Konfiguracja
│           └── models/          # DTO
│
├── cli/                         # Modul :cli
│   ├── build.gradle.kts         # Clikt, Compose Desktop, depends on :core
│   └── src/main/kotlin/
│       └── pl/jclab/refio/cli/
│           ├── main.kt          # Entry point
│           ├── ui/              # Compose Desktop UI
│           │   ├── App.kt
│           │   ├── ChatPanel.kt
│           │   ├── StatusPanel.kt
│           │   └── theme/
│           ├── context/         # Standalone context providers (filesystem-based)
│           ├── runner/          # Task runner, benchmark framework
│           └── headless/        # Headless mode
│
├── intellij-plugin/             # Modul :intellij-plugin
│   ├── build.gradle.kts         # IntelliJ Platform SDK, depends on :core
│   └── src/main/kotlin/
│       └── pl/jclab/refio/
│           ├── ui/              # Swing/IntelliJ UI (istniejace)
│           ├── services/        # Plugin services (istniejace)
│           ├── context/         # IDE-specific context providers
│           │   └── intellij/    # CurrentFile, OpenFiles, Problems, Terminal, etc.
│           └── actions/         # IDE actions
│
├── web-api/                     # Modul :web-api (przyszlosc)
│   ├── build.gradle.kts         # Ktor Server, depends on :core
│   └── src/main/kotlin/
│       └── pl/jclab/refio/web/
│           ├── Application.kt   # Ktor server
│           ├── routes/          # HTTP routes
│           └── websocket/       # WebSocket handlers
```

### 9.2 Interfejs abstrakcji `Project` (SPI)

Kluczowy krok — zastapienie `com.intellij.openapi.project.Project` interfejsem:

```kotlin
// core/src/.../core/project/ProjectHandle.kt
interface ProjectHandle {
    val id: String           // deterministyczny hash sciezki
    val name: String         // nazwa katalogu
    val rootPath: Path       // sciezka do projektu
    val configPath: Path     // .refio/config.yaml
    val databasePath: Path   // .refio/database.sqlite
}

// Standalone implementation
class StandaloneProjectHandle(override val rootPath: Path) : ProjectHandle {
    override val id = ProjectIdGenerator.generate(rootPath.toString())
    override val name = rootPath.fileName.toString()
    override val configPath = rootPath.resolve(".refio/config.yaml")
    override val databasePath = rootPath.resolve(".refio/database.sqlite")
}

// IntelliJ implementation (w module intellij-plugin)
class IntelliJProjectHandle(private val project: Project) : ProjectHandle {
    override val id = ProjectIdGenerator.generate(project.basePath ?: "")
    override val name = project.name
    override val rootPath = Path.of(project.basePath ?: "")
    override val configPath = rootPath.resolve(".refio/config.yaml")
    override val databasePath = rootPath.resolve(".refio/database.sqlite")
}
```

### 9.3 Context Provider SPI

```kotlin
// core/src/.../core/context/spi/
interface ContextProviderSPI {
    fun createIDEProviders(project: ProjectHandle): List<BaseContextProvider>
}

// Standalone: zwraca puste listy dla IDE-specific providerow
class StandaloneContextProviderSPI : ContextProviderSPI {
    override fun createIDEProviders(project: ProjectHandle): List<BaseContextProvider> {
        return listOf(
            // Standalone wersje bazujace na filesystem
            StandaloneGitDiffProvider(project),    // git diff via ProcessBuilder
            StandaloneGrepSearchProvider(project),  // ripgrep via ProcessBuilder
            StandaloneFileProvider(project),         // java.nio file operations
        )
        // Brak: CurrentFile, OpenFiles, RecentFiles, Terminal, Problems
        // (nie maja sensu bez IDE)
    }
}
```

### 9.4 Kolejnosc migracji plikow

**Faza 1 — Zero IntelliJ w core (2-3 tygodnie)**

1. Stworzyc `ProjectHandle` interface
2. Zamienic `com.intellij.openapi.project.Project?` → `ProjectHandle` w:
   - `CoreApiRouter` (linia 73)
   - `ChatService` (linia 54)
   - `PlanningService` (linia 55)
   - `ContextService` (import linia 4)
   - `AgentRouter` (import linia 6)
   - `SubagentRouter` (linia 43)
   - `BaseContextProvider` (import linia 3)
3. Wyniesc IDE-specific context providers do modulu `intellij-plugin`
4. Stworzyc `ContextProviderSPI` i `StandaloneContextProviderSPI`
5. Przeniesc `ProjectStartupActivity` do modulu `intellij-plugin`
6. Usunac `PluginManagerCore`/`PluginId` z `ContextProviderRegistry` (zamienic na SPI)
7. Dodac jawna zaleznosc `kotlinx-coroutines-core` do modulu `:core`

**Faza 2 — Multi-module Gradle (1-2 tygodnie)**

1. Stworzyc `settings.gradle.kts` z `include(":core", ":intellij-plugin", ":cli")`
2. Przeniesc pliki do odpowiednich modulow
3. Rozwiazac zaleznosci miedzy modulami
4. Upewnic sie ze `./gradlew :core:test` przechodzi bez IntelliJ Platform

---

## 10. Wybor technologii GUI

### 10.1 Decyzja: Dual approach

**Faza 1 (MVP — szybko):** Compose Multiplatform Desktop dla standalone GUI
**Faza 2 (Web):** Ktor HTTP/WebSocket server + React frontend

Uzasadnienie:
- Compose Desktop daje szybki start — ten sam jezyk (Kotlin), reuse logiki core
- React daje najlepsze GUI webowe — ogromny ecosystem, React AI chat components
- Ktor server juz jest w zaleznosc (Ktor Server 2.3.7 jest w build.gradle.kts)

### 10.2 Alternatywa: Path B — Ktor Backend + React/Tauri (hybrid)

Jezeli wersja Web jest priorytetem w perspektywie 6-12 miesiecy, warto rozwazyc podejscie hybrydowe:

| Aspekt | Opis |
|--------|------|
| Backend | Ktor server (juz w zaleznosc) jako headless service |
| Frontend | React + TypeScript (SPA) — najlepszy ecosystem chatowy |
| Desktop | Tauri v2 (~5MB overhead vs Electron ~100MB) — natywne okno z WebView |
| Web | Ten sam React frontend, zero dodatkowej pracy |
| Trade-off | Dwa jezyki (Kotlin + TypeScript), ale 100% code sharing frontendu |

**Wzorce z branzy:** ChatGPT, Claude.ai, Cursor — wszystkie uzywaja wariantu Ktor/FastAPI backend + React frontend. To sprawdzony pattern dla AI chat applications.

**Rekomendacja:** Jezeli React web jest pewny — Path B. Jezeli spekulatywny/dlugoterminowy — Path A (Compose Multiplatform) dla szybkosci i Kotlin-only stack.

### 10.3 Compose Desktop — kluczowe biblioteki

| Biblioteka | Wersja | Cel |
|------------|--------|-----|
| `org.jetbrains.compose` | 1.7+ | Compose Multiplatform plugin |
| `compose.desktop.currentOs` | - | Runtime desktopowe |
| `compose.material3` | - | Material Design 3 theming |
| `com.github.ajalt.clikt` | 4.x | CLI argument parsing |
| `com.darkrockstudios:mpfilepicker` | - | Natywny dialog plikow |
| `org.jetbrains.jewel` | 0.x | IntelliJ-like theme (opcjonalnie) |

### 10.4 Packaging standalone

| Platforma | Metoda | Format |
|-----------|--------|--------|
| macOS | jpackage / Conveyor | .dmg, .app |
| Windows | jpackage / Conveyor | .msi, .exe |
| Linux | jpackage / Conveyor | .deb, .rpm, .AppImage |
| Uniwersalny | Shadow JAR + launch script | .jar + refio.sh/refio.bat |

Rekomendacja: **Conveyor** (hydraulic.dev) — nowoczesny tool od Hydraulic, natywne pakiety z auto-update.

---

## 11. Architektura docelowa

### 11.1 Diagram komponentow

```
┌─────────────────────────────────────────────────────────────────┐
│                     Frontend Layer                               │
│                                                                  │
│  ┌──────────────┐  ┌──────────────────┐  ┌────────────────────┐ │
│  │  IntelliJ    │  │  Compose Desktop │  │  React Web         │ │
│  │  Plugin      │  │  (CLI)           │  │  (przyszlosc)      │ │
│  │  (Swing)     │  │                  │  │                    │ │
│  └──────┬───────┘  └────────┬─────────┘  └─────────┬──────────┘ │
│         │ in-process        │ in-process            │ HTTP/WS    │
└─────────┼───────────────────┼───────────────────────┼────────────┘
          │                   │                       │
┌─────────▼───────────────────▼───────────────────────▼────────────┐
│                     API Layer                                     │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  CoreApiRouter (in-process) ←──→ Ktor HTTP Server (optional) │ │
│  └──────────────────────────┬───────────────────────────────────┘ │
└─────────────────────────────┼────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────────┐
│                     Core Layer                                    │
│                                                                   │
│  ┌────────────────┐  ┌─────────────────┐  ┌───────────────────┐ │
│  │  Single Agent   │  │  Multi-Agent    │  │  Benchmark        │ │
│  │  Workflow       │  │  Orchestrator   │  │  Runner           │ │
│  │  (istniejacy)   │  │  (nowy)         │  │  (nowy)           │ │
│  └────────┬───────┘  └────────┬────────┘  └────────┬──────────┘ │
│           │                   │                     │            │
│  ┌────────▼───────────────────▼─────────────────────▼──────────┐ │
│  │  Services: Chat, Planning, Agent, Context, RAG, Config      │ │
│  └─────────────────────────────┬───────────────────────────────┘ │
│                                │                                  │
│  ┌─────────────────────────────▼───────────────────────────────┐ │
│  │  Infrastructure: LLM Adapters, Tools, DB, MCP, EventBus    │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 11.2 Przeplywy danych

```
Uzytkownik CLI:
  main.kt → Clikt parser → ComposeDesktopApp.launch()
    → ViewModel (Compose state) → CoreApiRouter.chat()
    → ChatService → LLMClient → AnthropicAdapter
    → StreamCallback → ViewModel.update() → Compose recompose

Uzytkownik IntelliJ:
  SessionManager → CoreApiClient → CoreApiRouter (identyczny flow)

Multi-agent:
  MultiAgentOrchestrator.execute(task)
    → AgentRegistry.createAgent() × N
    → coroutineScope { launch { agent.start() } × N }
    → AgentEventBus collects events
    → UserConfirmationQueue for approvals
    → GUI: ViewModel observes agentStates + events + approvals

Benchmark:
  BenchmarkRunner.run(suite)
    → for task in suite: headless CoreApiRouter session
    → ValidationRunner.validate(result)
    → ScoringEngine.score(result)
    → ReportGenerator.generate(results)
```

---

## 12. Fazy realizacji

### Faza 0: Przygotowanie (1-2 tygodnie)

- [ ] Stworzyc `ProjectHandle` interface i `StandaloneProjectHandle`
- [ ] Zamienic `com.intellij.openapi.project.Project?` na `ProjectHandle` w 6 core plikach
- [ ] Dodac jawne `kotlinx-coroutines-core` do zaleznosci
- [ ] Stworzyc multi-module Gradle setup (`:core`, `:intellij-plugin`)
- [ ] Upewnic sie ze `./gradlew :core:test` dziala bez IntelliJ Platform
- [ ] Wyniesc IDE-specific context providers do `:intellij-plugin`

### Faza 1: Standalone CLI MVP (3-4 tygodnie)

- [ ] Modul `:cli` z Clikt + Compose Desktop
- [ ] Podstawowy 2-kolumnowy layout (chat + status)
- [ ] Tryb Chat — pelna funkcjonalnosc
- [ ] Tryb headless z JSON output
- [ ] Standalone context providers (git diff, grep, file, folder, codebase, url, docs)
- [ ] Konfiguracja z command line i YAML
- [ ] Packaging: Shadow JAR + launch script
- [ ] Testy integracyjne CLI

### Faza 2: Pelna funkcjonalnosc CLI (3-4 tygodnie)

- [ ] Tryby Plan i Agent w GUI
- [ ] Streaming display (animowane tokeny)
- [ ] Code block rendering z syntax highlighting
- [ ] @mention autocomplete
- [ ] Subagent invocation
- [ ] Task runner (YAML definitions)
- [ ] Benchmark framework
- [ ] Natywne packaging (Conveyor)

### Faza 3: GUI dla nie-programistow (2-3 tygodnie)

- [ ] UX redesign — uproszczone etykiety
- [ ] Onboarding wizard
- [ ] Tryb kursowy
- [ ] Wizualna informacja o postepie
- [ ] Tlumaczenia (pl/en)
- [ ] Tooltips i dokumentacja in-app

### Faza 4: Multi-agentowość (4-6 tygodni)

- [ ] `AgentEventBus` (SharedFlow-based)
- [ ] `AgentRegistry` + lifecycle management
- [ ] `UserConfirmationQueue` z GUI
- [ ] `MultiAgentOrchestrator`
- [ ] Per-agent `GlobalMetrics`
- [ ] Konfigurowalny `OllamaRequestGate`
- [ ] Per-agent `TurnGuardrails`
- [ ] Conflict resolution (file locks, merge)
- [ ] GUI: panel agentow z statusami i eventami
- [ ] Rozszerzone definicje subagentow (subscribes_to, publishes)

### Faza 5: Web API preparation (2-3 tygodnie)

- [ ] Modul `:web-api` z Ktor HTTP server
- [ ] REST endpoints (CRUD tasks, chat, config)
- [ ] WebSocket streaming
- [ ] Token-based authentication
- [ ] CORS configuration
- [ ] API documentation (OpenAPI/Swagger)

### Faza 6: React Web Frontend (osobny projekt)

- [ ] React SPA z TypeScript
- [ ] Chat component z streaming
- [ ] 2-kolumnowy layout (responsive)
- [ ] Multi-agent dashboard
- [ ] Deployment (Docker, static hosting)

---

## 13. Ryzyka i mitigacje

| Ryzyko | Prawdopodobienstwo | Wplyw | Mitigacja |
|--------|-------------------|-------|-----------|
| Modularyzacja zlamie istniejace testy | Wysokie | Sredni | CI per-module, testy integracyjne |
| Compose Desktop niestabilne na Linuxie | Niskie (2026) | Wysoki | Fallback: Swing standalone |
| Multi-agent deadlock/livelock | Srednie | Wysoki | TurnGuardrails per-agent, timeout, circuit breaker |
| Konflikt plikow miedzy agentami | Wysokie | Sredni | File locking, merge strategies, user approval |
| Duzy koszt API przy wielu agentach | Wysokie | Sredni | Budget per agent, auto-stop, WEAK model delegation |
| IntelliJ plugin regresja po split | Srednie | Wysoki | Integration testy, CI, feature flags |
| Zlozonosc multi-module Gradle | Srednie | Niski | Wersjonowanie modulow, BOM |
| React web security (XSS, CSRF) | Srednie | Wysoki | Standard web security, sanitization, CORS |

---

## 14. Zalaczniki — macierz zmian plikow

### 14.1 Pliki do modyfikacji w Fazie 0

| Plik | Zmiana |
|------|--------|
| `core/api/CoreApiRouter.kt:73` | `Project? = null` → `ProjectHandle? = null` |
| `core/services/ChatService.kt:54` | `Project? = null` → `ProjectHandle? = null` |
| `core/services/PlanningService.kt:55` | `Project? = null` → `ProjectHandle? = null` |
| `core/services/ContextService.kt:4` | Usunac import `com.intellij`, uzyc `ProjectHandle` |
| `core/api/routers/AgentRouter.kt:6` | Usunac import `com.intellij`, uzyc `ProjectHandle` |
| `core/subagents/SubagentRouter.kt:43` | `Project?` → `ProjectHandle?` |
| `core/context/BaseContextProvider.kt:3` | Usunac import `com.intellij`, uzyc `ProjectHandle` |
| `core/context/ContextProviderRegistry.kt:4-5` | Usunac IntelliJ imports, uzyc SPI |
| `core/services/ProjectStartupActivity.kt` | Przeniesc do `:intellij-plugin` |
| `build.gradle.kts` | Rozbic na root + `:core` + `:intellij-plugin` |
| `settings.gradle.kts` | Dodac `include(":core", ":intellij-plugin", ":cli")` |

### 14.2 Pliki do przeniesienia (core → intellij-plugin)

| Plik | Powod |
|------|-------|
| `core/context/providers/CurrentFileContextProvider.kt` | FileEditorManager |
| `core/context/providers/OpenFilesContextProvider.kt` | FileEditorManager |
| `core/context/providers/RecentFilesContextProvider.kt` | EditorHistoryManager |
| `core/context/providers/TerminalContextProvider.kt` | ToolWindowManager |
| `core/context/providers/ProblemsContextProvider.kt` | WolfTheProblemSolver |
| `core/context/providers/GitDiffContextProvider.kt` | ChangeListManager |
| `core/context/providers/GrepSearchContextProvider.kt` | PsiSearchHelper |
| `core/context/providers/FileContextProvider.kt` | FilenameIndex |
| `core/services/ProjectStartupActivity.kt` | ProjectActivity |

### 14.3 Nowe pliki do stworzenia

| Plik | Modul | Cel |
|------|-------|-----|
| `core/project/ProjectHandle.kt` | :core | Abstrakcja projektu |
| `core/context/spi/ContextProviderSPI.kt` | :core | SPI dla IDE providerow |
| `core/agents/AgentEventBus.kt` | :core | Event bus (Faza 4) |
| `core/agents/AgentRegistry.kt` | :core | Registry agentow (Faza 4) |
| `core/agents/MultiAgentOrchestrator.kt` | :core | Orkiestrator (Faza 4) |
| `core/agents/UserConfirmationQueue.kt` | :core | Kolejka zatwierdzen (Faza 4) |
| `cli/main.kt` | :cli | Entry point CLI |
| `cli/ui/App.kt` | :cli | Compose Desktop root |
| `cli/ui/ChatPanel.kt` | :cli | Panel chatu |
| `cli/ui/StatusPanel.kt` | :cli | Panel statusu |
| `cli/runner/TaskRunner.kt` | :cli | Task runner |
| `cli/runner/BenchmarkRunner.kt` | :cli | Benchmark framework |
| `cli/context/StandaloneContextProviders.kt` | :cli | Standalone providers |
| `intellij-plugin/context/IntelliJContextProviders.kt` | :intellij-plugin | IDE providers |

---

## 15. Istniejace mechanizmy gotowe na reuse

Analiza wykazala ze projekt ma juz wiele mechanizmow ulatwiajacych ekstrakcje:

| Mechanizm | Lokalizacja | Gotowość |
|-----------|-------------|----------|
| `ContextProviderEnvironment.IDE_ONLY` | `BaseContextProvider.kt` | Providery juz oznaczone, skip przy `isIdeEnvironment=false` |
| `UIAdapter` interface | `core/api/UIAdapter.kt` | Abstrakcja UI gotowa na CLI implementacje |
| `StreamCallback` typealias | `StreamTypes.kt` | Callback-based streaming, framework-agnostic |
| 3-tier event listeners | WorkflowEventListener → TurnEventListener → ExecutionEventListener | Czyste interfejsy, bez Swing |
| `SwingWorkflowListener` | `ui/listeners/` | Wzorzec do skopiowania jako ComposeWorkflowListener |
| `SessionStateManager` | `services/session/` | Pure Kotlin StateFlow, bez IntelliJ |
| `CoreApiRouter` nullable `ideProject` | `core/api/CoreApiRouter.kt:73` | Juz opcjonalny |
| Debounced message rendering | `ChatView.kt` (200ms) | Wzorzec do replikacji w Compose |
| Content hash caching | `ChatView.CachedMessagePanel` | Optymalizacja przenaszalna |

**Szacowany naklad na ekstrakcje core (bez GUI):** ~17h (agent estimate)
**Szacowany naklad na multi-agent:** ~13-19 dni roboczych

---

## Podsumowanie

Projekt Refio ma solidne fundamenty architektoniczne. Warstwa `core/` jest w ~85% niezalezna od IntelliJ Platform, a commit `3d94c03` juz rozpoczal prace nad pelna separacja. Kluczowe decyzje:

1. **Modularyzacja Gradle** to warunek konieczny — bez niej nic innego sie nie ruszy
2. **Compose Multiplatform Desktop** to najlepsza opcja GUI dla standalone (Kotlin-native, sciezka do Web)
3. **Multi-agentowość** wymaga fundamentalnych zmian w GlobalMetrics, OllamaRequestGate i dodania Event Bus — ale istniejacy SubagentDefinition jest dobrym punktem wyjscia
4. **Web-ready API** jest prawie gotowe — Ktor Server juz jest w zaleznosc, DTO sa serializowalne
5. **Biggest risk**: modularyzacja moze zlamac istniejacy plugin — trzeba CI per-module od dnia 1
