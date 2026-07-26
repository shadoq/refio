// Upsert every case's review task into data/tasks.json. Works on the raw parsed
// JSON (not a schema-defaulted copy) so untouched tasks and criteria keep their
// exact on-disk shape; validates the merged file before writing.
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { TasksFileSchema } from "../../../src/schema/tasks";
import { caseToTaskCore, upsertTaskDated } from "../../../src/lib/catalog/emit";
import type { CatalogCase } from "../../../src/schema/catalog";
import type { Task } from "../../../src/schema/tasks";
import { writeOrCheck, type WriteResult } from "./io";

export async function emitTasks(opts: {
  benchmarkDir: string;
  loaded: Array<{ case: CatalogCase; promptText: string }>;
  now: string;
  check?: boolean;
  dryRun?: boolean;
}): Promise<WriteResult> {
  const path = join(opts.benchmarkDir, "data", "tasks.json");
  const raw = JSON.parse(await readFile(path, "utf8")) as {
    tasks: Task[];
    [k: string]: unknown;
  };

  let tasks = raw.tasks;
  for (const l of opts.loaded) {
    // systemPrompt is a string, so trim the trailing newline of the prompt file.
    tasks = upsertTaskDated(tasks, caseToTaskCore(l.case, l.promptText.trimEnd()), opts.now);
  }
  raw.tasks = tasks;

  TasksFileSchema.parse(raw); // validate the merged file; throws on drift
  const content = JSON.stringify(raw, null, 2) + "\n";
  return writeOrCheck(path, content, opts);
}
