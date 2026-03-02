## 1. Cel i kontekst

Opis stosu technologicznego dla projektu Refio: **plugin IntelliJ (Kotlin) ze zintegrowanym core** (również Kotlin). Dane trwałe w SQLite. Środowisko: Windows 10/11, Linux, macOS. Priorytety: stabilność IDE (brak webview), prywatność (no-egress), brak zależności zewnętrznych, szybkie iteracje nad AI.

**Architektura:** Core jest **wbudowanym modułem Kotlin** w plugin. Komunikacja odbywa się przez **CoreApiRouter** (in-process), nie HTTP.

## 2. Podsumowanie decyzji architektonicznych (v0.9 - Full-Kotlin)

* **UI/Frontend:** plugin IntelliJ w Kotlinie (JetBrains Platform SDK)
* **Core:** Kotlin + Exposed ORM + CoreApiRouter (embedded, bez HTTP)
* **Transport:** Direct method calls (in-process) przez CoreApiRouter; opcjonalnie Ktor HTTP server dla CLI (v1.1+)
* **Storage:** SQLite (tryb WAL) via Exposed ORM dla logów, kosztów, sesji, snapshotów; pliki snapshotów w `.refio/snapshots/`
* **Indeks/RAG:** prosty lokalny indeks (embedding + top-k), watcher FS, ignorowane ścieżki
* **Konfiguracja:** YAML config → Settings UI → built-in defaults; schema_version, secret redaction
* **Bezpieczeństwo:** JVM sandboxing, no-egress mode, denylista terminala, path sandbox (java.nio.file)
* **Orchestration:** UnifiedStepExecutor z pluggable strategies (SimpleAuto, Orchestration)
* **Context Building:** ProjectAnalyzerService + ContextService + MCP integration (w development)

## 3. Komponenty i biblioteki

### 3.1 Plugin IntelliJ (Kotlin/JVM) + Embedded Core

* **Język:** Kotlin 1.9.25 (korutyny)
* **SDK:** JetBrains Platform SDK (IntelliJ 2024.x)
* **Architektura:** Plugin → CoreApiClient → CoreApiRouter → Services
* **UI:** natywny UI IntelliJ (tool window po prawej, akcje, shortcuts). Brak webview.
* **Funkcje:** widok chat/plan/agent, kolejka kroków, toolbar (model, no-egress, thinking), wstawianie bloków kodu, integracja z viewerem diff, status indeksu i licznik kosztów.

### 3.2 Core (Kotlin - Embedded)

**Architektura warstwowa:**
```
Plugin UI Layer
     ↓
CoreApiClient (thin wrapper)
     ↓
CoreApiRouter (API contract, validation, middleware)
     ↓
Services (TaskService, PlanningService, AgentExecutor, ContextService, ConfigService)
     ↓
Exposed ORM (Database access layer)
     ↓
SQLite (WAL mode)
```

**Komponenty:**
* **CoreApiRouter:** Warstwa API (bez HTTP) z validacją, middleware, error handling
* **Services:**
  - `TaskService`, `PlanningService`, `ChatService` - tryby pracy
  - `AgentExecutor` - planowanie i wykonanie kroków
  - `ContextService` - budowanie kontekstu dla LLM
  - `ProjectAnalyzerService` - analiza struktury projektu
  - `SnapshotService`, `ConfigService`, `PromptsService`
* **Execution System:**
  - `UnifiedStepExecutor` - główna pętla wykonania
  - `SimpleAutoStrategy` - sekwencyjna egzekucja bez orchestracji
  - `OrchestrationStrategy` - orchestracja z reflection loop
  - `ExecutionEventListener` - observer pattern dla UI
* **LLM Integration:**
  - Custom HTTP clients dla Ollama, OpenAI, Anthropic (Ktor Client)
  - `ModelRegistry` - centralna rejestr modeli z capabilities i pricing
  - Full logging z secret redaction
* **Tools:** 10 narzędzi (read_file, grep, terminal, etc.) - implementacja JVM (java.nio.file, ProcessBuilder)
* **Security:** PathSandbox, terminal denylist, file limits, timeout protection
* **Async:** Kotlin coroutines (suspend functions, Dispatchers.IO)

### 3.3 Baza danych i pliki

