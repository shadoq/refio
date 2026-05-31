// @vitest-environment node
import { describe, it, expect } from "vitest";
import { normalizeScore, normalizeResult, leaderboard } from "@/lib/stats";
import type { TasksFile } from "@/schema/tasks";
import type { ResultsFile, Result } from "@/schema/results";

const coreCriteria: TasksFile["coreCriteria"] = [
  {
    id: "compliance",
    name: "Compliance",
    description: "",
    scale: { values: [0, 0.5, 1] },
    weight: 1.0,
  },
  {
    id: "works_out_of_box",
    name: "Works",
    description: "",
    scale: { values: [0, 0.5, 1] },
    weight: 1.0,
  },
  {
    id: "look",
    name: "Look",
    description: "",
    scale: { values: [0, 0.5, 1, 1.5, 2] },
    weight: 1.0,
  },
];

const makeTask = (id: string): TasksFile["tasks"][0] => ({
  id,
  name: id,
  description: "",
  systemPrompt: "",
  extraCriteria: [],
  createdAt: "2026-04-01T10:00:00.000Z",
  updatedAt: "2026-04-01T10:00:00.000Z",
});

const tasksFile: TasksFile = {
  version: 1,
  coreCriteria,
  tasks: [makeTask("snake"), makeTask("todo")],
};

const makeResult = (
  id: string,
  modelId: string,
  envId: string,
  taskId: string,
  scores: Array<{ criterionId: string; value: number }>,
  extra: Partial<Result> = {},
): Result => ({
  id,
  taskId,
  modelId,
  environmentId: envId,
  attemptNumber: 1,
  scores,
  attachments: [],
  runAt: "2026-04-15T09:00:00.000Z",
  createdAt: "2026-04-15T09:00:00.000Z",
  ...extra,
});

describe("normalizeScore", () => {
  it("returns 1.0 for max value", () => {
    expect(normalizeScore(2, [0, 0.5, 1, 1.5, 2])).toBeCloseTo(1.0);
  });

  it("returns 0.0 for min value", () => {
    expect(normalizeScore(0, [0, 0.5, 1, 1.5, 2])).toBeCloseTo(0.0);
  });

  it("returns 0.5 for mid value on [0,1] scale", () => {
    expect(normalizeScore(0.5, [0, 0.5, 1])).toBeCloseTo(0.5);
  });

  it("returns 0.25 for 0.5 on [0,2] effective scale", () => {
    expect(normalizeScore(0.5, [0, 0.5, 1, 1.5, 2])).toBeCloseTo(0.25);
  });
});

describe("normalizeResult", () => {
  it("computes average of normalized scores", () => {
    const result = makeResult("r1", "m1", "e1", "snake", [
      { criterionId: "compliance", value: 1 },
      { criterionId: "look", value: 2 },
    ]);
    expect(normalizeResult(result, tasksFile)).toBeCloseTo(1.0);
  });

  it("handles partial scores", () => {
    const result = makeResult("r1", "m1", "e1", "snake", [
      { criterionId: "compliance", value: 0.5 },
      { criterionId: "look", value: 1 },
    ]);
    expect(normalizeResult(result, tasksFile)).toBeCloseTo(0.5);
  });

  it("returns 0 for all-zero scores", () => {
    const result = makeResult("r1", "m1", "e1", "snake", [
      { criterionId: "compliance", value: 0 },
      { criterionId: "look", value: 0 },
    ]);
    expect(normalizeResult(result, tasksFile)).toBeCloseTo(0);
  });

  it("ignores unknown criterionId gracefully", () => {
    const result = makeResult("r1", "m1", "e1", "snake", [
      { criterionId: "compliance", value: 1 },
      { criterionId: "unknown-crit", value: 99 },
    ]);
    expect(normalizeResult(result, tasksFile)).toBeCloseTo(1.0);
  });

  it("uses criterion weights when averaging scores", () => {
    const weightedTasksFile: TasksFile = {
      ...tasksFile,
      coreCriteria: [
        { ...coreCriteria[0], weight: 1 },
        { ...coreCriteria[1], weight: 0.25 },
      ],
    };
    const result = makeResult("r1", "m1", "e1", "snake", [
      { criterionId: "compliance", value: 1 },
      { criterionId: "works_out_of_box", value: 0 },
    ]);

    expect(normalizeResult(result, weightedTasksFile)).toBeCloseTo(0.8);
  });
});

