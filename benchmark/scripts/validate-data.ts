import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { TasksFileSchema } from "../src/schema/tasks";
import { ResultsFileSchema } from "../src/schema/results";
import { validateReferentialIntegrity } from "../src/data/loaders";

async function main() {
  const tasksRaw = JSON.parse(await readFile(join(process.cwd(), "data/tasks.json"), "utf8"));
  const resultsRaw = JSON.parse(await readFile(join(process.cwd(), "data/results.json"), "utf8"));

  const tasks = TasksFileSchema.parse(tasksRaw);
  const results = ResultsFileSchema.parse(resultsRaw);

  const errors = validateReferentialIntegrity(tasks, results);
  if (errors.length > 0) {
    console.error("Referential integrity errors:");
    errors.forEach((e) => console.error(" -", e));
    process.exit(1);
  }
  console.log("✔ data files OK");
  console.log(`  ${tasks.tasks.length} tasks, ${results.results.length} results`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
