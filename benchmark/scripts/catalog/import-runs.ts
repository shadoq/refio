// Import headless runs into the review inbox. For each run it copies the artifact,
// renders a screenshot, computes the deterministic judge (compliance/works/agent_logic)
// and appends a schema-valid inbox[] entry to data/results.json - a row awaiting a
// human's look/code scores. It never writes results[] or manual scores.
//
// Two sources of a run:
//   --from-run <run.json> [--artifact <file>]   use existing artifacts (no model call)
//   (default) --run                             invoke the headless CLI (spends tokens!)
//
// usage:
//   tsx import-runs.ts (--all | <id>...) --model <m> [--env <id>] [--attempts N]
//                      [--from-run <run.json> --artifact <file>] [--no-render] [--dry-run]
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { readFile, copyFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { loadCases, type LoadedCase } from "../../../tools/e2e/lib/case";
import { runHeadless } from "./lib/run-cli";
import { captureShots } from "../judge/lib/render";
import { buildDeterministicJudge } from "../../src/lib/catalog/deterministic";
import { resolveModelTemplate } from "../../../tools/e2e/src/emit-scenario";
import {
  parseRunJson,
  makeInboxId,
  sanitizeModelId,
  deterministicVerdict,
  buildInboxEntry,
} from "../../src/lib/catalog/inbox";
import { ensureModel, ensureEnvironment, upsertInbox } from "../../src/lib/catalog/inbox-store";
import { saveResultsAtomic } from "../judge/lib/store";
import { InboxEntrySchema, type Attachment, type InboxEntry } from "../../src/schema/results";

interface Args {
  ids: string[];
  all: boolean;
  model: string;
  env: string;
  attempts: number;
  fromRun?: string;
  artifact?: string;
  noRender: boolean;
  dryRun: boolean;
  maxCost?: number;
}

function parseArgs(argv: string[]): Args {
  const a: Args = { ids: [], all: false, model: "", env: "local", attempts: 1, noRender: false, dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const t = argv[i];
    if (t === "--all") a.all = true;
    else if (t === "--no-render") a.noRender = true;
    else if (t === "--dry-run") a.dryRun = true;
    else if (t === "--model") a.model = argv[++i];
    else if (t === "--env") a.env = argv[++i];
    else if (t === "--attempts") a.attempts = Number(argv[++i]);
    else if (t === "--from-run") a.fromRun = argv[++i];
    else if (t === "--artifact") a.artifact = argv[++i];
    else if (t === "--max-cost") a.maxCost = Number(argv[++i]);
    else if (t.startsWith("--")) throw new Error(`unknown flag: ${t}`);
    else a.ids.push(t);
  }
  return a;
}

interface Paths {
  repoRoot: string;
  benchmarkDir: string;
  e2eDir: string;
  dataDir: string;
}

async function buildEntry(
  loaded: LoadedCase,
  model: string,
  env: string,
  attempt: number,
  runJson: unknown,
  deliverablePath: string | null,
  paths: Paths,
  noRender: boolean,
  persist: boolean,
): Promise<InboxEntry> {
  const c = loaded.case;
  const now = new Date().toISOString();
  const run = parseRunJson(runJson);
  const inboxId = makeInboxId(c.id, model, attempt);

  const attachments: Attachment[] = [];
  let deliverableText: string | null = null;
  let rendered: boolean | null = null;
  let consoleErrors: string[] = [];
  const screenshots: string[] = [];

  if (deliverablePath && existsSync(deliverablePath)) {
    deliverableText = await readFile(deliverablePath, "utf8");
    attachments.push({ type: "html", src: `attachments/${inboxId}/artifact.html` });

    // Copying the artifact and rendering are side effects, so a dry-run skips
    // them - it still reads the deliverable text to compute compliance.
    if (persist) {
      const destDir = join(paths.dataDir, "attachments", inboxId);
      await mkdir(destDir, { recursive: true });
      const artifactDest = join(destDir, "artifact.html");
      await copyFile(deliverablePath, artifactDest);

      if (!noRender) {
        const judgeDir = join(destDir, "_judge");
        await mkdir(judgeDir, { recursive: true });
        const shot = join(judgeDir, "shot-full.png");
        const outcome = await captureShots(artifactDest, { motionPaths: [], fullPagePath: shot });
        rendered = outcome.renderError === null;
        consoleErrors = outcome.consoleErrors;
        if (existsSync(shot)) {
          const rel = `attachments/${inboxId}/_judge/shot-full.png`;
          screenshots.push(rel);
          attachments.push({ type: "image", src: rel });
        }
      }
    }
  }

  const judge = buildDeterministicJudge({
    mode: c.mode,
    deliverableText,
    finalOutput: run.finalOutput,
    needles: c.assert.needles,
    needleInOutput: c.assert.needleInOutput,
    toolCalls: run.toolCalls,
    expectedToolOrder: c.assert.toolOrder,
    status: run.status,
    rendered,
    consoleErrors,
    judgedAt: now,
    screenshots,
  });

  return buildInboxEntry({
    caseId: c.id,
    mode: c.mode,
    modelId: model,
    environmentId: env,
    attemptNumber: attempt,
    run,
    judge,
    attachments,
    autoVerdict: deterministicVerdict(judge.scores),
    now,
  });
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  if (!args.model) {
    console.error("import-runs: --model <provider/model> is required");
    process.exit(2);
  }
  if (!args.all && args.ids.length === 0) {
    console.error("usage: import-runs (--all | <id>...) --model <m> [--from-run <run.json> --artifact <f>] [--no-render] [--dry-run]");
    process.exit(2);
  }

  const scriptDir = dirname(fileURLToPath(import.meta.url)); // scripts/catalog
  const benchmarkDir = join(scriptDir, "..", "..");
  const paths: Paths = {
    repoRoot: join(benchmarkDir, ".."),
    benchmarkDir,
    e2eDir: join(benchmarkDir, "..", "test_data", "e2e"),
    dataDir: join(benchmarkDir, "data"),
  };

  const loaded = await loadCases(
    join(paths.repoRoot, "test_data", "e2e_catalog"),
    args.all ? undefined : args.ids,
  );
  if (loaded.length === 0) {
    console.error("no cases matched");
    process.exit(1);
  }
  if (args.fromRun && loaded.length !== 1) {
    console.error("--from-run expects exactly one case id");
    process.exit(2);
  }

  const resultsPath = join(paths.dataDir, "results.json");
  const dryRunEntries: InboxEntry[] = [];
  let written = 0;

  for (const l of loaded) {
    const attempts = args.fromRun ? 1 : Math.max(1, args.attempts);
    for (let attempt = 1; attempt <= attempts; attempt++) {
      let runJson: unknown;
      let deliverablePath: string | null;
      if (args.fromRun) {
        runJson = JSON.parse(await readFile(args.fromRun, "utf8"));
        deliverablePath = args.artifact ?? null;
      } else {
        // Resolve {{MODEL_ID}} to the real model for both the prompt and the
        // expected deliverable filename, using the canonical catalog prompt.
        const token = sanitizeModelId(args.model);
        const res = await runHeadless({
          repoRoot: paths.repoRoot,
          fixtureDir: join(paths.e2eDir, l.case.fixture),
          promptText: resolveModelTemplate(l.promptText, token),
          mode: l.case.mode,
          model: args.model,
          deliverable: l.case.deliverable ? resolveModelTemplate(l.case.deliverable, token) : null,
          maxCost: args.maxCost,
        });
        runJson = res.runJson;
        deliverablePath = res.deliverablePath;
      }
      const entry = await buildEntry(l, args.model, args.env, attempt, runJson, deliverablePath, paths, args.noRender, !args.dryRun);

      const parsed = InboxEntrySchema.safeParse(entry);
      if (!parsed.success) {
        console.error(`invalid inbox entry ${entry.id}:`, parsed.error.issues[0]?.message);
        process.exit(1);
      }
      console.error(`  built inbox entry: ${entry.id} (verdict ${entry.autoVerdict?.verdict})`);

      if (args.dryRun) {
        dryRunEntries.push(entry);
        continue;
      }

      // Persist each attempt as soon as it is built, not once at the end: a crash or
      // timeout mid-run then keeps every finished attempt, and re-running only redoes
      // the missing ones (upsertInbox is keyed by id, so re-writing an attempt is
      // idempotent). Re-reading the file each time also folds in any concurrent change.
      const file = JSON.parse(await readFile(resultsPath, "utf8"));
      ensureModel(file, entry.modelId);
      ensureEnvironment(file, entry.environmentId);
      upsertInbox(file, entry);
      await saveResultsAtomic(benchmarkDir, file);
      written++;
    }
  }

  if (args.dryRun) {
    console.log(JSON.stringify(dryRunEntries, null, 2));
    console.error(`\ndry-run: ${dryRunEntries.length} entry(ies) built and validated, nothing written.`);
    return;
  }
  console.error(`\nwrote ${written} inbox entry(ies) to ${resultsPath}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
