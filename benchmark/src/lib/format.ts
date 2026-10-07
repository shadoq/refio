import dayjs from "dayjs";
import duration from "dayjs/plugin/duration";

dayjs.extend(duration);

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "-";
  const d = dayjs.duration(ms);
  const minutes = Math.floor(d.asMinutes());
  const seconds = Math.floor(d.asSeconds() % 60);
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export function formatCost(usd: number | null | undefined): string {
  if (usd == null) return "-";
  if (usd === 0) return "$0.00";
  if (usd < 0.01) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

export function formatTokens(count: number | null | undefined): string {
  if (count == null) return "-";
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k`;
  return String(count);
}

export function formatTokensPerSecond(value: number | null | undefined): string {
  if (value == null) return "-";
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M tok/s`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k tok/s`;
  if (value >= 10) return `${value.toFixed(0)} tok/s`;
  return `${value.toFixed(1)} tok/s`;
}

export function formatScore(score: number): string {
  return `${(score * 100).toFixed(1)}%`;
}

// One clearly distinct hue per 25% step of a normalized score: red, orange,
// yellow-green from the pass mark (50%, 3 of 6), green for the top quarter. Tuned for
// the dark theme.
const SCORE_HUES = [0, 35, 80, 125];

export function scoreColor(score: number): string {
  const step = Math.min(3, Math.max(0, Math.floor(score * 4)));
  return `hsl(${SCORE_HUES[step]}, 75%, 60%)`;
}

// One short line describing the weights behind a model row, e.g.
// "Q4_K_M · MoE A3B · 256k ctx · 23.9 GB". Empty for a model with no such data.
export function formatModelSpec(model: {
  quantization?: string;
  architecture?: "dense" | "moe";
  activeParameterCount?: string;
  contextWindow?: number;
  sizeGb?: number;
}): string {
  const parts: string[] = [];
  if (model.quantization) parts.push(model.quantization);
  if (model.architecture === "moe") {
    parts.push(model.activeParameterCount ? `MoE A${model.activeParameterCount}` : "MoE");
  } else if (model.architecture === "dense") {
    parts.push("dense");
  }
  if (model.contextWindow) parts.push(`${Math.round(model.contextWindow / 1024)}k ctx`);
  if (model.sizeGb) parts.push(`${model.sizeGb} GB`);
  return parts.join(" · ");
}
