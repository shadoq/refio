# 0003 — System eventow miedzy agentami + wizualizacja GUI

> **Data:** 2026-03-23
> **Status:** Projekt techniczny
> **Kontekst:** Rozszerzenie multi-agent z 0002 o pelny system eventow, interleaved chat i wizualizacje przeplywu

---

## Spis tresci

1. [Obecne fundamenty w kodzie](#1-obecne-fundamenty-w-kodzie)
2. [Typy eventow miedzy agentami](#2-typy-eventow-miedzy-agentami)
3. [Architektura event bus](#3-architektura-event-bus)
4. [Model danych — rozszerzenie DB](#4-model-danych--rozszerzenie-db)
5. [Interleaved chat — przeplatane wiadomosci](#5-interleaved-chat--przeplatane-wiadomosci)
6. [GUI — wizualizacja przeplywu agentow](#6-gui--wizualizacja-przeplywu-agentow)
7. [Scenariusze interakcji](#7-scenariusze-interakcji)
8. [Implementacja krok po kroku](#8-implementacja-krok-po-kroku)

---

## 1. Obecne fundamenty w kodzie

Analiza kodu wykazala ze **duzo infrastruktury juz istnieje**:

### 1.1 Istniejace mechanizmy

| Mechanizm | Lokalizacja | Gotowość |
|-----------|-------------|----------|
| `runId` (UUID per turn) | `AgentTurnLoop.kt:236` | Identyfikuje kazdy run — nadaje sie jako `agentRunId` |
| `parentRunId` | `TurnProfileOverrides.parentRunId` | Juz sluzy do laczenia parent→child (subagent nesting) |
| `depth` | `TurnProfileOverrides.depth` | Sledzenie glebokosci zagniezdzen |
| `subagentName` | `TurnProfileOverrides.subagentName` | Nazwa wywolujacego subagenta |
| `subagentChain` | `TurnProfileOverrides.subagentChain` | Lista lancucha wywolan (recursion prevention) |
| `metadata` JSON w `chat_messages` | `ChatMessagesTable.metadata` | Juz przechowuje `{"subagent_name":"..."}` |
| `TurnEventListener` | `turn/TurnEventListener.kt` | Events: turnStarted, toolExecutionStarted/Completed, streamChunk, turnCompleted |
| `WorkflowEventListener` | `workflow/WorkflowEventListener.kt` | Events: phases, streaming, questions, approvals |
| `taskId` per agent | `TasksTable` | Kazdy agent moze miec dedykowany task |

### 1.2 Obecny flow subagenta (InvokeSubagentTool)

Aktualnie subagent jest wywoływany tak:

```
InvokeSubagentTool.execute(params)
  → walidacja: recursion (subagentChain), depth (max 3), enabled status
  → tworzy TurnRequest z TurnRunProfile.SUBAGENT
    ├─ systemPromptOverride, allowedTools/disallowedTools
    ├─ modelOverride, maxIterationsOverride, contextProfile
    └─ parentRunId, depth+1, subagentChain + currentName
  → runTurnCallback(request, turnListener, streamCallback)
    └─ AgentTurnLoop uruchamia pelny cykl (prompt → LLM → tools → loop)
  → zwraca TurnResult
    ├─ success, response, iterations
    ├─ tokensIn, tokensOut, cost
    └─ toolsUsed
  → wynik persystowany jako TOOL message w chat_messages
    └─ metadata: {"subagent_name":"...", "depth":N, "iterations":N}
```

**Streaming:** TurnEventListener jest wstrzykiwany jako `_turn_event_listener` w parametrach narzędzia → subagent streamuje tokeny do parent agent UI w real-time.

**Kontekst subagenta kontrolowany przez:** `SubagentContextProfile` (includeFileTree, includeConversation, includeWorkingMemory, includeRag, includeDependencies, maxContextTokens, includeParentSummary).

### 1.3 Metadata w chat_messages

Pole `metadata` (text, JSON) przechowuje różne struktury:

- **MessageMetrics:** `{model, provider, inputTokens, outputTokens, costUsd, latencyMs, toolsUsed, ...}`
- **ToolCallDisplayInfo:** `{type:"tool_call", tool_name, display_type, parameters, status, result_summary, code_changes}`
- **Subagent info:** `{"subagent_name":"code-reviewer", "depth":1, "iterations":3}`

Pole jest elastyczne — dodanie `agent_instance_id` do metadata jest możliwe BEZ migracji schematu (ale dedykowana kolumna jest lepsza dla query).

### 1.4 Konwersja wiadomości (pipeline)

```
DB (ChatMessage) → ChatRouter.getMessages() → API (MessageResponse)
  → MessageDispatcher.loadMessages() → UI (Message)
    → ChatMessageBubbleRouter.render() → role-based routing
      ├─ "user"      → UserBubbleRenderer
      ├─ "assistant"  → AssistantBubbleRenderer (6 sub-renderers)
      ├─ "tool"       → ToolBubbleRenderer
      └─ other        → OtherBubbleRenderer
```

AssistantBubbleRenderer sprawdza kaskadowo: execution summary → plan → tool call → question → approval → regular text.

### 1.5 Czego brakuje

| Brak | Wplyw |
|------|-------|
| Brak tabeli eventow | Eventy nie sa persystowane — znikaja po zakonczeniu |
| Brak event bus | Agenty nie moga komunikowac sie miedzy soba |
| Brak `agentId` w `chat_messages` | Nie mozna filtrowac wiadomosci per agent |
| Brak sesji multi-agent | Nie ma konceptu "grupy agentow" pracujacych razem |
| Brak wizualizacji przeplywu | UI pokazuje jeden strumien wiadomosci |

---

## 2. Typy eventow miedzy agentami

### 2.1 Katalog eventow

```kotlin
// core/agents/events/AgentEvent.kt

sealed interface AgentEvent {
    val id: String                  // UUID eventu
    val sessionId: String           // Sesja multi-agent (laczy wszystkich agentow)
    val sourceAgentId: String       // Agent ktory emituje event
    val timestamp: Long             // System.currentTimeMillis()
    val correlationId: String       // Do sledzenia lancucha (= parentRunId)

    // ──────────────────────────────────────────────
    // LIFECYCLE — cykl zycia agenta
    // ──────────────────────────────────────────────

    /** Agent rozpoczal prace */
    data class AgentStarted(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val agentName: String,          // Czytelna nazwa ("Analyst", "Coder")
        val profile: String?,           // Subagent profile (np. "code-reviewer")
        val task: String,               // Opis zadania
        val model: String?,             // Uzywany model
        val dependsOn: List<String>     // Na kogo czekal
    ) : AgentEvent

    /** Agent zakonczyl prace pomyslnie */
    data class AgentCompleted(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val summary: String,            // Podsumowanie co zrobil
        val artifacts: List<Artifact>,  // Pliki, analizy, kod
        val tokensUsed: Long,
        val costUsd: Double,
        val durationMs: Long
    ) : AgentEvent

    /** Agent zakonczyl z bledem */
    data class AgentFailed(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val error: String,
        val recoverable: Boolean        // Czy mozna ponowic
    ) : AgentEvent

    // ──────────────────────────────────────────────
    // DATA — wymiana danych miedzy agentami
    // ──────────────────────────────────────────────

    /** Agent potrzebuje danych od innego agenta */
    data class DataRequest(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val targetAgentId: String?,     // null = broadcast do wszystkich
        val query: String,              // Co potrzebuje
        val context: Map<String, String> // Kontekst pytania
    ) : AgentEvent

    /** Agent odpowiada na DataRequest */
    data class DataResponse(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val targetAgentId: String,      // Komu odpowiada
        val requestId: String,          // ID oryginalnego DataRequest
        val response: String,           // Odpowiedz
        val artifacts: List<Artifact>   // Zalaczone dane
    ) : AgentEvent

    // ──────────────────────────────────────────────
    // COORDINATION — koordynacja miedzy agentami
    // ──────────────────────────────────────────────

    /** Agent produkuje artefakt (plik, analiza, plan) */
    data class ArtifactProduced(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val artifact: Artifact
    ) : AgentEvent

    /** Agent prosi o uruchomienie innego agenta */
    data class SpawnAgentRequest(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val requestedProfile: String,   // Profil subagenta do uruchomienia
        val task: String,               // Zadanie dla nowego agenta
        val priority: Int = 0,          // 0=normal, 1=high, -1=low
        val dependsOnCurrent: Boolean   // Czy nowy agent czeka na biezacego
    ) : AgentEvent

    /** Nowy agent zostal uruchomiony (odpowiedz na SpawnAgentRequest) */
    data class AgentSpawned(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,  // Orchestrator
        override val timestamp: Long,
        override val correlationId: String,
        val spawnedAgentId: String,
        val requestId: String           // ID oryginalnego SpawnAgentRequest
    ) : AgentEvent

    // ──────────────────────────────────────────────
    // APPROVAL — zatwierdzanie przez uzytkownika
    // ──────────────────────────────────────────────

    /** Agent potrzebuje zatwierdzenia uzytkownika */
    data class ApprovalRequired(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val action: String,             // Opis akcji
        val actionType: ApprovalActionType,
        val risk: RiskLevel,
        val details: Map<String, String>, // np. {"path": "src/User.kt", "operation": "write"}
        val autoApproveAfterMs: Long? = null  // Opcjonalny auto-approve timeout
    ) : AgentEvent

    /** Uzytkownik zatwierdzil/odrzucil */
    data class ApprovalDecision(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,  // "user"
        override val timestamp: Long,
        override val correlationId: String,
        val approvalId: String,         // ID oryginalnego ApprovalRequired
        val approved: Boolean,
        val reason: String?             // Opcjonalny powod odrzucenia
    ) : AgentEvent

    // ──────────────────────────────────────────────
    // PROGRESS — postep pracy (do GUI)
    // ──────────────────────────────────────────────

    /** Agent raportuje postep */
    data class ProgressUpdate(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val phase: String,              // "analyzing", "coding", "reviewing", "testing"
        val message: String,            // Czytelny opis co robi
        val progress: Float?,           // 0.0-1.0 jezeli mozliwy do okreslenia
        val toolName: String? = null    // Jezeli uzywa narzedzia
    ) : AgentEvent

    /** Agent streamuje fragment odpowiedzi (do interleaved chat) */
    data class StreamChunk(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val delta: String,              // Nowy fragment
        val accumulated: String,        // Caly dotychczasowy tekst
        val isComplete: Boolean
    ) : AgentEvent
}

// Typy pomocnicze
data class Artifact(
    val type: ArtifactType,
    val name: String,                   // np. "specyfikacja.md", "UserController.kt"
    val content: String?,               // Zawartosc (dla malych artefaktow)
    val path: String?,                  // Sciezka pliku (dla plikow)
    val metadata: Map<String, String> = emptyMap()
)

enum class ArtifactType {
    FILE_CREATED, FILE_MODIFIED, FILE_DELETED,
    ANALYSIS, SPECIFICATION, PLAN, CODE_REVIEW,
    TEST_RESULT, TERMINAL_OUTPUT
}

enum class ApprovalActionType {
    FILE_WRITE, FILE_DELETE, TERMINAL_COMMAND,
    EXTERNAL_API, SPAWN_AGENT, OTHER
}

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
```

### 2.2 Przeplyw eventow — typowy scenariusz

```
Czas  Agent         Event                    Szczegoly
─────────────────────────────────────────────────────────────
t0    Orchestrator  AgentStarted             "Analyst" — analiza wymagan
t1    Analyst       ProgressUpdate           phase="analyzing", "Czytam wymagania..."
t2    Analyst       StreamChunk              "Na podstawie analizy..." (do chatu)
t3    Analyst       ArtifactProduced         type=SPECIFICATION, "spec.md"
t4    Analyst       AgentCompleted           summary="Specyfikacja gotowa"
t5    Orchestrator  AgentStarted             "Coder" — implementacja (dependsOn: Analyst)
t6    Orchestrator  AgentStarted             "Reviewer" — review (dependsOn: Coder) — WAITING
t7    Coder         ProgressUpdate           phase="coding", "Tworze UserController..."
t8    Coder         ApprovalRequired         FILE_WRITE, "src/UserController.kt", MEDIUM
t9    User          ApprovalDecision         approved=true
t10   Coder         ArtifactProduced         type=FILE_CREATED, "UserController.kt"
t11   Coder         DataRequest              targetAgent=null, "Czy sa testy do uruchomienia?"
t12   Analyst       DataResponse             "Tak, w spec.md opisane sa test cases..."
t13   Coder         SpawnAgentRequest        profile="sre-engineer", task="Napisz testy"
t14   Orchestrator  AgentSpawned             spawnedAgentId="Tester"
t15   Tester        AgentStarted             "Tester" — pisanie testow
t16   Coder         AgentCompleted           summary="Implementacja gotowa"
t17   Tester        AgentCompleted           summary="5 testow, 4 passed, 1 failed"
t18   Reviewer      AgentStarted             "Reviewer" — review (unblocked)
t19   Reviewer      StreamChunk              "Analiza kodu wykazala..." (do chatu)
t20   Reviewer      ArtifactProduced         type=CODE_REVIEW, "review.md"
t21   Reviewer      AgentCompleted           summary="3 uwagi, 1 krytyczna"
```

---

## 3. Architektura event bus

### 3.1 Implementacja

```kotlin
// core/agents/events/AgentEventBus.kt

class AgentEventBus {
    // Glowny strumien eventow — replay ostatnich 200 dla polaczen GUI
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 200,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    // Persystencja eventow (opcjonalna, do historii i replay)
    private var eventRepository: AgentEventRepository? = null

    fun setRepository(repo: AgentEventRepository) {
        eventRepository = repo
    }

    suspend fun emit(event: AgentEvent) {
        // Persystuj
        eventRepository?.save(event)
        // Broadcastuj
        _events.emit(event)
    }

    // ── Filtry subskrypcji ──

    /** Wszystkie eventy danego agenta */
    fun agentEvents(agentId: String): Flow<AgentEvent> =
        events.filter { it.sourceAgentId == agentId }

    /** Eventy skierowane do konkretnego agenta */
    fun eventsFor(agentId: String): Flow<AgentEvent> =
        events.filter {
            when (it) {
                is AgentEvent.DataRequest -> it.targetAgentId == agentId || it.targetAgentId == null
                is AgentEvent.DataResponse -> it.targetAgentId == agentId
                is AgentEvent.ApprovalDecision -> it.approvalId in pendingApprovalsOf(agentId)
                is AgentEvent.AgentSpawned -> it.correlationId in requestsOf(agentId)
                else -> false
            }
        }

    /** Eventy danego typu */
    inline fun <reified T : AgentEvent> eventsOfType(): Flow<T> =
        events.filterIsInstance<T>()

    /** Wszystkie eventy sesji */
    fun sessionEvents(sessionId: String): Flow<AgentEvent> =
        events.filter { it.sessionId == sessionId }

    /** Eventy lifecycle (start/complete/fail) — do wizualizacji grafu */
    fun lifecycleEvents(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.AgentStarted ||
                it is AgentEvent.AgentCompleted ||
                it is AgentEvent.AgentFailed ||
                it is AgentEvent.AgentSpawned
            )
        }

    /** Eventy wymagajace reakcji uzytkownika */
    fun approvalEvents(sessionId: String): Flow<AgentEvent.ApprovalRequired> =
        events.filter { it.sessionId == sessionId }
            .filterIsInstance<AgentEvent.ApprovalRequired>()

    /** Stream chunks do interleaved chat */
    fun chatStream(sessionId: String): Flow<AgentEvent> =
        events.filter {
            it.sessionId == sessionId && (
                it is AgentEvent.StreamChunk ||
                it is AgentEvent.AgentStarted ||
                it is AgentEvent.AgentCompleted ||
                it is AgentEvent.AgentFailed ||
                it is AgentEvent.ArtifactProduced ||
                it is AgentEvent.ApprovalRequired
            )
        }
}
```

### 3.2 Agent — obsluga eventow przychodzacych

```kotlin
// core/agents/AgentEventHandler.kt

class AgentEventHandler(
    private val agentId: String,
    private val eventBus: AgentEventBus,
    private val scope: CoroutineScope
) {
    // Oczekujace DataRequests od tego agenta (bez odpowiedzi)
    private val pendingDataRequests = ConcurrentHashMap<String, CompletableDeferred<AgentEvent.DataResponse>>()

    // Oczekujace Approvals
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<AgentEvent.ApprovalDecision>>()

    init {
        // Nasłuchuj na eventy skierowane do tego agenta
        scope.launch {
            eventBus.eventsFor(agentId).collect { event ->
                when (event) {
                    is AgentEvent.DataResponse -> {
                        pendingDataRequests[event.requestId]?.complete(event)
                    }
                    is AgentEvent.ApprovalDecision -> {
                        pendingApprovals[event.approvalId]?.complete(event)
                    }
                    is AgentEvent.DataRequest -> {
                        // Ten agent zostal zapytany o dane — deleguj do LLM
                        handleIncomingDataRequest(event)
                    }
                    else -> {}
                }
            }
        }
    }

    /** Wyslij DataRequest i czekaj na odpowiedz */
    suspend fun requestData(
        targetAgentId: String?,
        query: String,
        context: Map<String, String> = emptyMap(),
        timeout: Duration = 60.seconds
    ): AgentEvent.DataResponse? {
        val request = AgentEvent.DataRequest(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sourceAgentId = agentId,
            timestamp = System.currentTimeMillis(),
            correlationId = correlationId,
            targetAgentId = targetAgentId,
            query = query,
            context = context
        )

        val deferred = CompletableDeferred<AgentEvent.DataResponse>()
        pendingDataRequests[request.id] = deferred
        eventBus.emit(request)

        return try {
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingDataRequests.remove(request.id)
            null
        }
    }

    /** Wyslij ApprovalRequired i czekaj na decyzje uzytkownika */
    suspend fun requestApproval(
        action: String,
        actionType: ApprovalActionType,
        risk: RiskLevel,
        details: Map<String, String>,
        autoApproveAfterMs: Long? = null
    ): Boolean {
        val approval = AgentEvent.ApprovalRequired(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sourceAgentId = agentId,
            timestamp = System.currentTimeMillis(),
            correlationId = correlationId,
            action = action,
            actionType = actionType,
            risk = risk,
            details = details,
            autoApproveAfterMs = autoApproveAfterMs
        )

        val deferred = CompletableDeferred<AgentEvent.ApprovalDecision>()
        pendingApprovals[approval.id] = deferred
        eventBus.emit(approval)

        return try {
            val decision = if (autoApproveAfterMs != null) {
                withTimeout(autoApproveAfterMs) { deferred.await() }
            } else {
                deferred.await()
            }
            decision.approved
        } catch (e: TimeoutCancellationException) {
            // Auto-approve po timeout
            pendingApprovals.remove(approval.id)
            true
        }
    }
}
```

---

## 4. Model danych — rozszerzenie DB

### 4.1 Nowa tabela: `agent_sessions`

```kotlin
// core/db/AgentSessionsTable.kt

object AgentSessionsTable : Table("agent_sessions") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val projectId = varchar("project_id", 512)
    val name = varchar("name", 255)               // Nazwa sesji multi-agent
    val status = enumerationByName<TaskStatus>("status", 16).default(TaskStatus.NEW)
    val definitionYaml = text("definition_yaml").nullable()  // YAML task definition
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val completedAt = long("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
```

### 4.2 Nowa tabela: `agent_instances`

```kotlin
// core/db/AgentInstancesTable.kt

object AgentInstancesTable : Table("agent_instances") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val sessionId = varchar("session_id", 36)
        .references(AgentSessionsTable.id, onDelete = ReferenceOption.CASCADE)
    val taskId = varchar("task_id", 36)
        .references(TasksTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val name = varchar("name", 255)                // "Analyst", "Coder"
    val profile = varchar("profile", 255).nullable() // Subagent profile name
    val status = enumerationByName<AgentInstanceStatus>("status", 32)
        .default(AgentInstanceStatus.PENDING)
    val model = varchar("model", 255).nullable()
    val taskDescription = text("task_description")
    val dependsOn = text("depends_on").nullable()   // JSON array of agent names
    val result = text("result").nullable()           // Final output/summary
    val tokensIn = integer("tokens_in").default(0)
    val tokensOut = integer("tokens_out").default(0)
    val costUsd = double("cost_usd").default(0.0)
    val startedAt = long("started_at").nullable()
    val completedAt = long("completed_at").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)
    init {
        index("idx_agent_instances_session", false, sessionId)
    }
}

enum class AgentInstanceStatus {
    PENDING,    // Czeka na zaleznosci
    RUNNING,    // Dziala
    WAITING_DATA,     // Czeka na dane od innego agenta
    WAITING_APPROVAL, // Czeka na zatwierdzenie uzytkownika
    COMPLETED,  // Zakonczony pomyslnie
    FAILED,     // Zakonczony z bledem
    CANCELLED   // Anulowany
}
```

### 4.3 Nowa tabela: `agent_events`

```kotlin
// core/db/AgentEventsTable.kt

object AgentEventsTable : Table("agent_events") {
    val id = varchar("id", 36)
    val sessionId = varchar("session_id", 36)
        .references(AgentSessionsTable.id, onDelete = ReferenceOption.CASCADE)
    val sourceAgentId = varchar("source_agent_id", 36)
    val eventType = varchar("event_type", 64)       // "AgentStarted", "DataRequest", etc.
    val correlationId = varchar("correlation_id", 36)
    val payloadJson = text("payload_json")           // Full event serialized as JSON
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
    init {
        index("idx_agent_events_session_ts", false, sessionId, timestamp)
        index("idx_agent_events_source", false, sourceAgentId)
        index("idx_agent_events_type", false, eventType)
    }
}
```

### 4.4 Rozszerzenie `chat_messages` — dodanie `agentId`

```kotlin
// ZMIANA w ChatMessagesTable.kt — nowa kolumna
val agentInstanceId = varchar("agent_instance_id", 36).nullable()
    // .references(AgentInstancesTable.id) — opcjonalnie, bez FK dla wstecznej compat

// MIGRACJA:
// ALTER TABLE chat_messages ADD COLUMN agent_instance_id VARCHAR(36) DEFAULT NULL;
// CREATE INDEX idx_chat_messages_agent ON chat_messages(agent_instance_id);
```

Istniejace wiadomosci (bez agentId) beda traktowane jako "default" agent — **zero regresji**.

---

## 5. Interleaved chat — przeplatane wiadomosci

### 5.1 Koncepcja

W multi-agent sesji chat wyswietla wiadomosci od WSZYSTKICH agentow w jednym strumieniu, oznaczone kolorami i ikonami:

```
┌──────────────────────────────────────────────┐
│ [SYSTEM] Sesja multi-agent: "REST API User"  │
│ Agenci: Analyst, Coder, Reviewer             │
├──────────────────────────────────────────────┤
│                                              │
│ 🟢 Analyst rozpoczal prace                   │  ← AgentStarted event
│                                              │
│ [Analyst 🔵] Na podstawie analizy wymagan    │  ← StreamChunk z agentId
│ stwierdzam ze potrzebujemy:                  │
│ 1. UserController z CRUD                     │
│ 2. UserService z walidacja                   │
│ 3. UserRepository                            │
│ 📎 Artefakt: specyfikacja.md                 │  ← ArtifactProduced
│                                              │
│ ✅ Analyst zakonczyl (1.2K tokens, $0.01)    │  ← AgentCompleted
│                                              │
│ 🟢 Coder rozpoczal prace                     │
│                                              │
│ [Coder 🟠] Implementuje UserController.kt... │  ← StreamChunk
│                                              │
│ ⚠️ Coder wymaga zatwierdzenia:              │  ← ApprovalRequired
│   Zapis pliku: src/UserController.kt         │
│   [✅ Zatwierdz] [❌ Odrzuc]                 │
│                                              │
│ [Coder 🟠] ```kotlin                         │  ← dalszy stream
│ @RestController                              │
│ class UserController(...) {                  │
│   @PostMapping("/users")                     │
│   fun create(...) = ...                      │
│ }                                            │
│ ```                                          │
│                                              │
│ 💬 Coder → Analyst: Czy sa testy do          │  ← DataRequest
│   uruchomienia?                              │
│                                              │
│ 💬 Analyst → Coder: Tak, w spec.md opisane   │  ← DataResponse
│   sa 5 test cases...                         │
│                                              │
│ 🟢 Tester rozpoczal prace (spawned by Coder) │  ← AgentSpawned
│                                              │
│ [Tester 🟣] Uruchamiam testy...              │
│ ✅ 4/5 passed, ❌ 1 failed                   │
│                                              │
│ [Reviewer 🔴] Analiza kodu wykazala:         │
│ 1. ✅ Dobra struktura pakietow               │
│ 2. ⚠️ Brak walidacji email w UserService     │
│ 3. ❌ SQL injection w findByName              │
│                                              │
└──────────────────────────────────────────────┘
```

### 5.2 Model danych chatu — unified message stream

```kotlin
// cli/ui/models/ChatMessage.kt (Compose Desktop)

data class MultiAgentChatMessage(
    val id: String,
    val timestamp: Long,
    val type: ChatMessageType,
    val agentId: String?,           // null = system/user
    val agentName: String?,         // Czytelna nazwa
    val agentColor: Color,          // Kolor agenta w UI
    val content: String,
    val isStreaming: Boolean = false,
    val artifacts: List<Artifact> = emptyList(),
    val approval: PendingApproval? = null,  // Jezeli wymaga zatwierdzenia
    val metadata: Map<String, String> = emptyMap()
)

enum class ChatMessageType {
    USER_MESSAGE,       // Wiadomosc uzytkownika
    AGENT_MESSAGE,      // Odpowiedz agenta (stream)
    AGENT_STARTED,      // Agent rozpoczal prace
    AGENT_COMPLETED,    // Agent zakonczyl
    AGENT_FAILED,       // Agent zawiodl
    DATA_EXCHANGE,      // Komunikacja miedzy agentami
    APPROVAL_REQUEST,   // Prosba o zatwierdzenie
    ARTIFACT,           // Wyprodukowany artefakt
    SYSTEM_MESSAGE      // Komunikat systemowy
}
```

### 5.3 Mapowanie eventow na wiadomosci chatu

```kotlin
// cli/ui/ChatMessageMapper.kt

fun AgentEvent.toChatMessage(colorMap: Map<String, Color>): MultiAgentChatMessage? {
    val color = colorMap[sourceAgentId] ?: Color.Gray

    return when (this) {
        is AgentEvent.AgentStarted -> MultiAgentChatMessage(
            id = id, timestamp = timestamp,
            type = ChatMessageType.AGENT_STARTED,
            agentId = sourceAgentId, agentName = agentName, agentColor = color,
            content = "🟢 $agentName rozpoczal prace: $task"
        )
        is AgentEvent.StreamChunk -> if (!isComplete) MultiAgentChatMessage(
            id = "$sourceAgentId-stream", timestamp = timestamp,
            type = ChatMessageType.AGENT_MESSAGE,
            agentId = sourceAgentId, agentName = null, agentColor = color,
            content = accumulated, isStreaming = true
        ) else null  // Final chunk — replaced by persisted message
        is AgentEvent.AgentCompleted -> MultiAgentChatMessage(
            id = id, timestamp = timestamp,
            type = ChatMessageType.AGENT_COMPLETED,
            agentId = sourceAgentId, agentName = null, agentColor = color,
            content = "✅ Zakonczono ($tokensUsed tokens, $${"%.3f".format(costUsd)}): $summary",
            artifacts = artifacts
        )
        is AgentEvent.DataRequest -> MultiAgentChatMessage(
            id = id, timestamp = timestamp,
            type = ChatMessageType.DATA_EXCHANGE,
            agentId = sourceAgentId, agentName = null, agentColor = color,
            content = "💬 → ${targetAgentId ?: "wszystkim"}: $query"
        )
        is AgentEvent.ApprovalRequired -> MultiAgentChatMessage(
            id = id, timestamp = timestamp,
            type = ChatMessageType.APPROVAL_REQUEST,
            agentId = sourceAgentId, agentName = null, agentColor = color,
            content = "⚠️ Wymaga zatwierdzenia: $action",
            approval = PendingApproval(id, sourceAgentId, action, risk, details)
        )
        // ... inne typy
        else -> null
    }
}
```

### 5.4 Filtrowanie widoku

Uzytkownik moze:
- **Wszystko** — pelny interleaved stream
- **Agent X** — tylko wiadomosci od konkretnego agenta
- **Komunikacja** — tylko DataRequest/DataResponse miedzy agentami
- **Zatwierdzenia** — tylko ApprovalRequired oczekujace na decyzje
- **Artefakty** — tylko ArtifactProduced

---

## 6. GUI — wizualizacja przeplywu agentow

### 6.1 Uklad ekranu (rozszerzony o panel agentow)

```
┌────────────────────────────────────────────┬──────────────────────┐
│  CHAT (2/3)                                │ PANEL (1/3)          │
│                                            │                      │
│  ┌────────────────────────────────────┐    │ ┌──────────────────┐ │
│  │ [Filtr: Wszystko ▾] [🔵🟠🟣🔴]   │    │ │ AGENT FLOW       │ │
│  └────────────────────────────────────┘    │ │                  │ │
│                                            │ │  ┌─────────┐     │ │
│  Interleaved messages from all agents      │ │  │Analyst ✅│     │ │
│  (patrz sekcja 5.2)                       │ │  └────┬────┘     │ │
│                                            │ │       │          │ │
│                                            │ │  ┌────▼────┐     │ │
│                                            │ │  │Coder  🟢│     │ │
│                                            │ │  └──┬───┬──┘     │ │
│                                            │ │     │   │        │ │
│                                            │ │ ┌───▼┐ ┌▼──────┐ │ │
│                                            │ │ │Test│ │Review⏳│ │ │
│                                            │ │ │ 🟢 │ │       │ │ │
│                                            │ │ └────┘ └───────┘ │ │
│                                            │ └──────────────────┘ │
│                                            │                      │
│  ┌────────────────────────────────────┐    │ ┌──────────────────┐ │
│  │ 💬 Wiadomosc do agentow...        │    │ │ ZATWIERDZENIA    │ │
│  │ [@agent ▾] [Wyslij]               │    │ │                  │ │
│  └────────────────────────────────────┘    │ │ Coder: zapis     │ │
│                                            │ │ UserCtrl.kt      │ │
│                                            │ │ [✅] [❌] [✅All]│ │
│                                            │ └──────────────────┘ │
│                                            │                      │
│                                            │ ┌──────────────────┐ │
│                                            │ │ METRYKI          │ │
│                                            │ │ Tokens: 4.2K     │ │
│                                            │ │ Koszt:  $0.05    │ │
│                                            │ │ Czas:   45s      │ │
│                                            │ │ Agenci: 3/4 done │ │
│                                            │ └──────────────────┘ │
└────────────────────────────────────────────┴──────────────────────┘
```

### 6.2 Agent Flow — wizualizacja DAG

```kotlin
// cli/ui/components/AgentFlowPanel.kt

@Composable
fun AgentFlowPanel(
    agents: List<AgentInstanceState>,
    events: List<AgentEvent>,
    modifier: Modifier = Modifier
) {
    // Rysuje DAG agentow z kolorowymi statusami
    Canvas(modifier) {
        // Rozmieszczenie: topological sort
        val layers = topologicalLayers(agents)

        for ((layerIndex, layer) in layers.withIndex()) {
            for ((agentIndex, agent) in layer.withIndex()) {
                val x = agentIndex * NODE_WIDTH + agentIndex * SPACING_H
                val y = layerIndex * NODE_HEIGHT + layerIndex * SPACING_V

                // Rysuj node
                drawAgentNode(agent, x, y)

                // Rysuj polaczenia do dependsOn
                for (depName in agent.dependsOn) {
                    val dep = agents.find { it.name == depName } ?: continue
                    drawArrow(from = dep.position, to = agent.position)
                }

                // Animowana strzalka dla aktywnych eventow (DataRequest/Response)
                val activeExchanges = events.filter {
                    it is AgentEvent.DataRequest &&
                    (it.sourceAgentId == agent.id || it.targetAgentId == agent.id)
                }
                for (exchange in activeExchanges) {
                    drawAnimatedArrow(exchange)
                }
            }
        }
    }
}

@Composable
fun AgentNode(agent: AgentInstanceState) {
    val color = when (agent.status) {
        AgentInstanceStatus.PENDING -> Color.Gray
        AgentInstanceStatus.RUNNING -> Color.Green
        AgentInstanceStatus.WAITING_DATA -> Color.Yellow
        AgentInstanceStatus.WAITING_APPROVAL -> Color.Orange
        AgentInstanceStatus.COMPLETED -> Color.Blue
        AgentInstanceStatus.FAILED -> Color.Red
        AgentInstanceStatus.CANCELLED -> Color.DarkGray
    }

    val icon = when (agent.status) {
        AgentInstanceStatus.COMPLETED -> "✅"
        AgentInstanceStatus.RUNNING -> "🟢"
        AgentInstanceStatus.FAILED -> "❌"
        AgentInstanceStatus.WAITING_APPROVAL -> "⚠️"
        AgentInstanceStatus.WAITING_DATA -> "💬"
        else -> "⏳"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.2f)),
        border = BorderStroke(2.dp, color)
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$icon ${agent.name}", fontWeight = FontWeight.Bold)
            if (agent.status == AgentInstanceStatus.RUNNING) {
                Text(agent.currentPhase ?: "", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (agent.costUsd > 0) {
                Text("$${"%.3f".format(agent.costUsd)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

### 6.3 Panel zatwierdzen

```kotlin
@Composable
fun ApprovalPanel(
    approvals: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onReject: (String, String?) -> Unit,
    onApproveAll: (String) -> Unit    // Zatwierdz wszystkie od danego agenta
) {
    LazyColumn {
        items(approvals) { approval ->
            Card(Modifier.padding(4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row {
                        Text(approval.agentName, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        RiskBadge(approval.risk)
                    }
                    Text(approval.action)
                    approval.details.forEach { (key, value) ->
                        Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onReject(approval.id, null) }) { Text("Odrzuc") }
                        Button(onClick = { onApprove(approval.id) }) { Text("Zatwierdz") }
                        TextButton(onClick = { onApproveAll(approval.agentId) }) { Text("Zaufaj agentowi") }
                    }
                }
            }
        }
    }
}
```

---

## 7. Scenariusze interakcji

### 7.1 Uzytkownik wysyla wiadomosc do konkretnego agenta

```
Uzytkownik: @Coder Zmien walidacje email na regex
→ MultiAgentRunner przekazuje wiadomosc do agenta "Coder"
→ Coder otrzymuje jako user message w swoim AgentTurnLoop
→ Coder StreamChunk emitowany do interleaved chat
```

### 7.2 Agent prosi innego agenta o dane

```
Coder emits DataRequest(target="Analyst", query="Jakie pola ma User?")
→ EventBus dostarcza do Analyst
→ AgentEventHandler w Analyst tworzy nowy turn z pytaniem
→ Analyst odpowiada DataResponse z informacja
→ Coder otrzymuje odpowiedz i kontynuuje
→ W GUI: widoczna wymiana 💬 Coder→Analyst, 💬 Analyst→Coder
```

### 7.3 Agent prosi o spawning nowego agenta

```
Coder emits SpawnAgentRequest(profile="sre-engineer", task="Napisz testy")
→ MultiAgentOrchestrator odbiera, tworzy nowa instancje agenta
→ Emits AgentSpawned(spawnedAgentId="Tester")
→ Tester.AgentStarted emitowany
→ W GUI: nowy node "Tester" pojawia sie w DAG, polaczony z "Coder"
```

### 7.4 Uzytkownik zatwierdza/odrzuca grupowo

```
Uzytkownik klika "Zaufaj agentowi" przy Coder
→ Wszystkie przyszle ApprovalRequired od Coder sa auto-approved
→ W GUI: Coder node dostaje badge "trusted" 🔓
→ Inne agenty nadal wymagaja indywidualnego zatwierdzenia
```

### 7.5 Agent zawodzi — recovery

```
Tester emits AgentFailed(error="Compilation error", recoverable=true)
→ MultiAgentOrchestrator: moze ponowic lub eskalowac
→ W GUI: Tester node zmienia sie na ❌, opcja "Ponow" wyswietlona
→ Uzytkownik klika "Ponow" lub "Pomin"
→ Jezeli agents depending on Tester: moga byc uruchomione z czesciowymi wynikami
```

---

## 8. Implementacja krok po kroku

### Faza A: Event Bus + model danych (3-4 dni)

```
☐ AgentEvent sealed interface (wszystkie typy eventow)
☐ AgentEventBus (SharedFlow-based)
☐ AgentEventRepository (persystencja w SQLite)
☐ AgentSessionsTable + migracja
☐ AgentInstancesTable + migracja
☐ AgentEventsTable + migracja
☐ chat_messages.agent_instance_id kolumna + migracja
☐ Testy: EventBus emit/subscribe/filter
```

### Faza B: Agent lifecycle + orchestrator (4-5 dni)

```
☐ AgentEventHandler (obsluga przychodzacych eventow per agent)
☐ MultiAgentRunner rozszerzony o event emission
☐ AgentMetrics per-agent (rozszerzenie GlobalMetrics)
☐ DataRequest/DataResponse flow (request → wait → respond)
☐ ApprovalRequired flow (request → user decision → continue)
☐ SpawnAgentRequest flow (request → orchestrator → spawn)
☐ Testy: lifecycle (start→complete), data exchange, approval
```

### Faza C: Interleaved chat (3-4 dni)

```
☐ ChatMessageMapper (AgentEvent → MultiAgentChatMessage)
☐ Compose ChatPanel z kolorowymi agent labels
☐ Filtrowanie widoku (per agent, per type)
☐ Streaming display per agent (osobny stream state per agent)
☐ Approval buttons w chacie (inline)
☐ Artifact display w chacie
☐ DataExchange display w chacie
```

### Faza D: Wizualizacja przeplywu (2-3 dni)

```
☐ AgentFlowPanel (DAG visualization)
☐ AgentNode composable z statusem i postepem
☐ Animowane strzalki miedzy agentami (data exchange)
☐ ApprovalPanel z grupowym zatwierdzaniem
☐ Metryki agregowane (tokens, cost, time per session)
☐ Responsywny layout (resize 2/3 + 1/3)
```

### Faza E: Integracja i polish (2-3 dni)

```
☐ @agent mention w input (wyslij do konkretnego agenta)
☐ "Trust agent" (auto-approve przyszlych requestow)
☐ Retry failed agent
☐ Cancel individual agent
☐ Export session (events + messages → JSON)
☐ Testy integracyjne: pelny scenariusz 3 agentow
```

**Lacznie: ~15-19 dni roboczych** (Fazy A-E)

---

## Podsumowanie

System eventow opiera sie na **3 filarach**:

1. **AgentEventBus** (SharedFlow) — centralny hub z filtrowana subskrypcja, replay dla GUI, opcjonalna persystencja
2. **AgentEventHandler** per agent — obsluga DataRequest/Response z CompletableDeferred, approval flow z timeout
3. **Interleaved chat** — mapowanie eventow na kolorowe wiadomosci z filtrami, DAG wizualizacja w panelu bocznym

**Kluczowe decyzje:**
- Eventy sa **persystowane w DB** (replay, historia, debug)
- Kazdy agent ma **dedykowany Task + chat_messages z agentId** (izolacja kontekstu)
- Komunikacja jest **asynchroniczna** (emit → filter → collect, z timeout)
- GUI jest **reaktywne** (StateFlow/SharedFlow → Compose recomposition)
- Uzytkownik moze **zaufac agentowi** (auto-approve) lub zatwierdzac granularnie
