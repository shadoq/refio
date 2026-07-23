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
import { aggregateJudgeScores, JUDGE_EXCLUDED_CRITERIA } from "@/lib/judge/scoring";

interface JudgeRadarCompareProps {
  results: Result[];
  selectedModelIds: string[];
  tasksFile: TasksFile;
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

// Radar over the strong-judge aggregate (median across judges) per criterion.
// Mirrors RadarCompare but reads judgeScores instead of the human scores, over
// core (minus judge-excluded) + judge-only criteria.
export function JudgeRadarCompare({
  results,
  selectedModelIds,
  tasksFile,
  modelNames,
  height = 400,
}: JudgeRadarCompareProps) {
  const criteria = useMemo(
    () =>
      [...tasksFile.coreCriteria, ...tasksFile.judgeCriteria].filter(
        (c) => !JUDGE_EXCLUDED_CRITERIA.includes(c.id),
      ),
    [tasksFile],
  );

  const chartData = useMemo(() => {
    return criteria.map((criterion) => {
      const point: Record<string, string | number> = { criterion: criterion.name };
      for (const modelId of selectedModelIds) {
        const modelResults = results.filter((r) => r.modelId === modelId);
        const values: number[] = [];
        for (const r of modelResults) {
          const sets = (r.judgeScores ?? []).filter((s) => s.error == null);
          if (sets.length === 0) continue;
          const value = aggregateJudgeScores(sets)[criterion.id];
          if (value !== undefined) values.push(normalizeScore(value, criterion.scale.values));
        }
        point[modelId] =
          values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
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
