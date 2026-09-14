// Import headless runs into the review inbox. For each run it copies the artifact,
// renders a screenshot, computes the deterministic judge (compliance/works/agent_logic)
// and appends a schema-valid inbox[] entry to data/results.json - a row awaiting a
// human's look/code scores. It never writes results[] or manual scores.
//
// Two sources of a run:
//   --from-run <run.json> [--artifact <file>]   use existing artifacts (no model call)
//   (default) --run                             invoke the harness (spends tokens!)
//
// --harness selects what drives the agent: refio (default, the headless CLI) or an
// external coding agent such as claude-code or codex. External runs land in the same
// queue but stay out of the main leaderboard, which filters on the Refio harness.
//
// --model is always the model id RECORDED in the data (anthropic/claude-opus-5).
// --harness-model is what the external CLI is told to run (`opus`); leave it out to
// let the agent pick its own default. For the refio harness --model is both.
//
// A model id starting with "ollama/" points the external agent at the local Ollama
// endpoint (--ollama-host), so the same model can be measured under Refio and under
// that agent and the two rows line up.
//
// usage:
//   tsx import-runs.ts (--all | <id>...) --model <m> [--env <id>] [--harness <id>]
//                      [--harness-model <m>] [--ollama-host <host>] [--attempts N]
//                      [--start-attempt N] [--thinking on|off] [--max-output-tokens N]
//                      [--ollama-ctx N] [--native-tools auto|always|never]
//                      [--from-run <run.json> --artifact <file>] [--no-render] [--dry-run]
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { readFile, copyFile, mkdir, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { loadCases, type LoadedCase } from "../../../tools/e2e/lib/case";
import { runHeadless } from "./lib/run-cli";
import { runAgent, buildCommand, AGENT_HARNESS_IDS } from "./lib/run-agent";
import { captureShots } from "../judge/lib/render";
import { execShell } from "../judge/lib/exec";
import { buildTraceForRun, thinkingForRun } from "./lib/land-trace";
import {
  buildDeterministicJudge,
  type RenderEvidence,
} from "../../src/lib/catalog/deterministic";
import { attachmentForDeliverable } from "../../src/lib/catalog/deliverable";
import {
  resolveHarnessRouting,
  refioConfigOverrides,
  permissionModeOf,
  ollamaModelName,
  DEFAULT_OLLAMA_CONTEXT,
  NATIVE_TOOLS_MODES,
  type NativeToolsMode,
} from "../../src/lib/catalog/harness-routing";
import { harnessVersion, warmUpOllama } from "./lib/run-context";
import { limitsForTier, DEFAULT_AGENT_LIMITS } from "../../src/lib/catalog/agent-limits";
import { toRunJson } from "../../src/lib/catalog/agent-run";
import { resolveModelTemplate } from "../../../tools/e2e/src/emit-scenario";
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
  type InboxEntry,
  type RunContext,
  type Thinking,
  type TraceSummary,
} from "../../src/schema/results";
import { createHash } from "node:crypto";

interface Args {
  ids: string[];
  all: boolean;
  model: string;
  env: string;
  harness: string;
  harnessModel?: string;
  ollamaHost: string;
  thinking: "on" | "off" | "unknown";
  maxOutputTokens?: number;
  // Tokens the local model is loaded with. Pinned for BOTH harnesses, because the same
  // model measured at two different windows is two different measurements.
  ollamaCtx: number;
  nativeTools?: NativeToolsMode;
  attempts: number;
  // Where the attempt numbering starts. Existing results for this task and model keep
  // their numbers, so a fresh sweep must continue above them rather than overwrite.
  startAttempt: number;
  fromRun?: string;
  artifact?: string;
  noRender: boolean;
  dryRun: boolean;
  maxCost?: number;
}

function parseArgs(argv: string[]): Args {
  const a: Args = {
    ids: [],
    all: false,
    model: "",
    env: "local",
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
    if (t === "--all") a.all = true;
    else if (t === "--no-render") a.noRender = true;
    else if (t === "--dry-run") a.dryRun = true;
    else if (t === "--model") a.model = argv[++i];
    else if (t === "--env") a.env = argv[++i];
    else if (t === "--harness") a.harness = argv[++i];
    else if (t === "--harness-model") a.harnessModel = argv[++i];
    else if (t === "--ollama-host") a.ollamaHost = argv[++i];
    else if (t === "--thinking") a.thinking = argv[++i] as Args["thinking"];
    else if (t === "--max-output-tokens") a.maxOutputTokens = Number(argv[++i]);
    else if (t === "--ollama-ctx") a.ollamaCtx = Number(argv[++i]);
    else if (t === "--native-tools") {
      const v = argv[++i];
      if (!(NATIVE_TOOLS_MODES as readonly string[]).includes(v)) {
        throw new Error(`--native-tools must be one of ${NATIVE_TOOLS_MODES.join(", ")}, got ${v}`);
      }
      a.nativeTools = v as NativeToolsMode;
    }
    else if (t === "--attempts") a.attempts = Number(argv[++i]);
    else if (t === "--start-attempt") a.startAttempt = Number(argv[++i]);
    else if (t === "--from-run") a.fromRun = argv[++i];
    else if (t === "--artifact") a.artifact = argv[++i];
    else if (t === "--max-cost") a.maxCost = Number(argv[++i]);
    else if (t.startsWith("--")) throw new Error(`unknown flag: ${t}`);
    else a.ids.push(t);
  }
  return a;
}

