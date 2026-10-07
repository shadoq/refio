// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  collectRuns,
  passMatrix,
  summarizeByModel,
  summarizeByTask,
} from "@/lib/overview";
import type { ResultsFile } from "@/schema/results";

const base = {
  environmentId: "cloud",
  harnessId: "refio",
  attachments: [],
  runAt: "2026-09-29T10:00:00.000Z",
  createdAt: "2026-09-29T10:00:00.000Z",
};

// One reviewed run (human scores) and queue runs judged only by the automatic verdict.
const file = {
  version: 1,
  models: [
    { id: "a/fast", name: "Fast", provider: "a" },
    { id: "b/slow", name: "Slow", provider: "b" },
  ],
  environments: [],
  harnesses: [],
  stability: [],
  results: [
    {
      ...base,
      id: "snake__a-fast__1",
      taskId: "snake",
      modelId: "a/fast",
      attemptNumber: 1,
      scores: [
        { criterionId: "compliance", value: 4 },
        { criterionId: "works_out_of_box", value: 4 },
        { criterionId: "agent_logic", value: 4 },
      ],
      judgeScores: [],
      costUsd: 0.1,
      durationMs: 60_000,
      thinking: { requested: "off", level: "LOW", observed: false },
    },
  ],
  inbox: [
    {
      ...base,
      id: "snake__a-fast__2",
      taskId: "snake",
      modelId: "a/fast",
      attemptNumber: 2,
      judgeScores: [
        {
          judgeId: "e2e-deterministic",
          judgeModel: "refio-cli",
          judgedAt: base.runAt,
          scores: [
            { criterionId: "works_out_of_box", value: 4 },
            { criterionId: "agent_logic", value: 0 },
          ],
          screenshots: [],
          consoleErrors: [],
          error: null,
        },
      ],
      autoVerdict: { verdict: "FAIL", reasons: [] },
      costUsd: 0.3,
      durationMs: 120_000,
      thinking: { requested: "off", level: "LOW", observed: false },
    },
    {
      ...base,
      id: "todo__b-slow__1",
      taskId: "todo",
      modelId: "b/slow",
      attemptNumber: 1,
      judgeScores: [],
      autoVerdict: { verdict: "PASS", reasons: [] },
      costUsd: 2,
      durationMs: 600_000,
    },
  ],
} as unknown as ResultsFile;

describe("overview", () => {
  // Reviewed runs and queue runs answer the same question, so both must be countable
  // together, while either one alone must still be selectable.
  it("counts reviewed and queue runs together and each source on its own", () => {
    expect(collectRuns(file, ["reviewed", "queue"])).toHaveLength(3);
    expect(collectRuns(file, ["reviewed"]).map((r) => r.source)).toEqual(["reviewed"]);
    expect(collectRuns(file, ["queue"])).toHaveLength(2);
  });

  it("does not count a promoted run twice when it is still in the queue", () => {
    const dup = { ...file, inbox: [...file.inbox, { ...file.inbox[0], id: "snake__a-fast__1" }] };
    expect(collectRuns(dup as ResultsFile, ["reviewed", "queue"])).toHaveLength(3);
  });

  it("summarizes a model by passes, full scores, total cost and average time", () => {
    const fast = summarizeByModel(collectRuns(file, ["reviewed", "queue"]), file).find(
      (m) => m.modelId === "a/fast",
    )!;
    expect(fast).toMatchObject({
      modelId: "a/fast",
      name: "Fast",
      attempts: 2,
      passed: 1,
      worksFull: 2,
      agentLogicFull: 1,
      reasoning: "low",
    });
    expect(fast.costUsd).toBeCloseTo(0.4);
    expect(fast.avgDurationMs).toBe(90_000);
  });

  // Ranking follows the pass rate first; a model that passes everything must not sink
  // below one with more attempts but a lower rate.
  it("ranks by pass rate, then by cost", () => {
    const names = summarizeByModel(collectRuns(file, ["reviewed", "queue"]), file).map((m) => m.name);
    expect(names).toEqual(["Slow", "Fast"]);
  });

  it("builds a model by task matrix of passes over attempts", () => {
    const rows = passMatrix(collectRuns(file, ["reviewed", "queue"]), file);
    const fast = rows.find((r) => r.modelId === "a/fast")!;
    expect(fast.cells.snake).toEqual({ passed: 1, attempts: 2 });
    expect(fast.cells.todo).toBeUndefined();
  });

  it("summarizes a task across models, naming who passed every attempt", () => {
    const tasks = summarizeByTask(collectRuns(file, ["reviewed", "queue"]), file);
    const snake = tasks.find((t) => t.taskId === "snake")!;
    expect(snake).toMatchObject({ attempts: 2, passed: 1, models: 1, perfectModels: [] });
    const todo = tasks.find((t) => t.taskId === "todo")!;
    expect(todo.perfectModels).toEqual(["Slow"]);
  });
});
