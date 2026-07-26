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
  judgeCriteria: [
    {
      id: "code_structure",
      name: "Code structure",
      description: "Test",
      scale: { values: [0, 0.5, 1] },
      weight: 0.25,
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
      judgeScores: [],
      runAt: "2026-04-15T09:00:00.000Z",
      createdAt: "2026-04-15T09:00:00.000Z",
    },
  ],
  stability: [],
  inbox: [],
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

  it("accepts a judge score set using a human and a judge-only criterion", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          judgeScores: [
            {
              judgeId: "claude-code",
              judgeModel: "claude-fable-5",
              judgedAt: "2026-07-19T12:00:00.000Z",
              scores: [
                { criterionId: "compliance", value: 1 },
                { criterionId: "code_structure", value: 0.5, rationale: "mediocre" },
              ],
              screenshots: [],
              consoleErrors: [],
              error: null,
            },
          ],
        },
      ],
    };
    expect(validateReferentialIntegrity(baseTasks, results)).toEqual([]);
  });

  it("reports a judge score value outside the criterion scale", () => {
    const results: ResultsFile = {
      ...baseResults,
      results: [
        {
          ...baseResults.results[0],
          judgeScores: [
            {
              judgeId: "codex",
              judgeModel: "gpt-5.1-codex",
              judgedAt: "2026-07-19T12:00:00.000Z",
              scores: [{ criterionId: "code_structure", value: 0.75 }],
              screenshots: [],
              consoleErrors: [],
            },
          ],
        },
      ],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain("not in scale");
  });

  it("reports duplicate judgeId on one result", () => {
    const set = {
      judgeId: "claude-code",
      judgeModel: "claude-fable-5",
      judgedAt: "2026-07-19T12:00:00.000Z",
      scores: [{ criterionId: "compliance", value: 1 }],
      screenshots: [],
      consoleErrors: [],
    };
    const results: ResultsFile = {
      ...baseResults,
      results: [{ ...baseResults.results[0], judgeScores: [set, { ...set }] }],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('duplicate judgeId "claude-code"');
  });

  it("reports a stability entry referencing an unknown resultId", () => {
    const results: ResultsFile = {
      ...baseResults,
      stability: [
        {
          taskId: "snake",
          modelId: "qwen3.5:9b",
          environmentId: "dgx-local",
          resultIds: ["r1", "does-not-exist"],
          deterministic: { scoreVariance: 0.1, codeSimilarity: 0.8 },
          judges: [],
          computedAt: "2026-07-19T12:00:00.000Z",
        },
      ],
    };
    const errors = validateReferentialIntegrity(baseTasks, results);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('unknown resultId "does-not-exist"');
  });
});
