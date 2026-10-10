import type { Messages } from "../../core";

export const compare = {
  title: "Compare Models",
  harness: "Harness: {harness}",
  harnessAll: "all",
  selectPrompt: "Select up to {max} models to compare:",
  selectPlaceholder: "Select models...",
  empty: "Select at least one model above to start comparing.",
  radarAvgScore: "Radar: Average Score per Criterion",
  radarJudgeScore: "Radar: Judge Score per Criterion",
  judgeAggregate: "strong-judge aggregate (median)",
  radarMetrics: "Radar: Derived Benchmark Metrics",
  radarTasks: "Radar: Model Behavior by Task",
  scoreByCriterion: "Score by Criterion",
  judgeScoreByCriterion: "Judge Score by Criterion",
  perTask: "Per-task Breakdown",
  colCriterion: "Criterion",
  colTask: "Task",
} satisfies Messages;
