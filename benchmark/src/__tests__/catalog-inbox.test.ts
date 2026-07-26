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
    toolCalls: ["advance_code_editing"],
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
    });

    expect(entry.id).toBe("todo__ollama-qwen3.6-35b__1");
    expect(entry.taskId).toBe("todo");
    expect(entry.durationMs).toBe(84210);
    expect(entry.costUsd).toBe(0.02);
    expect(entry.judgeScores).toHaveLength(1);
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
      toolCalls: ["advance_code_editing"],
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
  });
  return {
    version: 1,
    models: [{ id: "ollama/qwen3.6:35b", name: "Qwen", provider: "ollama" }],
    environments: [{ id: "local", name: "local", type: "local" }],
    results: [],
    stability: [],
    inbox: [entry],
  };
}

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
