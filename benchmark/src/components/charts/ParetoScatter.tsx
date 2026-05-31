import { useMemo } from "react";
import {
  ScatterChart,
  Scatter,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  ReferenceLine,
  Label,
} from "recharts";
import { paretoFront } from "@/lib/paretoFront";

interface ScatterPoint {
  id: string;
  x: number;
  y: number;
  label: string;
  provider: string;
  attemptCount: number;
  environmentType?: "local" | "cloud";
  xFormatted?: string;
  yFormatted?: string;
}

interface ParetoScatterProps {
  points: ScatterPoint[];
  height?: number;
  mini?: boolean;
  xLabel?: string;
  yLabel?: string;
  higherYIsBetter?: boolean;
  lowerXIsBetter?: boolean;
}

const PROVIDER_COLORS: Record<string, string> = {
  ollama: "var(--accent)",
  anthropic: "var(--accent-2)",
  openai: "#9b8cff",
  openrouter: "#ffd166",
  gemini: "#13c2c2",
};

function getColor(provider: string): string {
  return PROVIDER_COLORS[provider.toLowerCase()] ?? "#9fb0c5";
}

interface TooltipPayloadItem {
  payload: ScatterPoint & { fill: string; r: number; onFront: boolean };
}

function CustomTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: TooltipPayloadItem[];
}) {
  if (!active || !payload?.length) return null;
  const p = payload[0].payload;
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-title">{p.label}</div>
      {p.onFront && <div className="chart-tooltip-front">Pareto front</div>}
      <div>Y: {p.yFormatted ?? p.y.toFixed(4)}</div>
      <div>X: {p.xFormatted ?? p.x.toFixed(4)}</div>
      <div>Attempts: {p.attemptCount}</div>
      <div>Provider: {p.provider}</div>
    </div>
  );
}

export function ParetoScatter({
  points,
  height = 450,
  mini = false,
  xLabel = "Cost / Duration",
  yLabel = "Avg Score",
  higherYIsBetter = true,
  lowerXIsBetter = true,
}: ParetoScatterProps) {
  const paretoInput = useMemo(
    () =>
      points.map((point) => ({
        ...point,
        x: lowerXIsBetter ? point.x : -point.x,
        y: higherYIsBetter ? point.y : -point.y,
      })),
    [points, lowerXIsBetter, higherYIsBetter],
  );
  const front = useMemo(() => paretoFront(paretoInput), [paretoInput]);

  const frontPoints = useMemo(
    () =>
      points
        .filter((p) => front.has(p.id))
        .map((p) => ({
          ...p,
          fill: getColor(p.provider),
          r: Math.max(6, Math.min(14, 4 + p.attemptCount * 2)),
          onFront: true,
        })),
    [points, front],
  );

  const otherPoints = useMemo(
    () =>
      points
        .filter((p) => !front.has(p.id))
        .map((p) => ({
          ...p,
          fill: "#9fb0c5",
          r: Math.max(4, Math.min(10, 3 + p.attemptCount * 2)),
          onFront: false,
        })),
    [points, front],
  );

  const frontLine = useMemo(
    () =>
      frontPoints
        .slice()
        .sort((a, b) => a.x - b.x)
        .map((p) => ({ x: p.x, y: p.y })),
    [frontPoints],
  );

  if (points.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <ScatterChart margin={{ top: 20, right: 40, bottom: 40, left: 20 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(159, 176, 197, 0.22)" />
        <XAxis
          type="number"
          dataKey="x"
          name={xLabel}
          tickFormatter={(v: number) => (v < 0.01 ? v.toFixed(4) : v.toFixed(2))}
        >
          {!mini && <Label value={xLabel} offset={-10} position="insideBottom" />}
        </XAxis>
        <YAxis
          type="number"
          dataKey="y"
          name={yLabel}
          tickFormatter={(v: number) => (Math.abs(v) <= 1 ? `${(v * 100).toFixed(0)}%` : v.toFixed(1))}
        >
          {!mini && <Label value={yLabel} angle={-90} position="insideLeft" offset={10} />}
        </YAxis>
        <Tooltip content={<CustomTooltip />} />

        {frontLine.length >= 2 &&
          frontLine.slice(0, -1).map((p, idx) => (
            <ReferenceLine
              key={idx}
              segment={[
                { x: p.x, y: p.y },
                { x: frontLine[idx + 1].x, y: frontLine[idx + 1].y },
              ]}
              stroke="var(--accent-2)"
              opacity={0.9}
              strokeDasharray="6 3"
              strokeWidth={1.5}
            />
          ))}

        <Scatter
          name="Other"
          data={otherPoints}
          fill="#9fb0c5"
          shape={(props: unknown) => {
            const { cx = 0, cy = 0, payload } = props as {
              cx?: number;
              cy?: number;
              payload?: { r?: number; fill?: string };
            };
            const r = payload?.r ?? 5;
            return <circle cx={cx} cy={cy} r={r} fill={payload?.fill ?? "#9fb0c5"} opacity={0.36} />;
          }}
        />

        <Scatter
          name="Pareto front"
          data={frontPoints}
          fill="var(--accent-2)"
          shape={(props: unknown) => {
            const { cx = 0, cy = 0, payload } = props as {
              cx?: number;
              cy?: number;
              payload?: { r?: number; fill?: string };
            };
            const r = payload?.r ?? 6;
            return (
              <circle
                cx={cx}
                cy={cy}
                r={r}
                fill={payload?.fill ?? "var(--accent-2)"}
                stroke="#e6edf6"
                strokeWidth={2}
              />
            );
          }}
        />
      </ScatterChart>
    </ResponsiveContainer>
  );
}