// An agent that writes code the model produced is the same risk the e2e harness
// already takes, which is why the work dir is a throwaway temp dir. The cap keeps a
// hanging build from stalling the sweep.
const BUILD_TIMEOUT_MS = 5 * 60 * 1000;
const BUILD_OUTPUT_TAIL = 2000;

interface Paths {
  repoRoot: string;
  benchmarkDir: string;
  e2eDir: string;
  dataDir: string;
}

interface RunOutcome {
  runJson: unknown;
  deliverablePath: string | null;
  workDir: string | null;
  trace?: TraceSummary;
  thinking?: Thinking;
  runContext?: RunContext;
}

export function promptDigest(promptText: string): string {
  return createHash("sha256").update(promptText).digest("hex");
}

// Run the case's own build or test command over what the agent produced. It is the
// only way to score a deliverable there is no browser for.
async function runBuildCmd(
  buildCmd: string,
  workDir: string,
): Promise<{ exitCode: number; outputTail: string }> {
  const r = await execShell(buildCmd, { cwd: workDir, timeoutMs: BUILD_TIMEOUT_MS });
  const output = `${r.stdout}\n${r.stderr}`.trim();
  return {
    exitCode: r.timedOut ? 124 : (r.code ?? 1),
    outputTail: output.slice(-BUILD_OUTPUT_TAIL),
  };
}

