import type { Translation } from "../../core";
import type { help as en } from "../en/help";

export const help: Translation<typeof en> = {
  title: "Pomoc",
  intro: "Jak liczymy miary, które widać w widokach benchmarku i na stronach poszczególnych wyników.",

  metricRefioScoreName: "Refio Score",
  metricRefioScoreDesc:
    "Jedna liczba, która wyznacza kolejność w rankingu. Na jakość w dwóch trzecich składa się ręczna Średnia ocena, a w jednej trzeciej Ocena sędziów wystawiana na ślepo (gdy żaden sędzia nie ocenił grupy, liczy się tylko ocena ręczna). Ocena ręczna waży więcej, bo to zdanie autora benchmarku, a sędziowie są drugą opinią. Tę jakość mnożymy przez Średnią stabilność. Mnożymy, a nie dodajemy, żeby model, który za każdym razem zawodzi tak samo, nie zyskiwał na samej powtarzalności. Najmniej stabilny model zachowuje 80% swojej jakości. Liczy się każdy werdykt sędziego, także agenta, który sam wykonał dany przebieg, bo sędziowie oceniają na ślepo. Gdy grupa nie ma pomiaru stabilności, widać '-'. Wartość bezwzględna pozostaje porównywalna w czasie. Przełącznik „względem lidera” nad rankingiem działa inaczej: w każdym zadaniu dzieli jakość przez najlepszy wynik w tym zadaniu (dzięki temu trudne zadanie nie obniża nikogo, kto wypadł w nim najlepiej, jak się dało), stosuje ten sam mnożnik stabilności i pokazuje wynik jako procent wyniku lidera (100%). Ten widok zmienia się za każdym razem, gdy dochodzi silniejszy model albo nowe zadanie.",
  metricAvgScoreName: "Średnia ocena",
  metricAvgScoreDesc:
    "Ogólna ocena jakości grupy wyników. Wartość każdego kryterium dzielimy przez najwyższą wartość jego skali, a potem uśredniamy po kryteriach i próbach.",
  metricJudgeScoreName: "Ocena sędziów",
  metricJudgeScoreDesc:
    "Ogólna jakość w ocenie silnych sędziów (agentów Claude Code i Codex), liczona niezależnie od ręcznej Średniej oceny. Dla każdego wyniku bierzemy medianę ocen sędziów w każdym kryterium, normalizujemy ją z wagami tak samo jak ocenę ręczną, a potem uśredniamy po ocenionych próbach. Licznik pokazuje, ile prób oceniono, a ile jest wszystkich.",
  metricPassRateName: "Zaliczone",
  metricPassRateDesc:
    "Ile prób uzyskało znormalizowaną ocenę co najmniej 50% (3 z 6, czyli wynik działa, choć może mieć widoczne wady). Pozwala szybko sprawdzić, jak często model sobie radzi.",
  metricFirstShotName: "Pierwsza próba",
  metricFirstShotDesc:
    "Znormalizowana ocena pierwszej próby. Etykieta OK/Fix wynika z kryterium works_out_of_box, a gdy go brak, z oceny pierwszej próby.",
  metricReliabilityName: "Niezawodność",
  metricReliabilityDesc:
    "Czy kolejne próby dostają podobne oceny. Wartość mieści się w zakresie 0-100%. Im bliższe sobie oceny, tym wyższa niezawodność.",
  metricAvgStabilityName: "Średnia stabilność",
  metricAvgStabilityDesc:
    "Na ile powtarzalny jest model, gdy kilka razy rozwiązuje to samo zadanie, uśrednione po zadaniach. W odróżnieniu od Niezawodności sprawdza też, czy próby są zbudowane w ten sam sposób, i bierze pod uwagę zdanie sędziów. Gdy model nie ma żadnej grupy stabilności, widać '-'. Szczegóły w sekcji o stabilności poniżej.",
  metricLocalViabilityName: "Przydatność lokalna",
  metricLocalViabilityDesc:
    "Dotyczy tylko modeli lokalnych. localQualityRatio porównuje średnią ocenę modelu lokalnego z najlepszą średnią oceną modelu w chmurze. Jako miarę stabilności bierzemy Niezawodność, a gdy jej brak, Zaliczone.",
  metricAvgDurationName: "Średni czas",
  metricAvgDurationDesc:
    "Średni czas działania prób w grupie (model i środowisko), po zastosowaniu filtrów. Formularz administracyjny przyjmuje sekundy, a dłuższe wartości interfejs pokazuje w minutach i sekundach.",
  metricLlmEstName: "Szacunek LLM",
  metricLlmEstDesc:
    "Szacowany czas przetwarzania tokenów, podzielony na przetwarzanie wejścia (prefill) i generowanie (decode). Szacunek opiera się na zmierzonym czasie próby, bo dane benchmarku nie zawierają na razie osobnych pomiarów TTFT ani generowania.",
  metricTokenSpeedName: "Szybkość tokenów",
  metricTokenSpeedDesc:
    "Rzeczywista przepustowość przetwarzania wejścia (prefill) i generowania (decode), wyliczona z liczby tokenów i czasu każdego przebiegu. To szybkość uzyskana w benchmarku, a nie surowy pomiar od dostawcy.",
  metricAvgApiCostName: "Średni koszt API",
  metricAvgApiCostDesc:
    "Średni koszt chmury lub API jednej próby w grupie (model i środowisko), po zastosowaniu filtrów. Do porównywania przebiegów między sobą zwykle nadaje się lepiej niż inne miary kosztu.",

  fieldTaskName: "Zadanie",
  fieldTaskDesc: "Scenariusz benchmarku, który oceniamy, na przykład Snake.",
  fieldModelName: "Model",
  fieldModelDesc: "Identyfikator modelu i jego nazwa wyświetlana, pobrane z results.json.",
  fieldEnvironmentName: "Środowisko",
  fieldEnvironmentDesc:
    "Gdzie model był uruchomiony, na przykład lokalnie na DGX albo przez API w chmurze. Środowisko jest typu lokalnego albo chmurowego.",
  fieldAttemptName: "Próba",
  fieldAttemptDesc: "Numer kolejnej próby dla tego samego zadania, modelu i środowiska.",
  fieldTokensName: "Tokeny",
  fieldTokensDesc:
    "Tokeny wejściowe / wyjściowe, jeśli znamy ich liczbę. Z tych liczb wyliczamy też szacowaną szybkość przetwarzania wejścia i generowania.",
  fieldAttachmentsName: "Załączniki",
  fieldAttachmentsDesc:
    "Zrzuty ekranu, podglądy HTML, filmy lub osadzone treści dołączone do konkretnego wyniku. Nie są wymagane.",

  normTitle: "Normalizacja ocen",
  normP1a: "Surowe wartości kryteriów korzystają ze skali zdefiniowanej w pliku ",
  normP1b:
    ". Wszystkie kryteria mają tę samą skalę 0-6: 0 brak, 1 fragment, 2 częściowo, 3 działa z wyraźnymi wadami, 4 dobrze, 5 bardzo dobrze, 6 wyjątkowo. Ocenę normalizujemy wzorem ",
  normP1c:
    ", więc 6 daje 100%, 4 to 67%, a 3 to 50%. Wyniki ocenione przed wprowadzeniem skali 0-6 przeliczyliśmy tak, że ich najwyższa ocena odpowiada 4 (dobrze), bo dawna skala nie odróżniała dobrego wyniku od wyjątkowego. Jedynie dawna ocena wyglądu „excellent” zamieniła się w 6.",
  normP2:
    "Ocena wyniku to średnia wszystkich znormalizowanych ocen kryteriów, które ten wynik ma. Wiersze rankingu łączą potem te oceny dla każdej pary model i środowisko, uwzględniając aktywne filtry.",
  normP3a: "Kolejność w rankingu wyznacza kolumna ",
  normP3b: ". Gdy dwa wiersze mają taką samą średnią ocenę, decyduje średnia z ",
  normP3c: ", potem średnia z ",
  normP3d: ", a na końcu kolumna ",
  normP3e: ".",

  fieldsLabel: "Pola na stronie Wyniki",

  agentsLabel: "Ścieżka agentów: Claude Code, Codex, Gemini CLI, Hermes",
  agentsP1a: "Przy każdym wyniku zapisujemy ",
  agentsP1Harness: "narzędzie (agenta)",
  agentsP1b: ", czyli to, co sterowało pracą modelu. Prawie wszędzie jest to ",
  agentsP1c:
    ", nasze własne CLI działające bez interfejsu. Ścieżka agentów to te same zadania wykonane przez zewnętrznego agenta programistycznego (Claude Code, Codex albo Gemini CLI) na jego własnym modelu. Ma osobną stronę, Agenty. Ranking, Wyniki, Porównanie i Pareto domyślnie pokazują ścieżkę Refio.",
  agentsP2:
    "Celowo nie ma jej w rankingu. Ranking odpowiada na pytanie: „Jaki model Refio powinno wybierać domyślnie?”. Zewnętrzny agent wnosi własne planowanie, narzędzia, ponowienia i samodzielne sprawdzanie, więc ocenia się wtedy cały system, a nie sam model. Po wymieszaniu obu ścieżek żadna nie dawałaby się czytelnie odczytać. Ścieżka agentów odpowiada na inne pytania: jak daleko modelowi lokalnemu do tego, czego ludzie już używają na co dzień, i czy silny model zachowuje się inaczej, gdy steruje nim inny agent.",
  agentsP3a: "Identyfikator modelu zaczynający się od ",
  agentsP3b:
    " oznacza, że agenta skierowaliśmy na lokalny serwer Ollama zamiast do jego dostawcy w chmurze. Dzięki temu ten sam model lokalny można zmierzyć zarówno w Refio, jak i w zewnętrznym agencie. Takie pary zestawia tabela różnic na stronie Agenty.",
  agentsP4Trace: "Zapis przebiegu.",
  agentsP4a:
    " Każdy przebieg zapisuje teraz krok po kroku, co agent naprawdę zrobił: każdą odpowiedź asystenta, każde wywołanie narzędzia (z plikiem lub poleceniem, którego dotyczyło) i każdy wynik. Liczby przy przebiegu (tury, wywołania narzędzi, odczyty, zapisy, polecenia powłoki, moment pierwszego zapisu) wyliczamy z tego dziennika zwykłą arytmetyką, nigdy nie pytając o nie modelu. ",
  agentsP4SelfCheck: "Samodzielne sprawdzenie",
  agentsP4b:
    " oznacza, że model sam uruchomił budowanie lub test w trakcie przebiegu. Pętla, która sprawdza wynik za model, się nie liczy, bo porównujemy dyscyplinę samego modelu. Dziennik nie zawiera treści plików, bo te są już w artefakcie.",
  agentsP5a: "Czytając wyniki tej ścieżki, pamiętaj o trzech rzeczach. Kryterium ",
  agentsP5b:
    " ocenia tutaj narzędzie (agenta), a nie model, i właśnie po to ta ścieżka istnieje. ",
  agentsP5Cost: "Kosztów",
  agentsP5c:
    " nie da się porównać, bo zewnętrzny agent działa w abonamencie. Każda pokazana kwota to szacunek na podstawie tokenów, a nie faktyczna opłata, i widok Pareto ostrzega o tym, gdy koszt jest na wykresie. Poza tym sędziami są właśnie Claude Code i Codex. Dlatego na stronie Agenty pomijamy w wyniku zbiorczym werdykt sędziego o przebiegu, który wykonał jego własny agent, bo ocenianie własnej pracy nie jest pomiarem. W pozostałych widokach wynik zbiorczy się nie zmienia.",
  agentsP6:
    "Warunki uruchomienia każdego narzędzia (dostęp do sieci, tryb uprawnień, wersja) są zapisane w jego rekordzie i widać je na stronie Agenty. Czas, jaki agent dostaje na pracę, i liczba tur rosną z trudnością zadania: 15 minut na łatwe zadanie, 30 na średnie, godzina na trudne i dwie godziny na zadanie obciążeniowe. Gdyby limit realnie ograniczał agenta, mierzylibyśmy limit, a nie agenta.",

  judgeLabel: "Ocenianie przez silnych sędziów",
  judgeP1a: "Artefakty mogą oceniać nie tylko ludzie, ale też ",
  judgeP1Agents: "silni sędziowie (agenci)",
  judgeP1b:
    ", czyli zewnętrzni agenci CLI (Claude Code i Codex) uruchamiani bez interfejsu i tylko do odczytu poleceniem ",
  judgeP1c:
    ". Każdy artefakt renderujemy w Playwright (dwa zrzuty ekranu i błędy zebrane z konsoli), a każdy sędzia ocenia go na ślepo: nie widzi ani ocen ręcznych, ani ocen drugiego sędziego.",
  judgeP2Criteria: "Kryteria.",
  judgeP2a:
    " Sędziowie oceniają te same kryteria co człowiek (Compliance, Works out of the box, Look, Code quality) i dodatkowo dwa, które oceniają tylko oni: ",
  judgeP2b: " (struktura, nazewnictwo, powtórzenia, martwy kod) oraz ",
  judgeP2c: " (poprawność odczytana z kodu, a nie tylko z ekranu). Ręcznego kryterium ",
  judgeP2d: " sędziowie ",
  judgeP2Not: "nie",
  judgeP2e:
    " oceniają, bo dotyczy ono sposobu pracy agenta programistycznego (przejrzenie plików, edycja, sprawdzenie, podsumowanie), a tego nie widać w gotowym artefakcie.",
  judgeP3Title: "Wynik zbiorczy i rozbieżności.",
  judgeP3a:
    " W każdym kryterium wynikiem zbiorczym jest mediana ocen sędziów. Liczy ją przeglądarka i nigdzie jej nie zapisujemy. Strona Wyniki pokazuje ją w kolumnie ",
  judgeP3AutoColumn: "Auto (sędziowie)",
  judgeP3b: " razem z ",
  judgeP3Badge: "oznaczeniem rozbieżności",
  judgeP3c:
    ", gdy ocena ręczna i zbiorcza ocena sędziów różnią się w tym samym kryterium o co najmniej 2 punkty. Strony Ranking, Porównanie i Pareto pokazują dla każdego modelu miarę ",
  judgeP3d: ", a Porównanie ma dodatkowo osobny radar sędziów.",
  judgeP4Title: "Stabilność.",
  judgeP4a:
    " Gdy jeden model rozwiązuje to samo zadanie kilka razy, dla stabilności zapisujemy miary deterministyczne: ",
  judgeP4b:
    " (średnie odchylenie bezwzględne zbiorczej oceny sędziów między próbami; im niższe, tym stabilniej) oraz ",
  judgeP4c:
    " (podobieństwo Jaccarda tokenów w artefaktach), a do tego werdykt sędziego, który patrzy na wszystkie próby. Stabilność widać na stronie zadania, a strona Stabilność porównuje według niej modele na radarach i w rankingu.",
  judgeP5Title: "Przegląd.",
  judgeP5:
    " Oceny sędziów są tylko pomocnicze: nigdy nie zastępują ocen ręcznych. Człowiek przegląda je w szczegółach wyniku, obok ocen ręcznych.",

  stabilityLabel: "Strona Stabilność i Średnia stabilność",
  stabilityP1:
    "Stabilność odpowiada na inne pytanie niż jakość: czy ten sam model uruchomiony ponownie na tym samym zadaniu da podobny wynik? Liczymy ją dla grupy, czyli wszystkich prób jednego modelu w jednym zadaniu, w jednym środowisku i z jednym narzędziem (agentem). Grupa musi mieć co najmniej dwie próby z artefaktem HTML.",
  stabilityP2:
    "Każda grupa dostaje trzy wskaźniki w skali 0-100%. Im wyższa wartość, tym większa stabilność.",
  stabilityScoreTitle: "Spójność ocen",
  stabilityScoreA: " - jak blisko siebie są oceny sędziów dla poszczególnych prób: ",
  stabilityScoreB: ", gdzie ",
  stabilityScoreC:
    " to średnie odchylenie bezwzględne między próbami na skali 0-6. Tak jak w Niezawodności, odchylenie o połowę skali oznacza, że próby nie mają ze sobą nic wspólnego.",
  stabilityCodeTitle: "Podobieństwo kodu",
  stabilityCode:
    " - ile tokenów mają wspólnych artefakty z poszczególnych prób (miara Jaccarda). Niska wartość oznacza, że model za każdym razem pisze rozwiązanie inaczej, nawet jeśli oceny są zbliżone.",
  stabilityVerdictTitle: "Werdykt sędziów",
  stabilityVerdictA: " - każdy silny sędzia ogląda wszystkie próby naraz i odpowiada ",
  stabilityVerdictB: " (to samo podejście, podobna jakość), ",
  stabilityVerdictC: " (to samo podejście, różna jakość) albo ",
  stabilityVerdictD:
    " (różne podejścia albo skrajnie różna jakość). Grupa dostaje medianę werdyktów wszystkich sędziów.",
  stabilityOverallTitle: "Ogólna stabilność",
  stabilityOverallA:
    " grupy to zwykła średnia trzech wskaźników. Jeśli żaden sędzia nie ocenił jeszcze grupy, uśredniamy tylko dwa wskaźniki deterministyczne, więc brak werdyktu nigdy nie liczy się jako 0. Wartość dla modelu (kolumna ",
  stabilityOverallB: " w rankingu) to średnia z jego zadań.",
  stabilityVsTitle: "Średnia stabilność a Niezawodność.",
  stabilityVs:
    " Niezawodność sprawdza tylko, jak bardzo oceny zmieniają się między próbami. Średnia stabilność sprawdza też, czy próby są zbudowane w ten sam sposób i co sądzą o nich sędziowie. Dlatego obie wartości mogą się mocno różnić: model może za każdym razem wypaść równie słabo (wysoka Niezawodność), a przy tym w każdej próbie napisać zupełnie inny program (niska Średnia stabilność). Bywa też odwrotnie.",
  stabilityPageTitle: "Strona Stabilność",
  stabilityPage:
    " pokazuje dwa radary dla co najwyżej sześciu wybranych modeli (stabilność w poszczególnych zadaniach i według wskaźników, z osobną osią dla każdego sędziego). Te same wartości są też w tabelach, gdzie najlepsza wartość w każdym wierszu jest wyróżniona. Pod spodem jest ranking wszystkich modeli: kliknięcie wiersza dodaje model do porównania albo go z niego usuwa. Wybór jest wspólny ze stroną Porównanie. Obowiązują globalne filtry środowiska, zadania i narzędzia, a ukryte zadania są pomijane.",

  paretoLabel: "Eksplorator Pareto",
  paretoP:
    "Wykresy Pareto zestawiają dwie miary naraz. Przy jakości, niezawodności, pierwszej próbie, zaliczonych, szybkości tokenów i przydatności lokalnej im więcej, tym lepiej. Przy koszcie, czasie i szacowanym czasie LLM im mniej, tym lepiej. Punkty leżące blisko lepszej krawędzi na obu osiach to najkorzystniejsze kompromisy.",

  radarsLabel: "Radary na stronie Porównanie",
  radarsP1:
    "Strona Porównanie rysuje trzy wykresy radarowe. Każda oś ma skalę 0-100% i zawsze im wyżej, tym lepiej.",
  radarsCriterionTitle: "Średnia ocena według kryteriów",
  radarsCriterionA:
    " opiera się na surowych znormalizowanych ocenach w każdym kryterium (Compliance, Works out of the box, Look, Code quality, Agent logic). Wartość modelu na danej osi to średnia z ",
  radarsCriterionB: " ze wszystkich prób tego modelu w tym kryterium.",
  radarsDerivedTitle: "Miary pochodne benchmarku",
  radarsDerived:
    " łączą pola rankingu dla każdego modelu. Żeby wartości nie zmieniały się, gdy dodajesz modele do wyboru lub je usuwasz, normalizacja korzysta ze stałych punktów odniesienia wyliczonych z pełnego rankingu (wszystkie modele, po filtrach globalnych):",
  radarsRatioTitle:
    "Refio Score, Średnia ocena, Zaliczone, Pierwsza próba, Niezawodność, Przydatność lokalna",
  radarsRatio: " - już mieszczą się w zakresie 0-1, więc bierzemy surowe wartości obcięte do [0, 1].",
  radarsSpeedTitle: "Szybkość wejścia, Szybkość wyjścia",
  radarsSpeedA:
    " - im więcej, tym lepiej. Wartość dzielimy przez 95. percentyl wszystkich wierszy rankingu i obcinamy do 1: ",
  radarsSpeedB: ". Najszybsze modele dostają 100%, a wolniejsze proporcjonalnie mniej.",
  radarsCostTitle: "Średnia szybkość (czas), Koszt API",
  radarsCostA:
    " - im mniej, tym lepiej. Punktem odniesienia jest 5. percentyl wszystkich wierszy rankingu: ",
  radarsCostB:
    ". Najszybszy lub najtańszy model dostaje 100%, model dwa razy wolniejszy lub droższy 50%, a dziesięć razy 10%. Wartość nie spada do zera tylko dlatego, że jest powyżej mediany.",
  radarsAxisA:
    "Gdy model ma w rankingu kilka wierszy (dla różnych środowisk), wartość na każdej osi to ich średnia. Oś znika z wykresu, gdy ",
  radarsAxisAny: "którykolwiek",
  radarsAxisB:
    " z wybranych modeli nie ma dla niej danych. Przy niepełnych danych brakujący model spadłby do 0% i zniekształcił wielokąt. W praktyce, gdy zestawiasz modele chmurowe z lokalnymi, znikają osie Koszt API (modele lokalne go nie mają) i Przydatność lokalna (nie dotyczy chmury), więc zostają tylko miary, które da się porównać wprost. Wybór modeli nie przesuwa żadnego innego modelu na radarze.",
  radarsTaskTitle: "Zachowanie modelu według zadań",
  radarsTask:
    " uśrednia znormalizowane oceny kryteriów osobno dla każdego zadania i modelu. Recharts potrzebuje co najmniej trzech osi, żeby narysować wielokąt, więc przy jednym lub dwóch zadaniach wykres zamienia się w linię. Dokładne wartości znajdziesz w tabeli z podziałem na zadania poniżej.",

  tokenLabel: "Jak liczymy szybkość tokenów",
  tokenP1:
    "Wnioskowanie LLM ma dwie fazy. Przy przetwarzaniu wejścia (prefill) model czyta tokeny wejściowe i buduje z nich swój stan. Przy generowaniu (decode) tworzy tokeny wyjściowe jeden po drugim.",
  tokenP2a: "Dane benchmarku zawierają obecnie ",
  tokenP2b: " i ",
  tokenP2c:
    ", ale bez osobnych czasów TTFT, przetwarzania wejścia i generowania. Dopóki ich nie zbieramy, interfejs szacuje ten podział na podstawie zmierzonego czasu przebiegu.",
  tokenP3a: "Gdy znamy liczbę tokenów wejściowych i wyjściowych: ",
  tokenP3b: " i ",
  tokenP3c: ". Na tej podstawie ",
  tokenP3d: ".",
  tokenP4:
    "Jeśli znamy tylko jedną z tych liczb, cały zmierzony czas przypisujemy do niej. Wartości w rankingu to średnie z takich szacunków dla wszystkich prób w grupie (model i środowisko).",
};
