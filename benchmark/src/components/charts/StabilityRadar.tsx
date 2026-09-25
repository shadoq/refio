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

interface StabilityRadarProps {
  // One point per axis: { axis: label, [modelId]: 0..1 }.
  data: Record<string, string | number>[];
  selectedModelIds: string[];
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

export function StabilityRadar({
  data,
  selectedModelIds,
  modelNames,
  height = 400,
}: StabilityRadarProps) {
  if (selectedModelIds.length === 0 || data.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RadarChart data={data}>
        <PolarGrid />
        <PolarAngleAxis dataKey="axis" />
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
