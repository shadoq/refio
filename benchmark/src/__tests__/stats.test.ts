// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  normalizeScore,
  normalizeResult,
  leaderboard,
  harnessDelta,
  judgeCriteriaForTask,
  getResultJudgeScore,
} from "@/lib/stats";
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
  judgeCriteria: [],
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
  harnessId: "refio",
  attemptNumber: 1,
  scores,
  attachments: [],
  judgeScores: [],
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
    harnesses: [
      { id: "refio", name: "Refio", kind: "refio" },
      { id: "claude-code", name: "Claude Code", kind: "external" },
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
    stability: [],
    inbox: [],
  };

  it("returns one row per (modelId, environmentId) pair", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    expect(rows).toHaveLength(2);
  });

  // The same model driven by two different agents is two measurements, not one.
  // Collapsing them would silently average Refio's result with Claude Code's and
  // make both unreadable.
  it("keeps the same model in two harnesses as two rows", () => {
    const crossHarness: ResultsFile = {
      ...resultsFile,
      results: [
        makeResult("h1", "claude", "cloud", "snake", [
          { criterionId: "compliance", value: 1 },
        ]),
        makeResult(
          "h2",
          "claude",
          "cloud",
          "snake",
          [{ criterionId: "compliance", value: 0.5 }],
          { harnessId: "claude-code" },
        ),
      ],
    };
    const rows = leaderboard(crossHarness.results, crossHarness, tasksFile);
    expect(rows).toHaveLength(2);
    expect(rows.map((r) => r.harnessId).sort()).toEqual(["claude-code", "refio"]);
  });

  it("carries the harness record onto the row so the view can label the track", () => {
    const rows = leaderboard(resultsFile.results, resultsFile, tasksFile);
    expect(rows[0].harness.kind).toBe("refio");
  });

  // An import can land a run before anyone edits the registry. Dropping the row
  // would lose a measurement without saying so.
  it("keeps a row whose harness is not in the registry yet", () => {
    const unregistered: ResultsFile = {
      ...resultsFile,
      harnesses: [],
      results: [
        makeResult(
          "u1",
          "claude",
          "cloud",
          "snake",
          [{ criterionId: "compliance", value: 1 }],
          { harnessId: "codex" },
        ),
      ],
    };
    const rows = leaderboard(unregistered.results, unregistered, tasksFile);
    expect(rows).toHaveLength(1);
    expect(rows[0].harness.kind).toBe("external");
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

describe("judge scoring helpers", () => {
  const judgeTasks: TasksFile = {
    version: 1,
    coreCriteria: [
      { id: "compliance", name: "Compliance", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
      { id: "agent_logic", name: "Agent logic", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
    ],
    judgeCriteria: [
      { id: "logic_correctness", name: "Logic correctness", description: "", scale: { values: [0, 0.5, 1] }, weight: 1 },
    ],
    tasks: [
      {
        id: "snake",
        name: "Snake",
        description: "",
        systemPrompt: "",
        extraCriteria: [],
        createdAt: "2026-04-01T10:00:00.000Z",
        updatedAt: "2026-04-01T10:00:00.000Z",
      },
    ],
  };

  it("excludes agent_logic from the criteria a judge scores", () => {
    const ids = judgeCriteriaForTask(judgeTasks, "snake").map((c) => c.id);
    expect(ids).toContain("compliance");
    expect(ids).toContain("logic_correctness");
    expect(ids).not.toContain("agent_logic");
  });

  it("computes the judge score from the aggregate, ignoring agent_logic", () => {
    const result = makeResult("r1", "m1", "e1", "snake", [{ criterionId: "compliance", value: 1 }], {
      judgeScores: [
        {
          judgeId: "claude-code",
          judgeModel: "x",
          judgedAt: "2026-07-19T12:00:00.000Z",
          scores: [
            { criterionId: "compliance", value: 1 },
            { criterionId: "logic_correctness", value: 0.5 },
          ],
          screenshots: [],
          consoleErrors: [],
          error: null,
        },
      ],
    });
    // compliance 1/1 = 1, logic_correctness 0.5/1 = 0.5 -> mean 0.75
    expect(getResultJudgeScore(result, judgeTasks)).toBeCloseTo(0.75);
  });

  it("returns null when there are no judge scores", () => {
    const result = makeResult("r2", "m1", "e1", "snake", [{ criterionId: "compliance", value: 1 }]);
    expect(getResultJudgeScore(result, judgeTasks)).toBeNull();
  });
});

// The point of running the same task under two harnesses is the comparison, so the
// pairing has to be explicit rather than left to the reader scanning two tables.
describe("harnessDelta", () => {
  const scores = (compliance: number) => [{ criterionId: "compliance", value: compliance }];

  it("pairs the same model across harnesses and reports the difference", () => {
    const results = [
      makeResult("a", "claude", "cloud", "snake", scores(0.5)),
      makeResult("b", "claude", "cloud", "snake", scores(1), { harnessId: "claude-code" }),
    ];
    const rows = harnessDelta(results, tasksFile, "refio");
    expect(rows).toHaveLength(1);
    expect(rows[0].modelId).toBe("claude");
    expect(rows[0].baselineScore).toBeCloseTo(0.5);
    expect(rows[0].byHarness["claude-code"]).toBeCloseTo(1);
    expect(rows[0].delta["claude-code"]).toBeCloseTo(0.5);
  });

  it("averages every attempt a harness made, not just the first", () => {
    const results = [
      makeResult("a", "claude", "cloud", "snake", scores(1)),
      makeResult("b", "claude", "cloud", "snake", scores(0), { attemptNumber: 2 }),
      makeResult("c", "claude", "cloud", "snake", scores(1), { harnessId: "codex" }),
    ];
    const rows = harnessDelta(results, tasksFile, "refio");
    expect(rows[0].baselineScore).toBeCloseTo(0.5);
    expect(rows[0].delta["codex"]).toBeCloseTo(0.5);
  });

  // Without a baseline run there is nothing to compare against, and inventing a zero
  // would read as "Refio scored nothing" instead of "Refio has not run this".
  it("leaves the difference unset when the baseline harness did not run the model", () => {
    const results = [
      makeResult("b", "claude", "cloud", "snake", scores(1), { harnessId: "claude-code" }),
    ];
    const rows = harnessDelta(results, tasksFile, "refio");
    expect(rows[0].baselineScore).toBeNull();
    expect(rows[0].delta["claude-code"]).toBeUndefined();
  });

  it("returns nothing when only the baseline harness ran", () => {
    const results = [makeResult("a", "claude", "cloud", "snake", scores(1))];
    expect(harnessDelta(results, tasksFile, "refio")).toEqual([]);
  });
});
