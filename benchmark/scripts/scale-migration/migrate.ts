// One-off move of the benchmark data from the old 0 / 0.5 / 1 rating scale (and the
// 0..2 "look" scale) to a single 0-6 scale. Pure: run.ts does the file IO.
//
// The old top mark meant "covered / works / clean" without saying whether the work
// was good or exceptional, so it becomes 4 (good) and 5-6 stay free for work judged
// on the new scale. The one exception is the old "excellent" look (2), whose label
// already said "better than expected" - the meaning of 6.
import { aggregateJudgeScores, scoreVariance } from "../../src/lib/judge/scoring";

export const NEW_SCALE = [0, 1, 2, 3, 4, 5, 6];

export const NEW_LABELS: Record<string, string> = {
  "0": "missing",
  "1": "fragment",
  "2": "partial",
  "3": "flawed",
  "4": "good",
  "5": "very good",
  "6": "exceptional",
};

// What each level means for the shared criteria. Task extra criteria keep their own
// description; the judge prompt carries the generic meaning of every level.
export const NEW_DESCRIPTIONS: Record<string, string> = {
  compliance:
    "Are the requirements from the prompt covered in the produced artifact? 0 = none of it, 2 = important requirements missing, 3 = the essentials are there but some requirements are missing or only partly done, 4 = everything required with small gaps, 5 = everything required, done well, 6 = everything required and more, done with evident care.",
  works_out_of_box:
    "Does it run and behave as expected without manual fixes? 0 = doesn't run, 1 = runs but is unusable, 2 = serious bugs that block a main feature, 3 = main features work with noticeable bugs, 4 = works, minor bugs only, 5 = works, no bugs found, 6 = works flawlessly, including edge cases and unusual input.",
  look:
    "Visual quality. 0 = broken, 1 = barely styled, 2 = clear visual defects, 3 = acceptable, 4 = good, 5 = polished, 6 = better than expected, a design someone would ship.",
  code:
    "Subjective code quality. 0 = mess / unfinished, 2 = hard to follow, 3 = acceptable, 4 = clean, 5 = clean and well organised, 6 = exemplary.",
  agent_logic:
    "Does the model follow the agent workflow (check files, edit, verify, summarize)? 0 = ignored, 2 = partly, with many wasted or wrong steps, 3 = followed with some wasted steps, 4 = followed, 5 = followed efficiently, 6 = followed efficiently and verified its own work.",
  code_structure:
    "Structure, naming, duplication, dead code. 0 = mess, 2 = poor, 3 = acceptable, 4 = clean, 5 = clean and well organised, 6 = exemplary.",
  logic_correctness:
    "Correctness of the logic judged from reading the code, not only from the screen. 0 = broken logic, 2 = major flaws, 3 = the main path works with noticeable flaws, 4 = correct, minor flaws, 5 = correct, no flaws found, 6 = correct and robust, edge cases handled.",
};

interface RawCriterion {
  id: string;
  description: string;
  scale: { values: number[]; labels?: Record<string, string> };
  [key: string]: unknown;
}

interface RawTask {
  id: string;
  extraCriteria?: RawCriterion[];
  [key: string]: unknown;
}

export interface RawTasks {
  coreCriteria: RawCriterion[];
  judgeCriteria?: RawCriterion[];
  tasks: RawTask[];
  [key: string]: unknown;
}

interface RawScore {
  criterionId: string;
  value: number;
  [key: string]: unknown;
}

interface RawJudgeSet {
  error?: string | null;
  scores?: RawScore[];
  [key: string]: unknown;
}

interface RawRun {
  id: string;
  taskId: string;
  scores?: RawScore[];
  judgeScores?: RawJudgeSet[];
  autoVerdict?: { verdict: string; reasons: string[] };
  [key: string]: unknown;
}

interface RawStability {
  resultIds: string[];
  deterministic: { scoreVariance: number; codeSimilarity: number };
  [key: string]: unknown;
}

export interface RawResults {
  results: RawRun[];
  inbox?: RawRun[];
  stability?: RawStability[];
  [key: string]: unknown;
}

