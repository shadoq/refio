import type { Messages } from "../../core";

export const taskDetail = {
  back: "← Back",
  notFound: 'Task "{id}" not found',
  backToLeaderboard: "← Leaderboard",
  systemPrompt: "System Prompt",
  criteria: "Criteria",
  scale: "scale: [{values}]",
  noResults: "No results yet for this task.",
  attempts: "Attempts",
  scoreByCriterion: "Score by Criterion",
  vsExternal: "Refio vs external agents",
  vsExternalIntro:
    "The same model on the same task, driven by Refio and by an external coding agent. The difference is the agent's scaffolding, not the model.",
  notRun: "not run",
  stabilityTitle: "Stability across attempts",
  stabilityIntro:
    "Consistency of a model's solutions across repeated attempts. Lower score variance and higher code similarity mean more stable output.",
  colModel: "Model",
  colEnvironment: "Environment",
  colAttempts: "Attempts",
  colVariance: "Score variance",
  colSimilarity: "Code similarity",
  colJudges: "Judges",
  // Attempts table
  colAvgScore: "Avg Score",
  colDuration: "Duration",
  colTokens: "Tokens",
  colCost: "Cost",
  colLlmEst: "LLM Est.",
  colSpeed: "Speed",
  colFiles: "Files",
  avgRow: "Avg",
  speedIn: "{value} in",
  speedOut: "{value} out",
} satisfies Messages;
