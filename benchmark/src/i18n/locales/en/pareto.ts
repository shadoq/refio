import type { Messages } from "../../core";

export const pareto = {
  title: "Pareto Explorer",
  intro:
    "Compare trade-offs across local viability, speed, quality, first-shot success, reliability and cloud/API cost.",
  externalCostWarning:
    "The reference track (external coding agents) is on this chart and bills by subscription: its cost is an API-price estimate, not a charged amount.",
  xAxis: "X axis",
  yAxis: "Y axis",
  localOnly: "Local only:",
  empty: "Not enough data for this metric pair. Add more results or choose different axes.",
  chartTitle: "{y} vs {x}",
  minutes: "{value}m",
  statAvgCost: "Avg cost: {value}",
  statAvgDuration: "Avg: {value}",
  statLlmEst: "LLM est: {value}",
  statReliability: "Reliability: {value}",
  statJudgeScore: "Judge score: {value}",
  metricQuality: "Avg Quality",
  metricRefioScore: "Refio Score",
  metricJudgeScore: "Judge Score",
  metricCost: "Avg API Cost",
  metricDuration: "Avg Duration",
  metricEstimatedLlm: "Est. LLM Time",
  metricPrefillSpeed: "Prefill Speed",
  metricDecodeSpeed: "Decode Speed",
  metricReliability: "Reliability",
  metricFirstShot: "First-shot",
  metricLocalViability: "Local Viability",
  metricPassRate: "Pass Rate",
  metricAttempts: "Attempts",
} satisfies Messages;
