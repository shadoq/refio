Refio

### Główny problem
Pluginy do AI dla InteliJ lub commandline są głównie napisane w TypeScript i działają dobrze tylko z dużymi modelami językowymi. Większość pluginów posiada interfejs wykonany w HTML i uruchamiany jako interfejs desktopowy, powoduje to niestabilną pracę pluginu. Komercyjne pluginy tylko współpracują z wybranymi modelami AI. Dotego dochodzą kwestie prywatności, działając lokalnie można mieć pewność że kod nie wycieknie do chmury.

### Najmniejszy zestaw funkcjonalności
- Współpraca z modelami AI dostępnymi lokalnie porzez. Ollama lub API: Antropic, OpenAI, OpenRouter
- Zunifikowany dostęp do modeli poprzez swój własny interfejs wraz z providerami do danego dostawcy modelu
- Trakowanie zapytań do modeli AI oaz ich odpowiedzi, wraz z kalkulacją kosztów oraz zapisywanie do loga API
- Automatyczne budowanie konteku projektu w celu lepszego działania model LLM
- Gui - CMD: command line wraz z parametrami wtedy jest wykonywany process i oudput leci na konsolę
- Gui - pluginu dla InteliJ: zintegrowany z IDE, z możliwością wyboru modelu, providerów, historii zapytań i odpowiedzi

- Typowe historie użytkownika:
a: Tryb chat - użytkownik podaje pytanie i otrzymuje odpowiedź od modelu LLM, nie są używane wtedy żadne narzędzia, może być dostarczany kontekts projektu
b: Tryb planning - użytkownik podaje prompt, a LLM analizuje go i buduje plan działania oraz generuje rezultat przy pomocy narzędzi. W tym trybie używane są tylko narzędzia które nie dokonują zmian w projekcie (pracują w trybie tylko do odczytu)
c: Tryb agent - użytkownik podaje prompt, a LLM analizje go i buduje plan oraz realizuje wykonanie planu przy pomocy narzędzi które mogą dokonywać zmian w projekcie (tryb do odczytu i zapisu)

Opcje pracy: - opcje modyfikujące sposób działania aplikacji i generowania kodu
- Włączenie tryb "Interactive" gdzie aplikacja prosi o potwierdzenie każdego kroku agenta
- model - model LLM używany w chat, plan, agent
- Włączenie trybu "thinking" - niektóre modele LLM pozwalają na generowanie myśli (thinking) które są widoczne dla użytkownika
- weak model - model o niskim koszcie używany w pobocznych operacjach, np. podsumowanie rezultatu, domyślnie używany główny
- prompts - biliotek promtów które są dodawane do promtu użytkownika na jego prosbę "stosuje wtedy zapis: /prompt_id"
- rules - są to proste pliki tekstowe które są dołączane do zapytań idących do modelu AI - standartowo agent wspiera ładowanie pliku Agents.md
- tools - konfiguracja która pozwala włączać narzędzia używane podczas pracy
- documentation - konfiguracja która pozwala konfigurtować dokumentu które są dołączane do zapytań idących do modelu AI na podstawie indeksu i zapytan
- index - zindeksowany projekt który jest dołączany do zapytań jako kontekst przy pomocy RAG
- prompts - jest możliwość nadpisywania promptów używanych w aplikacji

Narzędzia w wersji MVP:
- read_file - odczytuje plik z projektu
- create_new_file - tworzy nowy plik
- run_terminal_command - uruchamia komendę w terminalu, zwraca output, nie pamięta poprzednich akcji w terminalu
- file_serach - szuka tekstu w plikach projektu, umożliwia stosowania maski dla przetwarzanych plików 
- view_diff - zwraca diff dwóch plików
- read_currently_open_file - odczytuje plik który jest aktualnie otwarty w edytorze InteliJ
- read_directory - zwraca pliki i katalogi w podanym katalogu
- fetch_url_content - pobiera zawartość strony www, nie działa z loklanymi plikami
- grep_search - szuka podanego wzoraca w projekcie, unika przeszukiwania cache, node modules, ukrytych plików, itd
- code_editing - edytuje dany plik przy pomocy specjalzowanego promptu, zwraca do głównego modelu diff
- knowledge_base - odpytuje RAG o projekt
- project_analysis - dokonuje analizy projektu i zwraca raport
- multi_edit - dokonuje edycji pliku, przyjmuje ścieżkę do pliku oraz tablicę ze zianami

TechSpec:
- język python 3.12
- baza danych - lokalna baza daych typu sqlite
- GUI - dla InteliJ plugin, dla CMD zwykła konsola

### Co NIE wchodzi w zakres MVP
- Zaawansowany silny RAG, w mvp używamy prostego
- Podgląd kodu generowanego przez LLM
- Integracja z systemami kontroli wersji (git, svn)
- Wizualizacja, edycja promoptów, przegląd logów aplikacji, itd.

### Kryteria sukcesu
- Aplikacja działa prawidłowo jako plugin do InteliJ oraz jako aplikacja command line
- 75% użytkowników dokonało budowy projektu przy pomocy aplikacji