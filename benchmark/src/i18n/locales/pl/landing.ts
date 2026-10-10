import { pluralPl, type Translation } from "../../core";
import type { landing as en } from "../en/landing";

export const landing: Translation<typeof en> = {
  eyebrow: "Benchmark Refio",
  emptySubtitle: "Lokalne modele LLM porównane ze sobą na prawdziwych zadaniach programistycznych.",
  emptyResults: "Nie ma jeszcze wyników. Dodasz je w Admin > Wyniki.",
  motto: "Pasja tworzy. Wiedza pomaga.",
  heroTitleStart: "Zmierzone ",
  heroTitleAccent: "małe zadania.",
  heroSubtitle:
    "Proste, powtarzalne zadania, na których porównujemy modele lokalne i chmurowe. W jednym miejscu widać, czy pierwsza próba nadaje się do użycia, czy wyniki się powtarzają, jak szybko model działa i czy warto uruchamiać go lokalnie.",
  heroNote:
    "Każdy wynik oceniamy subiektywnie, a statystyki dokłada wtyczka Refio. Benchmark pozwala porównać modele, zwłaszcza lokalne, na lekkich zadaniach, przy których nawet mniejszy model ma realną szansę zrobić coś użytecznego.",
  compareModels: "Porównaj modele",
  explorePareto: "Zobacz front Pareto",
  topSignals: "Czołówka rankingu",
  liveLeaderboard: "aktualny ranking",
  signalAttempts: ({ env, count }) =>
    `${env} / ${count} ${pluralPl(Number(count), "próba", "próby", "prób")}`,
  bestRefio: "Najlepszy Refio Score",
  bestRefioNote: "{model}: oceny ręczne i sędziów z poprawką na stabilność.",
  bestRefioEmpty: "Żeby policzyć stabilność, potrzeba kilku prób tego samego zadania.",
  bestScore: "Najlepsza ocena",
  bestScoreNote: "Jakość lidera w ocenie ręcznej.",
  reliability: "Niezawodność",
  reliabilityNote: "Czy kolejne próby dają podobny wynik.",
  firstShot: "Udana pierwsza próba",
  firstShotNote: "Jak często już pierwsza próba nadaje się do użycia.",
  bestJudge: "Najlepsza ocena sędziów",
  bestJudgeNote: "{model} w ocenie silnych sędziów.",
  bestJudgeEmpty: "Uruchom npm run judge, żeby dodać oceny sędziów.",
  bestLocal: "Najlepsza przydatność lokalna",
  bestLocalNote: "{model} na tle chmury, z uwzględnieniem stabilności.",
  bestLocalEmpty: "Dodaj przebiegi lokalne i w chmurze, żeby zobaczyć, ile modele lokalne tracą do chmury.",
  leaderboardTitle: "Ranking",
  leaderboardIntro:
    "Modele w poszczególnych środowiskach, od najlepszego. Obok wyniku: ile prób zaliczyły, ile kosztowały i jak długo trwały.",
  leaderboardCounts: ({ models, tasks, attempts }) =>
    `${models} ${pluralPl(Number(models), "model", "modele", "modeli")}, ` +
    `${tasks} ${pluralPl(Number(tasks), "zadanie", "zadania", "zadań")}, ` +
    `${Number(attempts).toLocaleString("pl-PL")} ${pluralPl(Number(attempts), "próba", "próby", "prób")}.`,
  externalTitle: "Zewnętrzne agenty programistyczne",
  externalBody:
    "Te same zadania rozwiązywane przez Claude Code, Codex i Gemini CLI, z zapisem tego, co każdy przebieg robił krok po kroku. Celowo nie trafiają do tego rankingu.",
  externalLink: "Otwórz stronę agentów",
  paretoTitle: "Pareto dla modeli lokalnych: przydatność a średni czas",
  fullView: "Pokaż całość",
  axisDuration: "Średni czas",
  axisViability: "Przydatność lokalna",
  tasksTitle: "Zadania",
};
