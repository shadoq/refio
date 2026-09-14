// @vitest-environment node
import { describe, it, expect } from "vitest";
import { TasksFileSchema, CriterionSchema } from "@/schema/tasks";
import {
  ResultsFileSchema,
  ResultSchema,
  HarnessSchema,
  InboxEntrySchema,
  StabilityEntrySchema,
} from "@/schema/results";

const validCriterion = {
  id: "compliance",
  name: "Compliance",
  description: "Test",
  scale: { values: [0, 0.5, 1] },
};

const validTask = {
  id: "snake",
  name: "Snake game",
  description: "Build snake",
  systemPrompt: "Build snake now",
  createdAt: "2026-04-01T10:00:00.000Z",
  updatedAt: "2026-04-01T10:00:00.000Z",
};

const validTasksFile = {
  version: 1 as const,
  coreCriteria: [validCriterion],
  tasks: [validTask],
};

describe("TasksFileSchema", () => {
  it("parses valid tasks file", () => {
    const result = TasksFileSchema.safeParse(validTasksFile);
    expect(result.success).toBe(true);
  });

  it("rejects invalid version", () => {
    const result = TasksFileSchema.safeParse({ ...validTasksFile, version: 2 });
    expect(result.success).toBe(false);
  });

  it("rejects empty coreCriteria", () => {
    const result = TasksFileSchema.safeParse({ ...validTasksFile, coreCriteria: [] });
    expect(result.success).toBe(false);
  });

  it("rejects criterion id with uppercase letters", () => {
    const result = CriterionSchema.safeParse({ ...validCriterion, id: "BadId" });
    expect(result.success).toBe(false);
  });

  it("applies default weight of 1.0", () => {
    const result = CriterionSchema.safeParse(validCriterion);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.weight).toBe(1.0);
  });

  it("applies default empty extraCriteria on task", () => {
    const result = TasksFileSchema.safeParse(validTasksFile);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.tasks[0].extraCriteria).toEqual([]);
  });
});

const validModel = { id: "qwen3.5:9b", name: "Qwen", provider: "ollama" };
const validEnv = { id: "dgx-local", name: "DGX", type: "local" as const };
const validResult = {
  id: "r1",
  taskId: "snake",
  modelId: "qwen3.5:9b",
  environmentId: "dgx-local",
  attemptNumber: 1,
  scores: [{ criterionId: "compliance", value: 1 }],
  runAt: "2026-04-15T09:00:00.000Z",
  createdAt: "2026-04-15T09:00:00.000Z",
};

describe("ResultsFileSchema", () => {
  it("parses valid results file", () => {
    const result = ResultsFileSchema.safeParse({
      version: 1,
      models: [validModel],
      environments: [validEnv],
      results: [validResult],
    });
    expect(result.success).toBe(true);
  });

  it("rejects environment type other than local/cloud", () => {
    const result = ResultsFileSchema.safeParse({
      version: 1,
      models: [validModel],
      environments: [{ ...validEnv, type: "hybrid" }],
      results: [],
    });
    expect(result.success).toBe(false);
  });

  it("rejects result with zero scores", () => {
    const result = ResultSchema.safeParse({ ...validResult, scores: [] });
    expect(result.success).toBe(false);
  });

  it("applies default empty attachments", () => {
    const result = ResultSchema.safeParse(validResult);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.attachments).toEqual([]);
  });

  it("accepts archive attachments for downloadable task outputs", () => {
    const result = ResultSchema.safeParse({
      ...validResult,
      attachments: [
        {
          type: "archive",
          src: "attachments/r1/ollama-qwen-proba-1-1.zip",
        },
      ],
    });
    expect(result.success).toBe(true);
  });

  it("rejects negative costUsd", () => {
    const result = ResultSchema.safeParse({ ...validResult, costUsd: -1 });
    expect(result.success).toBe(false);
  });
});

