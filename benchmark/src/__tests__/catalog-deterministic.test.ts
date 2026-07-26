// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  needleMatches,
  isSubsequence,
  complianceFromNeedles,
  worksFromRender,
  agentLogicFromRun,
  buildDeterministicJudge,
} from "@/lib/catalog/deterministic";

describe("needleMatches", () => {
  it("matches a text needle as a substring", () => {
    expect(needleMatches({ text: "Pod Bukiem" }, "Welcome to Pod Bukiem cafe")).toBe(true);
    expect(needleMatches({ text: "Missing" }, "abc")).toBe(false);
  });

  it("translates POSIX character classes so a regex needle matches", () => {
    // The e2e needles are authored in POSIX ERE; [[:space:]] must behave like \s.
    const needle = { regex: "[Gg]ame[[:space:]]*[Oo]ver" };
    expect(needleMatches(needle, "the Game  Over screen")).toBe(true);
    expect(needleMatches(needle, "gameover")).toBe(true);
    expect(needleMatches(needle, "no match here")).toBe(false);
  });
});

describe("isSubsequence", () => {
  it("holds when expected appears in order within actual", () => {
    expect(isSubsequence(["a", "c"], ["a", "b", "c", "d"])).toBe(true);
    expect(isSubsequence(["c", "a"], ["a", "b", "c"])).toBe(false);
    expect(isSubsequence([], ["a"])).toBe(true);
  });
});

describe("complianceFromNeedles", () => {
  const text = "<canvas> score Game Over";
  it("scores 1 with no rationale when every needle matches", () => {
    const r = complianceFromNeedles(text, [{ regex: "<canvas" }, { text: "score" }]);
    expect(r.value).toBe(1);
    expect(r.rationale).toBeUndefined();
  });
  it("scores 0.5 with a rationale when only some match", () => {
    const r = complianceFromNeedles(text, [{ regex: "<canvas" }, { text: "absent" }]);
    expect(r.value).toBe(0.5);
    expect(r.rationale).toMatch(/1\/2/);
  });
  it("scores 0 when none match", () => {
    const r = complianceFromNeedles(text, [{ text: "absent" }]);
    expect(r.value).toBe(0);
    expect(r.rationale).toBeDefined();
  });
});

describe("worksFromRender", () => {
  it("scores 1 when the artifact renders with no console errors", () => {
    expect(worksFromRender({ rendered: true, consoleErrors: [] })).toEqual({ value: 1 });
  });
  it("scores 0.5 when it renders but logs console errors", () => {
    const r = worksFromRender({ rendered: true, consoleErrors: ["TypeError x"] });
    expect(r.value).toBe(0.5);
    expect(r.rationale).toBeDefined();
  });
  it("scores 0 when the artifact fails to render", () => {
    const r = worksFromRender({ rendered: false, consoleErrors: [] });
    expect(r.value).toBe(0);
    expect(r.rationale).toBeDefined();
  });
});

describe("agentLogicFromRun", () => {
  it("scores 0 when the run did not succeed", () => {
    const r = agentLogicFromRun({ status: "INCOMPLETE", toolCalls: [], expectedToolOrder: [] });
    expect(r.value).toBe(0);
  });
  it("scores 1 on SUCCESS when no tool order is required", () => {
    expect(agentLogicFromRun({ status: "SUCCESS", toolCalls: ["x"], expectedToolOrder: [] }).value).toBe(1);
  });
  it("scores 1 on SUCCESS when the expected tool order is a subsequence", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      toolCalls: ["read_file", "create_new_file"],
      expectedToolOrder: ["create_new_file"],
    });
    expect(r.value).toBe(1);
  });
  it("scores 0.5 on SUCCESS when the expected tool order is missing", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      toolCalls: ["read_file"],
      expectedToolOrder: ["grep_search"],
    });
    expect(r.value).toBe(0.5);
    expect(r.rationale).toBeDefined();
  });

  // A greenfield deliverable is authored with create_new_file, an incremental change with
  // advance_code_editing/multi_line_editor; for agent_logic those are the same "the model
  // wrote the deliverable" step, so any one satisfies an expected edit tool.
  it("accepts create_new_file where advance_code_editing was the expected edit tool", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      toolCalls: ["create_new_file", "read_file"],
      expectedToolOrder: ["advance_code_editing"],
    });
    expect(r.value).toBe(1);
  });

  it("accepts multi_line_editor for an expected advance_code_editing", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      toolCalls: ["multi_line_editor"],
      expectedToolOrder: ["advance_code_editing"],
    });
    expect(r.value).toBe(1);
  });

  it("still fails a non-edit expected tool the run never called", () => {
    // Equivalence must not turn every run into a pass: a genuinely missing tool
    // (grep_search) still fails the order even though the edit step matched.
    const r = agentLogicFromRun({
      status: "SUCCESS",
      toolCalls: ["create_new_file"],
      expectedToolOrder: ["grep_search", "advance_code_editing"],
    });
    expect(r.value).toBe(0.5);
    expect(r.rationale).toBeDefined();
  });
});

describe("buildDeterministicJudge", () => {
  it("emits compliance + works + agent_logic for an AGENT run", () => {
    const set = buildDeterministicJudge({
      mode: "AGENT",
      deliverableText: "<canvas> score Game Over",
      finalOutput: "done",
      needles: [{ regex: "<canvas" }],
      needleInOutput: null,
      toolCalls: ["create_new_file"],
      expectedToolOrder: ["create_new_file"],
      status: "SUCCESS",
      rendered: true,
      consoleErrors: [],
      judgedAt: "2026-07-25T12:00:00.000Z",
      screenshots: ["attachments/x/_judge/shot-full.png"],
    });
    expect(set.judgeId).toBe("e2e-deterministic");
    expect(set.judgeModel).toBe("refio-cli");
    expect(set.error).toBeNull();
    expect(set.scores.map((s) => s.criterionId).sort()).toEqual([
      "agent_logic",
      "compliance",
      "works_out_of_box",
    ]);
  });

  it("emits compliance (from output) + agent_logic for a PLAN run, no works", () => {
    const set = buildDeterministicJudge({
      mode: "PLAN",
      deliverableText: null,
      finalOutput: "The OrderParser hands off to the validator.",
      needles: [],
      needleInOutput: { regex: "OrderParser" },
      toolCalls: ["grep_search", "read_file"],
      expectedToolOrder: [],
      status: "SUCCESS",
      rendered: null,
      consoleErrors: [],
      judgedAt: "2026-07-25T12:00:00.000Z",
      screenshots: [],
    });
    const ids = set.scores.map((s) => s.criterionId).sort();
    expect(ids).toEqual(["agent_logic", "compliance"]);
    expect(set.scores.find((s) => s.criterionId === "compliance")?.value).toBe(1);
  });
});
