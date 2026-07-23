// Cross-attempt stability: deterministic metrics (score variance + code
// similarity) plus a blind judge verdict over all attempts of one
// (task, model, environment) group.
import { readFile, mkdtemp, copyFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { captureShots, SETTLED_SHOT_MS } from "./render";
import { averagePairwiseSimilarity } from "../../../src/lib/judge/similarity";
import { aggregateJudgeScores, scoreVariance, snapToScale } from "../../../src/lib/judge/scoring";
import { extractJson } from "../../../src/lib/judge/parse";
import type { RawResult, RawResultsFile } from "./store";
import type { TasksFile } from "../../../src/schema/tasks";
import type { JudgeAdapter } from "./judges/types";
import type { StabilityEntry, StabilityJudge } from "../../../src/schema/results";

const STABILITY_SCALE = [0, 0.5, 1];
const LAUNCH_PROMPT =
  "Read the file INSTRUCTIONS.md in the current working directory and assess the " +
  "stability of the attempts exactly as it specifies. Output ONLY the final JSON " +
  "object, with no text before or after it.";

export interface StabilityGroup {
  taskId: string;
  modelId: string;
  environmentId: string;
  results: RawResult[];
}

function hasHtml(r: RawResult): boolean {
  return r.attachments?.some((a) => a.type === "html") ?? false;
}

function htmlSrc(r: RawResult): string {
  return r.attachments.find((a) => a.type === "html")!.src;
}

// Group by logical key, keeping only groups with >= 2 HTML attempts.
export function groupForStability(results: RawResult[]): StabilityGroup[] {
  const byKey = new Map<string, StabilityGroup>();
  for (const r of results.filter(hasHtml)) {
    const key = `${r.taskId}|${r.modelId}|${r.environmentId}`;
    const g = byKey.get(key);
    if (g) g.results.push(r);
    else
      byKey.set(key, {
        taskId: r.taskId,
        modelId: r.modelId,
        environmentId: r.environmentId,
        results: [r],
      });
  }
  return [...byKey.values()].filter((g) => g.results.length >= 2);
}

function renderTemplate(template: string, systemPrompt: string, count: number): string {
  return template
    .replace(/\{\{systemPrompt\}\}/g, () => systemPrompt)
    .replace(/\{\{attemptCount\}\}/g, () => String(count))
    .replace(/\{\{attemptCountPadded\}\}/g, () => String(count).padStart(2, "0"));
}

async function buildStabilityEvidence(
  benchmarkDir: string,
  group: StabilityGroup,
  systemPrompt: string,
  template: string,
): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), `judge-stab-${group.taskId}-`));
  for (let i = 0; i < group.results.length; i++) {
    const label = String(i + 1).padStart(2, "0");
    const src = join(benchmarkDir, "data", htmlSrc(group.results[i]));
    await copyFile(src, join(dir, `attempt-${label}.html`));
    // One settled screenshot per attempt: this judge compares attempts against
    // each other, so motion and interaction shots would only add noise.
    await captureShots(src, {
      motionPaths: [join(dir, `shot-${label}.png`)],
      delaysMs: [SETTLED_SHOT_MS],
    });
  }
  await writeFile(
    join(dir, "INSTRUCTIONS.md"),
    renderTemplate(template, systemPrompt, group.results.length),
  );
  return dir;
}

// Deterministic metrics for one group. Returns null when the group has no judge
// scores yet (score variance is undefined without them).
export async function computeDeterministic(
  benchmarkDir: string,
  group: StabilityGroup,
): Promise<{ scoreVariance: number; codeSimilarity: number } | null> {
  const perAttempt = group.results.map((r) => aggregateJudgeScores(r.judgeScores ?? []));
  if (perAttempt.some((agg) => Object.keys(agg).length === 0)) return null;

  const htmls = await Promise.all(
    group.results.map((r) => readFile(join(benchmarkDir, "data", htmlSrc(r)), "utf8")),
  );
  return {
    scoreVariance: scoreVariance(perAttempt),
    codeSimilarity: averagePairwiseSimilarity(htmls),
  };
}

async function runStabilityJudge(
  adapter: JudgeAdapter,
  evidenceDir: string,
  timeoutMs: number,
): Promise<StabilityJudge> {
  const verdict = await adapter.judge(evidenceDir, LAUNCH_PROMPT, timeoutMs);
  const hasValue = (o: unknown) => typeof (o as { value?: unknown })?.value === "number";
  const parsed = extractJson(verdict.rawOutput, hasValue) as
    | { value: number; rationale?: unknown }
    | null;
  if (!parsed) {
    throw new Error("stability verdict has no numeric value");
  }
  return {
    judgeId: adapter.id,
    judgeModel: verdict.judgeModel,
    value: snapToScale(parsed.value, STABILITY_SCALE),
    rationale: typeof parsed.rationale === "string" ? parsed.rationale : undefined,
    judgedAt: new Date().toISOString(),
  };
}

export async function computeStabilityEntry(opts: {
  benchmarkDir: string;
  group: StabilityGroup;
  tasks: TasksFile;
  adapters: JudgeAdapter[];
  template: string;
  timeoutMs: number;
  dryRun: boolean;
}): Promise<StabilityEntry | { skipped: string }> {
  const { benchmarkDir, group, tasks, adapters, template, timeoutMs, dryRun } = opts;
  const deterministic = await computeDeterministic(benchmarkDir, group);
  if (!deterministic) {
    return { skipped: `${group.taskId}/${group.modelId}: no judgeScores on some attempts` };
  }

  const task = tasks.tasks.find((t) => t.id === group.taskId);
  const judges: StabilityJudge[] = [];
  if (!dryRun && task) {
    const evidenceDir = await buildStabilityEvidence(
      benchmarkDir,
      group,
      task.systemPrompt,
      template,
    );
    for (const adapter of adapters) {
      try {
        judges.push(await runStabilityJudge(adapter, evidenceDir, timeoutMs));
      } catch (e) {
        console.error(`  stability ${group.taskId}/${adapter.id}: ERROR ${(e as Error).message}`);
      }
    }
  }

  return {
    taskId: group.taskId,
    modelId: group.modelId,
    environmentId: group.environmentId,
    resultIds: group.results.map((r) => r.id),
    deterministic,
    judges,
    computedAt: new Date().toISOString(),
  };
}
