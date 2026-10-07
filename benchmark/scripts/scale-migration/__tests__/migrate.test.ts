// @vitest-environment node
import { describe, it, expect } from "vitest";
import { mapValue, migrateResults, migrateTasks, type RawTasks } from "../migrate";

const OLD = [0, 0.5, 1];
const OLD_LOOK = [0, 0.5, 1, 1.5, 2];

const oldTasks = (): RawTasks => ({
  version: 1,
  coreCriteria: [
    { id: "compliance", name: "Compliance", description: "old", scale: { values: OLD }, weight: 1 },
    { id: "look", name: "Look", description: "old", scale: { values: OLD_LOOK }, weight: 1 },
  ],
  judgeCriteria: [
    { id: "logic_correctness", name: "Logic", description: "old", scale: { values: OLD }, weight: 0.5 },
  ],
  tasks: [
    {
      id: "todo",
      extraCriteria: [
        { id: "persistence", name: "P", description: "keeps todos", scale: { values: OLD }, weight: 1 },
      ],
    },
  ],
});

describe("mapValue", () => {
  // The old top mark did not say good or exceptional, so it lands on good (4) and
  // leaves 5 and 6 to work judged on the new scale.
  it("puts the old top mark at good, not at the top of the new scale", () => {
    expect(OLD.map((v) => mapValue(v, OLD))).toEqual([0, 2, 4]);
  });

  it("keeps look linear except the old 'better than expected', which becomes exceptional", () => {
    expect(OLD_LOOK.map((v) => mapValue(v, OLD_LOOK))).toEqual([0, 1, 2, 3, 6]);
  });

  it("refuses a value that is not on the old scale", () => {
    expect(() => mapValue(4, OLD)).toThrow(/not on the old scale/);
  });
});

describe("migrateTasks", () => {
  it("moves every criterion, extra ones included, to 0-6", () => {
    const next = migrateTasks(oldTasks());
    const all = [
      ...next.coreCriteria,
      ...(next.judgeCriteria ?? []),
      ...next.tasks.flatMap((t) => t.extraCriteria ?? []),
    ];
    for (const c of all) expect(c.scale.values).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(next.tasks[0].extraCriteria?.[0].description).toBe("keeps todos");
  });

  // Running it twice would multiply every score again.
  it("refuses data that is already on the 0-6 scale", () => {
    expect(() => migrateTasks(migrateTasks(oldTasks()))).toThrow(/already/);
  });
});

describe("migrateResults", () => {
  const run = (id: string, compliance: number, look: number) => ({
    id,
    taskId: "todo",
    scores: [
      { criterionId: "compliance", value: compliance },
      { criterionId: "look", value: look },
      { criterionId: "persistence", value: 1 },
    ],
    judgeScores: [
      {
        judgeId: "codex",
        error: null,
        scores: [
          { criterionId: "logic_correctness", value: compliance },
          { criterionId: "look", value: look },
        ],
      },
    ],
  });

  it("rescales human and judge scores of results and of the queue", () => {
    const { file } = migrateResults(
      {
        results: [run("a", 1, 1.5)],
        inbox: [
          {
            ...run("q", 0.5, 2),
            autoVerdict: { verdict: "FAIL", reasons: ["compliance=0.5", "agent_logic=not measured"] },
          },
        ],
      },
      oldTasks(),
    );
    expect(file.results[0].scores?.map((s) => s.value)).toEqual([4, 3, 4]);
    expect(file.results[0].judgeScores?.[0].scores?.map((s) => s.value)).toEqual([4, 3]);
    expect(file.inbox?.[0].scores?.map((s) => s.value)).toEqual([2, 6, 4]);
    expect(file.inbox?.[0].autoVerdict?.reasons).toEqual(["compliance=2", "agent_logic=not measured"]);
  });

  it("recomputes the score variance of a stability group from the rescaled judge values", () => {
    const { file, stabilityApproximated } = migrateResults(
      {
        results: [run("a", 1, 1), run("b", 0.5, 1)],
        stability: [
          { resultIds: ["a", "b"], deterministic: { scoreVariance: 0.125, codeSimilarity: 0.4 } },
        ],
      },
      oldTasks(),
    );
    // logic_correctness 4 vs 2 -> deviation 1; look 2 vs 2 -> 0; mean over criteria 0.5.
    expect(file.stability?.[0].deterministic.scoreVariance).toBe(0.5);
    expect(file.stability?.[0].deterministic.codeSimilarity).toBe(0.4);
    expect(stabilityApproximated).toBe(0);
  });

  it("stops on a score for a criterion the task does not define", () => {
    const stray = { id: "x", taskId: "snake", scores: [{ criterionId: "persistence", value: 1 }] };
    expect(() => migrateResults({ results: [stray] }, oldTasks())).toThrow(/unknown criterion/);
  });
});
