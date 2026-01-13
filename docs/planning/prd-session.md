<conversation_summary> <decisions>

1. Persona i platforma: Solo dev (początkujący/średniozaawansowany), małe/średnie projekty; IDE: IntelliJ; system docelowy na start: Windows.
2. Modele/providery: Lokalne przez Ollama + chmurowe Anthropic i OpenAI; wybór modelu per tryb (chat/plan/agent/weak). Akcje generowane przez LLM są parsowane z JSON (bez mapowania narzędzi w konfiguracji modeli).
3. Architektura: Plugin JVM (Kotlin/Java) + lokalny core w Python/FastAPI (HTTP na 127.0.0.1); wspólne API dla plugina i CLI; dane trwałe w SQLite.
4. Konfiguracja i kolejność nadpisywania: Global YAML (katalog użytkownika) → `.env` (tylko z katalogu projektu, w `.gitignore`) → YAML projektu → parametry CLI → ustawienia GUI (DB). „Last-writer-wins”, audit zmian, `schema_version`.
5. Bezpieczeństwo (MVP): Prosty JWT/sesyjny token między pluginem a core; toggle `no-egress`; brak ciężkich mechanizmów poza MVP.
6. Tryby pracy: Chat, Planning (read-only tools), Agent (read/write tools) + „Interactive” (domyślnie per-krok; opcjonalnie „approve all” po włączeniu w ustawieniach).
7. Rollback i diff: Snapshot per-krok; natywny diff IntelliJ; w czacie etykieta podsumowania zmian (`+N/−M` oraz liczba plików).
8. UI plugina: Prawy dock; start na widoku Chat; tryby jako przełącznik; kolejka kroków domyślnie widoczna (nad czatem przy wąskim oknie, w kolumnie 30% przy szerokim); osobne karty do konfiguracji.
9. Kontekst i „@providers”: `@file`, `@open`, `@selection`, `@folder`, `@docs`; przy polu promptu przycisk „+” (ostatnio otwarte/zmieniane pliki). `fetch_url_content` zgodnie z `no-egress`.
10. Biblioteka promptów: Autocomplete na `/komenda`; wstrzyknięcie promptu bez echa (opcjonalny tooltip).
11. Koszty/KPI: Globalny licznik sesji + etykiety kosztów per-krok (przełączalne); standardowe KPI (TTFB, czas zadania, % powodzeń, koszty in/out, udział modeli lokalnych).
12. Terminal: `run_terminal_command` tylko w CWD projektu; domyślnie PowerShell; denylista destrukcyjnych poleceń; timeout i limit outputu.
13. Indeks/RAG: Watcher zdarzeń systemowych; własny analizator poza IDE; prosty RAG na MVP.
14. Persistencja ustawień: Edytowalne w GUI sekcje trzymane w SQLite; YAML dla punktów nieobsługiwanych w GUI (np. lista surowych modeli).
15. Wstawianie kodu z czatu: Każdy blok — „wstaw do pliku”, „utwórz nowy plik”, „kopiuj”.
16. Historia/CLI parity: Nazwy sesji generowane przez LLM (edytowalne), eksport/import sesji (JSON), odtwarzanie w CLI.
17. Dystrybucja/aktualizacje: Kierunkowo zaakceptowane (plugin JetBrains + rdzeń Python); szczegóły polityki aktualizacji do doprecyzowania.

    </decisions>

<matched_recommendations>

1. Warstwowa konfiguracja + migracje schematów (przyjęte) — kolejność źródeł, `schema_version`, audyt, interpolacja env.
2. Capability flags i macierz zgodności (przyjęte kierunkowo) — walidacja modeli/providerów przy starcie; ostrzeżenia i degradacja funkcji.
3. JSON-DSL akcji + walidacja schematem (przyjęte kierunkowo) — akcje narzędzi jako obiekty JSON z wersjonowaniem.
4. Snapshoty i natywny diff (przyjęte) — snapshot per-krok; natywny viewer; podsumowanie `+N/−M`.
5. Interactive mode + kolejka kroków (przyjęte) — per-krok domyślnie; opcjonalne „approve all”.
6. Piaskownica terminala (przyjęte) — PowerShell, denylista, timeout, ograniczenie CWD.
7. RAG i limity (przyjęte kierunkowo) — watcher, prosty pipeline indeksowania, limity rozmiaru.
8. Telemetria/KPI i koszty (przyjęte) — licznik kosztów sesji i per-krok; standardowe KPI; ceny z YAML/.env + statystyki providerów.
9. IPC i lokalny serwis (przyjęte) — FastAPI na `127.0.0.1`, token sesyjny, port losowy/lockfile.
10. CLI parity i eksport sesji (przyjęte) — eksport/import JSON; re-run w CLI dla debugowania.
    </matched_recommendations>

