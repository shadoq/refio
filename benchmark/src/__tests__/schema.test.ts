// @vitest-environment node
import { describe, it, expect } from "vitest";
import { TasksFileSchema, CriterionSchema } from "@/schema/tasks";
import { ResultsFileSchema, ResultSchema } from "@/schema/results";

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
