# Dokument wymagań produktu (PRD) - Refio

## 1. Przegląd produktu

Refio to lokalny agent AI dla programistów pracujących w **IntelliJ IDEA**, z planowanym wsparciem **CLI** w przyszłych wersjach. Zapewnia stabilną integrację z lokalnymi i chmurowymi modelami LLM, spójne API narzędzi, śledzenie kosztów i pełną kontrolę prywatności.

**Architektura:** Plugin IntelliJ (Kotlin) z **wbudowanym rdzeniem** (również Kotlin), dane trwałe w SQLite. Komunikacja in-process (brak HTTP overhead).

**Funkcjonalność:** Trzy tryby pracy (chat, planning read-only, agent read/write), prosty RAG, zestaw 10 narzędzi do analizy i edycji plików, orchestration z reflection loops, automatyczne budowanie kontekstu projektu.

**Środowisko:** IntelliJ 2024.x (Windows, Linux, macOS); wsparcie dla Ollama (lokalnie), LM Studio (lokalnie), Anthropic, OpenAI, Gemini, OpenRouter (chmura).

**Priorytety:** Brak webview w UI, stabilność, pełna integracja z IntelliJ Platform, bezpieczeństwo lokalne, zero zewnętrznych zależności.

## 2. Problem użytkownika

* Większość narzędzi AI dla IDE opiera się na webview/HTML
* Komercyjne pluginy wspierają tylko wybranych providerów; brak elastyczności w wyborze modeli
* Rozwiązania chmurowe budzą obawy o prywatność kodu; brak łatwego trybu pracy całkowicie lokalnego
* Brak przejrzystego śledzenia kosztów i możliwości wyboru tańszych modeli pomocniczych
* Brak bezpiecznego trybu planowania z kontrolowanym wykonywaniem zmian (snapshot/diff/rollback)
* Brak inteligentnego budowania kontekstu projektu - użytkownik musi ręcznie podawać pliki

## 3. Wymagania funkcjonalne

3.1 Integracja modeli i providerów

* Wsparcie providerów: Ollama (lokalnie), LM Studio (lokalnie, OpenAI-compatible), Anthropic, OpenAI, Gemini; możliwość rozszerzenia.
* [ToDo] Konfiguracja per model: id, provider, tytuł/alias, parametry (np. maxTokens, temperature, topP, response_format), capabilities (np. tool_use, json_mode, streaming, max_context, thinking).
* [ToDo] Wybór domyślnych modeli per tryb: chat, plan, agent, weak; nadpisywalne na poziomie projektu/sesji.
* Przestrzeganie trybu no-egress (blokada zapytań sieciowych poza hostem).

3.2 Tryby pracy

* **Chat:** Konwersacja z LLM + automatyczny kontekst projektu; zwracane bloki kodu gotowe do wstawienia
* **Planning (read-only):** Generacja planu kroków z użyciem narzędzi bez zapisu
* **Agent (read/write):** Planowanie i wykonanie zmian przy użyciu narzędzi zapisujących;

**Nowe możliwości:**
* **Orchestration:** Reflection-based execution z decision loops (CONTINUE, MODIFY_PLAN, ASK_USER, ABORT)
* **Auto Context Building:** ProjectAnalyzerService analizuje strukturę projektu; ContextService buduje kontekst automatycznie
* [ToDo] **MCP Integration:** Model Context Protocol servers dla zewnętrznych źródeł kontekstu (w development)

3.3 Narzędzia (MVP)

* read_file, 
* create_new_file, 
* [ToDo] run_terminal_command (tylko CWD projektu; PowerShell domyślnie; denylista; timeout; limit outputu), 
* file_search, 
* view_diff, 
* [ToDo] read_currently_open_file, 
* read_directory, 
* [ToDo] fetch_url_content (zależne od no-egress), 
* grep_search, 
* code_editing, 
* advance_code_editing, 
* [ToDo] knowledge_base (RAG), 
* [ToDo] project_analysis, 
* [ToDo] multi_edit.

Każde wywołanie narzędzia logowane: parametry, czas, sukces/błąd, rozmiar I/O.

3.4 Kontekst, indeksowanie i RAG

[ToDo/WIP] * **Dostawcy kontekstu (14 built-in + MCP):**
  - **Podstawowe:** @file, @open, @selection, @folder, @current, @recent, @clipboard
  - **Zaawansowane:**
    - **@codebase** - semantyczne wyszukiwanie w kodzie projektu (RAG)
    - **@docs** - semantyczne wyszukiwanie w dokumentacji (RAG)
    - **@url** - pobieranie treści ze stron web (HTTP/HTTPS)
    - **@commit** - szczegóły commita git (hash, autor, zmiany)
    - **@grep** - szybkie wyszukiwanie tekstowe w projekcie
  - **IDE Integration:** @terminal, @problems (błędy kompilacji), @diff (uncommitted changes)
  - **Opcjonalnie:** @rules (Agents.md)
  - **Extensible:** MCP (Model Context Protocol) servers dla zewnętrznych źródeł

