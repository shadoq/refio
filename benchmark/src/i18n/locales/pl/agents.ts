import { pluralPl, type Translation } from "../../core";
import type { agents as en } from "../en/agents";

export const agents: Translation<typeof en> = {
  title: "Agenty",
  intro:
    "Te same zadania obok Refio rozwiązują zewnętrzne agenty programistyczne: Claude Code, Codex i Gemini CLI. Każdy z nich sam planuje pracę, używa własnych narzędzi i sam sprawdza wynik. Oceniamy je według tych samych kryteriów, ale nie wliczamy do rankingu. Ta strona pokazuje, ile pętli agenta Refio brakuje do narzędzi, z których programiści już korzystają, i czy mocny model zachowuje się inaczej, gdy prowadzi go inny agent. Modele o nazwie zaczynającej się od ollama/ uruchamialiśmy lokalnie w obu narzędziach - właśnie takie pary zestawia tabela różnic poniżej.",
  emptyExternal:
    "Nie ma jeszcze przebiegów zewnętrznych agentów. Zaimportuj je poleceniem import-runs --harness claude-code.",
  version: "wersja {version}",
  leaderboardTitle: "Ranking agentów",
  colAgent: "Agent",
  runConditions: "warunki uruchomienia",
  colModel: "Model",
  colTasks: "Zadania",
  colAttempts: "Próby",
  colAvgScore: "Średnia ocena",
  colJudges: "Sędziowie",
  colJudgesTip: "nie liczymy tu oceny, którą sędzia wystawił przebiegowi własnego agenta",
  colAvgTurns: "Śr. tur",
  colAvgTools: "Śr. narzędzi",
  colAvgWrites: "Śr. zapisów",
  colSelfCheck: "Sprawdzanie",
  colSelfCheckTip: "w ilu przebiegach model sam uruchomił budowanie albo testy",
  colWasted: "Powtórzenia",
  colWastedTip: "ile wywołań narzędzi powtarzało coś, co agent już wcześniej zrobił",
  colRecovered: "Po błędzie",
  colRecoveredTip:
    "w ilu przebiegach agent pracował dalej, gdy któreś narzędzie zwróciło błąd",
  colUnfinished: "Przerwane",
  colUnfinishedTip: "przebiegi, które nie dobiegły końca: wyczerpany limit, przekroczony czas albo awaria",
  colAvgDuration: "Średni czas",
  colEnvironment: "Środowisko",
  notRun: "nie uruchomiono",
  colDelta: "Różnica",
  colDeltaTip:
    "średnia z różnic w poszczególnych zadaniach, liczona tylko dla zadań, które wykonały OBA agenty; liczba w nawiasie to liczba takich zadań",
  sharedTasks: ({ count }) =>
    `(${count} ${pluralPl(Number(count), "wspólne zadanie", "wspólne zadania", "wspólnych zadań")})`,
  noSharedTask: "{harness}: brak wspólnych zadań",
  sameModelTitle: "Ten sam model w dwóch narzędziach",
  sameModelEmpty:
    "Żeby zobaczyć tę tabelę, uruchom ten sam model ollama/... w Refio i w zewnętrznym agencie",
  matrixTitle: "Zadania i narzędzia",
  colTask: "Zadanie",
  compareRunsTitle: "Porównaj dwa przebiegi",
  compareRunsEmpty: "Potrzeba co najmniej dwóch przebiegów z zapisanym śladem pracy",
  runA: "Przebieg A",
  runB: "Przebieg B (to samo zadanie)",
  colMetric: "Miara",
  metricTurns: "tury",
  metricToolCalls: "wywołania narzędzi",
  metricReads: "odczyty",
  metricWrites: "zapisy",
  metricShellRuns: "polecenia w powłoce",
  metricSelfCheck: "sprawdzanie własnej pracy",
  metricTimeToFirstWrite: "czas do pierwszego zapisu (s)",
};
