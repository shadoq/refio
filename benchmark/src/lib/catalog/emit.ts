// Pure transforms from a catalog case to the two generated artifacts: a headless
// e2e scenario object and an admin review Task. No filesystem access, so both the
// vitest suite (via @ alias) and the tsx generator (relative import) can use it.
// Type-only imports are erased at runtime, so relative paths work under plain tsx.
import type { CatalogCase } from "../../schema/catalog";
import type { Task } from "../../schema/tasks";

// The deliverable filename in prompts uses the corpus template `name_{{MODEL_ID}}_01.html`.
// {{MODEL_ID}} is a placeholder resolved per model. The e2e harness cannot substitute
// it (only {{FIXTURE_SERVER}}/{{PORT}}), so generated e2e artifacts resolve it to a
// fixed concrete token; import-runs resolves it to the real model id at run time.
export const E2E_MODEL_TOKEN = "model";

export function resolveModelTemplate(text: string, token: string): string {
  return text.split("{{MODEL_ID}}").join(token);
}

export interface ScenarioNeedle {
  path: string;
  regex?: string;
  text?: string;
}

export interface ScenarioAssert {
  tool_order?: string[];
  needles_in_file?: ScenarioNeedle[];
  needle_in_output?: { regex: string };
  file_unchanged?: string[];
  smoke?: { entry: string; dom_present: string[] };
  build_cmd?: string;
  no_context_overflow: boolean;
}

export interface E2eScenario {
  id: string;
  mode: string;
  description: string;
  category: string;
  max_iterations: number;
  fixture: string;
  prompt_file: string;
  assert: ScenarioAssert;
  judge: { criteria: string[] };
}

// Build the e2e scenario object. Optional assert keys are omitted (not set to
// null/empty) so generated JSON reads like the hand-written scenarios.
export function caseToScenario(c: CatalogCase): E2eScenario {
  const assert: ScenarioAssert = { no_context_overflow: c.assert.noContextOverflow };

  if (c.assert.toolOrder.length > 0) {
    assert.tool_order = [...c.assert.toolOrder];
  }
  if (c.deliverable && c.assert.needles.length > 0) {
    const path = resolveModelTemplate(c.deliverable, E2E_MODEL_TOKEN);
    assert.needles_in_file = c.assert.needles.map((n) =>
      n.regex !== undefined ? { path, regex: n.regex } : { path, text: n.text as string },
    );
  }
  if (c.assert.needleInOutput) {
    assert.needle_in_output = { regex: c.assert.needleInOutput.regex };
  }
  if (c.assert.fileUnchanged.length > 0) {
    assert.file_unchanged = [...c.assert.fileUnchanged];
  }
  if (c.assert.smoke) {
    assert.smoke = {
      entry: resolveModelTemplate(c.assert.smoke.entry, E2E_MODEL_TOKEN),
      dom_present: [...c.assert.smoke.domPresent],
    };
  }
  if (c.assert.buildCmd) {
    assert.build_cmd = c.assert.buildCmd;
  }

  return {
    id: c.id,
    mode: c.mode,
    description: c.review.description,
    category: c.category,
    max_iterations: c.maxIterations,
    fixture: c.fixture,
    prompt_file: `prompts/${c.id}.md`,
    assert,
    judge: { criteria: [...c.judge.criteria] },
  };
}

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
