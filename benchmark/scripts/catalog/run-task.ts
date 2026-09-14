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
//        [--harness-model <m>] [--ollama-host <host>] [--attempts N] [--start-attempt N]
//        [--no-render] [--dry-run]
//
// A model id starting with "ollama/" points an external agent at the local Ollama
// endpoint, so the same model can be measured under Refio and under that agent.
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { readFile, readdir, copyFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { runHeadless } from "./lib/run-cli";
import { runAgent, buildCommand, AGENT_HARNESS_IDS } from "./lib/run-agent";
import { captureShots } from "../judge/lib/render";
import {
  buildDeterministicJudge,
  type RenderEvidence,
} from "../../src/lib/catalog/deterministic";
import { createHash } from "node:crypto";
import { pickDeliverable } from "../../src/lib/catalog/deliverable";
import {
  resolveHarnessRouting,
  refioConfigOverrides,
  permissionModeOf,
  ollamaModelName,
  DEFAULT_OLLAMA_CONTEXT,
} from "../../src/lib/catalog/harness-routing";
import { harnessVersion, warmUpOllama } from "./lib/run-context";
import { DEFAULT_AGENT_LIMITS } from "../../src/lib/catalog/agent-limits";
import { toRunJson } from "../../src/lib/catalog/agent-run";
import { buildTraceForRun, thinkingForRun } from "./lib/land-trace";
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
import {
  InboxEntrySchema,
  type Attachment,
  type Thinking,
  type TraceSummary,
} from "../../src/schema/results";

interface Args {
  taskId: string;
  model: string;
  env: string;
  harness: string;
  harnessModel?: string;
  ollamaHost: string;
  thinking: "on" | "off" | "unknown";
  maxOutputTokens?: number;
  // Tokens the local model is loaded with, pinned for both harnesses alike.
  ollamaCtx: number;
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
    ollamaHost: process.env.OLLAMA_HOST ?? "127.0.0.1",
    thinking: "unknown",
    ollamaCtx: DEFAULT_OLLAMA_CONTEXT,
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
    else if (t === "--ollama-host") a.ollamaHost = argv[++i];
    else if (t === "--thinking") a.thinking = argv[++i] as Args["thinking"];
    else if (t === "--max-output-tokens") a.maxOutputTokens = Number(argv[++i]);
    else if (t === "--ollama-ctx") a.ollamaCtx = Number(argv[++i]);
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

  // Load the local model with the window this sweep measures at, so both harnesses get
  // the same one. See import-runs for why this cannot be left to each side's defaults.
  const localModel = ollamaModelName(args.model);
  const contextWindow = localModel ? args.ollamaCtx : undefined;
  let warmedUp = false;
  if (localModel && !args.dryRun) {
    warmedUp = await warmUpOllama(args.ollamaHost, localModel, args.ollamaCtx);
    console.error(
      warmedUp
        ? `  loaded ${localModel} at ${args.ollamaCtx} tokens of context`
        : `  WARNING: could not load ${localModel} at ${args.ollamaCtx} tokens; the window this run used is unknown`,
    );
  }
  const version = await harnessVersion(args.harness);
  const runContext: RunContext = {
    ...(version ? { harnessVersion: version } : {}),
    promptSha256: createHash("sha256").update(promptText).digest("hex"),
    ...(contextWindow !== undefined && warmedUp ? { contextWindow } : {}),
    ...(args.maxOutputTokens !== undefined ? { maxOutputTokens: args.maxOutputTokens } : {}),
    ...(localModel ? { modelServer: args.ollamaHost } : {}),
    ...(permissionModeOf(args.harness) ? { permissionMode: permissionModeOf(args.harness) } : {}),
    timeoutMs: DEFAULT_AGENT_LIMITS.timeoutMs,
    maxTurns: DEFAULT_AGENT_LIMITS.maxTurns,
  };

  const lastAttempt = args.startAttempt + Math.max(1, args.attempts) - 1;
  for (let attempt = args.startAttempt; attempt <= lastAttempt; attempt++) {
    console.error(`=== ${args.taskId} / ${args.harness} / ${args.model} / attempt ${attempt} ===`);

    const inboxId = makeInboxId(args.taskId, args.model, attempt, args.harness);
    let runJson: unknown;
    let workDir: string | null;
    let trace: TraceSummary | undefined;
    let thinking: Thinking | undefined;
    if (args.harness === "refio") {
      const res = await runHeadless({
        repoRoot,
        fixtureDir: null,
        promptText,
        mode: "AGENT",
        model: args.model,
        deliverable: null,
        maxCost: args.maxCost,
        configOverrides: refioConfigOverrides(args.model, args.ollamaHost, contextWindow),
      });
      runJson = res.runJson;
      workDir = res.workDir;
      trace = await buildTraceForRun({
        harnessId: "refio",
        entryId: inboxId,
        dataDir,
        persist: !args.dryRun,
        runJson: res.runJson,
        runJsonSrc: res.runJsonPath,
        loop: parseRunJson(res.runJson).loop,
      });
      thinking = thinkingForRun({
        harnessId: "refio",
        requested: args.thinking,
        runJson: res.runJson,
      });
    } else {
      const routing = resolveHarnessRouting(
        args.harness,
        args.model,
        args.harnessModel,
        args.ollamaHost,
        { thinking: args.thinking, maxOutputTokens: args.maxOutputTokens },
      );
      // A task from tasks.json has no case, so it keeps the default limits.
      const agentOpts = {
        harnessId: args.harness,
        promptText,
        fixtureDir: null,
        deliverable: null,
        routing,
        limits: DEFAULT_AGENT_LIMITS,
        progress: true,
      };
      // A dry run must not spend tokens: it prints the command it would have run and
      // builds the entry from a stand-in result.
      if (args.dryRun) {
        console.error(`  ${buildCommand(agentOpts, "<workdir>", "<workdir>/agent-last-message.txt")}`);
        const envNames = Object.keys(routing.env);
        if (envNames.length > 0) console.error(`  env overrides: ${envNames.join(", ")}`);
        console.error("  dry-run: agent not executed");
        runJson = toRunJson({ status: "FAILED", finalOutput: "dry-run" });
        workDir = null;
      } else {
        const res = await runAgent(agentOpts);
        runJson = res.runJson;
        workDir = res.workDir;
        trace = await buildTraceForRun({
          harnessId: args.harness,
          entryId: inboxId,
          dataDir,
          persist: true,
          timedLines: res.timedLines,
          rawLog: res.rawLog,
          source: res.traceSource,
        });
        thinking = thinkingForRun({
          harnessId: args.harness,
          requested: args.thinking,
          timedLines: res.timedLines,
        });
        if (res.stderr.trim() !== "") console.error(res.stderr.slice(0, 500));
      }
    }

    const produced = workDir ? await readdir(workDir) : [];
    const deliverable = workDir ? pickDeliverable(produced) : null;
    if (workDir && !deliverable) {
      console.error(
        `  no single html artifact in ${workDir} (found: ${produced.join(", ") || "nothing"})`,
      );
    }

    const now = new Date().toISOString();
    const run = parseRunJson(runJson);
    const attachments: Attachment[] = [];
    let deliverableText: string | null = null;
    let rendered: boolean | null = null;
    let consoleErrors: string[] = [];
    let renderEvidence: RenderEvidence | null = null;
    const screenshots: string[] = [];

    if (deliverable && workDir) {
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
          // A browser missing from THIS machine says nothing about the artifact, so the
          // criterion is left unmeasured rather than scored zero - and the run, which
          // cost real tokens, is not thrown away over a local tooling gap.
          try {
            const outcome = await captureShots(artifactDest, { motionPaths: [], fullPagePath: shot });
            rendered = outcome.renderError === null;
            consoleErrors = outcome.consoleErrors;
            renderEvidence = outcome.evidence ?? null;
            if (existsSync(shot)) {
              const rel = `attachments/${inboxId}/_judge/shot-full.png`;
              screenshots.push(rel);
              attachments.push({ type: "image", src: rel });
            }
          } catch (e) {
            console.error(`  render skipped: ${String(e).split("\n")[0]}`);
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
      expectedToolOrder: [],
      ...(trace?.classOrder !== undefined ? { classOrder: trace.classOrder } : {}),
      status: run.status,
      rendered,
      consoleErrors,
      renderEvidence,
      loop: trace
        ? {
            writes: trace.writes,
            toolCalls: trace.toolCalls,
            duplicateCalls: trace.duplicateCalls,
            toolErrors: trace.toolErrors,
            recoveredFromError: trace.recoveredFromError,
            selfVerified: trace.selfVerified,
          }
        : null,
      judgedAt: now,
      screenshots,
    });

    const entry = buildInboxEntry({
      runContext,
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
      ...(trace ? { trace } : {}),
      ...(thinking ? { thinking } : {}),
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
