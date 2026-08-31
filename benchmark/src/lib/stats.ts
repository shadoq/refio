import type { TasksFile, Criterion } from "@/schema/tasks";
import type { Result, ResultsFile, Model, Environment } from "@/schema/results";
import { estimateTokenProcessing } from "@/lib/tokenSpeed";
import {
  aggregateJudgeScores,
  weightedNormalized,
  JUDGE_EXCLUDED_CRITERIA,
} from "@/lib/judge/scoring";

export interface LeaderboardRow {
  modelId: string;
  environmentId: string;
  model: Model;
  environment: Environment;
  attemptCount: number;
  tasksEvaluated: number;
  avgScore: number;
  totalCostUsd: number | null;
  avgCostUsd: number | null;
  avgDurationMs: number | null;
  avgEstimatedPrefillMs: number | null;
  avgEstimatedDecodeMs: number | null;
  avgEstimatedLlmMs: number | null;
  avgPrefillTokensPerSecond: number | null;
  avgDecodeTokensPerSecond: number | null;
  passRate: number;
  avgWorksOutOfBoxScore: number | null;
  avgComplianceScore: number | null;
  reliabilityScore: number | null;
  firstShotScore: number | null;
  firstShotSuccess: boolean | null;
  localViabilityScore: number | null;
  localQualityRatio: number | null;
  judgeAvgScore: number | null;
  judgedAttempts: number;
}

const PASS_THRESHOLD = 0.7;
const FIRST_SHOT_CRITERION_ID = "works_out_of_box";
const COMPLIANCE_CRITERION_ID = "compliance";

export function normalizeScore(value: number, scaleValues: number[]): number {
  const max = Math.max(...scaleValues);
  if (max === 0) return 0;
  return value / max;
}

// A task flagged `hidden` is dropped from the public results view and from every
// measurement. Admin views keep the raw list so the flag stays toggleable.
export function visibleTasks<T extends { hidden?: boolean }>(tasks: T[]): T[] {
  return tasks.filter((task) => task.hidden !== true);
}

// Drop results whose task is hidden so leaderboards and charts never count them.
// A result whose taskId is unknown is kept - only known-hidden tasks are excluded.
export function excludeHiddenResults(results: Result[], tasks: TasksFile): Result[] {
  const hidden = new Set(tasks.tasks.filter((t) => t.hidden === true).map((t) => t.id));
  if (hidden.size === 0) return results;
  return results.filter((r) => !hidden.has(r.taskId));
}

export function getResultCriterionScore(
  result: Result,
  tasks: TasksFile,
  criterionId: string,
): number | null {
  const task = tasks.tasks.find((t) => t.id === result.taskId);
  const criterion =
    tasks.coreCriteria.find((c) => c.id === criterionId) ??
    task?.extraCriteria.find((c) => c.id === criterionId);
  const score = result.scores.find((s) => s.criterionId === criterionId);
  if (!criterion || !score) return null;
  return normalizeScore(score.value, criterion.scale.values);
}

// Criteria a strong judge scores for a task: human core + task extra + judge-only,
// minus criteria a judge cannot assess from a static artifact.
export function judgeCriteriaForTask(tasks: TasksFile, taskId: string): Criterion[] {
  const task = tasks.tasks.find((t) => t.id === taskId);
  return [...tasks.coreCriteria, ...(task?.extraCriteria ?? []), ...tasks.judgeCriteria].filter(
    (c) => !JUDGE_EXCLUDED_CRITERIA.includes(c.id),
  );
}

// Overall judge score for one result: the judge aggregate (median per criterion),
// weighted-normalized like the human score. Null when no judge scored it.
export function getResultJudgeScore(result: Result, tasks: TasksFile): number | null {
  const sets = (result.judgeScores ?? []).filter((s) => s.error == null);
  if (sets.length === 0) return null;
  const aggregate = aggregateJudgeScores(sets);
  return weightedNormalized(aggregate, judgeCriteriaForTask(tasks, result.taskId));
}

