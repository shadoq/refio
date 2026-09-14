// How long an external agent may work on one task, and how many turns it gets. Tied
// to the task's difficulty tier: a cap that bites measures the cap rather than the
// agent, and a generous cap on a trivial task leaves a stalled run hanging for hours.
// Pure, no IO, so both vitest and the tsx importer can read it.
export interface AgentLimits {
  timeoutMs: number;
  maxTurns: number;
}

export const AGENT_LIMITS_BY_TIER: Record<"easy" | "medium" | "hard" | "stress", AgentLimits> = {
  easy: { timeoutMs: 15 * 60_000, maxTurns: 40 },
  medium: { timeoutMs: 30 * 60_000, maxTurns: 60 },
  hard: { timeoutMs: 60 * 60_000, maxTurns: 120 },
  stress: { timeoutMs: 120 * 60_000, maxTurns: 200 },
};

export const DEFAULT_AGENT_LIMITS: AgentLimits = AGENT_LIMITS_BY_TIER.medium;

export function limitsForTier(tier: string | undefined): AgentLimits {
  if (tier && tier in AGENT_LIMITS_BY_TIER) {
    return AGENT_LIMITS_BY_TIER[tier as keyof typeof AGENT_LIMITS_BY_TIER];
  }
  return DEFAULT_AGENT_LIMITS;
}