async function buildEntry(
  loaded: LoadedCase,
  model: string,
  env: string,
  harness: string,
  attempt: number,
  outcome: RunOutcome,
  paths: Paths,
  noRender: boolean,
  persist: boolean,
): Promise<InboxEntry> {
  const c = loaded.case;
  const now = new Date().toISOString();
  const run = parseRunJson(outcome.runJson);
  const inboxId = makeInboxId(c.id, model, attempt, harness);
  const deliverablePath = outcome.deliverablePath;

  const attachments: Attachment[] = [];
  let deliverableText: string | null = null;
  let rendered: boolean | null = null;
  let consoleErrors: string[] = [];
  let renderEvidence: RenderEvidence | null = null;
  const screenshots: string[] = [];
  const destDir = join(paths.dataDir, "attachments", inboxId);

  if (deliverablePath && existsSync(deliverablePath)) {
    deliverableText = await readFile(deliverablePath, "utf8");
    // A page is previewed in the viewer; a module of a multi-file task has nothing to
    // render, so it is kept as a plain file under its own name.
    const kept = attachmentForDeliverable(deliverablePath);
    const rel = `attachments/${inboxId}/${kept.fileName}`;
    attachments.push(
      kept.kind === "html"
        ? { type: "html", src: rel }
        : { type: "file", src: rel, caption: c.deliverable ?? kept.fileName },
    );

    // Copying the artifact and rendering are side effects, so a dry-run skips
    // them - it still reads the deliverable text to compute compliance.
    if (persist) {
      await mkdir(destDir, { recursive: true });
      const artifactDest = join(destDir, kept.fileName);
      await copyFile(deliverablePath, artifactDest);

      if (kept.kind === "html" && !noRender) {
        const judgeDir = join(destDir, "_judge");
        await mkdir(judgeDir, { recursive: true });
        const shot = join(judgeDir, "shot-full.png");
        // A browser missing from THIS machine says nothing about the artifact, so the
        // criterion is left unmeasured rather than scored zero - and above all the run,
        // which cost real tokens, is not thrown away over a local tooling gap.
        try {
          const outcomeShots = await captureShots(artifactDest, { motionPaths: [], fullPagePath: shot });
          rendered = outcomeShots.renderError === null;
          consoleErrors = outcomeShots.consoleErrors;
          renderEvidence = outcomeShots.evidence ?? null;
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

  // A case with a build command is scored by running it over what the agent left in
  // the work dir, which is also the only evidence a multi-file task worked at all.
  let build: { exitCode: number; outputTail: string } | null = null;
  if (c.assert.buildCmd && outcome.workDir && existsSync(outcome.workDir)) {
    console.error(`  running build command: ${c.assert.buildCmd}`);
    build = await runBuildCmd(c.assert.buildCmd, outcome.workDir);
    console.error(`  build exited ${build.exitCode}`);
    if (persist) {
      const traceDir = join(destDir, "_trace");
      await mkdir(traceDir, { recursive: true });
      await writeFile(join(traceDir, "build.log"), build.outputTail);
    }
  }

  const judge = buildDeterministicJudge({
    mode: c.mode,
    deliverableText,
    finalOutput: run.finalOutput,
    needles: c.assert.needles,
    needleInOutput: c.assert.needleInOutput,
    expectedToolOrder: c.assert.toolOrder,
    // The case says whether checking the work is part of the job; a task with nothing
    // to run must not cost every harness the same half point.
    expectsSelfVerification: c.assert.selfVerified === true,
    // What the run did, in order, in the vocabulary every harness reports. Scoring the
    // expected order only where Refio tool names exist made it a criterion Refio alone
    // could fail.
    ...(outcome.trace?.classOrder !== undefined
      ? { classOrder: outcome.trace.classOrder }
      : {}),
    status: run.status,
    rendered,
    build,
    consoleErrors,
    renderEvidence,
    // The same arithmetic for every harness: what the action log says the loop did.
    loop: outcome.trace
      ? {
          writes: outcome.trace.writes,
          toolCalls: outcome.trace.toolCalls,
          duplicateCalls: outcome.trace.duplicateCalls,
          toolErrors: outcome.trace.toolErrors,
          recoveredFromError: outcome.trace.recoveredFromError,
          selfVerified: outcome.trace.selfVerified,
        }
      : null,
    judgedAt: now,
    screenshots,
  });

  return buildInboxEntry({
    caseId: c.id,
    mode: c.mode,
    modelId: model,
    environmentId: env,
    harnessId: harness,
    attemptNumber: attempt,
    run,
    judge,
    attachments,
    autoVerdict: deterministicVerdict(judge.scores),
    now,
    ...(outcome.trace ? { trace: outcome.trace } : {}),
    ...(outcome.thinking ? { thinking: outcome.thinking } : {}),
    ...(outcome.runContext ? { runContext: outcome.runContext } : {}),
  });
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  if (!args.model) {
    console.error("import-runs: --model <provider/model> is required");
    process.exit(2);
  }
  if (args.harness !== "refio" && !AGENT_HARNESS_IDS.includes(args.harness)) {
    console.error(
      `import-runs: unknown --harness ${args.harness} (known: refio, ${AGENT_HARNESS_IDS.join(", ")})`,
    );
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

  // Load the local model with the window this sweep measures at, before anything runs.
  // Ollama allocates the key-value cache when a model loads, so this is what gives an
  // external agent - which has no way to ask for a window - the same one Refio asks for.
  const localModel = ollamaModelName(args.model);
  const contextWindow = localModel ? args.ollamaCtx : undefined;
  let warmedUp = false;
  if (localModel && !args.dryRun && !args.fromRun) {
    warmedUp = await warmUpOllama(args.ollamaHost, localModel, args.ollamaCtx);
    console.error(
      warmedUp
        ? `  loaded ${localModel} at ${args.ollamaCtx} tokens of context`
        : `  WARNING: could not load ${localModel} at ${args.ollamaCtx} tokens; the window this run used is unknown`,
    );
  }
  const version = await harnessVersion(args.harness);

  // Everything a later reader needs to run this attempt again and get a comparable one.
  const runContextFor = (
    promptText: string,
    limits: { timeoutMs: number; maxTurns: number },
    commandLine?: string,
  ): RunContext => ({
    ...(version ? { harnessVersion: version } : {}),
    promptSha256: promptDigest(promptText),
    ...(contextWindow !== undefined && warmedUp ? { contextWindow } : {}),
    ...(args.maxOutputTokens !== undefined ? { maxOutputTokens: args.maxOutputTokens } : {}),
    ...(localModel ? { modelServer: args.ollamaHost } : {}),
    ...(permissionModeOf(args.harness) ? { permissionMode: permissionModeOf(args.harness) } : {}),
    timeoutMs: limits.timeoutMs,
    maxTurns: limits.maxTurns,
    ...(commandLine ? { commandLine } : {}),
    // Only Refio has a tool channel to choose; for the others the field would say
    // nothing about the run.
    ...(args.harness === "refio" && args.nativeTools !== undefined
      ? { nativeTools: args.nativeTools }
      : {}),
  });

  for (const l of loaded) {
    const count = args.fromRun ? 1 : Math.max(1, args.attempts);
    const lastAttempt = args.startAttempt + count - 1;
    const limits = args.harness === "refio" ? DEFAULT_AGENT_LIMITS : limitsForTier(l.case.tier);
    for (let attempt = args.startAttempt; attempt <= lastAttempt; attempt++) {
      const inboxId = makeInboxId(l.case.id, args.model, attempt, args.harness);
      let outcome: RunOutcome;

      if (args.fromRun) {
        const runJson: unknown = JSON.parse(await readFile(args.fromRun, "utf8"));
        outcome = {
          runJson,
          deliverablePath: args.artifact ?? null,
          workDir: null,
          trace: await buildTraceForRun({
            harnessId: "refio",
            entryId: inboxId,
            dataDir: paths.dataDir,
            persist: !args.dryRun,
            runJson,
            runJsonSrc: args.fromRun,
            loop: parseRunJson(runJson).loop,
          }),
          thinking: thinkingForRun({ harnessId: "refio", requested: args.thinking, runJson }),
        };
      } else {
        // Resolve {{MODEL_ID}} to the real model for both the prompt and the
        // expected deliverable filename, using the canonical catalog prompt.
        // The token carries the harness too, so two harnesses running the same model
        // do not write the same filename into the same queue.
        const token = sanitizeModelId(
          args.harness === "refio" ? args.model : `${args.harness}-${args.model}`,
        );
        const promptText = resolveModelTemplate(l.promptText, token);
        const deliverable = l.case.deliverable
          ? resolveModelTemplate(l.case.deliverable, token)
          : null;
        const fixtureDir = join(paths.e2eDir, l.case.fixture);

        if (args.harness === "refio") {
          const res = await runHeadless({
            repoRoot: paths.repoRoot,
            fixtureDir,
            promptText,
            mode: l.case.mode,
            model: args.model,
            deliverable,
            maxCost: args.maxCost,
            configOverrides: refioConfigOverrides(
              args.model,
              args.ollamaHost,
              contextWindow,
              args.nativeTools,
            ),
          });
          outcome = {
            runJson: res.runJson,
            deliverablePath: res.deliverablePath,
            workDir: res.workDir,
            runContext: runContextFor(promptText, limits),
            trace: await buildTraceForRun({
              harnessId: "refio",
              entryId: inboxId,
              dataDir: paths.dataDir,
              persist: !args.dryRun,
              runJson: res.runJson,
              runJsonSrc: res.runJsonPath,
              loop: parseRunJson(res.runJson).loop,
            }),
            thinking: thinkingForRun({
              harnessId: "refio",
              requested: args.thinking,
              runJson: res.runJson,
            }),
          };
        } else {
          const routing = resolveHarnessRouting(
            args.harness,
            args.model,
            args.harnessModel,
            args.ollamaHost,
            { thinking: args.thinking, maxOutputTokens: args.maxOutputTokens },
          );
          const agentOpts = {
            harnessId: args.harness,
            promptText,
            fixtureDir,
            deliverable,
            routing,
            limits,
            progress: true,
          };
          // A dry run must not spend tokens: it prints the command it would have run
          // and builds the entry from a stand-in result.
          if (args.dryRun) {
            console.error(`  ${buildCommand(agentOpts, "<workdir>", "<workdir>/agent-last-message.txt")}`);
            const envNames = Object.keys(routing.env);
            if (envNames.length > 0) console.error(`  env overrides: ${envNames.join(", ")}`);
            console.error("  dry-run: agent not executed");
            outcome = {
              runJson: toRunJson({ status: "FAILED", finalOutput: "dry-run" }),
              deliverablePath: null,
              workDir: null,
            };
          } else {
            const res = await runAgent(agentOpts);
            outcome = {
              runJson: res.runJson,
              deliverablePath: res.deliverablePath,
              workDir: res.workDir,
              runContext: runContextFor(
                promptText,
                limits,
                buildCommand(agentOpts, res.workDir, "<workdir>/agent-last-message.txt"),
              ),
              trace: await buildTraceForRun({
                harnessId: args.harness,
                entryId: inboxId,
                dataDir: paths.dataDir,
                persist: true,
                timedLines: res.timedLines,
                rawLog: res.rawLog,
                source: res.traceSource,
              }),
              thinking: thinkingForRun({
                harnessId: args.harness,
                requested: args.thinking,
                timedLines: res.timedLines,
              }),
            };
            if (res.deliverablePath === null && res.stderr.trim() !== "") {
              console.error(`  ${args.harness} produced no deliverable; stderr: ${res.stderr.slice(0, 500)}`);
            }
          }
        }
      }
      const entry = await buildEntry(l, args.model, args.env, args.harness, attempt, outcome, paths, args.noRender, !args.dryRun);

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
      ensureHarness(file, entry.harnessId);
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
