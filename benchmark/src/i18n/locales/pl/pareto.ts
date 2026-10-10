import type { Translation } from "../../core";
import type { pareto as en } from "../en/pareto";

export const pareto: Translation<typeof en> = {
  title: "Wykres Pareto",
  intro:
    "Zobacz, co zyskujesz, a co tracisz: przydatność lokalna, szybkość, jakość, trafność pierwszej próby, niezawodność i koszt chmury lub API.",
  externalCostWarning:
    "Wykres obejmuje też zewnętrzne agenty programistyczne, które rozlicza się w abonamencie. Ich koszt to tylko szacunek według cennika API, a nie kwota, którą faktycznie zapłacono.",
  xAxis: "Oś X",
  yAxis: "Oś Y",
  localOnly: "Tylko lokalne:",
  empty: "Za mało danych dla tej pary miar. Dodaj więcej wyników albo wybierz inne osie.",
  chartTitle: "{y} a {x}",
  minutes: "{value} min",
  statAvgCost: "Średni koszt: {value}",
  statAvgDuration: "Średni czas: {value}",
  statLlmEst: "Szac. czas LLM: {value}",
  statReliability: "Niezawodność: {value}",
  statJudgeScore: "Ocena sędziów: {value}",
  metricQuality: "Średnia jakość",
  metricRefioScore: "Refio Score",
  metricJudgeScore: "Ocena sędziów",
  metricCost: "Średni koszt API",
  metricDuration: "Średni czas",
  metricEstimatedLlm: "Szac. czas LLM",
  metricPrefillSpeed: "Szybkość wczytywania (prefill)",
  metricDecodeSpeed: "Szybkość generowania (decode)",
  metricReliability: "Niezawodność",
  metricFirstShot: "Pierwsza próba",
  metricLocalViability: "Przydatność lokalna",
  metricPassRate: "Zaliczone",
  metricAttempts: "Próby",
};
