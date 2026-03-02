# Refio Tools - Usage Guide (Kotlin Implementation)

Last Update: 2025-12-06
Implementation: Kotlin (src/main/kotlin/pl/jclab/refio/core/tools/)

---

## Overview

System narzedzi dostarcza **10 narzedzi** (READ_ONLY: 5, WRITE: 5). W praktyce aktywnych jest **9**: RunTerminalCommandTool jest w kodzie, ale domyslnie wylaczony (zakomentowany w ToolFactory.createWriteTools()).

### Dostepnosc per tryb
- CHAT: READ_ONLY (sugestie/manual)
- PLAN: READ_ONLY (planowanie)
- AGENT: READ_ONLY + WRITE (wg TaskMode i ToolPermissionsService)
Wykonanie narzedzi egzekwuje tryb zadania w runtime (PLAN blokuje WRITE).

---

## Architecture

### Core Components
```
tools/
├── base/
│   ├── Tool.kt                 # Interface
│   ├── ToolRegistry.kt         # Registry, TaskMode-aware filtering
│   └── ToolFactory.kt          # Tworzy i rejestruje narzedzia
├── security/
│   ├── FileLimits.kt           # Limity rozmiaru/glebokosci
│   └── CommandDenylist.kt      # Blokowane komendy
├── PathSandbox.kt              # Walidacja sciezek (sandbox)
└── implementations/
    ├── ReadFileTool.kt         # [READ_ONLY]
    ├── ReadDirectoryTool.kt    # [READ_ONLY]
    ├── FileSearchTool.kt       # [READ_ONLY]
    ├── GrepSearchTool.kt       # [READ_ONLY]
    ├── ViewDiffTool.kt         # [READ_ONLY]
    ├── CreateNewFileTool.kt    # [WRITE]
    ├── CodeEditingTool.kt      # [WRITE]
    ├── AdvanceCodeEditingTool.kt # [WRITE]
    ├── MultiEditTool.kt        # [WRITE]
    └── RunTerminalCommandTool.kt # [WRITE, disabled]
```

### Tool Interface (skrot)
```kotlin
interface Tool {
    val name: String
    val description: String
    val mode: ToolMode
    val category: ToolCategory
    suspend fun execute(params: Map<String, Any>): ToolResult
    fun validateParams(params: Map<String, Any>)
    fun getParameterSchema(): Map<String, Any>
}
```

---

## Security and limits
- PathSandbox: pilnuje sciezek w obrebie projectRoot. **Znany P0: symlink escape (PathSandbox.kt:45)** – traktuj sciezki z symlinkami jako niebezpieczne do czasu poprawki.
- FileLimits (domyslne): maxFileSize 2 MB; maxFilesInDirectory 1000; maxSearchDepth 10; maxSearchResults 100; maxGrepResults 500; exclude common build/out i rozszerzenia binarne.
- CommandLimits (jesli terminal zostanie wlaczony): timeoutSeconds 30, maxOutputSize 100000 chars, maxConcurrentCommands 5. CommandDenylist blokuje komendy destrukcyjne/sieciowe.
- Snapshots: narzedzia NIE tworza snapshotow; orchestration/AgentExecutor musi wykonac snapshot-before-write.

---

## READ_ONLY Tools (5)

### 1) read_file (DATA_PRODUCING)
- Params: `path` (wymagany, relative).
- Dzialanie: resolve w sandboxie, sprawdza regular file i limit rozmiaru, czyta UTF-8; metadata: file_size, line_count.
- Bledy: file not found; not a regular file; file too large; security error.

### 2) read_directory (DATA_PRODUCING)
- Params: `path` (domyslnie "."), `recursive` (false), `max_depth` (3, cap do maxSearchDepth).
- Dzialanie: listuje katalog (opcjonalnie rekurencyjnie), pomija excluded dirs, limituje maxFilesInDirectory; zwraca drzewo tekstowe, metadata: file_count, directory_count.
- Bledy: directory not found; not a directory; limit exceeded; security error.

### 3) file_search (DATA_PRODUCING)
- Params: `pattern` (glob, wymagany), `path` ("."), `max_depth` (10 cap), `offset` (0), `limit` (maxSearchResults).
- Dzialanie: walking z exclude dirs/ext, glob->regex, offset/limit na wynikach; zwraca posortowane relative paths, metadata: has_more.
- Bledy: directory not found; not a directory; invalid offset/limit; security error.

### 4) grep_search (DATA_PRODUCING)
- Params: `pattern` (regex, wymagany), `path` ("."), `file_pattern` (glob, default "*"), `case_sensitive` (false), `max_results` (100 cap maxGrepResults).
- Dzialanie: search do maxSearchDepth, pomija excluded dirs/ext i pliki > maxFileSize; zwraca `path:line: snippet`; tnie przy max_results.
- Bledy: directory not found; not a directory; security error; limit reached.

### 5) view_diff (DATA_PRODUCING)
- Params: `file1` (wymagany), `file2` (opcjonalny), `content2` (alternatywa dla file2).
- Dzialanie: czyta file1 i file2 lub content2, generuje prosty diff liniowy z +/-; sandbox enforced.
- Bledy: file missing; file is directory; security error.

---

## WRITE Tools (5 w kodzie, aktywne 4)

### 6) create_new_file (FILE_MODIFYING)
- Params: `path`, `content` (wymagane).
- Dzialanie: tworzy parent dirs, sprawdza maxFileSize; jesli plik istnieje -> success=true + warning, bez zapisu.
- Bledy: security error; parent not directory; content too large.
- Ograniczenie: brak snapshotu (P0, zalezne od orchestration layer).

