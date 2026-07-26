// @vitest-environment node
import { describe, it, expect } from "vitest";
import { excludeUncountedResults, leaderboard } from "@/lib/stats";
import type { Task, TasksFile } from "@/schema/tasks";
import type { Result, ResultsFile } from "@/schema/results";

const makeTask = (id: string): Task => ({
  id,
  name: id,
  description: "",
  systemPrompt: "",
  extraCriteria: [],
  createdAt: "2026-04-01T10:00:00.000Z",
  updatedAt: "2026-04-01T10:00:00.000Z",
});

const coreCriteria: TasksFile["coreCriteria"] = [
  { id: "compliance", name: "Compliance", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
  { id: "works_out_of_box", name: "Works", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
];

const makeResult = (id: string, modelId: string, taskId: string, extra: Partial<Result> = {}): Result => ({
  id,
  taskId,
  modelId,
  environmentId: "local",
  attemptNumber: 1,
  scores: [
    { criterionId: "compliance", value: 1 },
    { criterionId: "works_out_of_box", value: 1 },
  ],
  attachments: [],
  judgeScores: [],
  runAt: "2026-04-15T09:00:00.000Z",
  createdAt: "2026-04-15T09:00:00.000Z",
  ...extra,
});

describe("excludeUncountedResults", () => {
  it("drops results explicitly flagged excludeFromStats and keeps everything else", () => {
    const results = [
      makeResult("kept", "m1", "snake"),
      makeResult("failed", "m1", "snake", { excludeFromStats: true }),
      makeResult("counted", "m1", "snake", { excludeFromStats: false }),
    ];
    expect(excludeUncountedResults(results).map((r) => r.id)).toEqual(["kept", "counted"]);
  });
});

describe("leaderboard ignores results flagged excludeFromStats", () => {
  const tasksFile: TasksFile = {
    version: 1,
    coreCriteria,
    judgeCriteria: [],
    tasks: [makeTask("snake")],
  };
  // A model with one good run and one failed run whose result could not be established:
  // all-zero scores, a real cloud cost, and the exclusion flag. It must not drag the average.
  const resultsFile: ResultsFile = {
    version: 1,
    models: [{ id: "m1", name: "M1", provider: "ollama" }],
    environments: [{ id: "local", name: "Local", type: "local" }],
    results: [
      makeResult("ok1", "m1", "snake", { costUsd: 0.02 }),
      makeResult("failed", "m1", "snake", {
        attemptNumber: 2,
        excludeFromStats: true,
        costUsd: 0.5,
        scores: [
          { criterionId: "compliance", value: 0 },
          { criterionId: "works_out_of_box", value: 0 },
        ],
      }),
    ],
    stability: [],
    inbox: [],
  };

  it("excludes the flagged run from score, attempt count, pass rate and cost", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    expect(rows).toHaveLength(1);
    const row = rows[0];
    expect(row.attemptCount).toBe(1); // only the counted run
    expect(row.avgScore).toBeCloseTo(1); // not dragged down by the all-zero failed run
    expect(row.passRate).toBeCloseTo(1);
    expect(row.totalCostUsd).toBeCloseTo(0.02); // the failed run's 0.5 cost is not summed
  });

  it("drops a model whose only results are flagged excludeFromStats", () => {
    const onlyFlagged: ResultsFile = {
      ...resultsFile,
      results: [makeResult("f", "m1", "snake", { excludeFromStats: true })],
    };
    expect(leaderboard(onlyFlagged.results, onlyFlagged, tasksFile)).toHaveLength(0);
  });
});
