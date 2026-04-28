import { useMemo } from "react";
import {
  RadarChart,
  Radar,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import type { Result } from "@/schema/results";
import type { TasksFile } from "@/schema/tasks";
import { normalizeScore } from "@/lib/stats";

interface RadarCompareProps {
  results: Result[];
  selectedModelIds: string[];
  tasksFile: TasksFile;
  modelNames: Record<string, string>;
  height?: number;
}

const COLORS = [
  "var(--accent-2)", "var(--accent)", "var(--accent-3)", "#9b8cff",
];

export function RadarCompare({
  results,
  selectedModelIds,
  tasksFile,
  modelNames,
  height = 400,
}: RadarCompareProps) {
  const criteria = tasksFile.coreCriteria;

  const chartData = useMemo(() => {
    return criteria.map((criterion) => {
      const point: Record<string, string | number> = { criterion: criterion.name };
      for (const modelId of selectedModelIds) {
        const modelResults = results.filter((r) => r.modelId === modelId);
        const scores = modelResults
          .map((r) => {
            const score = r.scores.find((s) => s.criterionId === criterion.id);
            if (!score) return null;
            return normalizeScore(score.value, criterion.scale.values);
          })
          .filter((s): s is number => s !== null);
        point[modelId] =
          scores.length > 0
            ? scores.reduce((a, b) => a + b, 0) / scores.length
            : 0;
      }
      return point;
    });
  }, [criteria, selectedModelIds, results]);

  if (selectedModelIds.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RadarChart data={chartData}>
        <PolarGrid />
        <PolarAngleAxis dataKey="criterion" />
        <PolarRadiusAxis domain={[0, 1]} tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`} />
        <Tooltip
          formatter={(value: unknown, name: unknown) => [
            `${(Number(value) * 100).toFixed(1)}%`,
            modelNames[String(name)] ?? String(name),
          ]}
        />
        <Legend formatter={(value: string) => modelNames[value] ?? value} />
        {selectedModelIds.map((modelId, idx) => (
          <Radar
            key={modelId}
            name={modelId}
            dataKey={modelId}
            stroke={COLORS[idx % COLORS.length]}
            fill={COLORS[idx % COLORS.length]}
            fillOpacity={0.15}
          />
        ))}
      </RadarChart>
    </ResponsiveContainer>
  );
}
