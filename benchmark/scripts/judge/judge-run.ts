// Entry point for the strong-judge subsystem. Deterministic orchestration:
// scan results.json -> build evidence (Playwright) -> run judge CLIs read-only ->
// validate + snap the verdict -> write judgeScores back atomically.
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { buildEvidence } from "./lib/evidence";
import {
  loadTasks,
  loadRawResults,
  resolveCriteria,
  upsertJudgeScore,
  recordJudgeError,
  upsertStability,
  saveResultsAtomic,
  type RawResult,
  type RawResultsFile,
} from "./lib/store";
import { claudeCodeAdapter } from "./lib/judges/claude-code";
import { codexAdapter } from "./lib/judges/codex";
import type { JudgeAdapter } from "./lib/judges/types";
import { groupForStability, computeStabilityEntry } from "./lib/stability";
import { stabilityNeedsJudging } from "../../src/lib/judge/stability-merge";
import { extractJson } from "../../src/lib/judge/parse";
import {
  validateVerdict,
  needsNoArtifactVerdict,
  buildNoArtifactVerdict,
} from "../../src/lib/judge/scoring";
import type { Result } from "../../src/schema/results";
import type { Task, TasksFile } from "../../src/schema/tasks";

const JUDGE_TIMEOUT_MS = 300_000;
const LAUNCH_PROMPT =
  "Read the file INSTRUCTIONS.md in the current working directory and score the " +
  "artifact exactly as it specifies. Output ONLY the final JSON object described " +
  "there, with no text before or after it.";
const RETRY_SUFFIX =
  " Your previous response was not valid JSON. Output ONLY the JSON object.";

const ALL_ADAPTERS: JudgeAdapter[] = [claudeCodeAdapter, codexAdapter];

interface Args {
  task?: string;
  model?: string;
  resultId?: string;
  judges?: string[];
  limit: number;
  reJudge: boolean;
  stability: boolean;
  dryRun: boolean;
}

function parseArgs(argv: string[]): Args {
  const args: Args = { limit: 20, reJudge: false, stability: false, dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === "--task") args.task = next();
    else if (a === "--model") args.model = next();
    else if (a === "--result-id") args.resultId = next();
    else if (a === "--judges") args.judges = next().split(",").map((s) => s.trim());
    else if (a === "--limit") args.limit = Number(next());
    else if (a === "--re-judge") args.reJudge = true;
    else if (a === "--stability") args.stability = true;
    else if (a === "--dry-run") args.dryRun = true;
    else console.warn(`ignoring unknown flag: ${a}`);
  }
  return args;
}

function hasHtml(r: RawResult): boolean {
  return r.attachments?.some((a) => a.type === "html") ?? false;
}

function successfulEntry(r: RawResult, judgeId: string): boolean {
  return (r.judgeScores ?? []).some((s) => s.judgeId === judgeId && s.error == null);
}

