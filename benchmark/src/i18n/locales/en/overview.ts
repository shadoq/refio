import type { Messages } from "../../core";

export const overview = {
  title: "Overview",
  intro:
    "The same runs seen from several sides: per model, per model and task, and per task. A run passes when every measured criterion holds.",
  unreviewedWarning:
    "Unreviewed runs are judged only by the automatic verdict and have not been confirmed by hand.",
  runs: "Runs",
  sourceAll: "Reviewed + unreviewed",
  sourceReviewed: "Reviewed",
  sourceQueue: "Unreviewed",
  noRuns: "No runs match the current filters.",
  tabByModel: "By model",
  tabMatrix: "Model × task",
  tabByTask: "By task",
  colModel: "Model",
  colReasoning: "Reasoning",
  colPassed: "Passed",
  colWorks: "Works out of the box",
  colAgentLogic: "Agent logic = 1",
  colCost: "Cost",
  colAvgTime: "Avg time",
  colTask: "Task",
  colModels: "Models",
  colPerfect: "Passed every attempt",
  colWeakest: "Weakest",
  unreviewedTag: "unreviewed",
} satisfies Messages;
