import type { StabilityEntry } from "@/schema/results";

// Same ceiling as the leaderboard reliability score: a mean deviation of 0.5 between
// attempts (half the normalized scale) already means the attempts are unrelated.
const DEVIATION_CEILING = 0.5;

export function scoreConsistency(scoreVariance: number): number {
  return Math.min(1, Math.max(0, 1 - scoreVariance / DEVIATION_CEILING));
}

function mean(values: number[]): number | null {
  return values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : null;
}

function median(values: number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

export interface EntryStability {
  consistency: number;
  similarity: number;
  judge: number | null; // median verdict across judges; null until any judge scored it
  overall: number;
}

// One number per (task, model, env) group: equal-weight blend of the three stability
// signals. A group with no judge verdict yet is scored on the deterministic parts only.
export function entryStability(entry: StabilityEntry): EntryStability {
  const consistency = scoreConsistency(entry.deterministic.scoreVariance);
  const similarity = entry.deterministic.codeSimilarity;
  const judge = median(entry.judges.map((j) => j.value));
  const parts = judge == null ? [consistency, similarity] : [consistency, similarity, judge];
  return { consistency, similarity, judge, overall: mean(parts)! };
}

export interface ModelStability {
  modelId: string;
  groups: number;
  overall: number;
  consistency: number;
  similarity: number;
  judge: number | null;
  byJudge: Record<string, number>;
  byTask: Record<string, number>;
}

export function modelStability(entries: StabilityEntry[], modelId: string): ModelStability | null {
  const own = entries.filter((e) => e.modelId === modelId);
  if (own.length === 0) return null;
  const scored = own.map((e) => ({ entry: e, s: entryStability(e) }));

  // A model can have several groups per task (environments, harnesses) - average them.
  const taskValues: Record<string, number[]> = {};
  for (const { entry, s } of scored) (taskValues[entry.taskId] ??= []).push(s.overall);
  const byTask = Object.fromEntries(
    Object.entries(taskValues).map(([taskId, v]) => [taskId, mean(v)!]),
  );

  const judgeValues: Record<string, number[]> = {};
  for (const { entry } of scored) {
    for (const j of entry.judges) (judgeValues[j.judgeId] ??= []).push(j.value);
  }
  const byJudge = Object.fromEntries(
    Object.entries(judgeValues).map(([judgeId, v]) => [judgeId, mean(v)!]),
  );

  return {
    modelId,
    groups: own.length,
    overall: mean(Object.values(byTask))!,
    consistency: mean(scored.map((x) => x.s.consistency))!,
    similarity: mean(scored.map((x) => x.s.similarity))!,
    judge: mean(scored.map((x) => x.s.judge).filter((v): v is number => v != null)),
    byJudge,
    byTask,
  };
}

export interface StabilityFilters {
  modelIds: string[];
  taskIds: string[];
  environmentIds: string[];
  harnessIds: string[];
}

// Stability entries have no run date, so only the identity filters apply.
export function filterStabilityEntries(
  entries: StabilityEntry[],
  f: StabilityFilters,
  hiddenTaskIds: Set<string>,
): StabilityEntry[] {
  return entries.filter(
    (e) =>
      !hiddenTaskIds.has(e.taskId) &&
      (f.modelIds.length === 0 || f.modelIds.includes(e.modelId)) &&
      (f.taskIds.length === 0 || f.taskIds.includes(e.taskId)) &&
      (f.environmentIds.length === 0 || f.environmentIds.includes(e.environmentId)) &&
      (f.harnessIds.length === 0 || f.harnessIds.includes(e.harnessId)),
  );
}
