// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  needleMatches,
  isSubsequence,
  complianceFromNeedles,
  worksFromRender,
  worksFromBuild,
  agentLogicFromRun,
  agentLogicFromToolOrder,
  agentLogicFromTrace,
  agentLogicFromVerification,
  expectedClasses,
  artifactLooksEmpty,
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
    expect(r?.value).toBe(1);
    expect(r?.rationale).toBeUndefined();
  });
  it("scores 0.5 with a rationale when only some match", () => {
    const r = complianceFromNeedles(text, [{ regex: "<canvas" }, { text: "absent" }]);
    expect(r?.value).toBe(0.5);
    expect(r?.rationale).toMatch(/1\/2/);
  });
  it("scores 0 when none match", () => {
    const r = complianceFromNeedles(text, [{ text: "absent" }]);
    expect(r?.value).toBe(0);
    expect(r?.rationale).toBeDefined();
  });
});

describe("worksFromRender", () => {
  it("scores 1 when the artifact renders with no console errors", () => {
    expect(worksFromRender({ rendered: true, consoleErrors: [] })).toEqual({ value: 1 });
  });
  it("scores 0.5 when it renders but logs console errors", () => {
    const r = worksFromRender({ rendered: true, consoleErrors: ["TypeError x"] });
    expect(r?.value).toBe(0.5);
    expect(r?.rationale).toBeDefined();
  });
  it("scores 0 when the artifact fails to render", () => {
    const r = worksFromRender({ rendered: false, consoleErrors: [] });
    expect(r?.value).toBe(0);
    expect(r?.rationale).toBeDefined();
  });
});

describe("agentLogicFromRun", () => {
  it("scores 0 when the run did not succeed", () => {
    const r = agentLogicFromRun({ status: "INCOMPLETE", expectedToolOrder: [] });
    expect(r?.value).toBe(0);
  });

  // Nothing about the loop was measurable here, and an unmeasured criterion is left
  // out rather than handed full marks: the free point was enough on its own to turn a
  // verdict green.
  it("leaves the criterion unmeasured when there is no expectation and no action log", () => {
    expect(
      agentLogicFromRun({ status: "SUCCESS", expectedToolOrder: [], classOrder: ["read"] }),
    ).toBeNull();
  });

  it("scores 1 when the expected actions appear in order", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      classOrder: ["read", "write"],
      expectedToolOrder: ["create_new_file"],
    });
    expect(r?.value).toBe(1);
  });

  it("scores 0.5 when the expected action never happened", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      classOrder: ["read"],
      expectedToolOrder: ["grep_search"],
    });
    expect(r?.value).toBe(0.5);
    expect(r?.rationale).toBeDefined();
  });

  // A greenfield deliverable is authored with create_new_file, an incremental change
  // with advance_code_editing or multi_line_editor. For this criterion all three are
  // the same step - the model wrote the deliverable - and so is a shell command that
  // redirects into the file, which is how an agent with no edit tool does it.
  it("treats every way of writing the deliverable as the same step", () => {
    for (const expected of ["create_new_file", "advance_code_editing", "multi_line_editor"]) {
      expect(
        agentLogicFromRun({
          status: "SUCCESS",
          classOrder: ["read", "write"],
          expectedToolOrder: [expected],
        })?.value,
      ).toBe(1);
    }
  });

  it("still fails an expected action the run never took", () => {
    // Equivalence must not turn every run into a pass: a genuinely missing search
    // still fails the order even though the write step matched.
    const r = agentLogicFromRun({
      status: "SUCCESS",
      classOrder: ["write"],
      expectedToolOrder: ["grep_search", "advance_code_editing"],
    });
    expect(r?.value).toBe(0.5);
    expect(r?.rationale).toBeDefined();
  });

  // The action log is what makes the criterion answerable for every harness; without
  // one there is nothing to check, and the loop evidence stands alone.
  it("judges a run with no action log from its loop evidence alone", () => {
    const clean = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: ["create_new_file"],
      loop: { writes: 2, toolCalls: 8, duplicateCalls: 0, toolErrors: 0, recoveredFromError: null },
    });
    expect(clean?.value).toBe(1);

    const idle = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: [],
      loop: { writes: 0, toolCalls: 1, duplicateCalls: 0, toolErrors: 0, recoveredFromError: null },
    });
    expect(idle?.value).toBe(0);
  });
});

