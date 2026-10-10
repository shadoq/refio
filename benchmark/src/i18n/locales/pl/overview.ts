import type { Translation } from "../../core";
import type { overview as en } from "../en/overview";

export const overview: Translation<typeof en> = {
  title: "Przegląd",
  intro:
    "Te same przebiegi pokazane na trzy sposoby: według modelu, w układzie model × zadanie i według zadania. Przebieg jest zaliczony, gdy spełnia wszystkie mierzone kryteria.",
  unreviewedWarning:
    "Przebiegi niesprawdzone mają tylko automatyczny werdykt. Nikt jeszcze nie potwierdził ich ręcznie.",
  runs: "Przebiegi",
  sourceAll: "Wszystkie",
  sourceReviewed: "Sprawdzone",
  sourceQueue: "Niesprawdzone",
  noRuns: "Żaden przebieg nie pasuje do wybranych filtrów.",
  tabByModel: "Według modelu",
  tabMatrix: "Model × zadanie",
  tabByTask: "Według zadania",
  colModel: "Model",
  colReasoning: "Rozumowanie",
  colPassed: "Zaliczone",
  colWorks: "Działa od razu",
  colAgentLogic: "Logika agenta = 1",
  colCost: "Koszt",
  colAvgTime: "Średni czas",
  colTask: "Zadanie",
  colModels: "Modele",
  colPerfect: "Zaliczyły wszystkie próby",
  colWeakest: "Najsłabsze",
  unreviewedTag: "niesprawdzone",
};
