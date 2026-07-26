// The deterministic "judge": it scores exactly the criteria the e2e harness can
// measure objectively - compliance (needles), works_out_of_box (render), and
// agent_logic (run status + tool order) - and emits them as a JudgeScoreSet with
// judgeId "e2e-deterministic". It deliberately leaves look/code and the judge-only
// criteria to the strong LLM judges (they cannot be measured mechanically).
//
// Pure, no IO: importable by vitest (@ alias) and by the tsx importer (relative).
import { snapToScale } from "../judge/scoring";
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
// 0.5, none -> 0. No needles declared is treated as full compliance.
export function complianceFromNeedles(deliverable: string, needles: Needle[]): DetScore {
  if (needles.length === 0) return { value: 1 };
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

// works_out_of_box: the artifact must render in a headless browser cleanly.
export function worksFromRender(opts: { rendered: boolean; consoleErrors: string[] }): DetScore {
  if (!opts.rendered) {
    return { value: 0, rationale: "artifact failed to render in a headless browser" };
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
const EDIT_TOOLS = new Set([
  "advance_code_editing",
  "create_new_file",
  "multi_line_editor",
  "code_editing",
]);

function canonicalTool(tool: string): string {
  return EDIT_TOOLS.has(tool) ? "__edit__" : tool;
}

// agent_logic: the run must succeed and, when required, follow the expected tool
// order (as a subsequence of the tool calls it made). Edit tools are canonicalised
// so any authoring tool satisfies an expected edit step.
export function agentLogicFromRun(opts: {
  status: string;
  toolCalls: string[];
  expectedToolOrder: string[];
}): DetScore {
  if (opts.status !== "SUCCESS") return { value: 0, rationale: `run status ${opts.status}` };
  if (opts.expectedToolOrder.length === 0) return { value: 1 };
  const expected = opts.expectedToolOrder.map(canonicalTool);
  const actual = opts.toolCalls.map(canonicalTool);
  if (isSubsequence(expected, actual)) return { value: 1 };
  return {
    value: 0.5,
    rationale: `expected tool order ${opts.expectedToolOrder.join(" -> ")} not found in the run`,
  };
}

export interface DeterministicInput {
  mode: string;
  deliverableText: string | null; // null when there is no artifact (PLAN/CHAT)
  finalOutput: string;
  needles: Needle[];
  needleInOutput: { regex: string } | null;
  toolCalls: string[];
  expectedToolOrder: string[];
  status: string;
  rendered: boolean | null; // null when no artifact was rendered
  consoleErrors: string[];
  judgedAt: string;
  screenshots: string[];
}

// Assemble the deterministic JudgeScoreSet from one run.
export function buildDeterministicJudge(input: DeterministicInput): JudgeScoreSet {
  const scores: Array<{ criterionId: string; value: number; rationale?: string }> = [];

  if (input.mode === "AGENT" && input.deliverableText !== null) {
    scores.push({ criterionId: "compliance", ...complianceFromNeedles(input.deliverableText, input.needles) });
  } else if (input.needleInOutput) {
    scores.push({ criterionId: "compliance", ...complianceFromOutput(input.finalOutput, input.needleInOutput.regex) });
  }

  if (input.rendered !== null) {
    scores.push({
      criterionId: "works_out_of_box",
      ...worksFromRender({ rendered: input.rendered, consoleErrors: input.consoleErrors }),
    });
  }

  scores.push({
    criterionId: "agent_logic",
    ...agentLogicFromRun({
      status: input.status,
      toolCalls: input.toolCalls,
      expectedToolOrder: input.expectedToolOrder,
    }),
  });

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
