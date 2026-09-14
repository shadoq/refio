// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  parseRunJson,
  makeInboxId,
  deterministicVerdict,
  buildInboxEntry,
  promoteInboxEntry,
  discardInboxEntry,
} from "@/lib/catalog/inbox";
import { InboxEntrySchema, ResultsFileSchema, type ResultsFile } from "@/schema/results";
import { buildDeterministicJudge } from "@/lib/catalog/deterministic";

const sampleRun = {
  schemaVersion: 1,
  session: { status: "SUCCESS" },
  metrics: { toolCallCount: 3, contextOverflow: false, costUsd: 0.02, durationMs: 84210, tokensOut: 5300 },
  finalOutput: "Added a null check.",
  conversation: [
    { role: "ASSISTANT", toolCalls: ["grep_search"] },
    { role: "ASSISTANT", toolCalls: ["read_file", "advance_code_editing"] },
  ],
};

describe("parseRunJson", () => {
  it("extracts status, flattened tool calls, output and metrics", () => {
    const run = parseRunJson(sampleRun);
    expect(run.status).toBe("SUCCESS");
    expect(run.toolCalls).toEqual(["grep_search", "read_file", "advance_code_editing"]);
    expect(run.finalOutput).toBe("Added a null check.");
    expect(run.metrics.durationMs).toBe(84210);
    expect(run.metrics.costUsd).toBe(0.02);
    expect(run.contextOverflow).toBe(false);
  });

  it("is defensive about a missing session/metrics", () => {
    const run = parseRunJson({});
    expect(run.status).toBe("UNKNOWN");
    expect(run.toolCalls).toEqual([]);
  });
});

describe("makeInboxId", () => {
  it("sanitizes provider slashes and colons in the model id", () => {
    expect(makeInboxId("todo", "ollama/qwen3.6:35b", 1)).toBe("todo__ollama-qwen3.6-35b__1");
  });

  // Ids name attachment folders on disk, so the Refio form must not change - the
  // existing queue and its artifacts keep working.
  it("leaves the Refio form untouched when the harness is passed explicitly", () => {
    expect(makeInboxId("todo", "ollama/qwen3.6:35b", 1, "refio")).toBe(
      "todo__ollama-qwen3.6-35b__1",
    );
  });

  // Without the harness in the id, importing the same model under Refio and under
  // Claude Code would overwrite one run with the other.
  it("separates the same model run under two harnesses", () => {
    const refio = makeInboxId("todo", "anthropic/claude-opus-5", 1, "refio");
    const external = makeInboxId("todo", "anthropic/claude-opus-5", 1, "claude-code");
    expect(external).not.toBe(refio);
    expect(external).toContain("claude-code");
  });
});

describe("deterministicVerdict", () => {
  it("is PASS when compliance is full and the artifact worked", () => {
    const v = deterministicVerdict([
      { criterionId: "compliance", value: 1 },
      { criterionId: "works_out_of_box", value: 1 },
      { criterionId: "agent_logic", value: 1 },
    ]);
    expect(v.verdict).toBe("PASS");
  });
  it("is FAIL when compliance is partial", () => {
    const v = deterministicVerdict([
      { criterionId: "compliance", value: 0.5 },
      { criterionId: "works_out_of_box", value: 1 },
      { criterionId: "agent_logic", value: 1 },
    ]);
    expect(v.verdict).toBe("FAIL");
    expect(v.reasons.length).toBeGreaterThan(0);
  });
});

