import type { Translation } from "../../core";
import type { stability as en } from "../en/stability";

export const stability: Translation<typeof en> = {
  title: "Stabilność",
  intro:
    "Narzędzie: {harness}. Czy kolejne próby tego samego zadania dają podobny wynik. Ogólna stabilność to zwykła średnia z trzech składników: spójności ocen (1 - 2 x średnie odchylenie oceny między próbami), podobieństwa kodu w artefaktach i mediany werdyktów sędziów (0 - próby się rozjeżdżają, 0,5 - to samo podejście, ale nierówna jakość, 1 - wyniki stabilne).",
  harnessAll: "wszystkie",
  selectPrompt: "Wybierz modele do porównania (najwyżej {max}):",
  selectPlaceholder: "Wybierz modele...",
  empty: "Wybierz powyżej przynajmniej jeden model albo kliknij go w rankingu poniżej.",
  radarByTask: "Radar: stabilność w zadaniach",
  radarDimensions: "Radar: składniki stabilności",
  byDimension: "Stabilność według składników",
  perTask: "Stabilność w poszczególnych zadaniach",
  ranking: "Ranking stabilności",
  rankingHint: "kliknij wiersz, żeby dodać model do porównania albo go usunąć",
  overallStability: "Ogólna stabilność",
  scoreConsistency: "Spójność ocen",
  codeSimilarity: "Podobieństwo kodu",
  judge: "Sędzia: {judge}",
  colModel: "Model",
  colOverall: "Ogółem",
  colGroups: "Grupy",
};
