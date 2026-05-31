import { useMemo } from "react";
import {
  Legend,
  PolarAngleAxis,
  PolarGrid,
  PolarRadiusAxis,
  Radar,
  RadarChart,
  ResponsiveContainer,
  Tooltip,
} from "recharts";
import type { Result, ResultsFile } from "@/schema/results";
import type { TasksFile } from "@/schema/tasks";
import { leaderboard, type LeaderboardRow } from "@/lib/stats";

interface MetricRadarCompareProps {
  results: Result[];
  selectedModelIds: string[];
  tasksFile: TasksFile;
  resultsFile: Pick<ResultsFile, "models" | "environments">;
  modelNames: Record<string, string>;
  height?: number;
}

const COLORS = [
  "var(--accent-2)",
  "var(--accent)",
  "var(--accent-3)",
  "#9b8cff",
  "#ffd166",
  "#4dd4ac",
];

const CEILING_PERCENTILE = 0.95;
const FLOOR_PERCENTILE = 0.05;

interface Metric {
  label: string;
  getNormalized: (row: LeaderboardRow) => number | null;
}

export function MetricRadarCompare({
  results,
  selectedModelIds,
  tasksFile,
  resultsFile,
  modelNames,
  height = 420,
}: MetricRadarCompareProps) {
  const chartData = useMemo(() => {
    const rows = leaderboard(results, resultsFile, tasksFile);

    const collect = (selector: (row: LeaderboardRow) => number | null): number[] =>
      rows.map(selector).filter((v): v is number => v != null);

    const prefillCeil = ceiling(collect((r) => r.avgPrefillTokensPerSecond));
    const decodeCeil = ceiling(collect((r) => r.avgDecodeTokensPerSecond));
    const durationFloor = floor(collect((r) => r.avgDurationMs));
    const costFloor = floor(collect((r) => r.avgCostUsd));

    const metrics: Metric[] = [
      { label: "Avg Score", getNormalized: (r) => clampNullable(r.avgScore) },
      { label: "Pass Rate", getNormalized: (r) => clampNullable(r.passRate) },
      { label: "First-shot", getNormalized: (r) => clampNullable(r.firstShotScore) },
      { label: "Reliability", getNormalized: (r) => clampNullable(r.reliabilityScore) },
      {
        label: "Local Viability",
        getNormalized: (r) => clampNullable(r.localViabilityScore),
      },
      {
        label: "Input Speed",
        getNormalized: (r) => normalizeHigher(r.avgPrefillTokensPerSecond, prefillCeil),
      },
      {
        label: "Output Speed",
        getNormalized: (r) => normalizeHigher(r.avgDecodeTokensPerSecond, decodeCeil),
      },
      {
        label: "Avg Speed",
        getNormalized: (r) => normalizeLower(r.avgDurationMs, durationFloor),
      },
      {
        label: "API Cost",
        getNormalized: (r) => normalizeLower(r.avgCostUsd, costFloor),
      },
    ];

    return metrics.flatMap((metric) => {
      const perModel = selectedModelIds.map((modelId) => {
        const modelRows = rows.filter((r) => r.modelId === modelId);
        return { modelId, value: avgMetricForModel(modelRows, metric) };
      });
      // Drop axis if any selected model has no data — partial coverage is misleading.
      if (perModel.some((entry) => entry.value == null)) return [];
      const point: Record<string, string | number> = { metric: metric.label };
      for (const { modelId, value } of perModel) {
        point[modelId] = value ?? 0;
      }
      return [point];
    });
  }, [results, resultsFile, selectedModelIds, tasksFile]);

  if (selectedModelIds.length === 0 || chartData.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RadarChart data={chartData}>
        <PolarGrid />
        <PolarAngleAxis dataKey="metric" />
        <PolarRadiusAxis
          domain={[0, 1]}
          tickFormatter={(value: number) => `${(value * 100).toFixed(0)}%`}
        />
        <Tooltip
          formatter={(value: unknown, name: unknown) => [
            `${(Number(value) * 100).toFixed(1)}%`,
            modelNames[String(name)] ?? String(name),
          ]}
        />
        <Legend formatter={(value: string) => modelNames[value] ?? value} />
        {selectedModelIds.map((modelId, index) => (
          <Radar
            key={modelId}
            name={modelId}
            dataKey={modelId}
            stroke={COLORS[index % COLORS.length]}
            fill={COLORS[index % COLORS.length]}
            fillOpacity={0.13}
          />
        ))}
      </RadarChart>
    </ResponsiveContainer>
  );
}

function avgMetricForModel(
  modelRows: LeaderboardRow[],
  metric: Metric,
): number | null {
  const values = modelRows
    .map((row) => metric.getNormalized(row))
    .filter((v): v is number => v != null);
  if (values.length === 0) return null;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function clampNullable(value: number | null | undefined): number | null {
  if (value == null) return null;
  return clamp(value, 0, 1);
}

function normalizeHigher(value: number | null, ceilingValue: number): number | null {
  if (value == null) return null;
  if (ceilingValue <= 0) return 0;
  return clamp(value / ceilingValue, 0, 1);
}

function normalizeLower(value: number | null, floorValue: number): number | null {
  if (value == null) return null;
  if (value <= 0) return 1;
  if (floorValue <= 0) return 0;
  return clamp(floorValue / value, 0, 1);
}

function percentile(values: number[], p: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = clamp(Math.floor(p * (sorted.length - 1)), 0, sorted.length - 1);
  return sorted[idx];
}

function ceiling(values: number[]): number {
  if (values.length === 0) return 1;
  const p = percentile(values, CEILING_PERCENTILE);
  if (p > 0) return p;
  const max = Math.max(...values);
  return max > 0 ? max : 1;
}

function floor(values: number[]): number {
  if (values.length === 0) return 0;
  const positives = values.filter((v) => v > 0);
  if (positives.length === 0) return 0;
  return percentile(positives, FLOOR_PERCENTILE);
}