describe("buildInboxEntry", () => {
  const judge = buildDeterministicJudge({
    mode: "AGENT",
    deliverableText: "<canvas> score Game Over",
    finalOutput: "done",
    needles: [{ regex: "<canvas" }],
    needleInOutput: null,
    classOrder: ["write"],
    expectedToolOrder: ["advance_code_editing"],
    status: "SUCCESS",
    rendered: true,
    consoleErrors: [],
    judgedAt: "2026-07-25T12:00:00.000Z",
    screenshots: ["attachments/todo__m__1/_judge/shot-full.png"],
  });

  it("produces a schema-valid inbox entry with the deterministic judge attached", () => {
    const entry = buildInboxEntry({
      caseId: "todo",
      mode: "AGENT",
      modelId: "ollama/qwen3.6:35b",
      environmentId: "dgx-local",
      attemptNumber: 1,
      run: parseRunJson(sampleRun),
      judge,
      attachments: [
        { type: "html", src: "attachments/todo__ollama-qwen3.6-35b__1/artifact.html" },
        { type: "image", src: "attachments/todo__ollama-qwen3.6-35b__1/_judge/shot-full.png" },
      ],
      autoVerdict: deterministicVerdict(judge.scores),
      now: "2026-07-25T12:00:00.000Z",
      harnessId: "refio",
    });

    expect(entry.id).toBe("todo__ollama-qwen3.6-35b__1");
    expect(entry.taskId).toBe("todo");
    expect(entry.durationMs).toBe(84210);
    expect(entry.costUsd).toBe(0.02);
    expect(entry.judgeScores).toHaveLength(1);
    expect(entry.harnessId).toBe("refio");
    // Must satisfy the strict inbox schema (no stray keys, no manual scores).
    expect(InboxEntrySchema.safeParse(entry).success).toBe(true);
  });
});

function fileWithOneInboxEntry(): ResultsFile {
  const entry = buildInboxEntry({
    caseId: "todo",
    mode: "AGENT",
    modelId: "ollama/qwen3.6:35b",
    environmentId: "local",
    attemptNumber: 1,
    run: parseRunJson(sampleRun),
    judge: buildDeterministicJudge({
      mode: "AGENT",
      deliverableText: "<canvas>",
      finalOutput: "",
      needles: [{ regex: "<canvas" }],
      needleInOutput: null,
      classOrder: ["write"],
      expectedToolOrder: [],
      status: "SUCCESS",
      rendered: true,
      consoleErrors: [],
      judgedAt: "2026-07-25T12:00:00.000Z",
      screenshots: [],
    }),
    attachments: [{ type: "html", src: "attachments/todo__x__1/artifact.html" }],
    autoVerdict: { verdict: "PASS", reasons: [] },
    now: "2026-07-25T12:00:00.000Z",
    harnessId: "refio",
  });
  return {
    version: 1,
    models: [{ id: "ollama/qwen3.6:35b", name: "Qwen", provider: "ollama" }],
    environments: [{ id: "local", name: "local", type: "local" }],
    harnesses: [{ id: "refio", name: "Refio", kind: "refio" }],
    results: [],
    stability: [],
    inbox: [entry],
  };
}

describe("buildInboxEntry under an external harness", () => {
  it("records the harness and keeps it out of the Refio id space", () => {
    const entry = buildInboxEntry({
      caseId: "todo",
      mode: "AGENT",
      modelId: "anthropic/claude-opus-5",
      environmentId: "anthropic-cloud",
      attemptNumber: 1,
      run: parseRunJson(sampleRun),
      judge: buildDeterministicJudge({
        mode: "AGENT",
        deliverableText: "<canvas>",
        finalOutput: "",
        needles: [{ regex: "<canvas" }],
        needleInOutput: null,
        classOrder: [],
        expectedToolOrder: [],
        status: "SUCCESS",
        rendered: true,
        consoleErrors: [],
        judgedAt: "2026-07-25T12:00:00.000Z",
        screenshots: [],
      }),
      attachments: [],
      autoVerdict: { verdict: "PASS", reasons: [] },
      now: "2026-07-25T12:00:00.000Z",
      harnessId: "claude-code",
    });

    expect(entry.harnessId).toBe("claude-code");
    expect(entry.id).toContain("claude-code");
    expect(InboxEntrySchema.safeParse(entry).success).toBe(true);
  });
});

