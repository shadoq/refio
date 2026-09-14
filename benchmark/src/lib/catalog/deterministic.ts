// The deterministic "judge": it scores exactly the criteria the e2e harness can
// measure objectively - compliance (needles), works_out_of_box (render), and
// agent_logic (run status + tool order) - and emits them as a JudgeScoreSet with
// judgeId "e2e-deterministic". It deliberately leaves look/code and the judge-only
// criteria to the strong LLM judges (they cannot be measured mechanically).
//
// Pure, no IO: importable by vitest (@ alias) and by the tsx importer (relative).
import { snapToScale } from "../judge/scoring";
import { classifyTool } from "../trace/tool-classes";
import type { ToolClass } from "../trace/types";
import type { JudgeScoreSet } from "../../schema/results";

export interface Needle {
  regex?: string;
  text?: string;
}

export interface DetScore {
  value: number;
  rationale?: string;
}

const BINARY_SCALE = [0, 0.5, 1];

// POSIX ERE bracket classes used by the e2e needles, mapped to JS regex snippets.
// They always appear inside [...], e.g. [[:space:]] -> [\s], which is valid JS.
const POSIX_CLASS: Record<string, string> = {
  "[:alpha:]": "a-zA-Z",
  "[:digit:]": "0-9",
  "[:alnum:]": "a-zA-Z0-9",
  "[:space:]": "\\s",
  "[:upper:]": "A-Z",
  "[:lower:]": "a-z",
  "[:word:]": "\\w",
  "[:punct:]": "!-/:-@\\[-`{-~",
};

export function posixEreToJs(pattern: string): string {
  let out = pattern;
  for (const [cls, repl] of Object.entries(POSIX_CLASS)) {
    out = out.split(cls).join(repl);
  }
  return out;
}

export function needleMatches(needle: Needle, text: string): boolean {
  if (needle.text !== undefined) return text.includes(needle.text);
  if (needle.regex !== undefined) {
    try {
      return new RegExp(posixEreToJs(needle.regex)).test(text);
    } catch {
      return false;
    }
  }
  return false;
}

// Is `expected` an ordered subsequence of `actual`?
export function isSubsequence(expected: string[], actual: string[]): boolean {
  let i = 0;
  for (const a of actual) {
    if (i < expected.length && a === expected[i]) i++;
  }
  return i === expected.length;
}

// compliance: fraction of the deliverable needles that matched. All -> 1, some ->
// 0.5, none -> 0.
//
// A case that declares no needles has nothing to compare the artifact against, so the
// criterion is left UNMEASURED rather than scored full marks. Scoring it 1 handed every
// run that dropped any file at all a free point, and that point alone was enough to
// turn the verdict green.
export function complianceFromNeedles(deliverable: string, needles: Needle[]): DetScore | null {
  if (needles.length === 0) return null;
  const hits = needles.filter((n) => needleMatches(n, deliverable)).length;
  const raw = hits === needles.length ? 1 : hits === 0 ? 0 : 0.5;
  const value = snapToScale(raw, BINARY_SCALE);
  return value === 1 ? { value } : { value, rationale: `${hits}/${needles.length} needles matched` };
}

// compliance for PLAN/CHAT: the run's final output must contain the needle.
export function complianceFromOutput(finalOutput: string, needleRegex: string): DetScore {
  const matched = needleMatches({ regex: needleRegex }, finalOutput);
  return matched
    ? { value: 1 }
    : { value: 0, rationale: "expected needle not found in the run output" };
}

// What the page had on it once it had settled. Measured in the browser that took the
// screenshot, so it costs one evaluation and no judge.
export interface RenderEvidence {
  domNodes: number;
  textLength: number;
  hasCanvas: boolean;
  // Distinct colours sampled from the largest canvas; null when it could not be read
  // (a WebGL context, a tainted canvas), which is not evidence of anything.
  canvasColors: number | null;
}

// A page that loaded without complaining and still shows nothing. Only unambiguous
// evidence counts: a canvas we could read that holds a single flat colour, or a page
// with no text and almost no elements. Anything we could not measure is not emptiness.
export function artifactLooksEmpty(ev: RenderEvidence): boolean {
  if (ev.hasCanvas) {
    return ev.canvasColors !== null && ev.canvasColors <= 1;
  }
  return ev.textLength === 0 && ev.domNodes < 10;
}

// works_out_of_box: the artifact must render in a headless browser cleanly AND show
// something. A clean console over a blank canvas passed every mechanical check while
// the deliverable was, to a person looking at it, not there.
export function worksFromRender(opts: {
  rendered: boolean;
  consoleErrors: string[];
  evidence?: RenderEvidence | null;
}): DetScore {
  if (!opts.rendered) {
    return { value: 0, rationale: "artifact failed to render in a headless browser" };
  }
  if (opts.evidence && artifactLooksEmpty(opts.evidence)) {
    return { value: 0, rationale: "rendered, but the page shows nothing" };
  }
  if (opts.consoleErrors.length > 0) {
    return { value: 0.5, rationale: `rendered with ${opts.consoleErrors.length} console error(s)` };
  }
  return { value: 1 };
}

