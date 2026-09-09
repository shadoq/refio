// Run one of the measured tasks from data/tasks.json through a harness and land the
// result in the review queue.
//
// Why this exists next to import-runs.ts: import-runs drives the e2e case catalog
// (test_data/e2e_catalog), which covers 13 of the 18 tasks. The five tasks the whole
// data set is actually measured on - snake, todo-app, neuron_growth_simulation,
// website-museum-night, demoscene-effect-gouraud-shaded-cube - were run by an older
// script whose prompt templates are gone from disk. Their prompts survived in
// tasks.json, so this reads them from there. A task with no catalog case has no
// needles and no fixture, so the deterministic judge scores what it can (render,
// status) and leaves compliance to the human and the strong judges.
//
// Executing this spends tokens / local GPU time and writes into a throwaway dir.
//
// usage:
//   tsx scripts/catalog/run-task.ts <taskId> --model <m> [--env <id>] [--harness <id>]
//        [--harness-model <m>] [--attempts N] [--start-attempt N] [--no-render] [--dry-run]
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { readFile, readdir, copyFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { runHeadless } from "./lib/run-cli";
import { runAgent, AGENT_HARNESS_IDS } from "./lib/run-agent";
import { captureShots } from "../judge/lib/render";
import { buildDeterministicJudge } from "../../src/lib/catalog/deterministic";
import { pickDeliverable } from "../../src/lib/catalog/deliverable";
import {
  parseRunJson,
  makeInboxId,
  sanitizeModelId,
  deterministicVerdict,
  buildInboxEntry,
} from "../../src/lib/catalog/inbox";
import {
  ensureModel,
  ensureEnvironment,
  ensureHarness,
  upsertInbox,
} from "../../src/lib/catalog/inbox-store";
import { saveResultsAtomic } from "../judge/lib/store";
import { InboxEntrySchema, type Attachment } from "../../src/schema/results";

const AGENT_TIMEOUT_MS = 30 * 60 * 1000;

interface Args {
  taskId: string;
  model: string;
  env: string;
  harness: string;
  harnessModel?: string;
  attempts: number;
  startAttempt: number;
  noRender: boolean;
  dryRun: boolean;
  maxCost?: number;
}

function parseArgs(argv: string[]): Args {
  const a: Args = {
    taskId: "",
    model: "",
    env: "dgx-local",
    harness: "refio",
    attempts: 1,
    startAttempt: 1,
    noRender: false,
    dryRun: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const t = argv[i];
    if (t === "--no-render") a.noRender = true;
    else if (t === "--dry-run") a.dryRun = true;
    else if (t === "--model") a.model = argv[++i];
    else if (t === "--env") a.env = argv[++i];
    else if (t === "--harness") a.harness = argv[++i];
    else if (t === "--harness-model") a.harnessModel = argv[++i];
    else if (t === "--attempts") a.attempts = Number(argv[++i]);
    else if (t === "--start-attempt") a.startAttempt = Number(argv[++i]);
    else if (t === "--max-cost") a.maxCost = Number(argv[++i]);
    else if (t.startsWith("--")) throw new Error(`unknown flag: ${t}`);
    else if (a.taskId === "") a.taskId = t;
    else throw new Error(`unexpected argument: ${t}`);
  }
  return a;
}

// The token substituted into {{MODEL_ID}}. It carries the harness for an external
// agent so two harnesses running the same model do not write the same filename.
function modelToken(model: string, harness: string): string {
  return sanitizeModelId(harness === "refio" ? model : `${harness}-${model}`);
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  if (!args.taskId || !args.model) {
    console.error(
      "usage: run-task <taskId> --model <provider/model> [--env <id>] [--harness <id>]",
    );
    process.exit(2);
  }
  if (args.harness !== "refio" && !AGENT_HARNESS_IDS.includes(args.harness)) {
    console.error(
      `run-task: unknown --harness ${args.harness} (known: refio, ${AGENT_HARNESS_IDS.join(", ")})`,
    );
    process.exit(2);
  }

  const scriptDir = dirname(fileURLToPath(import.meta.url)); // scripts/catalog
  const benchmarkDir = join(scriptDir, "..", "..");
  const repoRoot = join(benchmarkDir, "..");
  const dataDir = join(benchmarkDir, "data");
  const resultsPath = join(dataDir, "results.json");

  const tasks = JSON.parse(await readFile(join(dataDir, "tasks.json"), "utf8"));
  const task = (tasks.tasks as Array<{ id: string; systemPrompt: string }>).find(
    (t) => t.id === args.taskId,
  );
  if (!task) {
    console.error(`run-task: no task ${args.taskId} in data/tasks.json`);
    process.exit(1);
  }

  const token = modelToken(args.model, args.harness);
  const promptText = task.systemPrompt.split("{{MODEL_ID}}").join(token);
  let written = 0;

  const lastAttempt = args.startAttempt + Math.max(1, args.attempts) - 1;
  for (let attempt = args.startAttempt; attempt <= lastAttempt; attempt++) {
    console.error(`=== ${args.taskId} / ${args.harness} / ${args.model} / attempt ${attempt} ===`);

    let runJson: unknown;
    let workDir: string;
    if (args.harness === "refio") {
      const res = await runHeadless({
        repoRoot,
        fixtureDir: null,
        promptText,
        mode: "AGENT",
        model: args.model,
        deliverable: null,
        maxCost: args.maxCost,
      });
      runJson = res.runJson;
      workDir = res.workDir;
    } else {
      const res = await runAgent({
        harnessId: args.harness,
        promptText,
        fixtureDir: null,
        deliverable: null,
        model: args.harnessModel,
        timeoutMs: AGENT_TIMEOUT_MS,
      });
      runJson = res.runJson;
      workDir = res.workDir;
      if (res.stderr.trim() !== "") console.error(res.stderr.slice(0, 500));
    }

    const produced = await readdir(workDir);
    const deliverable = pickDeliverable(produced);
    if (!deliverable) {
      console.error(
        `  no single html artifact in ${workDir} (found: ${produced.join(", ") || "nothing"})`,
      );
    }

    const now = new Date().toISOString();
    const run = parseRunJson(runJson);
    const inboxId = makeInboxId(args.taskId, args.model, attempt, args.harness);
    const attachments: Attachment[] = [];
    let deliverableText: string | null = null;
    let rendered: boolean | null = null;
    let consoleErrors: string[] = [];
    const screenshots: string[] = [];

    if (deliverable) {
      const src = join(workDir, deliverable);
      deliverableText = await readFile(src, "utf8");
      attachments.push({ type: "html", src: `attachments/${inboxId}/artifact.html` });

      if (!args.dryRun) {
        const destDir = join(dataDir, "attachments", inboxId);
        await mkdir(destDir, { recursive: true });
        const artifactDest = join(destDir, "artifact.html");
        await copyFile(src, artifactDest);

        if (!args.noRender) {
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

    // A tasks.json task carries no needles and no expected tool order, so compliance
    // stays for the human and the strong judges; the deterministic pass only reports
    // what it can actually observe.
    const judge = buildDeterministicJudge({
      mode: "AGENT",
      deliverableText,
      finalOutput: run.finalOutput,
      needles: [],
      needleInOutput: null,
      toolCalls: run.toolCalls,
      expectedToolOrder: [],
      toolCallsReported: args.harness === "refio",
      status: run.status,
      rendered,
      consoleErrors,
      judgedAt: now,
      screenshots,
    });

    const entry = buildInboxEntry({
      caseId: args.taskId,
      mode: "AGENT",
      modelId: args.model,
      environmentId: args.env,
      harnessId: args.harness,
      attemptNumber: attempt,
      run,
      judge,
      attachments,
      autoVerdict: deterministicVerdict(judge.scores),
      now,
    });

    const parsed = InboxEntrySchema.safeParse(entry);
    if (!parsed.success) {
      console.error(`invalid inbox entry ${entry.id}:`, parsed.error.issues[0]?.message);
      process.exit(1);
    }
    console.error(
      `  built inbox entry: ${entry.id} (status ${run.status}, verdict ${entry.autoVerdict?.verdict})`,
    );

    if (args.dryRun) {
      console.log(JSON.stringify(entry, null, 2));
      continue;
    }

    // Persist each attempt as it finishes so a crash mid-sweep keeps the finished ones.
    const file = JSON.parse(await readFile(resultsPath, "utf8"));
    ensureModel(file, entry.modelId);
    ensureEnvironment(file, entry.environmentId);
    ensureHarness(file, entry.harnessId);
    upsertInbox(file, entry);
    await saveResultsAtomic(benchmarkDir, file);
    written++;
  }

  if (args.dryRun) {
    console.error("\ndry-run: nothing written.");
    return;
  }
  console.error(`\nwrote ${written} inbox entry(ies) to ${resultsPath}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