describe("promoteInboxEntry", () => {
  it("moves the entry into results with the human scores and artifacts, but not judge scores (judges run later)", () => {
    const file = fileWithOneInboxEntry();
    const next = promoteInboxEntry(
      file,
      "todo__ollama-qwen3.6-35b__1",
      [
        { criterionId: "look", value: 1.5 },
        { criterionId: "code", value: 1 },
      ],
      "2026-07-26T09:00:00.000Z",
    );
    expect(next.inbox).toHaveLength(0);
    expect(next.results).toHaveLength(1);
    const r = next.results[0];
    expect(r.id).toBe("todo__ollama-qwen3.6-35b__1");
    expect(r.scores.map((s) => s.criterionId)).toEqual(["look", "code"]);
    expect(r.judgeScores).toEqual([]); // judges are run later during scoring, never copied on promotion
    expect(r.attachments).toHaveLength(1);
    expect(r.durationMs).toBe(84210);
    // The promoted file must still be schema-valid (results now require >=1 score).
    expect(ResultsFileSchema.safeParse(next).success).toBe(true);
  });

  // A promoted run that loses its harness would land in the main table as if Refio
  // had produced it.
  it("carries the harness from the queue entry into the result", () => {
    const file = fileWithOneInboxEntry();
    file.inbox[0].harnessId = "claude-code";
    const next = promoteInboxEntry(
      file,
      "todo__ollama-qwen3.6-35b__1",
      [{ criterionId: "look", value: 1.5 }],
      "2026-07-26T09:00:00.000Z",
    );
    expect(next.results[0].harnessId).toBe("claude-code");
  });

  it("throws when the entry id is unknown", () => {
    expect(() =>
      promoteInboxEntry(fileWithOneInboxEntry(), "nope", [{ criterionId: "look", value: 1 }], "now"),
    ).toThrow();
  });
});

describe("discardInboxEntry", () => {
  it("removes the entry and leaves results untouched", () => {
    const next = discardInboxEntry(fileWithOneInboxEntry(), "todo__ollama-qwen3.6-35b__1");
    expect(next.inbox).toHaveLength(0);
    expect(next.results).toHaveLength(0);
  });
});

// The trace is the whole point of running the same model under two harnesses, so it
// must survive promotion; a result that loses it can no longer be compared.
const sampleTrace = {
  format: "refio-trace/1" as const,
  source: "refio-run-json" as const,
  path: "attachments/todo__ollama-qwen3.6-35b__1/_trace/trace.jsonl",
  turns: 2,
  endReason: "completed" as const,
  toolCalls: 3,
  reads: 1,
  writes: 1,
  shellRuns: 1,
  searches: 0,
  otherCalls: 0,
  toolErrors: 0,
  nonZeroExits: 0,
  duplicateCalls: 0,
  repeatedCallStreak: 0,
  repeatedFailedCallStreak: 0,
  recoveredFromError: null,
  readsBeforeFirstWrite: 1,
  searchesBeforeFirstWrite: 0,
  filesWritten: 1,
  firstWriteAtCall: 2,
  editsAfterFirstWrite: 0,
  timeToFirstWriteMs: 1200,
  selfVerified: true,
  toolHistogram: { read_file: 1 },
};

