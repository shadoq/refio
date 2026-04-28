import { useMemo } from "react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import type { Result } from "@/schema/results";
import type { TasksFile, Criterion } from "@/schema/tasks";
import { normalizeScore } from "@/lib/stats";

interface BarByCriterionProps {
  results: Result[];
  criteria: Criterion[];
  tasksFile: TasksFile;
  modelNames: Record<string, string>; // modelId → display name
  height?: number;
}

const COLORS = [
  "var(--accent-2)", "var(--accent)", "var(--accent-3)", "#9b8cff", "#13c2c2",
  "#eb2f96", "#ffd166", "#a0d911",
];

export function BarByCriterion({
  results,
  criteria,
  tasksFile,
  modelNames,
  height = 350,
}: BarByCriterionProps) {
  const modelIds = useMemo(
    () => [...new Set(results.map((r) => r.modelId))],
    [results],
  );

  const chartData = useMemo(() => {
    const criteriaById = new Map(
      [...tasksFile.coreCriteria, ...criteria].map((c) => [c.id, c]),
    );

    return criteria.map((criterion) => {
      const row: Record<string, string | number> = { criterion: criterion.name };
      for (const modelId of modelIds) {
        const modelResults = results.filter((r) => r.modelId === modelId);
        const scores = modelResults
          .map((r) => {
            const score = r.scores.find((s) => s.criterionId === criterion.id);
            if (!score) return null;
            const crit = criteriaById.get(criterion.id);
            if (!crit) return null;
            return normalizeScore(score.value, crit.scale.values);
          })
          .filter((s): s is number => s !== null);

        row[modelId] =
          scores.length > 0
            ? scores.reduce((a, b) => a + b, 0) / scores.length
            : 0;
      }
      return row;
    });
  }, [results, criteria, modelIds, tasksFile]);

  if (chartData.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart
        data={chartData}
        margin={{ top: 10, right: 30, left: 0, bottom: 5 }}
      >
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="criterion" />
        <YAxis domain={[0, 1]} tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`} />
        <Tooltip
          formatter={(value, name) => {
            const nameStr = String(name ?? "");
            const label = typeof value === "number" ? `${(value * 100).toFixed(1)}%` : String(value);
            return [label, modelNames[nameStr] ?? nameStr] as [string, string];
          }}
        />
        <Legend formatter={(value: string) => modelNames[value] ?? value} />
        {modelIds.map((modelId, idx) => (
          <Bar
            key={modelId}
            dataKey={modelId}
            name={modelId}
            fill={COLORS[idx % COLORS.length]}
          />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}