* **SQLite 3** w trybie WAL
* **ORM:** Exposed (JetBrains) - DSL + DAO
* **Indeksy:** po ts, session_id, project_id
* **Retencja:** domyślnie 90 dni (do doprecyzowania)
* **Snapshoty:** .refio/snapshots/ w projekcie; LRU cleanup (np. 1 GB/100 szt.)
* **Schema:** 100% kompatybilny z poprzednią wersją Python (SQLModel)

### 3.4 CLI

* **Status:** Planowany (v1.1+)
* **Implementacja:** Kotlin CLI (clikt lub kotlinx-cli)
* **Transport:** Opcjonalny Ktor HTTP server jako wrapper nad CoreApiRouter
* **Parytet:** Pełna zgodność funkcjonalna z pluginem
* **Polecenia:** chat|plan|agent, steps list|approve|diff|rollback, index status|rebuild, session export|import

## 4. Wersje i zależności (v0.9 - Full-Kotlin)

**Główne zależności:**
* **Kotlin:** 1.9.25
* **JVM Target:** 17
* **IntelliJ Platform:** 2024.x (builds 241-243.*)
* **Ktor:** 2.3.7 (Client dla LLM providers; opcjonalnie Server dla CLI)
* **Exposed ORM:** 0.46.0 (core, dao, jdbc)
* **SQLite JDBC:** 3.44.1.0
* **Gson:** 2.10.1 (JSON serialization)
* **kotlinx-serialization:** 1.6.2
* **kotlinx-coroutines:** 1.7.3
* **CommonMark:** 0.21.0 (Markdown rendering w ChatView)
* **kotlin-logging-jvm:** 3.0.5 (structured logging)

**Testing:**
* **JUnit 5:** 5.10.1
* **MockK:** 1.13.8 (mocking framework)
* **kotlinx-coroutines-test:** 1.7.3 (coroutine testing)

**Code Quality:**
* **detekt:** Static analysis
* **ktlint:** Code formatting

## 5. Transport i API

### 5.1 Current Implementation (v0.9)

* **Transport:** In-process method calls (brak HTTP overhead)
* **API Layer:** CoreApiRouter - suspend functions Kotlin
* **Kontrakt:** Request/Response data classes (Kotlin)
* **Latency:** < 1ms (in-process)
* **Error Handling:** Typed exceptions → ErrorEnvelope responses

**Architektura:**
```kotlin
// Plugin wywołuje router bezpośrednio
class CoreApiClient(private val router: CoreApiRouter) {
    suspend fun createTask(
        request: TaskCreateRequest,
        userId: String,
        idempotencyKey: String
    ): TaskResponse {
        return router.createTask(request, userId, idempotencyKey)
    }
}
```

### 5.2 Future: HTTP Layer (v1.1+ dla CLI)

* **Cel:** Wsparcie dla Kotlin CLI
* **Implementacja:** Ktor HTTP server jako wrapper nad CoreApiRouter
* **Routes:** HTTP endpoints wywołują te same metody routera (zero duplikacji logiki)
* **Plugin:** Nadal używa in-process calls (bez zmian)

**Przykład (planned):**
```kotlin
fun Application.module() {
    routing {
        post("/v1/tasks") {
            val request = call.receive<TaskCreateRequest>()
            val result = coreRouter.createTask(request, call.userId(), idempotencyKey)
            call.respond(HttpStatusCode.Created, result)
        }
    }
}
```

### 5.3 Future: gRPC (v2.0+)

* **Cel:** Streaming tokenów, dwukierunkowy streaming kroków agenta
* **Proto:** Kompatybilna semantyka z API contract
* **Zalety:** Niższy overhead niż HTTP, native streaming

### 5.4 Request/Response Format (Data Classes)

**Kotlin data classes (zamiast JSON):**
```kotlin
data class TaskCreateRequest(
    val name: String,
    val mode: TaskMode,
    val readOnly: Boolean = false,
    val pinned: Boolean = false
)

data class TaskResponse(
    val id: String,
    val name: String,
    val mode: TaskMode,
    val status: TaskStatus,
    val executionMode: ExecutionMode,
    val readOnly: Boolean,
    val pinned: Boolean,
    val coreApiVersion: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class TaskPlanRequest(
    val input: String,
    val contextRefs: List<String> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
    val maxSteps: Int = 20,
    val thinking: Boolean = false,
    val interactive: Boolean = true
)

data class TaskPlanResponse(
    val taskId: String,
    val subtasks: List<SubtaskDto>,
    val costs: CostSummary,
    val error: ErrorDetail? = null
)
```