describe("expectedClasses", () => {
  it("resolves a catalog expectation written in Refio's tool names", () => {
    expect(expectedClasses(["grep_search", "create_new_file"])).toEqual(["search", "write"]);
    expect(expectedClasses(["read_file", "run_terminal_command"])).toEqual(["read", "shell"]);
  });

  it("accepts an expectation already written as classes", () => {
    expect(expectedClasses(["read", "write"])).toEqual(["read", "write"]);
  });

  // A name nothing classifies carries no expectation any harness could satisfy, so
  // asserting it would fail every run rather than measure one.
  it("drops an expectation no harness could report", () => {
    expect(expectedClasses(["delegate_to_strong_model"])).toEqual([]);
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
      classOrder: ["write"],
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
      classOrder: ["search", "read"],
      expectedToolOrder: [],
      status: "SUCCESS",
      rendered: null,
      consoleErrors: [],
      judgedAt: "2026-07-25T12:00:00.000Z",
      screenshots: [],
    });
    const ids = set.scores.map((s) => s.criterionId).sort();
    expect(ids).toEqual(["compliance"]);
    expect(set.scores.find((s) => s.criterionId === "compliance")?.value).toBe(1);
  });

  // A self-recovery sequence (delete a truncated file, retry via a shell script) can
  // call advance_code_editing and still leave nothing behind. The trace's write count
  // says the model tried; the directory scan that produced deliverableText says it did
  // not land. agent_logic must trust the scan, or a run that produced no artifact at
  // all scores a false PASS on the one criterion meant to catch exactly that.
  it("fails agent_logic when a write was attempted but no deliverable was found on disk", () => {
    const set = buildDeterministicJudge({
      mode: "AGENT",
      deliverableText: null,
      finalOutput: "Now let me write the complete file.",
      needles: [],
      needleInOutput: null,
      expectedToolOrder: [],
      status: "SUCCESS",
      rendered: null,
      consoleErrors: [],
      loop: { writes: 1, toolCalls: 7, duplicateCalls: 0, toolErrors: 1, recoveredFromError: true },
      judgedAt: "2026-07-25T12:00:00.000Z",
      screenshots: [],
    });
    const agentLogic = set.scores.find((s) => s.criterionId === "agent_logic");
    expect(agentLogic?.value).toBe(0);
    expect(agentLogic?.rationale).toContain("wrote no file");
  });

  // A multi-file (build-scored) task has no single deliverable file to check - the
  // build command is the only evidence it worked - so a null deliverableText there
  // must not be read as "wrote nothing" the way it is for a render-scored task.
  it("does not penalise a build-scored task for having no single deliverable", () => {
    const set = buildDeterministicJudge({
      mode: "AGENT",
      deliverableText: null,
      finalOutput: "done",
      needles: [],
      needleInOutput: null,
      expectedToolOrder: [],
      status: "SUCCESS",
      rendered: null,
      build: { exitCode: 0, outputTail: "" },
      consoleErrors: [],
      loop: { writes: 1, toolCalls: 3, duplicateCalls: 0, toolErrors: 0, recoveredFromError: null },
      judgedAt: "2026-07-25T12:00:00.000Z",
      screenshots: [],
    });
    expect(set.scores.find((s) => s.criterionId === "agent_logic")?.value).toBe(1);
  });
});

// A multi-file deliverable cannot be rendered in a browser, so what "works out of the
// box" means for it is that its own build or test command passes.
describe("works_out_of_box from a build command", () => {
  const base = {
    mode: "AGENT",
    deliverableText: "require('./pricing')",
    finalOutput: "",
    needles: [],
    needleInOutput: null,
    toolCalls: [],
    expectedToolOrder: [],
    status: "SUCCESS",
    consoleErrors: [],
    judgedAt: "2026-09-11T10:00:00.000Z",
    screenshots: [],
  };

  it("scores a passing build as working", () => {
    const judge = buildDeterministicJudge({
      ...base,
      rendered: null,
      build: { exitCode: 0, outputTail: "ok" },
    });
    expect(judge.scores.find((s) => s.criterionId === "works_out_of_box")?.value).toBe(1);
  });

  it("scores a failing build as broken and says what the output was", () => {
    const judge = buildDeterministicJudge({
      ...base,
      rendered: null,
      build: { exitCode: 1, outputTail: "2 tests failed" },
    });
    const score = judge.scores.find((s) => s.criterionId === "works_out_of_box");
    expect(score?.value).toBe(0);
    expect(score?.rationale).toContain("2 tests failed");
  });

  it("leaves the criterion unscored when neither a render nor a build happened", () => {
    const judge = buildDeterministicJudge({ ...base, rendered: null });
    expect(judge.scores.some((s) => s.criterionId === "works_out_of_box")).toBe(false);
  });

  it("keeps the render verdict when the artifact was rendered", () => {
    const judge = buildDeterministicJudge({
      ...base,
      rendered: true,
      build: { exitCode: 1, outputTail: "irrelevant" },
    });
    expect(judge.scores.find((s) => s.criterionId === "works_out_of_box")?.value).toBe(1);
  });
});