<prd_planning_summary>
a. **Główne wymagania funkcjonalne (ze szczegółami operacyjnymi)**

* **Integracja modeli**

    * *Źródła*: Ollama (lokalnie), Anthropic, OpenAI (API).
    * *Konfiguracja modelu*: `model`, `provider`, `title`, `apiKey` (z `.env`/Credential Manager), `params` (np. `maxTokens`, `temperature`, `topP`, `response_format`), `capabilities` (flags).
    * *Wybór per tryb*: `default_models.{chat, plan, agent, weak}` (nadpisywalne).
* **Tryby pracy**

    * *Chat*: czat + kontekst; generowane bloki kodu z akcjami „wstaw/nowy/kopiuj”.
    * *Planning (read-only)*: generacja planu kroków (kolejka), narzędzia bez zapisu, każdy krok zatwierdzany (Interactive).
    * *Agent (read/write)*: plan + wykonanie kroków z narzędziami zapisującymi; zawsze snapshot przed zapisem; „Interactive” per-krok (default).
* **Narzędzia (MVP)**

    * `read_file`, `create_new_file`, `run_terminal_command`, `file_search`, `view_diff`, `read_currently_open_file`, `read_directory`, `fetch_url_content`, `grep_search`, `code_editing`, `knowledge_base`, `project_analysis`, `multi_edit`.
    * *Ograniczenia*: terminal tylko w CWD; denylista; timeout (np. 60 s); limit outputu (np. 5 MB).
* **Kontekst i indeksowanie**

    * *Providers `@`*: `@file`, `@open`, `@selection`, `@folder` (limit głębokości/rozmiaru), `@docs` (cache lokalny; blokada przez `no-egress`), opcjonalnie `@rules`.
    * *Watcher*: zdarzenia systemowe, ignorowane ścieżki (np. `.git`, `node_modules`, `build/target`, pliki > 2 MB — propozycja).
    * *RAG*: prosty (embedding + top-k); „Preview context” (docelowo) z kalkulacją tokenów.
* **Rollback i diff**

    * *Snapshoty*: per-krok do `.refio/snapshots/` (limit: np. 1 GB/100 snapshotów; LRU cleanup).
    * *Diff*: natywny viewer IntelliJ; w czacie etykieta `+N/−M` i liczba plików.
    * *Akcje*: „Rollback file” (ostatni snapshot pliku), „Rollback to snapshot” (cały projekt; potwierdzenie + lista plików).
* **Konfiguracja i przechowywanie**

    * *Precedencja*: global YAML → `.env` (projekt, w `.gitignore`) → projekt YAML → CLI → GUI/DB.
    * *GUI/DB*: ustawienia edytowalne (modele/aliasy wyboru, limity budżetu, tryby narzędzi `disabled/interactive/auto`, `no-egress`, ignorowane ścieżki indeksu).
    * *YAML*: pola nieobsługiwane w GUI (np. pełna lista modeli, globalne ścieżki).
* **Koszty i KPI**

    * *Koszty*: licznik sesji (tokens in/out, koszt) + etykiety per-krok (przełączalne); ceny w YAML dla `1M` tokenów IN/OUT; realne zużycie z providerów; dla lokalnych modeli — czas CPU/RAM jako metryka.
    * *KPI*: TTFB, czas zadania, % kroków zakończonych sukcesem, koszt per provider, udział lokalnych modeli; zapis w SQLite.
* **CLI parity**

    * *Komendy (przykłady)*:

        * `refio chat|plan|agent --interactive --session <name>`
        * `refio steps list|approve|diff|rollback`
        * `refio index status|rebuild`
        * `refio session export|import`
    * *Eksport sesji*: JSON/JSONL z `{ts, mode, input, context_refs, tool_calls, diffs, costs, schema_version}`.
