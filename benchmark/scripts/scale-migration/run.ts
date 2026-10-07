// Usage: npx tsx scripts/scale-migration/run.ts
// Moves data/tasks.json and data/results.json to the 0-6 scale in one go and refuses
// to run twice. Back both files up first: data/results.json.bak belongs to the judge
// runner and is not written here.
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { TasksFileSchema } from "../../src/schema/tasks";
import { ResultsFileSchema } from "../../src/schema/results";
import { migrateResults, migrateTasks, type RawResults, type RawTasks } from "./migrate";

const dataDir = join(import.meta.dirname, "..", "..", "data");
const tasksPath = join(dataDir, "tasks.json");
const resultsPath = join(dataDir, "results.json");

const tasks = JSON.parse(await readFile(tasksPath, "utf8")) as RawTasks;
const results = JSON.parse(await readFile(resultsPath, "utf8")) as RawResults;

const nextTasks = migrateTasks(tasks);
const { file: nextResults, stabilityApproximated } = migrateResults(results, tasks);

// Validate both before writing either, so a failure leaves the data untouched.
TasksFileSchema.parse(nextTasks);
ResultsFileSchema.parse(nextResults);

await writeFile(tasksPath, `${JSON.stringify(nextTasks, null, 2)}\n`);
await writeFile(resultsPath, `${JSON.stringify(nextResults, null, 2)}\n`);

console.log(
  `migrated: ${nextResults.results.length} results, ${nextResults.inbox?.length ?? 0} inbox entries, ` +
    `${nextResults.stability?.length ?? 0} stability groups (${stabilityApproximated} approximated)`,
);