// The criterion that used to be a free point for every external agent, and the only
// one meant to say anything about how the loop worked.
describe("agentLogicFromTrace", () => {
  const clean = { writes: 1, toolCalls: 10, duplicateCalls: 0, toolErrors: 0, recoveredFromError: null };

  it("gives full marks to a run that delivered without thrashing", () => {
    expect(agentLogicFromTrace(clean, true).value).toBe(1);
  });

  it("scores zero when the agent produced no file at all", () => {
    const r = agentLogicFromTrace({ ...clean, writes: 0 }, true);
    expect(r?.value).toBe(0);
    expect(r?.rationale).toContain("wrote no file");
  });

  it("does not expect a file from a run that was never meant to produce one", () => {
    expect(agentLogicFromTrace({ ...clean, writes: 0 }, false).value).toBe(1);
  });

  it("scores zero when a deliverable check says nothing was found, even if a write was called", () => {
    const r = agentLogicFromTrace(clean, true, false);
    expect(r?.value).toBe(0);
    expect(r?.rationale).toContain("wrote no file");
  });

  it("trusts the write count when no deliverable check was made", () => {
    expect(agentLogicFromTrace(clean, true).value).toBe(1);
    expect(agentLogicFromTrace(clean, true, true).value).toBe(1);
  });

  it("docks a run that stopped at its last failing call", () => {
    const r = agentLogicFromTrace({ ...clean, toolErrors: 2, recoveredFromError: false }, true);
    expect(r?.value).toBe(0.5);
  });

  it("does not dock a run that failed and then carried on", () => {
    expect(agentLogicFromTrace({ ...clean, toolErrors: 2, recoveredFromError: true }, true).value).toBe(1);
  });

  it("docks a run that mostly repeated itself", () => {
    const r = agentLogicFromTrace({ ...clean, toolCalls: 10, duplicateCalls: 6 }, true);
    expect(r?.value).toBe(0.5);
    expect(r?.rationale).toContain("repeated");
  });
});

// All the mechanical checks passed on a page whose canvas was blank and whose own
// counter read zero frames per second. Console silence is not the same as working.
describe("artifactLooksEmpty", () => {
  it("calls a canvas holding one flat colour empty", () => {
    expect(artifactLooksEmpty({ domNodes: 40, textLength: 12, hasCanvas: true, canvasColors: 1 })).toBe(true);
  });

  it("accepts a canvas with something drawn on it", () => {
    expect(artifactLooksEmpty({ domNodes: 40, textLength: 12, hasCanvas: true, canvasColors: 260 })).toBe(false);
  });

  // A WebGL canvas cannot be sampled through a 2D context; not knowing is not evidence.
  it("says nothing about a canvas it could not read", () => {
    expect(artifactLooksEmpty({ domNodes: 40, textLength: 0, hasCanvas: true, canvasColors: null })).toBe(false);
  });

  it("calls a page with no text and almost no elements empty", () => {
    expect(artifactLooksEmpty({ domNodes: 4, textLength: 0, hasCanvas: false, canvasColors: null })).toBe(true);
  });

  it("accepts an ordinary page of text", () => {
    expect(artifactLooksEmpty({ domNodes: 120, textLength: 900, hasCanvas: false, canvasColors: null })).toBe(false);
  });
});

// A case with no needles has nothing to check the artifact against.
describe("complianceFromNeedles with nothing to check", () => {
  it("leaves compliance unmeasured instead of awarding it", () => {
    expect(complianceFromNeedles("<html></html>", [])).toBeNull();
  });
});

// A criterion that can only ever be scored against one harness can only ever lower
// that harness's score. Fifteen of the catalog's cases expect a tool named the way
// Refio names it, so before this the comparison judged Refio on a part nobody else
// carried. The expectation is about WHAT the agent did, so it is checked against the
// class of each call, which every harness now reports.
describe("agentLogicFromToolOrder is asked in a vocabulary every harness speaks", () => {
  it("passes a run whose actions contain the expected ones in order", () => {
    expect(
      agentLogicFromToolOrder({ classOrder: ["read", "read", "write"], expected: ["write"] }).value,
    ).toBe(1);
    expect(
      agentLogicFromToolOrder({
        classOrder: ["search", "read", "write", "shell"],
        expected: ["read", "write"],
      }).value,
    ).toBe(1);
  });

  it("marks a run that never did the expected thing", () => {
    const r = agentLogicFromToolOrder({ classOrder: ["read", "read"], expected: ["write"] });
    expect(r.value).toBe(0.5);
    expect(r.rationale).toMatch(/write/);
  });

  it("marks a run that did the expected things in the wrong order", () => {
    expect(
      agentLogicFromToolOrder({ classOrder: ["write", "read"], expected: ["read", "write"] }).value,
    ).toBe(0.5);
  });
});

