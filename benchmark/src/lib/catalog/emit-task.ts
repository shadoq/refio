// Pure transforms from a catalog case to a benchmark review task. The mirror-image
// transform (case -> e2e scenario) and the case schema itself live in tools/e2e, which
// ships with main; this module extends it with the benchmark-only half.
import type { CatalogCase } from "@e2e/schema/case";
import type { Task } from "../../schema/tasks";

// A task's content without timestamps - what the generator derives from a case.
export type TaskCore = Omit<Task, "createdAt" | "updatedAt">;

// Build the task content from a case. The prompt body becomes systemPrompt verbatim.
export function caseToTaskCore(c: CatalogCase, promptText: string): TaskCore {
  return {
    id: c.id,
    name: c.title,
    description: c.review.description,
    systemPrompt: promptText,
    extraCriteria: c.review.extraCriteria,
  };
}

// Convenience: task content plus explicit timestamps.
export function caseToTask(
  c: CatalogCase,
  promptText: string,
  createdAt: string,
  updatedAt: string,
): Task {
  return { ...caseToTaskCore(c, promptText), createdAt, updatedAt };
}

// Do two task contents match, ignoring timestamps?
export function taskContentEqual(a: TaskCore, b: TaskCore): boolean {
  return (
    a.id === b.id &&
    a.name === b.name &&
    a.description === b.description &&
    a.systemPrompt === b.systemPrompt &&
    JSON.stringify(a.extraCriteria) === JSON.stringify(b.extraCriteria)
  );
}

// Insert or update a task from its content, applying timestamps idempotently so
// re-running the generator on an unchanged case produces no diff:
// - new id: createdAt = updatedAt = now
// - existing id, content changed: keep createdAt, bump updatedAt to now
// - existing id, content unchanged: return the input array untouched (same refs)
export function upsertTaskDated(tasks: Task[], core: TaskCore, now: string): Task[] {
  const idx = tasks.findIndex((t) => t.id === core.id);
  if (idx < 0) return [...tasks, { ...core, createdAt: now, updatedAt: now }];
  const existing = tasks[idx];
  if (taskContentEqual(existing, core)) return tasks;
  const next = [...tasks];
  next[idx] = { ...core, createdAt: existing.createdAt, updatedAt: now };
  return next;
}