* **Bezpieczeństwo/prywatność**

    * *Core API*: `127.0.0.1:<random_port>`, JWT/sesyjny token, lockfile; CORS wyłączony; `no-egress` jako global/projekt toggle.
    * *Sekrety*: `.env` tylko lokalnie; ostrzeżenia przy kluczach w YAML; redakcja potencjalnych sekretów w logach.

b. **Kluczowe historie użytkownika i ścieżki (rozszerzone)**

* **U1 – Chat z kontekstem**: Solo dev wybiera model i tryb Chat → dodaje `@file`/„+” → wysyła prompt → otrzymuje odpowiedź z blokami kodu → wybiera „wstaw do pliku” (pre-snapshot) lub „utwórz nowy plik” → diff dostępny na żądanie.
* **U2 – Planning (read-only)**: Użytkownik podaje cel → agent tworzy plan kroków (kolejka) → każdy krok ma opis, przewidywany efekt i *podgląd* (bez zapisu) → użytkownik zatwierdza sekwencyjnie → wynik (raport).
* **U3 – Agent (read/write)**: Użytkownik uruchamia agenta → każdy krok wymaga potwierdzenia (Interactive) → zapis zmian do plików po snapshot’cie → w razie błędu „Rollback file” lub „Rollback to snapshot”.
* **U4 – Debug/CLI**: Z GUI eksportuje sesję → odtwarza w CLI (`--session <file.json>`) → porównuje wyniki (parytet).
* **U5 – Koszty i limity**: Podczas sesji użytkownik widzi globalny licznik kosztów i etykiety per-krok; przy przekroczeniu progów — alert (docelowo).
* **U6 – Indeks i kontekst**: Watcher aktualizuje indeks; w razie dużych zmian użytkownik pauzuje/wznawia; `Preview context` pozwala ograniczyć pliki.

c. **Kryteria sukcesu i pomiar (doprecyzowane)**

* **Stabilność**: Crash-free rate ≥ 99% w scenariuszach U1–U3 (Windows/IntelliJ 2024.x).
* **Ukończenie zadań**: ≥ 75% aktywnych użytkowników kończy „build/plan/agent” z wynikiem „success” (metryka sesyjna).
* **Wydajność**: TTFB ≤ 2 s (lokalny model) / ≤ 4 s (API); mediana czasu kroku ≤ 20 s (bez tooli kompilacji).
* **Koszt**: Odchylenie prognozy kosztu vs rozliczenie providerów ≤ 5%.
* **Adopcja lokalnych modeli**: ≥ 50% sesji z udziałem modelu lokalnego (MVP cel).
* **Obserwowalność**: 100% kroków z logiem (narzędzie, czas, koszt, rezultat) w SQLite.

d. **Nierozwiązane kwestie (do doprecyzowania i wpisania do PRD jako TODO)**

* **JSON-DSL akcji**: finalny schemat, wersjonowanie, kody błędów (`error_code`, `recoverable`).
* **Capability flags**: definicje (`tool_use`, `json_mode`, `streaming`, `max_context`, `thinking`) i polityki fallbacku.
* **RAG**: parametry chunkingu (np. 800–1200 tokenów, overlap 100–200), top-k/top-p, limity projektu (linie/MB).
* **Denylista terminala**: pełna lista (np. `Remove-Item -Recurse -Force`, `format`, `diskpart`, odpowiedniki `rm -rf`, komendy sieciowe przy `no-egress`).
* **Retencja/rotacja**: domyślne wartości (np. snapshoty: 1 GB/100 szt.; SQLite: 90 dni), „purge” ręczny i automatyczny.
* **Aktualizacje**: kanały (Stable/EAP), podpisy, rollback 1-klik, zgodność plugin↔core.
* **Budżety**: limity per projekt/sesja i progi alertów; polityka wstrzymania agenta po przekroczeniu.
* **Zestaw `@providers`**: finalny zakres na MVP i UX „Preview context”.
  </prd_planning_summary>

<unresolved_issues>

