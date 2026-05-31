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
import type { Result } from "@/schema/results";
import type { TasksFile } from "@/schema/tasks";
import { normalizeScore } from "@/lib/stats";

interface TaskRadarCompareProps {
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

export function TaskRadarCompare({
  results,
  selectedModelIds,
  tasksFile,
  modelNames,
  height = 420,
}: TaskRadarCompareProps) {
  const chartData = useMemo(() => {
    return tasksFile.tasks
      .map((task) => {
        const taskResults = results.filter((result) => result.taskId === task.id);
        const point: Record<string, string | number> = { task: task.name };

        for (const modelId of selectedModelIds) {
          const modelResults = taskResults.filter((result) => result.modelId === modelId);
          const scores = modelResults.map((result) => {
            const normalized = result.scores
              .map((score) => {
                const criterion =
                  tasksFile.coreCriteria.find((item) => item.id === score.criterionId) ??
                  task.extraCriteria.find((item) => item.id === score.criterionId);
                return criterion ? normalizeScore(score.value, criterion.scale.values) : null;
              })
              .filter((value): value is number => value != null);
            return normalized.length > 0
              ? normalized.reduce((a, b) => a + b, 0) / normalized.length
              : null;
          }).filter((value): value is number => value != null);

          point[modelId] =
            scores.length > 0 ? scores.reduce((a, b) => a + b, 0) / scores.length : 0;
        }

        return point;
      })
      .filter((point) =>
        selectedModelIds.some((modelId) => Number(point[modelId] ?? 0) > 0),
      );
  }, [results, selectedModelIds, tasksFile]);

  if (selectedModelIds.length === 0 || chartData.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RadarChart data={chartData}>
        <PolarGrid />
        <PolarAngleAxis dataKey="task" />
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
            fillOpacity={0.15}
          />
        ))}
      </RadarChart>
    </ResponsiveContainer>
  );
}