// Map one old value onto the 0-6 scale. Throws on anything that is not a value of
// the old scale, so a half-migrated or hand-edited file stops the run instead of
// being silently multiplied twice.
export function mapValue(value: number, oldScale: number[]): number {
  if (!oldScale.includes(value)) {
    throw new Error(`value ${value} is not on the old scale [${oldScale.join(", ")}]`);
  }
  const max = Math.max(...oldScale);
  if (max === 1) return value * 4;
  if (max === 2) return value === 2 ? 6 : value * 2;
  throw new Error(`unexpected old scale [${oldScale.join(", ")}]`);
}

function allCriteria(tasks: RawTasks): RawCriterion[] {
  return [
    ...tasks.coreCriteria,
    ...(tasks.judgeCriteria ?? []),
    ...tasks.tasks.flatMap((t) => t.extraCriteria ?? []),
  ];
}

export function isMigrated(tasks: RawTasks): boolean {
  return allCriteria(tasks).some((c) => Math.max(...c.scale.values) === 6);
}

// Old scale of every criterion a run of `taskId` can carry: core, judge-only and the
// task's own extra criteria.
function oldScalesFor(tasks: RawTasks, taskId: string): Map<string, number[]> {
  const task = tasks.tasks.find((t) => t.id === taskId);
  const list = [
    ...tasks.coreCriteria,
    ...(tasks.judgeCriteria ?? []),
    ...(task?.extraCriteria ?? []),
  ];
  return new Map(list.map((c) => [c.id, c.scale.values]));
}

export function migrateTasks(tasks: RawTasks): RawTasks {
  if (isMigrated(tasks)) throw new Error("tasks.json is already on the 0-6 scale");
  const next = structuredClone(tasks);
  for (const c of allCriteria(next)) {
    c.scale = { values: [...NEW_SCALE], labels: { ...NEW_LABELS } };
    if (NEW_DESCRIPTIONS[c.id]) c.description = NEW_DESCRIPTIONS[c.id];
  }
  return next;
}

function migrateScores(scores: RawScore[], scales: Map<string, number[]>, where: string): void {
  for (const s of scores) {
    const scale = scales.get(s.criterionId);
    if (!scale) throw new Error(`${where}: unknown criterion "${s.criterionId}"`);
    s.value = mapValue(s.value, scale);
  }
}

// "compliance=1" -> "compliance=4"; "compliance=not measured" stays as it is.
function migrateReason(reason: string, scales: Map<string, number[]>): string {
  const m = /^([a-z0-9_-]+)=(\d+(?:\.\d+)?)$/.exec(reason);
  if (!m) return reason;
  const scale = scales.get(m[1]);
  return scale ? `${m[1]}=${mapValue(Number(m[2]), scale)}` : reason;
}

function migrateRun(run: RawRun, oldTasks: RawTasks): void {
  const scales = oldScalesFor(oldTasks, run.taskId);
  migrateScores(run.scores ?? [], scales, run.id);
  for (const set of run.judgeScores ?? []) migrateScores(set.scores ?? [], scales, run.id);
  if (run.autoVerdict) {
    run.autoVerdict.reasons = run.autoVerdict.reasons.map((r) => migrateReason(r, scales));
  }
}

// Score variance is a mean absolute deviation of raw judge values, so it is
// recomputed from the migrated values exactly as the stability runner would. A group
// it cannot be recomputed for keeps its old value scaled like a 0-1 criterion.
function recomputeStability(results: RawRun[], stability: RawStability[]): number {
  const byId = new Map(results.map((r) => [r.id, r]));
  let approximated = 0;
  for (const entry of stability) {
    const runs = entry.resultIds.map((id) => byId.get(id));
    const perAttempt = runs.map((r) =>
      aggregateJudgeScores(
        (r?.judgeScores ?? []).map((s) => ({ error: s.error ?? null, scores: s.scores ?? [] })),
      ),
    );
    if (runs.some((r) => !r) || perAttempt.some((a) => Object.keys(a).length === 0)) {
      entry.deterministic.scoreVariance *= 4;
      approximated++;
      continue;
    }
    entry.deterministic.scoreVariance = scoreVariance(perAttempt);
  }
  return approximated;
}

export function migrateResults(
  results: RawResults,
  oldTasks: RawTasks,
): { file: RawResults; stabilityApproximated: number } {
  const next = structuredClone(results);
  for (const run of next.results) migrateRun(run, oldTasks);
  for (const run of next.inbox ?? []) migrateRun(run, oldTasks);
  const stabilityApproximated = recomputeStability(next.results, next.stability ?? []);
  return { file: next, stabilityApproximated };
}