[Done/WIP] **RAG Implementation:**
  - ✅ **File Analysis Service** (ADR-0045): Analiza pojedynczych plików z językowymi analizerami (Kotlin/Java/Python/TypeScript), semantic chunking, in-memory cache (TTL 5min)
  - ✅ **Embeddings Service**: OpenAI (text-embedding-3-small), Ollama (nomic-embed-text), cache, batch processing
  - ✅ **Background Indexing**: Async indexing do RAG w tle (non-blocking), automatic re-indexing on file changes
  - 🔶 **Startup Indexing** (ADR-0047 - Proposed): Automatyczne indeksowanie projektu przy starcie IDE, checksum-based change detection, progress indicator w status bar
  - 🔶 **AST-based Analysis** (ADR-0046 - Proposed): JavaParser/Kotlin Compiler dla głębokiej analizy struktury kodu, dependency graphs, pattern detection
  - RagSearchService z cosine similarity search
  - RagRepository z full CRUD dla indexed files, chunks, embeddings
  - DocumentationRepository dla zewnętrznych źródeł dokumentacji
  - Project-isolated: per projectRoot, configurable similarity threshold (0.5f default)
  - Top-k results with similarity scores
[Done] * **Semantic Chunking**: Full-file, class-level, function-level chunks z metadatą (annotations, docs, signatures)
[Proposed] * **File Watcher**: IntelliJ VirtualFileListener dla automatycznej aktualizacji indeksu; ignorowane ścieżki (np. .git, node_modules, build/target, pliki > 5 MB domyślnie)

[Done] **3.4.1 Context Optimization & Conversation Summarization** (ADR-0043)
* **Funkcjonalność:** Podsumowanie długich konwersacji w celu zmniejszenia zużycia tokenów i kosztów API
* **Implementacja:**
  - `ChatService.summarizeConversation()` - generuje podsumowanie od ostatniego summary marker
  - `SessionManager.generateSummary()` - UI integration (button "📦 Summarize" w toolbarze)
  - `ChatView.compactConversation()` - wyświetla podsumowanie jako system message w chacie (bez modalnego okna)
  - `ContextService` - ładuje tylko wiadomości od ostatniego podsumowania + summary jako header
* **Metadata:** `conversation_summary` w `chat_messages.metadata` z polami: `type`, `summarized_count`, `summary_index`, `timestamp`
* **UI:** Dedykowana bańka podsumowania z `LCATheme.summaryBubble*` kolorami, polskie komunikaty
* **Oszczędności:** ~50-70% redukcja tokenów kontekstu dla długich konwersacji (50+ wiadomości)
* **Wsparcie:** Wielokrotne podsumowania w długich sesjach (summary_index: 1, 2, 3...)

[ToDo] 3.5 Snapshot, diff i rollback
* Snapshot per-krok do katalogu projektu (.shadowagent/snapshots/); limit retencji (np. 1 GB/100 snapshotów; LRU cleanup).
* Integracja z natywnym viewerem diff w IntelliJ.
* Operacje: Rollback file (ostatni snapshot pliku), Rollback to snapshot (cały projekt, z listą plików i potwierdzeniem).

[ToDo/WIP] 3.6 Konfiguracja i precedence

* Warstwowa konfiguracja: global YAML (katalog użytkownika) → .env (tylko z katalogu projektu; w .gitignore) → YAML projektu → parametry CLI → ustawienia GUI (DB).
* Last-writer-wins i audyt zmian; schema_version w konfiguracjach i DB; ostrzeganie, jeśli klucze API wykryte w YAML.

[ToDo/WIP] 3.7 Koszty i telemetria

* Licznik kosztów sesji i etykiety per-krok (tokens in/out, koszt, czas, model, provider).
* Cenniki providerów w YAML/hardcoded dla 1M tokenów IN/OUT; realne użycie pobierane z odpowiedzi API (gdy dostępne).
* Dla modeli lokalnych metryki CPU/RAM/czas jako procent kosztu.
* KPI: TTFB, czas kroku, procent sukcesów, koszt per provider, udział modeli lokalnych; zapis w SQLite.

[ToDo/WIP] 3.8 Historia, eksport i parytet CLI

* Nazwy sesji generowane automatycznie; edytowalne/pinowane.
* Eksport/import sesji do/z JSON/JSONL z polami {ts, mode, input, context_refs, tool_calls, diffs, costs, schema_version}.
* Parytet funkcjonalny CLI i plugina dla głównych ścieżek.

[ToDo/WIP] 3.9 Bezpieczeństwo i prywatność

