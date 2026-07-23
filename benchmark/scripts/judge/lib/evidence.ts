// Builds the read-only evidence folder a strong judge scores from: the artifact,
// timed screenshots, a whole-page screenshot, one screenshot per interaction step,
// captured console errors, and the rendered instructions prompt.
import { mkdtemp, mkdir, copyFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { captureShots, captureInteractions, MOTION_DELAYS_MS } from "./render";
import { scenarioFor } from "./interactions";
import {
  describeInteractions,
  INTERACTION_STEPS,
} from "../../../src/lib/judge/interactions";
import type { Result } from "../../../src/schema/results";
import type { Task, Criterion } from "../../../src/schema/tasks";

export interface EvidenceResult {
  evidenceDir: string;
  htmlSrcPath: string;
  // Paths relative to data/, ready to store on a JudgeScoreSet.
  screenshots: string[];
  consoleErrors: string[];
  promptText: string;
}

const MOTION_SHOTS = MOTION_DELAYS_MS.map((_, i) => `shot-${i + 1}.png`);
const FULL_SHOT = "shot-full.png";
const INTERACT_SHOTS = Array.from(
  { length: INTERACTION_STEPS },
  (_, i) => `interact-${i + 1}.png`,
);

export function renderPrompt(
  template: string,
  systemPrompt: string,
  criteria: Criterion[],
): string {
  const criteriaBlock = criteria
    .map(
      (c) =>
        `- ${c.id} (${c.name}): ${c.description}\n  allowed values: [${c.scale.values.join(", ")}]`,
    )
    .join("\n");
  return template
    .replace("{{systemPrompt}}", () => systemPrompt)
    .replace("{{criteria}}", () => criteriaBlock);
}

// Appended to INSTRUCTIONS.md when the artifact failed to render, so the judge
// treats a page a real user could not run as a broken result.
function renderFailureNote(reason: string, shotsAvailable: number): string {
  return (
    "\n\n## Render status: FAILED\n\n" +
    `This artifact failed to render in a headless browser (${reason}). ` +
    (shotsAvailable === 0
      ? "No screenshots are available. "
      : "The screenshots may be blank or incomplete. ") +
    "A real user would see the same broken or hanging page, so treat this as a " +
    "broken result: score works_out_of_box = 0. You may still read artifact.html " +
    "to score logic_correctness and code_structure.\n"
  );
}

export async function buildEvidence(opts: {
  benchmarkDir: string;
  result: Result;
  task: Task;
  criteria: Criterion[];
  promptTemplate: string;
}): Promise<EvidenceResult> {
  const { benchmarkDir, result, task, criteria, promptTemplate } = opts;
  const html = result.attachments.find((a) => a.type === "html");
  if (!html) throw new Error(`result ${result.id} has no HTML artifact`);
  const htmlSrcPath = join(benchmarkDir, "data", html.src);

  const evidenceDir = await mkdtemp(join(tmpdir(), `judge-${result.id}-`));
  await copyFile(htmlSrcPath, join(evidenceDir, "artifact.html"));
  const at = (name: string) => join(evidenceDir, name);

  const render = await captureShots(htmlSrcPath, {
    motionPaths: MOTION_SHOTS.map(at),
    fullPagePath: at(FULL_SHOT),
  });
  const interaction = await captureInteractions(
    htmlSrcPath,
    scenarioFor(task.id),
    INTERACT_SHOTS.map(at),
  );

  const consoleErrors = [...render.consoleErrors, ...interaction.consoleErrors];
  await writeFile(
    join(evidenceDir, "console-errors.json"),
    JSON.stringify(consoleErrors, null, 2),
  );
  await writeFile(
    join(evidenceDir, "interactions.json"),
    JSON.stringify(interaction.records, null, 2),
  );

  // Persist only the screenshots that were actually captured (a broken render may
  // leave none) so the viewer never links a missing file.
  const judgeDir = join(benchmarkDir, "data", "attachments", result.id, "_judge");
  await mkdir(judgeDir, { recursive: true });
  const screenshots: string[] = [];
  for (const name of [...MOTION_SHOTS, FULL_SHOT, ...INTERACT_SHOTS]) {
    if (!existsSync(at(name))) continue;
    await copyFile(at(name), join(judgeDir, name));
    screenshots.push(`attachments/${result.id}/_judge/${name}`);
  }

  let promptText = renderPrompt(promptTemplate, task.systemPrompt, criteria);
  promptText += describeInteractions(interaction.records);
  if (render.renderError) {
    promptText += renderFailureNote(render.renderError, screenshots.length);
  }
  await writeFile(join(evidenceDir, "INSTRUCTIONS.md"), promptText);

  return { evidenceDir, htmlSrcPath, screenshots, consoleErrors, promptText };
}
