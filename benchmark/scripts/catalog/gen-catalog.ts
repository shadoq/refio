// Generator entry point. Reads catalog cases and emits, per case, an e2e scenario
// (+ prompt copy + fixture stub) and an upserted admin review task. Idempotent:
// re-running an unchanged case produces no diff. `--check` reports drift for CI.
//
// usage: tsx scripts/catalog/gen-catalog.ts (--all | <id>...) [--check] [--dry-run]
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { loadCases } from "./lib/case";
import { emitE2e } from "./lib/emit-e2e";
import { emitTasks } from "./lib/emit-task";
import type { WriteResult } from "./lib/io";

interface Args {
  ids: string[];
  all: boolean;
  check: boolean;
  dryRun: boolean;
}

function parseArgs(argv: string[]): Args {
  const args: Args = { ids: [], all: false, check: false, dryRun: false };
  for (const a of argv) {
    if (a === "--all") args.all = true;
    else if (a === "--check") args.check = true;
    else if (a === "--dry-run") args.dryRun = true;
    else if (a.startsWith("--")) throw new Error(`unknown flag: ${a}`);
    else args.ids.push(a);
  }
  return args;
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  if (!args.all && args.ids.length === 0) {
    console.error("usage: gen-catalog (--all | <id>...) [--check] [--dry-run]");
    process.exit(2);
  }

  const scriptDir = dirname(fileURLToPath(import.meta.url)); // scripts/catalog
  const benchmarkDir = join(scriptDir, "..", ".."); // benchmark/
  const catalogDir = join(benchmarkDir, "catalog");
  const e2eDir = join(benchmarkDir, "..", "test_data", "e2e");
  const now = new Date().toISOString();

  const loaded = await loadCases(catalogDir, args.all ? undefined : args.ids);
  if (loaded.length === 0) {
    console.error(`no cases found under ${catalogDir}`);
    process.exit(1);
  }

  const results: WriteResult[] = [];
  for (const l of loaded) {
    results.push(...(await emitE2e({ e2eDir, loaded: l, check: args.check, dryRun: args.dryRun })));
  }
  results.push(await emitTasks({ benchmarkDir, loaded, now, check: args.check, dryRun: args.dryRun }));

  const changed = results.filter((r) => r.changed);
  for (const r of results) {
    const tag = !r.changed ? "ok" : args.check ? "DRIFT" : args.dryRun ? "would-write" : "wrote";
    console.error(`  ${tag}: ${r.path}`);
  }
  const verb = args.check ? "drifted" : args.dryRun ? "to write" : "written";
  console.error(`\n${loaded.length} case(s); ${changed.length} file(s) ${verb}.`);

  if (args.check && changed.length > 0) {
    console.error("catalog is out of sync - run `npm run gen-catalog -- --all` and commit.");
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