// File-authoring tools are interchangeable for agent_logic: creating a new file and
// editing an existing one are the same "the model wrote the deliverable" step. A
// greenfield task is naturally done with create_new_file, so it must satisfy an
// expected advance_code_editing (and vice versa) instead of being penalised for the
// tool choice. Non-edit tools are compared by their exact name.

// works_out_of_box for a deliverable that cannot be rendered: its own build or test
// command is the measurement. The tail of the output is kept as the reason, so a
// failure says what broke instead of only that something did.
export function worksFromBuild(
  build: { exitCode: number; outputTail: string },
  // What the action log says the run produced. A refactoring case ships a suite that
  // is green before the agent starts - keeping it green is the task - so a passing
  // build proves nothing about a run that wrote nothing. Omit when the run produced no
  // action log: unmeasured writes must not turn a genuine pass into a zero.
  activity?: { wrote: boolean },
): DetScore {
  if (build.exitCode !== 0) {
    return {
      value: 0,
      rationale: build.outputTail.slice(-300) || `build command exited with ${build.exitCode}`,
    };
  }
  if (activity && !activity.wrote) {
    return { value: 0, rationale: "build passes, but the run wrote nothing to make it pass" };
  }
  return { value: 1 };
}

// What the run's own action log says about how the loop behaved. Harness-neutral by
// construction: every field is counted the same way for every agent.
export interface LoopEvidence {
  writes: number;
  toolCalls: number;
  duplicateCalls: number;
  toolErrors: number;
  // Null when nothing failed, so a clean run is not credited with a recovery it never
  // had to make.
  recoveredFromError: boolean | null;
  // Whether the run itself built or tested what it had just written. Null when the run
  // recorded no action log to read it from.
  selfVerified?: boolean | null;
}

// Above this share of repeated calls the agent was mostly re-treading its own steps.
export const WASTE_RATIO_LIMIT = 0.3;

// agent_logic from the action log. This replaces the free full marks every external
// agent used to collect: a run that read nothing, wrote nothing and exited zero scored
// the same as one that did the work, and the only criterion meant to judge the loop
// was scored honestly for Refio alone.
export function agentLogicFromTrace(ev: LoopEvidence, expectsArtifact: boolean): DetScore {
  const wroteNothing = expectsArtifact && ev.writes === 0;
  const faults: string[] = [];
  if (wroteNothing) faults.push("wrote no file");
  if (ev.toolErrors > 0 && ev.recoveredFromError === false) {
    faults.push("stopped after a failing tool call");
  }
  const wasteRatio = ev.toolCalls > 0 ? ev.duplicateCalls / ev.toolCalls : 0;
  if (wasteRatio > WASTE_RATIO_LIMIT) {
    faults.push(`${Math.round(wasteRatio * 100)}% of its calls repeated an earlier one`);
  }

  if (faults.length === 0) return { value: 1 };
  // Producing nothing is not a degree of quality, it is the absence of the work.
  if (wroteNothing || faults.length > 1) return { value: 0, rationale: faults.join("; ") };
  return { value: 0.5, rationale: faults[0] };
}

// agent_logic from what the run actually did, in order. The expectation is written in
// the catalog with Refio's tool names because that is the vocabulary a case author
// works in, but it is CHECKED against the class of each call - read, write, search,
// shell - which every harness reports. Scoring it only where tool names exist made it
// a criterion Refio alone could fail, and a criterion only one side can fail is not a
// comparison.
export function agentLogicFromToolOrder(opts: {
  classOrder: ToolClass[];
  expected: ToolClass[];
}): DetScore {
  if (isSubsequence(opts.expected, opts.classOrder)) return { value: 1 };
  return {
    value: 0.5,
    rationale: `expected ${opts.expected.join(" -> ")} in that order, ran ${opts.classOrder.join(" -> ") || "nothing"}`,
  };
}

// Did the run check its own work? Only asked where the case says checking is part of
// the job: a page-generation task has no suite to run, and scoring it everywhere would
// take the same half point off every harness and measure nothing. Null when the case
// did not ask, or when the run recorded no action log to answer from.
export function agentLogicFromVerification(
  selfVerified: boolean | null | undefined,
  expected: boolean,
): DetScore | null {
  if (!expected) return null;
  if (selfVerified === null || selfVerified === undefined) return null;
  if (selfVerified) return { value: 1 };
  return { value: 0.5, rationale: "finished without ever running the build or the tests it was told to check" };
}

