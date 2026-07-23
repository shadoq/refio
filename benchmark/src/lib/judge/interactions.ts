// Pure part of the judge's interaction evidence: which scenario a task gets, and
// how the recorded steps are described to the judge. The Playwright driver that
// actually performs the steps lives in scripts/judge/lib/interactions.ts.

// How many interaction screenshots every scenario produces.
export const INTERACTION_STEPS = 3;

export type ScenarioId = "generic" | "snake" | "todo";

// One interaction step as the judge sees it in interactions.json.
export interface InteractionRecord {
  shot: string;
  // What the step did, in plain words ("clicked button 'Get tickets'").
  action: string;
  ok: boolean;
  note?: string;
}

// Tasks whose controls cannot be exercised by clicking the first buttons on the
// page: snake is keyboard-driven, todo shows nothing until something is typed.
const BY_TASK: Record<string, ScenarioId> = {
  snake: "snake",
  "todo-app": "todo",
};

export function scenarioIdFor(taskId: string): ScenarioId {
  return BY_TASK[taskId] ?? "generic";
}

// Tells the judge what each interaction screenshot was supposed to show, so an
// unchanged shot reads as "the control did nothing" instead of being guessed at.
export function describeInteractions(records: InteractionRecord[]): string {
  if (!records.length) return "";
  const lines = records.map((r) => {
    const suffix = r.note ? ` [${r.note}]` : "";
    return `- \`${r.shot}\` - ${r.action}${r.ok ? "" : " (step failed)"}${suffix}`;
  });
  return (
    "\n\n## Interaction screenshots\n\n" +
    "Each was taken on a freshly loaded page after exactly the step described, so " +
    "they are independent of each other:\n\n" +
    lines.join("\n") +
    "\n\nIf a step reports it found nothing to click, or the shot is identical to " +
    "`shot-1.png` when the step did click something, the control is inert - that is " +
    "a defect for `works_out_of_box`.\n"
  );
}
