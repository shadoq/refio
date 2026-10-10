import type { Messages } from "../../core";

export const landing = {
  eyebrow: "Refio evaluation",
  emptySubtitle: "Local LLMs evaluated head-to-head on real coding tasks.",
  emptyResults: "No benchmark results yet. Add results via Admin > Results.",
  motto: "Passion creates. Knowledge helps.",
  heroTitleStart: "Small tasks, ",
  heroTitleAccent: "measured.",
  heroSubtitle:
    "Simple repeatable tasks for comparing local and cloud models: first-shot usability, reliability, speed and local viability in one benchmark cockpit.",
  heroNote:
    "This is a subjective benchmark of each test result, enriched with statistical data collected by the Refio plugin. It is designed to compare models, especially local ones, on lightweight tasks where smaller models still have a realistic chance to produce a usable result.",
  compareModels: "Compare models",
  explorePareto: "Explore Pareto front",
  topSignals: "Top benchmark signals",
  liveLeaderboard: "live leaderboard",
  signalAttempts: ({ env, count }) => `${env} / ${count} ${Number(count) === 1 ? "attempt" : "attempts"}`,
  bestRefio: "Best Refio Score",
  bestRefioNote: "{model}: human and judge quality, scaled by stability.",
  bestRefioEmpty: "Needs repeated attempts with stability computed.",
  bestScore: "Best score",
  bestScoreNote: "Human-reviewed quality of the leading model.",
  reliability: "Reliability",
  reliabilityNote: "Consistency across repeated attempts.",
  firstShot: "First-shot success",
  firstShotNote: "How often attempt #1 is already usable.",
  bestJudge: "Best judge score",
  bestJudgeNote: "{model}, scored by strong-judge agents.",
  bestJudgeEmpty: "Run npm run judge to add strong-judge scores.",
  bestLocal: "Best local viability",
  bestLocalNote: "{model} vs cloud baseline, blended with stability.",
  bestLocalEmpty: "Add local and cloud runs to calculate the local viability gap.",
  leaderboardTitle: "Leaderboard",
  leaderboardIntro:
    "Ranked model and environment combinations with score, pass-rate, cost and runtime context.",
  leaderboardCounts: ({ models, tasks, attempts }) =>
    `${models} models, ${tasks} tasks, ${Number(attempts).toLocaleString("en-US")} attempts.`,
  externalTitle: "External coding agents",
  externalBody:
    "The same tasks run by Claude Code, Codex and Gemini CLI, with what each run actually did step by step. Kept off this leaderboard on purpose.",
  externalLink: "Open the agents page",
  paretoTitle: "Local Pareto: Viability vs Avg Runtime",
  fullView: "Full view",
  axisDuration: "Avg Duration",
  axisViability: "Local Viability",
  tasksTitle: "Tasks",
} satisfies Messages;