// agent_logic overall: a failed run scores zero, otherwise every measurable view of
// the loop is taken and the worst one stands. Null when nothing about the loop could
// be measured at all - an unmeasured criterion is honest, a free point is not.
export function agentLogicFromRun(opts: {
  status: string;
  expectedToolOrder: string[];
  // What the run did, in order, one entry per call. Absent for a run recorded before
  // the action log existed: unmeasured on this part, never counted as a miss.
  classOrder?: ToolClass[];
  loop?: LoopEvidence | null;
  expectsArtifact?: boolean;
  // The case declared that checking the work is part of the task.
  expectsSelfVerification?: boolean;
}): DetScore | null {
  if (opts.status !== "SUCCESS") return { value: 0, rationale: `run status ${opts.status}` };

  const parts: DetScore[] = [];
  const expected = expectedClasses(opts.expectedToolOrder);
  if (expected.length > 0 && opts.classOrder !== undefined) {
    parts.push(agentLogicFromToolOrder({ classOrder: opts.classOrder, expected }));
  }
  if (opts.loop) {
    parts.push(agentLogicFromTrace(opts.loop, opts.expectsArtifact ?? true));
    const verified = agentLogicFromVerification(
      opts.loop.selfVerified,
      opts.expectsSelfVerification ?? false,
    );
    if (verified) parts.push(verified);
  }
  if (parts.length === 0) return null;

  return parts.reduce((worst, part) => (part.value < worst.value ? part : worst));
}

// A case states its expectation as tool names or as classes; both resolve to classes.
// An "other" class carries no expectation worth checking, so it is dropped rather than
// asserted against a harness that would never emit it.
export function expectedClasses(expected: string[]): ToolClass[] {
  const CLASSES: ToolClass[] = ["read", "write", "shell", "search"];
  return expected
    .map((name) =>
      (CLASSES as string[]).includes(name)
        ? (name as ToolClass)
        : classifyTool("refio", name),
    )
    .filter((cls): cls is ToolClass => cls !== "other");
}

export interface DeterministicInput {
  mode: string;
  deliverableText: string | null; // null when there is no artifact (PLAN/CHAT)
  finalOutput: string;
  needles: Needle[];
  needleInOutput: { regex: string } | null;
  expectedToolOrder: string[];
  // What the run actually did, in order, one entry per call. Every harness produces
  // it, so the expectation above is checked against all of them alike.
  classOrder?: ToolClass[];
  status: string;
  rendered: boolean | null; // null when no artifact was rendered
  // Outcome of the case's own build command, for deliverables there is no browser
  // for. Only consulted when nothing was rendered.
  build?: { exitCode: number; outputTail: string } | null;
  consoleErrors: string[];
  // What the page actually showed, when a browser was there to look.
  renderEvidence?: RenderEvidence | null;
  // What the action log says about how the loop behaved, for every harness alike.
  loop?: LoopEvidence | null;
  // The case declared that the agent must build or test what it wrote.
  expectsSelfVerification?: boolean;
  judgedAt: string;
  screenshots: string[];
}

// Assemble the deterministic JudgeScoreSet from one run.
export function buildDeterministicJudge(input: DeterministicInput): JudgeScoreSet {
  const scores: Array<{ criterionId: string; value: number; rationale?: string }> = [];

  if (input.mode === "AGENT" && input.deliverableText !== null) {
    const compliance = complianceFromNeedles(input.deliverableText, input.needles);
    if (compliance) scores.push({ criterionId: "compliance", ...compliance });
  } else if (input.needleInOutput) {
    scores.push({ criterionId: "compliance", ...complianceFromOutput(input.finalOutput, input.needleInOutput.regex) });
  }

  if (input.rendered !== null) {
    scores.push({
      criterionId: "works_out_of_box",
      ...worksFromRender({
        rendered: input.rendered,
        consoleErrors: input.consoleErrors,
        evidence: input.renderEvidence ?? null,
      }),
    });
  } else if (input.build) {
    scores.push({
      criterionId: "works_out_of_box",
      ...worksFromBuild(
        input.build,
        input.loop ? { wrote: input.loop.writes > 0 } : undefined,
      ),
    });
  }

  const agentLogic = agentLogicFromRun({
    status: input.status,
    expectedToolOrder: input.expectedToolOrder,
    ...(input.classOrder !== undefined ? { classOrder: input.classOrder } : {}),
    loop: input.loop ?? null,
    expectsArtifact: input.mode === "AGENT",
    ...(input.expectsSelfVerification !== undefined
      ? { expectsSelfVerification: input.expectsSelfVerification }
      : {}),
  });
  if (agentLogic) scores.push({ criterionId: "agent_logic", ...agentLogic });

  return {
    judgeId: "e2e-deterministic",
    judgeModel: "refio-cli",
    judgedAt: input.judgedAt,
    scores,
    screenshots: input.screenshots,
    consoleErrors: input.consoleErrors,
    error: null,
  };
}