// The harness is what drove the agent: Refio itself, or an external coding agent
// such as Claude Code or Codex. Every result recorded before the dimension existed
// was produced by Refio, so an absent harnessId must mean exactly that - otherwise
// the whole historical data set would have to be rewritten to stay loadable.
describe("harness dimension", () => {
  const validHarness = { id: "claude-code", name: "Claude Code", kind: "external" as const };

  it("defaults a result with no harnessId to refio", () => {
    const result = ResultSchema.safeParse(validResult);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.harnessId).toBe("refio");
  });

  it("keeps an explicitly recorded harness", () => {
    const result = ResultSchema.safeParse({ ...validResult, harnessId: "claude-code" });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.harnessId).toBe("claude-code");
  });

  it("defaults the harness registry to an empty array", () => {
    const result = ResultsFileSchema.safeParse({
      version: 1,
      models: [validModel],
      environments: [validEnv],
      results: [validResult],
    });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.harnesses).toEqual([]);
  });

  it("accepts a harness registry entry", () => {
    const result = HarnessSchema.safeParse(validHarness);
    expect(result.success).toBe(true);
  });

  it("records the run conditions that make a harness comparison readable", () => {
    const result = HarnessSchema.safeParse({
      ...validHarness,
      version: "2.1.0",
      conditions: "network on, acceptEdits, max 30 turns",
    });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.conditions).toContain("network on");
  });

  it("rejects a harness kind outside refio/external", () => {
    const result = HarnessSchema.safeParse({ ...validHarness, kind: "hybrid" });
    expect(result.success).toBe(false);
  });

  it("defaults a stability entry with no harnessId to refio", () => {
    const result = StabilityEntrySchema.safeParse({
      taskId: "snake",
      modelId: "qwen3.5:9b",
      environmentId: "dgx-local",
      resultIds: ["r1", "r2"],
      deterministic: { scoreVariance: 0.1, codeSimilarity: 0.5 },
      computedAt: "2026-04-15T09:00:00.000Z",
    });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.harnessId).toBe("refio");
  });
});

// The run trace is what makes two harnesses comparable beyond the score: how many
// turns, how many tool calls, how soon the first write happened. It is optional, so
// every row recorded before it existed stays valid.
describe("run trace summary", () => {
  const validTrace = {
    format: "refio-trace/1" as const,
    source: "claude-stream-json" as const,
    path: "attachments/r1/_trace/trace.jsonl",
    rawPath: "attachments/r1/_trace/raw.log",
    turns: 4,
    endReason: "completed" as const,
    toolCalls: 9,
    reads: 3,
    writes: 2,
    shellRuns: 1,
    searches: 3,
    otherCalls: 0,
    toolErrors: 1,
    nonZeroExits: 0,
    duplicateCalls: 1,
    repeatedCallStreak: 2,
    repeatedFailedCallStreak: 0,
    recoveredFromError: true,
    readsBeforeFirstWrite: 3,
    searchesBeforeFirstWrite: 3,
    filesWritten: 1,
    firstWriteAtCall: 4,
    editsAfterFirstWrite: 1,
    timeToFirstWriteMs: 42000,
    selfVerified: true,
    toolHistogram: { Read: 3, Write: 2 },
  };

  it("parses a result recorded before traces existed", () => {
    const result = ResultSchema.safeParse(validResult);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.trace).toBeUndefined();
  });

  it("parses a result carrying a full trace summary", () => {
    const result = ResultSchema.safeParse({ ...validResult, trace: validTrace });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.trace?.toolCalls).toBe(9);
  });

  it("parses an inbox entry carrying a trace summary", () => {
    const result = InboxEntrySchema.safeParse({
      id: "e1",
      taskId: "snake",
      modelId: "qwen3.5:9b",
      environmentId: "dgx-local",
      attemptNumber: 1,
      runAt: "2026-04-15T09:00:00.000Z",
      createdAt: "2026-04-15T09:00:00.000Z",
      trace: validTrace,
    });
    expect(result.success).toBe(true);
  });

  it("rejects a trace written in an unknown format", () => {
    const result = ResultSchema.safeParse({
      ...validResult,
      trace: { ...validTrace, format: "x" },
    });
    expect(result.success).toBe(false);
  });

  it("accepts a run that never wrote a file", () => {
    const result = ResultSchema.safeParse({
      ...validResult,
      trace: { ...validTrace, firstWriteAtCall: null, timeToFirstWriteMs: null, writes: 0 },
    });
    expect(result.success).toBe(true);
  });
});

// A thinking model with reasoning switched off is a different worker from the same
// model with it on, so a result that does not say which one it was cannot be compared
// with anything.
describe("thinking mode on a result", () => {
  const thinking = { requested: "on" as const, level: "default", observed: true, tokens: 5342 };

  it("parses a result that records the thinking mode", () => {
    const result = ResultSchema.safeParse({ ...validResult, thinking });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.thinking?.observed).toBe(true);
  });

  it("parses an inbox entry that records it", () => {
    const result = InboxEntrySchema.safeParse({
      id: "e1",
      taskId: "snake",
      modelId: "qwen3.5:9b",
      environmentId: "dgx-local",
      attemptNumber: 1,
      runAt: "2026-04-15T09:00:00.000Z",
      createdAt: "2026-04-15T09:00:00.000Z",
      thinking,
    });
    expect(result.success).toBe(true);
  });

  it("keeps a run recorded before the dimension existed valid", () => {
    const result = ResultSchema.safeParse(validResult);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.thinking).toBeUndefined();
  });

  it("rejects a thinking mode outside on/off/unknown", () => {
    const result = ResultSchema.safeParse({
      ...validResult,
      thinking: { ...thinking, requested: "maybe" },
    });
    expect(result.success).toBe(false);
  });
});
