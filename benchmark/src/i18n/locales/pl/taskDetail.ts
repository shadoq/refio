import type { Translation } from "../../core";
import type { taskDetail as en } from "../en/taskDetail";

export const taskDetail: Translation<typeof en> = {
  back: "← Wstecz",
  notFound: "Nie znaleziono zadania „{id}”",
  backToLeaderboard: "← Ranking",
  systemPrompt: "Prompt systemowy",
  criteria: "Kryteria",
  scale: "skala: [{values}]",
  noResults: "To zadanie nie ma jeszcze wyników.",
  attempts: "Próby",
  scoreByCriterion: "Oceny według kryteriów",
  vsExternal: "Refio na tle zewnętrznych agentów",
  vsExternalIntro:
    "Ten sam model i to samo zadanie, raz w Refio, raz w zewnętrznym agencie programistycznym. Różnicę robi otoczenie, w którym model pracuje, a nie sam model.",
  notRun: "nie uruchomiono",
  stabilityTitle: "Stabilność w kolejnych próbach",
  stabilityIntro:
    "Czy model w kolejnych próbach rozwiązuje zadanie podobnie. Im mniejsza wariancja ocen i im bardziej podobny kod, tym stabilniejszy model.",
  colModel: "Model",
  colEnvironment: "Środowisko",
  colAttempts: "Próby",
  colVariance: "Wariancja ocen",
  colSimilarity: "Podobieństwo kodu",
  colJudges: "Sędziowie",
  colAvgScore: "Średnia ocena",
  colDuration: "Czas",
  colTokens: "Tokeny",
  colCost: "Koszt",
  colLlmEst: "Szac. czas LLM",
  colSpeed: "Tokeny/s",
  colFiles: "Pliki",
  avgRow: "Średnia",
  speedIn: "wej. {value}",
  speedOut: "wyj. {value}",
};
