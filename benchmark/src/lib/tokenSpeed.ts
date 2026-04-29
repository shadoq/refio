import type { Result } from "@/schema/results";

export const ASSUMED_PREFILL_TIME_SHARE = 0.2;
export const ASSUMED_DECODE_TIME_SHARE = 0.8;

export interface TokenProcessingEstimate {
  prefillMs: number | null;
  decodeMs: number | null;
  totalMs: number | null;
  prefillTokensPerSecond: number | null;
  decodeTokensPerSecond: number | null;
}

export function estimateTokenProcessing(
  tokensIn: number | null | undefined,
  tokensOut: number | null | undefined,
  durationMs: number | null | undefined,
): TokenProcessingEstimate {
  const hasTokensIn = tokensIn != null && tokensIn > 0;
  const hasTokensOut = tokensOut != null && tokensOut > 0;
  const hasDuration = durationMs != null && durationMs > 0;
  const hasBothTokenSides = hasTokensIn && hasTokensOut;

  const prefillMs =
    hasTokensIn && hasDuration
      ? durationMs * (hasBothTokenSides ? ASSUMED_PREFILL_TIME_SHARE : 1)
      : null;
  const decodeMs =
    hasTokensOut && hasDuration
      ? durationMs * (hasBothTokenSides ? ASSUMED_DECODE_TIME_SHARE : 1)
      : null;
  const totalMs =
    hasDuration && (hasTokensIn || hasTokensOut) ? (prefillMs ?? 0) + (decodeMs ?? 0) : null;

  return {
    prefillMs,
    decodeMs,
    totalMs,
    prefillTokensPerSecond:
      tokensIn == null || prefillMs == null || prefillMs === 0
        ? null
        : tokensIn / (prefillMs / 1000),
    decodeTokensPerSecond:
      tokensOut == null || decodeMs == null || decodeMs === 0
        ? null
        : tokensOut / (decodeMs / 1000),
  };
}

export function estimateResultTokenProcessing(
  result: Pick<Result, "tokensIn" | "tokensOut" | "durationMs">,
): TokenProcessingEstimate {
  return estimateTokenProcessing(result.tokensIn, result.tokensOut, result.durationMs);
}