**Format zachowany dla kompatybilności (JSON serialization):**
- Requests/responses można serializować do JSON (Gson)
- Schema version w payload
- Error envelope format bez zmian

## 6. Konfiguracja i precedence

Kolejność nadpisywania ustawień (last-writer-wins):

1. Global YAML w katalogu użytkownika
2. .env w katalogu projektu (w .gitignore)
3. YAML projektu
4. Parametry CLI
5. Ustawienia GUI (zapisywane w DB)

Dodatkowo:

* schema_version w DB i plikach YAML; migrator konfiguracji.
* Audit zmian (kto/co/kiedy) w SQLite.
* Ostrzeżenia przy wykryciu sekretów w YAML; preferowane .env.

## 7. Bezpieczeństwo i prywatność

* Core nasłuchuje wyłącznie na 127.0.0.1.
* Token/JWT sesyjny + lockfile z nonce do handshaku.
* CORS wyłączony, ograniczenie metod HTTP, rate-limit lokalny.
* Flaga no-egress globalnie/projektowo blokuje wywołania zewnętrzne (providerzy chmurowi, fetch_url_content).
* Redakcja sekretów i PII w logach; maskowanie nagłówków Authorization.
* Terminal w CWD projektu, denylista destrukcyjnych poleceń, limit czasu i rozmiaru outputu.
* Blokada ścieżek poza projektem (kanonikalizacja ścieżek).

## 8. Dane i model relacyjny (zarys)

Tabele (przykładowo):

* sessions(session_id, project_id, mode, started_at, finished_at, status)
* steps(step_id, session_id, kind, input_hash, started_at, finished_at, status, error_code)
* tool_invocations(id, step_id, tool, params_json, ok, ms, bytes_in, bytes_out)
* costs(id, step_id, model, provider, tokens_in, tokens_out, usd_est)
* settings(id, scope, key, value, updated_at, source)  // audit precedence
* index_files(file_id, project_id, path, size, hash, updated_at)
* embeddings(file_id, chunk_id, vector, dims, meta)
* logs(id, ts, level, source, message)  // lokalna telemetria

Wydajność:

* SQLite WAL, busy_timeout, indeksy po (session_id, ts) i (project_id, path).
* Rotacja rekordów i VACUUM okresowo.

## 9. Wydajność i skalowanie

* Asynchroniczne endpointy core, limity rozmiarów żądań, timeouts.
* Back-pressure dla długich narzędzi; kolejka w pamięci na kroki agenta.
* RAG: limity rozmiaru repo, chunking i top-k; ignorowane katalogi (.git, node_modules, build).
* Single-user: OK. Multi-user (poza MVP): przewidziana migracja do PostgreSQL, gRPC i/lub usługowego core.

## 10. Packaging i dystrybucja

* Core: jeden artefakt na Windows (PyInstaller lub uv standalone). Start on-demand przez plugin (sprawdzenie lockfile/portu).
* Spójność wersji: kontrola protokołu plugin↔core (X-Core-Api-Version). W razie mismatch: elegancki komunikat i instrukcja aktualizacji/rollback.
* Plugin: publikacja w JetBrains Marketplace (docelowo) + kanały Stable/EAP.

## 11. Testy i jakość

* Testy kontraktowe: wygenerowany klient JVM z OpenAPI (weryfikacja endpointów).
* Testy integracyjne: scenariusze chat/plan/agent z mockami providerów.
* Testy narzędzi: operacje na sandboxie repo testowego (fixtures).
* Static analysis: detekt/ktlint (Kotlin), ruff/mypy (Python).
* Skan bezpieczeństwa zależności (OWASP/Dependabot).

## 12. Telemetria i koszty

* Lokalna telemetria w SQLite (brak wysyłki zewnętrznej).
* Etykiety per-krok: model, tokens in/out, czas, koszt.
* Raport sesji do JSON/JSONL (eksport/import).
* Cenniki providerów definiowane w YAML dla 1M tokenów IN/OUT; realne zużycie z odpowiedzi API, dla lokalnych modeli czas CPU/RAM jako proxy.

## 13. Roadmapa techniczna