describe("trace on the queue entry", () => {
  it("puts the trace summary on the built entry when the run produced one", () => {
    const entry = buildInboxEntry({
      caseId: "todo",
      mode: "AGENT",
      modelId: "ollama/qwen3.6:35b",
      environmentId: "local",
      harnessId: "refio",
      attemptNumber: 1,
      run: parseRunJson(sampleRun),
      judge: buildDeterministicJudge({
        mode: "AGENT",
        deliverableText: "<canvas>",
        finalOutput: "",
        needles: [],
        needleInOutput: null,
        classOrder: [],
        expectedToolOrder: [],
        status: "SUCCESS",
        rendered: true,
        consoleErrors: [],
        judgedAt: "2026-07-25T12:00:00.000Z",
        screenshots: [],
      }),
      attachments: [],
      autoVerdict: { verdict: "PASS", reasons: [] },
      now: "2026-07-25T12:00:00.000Z",
      trace: sampleTrace,
    });
    expect(entry.trace?.toolCalls).toBe(3);
    expect(InboxEntrySchema.safeParse(entry).success).toBe(true);
  });

  it("carries the trace from the queue entry into the promoted result", () => {
    const file = fileWithOneInboxEntry();
    file.inbox[0].trace = sampleTrace;
    const next = promoteInboxEntry(
      file,
      "todo__ollama-qwen3.6-35b__1",
      [{ criterionId: "look", value: 1 }],
      "2026-07-26T09:00:00.000Z",
    );
    expect(next.results[0].trace?.selfVerified).toBe(true);
  });

  it("leaves the result without a trace when the entry had none", () => {
    const file = fileWithOneInboxEntry();
    const next = promoteInboxEntry(
      file,
      "todo__ollama-qwen3.6-35b__1",
      [{ criterionId: "look", value: 1 }],
      "2026-07-26T09:00:00.000Z",
    );
    expect(next.results[0].trace).toBeUndefined();
  });
});

describe("thinking mode on the queue entry", () => {
  const thinking = { requested: "off" as const, observed: false };

  it("carries the thinking mode from the queue entry into the promoted result", () => {
    const file = fileWithOneInboxEntry();
    file.inbox[0].thinking = thinking;
    const next = promoteInboxEntry(
      file,
      "todo__ollama-qwen3.6-35b__1",
      [{ criterionId: "look", value: 1 }],
      "2026-07-26T09:00:00.000Z",
    );
    expect(next.results[0].thinking).toEqual(thinking);
  });
});

// The verdict says what the evidence supports. A criterion nobody could measure is
// neither a pass nor a failure, and the reasons have to say which is which.
describe("deterministicVerdict over partly measured criteria", () => {
  it("names the criteria that were not measured", () => {
    const v = deterministicVerdict([{ criterionId: "agent_logic", value: 1 }]);
    expect(v.reasons).toContain("compliance=not measured");
    expect(v.reasons).toContain("works_out_of_box=not measured");
  });

  it("does not fail a run only because a criterion could not be measured", () => {
    expect(deterministicVerdict([{ criterionId: "agent_logic", value: 1 }]).verdict).toBe("PASS");
  });

  it("cannot pass a run with nothing measured at all", () => {
    expect(deterministicVerdict([]).verdict).toBe("FAIL");
  });

  it("still fails a run whose compliance was measured and fell short", () => {
    const v = deterministicVerdict([
      { criterionId: "compliance", value: 0.5 },
      { criterionId: "agent_logic", value: 1 },
    ]);
    expect(v.verdict).toBe("FAIL");
  });
});

// What the loop said about itself. Previously parsed and thrown away, so a run that
// silently overflowed its window looked like any other run on the leaderboard.
describe("loop signals from the run document", () => {
  it("keeps the overflow flag, the failure marker and the verification result", () => {
    const run = parseRunJson({
      session: { status: "SUCCESS" },
      metrics: {
        contextOverflow: true,
        failureMarker: "NOOP_WRITE_STALL",
        verification: { ran: true, attempts: 2, result: "PASSED" },
      },
    });
    expect(run.loop.contextOverflow).toBe(true);
    expect(run.loop.failureMarker).toBe("NOOP_WRITE_STALL");
    expect(run.loop.verification).toEqual({ ran: true, attempts: 2, result: "PASSED" });
  });

  it("reads what the loop had to leave out of the prompt, once it reports it", () => {
    const run = parseRunJson({
      session: { status: "SUCCESS" },
      metrics: {
        context: { budgetTokens: 48000, usedTokens: 47200, droppedMessages: 14, drops: { CONVERSATION: 3 } },
      },
    });
    expect(run.loop.context?.droppedMessages).toBe(14);
    expect(run.loop.context?.drops).toEqual({ CONVERSATION: 3 });
  });

  it("leaves the signals empty for a run document that reports none", () => {
    expect(parseRunJson({ session: { status: "SUCCESS" } }).loop).toEqual({});
  });
});
