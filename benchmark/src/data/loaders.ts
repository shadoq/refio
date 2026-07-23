import { TasksFileSchema, type TasksFile, type Criterion } from "@/schema/tasks";
import { ResultsFileSchema, type ResultsFile } from "@/schema/results";

export async function fetchTasks(): Promise<TasksFile> {
  const res = await fetch("/data/tasks.json");
  if (!res.ok) throw new Error(`Failed to fetch tasks.json: ${res.status}`);
  return TasksFileSchema.parse(await res.json());
}

export async function fetchResults(): Promise<ResultsFile> {
  const res = await fetch("/data/results.json");
  if (!res.ok) throw new Error(`Failed to fetch results.json: ${res.status}`);
  return ResultsFileSchema.parse(await res.json());
}

export function validateReferentialIntegrity(
  tasks: TasksFile,
  results: ResultsFile,
): string[] {
  const errors: string[] = [];

  // Duplicate ID checks
  const taskIds = tasks.tasks.map((t) => t.id);
  const dupTaskIds = taskIds.filter((id, i) => taskIds.indexOf(id) !== i);
  dupTaskIds.forEach((id) => errors.push(`duplicate taskId "${id}" in tasks.json`));

  const resultIds = results.results.map((r) => r.id);
  const dupResultIds = resultIds.filter((id, i) => resultIds.indexOf(id) !== i);
  dupResultIds.forEach((id) => errors.push(`duplicate resultId "${id}" in results.json`));

  const taskById = new Map(tasks.tasks.map((t) => [t.id, t]));
  const modelIds = new Set(results.models.map((m) => m.id));
  const envIds = new Set(results.environments.map((e) => e.id));
  const coreCriteriaById = new Map(tasks.coreCriteria.map((c) => [c.id, c]));

  for (const r of results.results) {
    const task = taskById.get(r.taskId);
    if (!task) {
      // Skip further checks for this result — criteria can't be validated without a task
      errors.push(`result ${r.id}: unknown taskId "${r.taskId}"`);
      continue;
    }
    if (!modelIds.has(r.modelId)) {
      errors.push(`result ${r.id}: unknown modelId "${r.modelId}"`);
    }
    if (!envIds.has(r.environmentId)) {
      errors.push(`result ${r.id}: unknown environmentId "${r.environmentId}"`);
    }

    const allCriteria = new Map<string, Criterion>([
      ...coreCriteriaById,
      ...task.extraCriteria.map((c): [string, Criterion] => [c.id, c]),
    ]);

    for (const s of r.scores) {
      const criterion = allCriteria.get(s.criterionId);
      if (!criterion) {
        errors.push(`result ${r.id}: unknown criterionId "${s.criterionId}"`);
      } else if (!criterion.scale.values.includes(s.value)) {
        errors.push(
          `result ${r.id}: score value ${s.value} for "${s.criterionId}" is not in scale [${criterion.scale.values.join(", ")}]`,
        );
      }
    }

    // Judge scores may use judgeCriteria in addition to human criteria.
    const judgeCriteria = new Map<string, Criterion>([
      ...allCriteria,
      ...tasks.judgeCriteria.map((c): [string, Criterion] => [c.id, c]),
    ]);
    const seenJudgeIds = new Set<string>();
    for (const set of r.judgeScores) {
      if (seenJudgeIds.has(set.judgeId)) {
        errors.push(`result ${r.id}: duplicate judgeId "${set.judgeId}" in judgeScores`);
      }
      seenJudgeIds.add(set.judgeId);
      if (set.error != null && set.scores.length > 0) {
        errors.push(
          `result ${r.id}: judge "${set.judgeId}" has both an error and scores`,
        );
      }
      for (const s of set.scores) {
        const criterion = judgeCriteria.get(s.criterionId);
        if (!criterion) {
          errors.push(
            `result ${r.id}: judge "${set.judgeId}" unknown criterionId "${s.criterionId}"`,
          );
        } else if (!criterion.scale.values.includes(s.value)) {
          errors.push(
            `result ${r.id}: judge "${set.judgeId}" value ${s.value} for "${s.criterionId}" is not in scale [${criterion.scale.values.join(", ")}]`,
          );
        }
      }
    }
  }

  const knownResultIds = new Set(resultIds);
  const seenStabilityKeys = new Set<string>();
  for (const s of results.stability) {
    const key = `${s.taskId}|${s.modelId}|${s.environmentId}`;
    if (seenStabilityKeys.has(key)) {
      errors.push(`stability: duplicate entry for (${key})`);
    }
    seenStabilityKeys.add(key);
    for (const rid of s.resultIds) {
      if (!knownResultIds.has(rid)) {
        errors.push(`stability (${key}): unknown resultId "${rid}"`);
      }
    }
  }
  return errors;
}