1. Finalizacja schematu JSON-DSL narzędzi (w tym wersjonowanie, pola obowiązkowe i obsługa błędów).
2. Sformalizowana macierz capabilities modeli (flags, minimalne wymagania per tryb, reguły degradacji).
3. Docelowe parametry RAG (chunking, overlap, top-k, limity rozmiaru repo i plików).
4. Pełna denylista/whitelist poleceń terminala (PowerShell/WSL/Git Bash) + mapowanie CWD w WSL.
5. Retencja/rotacja danych (SQLite, snapshoty) – wartości domyślne i UI do zarządzania.
6. Polityka aktualizacji i podpisywania wydań (JetBrains Marketplace, rdzeń PyInstaller).
7. Budżety i alerty kosztów/KPI (progi, reakcje agenta).
8. Finalny zakres i UX providerów `@...` (w tym podgląd „Preview context”).
   </unresolved_issues>

<conversation>
**I. Persona i środowisko**  
- Solo dev, małe/średnie projekty, IntelliJ; Windows jako pierwsza platforma. Unikanie webview; natywny UI IntelliJ.

**II. Architektura i IPC**

* Plugin JVM (Kotlin/Java) + core Python/FastAPI na `127.0.0.1` (port losowy), JWT/sesja, lockfile.
* Wspólne API dla plugina i CLI; dane w SQLite (WAL, indeksy po `project_id`, `session_id`, `ts`).

**III. Konfiguracja i precedence**

* Global YAML → `.env` (tylko projekt, w `.gitignore`) → YAML projektu → CLI → GUI (DB).
* `schema_version`, migrator, „last-writer-wins”, audit zmian; ostrzeganie, gdy sekrety trafią do YAML.

**IV. Modele i capabilities**

* Źródła: Ollama, Anthropic, OpenAI; wybór modeli per tryb (`chat/plan/agent/weak`).
* JSON „akcji” generowanych przez model; walidacja po stronie core (schema).

**V. Tryby pracy i Interactive**

* Chat; Planning (read-only narzędzia); Agent (read/write) — domyślnie Interactive per-krok; opcjonalne „approve all” po włączeniu w ustawieniach.

**VI. Narzędzia i sandbox**

* Lista MVP (operacje na plikach, grep/search, diff, edycja, analiza, multi-edit, terminal z ograniczeniami).
* Terminal: tylko CWD projektu; denylista, timeout, limit outputu.

**VII. Kontekst, indeks, RAG**

* Providers `@file/@open/@selection/@folder/@docs`; przy prompt’cie „+” (ostatnio otwarte/zmieniane).
* Watcher zdarzeń; prosty RAG; `no-egress` ogranicza `@docs/fetch_url_content`.

**VIII. UI plugina (prawe dock)**

* Toolbar: Mode, Model/Provider, Thinking, No-egress, status indeksu/koszt sesji.
* Kolejka kroków domyślnie widoczna: nad czatem (wąsko) lub w kolumnie 30% (szeroko).
* Główna przestrzeń: Chat/Logs; na dole pole promptu z autocomplete `/` i `@` oraz przyciskiem „+”.
* Diff i rollback: natywny viewer; „Rollback file” i „Rollback to snapshot”.

**IX. Koszty i KPI**

* Globalny licznik sesji + etykiety per-krok (model, tokens in/out, czas, koszt — przełączalne).
* KPI: TTFB, czas zadania, % sukcesów, koszt per provider, udział lokalnych modeli.

**X. Historia i CLI parity**

* Nazwy sesji generowane przez LLM; edytowalne/pinowane; eksport/import JSON; re-run w CLI.
* Logi: inline (tool+diff+czas+koszt) i pełne w zakładce Logs (payloady narzędzi).

**XI. Persistencja i migracje**

* SQLite (WAL); retention/rotacja do ustalenia; snapshoty per-krok (limit/cleanup LRU).
* Migracje DB i YAML (`schema_version`).

**XII. Dystrybucja i aktualizacje**

* Kierunek: plugin JetBrains + core PyInstaller; kanały Stable/EAP; rollback — do doprecyzowania.

**XIII. Kryteria sukcesu**

* Stabilne działanie na Windows; ≥75% użytkowników realizuje „build/plan/agent” z sukcesem; KPI i koszty w akceptowalnych progach.

  </conversation>

</conversation_summary>
