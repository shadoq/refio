import type { Translation } from "../../core";
import type { compare as en } from "../en/compare";

export const compare: Translation<typeof en> = {
  title: "Porównaj modele",
  harness: "Narzędzie: {harness}",
  harnessAll: "wszystkie",
  selectPrompt: "Wybierz modele do porównania (najwyżej {max}):",
  selectPlaceholder: "Wybierz modele...",
  empty: "Wybierz powyżej przynajmniej jeden model, żeby je porównać.",
  radarAvgScore: "Radar: średnia ocena w każdym kryterium",
  radarJudgeScore: "Radar: ocena sędziów w każdym kryterium",
  judgeAggregate: "mediana ocen silnych sędziów",
  radarMetrics: "Radar: miary wyliczone z benchmarku",
  radarTasks: "Radar: jak model radzi sobie w zadaniach",
  scoreByCriterion: "Ocena w każdym kryterium",
  judgeScoreByCriterion: "Ocena sędziów w każdym kryterium",
  perTask: "Wyniki w poszczególnych zadaniach",
  colCriterion: "Kryterium",
  colTask: "Zadanie",
};
