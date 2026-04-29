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