// The same case, the same behaviour, scored through two different harnesses: the one
// that shells out and the one with named tools must come out with the same number.
describe("agentLogicFromRun scores a shell-only harness like a tool-named one", () => {
  const loop = { writes: 1, toolCalls: 6, duplicateCalls: 0, toolErrors: 0, recoveredFromError: null };
  it("gives the same score for the same action sequence", () => {
    const refio = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: ["write"],
      classOrder: ["read", "read", "write"],
      loop,
    });
    const codex = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: ["write"],
      classOrder: ["read", "read", "write"],
      loop,
    });
    expect(refio?.value).toBe(codex?.value);
    expect(refio?.value).toBe(1);
  });

  // A run whose actions were never recorded is unmeasured on this part, not a miss.
  it("skips the order when the run recorded no actions at all", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: ["write"],
      loop,
    });
    expect(r?.value).toBe(1);
  });
});

// A refactoring case starts with a green test suite on purpose: the point is to keep
// it green while moving the code. So a passing build is only evidence that the AGENT
// worked when the agent wrote something - otherwise the fixture's own health is being
// scored as the agent's achievement, and a run that read three files and stopped
// collects a full point for it.
describe("worksFromBuild against a suite that was green to begin with", () => {
  const green = { exitCode: 0, outputTail: "" };

  it("credits a passing build to a run that changed something", () => {
    expect(worksFromBuild(green, { wrote: true }).value).toBe(1);
  });

  it("refuses to credit a passing build to a run that wrote nothing", () => {
    const r = worksFromBuild(green, { wrote: false });
    expect(r.value).toBe(0);
    expect(r.rationale).toMatch(/wrote nothing/i);
  });

  it("still fails a broken build whatever the run did", () => {
    expect(worksFromBuild({ exitCode: 1, outputTail: "1 failing" }, { wrote: true }).value).toBe(0);
    expect(worksFromBuild({ exitCode: 1, outputTail: "1 failing" }, { wrote: false }).value).toBe(0);
  });

  // A run whose actions were never recorded cannot be held against: unmeasured writes
  // must not turn a genuine pass into a zero.
  it("credits the build when there is no action log to check", () => {
    expect(worksFromBuild(green).value).toBe(1);
  });
});

// A run that did the work and never checked it is the failure mode a green verdict
// hides best: one of today's Claude Code runs reported success over a red test suite.
// The case says when checking is part of the job - a generation task has nothing to
// run - so the criterion is asked only where the case asked for it.
describe("agentLogicFromVerification", () => {
  it("is not asked when the case does not require checking", () => {
    expect(agentLogicFromVerification(false, false)).toBeNull();
    expect(agentLogicFromVerification(null, false)).toBeNull();
  });

  it("passes a run that ran the build or the tests after writing", () => {
    expect(agentLogicFromVerification(true, true)?.value).toBe(1);
  });

  it("marks down a run that finished without checking its own work", () => {
    const r = agentLogicFromVerification(false, true);
    expect(r?.value).toBe(0.5);
    expect(r?.rationale).toMatch(/never|check/i);
  });

  // No action log means the question was never answerable, which is not a fault.
  it("leaves it unmeasured when the run recorded nothing", () => {
    expect(agentLogicFromVerification(null, true)).toBeNull();
  });
});

describe("agentLogicFromRun with a case that requires self-verification", () => {
  const clean = { writes: 2, toolCalls: 8, duplicateCalls: 0, toolErrors: 0, recoveredFromError: null };

  it("takes the verification miss as the worst part", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: [],
      loop: { ...clean, selfVerified: false },
      expectsSelfVerification: true,
    });
    expect(r?.value).toBe(0.5);
  });

  it("leaves a clean verified run at full marks", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: [],
      loop: { ...clean, selfVerified: true },
      expectsSelfVerification: true,
    });
    expect(r?.value).toBe(1);
  });

  // The generation tasks: nobody verifies, nothing to verify, so the criterion must
  // not quietly drag every harness down by the same half point.
  it("ignores verification for a case that never asked for it", () => {
    const r = agentLogicFromRun({
      status: "SUCCESS",
      expectedToolOrder: [],
      loop: { ...clean, selfVerified: false },
    });
    expect(r?.value).toBe(1);
  });
});