### 7) code_editing (legacy search-replace)
- Params: `path`, `old_string`, `new_string` (wymagane), `replace_all` (default false).
- Dzialanie: gdy plik nie istnieje i old_string == "" -> tworzy plik z new_string. Inaczej czyta plik (regular, w limicie), wymaga wystapienia old_string; gdy replace_all=false i wystapien >1 -> blad; zapisuje replaceFirst/replaceAll. Metadata: replacements, lengths.
- Bledy: file not found (gdy old_string niepusty); not a regular file; file too large; string not found; string appears multiple times; security error.
- Ograniczenia: brak snapshot/rollback; brak permissions enforcement (US-037).

### 8) advance_code_editing (LLM-assisted, rekomendowane)
- Params: `path` (wymagany), `edit_description` (wymagany). Legacy old_string mode usuniety.
- Dzialanie: wykrywa jezyk (25+), buduje prompty (PromptsService/ConfigService), wywoluje LLMClient (TaskMode.AGENT defaults, temp=0.2), ekstrakcja code fence lub fallback, generuje unified diff, zapisuje plik; wspiera streaming via ExecutionEventListener/StreamCallback. Snapshots zapewnia warstwa wyzej.
- Bledy: missing file; file too large; failed to extract code block; LLM failure; security error.
- Koszty: metadata tokens_in/out, cost_usd; provider/model wg ConfigService.

### 9) multi_edit (FILE_MODIFYING)
- Params: `edits` (lista map: path, old_string, new_string – wymagane).
- Dzialanie: waliduje wszystkie pliki (istnieja, regular, limit), sprawdza old_string; dopiero potem replaceFirst dla kazdego; brak rollbacku/snapshotu wewnatrz toola.
- Bledy: missing edits; file not found; not a regular file; file too large; string not found; security error.

### 10) run_terminal_command (EXECUTION) — **domyslnie wylaczone**
- Params: `command` (wymagany string).
- Dzialanie (po wlaczeniu): ProcessBuilder w project root, CommandDenylist + CommandLimits, stdout/stderr scalone i ewentualnie przyciete; timeout enforced.
- Status: nie rejestrowany; wlaczyc dopiero po uszczelnieniu denylisty i polityki sandboxu (znany bypass risk) oraz po naprawie symlink escape.
- Bledy: command blocked; timeout; exit != 0; security error.

---

## AdvanceCodeEditingTool – kluczowe szczegoly (ADR 0017)
- Prompty: system/user z PromptsService (fallback na default). Zmienne: FILE_PATH, LANGUAGE, ORIGINAL_CONTENT, EDIT_DESCRIPTION.
- Ekstrakcja kodu: regex na ```lang```, potem dowolny fence, fallback full response.
- Diff: unified diff a/path -> b/path. Snapshot tworzony przez orchestration, nie przez tool.
- Supported languages: >25 (kt/java/py/ts/js/go/rs/c/cpp/cs/rb/php/swift/scala/sh/ps1/sql/html/css/json/xml/yaml/md/txt ...).
- Performance: LLM mode typowo 3-12s (dominacja LLM), legacy (gdyby byl) <100ms.

---

## Setup & Usage (skrot)
```kotlin
val factory = ToolFactory(
    projectRoot = Paths.get("/path/to/project"),
    toolRegistry = ToolRegistry(),
    llmClient = llmClient,
    configService = configService,
    promptsService = promptsService,
    taskRepository = taskRepository,
    fileLimits = FileLimits.DEFAULT,
    commandLimits = CommandLimits.DEFAULT,
    commandDenylist = CommandDenylist.DEFAULT
)
val count = factory.registerAllTools()
val tool = toolRegistry.getTool("read_file") ?: error("not found")
val result = tool.execute(mapOf("path" to "README.md"))
```
ToolRegistry.getAvailableTools(taskMode, permissionsService, taskId) filtruje wg uprawnien; validateParams rzuca IllegalArgumentException przy brakach.

---

## Error handling (wybrane)
- File not found / Not a regular file / File too large / Path outside project
- String not found / String appears N times (code_editing, multi_edit)
- Security error (sandbox, denylist)
- Command blocked / timeout / exit != 0 (terminal)
- Failed to extract code block / LLM request failed (advance_code_editing)

---

## Performance targets (orientacyjne)
- read_file <50ms dla plikow <1MB
- read_directory <200ms dla 1000 plikow depth=3
- file_search <2s dla 1000 plikow
- grep_search <1s dla 1000 plikow
- code_editing <100ms dla plikow <100KB
- advance_code_editing 3-12s (zaleznie od LLM)
- multi_edit <500ms dla 5 plikow
- run_terminal_command timeout 30s (narzedzie wylaczone)

---

## Known issues & TODO
- P0: brak snapshot-before-write w create_new_file, code_editing, advance_code_editing, multi_edit (orchestration musi zapewnic snapshoty/rollback).
- P0: PathSandbox symlink escape (PathSandbox.kt:45) – blokuje bezpieczne wlaczenie terminala.
- P1: RunTerminalCommandTool domyslnie wylaczony; wymaga przegladu denylisty/sandboxu.
- P1: Tool permissions backend (US-037) brak egzekucji.
- P2: AdvanceCodeEditingTool bez testow end-to-end i walidacji skladni po edycji.
- P2: SnapshotService nie przywraca (brak rollbacku).
