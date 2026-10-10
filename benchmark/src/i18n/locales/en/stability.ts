import type { Messages } from "../../core";

export const stability = {
  title: "Stability",
  intro:
    "Harness: {harness}. How consistent a model is across repeated attempts at the same task. Overall stability is the equal-weight mean of score consistency (1 - 2 x mean score deviation between attempts), code similarity between the artifacts and the median judge verdict (0 divergent, 0.5 same approach with variable quality, 1 stable).",
  harnessAll: "all",
  selectPrompt: "Select up to {max} models to compare:",
  selectPlaceholder: "Select models...",
  empty: "Select at least one model above, or pick one from the ranking below.",
  radarByTask: "Radar: Stability by Task",
  radarDimensions: "Radar: Stability Dimensions",
  byDimension: "Stability by Dimension",
  perTask: "Per-task Stability",
  ranking: "Stability Ranking",
  rankingHint: "click a row to add or remove it from the comparison",
  overallStability: "Overall stability",
  scoreConsistency: "Score consistency",
  codeSimilarity: "Code similarity",
  judge: "Judge: {judge}",
  colModel: "Model",
  colOverall: "Overall",
  colGroups: "Groups",
} satisfies Messages;
