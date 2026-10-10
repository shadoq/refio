import type { Translation } from "../../core";
import type { charts as en } from "../en/charts";

export const charts: Translation<typeof en> = {
  paretoFront: "Front Pareto",
  paretoOther: "Pozostałe",
  paretoDefaultX: "Koszt / czas",
  paretoDefaultY: "Średnia ocena",
  tooltipAttempts: "Próby: {count}",
  tooltipProvider: "Dostawca: {provider}",
  metricRefioScore: "Refio Score",
  metricAvgScore: "Średnia ocena",
  metricPassRate: "Zaliczone próby",
  metricFirstShot: "Pierwsza próba",
  metricReliability: "Niezawodność",
  metricLocalViability: "Przydatność lokalna",
  metricInputSpeed: "Szybkość wczytywania",
  metricOutputSpeed: "Szybkość generowania",
  metricAvgSpeed: "Średnia szybkość",
  metricApiCost: "Koszt API",
};