async function runStability(
  benchmarkDir: string,
  promptsDir: string,
  tasks: TasksFile,
  file: RawResultsFile,
  adapters: JudgeAdapter[],
  args: Args,
): Promise<void> {
  const template = await readFile(join(promptsDir, "judge-stability.md"), "utf8");

  let groups = groupForStability(file.results);
  if (args.task) groups = groups.filter((g) => g.taskId === args.task);
  if (args.model) groups = groups.filter((g) => g.modelId === args.model);
  const byKey = new Map(
    (file.stability ?? []).map((s) => [`${s.taskId}|${s.modelId}|${s.environmentId}`, s]),
  );
  if (!args.reJudge) {
    const judgeIds = adapters.map((a) => a.id);
    groups = groups.filter((g) =>
      stabilityNeedsJudging(byKey.get(`${g.taskId}|${g.modelId}|${g.environmentId}`), judgeIds),
    );
  }
  if (args.limit > 0) groups = groups.slice(0, args.limit);

  console.log(
    `stability: ${groups.length} group(s), judges: [${adapters.map((a) => a.id).join(", ")}]` +
      (args.dryRun ? " (dry-run)" : ""),
  );

  let computed = 0;
  const skipped: string[] = [];
  for (const group of groups) {
    const existing = byKey.get(`${group.taskId}|${group.modelId}|${group.environmentId}`);
    // Top up only what is missing: re-running a judge that already scored the
    // group would spend a CLI call to overwrite its own verdict.
    const groupAdapters = args.reJudge
      ? adapters
      : adapters.filter((a) => !(existing?.judges ?? []).some((j) => j.judgeId === a.id));
    const entry = await computeStabilityEntry({
      benchmarkDir,
      group,
      tasks,
      adapters: groupAdapters,
      template,
      timeoutMs: JUDGE_TIMEOUT_MS,
      dryRun: args.dryRun,
    });
    if ("skipped" in entry) {
      skipped.push(entry.skipped);
      continue;
    }
    if (args.dryRun) {
      console.log(
        `\n=== ${group.taskId}/${group.modelId}/${group.environmentId} ===\n` +
          `attempts: ${entry.resultIds.length}, ` +
          `scoreVariance ${entry.deterministic.scoreVariance.toFixed(3)}, ` +
          `codeSimilarity ${entry.deterministic.codeSimilarity.toFixed(3)}`,
      );
      continue;
    }
    upsertStability(file, entry);
    await saveResultsAtomic(benchmarkDir, file);
    computed++;
    const verdicts = entry.judges.map((j) => `${j.judgeId}=${j.value}`).join(", ");
    console.log(
      `  ${group.taskId}/${group.modelId}: var ${entry.deterministic.scoreVariance.toFixed(2)} ` +
        `sim ${entry.deterministic.codeSimilarity.toFixed(2)} [${verdicts}]`,
    );
  }

  console.log(`\ndone: stability computed ${computed}, skipped ${skipped.length}`);
  skipped.forEach((s) => console.log(`  skipped ${s}`));
  process.exit(0);
}

// Write the deterministic zero verdict for every in-scope run that produced no
// artifact. Returns how many results were scored.
async function writeNoArtifactVerdicts(
  benchmarkDir: string,
  tasks: TasksFile,
  file: RawResultsFile,
  results: RawResult[],
  args: Args,
): Promise<number> {
  const pending = results.filter((r) => args.reJudge || needsNoArtifactVerdict(r));
  if (pending.length === 0) return 0;

  if (args.dryRun) {
    console.log(`no-artifact: would score ${pending.length} result(s) as 0`);
    pending.forEach((r) => console.log(`  ${r.id} (${r.taskId})`));
    return 0;
  }

  for (const r of pending) {
    const verdict = buildNoArtifactVerdict(
      resolveCriteria(tasks, r.taskId),
      new Date().toISOString(),
    );
    upsertJudgeScore(r, verdict);
    console.log(`  ${r.id}/${verdict.judgeId}: avg 0.00 (no artifact)`);
  }
  await saveResultsAtomic(benchmarkDir, file);
  return pending.length;
}

