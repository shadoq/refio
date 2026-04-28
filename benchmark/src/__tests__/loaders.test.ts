// @vitest-environment node
import { describe, it, expect } from "vitest";
import { validateReferentialIntegrity } from "@/data/loaders";
import type { TasksFile } from "@/schema/tasks";
import type { ResultsFile } from "@/schema/results";

const baseTasks: TasksFile = {
  version: 1,
  coreCriteria: [
    {
      id: "compliance",
      name: "Compliance",
      description: "Test",
      scale: { values: [0, 0.5, 1] },
      weight: 1.0,
    },
  ],
  tasks: [
    {
      id: "snake",
      name: "Snake",
      description: "desc",
      systemPrompt: "prompt",
      extraCriteria: [],
      createdAt: "2026-04-01T10:00:00.000Z",
      updatedAt: "2026-04-01T10:00:00.000Z",
    },
  ],
};

const baseResults: ResultsFile = {
  version: 1,
  models: [{ id: "qwen3.5:9b", name: "Qwen", provider: "ollama" }],
  environments: [{ id: "dgx-local", name: "DGX", type: "local" }],
  results: [
    {
      id: "r1",
      taskId: "snake",
      modelId: "qwen3.5:9b",
      environmentId: "dgx-local",
      attemptNumber: 1,
      scores: [{ criterionId: "compliance", value: 1 }],
      attachments: [],
      runAt: "2026-04-15T09:00:00.000Z",
      createdAt: "2026-04-15T09:00:00.000Z",
    },
  ],
};

describe("validateReferentialIntegrity", () => {
  it("returns no errors for valid data", () => {
    expect(validateReferentialIntegrity(baseTasks, baseResults)).toEqual([]);
  });

  it("reports unknown taskId", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [{ ...baseResults.results[0], taskId: "nonexistent" }],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('unknown taskId "nonexistent"');
  });

  it("reports unknown modelId", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [{ ...baseResults.results[0], modelId: "unknown-model" }],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('unknown modelId "unknown-model"');
  });

  it("reports unknown environmentId", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [{ ...baseResults.results[0], environmentId: "unknown-env" }],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('unknown environmentId "unknown-env"');
  });

  it("reports unknown criterionId", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          scores: [{ criterionId: "no-such-criterion", value: 1 }],
        },
      ],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('unknown criterionId "no-such-criterion"');
  });

  it("accepts extraCriteria criterionIds from the task", () => {
    const tasks: TasksFile = {
      ...baseTasks,
      tasks: [
        {
          ...baseTasks.tasks[0],
          extraCriteria: [
            {
              id: "persistence",
              name: "Persistence",
              description: "test",
              scale: { values: [0, 1] },
              weight: 1.0,
            },
          ],
        },
      ],
    };
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          scores: [
            { criterionId: "compliance", value: 1 },
            { criterionId: "persistence", value: 1 },
          ],
        },
      ],
    };
    expect(validateReferentialIntegrity(tasks, results)).toEqual([]);
  });

  it("collects multiple errors", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          modelId: "bad-model",
          environmentId: "bad-env",
        },
      ],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(2);
    expect(errors).toEqual(
      expect.arrayContaining([
        expect.stringContaining('unknown modelId "bad-model"'),
        expect.stringContaining('unknown environmentId "bad-env"'),
      ]),
    );
  });

  it("validates score value against scale", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          scores: [{ criterionId: "compliance", value: 0.75 }],
        },
      ],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain("not in scale");
  });

  it("accepts score value that is in scale", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          scores: [{ criterionId: "compliance", value: 0.5 }],
        },
      ],
    };
    expect(validateReferentialIntegrity(baseTasks, results)).toEqual([]);
  });
});