* v1.0: HTTP/JSON, SQLite WAL, minimalny RAG, denylista terminala, no-egress, snapshot+diff.
* v1.1: export/import sesji, preview kontekstu (licznik tokenów).
* v1.2: lepsza obsługa błędów i polityka fallback modeli (capabilities).
* v2.0: gRPC (streaming tokenów/kroków), opcjonalny PostgreSQL, tryb usługowy dla wielu użytkowników.

## 14. Ryzyka i mitigacje

* Dryf kontraktu plugin↔core → OpenAPI jako źródło prawdy, testy kontraktowe w CI, wersjonowanie nagłówkiem.
* Rozrost SQLite i blokady → WAL, indeksy, rotacja 90 dni, VACUUM, back-pressure.
* Złożoność dystrybucji core na Windows → jeden exe, check-sum, prosty updater, diagnostyka health/ports.
* Bezpieczeństwo terminala → twarda denylista i whitelist, tokenizacja argumentów, limity czasu/rozmiaru.

## 15. Przykłady implementacji

### 15.1 Plugin → CoreApiRouter (in-process)

```kotlin
// Plugin service
class SessionManager(private val project: Project) {
    private val coreManager = service<CoreConnectionManager>()
    private val projectRouter = coreManager.getProjectRouter(project)

    suspend fun sendChatMessage(input: String) {
        val request = ChatRequest(
            input = input,
            contextRefs = buildContextRefs(),
            model = selectedModel,
            provider = selectedProvider
        )

        val response = projectRouter.chat(request)
        // Update UI with response
    }
}
```

### 15.2 CoreApiRouter → Services

```kotlin
// CoreApiRouter
suspend fun chat(request: ChatRequest): ChatResponse {
    return try {
        // Validate request
        validateChatRequest(request)

        // Delegate to service
        val result = chatService.chat(
            taskId = currentTaskId,
            request = request
        )

        ChatResponse(
            output = result.output,
            costs = result.costs,
            model = result.model
        )
    } catch (e: Exception) {
        ChatResponse(error = ErrorDetail.from(e))
    }
}
```

### 15.3 Execution Strategy Pattern

```kotlin
// UnifiedStepExecutor z OrchestrationStrategy
val executor = UnifiedStepExecutor(
    strategy = OrchestrationStrategy(
        agentExecutor = agentExecutor,
        llmClient = llmClient,
        chatMessageRepository = chatMessageRepository
    ),
    listener = object : ExecutionEventListener {
        override fun onStepCompleted(step: Subtask, result: StepResult) {
            // Update UI
            sessionManager.loadMessages()
            sessionManager.loadSubtasks()
        }
    }
)

executor.execute(taskId)
```

## 16. Struktura repo

```
/
  build.gradle.kts
  src/main/kotlin/
    pl/jclab/refio/
      ui/             # Plugin UI components
      services/       # Plugin services (SessionManager, etc.)
      actions/        # IDE actions
      settings/       # Settings UI
    pl/jclab/refio/core/
      api/            # CoreApiRouter
      services/       # Business logic (ChatService, PlanningService, etc.)
      db/             # Database (Exposed ORM)
      llm/            # LLM clients & adapters
      tools/          # Tool implementations
      execution/      # UnifiedStepExecutor, strategies
      config/         # Configuration management
  src/test/kotlin/    # Tests (JUnit 5 + MockK)
  /docs/
    implements/         # ADRs & implementation docs
    prd.md
    tech-stack.md
    tasks.md
  /db/
    migrations/         # Database migrations (future)
```

## 17. ADRs (Architecture Decision Records)


## 18. Ustalenia operacyjne

* Domyślny shell: PowerShell (Windows). W planie: wsparcie WSL/Git Bash (mapowanie CWD).
* Limity: max rozmiar pliku w kontekście, max łączny rozmiar kontekstu, timeouts na narzędziach i providerach.
* Ignorowane ścieżki: .git, .idea, node_modules, build/target, pliki > 2 MB (domyślnie; konfigurowalne).

## 19. Glosariusz

* no-egress: tryb blokujący ruch sieciowy na zewnątrz hosta.
* snapshot: kopia stanu pliku/projektu przed zapisem kroku agenta.
* envelope: ujednolicona struktura JSON request/response z metadanymi.
* capabilities: cechy modelu/providera (json_mode, tool_use, streaming, max_context, thinking).