describe("leaderboard", () => {
  const resultsFile: ResultsFile = {
    version: 1,
    models: [
      { id: "qwen", name: "Qwen", provider: "ollama" },
      { id: "claude", name: "Claude", provider: "anthropic" },
    ],
    environments: [
      { id: "local", name: "Local", type: "local" },
      { id: "cloud", name: "Cloud", type: "cloud" },
    ],
    results: [
      makeResult(
        "r1",
        "qwen",
        "local",
        "snake",
        [
          { criterionId: "compliance", value: 1 },
          { criterionId: "works_out_of_box", value: 1 },
          { criterionId: "look", value: 1 },
        ],
        { durationMs: 40000, tokensIn: 100, tokensOut: 200 },
      ),
      makeResult(
        "r2",
        "qwen",
        "local",
        "snake",
        [
          { criterionId: "compliance", value: 0.5 },
          { criterionId: "works_out_of_box", value: 0.5 },
          { criterionId: "look", value: 0.5 },
        ],
        { attemptNumber: 2, durationMs: 50000, tokensIn: 120, tokensOut: 220 },
      ),
      makeResult(
        "r3",
        "claude",
        "cloud",
        "snake",
        [
          { criterionId: "compliance", value: 1 },
          { criterionId: "works_out_of_box", value: 1 },
          { criterionId: "look", value: 2 },
        ],
        { costUsd: 0.02, durationMs: 15000, tokensIn: 80, tokensOut: 180 },
      ),
      makeResult(
        "r4",
        "claude",
        "cloud",
        "todo",
        [
          { criterionId: "compliance", value: 1 },
          { criterionId: "works_out_of_box", value: 1 },
          { criterionId: "look", value: 2 },
        ],
        { attemptNumber: 2, costUsd: 0.04, durationMs: 17000, tokensIn: 90, tokensOut: 190 },
      ),
    ],
  };

  it("returns one row per (modelId, environmentId) pair", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    expect(rows).toHaveLength(2);
  });

  it("sorts by avgScore descending", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    expect(rows[0].modelId).toBe("claude");
    expect(rows[1].modelId).toBe("qwen");
  });

  it("breaks avgScore ties by works-out-of-box, compliance, then first-shot", () => {
    const tiedResultsFile: ResultsFile = {
      ...resultsFile,
      models: [
        { id: "steady", name: "Steady", provider: "ollama" },
        { id: "flashy", name: "Flashy", provider: "ollama" },
      ],
      results: [
        makeResult("steady-1", "steady", "local", "snake", [
          { criterionId: "compliance", value: 0.5 },
          { criterionId: "works_out_of_box", value: 1 },
          { criterionId: "look", value: 1 },
        ]),
        makeResult("flashy-1", "flashy", "local", "snake", [
          { criterionId: "compliance", value: 1 },
          { criterionId: "works_out_of_box", value: 0.5 },
          { criterionId: "look", value: 1 },
        ]),
      ],
    };

    const rows = leaderboard(tiedResultsFile.results, tiedResultsFile, tasksFile);
    expect(rows[0].modelId).toBe("steady");
    expect(rows[0].avgScore).toBeCloseTo(rows[1].avgScore);
    expect(rows[0].avgWorksOutOfBoxScore).toBeGreaterThan(rows[1].avgWorksOutOfBoxScore!);
  });

  it("computes avgScore correctly for qwen", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    expect(qwen.avgScore).toBeCloseTo(0.625);
  });

  it("sums totalCostUsd for cloud results", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const claude = rows.find((r) => r.modelId === "claude")!;
    expect(claude.totalCostUsd).toBeCloseTo(0.06);
  });

  it("computes avgCostUsd for cloud results", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const claude = rows.find((r) => r.modelId === "claude")!;
    expect(claude.avgCostUsd).toBeCloseTo(0.03);
  });

  it("estimates prefill and decode processing from token counts", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    expect(qwen.avgEstimatedPrefillMs).toBeCloseTo(9000);
    expect(qwen.avgEstimatedDecodeMs).toBeCloseTo(36000);
    expect(qwen.avgEstimatedLlmMs).toBeCloseTo(45000);
    expect(qwen.avgPrefillTokensPerSecond).toBeCloseTo(12.25);
    expect(qwen.avgDecodeTokensPerSecond).toBeCloseTo(5.875);
  });

  it("counts distinct tasks evaluated", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    const claude = rows.find((r) => r.modelId === "claude")!;
    expect(qwen.tasksEvaluated).toBe(1);
    expect(claude.tasksEvaluated).toBe(2);
  });

  it("computes pass rate", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const claude = rows.find((r) => r.modelId === "claude")!;
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    expect(claude.passRate).toBeCloseTo(1.0);
    expect(qwen.passRate).toBeCloseTo(0.5);
  });

  it("computes first-shot success from attempt #1 works-out-of-box score", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    expect(qwen.firstShotSuccess).toBe(true);
    expect(qwen.firstShotScore).toBeCloseTo(0.833333);
  });

  it("computes reliability for repeated attempts", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    const claude = rows.find((r) => r.modelId === "claude")!;
    expect(qwen.reliabilityScore).not.toBeNull();
    expect(qwen.reliabilityScore!).toBeGreaterThan(0);
    expect(claude.reliabilityScore).toBeCloseTo(1);
  });

  it("computes local viability only for local rows", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    const qwen = rows.find((r) => r.modelId === "qwen")!;
    const claude = rows.find((r) => r.modelId === "claude")!;
    expect(qwen.localViabilityScore).not.toBeNull();
    expect(qwen.localQualityRatio).toBeCloseTo(qwen.avgScore / claude.avgScore);
    expect(claude.localViabilityScore).toBeNull();
  });
});
