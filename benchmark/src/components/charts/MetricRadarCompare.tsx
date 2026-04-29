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
import { leaderboard } from "@/lib/stats";

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
    const modelRows = selectedModelIds.map((modelId) => ({
      modelId,
      rows: rows.filter((row) => row.modelId === modelId),
    }));

    const metrics = [
      {
        label: "Avg Score",
        getValue: (items: typeof rows) => avg(items.map((row) => row.avgScore)),
        higherIsBetter: true,
      },
      {
        label: "Pass Rate",
        getValue: (items: typeof rows) => avg(items.map((row) => row.passRate)),
        higherIsBetter: true,
      },
      {
        label: "First-shot",
        getValue: (items: typeof rows) => avg(items.map((row) => row.firstShotScore)),
        higherIsBetter: true,
      },
      {
        label: "Reliability",
        getValue: (items: typeof rows) => avg(items.map((row) => row.reliabilityScore)),
        higherIsBetter: true,
      },
      {
        label: "Local Viability",
        getValue: (items: typeof rows) => avg(items.map((row) => row.localViabilityScore)),
        higherIsBetter: true,
      },
      {
        label: "Input Speed",
        getValue: (items: typeof rows) =>
          avg(items.map((row) => row.avgPrefillTokensPerSecond)),
        higherIsBetter: true,
      },
      {
        label: "Output Speed",
        getValue: (items: typeof rows) =>
          avg(items.map((row) => row.avgDecodeTokensPerSecond)),
        higherIsBetter: true,
      },
      {
        label: "Runtime",
        getValue: (items: typeof rows) => avg(items.map((row) => row.avgDurationMs)),
        higherIsBetter: false,
      },
      {
        label: "API Cost",
        getValue: (items: typeof rows) => avg(items.map((row) => row.avgCostUsd)),
        higherIsBetter: false,
      },
    ];

    return metrics.flatMap((metric) => {
      const rawValues = modelRows.map(({ rows }) => metric.getValue(rows));
      if (rawValues.every((value) => value == null)) return [];
      const normalized = normalizeAcrossModels(rawValues, metric.higherIsBetter);
      const point: Record<string, string | number> = { metric: metric.label };
      selectedModelIds.forEach((modelId, index) => {
        point[modelId] = normalized[index] ?? 0;
      });
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

function avg(values: Array<number | null | undefined>): number | null {
  const nums = values.filter((value): value is number => value != null);
  if (nums.length === 0) return null;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

function normalizeAcrossModels(
  values: Array<number | null>,
  higherIsBetter: boolean,
): Array<number | null> {
  const nums = values.filter((value): value is number => value != null);
  if (nums.length === 0) return values.map(() => null);
  const min = Math.min(...nums);
  const max = Math.max(...nums);

  return values.map((value) => {
    if (value == null) return null;
    if (max === min) return 1;
    const normalized = (value - min) / (max - min);
    return higherIsBetter ? normalized : 1 - normalized;
  });
}
