// Load/save helpers that preserve the raw results.json shape so a judge run only
// adds judgeScores to the targeted result (and stability entries), never rewrites
// the whole file with schema defaults.
import { readFile, writeFile, copyFile } from "node:fs/promises";
import { join } from "node:path";
import { TasksFileSchema, type TasksFile, type Criterion } from "../../../src/schema/tasks";
import {
  ResultsFileSchema,
  type JudgeScoreSet,
  type StabilityEntry,
} from "../../../src/schema/results";
import {
  JUDGE_EXCLUDED_CRITERIA,
  mayRecordJudgeError,
} from "../../../src/lib/judge/scoring";

export interface RawResult {
  id: string;
  taskId: string;
  modelId: string;
  environmentId: string;
  attachments: Array<{ type: string; src: string }>;
  judgeScores?: JudgeScoreSet[];
  [key: string]: unknown;
}

export interface RawResultsFile {
  results: RawResult[];
  stability?: StabilityEntry[];
  [key: string]: unknown;
}

export async function loadTasks(benchmarkDir: string): Promise<TasksFile> {
  const raw = JSON.parse(await readFile(join(benchmarkDir, "data/tasks.json"), "utf8"));
  return TasksFileSchema.parse(raw);
}

export async function loadRawResults(benchmarkDir: string): Promise<RawResultsFile> {
  return JSON.parse(await readFile(join(benchmarkDir, "data/results.json"), "utf8"));
}

// Active criteria a judge scores: human core + task extra + judge-only criteria,
// minus criteria a judge cannot assess from a static artifact (see
// JUDGE_EXCLUDED_CRITERIA).
export function resolveCriteria(tasks: TasksFile, taskId: string): Criterion[] {
  const task = tasks.tasks.find((t) => t.id === taskId);
  const extra = task?.extraCriteria ?? [];
  return [...tasks.coreCriteria, ...extra, ...tasks.judgeCriteria].filter(
    (c) => !JUDGE_EXCLUDED_CRITERIA.includes(c.id),
  );
}

// Replace this judge's entry on the result (one entry per judge) or append it.
export function upsertJudgeScore(
  result: RawResult,
  set: JudgeScoreSet,
): void {
  const list = result.judgeScores ?? [];
  const filtered = list.filter((s) => s.judgeId !== set.judgeId);
  filtered.push(set);
  result.judgeScores = filtered;
}

// Record a judge failure without destroying a verdict an earlier run produced.
// Returns true if the error entry was written, false if a prior successful entry
// for this judge was kept intact (so a transient CLI failure cannot erase a good
// score). Callers should skip the file write when this returns false.
export function recordJudgeError(result: RawResult, set: JudgeScoreSet): boolean {
  if (!mayRecordJudgeError(result.judgeScores ?? [], set.judgeId)) return false;
  upsertJudgeScore(result, set);
  return true;
}

// Replace a stability entry with the same logical key, or append it.
export function upsertStability(file: RawResultsFile, entry: StabilityEntry): void {
  const list = file.stability ?? [];
  const filtered = list.filter(
    (s) =>
      !(
        s.taskId === entry.taskId &&
        s.modelId === entry.modelId &&
        s.environmentId === entry.environmentId
      ),
  );
  filtered.push(entry);
  file.stability = filtered;
}

// Validate against the full schema, back up the current file, then write.
export async function saveResultsAtomic(
  benchmarkDir: string,
  file: RawResultsFile,
): Promise<void> {
  ResultsFileSchema.parse(file);
  const path = join(benchmarkDir, "data/results.json");
  try {
    await copyFile(path, `${path}.bak`);
  } catch {
    // No existing file to back up (first write) - ignore.
  }
  await writeFile(path, `${JSON.stringify(file, null, 2)}\n`);
}