export function normalizeResult(result: Result, tasks: TasksFile): number {
  const task = tasks.tasks.find((t) => t.id === result.taskId);
  const allCriteria = new Map<string, Criterion>([
    ...tasks.coreCriteria.map((c): [string, Criterion] => [c.id, c]),
    ...(task?.extraCriteria ?? []).map((c): [string, Criterion] => [c.id, c]),
  ]);

  const weightedScores: Array<{ value: number; weight: number }> = [];
  for (const score of result.scores) {
    const criterion = allCriteria.get(score.criterionId);
    if (!criterion) continue;
    weightedScores.push({
      value: normalizeScore(score.value, criterion.scale.values),
      weight: criterion.weight,
    });
  }

  const totalWeight = weightedScores.reduce((sum, score) => sum + score.weight, 0);
  if (totalWeight === 0) return 0;
  return (
    weightedScores.reduce((sum, score) => sum + score.value * score.weight, 0) /
    totalWeight
  );
}

function standardDeviation(values: number[]): number {
  if (values.length < 2) return 0;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  const variance =
    values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / values.length;
  return Math.sqrt(variance);
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function avgNullable(values: Array<number | null>): number | null {
  const nums = values.filter((value): value is number => value != null);
  if (nums.length === 0) return null;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

function compareNullableDesc(a: number | null, b: number | null): number {
  return (b ?? -1) - (a ?? -1);
}

export function compareLeaderboardRows(a: LeaderboardRow, b: LeaderboardRow): number {
  return (
    b.avgScore - a.avgScore ||
    compareNullableDesc(a.avgWorksOutOfBoxScore, b.avgWorksOutOfBoxScore) ||
    compareNullableDesc(a.avgComplianceScore, b.avgComplianceScore) ||
    compareNullableDesc(a.firstShotScore, b.firstShotScore)
  );
}

export function leaderboard(
  results: Result[],
  resultsFile: Pick<ResultsFile, "models" | "environments">,
  tasks: TasksFile,
): LeaderboardRow[] {
  const measured = excludeHiddenResults(results, tasks);
  const modelById = new Map(resultsFile.models.map((m) => [m.id, m]));
  const envById = new Map(resultsFile.environments.map((e) => [e.id, e]));

  // Group by (modelId, environmentId)
  const groups = new Map<string, Result[]>();
  for (const r of measured) {
    const key = `${r.modelId}::${r.environmentId}`;
    const group = groups.get(key) ?? [];
    group.push(r);
    groups.set(key, group);
  }

  const rows: LeaderboardRow[] = [];
  for (const [, group] of groups) {
    const { modelId, environmentId } = group[0];
    const model = modelById.get(modelId);
    const environment = envById.get(environmentId);
    if (!model || !environment) continue;

    const scores = group.map((r) => normalizeResult(r, tasks));
    const avgScore = scores.reduce((a, b) => a + b, 0) / scores.length;
    const judgeVals = group
      .map((r) => getResultJudgeScore(r, tasks))
      .filter((v): v is number => v != null);
    const judgeAvgScore =
      judgeVals.length > 0 ? judgeVals.reduce((a, b) => a + b, 0) / judgeVals.length : null;
    const avgWorksOutOfBoxScore = avgNullable(
      group.map((r) => getResultCriterionScore(r, tasks, FIRST_SHOT_CRITERION_ID)),
    );
    const avgComplianceScore = avgNullable(
      group.map((r) => getResultCriterionScore(r, tasks, COMPLIANCE_CRITERION_ID)),
    );
    const passCount = scores.filter((s) => s >= PASS_THRESHOLD).length;
    const scoreStdDev = standardDeviation(scores);
    const reliabilityScore =
      scores.length >= 2 ? clamp(1 - scoreStdDev / 0.5, 0, 1) : null;
    const firstShot = group
      .slice()
      .sort((a, b) => a.attemptNumber - b.attemptNumber)
      .find((r) => r.attemptNumber === 1) ?? null;
    const firstShotScore = firstShot ? normalizeResult(firstShot, tasks) : null;
    const firstShotWorks = firstShot
      ? getResultCriterionScore(firstShot, tasks, FIRST_SHOT_CRITERION_ID)
      : null;
    const firstShotSuccess =
      firstShotScore == null
        ? null
        : (firstShotWorks ?? firstShotScore) >= PASS_THRESHOLD;

    const costs = group.map((r) => r.costUsd).filter((c): c is number => c != null);
    const durations = group.map((r) => r.durationMs).filter((d): d is number => d != null);
    const tokenProcessing = group.map((r) =>
      estimateTokenProcessing(r.tokensIn, r.tokensOut, r.durationMs),
    );
    const estimatedPrefillMs = tokenProcessing
      .map((estimate) => estimate.prefillMs)
      .filter((value): value is number => value != null);
    const estimatedDecodeMs = tokenProcessing
      .map((estimate) => estimate.decodeMs)
      .filter((value): value is number => value != null);
    const estimatedLlmMs = tokenProcessing
      .map((estimate) => estimate.totalMs)
      .filter((value): value is number => value != null);
    const prefillTps = tokenProcessing
      .map((estimate) => estimate.prefillTokensPerSecond)
      .filter((value): value is number => value != null);
    const decodeTps = tokenProcessing
      .map((estimate) => estimate.decodeTokensPerSecond)
      .filter((value): value is number => value != null);

    rows.push({
      modelId,
      environmentId,
      model,
      environment,
      attemptCount: group.length,
      tasksEvaluated: new Set(group.map((r) => r.taskId)).size,
      avgScore,
      totalCostUsd: costs.length > 0 ? costs.reduce((a, b) => a + b, 0) : null,
      avgCostUsd: costs.length > 0 ? costs.reduce((a, b) => a + b, 0) / costs.length : null,
      avgDurationMs: durations.length > 0 ? durations.reduce((a, b) => a + b, 0) / durations.length : null,
      avgEstimatedPrefillMs:
        estimatedPrefillMs.length > 0
          ? estimatedPrefillMs.reduce((a, b) => a + b, 0) / estimatedPrefillMs.length
          : null,
      avgEstimatedDecodeMs:
        estimatedDecodeMs.length > 0
          ? estimatedDecodeMs.reduce((a, b) => a + b, 0) / estimatedDecodeMs.length
          : null,
      avgEstimatedLlmMs:
        estimatedLlmMs.length > 0
          ? estimatedLlmMs.reduce((a, b) => a + b, 0) / estimatedLlmMs.length
          : null,
      avgPrefillTokensPerSecond:
        prefillTps.length > 0 ? prefillTps.reduce((a, b) => a + b, 0) / prefillTps.length : null,
      avgDecodeTokensPerSecond:
        decodeTps.length > 0 ? decodeTps.reduce((a, b) => a + b, 0) / decodeTps.length : null,
      passRate: passCount / group.length,
      avgWorksOutOfBoxScore,
      avgComplianceScore,
      reliabilityScore,
      firstShotScore,
      firstShotSuccess,
      localViabilityScore: null,
      localQualityRatio: null,
      judgeAvgScore,
      judgedAttempts: judgeVals.length,
    });
  }

  const bestCloudScore = Math.max(
    0,
    ...rows
      .filter((row) => row.environment.type === "cloud")
      .map((row) => row.avgScore),
  );
  for (const row of rows) {
    if (row.environment.type !== "local") continue;
    const localQualityRatio =
      bestCloudScore > 0 ? row.avgScore / bestCloudScore : row.avgScore;
    const stability = row.reliabilityScore ?? row.passRate;
    row.localQualityRatio = localQualityRatio;
    row.localViabilityScore = clamp(localQualityRatio * 0.7 + stability * 0.3, 0, 1.2);
  }

  return rows.sort(compareLeaderboardRows);
}
