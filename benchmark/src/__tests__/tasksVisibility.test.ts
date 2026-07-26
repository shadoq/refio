// @vitest-environment node
import { describe, it, expect } from "vitest";
import { visibleTasks, excludeHiddenResults, leaderboard } from "@/lib/stats";
import type { Task, TasksFile } from "@/schema/tasks";
import type { Result, ResultsFile } from "@/schema/results";

const makeTask = (id: string, hidden?: boolean): Task => ({
  id,
  name: id,
  description: "",
  systemPrompt: "",
  extraCriteria: [],
  createdAt: "2026-04-01T10:00:00.000Z",
  updatedAt: "2026-04-01T10:00:00.000Z",
  ...(hidden === undefined ? {} : { hidden }),
});

const coreCriteria: TasksFile["coreCriteria"] = [
  { id: "compliance", name: "Compliance", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
  { id: "works_out_of_box", name: "Works", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
];

const makeResult = (id: string, modelId: string, taskId: string): Result => ({
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
});

describe("visibleTasks", () => {
  it("drops tasks explicitly flagged hidden and keeps unflagged ones", () => {
    const tasks = [makeTask("snake"), makeTask("secret", true), makeTask("todo", false)];
    expect(visibleTasks(tasks).map((t) => t.id)).toEqual(["snake", "todo"]);
  });
});

describe("excludeHiddenResults", () => {
  const tasksFile: TasksFile = {
    version: 1,
    coreCriteria,
    judgeCriteria: [],
    tasks: [makeTask("snake"), makeTask("secret", true)],
  };

  it("removes results that belong to a hidden task", () => {
    const results = [makeResult("r1", "m1", "snake"), makeResult("r2", "m1", "secret")];
    expect(excludeHiddenResults(results, tasksFile).map((r) => r.id)).toEqual(["r1"]);
  });

  it("keeps a result whose task is unknown - only known-hidden tasks are excluded", () => {
    const results = [makeResult("r3", "m1", "ghost")];
    expect(excludeHiddenResults(results, tasksFile).map((r) => r.id)).toEqual(["r3"]);
  });
});

describe("leaderboard excludes hidden tasks from measurements", () => {
  const tasksFile: TasksFile = {
    version: 1,
    coreCriteria,
    judgeCriteria: [],
    tasks: [makeTask("snake"), makeTask("secret", true)],
  };
  const resultsFile: ResultsFile = {
    version: 1,
    models: [{ id: "m1", name: "M1", provider: "ollama" }],
    environments: [{ id: "local", name: "Local", type: "local" }],
    results: [makeResult("r1", "m1", "snake"), makeResult("r2", "m1", "secret")],
    stability: [],
    inbox: [],
  };

  it("does not count a hidden task's results toward a model's row", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    expect(rows).toHaveLength(1);
    expect(rows[0].attemptCount).toBe(1);
    expect(rows[0].tasksEvaluated).toBe(1);
  });

  it("drops a model whose only results are for hidden tasks", () => {
    const onlyHidden: ResultsFile = { ...resultsFile, results: [makeResult("r2", "m1", "secret")] };
    expect(leaderboard(onlyHidden.results, onlyHidden, tasksFile)).toHaveLength(0);
  });
});
