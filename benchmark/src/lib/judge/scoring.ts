// Pure scoring helpers shared by the viewer and the judge runner. Kept free of
// schema/alias imports so it works both under Vite/vitest and plain tsx.

// Human criteria that a strong judge must NOT score. "agent_logic" is the
// human's assessment of the coding agent's workflow (did it check files, edit,
// verify, summarize) - it cannot be judged from a static artifact. Code logic is
// covered by the judge-only "logic_correctness" criterion instead.
export const JUDGE_EXCLUDED_CRITERIA = ["agent_logic"];

// A judge run that fails (a CLI timeout, a provider usage limit) must never turn a
// verdict an earlier run already produced into an error stub. Given a result's
// existing judge entries, decide whether a fresh failure for `judgeId` may be
// written: only when no successful entry for that judge exists yet, so a
// never-scored result still records the error while a good score is preserved.
export function mayRecordJudgeError(
  existing: ReadonlyArray<{ judgeId: string; error?: string | null }>,
  judgeId: string,
): boolean {
  return !existing.some((s) => s.judgeId === judgeId && s.error == null);
}

export interface ScaleCriterion {
  id: string;
  scale: { values: number[] };
}

export interface RawScore {
  criterionId: string;
  value: number;
  rationale?: string;
}

export interface ValidatedVerdict {
  scores: RawScore[];
  missing: string[];
  unknown: string[];
}

// Nearest allowed scale value. On an exact tie, the lower value wins.
export function snapToScale(value: number, scaleValues: number[]): number {
  if (scaleValues.length === 0) return value;
  const sorted = [...scaleValues].sort((a, b) => a - b);
  let best = sorted[0];
  let bestDist = Math.abs(value - best);
  for (const candidate of sorted) {
    const dist = Math.abs(value - candidate);
    // Strictly-less keeps the first (lower) candidate on a tie.
    if (dist < bestDist) {
      best = candidate;
      bestDist = dist;
    }
  }
  return best;
}

// Snap every raw score to its criterion scale, drop scores for unknown criteria,
// and report which expected criteria have no score.
export function validateVerdict(
  rawScores: RawScore[],
  criteria: ScaleCriterion[],
): ValidatedVerdict {
  const byId = new Map(criteria.map((c) => [c.id, c]));
  const scores: RawScore[] = [];
  const unknown: string[] = [];
  const covered = new Set<string>();

  for (const raw of rawScores) {
    const criterion = byId.get(raw.criterionId);
    if (!criterion) {
      unknown.push(raw.criterionId);
      continue;
    }
    covered.add(raw.criterionId);
    scores.push({
      ...raw,
      value: snapToScale(raw.value, criterion.scale.values),
    });
  }

  const missing = criteria.map((c) => c.id).filter((id) => !covered.has(id));
  return { scores, missing, unknown };
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[mid - 1] + sorted[mid]) / 2
    : sorted[mid];
}

export interface AggregatableJudgeSet {
  error?: string | null;
  scores: Array<{ criterionId: string; value: number }>;
}

// Median value per criterion across all judges that produced a valid verdict.
// Judge sets carrying an error contribute nothing.
export function aggregateJudgeScores(
  judgeSets: AggregatableJudgeSet[],
): Record<string, number> {
  const byCriterion = new Map<string, number[]>();
  for (const set of judgeSets) {
    if (set.error != null) continue;
    for (const s of set.scores) {
      const bucket = byCriterion.get(s.criterionId) ?? [];
      bucket.push(s.value);
      byCriterion.set(s.criterionId, bucket);
    }
  }
  const out: Record<string, number> = {};
  for (const [criterionId, values] of byCriterion) {
    out[criterionId] = median(values);
  }
  return out;
}

export interface WeightedCriterion {
  id: string;
  weight: number;
  scale: { values: number[] };
}

// Weighted, scale-normalized overall score from a per-criterion value map, using
// the same weighting as the human overall score. Returns null if nothing matches.
export function weightedNormalized(
  scores: Record<string, number>,
  criteria: WeightedCriterion[],
): number | null {
  let sum = 0;
  let totalWeight = 0;
  for (const c of criteria) {
    const value = scores[c.id];
    if (value === undefined) continue;
    const max = Math.max(...c.scale.values);
    const normalized = max === 0 ? 0 : value / max;
    sum += normalized * c.weight;
    totalWeight += c.weight;
  }
  return totalWeight === 0 ? null : sum / totalWeight;
}

// Largest raw-value gap between a human score and the judge aggregate over the
// criteria both scored. Drives the viewer's divergence badge.
export function maxSharedDivergence(
  humanScores: Array<{ criterionId: string; value: number }>,
  aggregate: Record<string, number>,
): number {
  let max = 0;
  for (const h of humanScores) {
    const a = aggregate[h.criterionId];
    if (a === undefined) continue;
    max = Math.max(max, Math.abs(h.value - a));
  }
  return max;
}

// Cross-attempt score variance: for each criterion, the mean absolute deviation
// of its per-attempt aggregate value, averaged over criteria. 0 = identical.
export function scoreVariance(
  perAttemptAggregates: Array<Record<string, number>>,
): number {
  if (perAttemptAggregates.length < 2) return 0;
  const criterionIds = new Set<string>();
  for (const agg of perAttemptAggregates) {
    for (const id of Object.keys(agg)) criterionIds.add(id);
  }
  if (criterionIds.size === 0) return 0;

  const deviations: number[] = [];
  for (const id of criterionIds) {
    const values = perAttemptAggregates
      .map((agg) => agg[id])
      .filter((v): v is number => typeof v === "number");
    if (values.length < 2) continue;
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    const mad =
      values.reduce((a, b) => a + Math.abs(b - mean), 0) / values.length;
    deviations.push(mad);
  }
  if (deviations.length === 0) return 0;
  return deviations.reduce((a, b) => a + b, 0) / deviations.length;
}
