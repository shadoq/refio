import { deterministicVerdict } from "@/lib/catalog/inbox";
import { GOOD_SCORE } from "@/lib/judge/scoring";
import type { ResultsFile } from "@/schema/results";

// Reviewed runs carry human scores; queue runs only the automatic verdict of the
// deterministic judge. The overview can show either or both, so every run is
// flattened to the same shape first.
export type RunSource = "reviewed" | "queue";

export interface OverviewRun {
  id: string;
  modelId: string;
  taskId: string;
  harnessId: string;
  environmentId: string;
  runAt: string;
  source: RunSource;
  passed: boolean;
  works: number | null;
  agentLogic: number | null;
  costUsd: number | null;
  durationMs: number | null;
  reasoning: string | null;
}

const DETERMINISTIC_JUDGE = "e2e-deterministic";

function scoreOf(scores: Array<{ criterionId: string; value: number }>, id: string): number | null {
  return scores.find((s) => s.criterionId === id)?.value ?? null;
}

function reasoningLabel(thinking?: { requested: string; level?: string }): string | null {
  if (!thinking) return null;
  if (thinking.level) return thinking.level.toLowerCase();
  return thinking.requested === "unknown" ? null : thinking.requested;
}

export function collectRuns(file: ResultsFile, sources: RunSource[]): OverviewRun[] {
  const runs: OverviewRun[] = [];
  const reviewedIds = new Set(file.results.map((r) => r.id));

  if (sources.includes("reviewed")) {
    for (const r of file.results) {
      runs.push({
        id: r.id,
        modelId: r.modelId,
        taskId: r.taskId,
        harnessId: r.harnessId,
        environmentId: r.environmentId,
        runAt: r.runAt,
        source: "reviewed",
        passed: deterministicVerdict(r.scores).verdict === "PASS",
        works: scoreOf(r.scores, "works_out_of_box"),
        agentLogic: scoreOf(r.scores, "agent_logic"),
        costUsd: r.costUsd ?? null,
        durationMs: r.durationMs ?? null,
        reasoning: reasoningLabel(r.thinking),
      });
    }
  }

  if (sources.includes("queue")) {
    for (const e of file.inbox) {
      // A run promoted to the reviewed set is the same run; count it once.
      if (reviewedIds.has(e.id)) continue;
      const scores = e.judgeScores.find((j) => j.judgeId === DETERMINISTIC_JUDGE)?.scores ?? [];
      runs.push({
        id: e.id,
        modelId: e.modelId,
        taskId: e.taskId,
        harnessId: e.harnessId,
        environmentId: e.environmentId,
        runAt: e.runAt,
        source: "queue",
        passed: (e.autoVerdict ?? deterministicVerdict(scores)).verdict === "PASS",
        works: scoreOf(scores, "works_out_of_box"),
        agentLogic: scoreOf(scores, "agent_logic"),
        costUsd: e.costUsd ?? null,
        durationMs: e.durationMs ?? null,
        reasoning: reasoningLabel(e.thinking),
      });
    }
  }
  return runs;
}

function groupBy<T>(items: T[], key: (item: T) => string): Map<string, T[]> {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    const group = groups.get(k);
    if (group) group.push(item);
    else groups.set(k, [item]);
  }
  return groups;
}

function average(values: Array<number | null>): number | null {
  const known = values.filter((v): v is number => v != null);
  return known.length ? known.reduce((a, b) => a + b, 0) / known.length : null;
}

function total(values: Array<number | null>): number | null {
  const known = values.filter((v): v is number => v != null);
  return known.length ? known.reduce((a, b) => a + b, 0) : null;
}

function modelName(file: ResultsFile, modelId: string): string {
  return file.models.find((m) => m.id === modelId)?.name ?? modelId;
}

// The label every run of a model agrees on, or "mixed" when runs differ.
function commonLabel(values: Array<string | null>): string | null {
  const distinct = [...new Set(values.filter((v): v is string => v != null))];
  if (distinct.length === 0) return null;
  return distinct.length === 1 ? distinct[0] : "mixed";
}

export interface ModelSummary {
  modelId: string;
  name: string;
  reasoning: string | null;
  attempts: number;
  passed: number;
  worksFull: number;
  agentLogicFull: number;
  costUsd: number | null;
  avgDurationMs: number | null;
  sources: RunSource[];
}

export function summarizeByModel(runs: OverviewRun[], file: ResultsFile): ModelSummary[] {
  const rows = [...groupBy(runs, (r) => r.modelId)].map(([modelId, group]) => ({
    modelId,
    name: modelName(file, modelId),
    reasoning: commonLabel(group.map((r) => r.reasoning)),
    attempts: group.length,
    passed: group.filter((r) => r.passed).length,
    worksFull: group.filter((r) => r.works != null && r.works >= GOOD_SCORE).length,
    agentLogicFull: group.filter((r) => r.agentLogic != null && r.agentLogic >= GOOD_SCORE).length,
    costUsd: total(group.map((r) => r.costUsd)),
    avgDurationMs: average(group.map((r) => r.durationMs)),
    sources: [...new Set(group.map((r) => r.source))],
  }));
  return rows.sort(
    (a, b) =>
      b.passed / b.attempts - a.passed / a.attempts ||
      (a.costUsd ?? Infinity) - (b.costUsd ?? Infinity),
  );
}

export interface PassCell {
  passed: number;
  attempts: number;
}

export interface MatrixRow {
  modelId: string;
  name: string;
  cells: Record<string, PassCell>;
}

export function passMatrix(runs: OverviewRun[], file: ResultsFile): MatrixRow[] {
  return summarizeByModel(runs, file).map((model) => {
    const cells: Record<string, PassCell> = {};
    for (const r of runs.filter((run) => run.modelId === model.modelId)) {
      const cell = (cells[r.taskId] ??= { passed: 0, attempts: 0 });
      cell.attempts += 1;
      if (r.passed) cell.passed += 1;
    }
    return { modelId: model.modelId, name: model.name, cells };
  });
}

export interface TaskSummary {
  taskId: string;
  attempts: number;
  passed: number;
  models: number;
  perfectModels: string[];
  weakestModels: string[];
  costUsd: number | null;
  avgDurationMs: number | null;
}

export function summarizeByTask(runs: OverviewRun[], file: ResultsFile): TaskSummary[] {
  return [...groupBy(runs, (r) => r.taskId)]
    .map(([taskId, group]) => {
      const byModel = [...groupBy(group, (r) => r.modelId)].map(([modelId, rs]) => ({
        name: modelName(file, modelId),
        rate: rs.filter((r) => r.passed).length / rs.length,
      }));
      const lowest = Math.min(...byModel.map((m) => m.rate));
      return {
        taskId,
        attempts: group.length,
        passed: group.filter((r) => r.passed).length,
        models: byModel.length,
        perfectModels: byModel.filter((m) => m.rate === 1).map((m) => m.name),
        weakestModels: lowest < 1 ? byModel.filter((m) => m.rate === lowest).map((m) => m.name) : [],
        costUsd: total(group.map((r) => r.costUsd)),
        avgDurationMs: average(group.map((r) => r.durationMs)),
      };
    })
    .sort((a, b) => a.passed / a.attempts - b.passed / b.attempts);
}