* **In-process architecture:** Brak external ports, brak network exposure (core wbudowany w plugin)
* **No-egress mode:** Blokuje wszystkie zewnętrzne połączenia sieciowe (toggle w UI)
* **Path sandbox:** Wszystkie operacje na plikach ograniczone do project CWD (JVM-based)
* **Terminal denylist:** Blokowanie destrukcyjnych komend (rm, format, shutdown, etc.)
* **Secret redaction:** Automatyczne maskowanie API keys, tokenów, haseł w logach
* **Uprawnienia narzędzi:** Konfigurowalne disabled/interactive/auto per narzędzie
* **File limits:** Domyślnie 2MB per plik, 10MB total read, timeout protection
* **Database:** Local SQLite w `%USERPROFILE%\.IntelliJIdea2024.x\system\refio\`

[Done] 3.10 GUI i ergonomia

* Plugin w prawym docku: przełącznik trybów, wybór modelu/providerów, thinking/no-egress, status indeksu, licznik kosztów sesji.
* Kolejka kroków widoczna; nad czatem przy wąskim oknie, w kolumnie 30% przy szerokim.
* Pole promptu z autocomplete dla komend (/...) i providerów kontekstu (@...).
* Na blokach kodu: akcje wstaw/utwórz plik/kopiuj; przed zapisem wykonywany snapshot.
* W czacie podsumowanie zmian: znacznik +N/−M i liczba plików.

## 4. Granice produktu

* Poza MVP: zaawansowany RAG, wizualny edytor promptów, integracje VCS (git), rozbudowana wizualizacja logów i historii, pełny podgląd „thinking” dla wszystkich modeli, zaawansowane polityki budżetów/alertów, panel administracyjny cenników.
* Założenia: użytkownik ma zainstalowaną JDK/IntelliJ, uprawnienia do tworzenia plików w projekcie, dostęp do kluczy API (gdy używa chmury), środowisko Windows (startowo)/Mac/Linux.
* Ograniczenia: terminal tylko w CWD projektu; denylista komend destrukcyjnych; timeouty; limit rozmiaru kontekstu; brak pracy z repozytoriami zdalnymi w MVP.
* Niezmienniki: brak webview w pluginie; rdzeń działa lokalnie; tryb Interactive domyślny dla trybu agent.

## 5. Historyjki użytkowników

Format: ID, Tytuł, Opis, Kryteria akceptacji.

#### US-001 [Done]
Tytuł: Panel konfiguracji,
Opis: Jako użytkownik chce móc konfgurować plugin i jego działanie
Kryteria akceptacji:

* Po naciśnięciu buttona setup pojawia się okno ustawień gdzie poszczególne elementy są wyświetlane jako taby "na górze panelu ustawień"
* Zmiany w ustawieniach zapamiętywane są bez konieczności używania przycisku save, mają zostać zapamiętane automatyczne
* Nie widzę przycisków Cancel, Save, Mam przycisk "reset to default"

#### US-002 [Done]
Tytuł: Konfiguracja kluczy API,
Opis: Jako użytkownik chce móc podać posiadane klucze API dla providerów typu OpenAI, Antropic, OpenRouter, Gemini
Kryteria akceptacji:

* Po naciśnięciu buttona setup pojawia się okno ustawień, i można przejść do zakładki Providers
* W zakładce Providers widzę ustawienia dla: Ollama - Server Endpoint, Anthropic - Api Key, OpenAi - ApiKey, Gemini - ApiKey, OpenRouter - ApiKey  
* Mogę podać API Key w polu maskowanym
* Po naciśnięciu buttona Test Connection - idzie uderzenie przez provider i widzę status połączenia
* Po podaniu AipKey dla danego providera na liście modeli będę widział modele dostarczane przez danego providera, następuje automatyczne odświeżenie
* Zmiany w ustawieniach zapamiętywane są bez konieczności używania przycisku save, mają zostać zapamiętane automatyczne

#### US-003 [Done]
Tytuł: Konfiguracja modeli LLM
Opis: Jako użytkownik chce móc zdecydować jakie modele będę używał w pluginie
Kryteria akceptacji:

* Po naciśnięciu buttona setup pojawia się okno ustawień, i można przejść do zakładki Models
* W zakładce Models widzę listę modeli dla providerow, dla których podałem klucze API lub Konfigurację
* Na liście modeli widzę parametry danego modela: Nazwę wewnętrzną, Context size, Capabilities, Price (input token, output token) , oraz opcję do pokazywania modelu na dropdown pod inputem użytkownika
* Pod listą modeli widzę button z akcją "Refresh" - powoduje ono odświeżenie listy modeli od providerów
* Pod listą modeli widzę dropdown do konfigurowania modeli prez zadanie - Default model to jest domyślny dla wszyskich zadań dodatkowo widzę możliwość wybrania specjalizowanych modeli
* Zmiany w ustawieniach zapamiętywane są bez konieczności używania przycisku save, mają zostać zapamiętane automatyczne

#### US-004 [ToDo]
Tytuł: Konfiguracja promptów
Opis: Jako użytkownik chce móc zedytować prompty systemowe i użytkownika używane w pluginie
Kryteria akceptacji:

* Po naciśnięciu buttona setup pojawia się okno ustawień, i można przejść do zakładki Prompts
* Na liście widzę wszystkie prompty zdefiniowane w plugin, mam możliwości ich edycji
* Na liście widzę wszystkie komendy zdefiniowane w plugin i używane w input po wybraniu klawisza /command
* Zmiany w ustawieniach zapamiętywane są bez konieczności używania przycisku save, mają zostać zapamiętane automatyczne

#### US-005 [Done]
Tytuł: Historia sessji
Opis: Jako użytkownik chcę wyświetlić poprzednie sesje Chat/Plan/Agent w osobnym zakładce, oraz móc kontynować przerwaną sesję
Kryteria akceptacji:

* Po naciśnięciu buttona Historia wyświetla mi się panel poprzednich sesji
* W panelu widzę nazwę sesji, tryb, ilość tokentów, kroków, itd
* Po kliknięciu w sesję przechodzę do trybu chat gdzie mogę kontynuować sesję

#### US-006 [Done]
Tytuł: Rozpoczęcie pracy w agente,
Opis: Jako solo dev chcę rozpocząć rozmowę na temat projektu w trybie chat
Kryteria akceptacji:

* Po naciśnięciu buttona new session następuje wyczyszczenie okna chata i w backend tworzy się nowa sesja
* Zostaje zbudowany context projektu
* Podaję pytanie w oknie wprowadzania komunikatu użytkownika, po naciśnięciu Ctrl+Enter lub Buttona send komunikat użytkownika pojawia się w oknie chat oraz następuje wysłanie komunikatu użytkownika do backendu
* Zostaje wysłany request do wybranego modelu LLM zawierający prompt systemowy, context projektu i komunikat użytkownika
* Do okna chata zostaje dodana odpowiedź modelu LLM

#### US-007 [WIP]
Tytuł: Tryb chat z kontekstem
Opis: Jako użytkownik chcę rozmawiać z LLM i dołączać kontekst @file/@selection/@folder oraz stosować komendy /command.
Kryteria akceptacji:

* Autocomplete @... działa i dołącza referencje do zapytania.
* Odpowiedzi mogą zawierać bloki kodu z akcjami wstaw/nowy/kopiuj.
* Próba dołączenia zbyt dużych plików skutkuje komunikatem i podpowiedzią ograniczenia.
* Komanda jest w input wyśietlana jako rozwijany autokomplete nad input panel, wciśnięcie /klawisz powoduje pojakwienie się autocomplete z dodaniem kommand

#### US-008 [Done]
Tytuł: Planning (read-only)
Opis: Jako użytkownik chcę wygenerować plan kroków bez modyfikowania repozytorium.
Kryteria akceptacji:

* Narzędzia w planning działają wyłącznie w trybie odczytu.
* Każdy krok wymaga zatwierdzenia (Interactive).
* Wynik to raport z listą kroków i wynikami narzędzi oraz podsumowaniem.

#### US-009 [Done]
Tytuł: Agent (read/write) z interaktywnym zatwierdzaniem
Opis: Jako użytkownik chcę wykonywać plan z zapisami do plików z potwierdzeniem każdego kroku.
Kryteria akceptacji:

* Przed każdą modyfikacją wykonywany jest snapshot.
* Użytkownik zatwierdza/odrzuca krok; po odrzuceniu agent proponuje alternatywę lub zatrzymuje się.
* Wynik to raport z listą kroków i wynikami narzędzi oraz podsumowaniem.

#### US-010 [ToDo]
Tytuł: Konfiguracja providerów i modeli
Opis: Jako solo dev chcę dodać klucze API i skonfigurować modele (Ollama/Anthropic/OpenAI/Gemini), aby wybrać model per zadanie.
Kryteria akceptacji:

* Wybór domyślnych modeli dla chat/plan/agent/weak zapisuje się i działa po restarcie.
* Błędne klucze są walidowane i sygnalizowane.
* No-egress blokuje zewnętrzne wywołania.

#### US-011 [ToDo]
Tytuł: Przełącznik no-egress
Opis: Jako użytkownik chcę jednym przełącznikiem zablokować ruch sieciowy na zewnątrz.
Kryteria akceptacji:

* Po włączeniu no-egress wszystkie próby wywołań zewnętrznych są anulowane i logowane.
* fetch_url_content i chmurowi providerzy są niedostępni; UI informuje o blokadzie.
* Wyłączenie przywraca działanie bez restartu.

#### US-012 [Done]
Tytuł: Praca w różnych trybach
Opis: Jako solo dev chcę pracować nad projektem w różnych trybach. Na początku omawam w chat, pózniej w plan, a na końcu implementuje zmiany w trybie agent
Kryteria akceptacji:

* Zmiana trybu pracy nie może tworzyć nowej sesji
* Do planowania trafia konwersacja w chacie
* Plan powstaje na podstawie całej konwersacji a nie tylko na podstawie prompta użytkownika

#### US-013 [Done]
Tytuł: Praca w trybie autonomicznym lub interaktywnym
Opis: Jako solo dev chcę widzieć jak Agent AI pracuje nad projektem w trybie automatycznym, w tym przypadku w trybie auto chcę widzieć co będzie wykonywane w danej chwili oraz widzieć rezultat pracy narzędzia, jako krótkie podsumowanie. W trybie interaktrynym chcę widzieć co będzie dane narzędzie robiło i potwierdzać czy ma to wykonywać, po wykonaniu powinienem widzieć krótkie podsumowanie. W bloku przykład sesji agenta masz opisany przykład jak działa to w innym narzędziu.
Kryteria akceptacji:
* W toolbarze obok wyboru modelu jest button do pracy interaktywnej/auto
* W dolnym status bar jak wyświetlani licznik kroków z max oraz button do przerwania pracy agenta
* W trybie interaktywnym przed rozpoczęciem danego kroku jest wypisywana informacja co będzie on robił i potwierdzanie przez uzytkownika
* W trybie automatycznym użytkownik widzi kolejne kroki, w danej chwili może jest przerwać.

#### US-014 [ToDo]
Tytuł: Snapshot i rollback pliku
Opis: Jako użytkownik chcę cofnąć ostatnią zmianę w danym pliku.
Kryteria akceptacji:

* Polecenie Rollback file przywraca poprzedni stan.
* Operacja jest logowana i odwracalna do kolejnego snapshotu.
* Brak snapshotu skutkuje komunikatem.

#### US-015 [ToDo]
Tytuł: Rollback całego projektu do snapshotu
Opis: Jako użytkownik chcę przywrócić projekt do wybranego snapshotu.
Kryteria akceptacji:

* Lista plików objętych rollbackiem jest prezentowana do potwierdzenia.
* Operacja kończy się sukcesem lub raportuje konflikty.
* Zdarzenie zapisane w historii sesji.

#### US-016 [ToDo]
Tytuł: Thinking toggle
Opis: Jako użytkownik chcę włączyć wyświetlanie thinking dla modeli, które to wspierają.
Kryteria akceptacji:

* UI pokazuje przełącznik; niedostępny dla modeli bez capability.
* Po włączeniu sekcje thinking są widoczne i oznaczone.
* Dane thinking nie są zapisywane, jeśli użytkownik tak ustawi (opcja).

#### US-017 [Done]
Tytuł: Biblioteka promptów i komendy /prompt_id
Opis: Jako użytkownik chcę wstrzykiwać zapisane prompty przez komendy slash.
Kryteria akceptacji:

* Autocomplete pokazuje dostępne komendy.
* Wstrzyknięty prompt nie dubluje się na ekranie.

#### US-018 [Todo]
Tytuł: Ładowanie rules/Agents.md
Opis: Jako użytkownik chcę dołączać reguły z plików tekstowych.
Kryteria akceptacji:

* Domyślnie do zapytania jest dołączany plik Agents.md jeżeli istnieje.
* Wskazanie pliku rules dołącza treść do zapytania.

#### US-019 [Done]
Tytuł: read_file
Opis: Jako użytkownik chcę podglądać zawartość pliku.
Kryteria akceptacji:

* Narzędzie zwraca treść pliku lub błąd, gdy plik nie istnieje/za duży.
* Operacja zapisywana jest w logu narzędzi.

#### US-020 [Done]
Tytuł: create_new_file
Opis: Jako użytkownik chcę utworzyć nowy plik w projekcie.
Kryteria akceptacji:

* Konflikt nazw skutkuje błędem.
* Ścieżki poza projektem są blokowane.

#### US-021 [Wip]
Tytuł: code_editing
Opis: Jako użytkownik chcę edytować istniejący plik przez LLM z kontrolowanym diffem.
Kryteria akceptacji:

* Błędy walidacji lub konflikt wersji są raportowane.

#### US-022 [ToDo]
Tytuł: multi_edit
Opis: Jako użytkownik chcę wykonać serię edycji w jednym kroku.
Kryteria akceptacji:

* Operacja atomowa: przy błędzie żadna zmiana nie jest zapisana.
* Raport zawiera listę plików i sumaryczny diff.

#### US-023 [Wip]
Tytuł: file_search
Opis: Jako użytkownik chcę przeszukiwać pliki po treści z maskami.
Kryteria akceptacji:

* Wspiera maski i wykluczenia.
* Duże wyniki są stronicowane/limitowane.
* Wyniki zawierają ścieżkę i fragment dopasowania.

#### US-024 [Wip]
Tytuł: grep_search
Opis: Jako użytkownik chcę szybkie wyszukiwanie wzorców z domyślną ignorancją cache/node_modules/ukrytych.
Kryteria akceptacji:

* Domyślne wykluczenia aktywne; można je nadpisać.
* Zwracane są pozycje linii i plików.
* Limit czasu chroni przed zablokowaniem.

#### US-025 [Wip]
Tytuł: view_diff
Opis: Jako użytkownik chcę zobaczyć różnice między dwoma plikami.
Kryteria akceptacji:

* Integracja z viewerem diff w IntelliJ.
* Gdy plik nie istnieje, narzędzie zwraca błąd.
* Obsługa plików tekstowych; binarne odrzucane.

#### US-026 [ToDo]
Tytuł: read_currently_open_file
Opis: Jako użytkownik chcę szybko dodać do kontekstu aktualnie otwarty plik.
Kryteria akceptacji:

* Zwraca zawartość aktywnego edytora.
* Brak aktywnego edytora skutkuje komunikatem.

#### US-027 [ToDo]
Tytuł: read_directory
Opis: Jako użytkownik chcę listę plików i katalogów w ścieżce.
Kryteria akceptacji:

* Obsługa limitu głębokości i rozmiaru.
* Wykluczenia zgodne z konfiguracją indeksu.

##### US-028 [ToDo]
Tytuł: fetch_url_content
Opis: Jako użytkownik chcę pobrać treść strony.
Kryteria akceptacji:

* Działa tylko, gdy no-egress jest wyłączony.
* Błędy HTTP raportowane z kodami.
* Rozmiar treści limitowany.

#### US-029 [ToDo]
Tytuł: knowledge_base (RAG)
Opis: Jako użytkownik chcę zadać pytanie o projekt i otrzymać odpowiedź z cytatami źródeł.
Kryteria akceptacji:

* Zwracane są odnośniki do plików i fragmenty.
* Wynik zawiera listę top-k dopasowań.
* Limit rozmiaru kontekstu respektowany.

#### US-030 [Wip]
Tytuł: project_analysis
Opis: Jako użytkownik chcę raport o strukturze projektu, językach, zależnościach.
Kryteria akceptacji:

* Raport zawiera podsumowanie plików, wykryte frameworki i potencjalne hot-spoty.
* Czas wykonania mieści się w limicie.

#### US-031 [ToDo]
Tytuł: run_terminal_commandcommand
Opis: Jako użytkownik chcę uruchomić bezpieczne polecenie w terminalu w CWD projektu.
Kryteria akceptacji:

* Denylista blokuje destrukcyjne komendy (i odpowiedniki).
* Timeout i limit outputu działają.
* Log zawiera polecenie, czas i status.

#### US-032 [Done]
Tytuł: Kolejka kroków i Interactive
Opis: Jako użytkownik chcę przeglądać i akceptować kolejkę kroków.
Kryteria akceptacji:

* Widok kolejki pokazuje opis, narzędzia i przewidywany efekt.
* Dostępne są działania: approve/skip/stop.
* Po włączeniu approve-all kroki wykonywane są seryjnie.

#### US-033 [Done]
Tytuł: Licznik kosztów i metryki per-krok
Opis: Jako użytkownik chcę widzieć koszty i metryki każdej akcji.
Kryteria akceptacji:

* Etykieta pokazuje model, tokens in/out, czas, koszt.
* Sumaryczny licznik sesji widoczny na toolbarze.

#### US-034 [Todo]
Tytuł: Indeks projektu i watcher
Opis: Jako użytkownik chcę mieć aktualny indeks projektu.
Kryteria akceptacji:

* Watcher aktualizuje indeks po zmianach.
* Możliwe jest ręczne pauzowanie/wznawianie i rebuild.
* Ignorowane ścieżki działają.

#### US-035 [ToDo]
Tytuł: Widok diff i podsumowanie zmian
Opis: Jako użytkownik chcę zobaczyć podsumowanie +N/−M i liczbę plików.
Kryteria akceptacji:

* Znacznik zmian widoczny przy odpowiedzi.
* Otwarcie diff prowadzi do natywnego viewera.
* W przypadku wielu plików dostępna jest lista.

#### US-036 [ToDo]
Tytuł: Precedencja konfiguracji
Opis: Jako użytkownik chcę przewidywalnej kolejności nadpisywania ustawień.
Kryteria akceptacji:

* Global YAML → .env → YAML projektu → CLI → GUI/DB.
* Wyświetlany jest aktywny zestaw wartości.

#### US-037 [Done]
Tytuł: Ochrona sekretów
Opis: Jako użytkownik chcę, aby sekrety nie trafiały do logów.
Kryteria akceptacji:

* Klucze z .env są redagowane w logach.
* Wykrycie klucza w YAML skutkuje ostrzeżeniem i sugestią przeniesienia do .env.

#### US-038 [Done]
Tytuł: Ograniczenia rozmiaru i czasu
Opis: Jako system chcę chronić się przed przeciążeniem.
Kryteria akceptacji:

* Globalne limity rozmiarów plików, kontekstu i czasu narzędzi.
* Przekroczenie limitu zwraca czytelny błąd i wskazówki.

#### US-039 [Done]
Tytuł: Historia zmian i logi kroków
Opis: Jako użytkownik chcę mieć pełną historię operacji.
Kryteria akceptacji:

* Każdy krok zapisany w SQLite z ts, narzędziem, parametrami, czasem, wynikiem, kosztami.
* W zakładce Logs widok pełnych rekordów.

#### US-040 [Done]
Tytuł: Wstawianie kodu z czatu
Opis: Jako użytkownik chcę wstawić blok kodu do pliku lub utworzyć nowy.
Kryteria akceptacji:

* Akcje dostępne przy każdym bloku.


#### US-041 [ToDo]
Tytuł: Konfiguracja uprawnień narzędzi
Opis: Jako użytkownik chcę ustawić narzędzia na disabled/interactive/auto.
Kryteria akceptacji:

* Zmiany działają bez restartu.
* Tryb agent respektuje ustawienia.

#### US-042 [Done]
Tytuł: Status indeksu i budowa na żądanie
Opis: Jako użytkownik chcę widzieć status i wymusić rebuild.
Kryteria akceptacji:

* Toolbar pokazuje stan indeksu.
* Komenda rebuild wykonuje pełny przegląd projektu.

#### US-043 [Done]
Tytuł: Tryb approve-all
Opis: Jako użytkownik chcę wykonywać kroki bez per-krok potwierdzania.
Kryteria akceptacji:

* Po włączeniu agent działa bez zatrzymań do pierwszego błędu.
* W każdej chwili można przerwać i wrócić do Interactive.

#### US-044 [ToDo]
Tytuł: Blokada ścieżek poza projektem
Opis: Jako system chcę zabronić operacji poza katalogiem projektu.
Kryteria akceptacji:

* Próby dostępu poza projektem są blokowane i logowane.
* UI pokazuje przyczynę odrzucenia.

#### US-045 [ToDo]
Tytuł: Błędy i komunikaty
Opis: Jako użytkownik chcę czytelnych błędów z kodami i sugestiami.
Kryteria akceptacji:

* Błędy narzędzi zawierają kod, opis i wskazówkę.
* Błędy modeli informują o rate limit, auth, no-egress itp.

#### US-046 [ToDo]
Tytuł: Import reguł i promptów z projektu
Opis: Jako użytkownik chcę nadpisywać prompty i reguły plikami w repo.
Kryteria akceptacji:

* Pliki są wykrywane i wersjonowane przez schema_version.
* Konflikty rozwiązywane przez precedence.

#### US-047 [Wip]
Tytuł: Tryb tylko lokalny
Opis: Jako użytkownik chcę wymusić użycie wyłącznie modeli lokalnych.
Kryteria akceptacji:

* Przełącznik blokuje providerów chmurowych.
* UI informuje o ograniczeniach.

#### US-048 [Done]
Tytuł: Minimalna telemetria lokalna
Opis: Jako użytkownik chcę, aby dane nie opuszczały hosta.
Kryteria akceptacji:

* Wszystkie logi i DB zapisywane lokalnie.
* Brak automatycznej telemetrii zewnętrznej.

#### US-049 [ToDo]
Tytuł: Podgląd i ograniczenie kontekstu
Opis: Jako użytkownik chcę kontrolować rozmiar kontekstu.
Kryteria akceptacji:

* UI pokazuje szacowany rozmiar tokenów dla kontekstu.
* Można odznaczać pliki przed wysłaniem.

#### US-050 [Done]
Tytuł: Tryb tylko-do-odczytu całej aplikacji
Opis: Jako użytkownik chcę zablokować wszelkie zapisy.
Kryteria akceptacji:

* Flaga globalna uniemożliwia create_new_file, code_editing, multi_edit, rollback.
* UI oznacza tryb i podpowiada jak wyłączyć.

## 6. Metryki sukcesu

* Stabilność: crash-free rate ≥ 99% dla scenariuszy na Windows/IntelliJ 2024.x.
* Ukończenie zadań: ≥ 75% aktywnych użytkowników kończy sesje build/plan/agent z wynikiem success.
* Obserwowalność: 100% kroków zapisane w SQLite z pełnym logiem.

## 7. UX/UI

* Lokalizacja UI: prawy dock IntelliJ; start na widoku Chat.
* Toolbar: wybór trybu, model/provider, thinking, no-egress, status indeksu, licznik kosztów sesji.
* Kolejka kroków: u góry nad czatem (wąskie okno) lub w kolumnie 30% (szerokie okno); widoczne akcje approve/skip/stop/approve-all.
* Pole promptu: autocomplete dla /komend i @providerów; przycisk „+” z ostatnio otwieranymi/zmienianymi plikami.
* Odpowiedzi: bloki kodu z akcjami wstaw/utwórz plik/kopiuj; etykieta podsumowania zmian +N/−M i liczba plików; link do diff.
* Widok Logs: podsumowanie kroków inline i pełne rekordy (payloady narzędzi) w dedykowanej zakładce.
* Stany i błędy: spójne, krótkie komunikaty z kodami błędów i sugestiami naprawy.
* Dostępność: skróty klawiaturowe do przełączania trybów, wykonywania approve/stop, wstawiania bloków kodu; brak webview dla stabilności.

## 8. Tech

* Architektura: plugin JVM (Kotlin/Java).
* DB: SQLite w trybie WAL, indeksy po project_id, session_id, ts; schema_version i migrator.
* Konfiguracja: global YAML → .env (tylko katalog projektu) → YAML projektu → CLI → GUI/DB; audit zmian; redakcja sekretów.
* Modele: adaptery providerów (Ollama/Anthropic/OpenAI/Gemini) z capability flags; JSON-DSL akcji narzędzi z walidacją schematem; fallback polityki.
* Narzędzia: sandboxowane; denylista poleceń; timeouty i limity rozmiaru/wyjścia; blokada ścieżek poza projektem.
* Indeks/RAG: watcher zdarzeń systemowych; ignorowane ścieżki; prosty RAG top-k; parametry chunkingu w konfiguracji (domyślne w MVP).
* Telemetria: log kroków i kosztów w SQLite; brak zewnętrznej telemetrii; możliwość eksportu sesji/raportów do JSON.
* Dystrybucja/aktualizacje: plugin JetBrains Marketplace (docelowo).
* Bezpieczeństwo: no-egress, tryb tylko-do-odczytu, redakcja sekretów, ograniczenia ścieżek; brak ruchu przychodzącego z poza localhost.
* CLI: polecenia parytetowe z pluginem, m.in.:

    * refio chat|plan|agent --interactive --session <name>
    * refio steps list|approve|diff|rollback
    * refio index status|rebuild
    * refio session export|import

## 9. Pozostałe

9.1 Założenia i zależności

* IntelliJ 2024.x, Windows 10/11, JDK zainstalowane.
* Użytkownik posiada klucze API do providerów chmurowych; Ollama zainstalowana lokalnie dla modeli on-prem (Anthropic/OpenAI/Gemini/OpenRouter jako chmura).
* Uprawnienia do zapisu w projekcie; brak ograniczeń antywirusowych blokujących rdzeń.

9.2 Retencja i rotacja

* Snapshoty: domyślnie 1 GB lub 100 sztuk; LRU cleanup; konfigurowalne.
* Logi i DB: rotacja po 90 dniach (do doprecyzowania); komenda purge.

9.3 Ryzyka i mitgacje

* Niezgodność wersji plugin↔core: wersjonowanie protokołu, komunikat i instrukcja aktualizacji.
* Niestabilność modeli lokalnych: capability flags i szybki fallback.
* Koszty chmurowe: licznik i progi ostrzeżeń (w MVP etykiety i raporty).
* Wydajność indeksu na dużych repo: limity rozmiaru, ignorowane ścieżki, rebuild na żądanie.

9.4 Kwestie do doprecyzowania (TODO)

* Finalny schemat JSON-DSL akcji, kody błędów i wersjonowanie.
* Macierz capabilities per model i reguły degradacji.
* Parametry RAG (chunking, overlap, top-k) i limity projektu.
* Pełna denylista/whitelist poleceń terminala dla PowerShell/WSL/Git Bash.
* Retencja danych (SQLite, snapshoty) i panel zarządzania.
* Polityka aktualizacji, podpisywanie wydań, rollback 1-klik.
* Budżety i alerty kosztów/KPI (progi i reakcje agenta).
* Zakres i UX providerów @... oraz podgląd rozmiaru kontekstu.

9.5 Lista kontrolna ukończenia PRD

* Każda historyjka ma mierzalne kryteria akceptacji i jest testowalna.
* Uwzględniono historię dotyczącą uwierzytelniania.
* Pokryto kluczowe interakcje użytkownika dla pełnego MVP: konfiguracja modeli, tryby pracy, narzędzia, kontekst, snapshot/diff/rollback, koszty, CLI parytet, indeks i bezpieczeństwo.
* Zdefiniowano metryki sukcesu i granice MVP.