async function main() {
  const benchmarkDir = process.cwd();
  const args = parseArgs(process.argv.slice(2));
  const promptsDir = join(dirname(fileURLToPath(import.meta.url)), "prompts");

  const tasks = await loadTasks(benchmarkDir);
  const file: RawResultsFile = await loadRawResults(benchmarkDir);

  // Resolve active judges (default: all). Skip any whose CLI is missing.
  const wanted = args.judges ?? ALL_ADAPTERS.map((a) => a.id);
  const selected = ALL_ADAPTERS.filter((a) => wanted.includes(a.id));
  const adapters: JudgeAdapter[] = [];
  if (!args.dryRun) {
    for (const a of selected) {
      if (await a.isAvailable()) adapters.push(a);
      else console.warn(`judge "${a.id}" skipped: CLI not found on PATH`);
    }
    if (adapters.length === 0) {
      console.error("no judge CLIs available - nothing to do");
      process.exit(1);
    }
  } else {
    adapters.push(...selected);
  }

  if (args.stability) {
    await runStability(benchmarkDir, promptsDir, tasks, file, adapters, args);
    return;
  }

  const promptTemplate = await readFile(join(promptsDir, "judge-artifact.md"), "utf8");

  // Scan candidates.
  const inScope = (r: RawResult) =>
    (!args.resultId || r.id === args.resultId) &&
    (!args.task || r.taskId === args.task) &&
    (!args.model || r.modelId === args.model);

  // A run that worked but delivered no artifact scores the scale minimum. No CLI
  // is launched: the verdict follows from the missing file, and leaving it out
  // would hide the failure from every judge metric.
  const zeroed = await writeNoArtifactVerdicts(
    benchmarkDir,
    tasks,
    file,
    file.results.filter((r) => !hasHtml(r) && inScope(r)),
    args,
  );

  let candidates = file.results.filter((r) => hasHtml(r) && inScope(r));

  const needing = (r: RawResult) =>
    adapters.filter((a) => args.reJudge || !successfulEntry(r, a.id));
  candidates = candidates.filter((r) => needing(r).length > 0);
  if (args.limit > 0) candidates = candidates.slice(0, args.limit);

  console.log(
    `scanning: ${candidates.length} result(s), judges: [${adapters.map((a) => a.id).join(", ")}]` +
      (args.dryRun ? " (dry-run)" : ""),
  );

  let judged = 0;
  let errors = 0;
  const skipped: string[] = [];

  for (const r of candidates) {
    const task = tasks.tasks.find((t) => t.id === r.taskId);
    if (!task) {
      skipped.push(`${r.id}: unknown task "${r.taskId}"`);
      continue;
    }
    const criteria = resolveCriteria(tasks, r.taskId);

    let evidence;
    try {
      evidence = await buildEvidence({
        benchmarkDir,
        result: r as unknown as Result,
        task: task as Task,
        criteria,
        promptTemplate,
      });
    } catch (e) {
      skipped.push(`${r.id}: evidence failed: ${(e as Error).message}`);
      continue;
    }

    if (args.dryRun) {
      console.log(`\n=== ${r.id} (${r.taskId}) ===`);
      console.log(`evidence dir: ${evidence.evidenceDir}`);
      console.log(`console errors: ${evidence.consoleErrors.length}`);
      console.log(`would run: [${needing(r).map((a) => a.id).join(", ")}]`);
      console.log(`--- INSTRUCTIONS.md ---\n${evidence.promptText}`);
      continue;
    }

    for (const adapter of needing(r)) {
      const judgedAt = new Date().toISOString();
      const hasScores = (o: unknown) =>
        Array.isArray((o as { scores?: unknown })?.scores);
      try {
        let verdict = await adapter.judge(evidence.evidenceDir, LAUNCH_PROMPT, JUDGE_TIMEOUT_MS);
        let parsed = extractJson(verdict.rawOutput, hasScores);
        if (!parsed) {
          verdict = await adapter.judge(
            evidence.evidenceDir,
            LAUNCH_PROMPT + RETRY_SUFFIX,
            JUDGE_TIMEOUT_MS,
          );
          parsed = extractJson(verdict.rawOutput, hasScores);
        }
        if (!parsed) throw new Error("no JSON verdict with a scores array after retry");

        const rawScores = (parsed as { scores: unknown[] }).scores;
        const { scores, missing } = validateVerdict(rawScores, criteria);
        if (missing.length > 0) {
          console.warn(`  ${r.id}/${adapter.id}: missing criteria [${missing.join(", ")}]`);
        }

        upsertJudgeScore(r, {
          judgeId: adapter.id,
          judgeModel: verdict.judgeModel,
          judgedAt,
          scores,
          screenshots: evidence.screenshots,
          consoleErrors: evidence.consoleErrors,
          error: null,
        });
        await saveResultsAtomic(benchmarkDir, file);
        judged++;
        const avg = scores.length
          ? (scores.reduce((a, s) => a + s.value, 0) / scores.length).toFixed(2)
          : "n/a";
        console.log(`  ${r.id}/${adapter.id}: avg ${avg}`);
      } catch (e) {
        // A failed judge must not overwrite a verdict an earlier run produced: a
        // transient CLI failure (timeout, provider usage limit) would otherwise
        // erase a good score. Only write the error - and the file - when no prior
        // successful entry for this judge exists.
        const wrote = recordJudgeError(r, {
          judgeId: adapter.id,
          judgeModel: "",
          judgedAt,
          scores: [],
          screenshots: evidence.screenshots,
          consoleErrors: evidence.consoleErrors,
          error: (e as Error).message,
        });
        if (wrote) await saveResultsAtomic(benchmarkDir, file);
        errors++;
        const kept = wrote ? "" : " (kept prior score)";
        console.error(`  ${r.id}/${adapter.id}: ERROR${kept} ${(e as Error).message}`);
      }
    }
  }

  console.log(
    `\ndone: judged ${judged}, no-artifact zeros ${zeroed}, ` +
      `errors ${errors}, skipped ${skipped.length}`,
  );
  skipped.forEach((s) => console.log(`  skipped ${s}`));
  process.exit(errors > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
