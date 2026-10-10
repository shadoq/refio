// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  normalizeScore,
  normalizeResult,
  leaderboard,
  harnessDelta,
  judgeCriteriaForTask,
  getResultJudgeScore,
  taskHarnessMatrix,
  refioScore,
  leaderRelativeScores,
  withRefioMode,
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

  // The baseline usually has many more tasks behind it than the agent it is compared
  // with. Averaging each side over everything it happened to run put an average over
  // two tasks next to an average over one and called the difference a result.
  it("compares only the tasks both harnesses actually ran", () => {
    const results = [
      makeResult("a", "claude", "cloud", "snake", scores(1)),
      makeResult("b", "claude", "cloud", "todo", scores(0)),
      makeResult("c", "claude", "cloud", "snake", scores(1), { harnessId: "claude-code" }),
    ];
    const rows = harnessDelta(results, tasksFile, "refio");
    expect(rows[0].pairedTasks["claude-code"]).toBe(1);
    // Both scored 1 on the one task they share, so there is no difference to report.
    expect(rows[0].delta["claude-code"]).toBeCloseTo(0);
    expect(rows[0].baselineScore).toBeCloseTo(1);
  });

  it("reports no shared task rather than a difference when the two never met", () => {
    const results = [
      makeResult("a", "claude", "cloud", "snake", scores(1)),
      makeResult("b", "claude", "cloud", "todo", scores(0), { harnessId: "claude-code" }),
    ];
    const rows = harnessDelta(results, tasksFile, "refio");
    expect(rows[0].pairedTasks["claude-code"]).toBe(0);
    expect(rows[0].delta["claude-code"]).toBeUndefined();
  });
});

describe("taskHarnessMatrix", () => {
  const scoresFull = [
    { criterionId: "compliance", value: 1 },
    { criterionId: "look", value: 2 },
  ];
  const scoresHalf = [
    { criterionId: "compliance", value: 0.5 },
    { criterionId: "look", value: 1 },
  ];

  it("puts one row per task and one cell per harness that ran it", () => {
    const rows = taskHarnessMatrix(
      [
        makeResult("a", "m", "e", "snake", scoresFull),
        makeResult("b", "m", "e", "snake", scoresHalf, { harnessId: "claude-code" }),
        makeResult("c", "m", "e", "todo", scoresFull, { harnessId: "codex" }),
      ],
      tasksFile,
    );
    const snake = rows.find((r) => r.taskId === "snake");
    expect(snake?.byHarness["refio"]).toEqual({ avgScore: 1, attempts: 1 });
    expect(snake?.byHarness["claude-code"].avgScore).toBeCloseTo(0.5);
    expect(rows.find((r) => r.taskId === "todo")?.byHarness["codex"].attempts).toBe(1);
  });

  it("leaves out a task that is hidden from the public view", () => {
    const hidden: TasksFile = {
      ...tasksFile,
      tasks: [{ ...makeTask("snake"), hidden: true }, makeTask("todo")],
    };
    const rows = taskHarnessMatrix(
      [makeResult("a", "m", "e", "snake", scoresFull)],
      hidden,
    );
    expect(rows.some((r) => r.taskId === "snake")).toBe(false);
  });
});

// A judge run by the same agent that produced the result is marking its own work, so
// the agents page drops it; every other page keeps the aggregate it always had.
describe("leaderboard with the self-judge excluded", () => {
  const judged = (judgeId: string) => ({
    judgeId,
    judgeModel: "m",
    judgedAt: "2026-04-16T09:00:00.000Z",
    scores: [{ criterionId: "compliance", value: 1 }],
    screenshots: [],
    consoleErrors: [],
  });

  const resultsFile: Pick<ResultsFile, "models" | "environments" | "harnesses"> = {
    models: [{ id: "m", name: "m", provider: "anthropic" }],
    environments: [{ id: "e", name: "e", type: "cloud" }],
    harnesses: [{ id: "claude-code", name: "Claude Code", kind: "external" }],
  };

  const results = [
    makeResult("a", "m", "e", "snake", [{ criterionId: "compliance", value: 1 }], {
      harnessId: "claude-code",
      judgeScores: [judged("claude-code")],
    }),
  ];

  it("keeps the judge aggregate by default", () => {
    expect(leaderboard(results, resultsFile, tasksFile)[0].judgedAttempts).toBe(1);
  });

  it("drops the verdict of the agent that produced the run", () => {
    const row = leaderboard(results, resultsFile, tasksFile, { excludeSelfJudge: true })[0];
    expect(row.judgedAttempts).toBe(0);
    expect(row.judgeAvgScore).toBeNull();
  });
});

// One number per leaderboard row: quality (human and judge halves) scaled by how
// repeatable the model is. Stability multiplies instead of adding, so a model that
// fails the same way every time gains nothing from being consistent.
describe("refioScore", () => {
  // The human score is the owner's own verdict; judges are a second opinion, so they
  // count half as much.
  it("weighs the human score twice as much as the judges", () => {
    expect(refioScore(0.8, 0.5, 1)).toBeCloseTo(0.7);
  });

  it("takes away at most a fifth of the quality from a model with no stability", () => {
    expect(refioScore(0.8, 0.5, 0)).toBeCloseTo(0.56);
  });

  it("gives a consistently failing model nothing for its consistency", () => {
    expect(refioScore(0, 0, 1)).toBe(0);
  });

  it("falls back to the human score alone when no judge scored the row", () => {
    expect(refioScore(0.8, null, 1)).toBeCloseTo(0.8);
  });

  it("stays empty without a stability measurement instead of guessing one", () => {
    expect(refioScore(0.8, 0.6, null)).toBeNull();
  });
});

describe("leaderboard Refio Score", () => {
  const judged = (judgeId: string, value: number) => ({
    judgeId,
    judgeModel: "m",
    judgedAt: "2026-04-16T09:00:00.000Z",
    scores: [{ criterionId: "compliance", value }],
    screenshots: [],
    consoleErrors: [],
  });

  const stabilityEntry = (harnessId: string, scoreVariance: number, codeSimilarity: number) => ({
    taskId: "snake",
    modelId: "m",
    environmentId: "e",
    harnessId,
    resultIds: ["a", "b"],
    deterministic: { scoreVariance, codeSimilarity },
    judges: [],
    computedAt: "2026-04-16T09:00:00.000Z",
  });

  const file = (stability: ReturnType<typeof stabilityEntry>[]) => ({
    models: [{ id: "m", name: "m", provider: "anthropic" as const }],
    environments: [{ id: "e", name: "e", type: "cloud" as const }],
    harnesses: [{ id: "claude-code", name: "Claude Code", kind: "external" as const }],
    stability,
  });

  // Human compliance 1/1 = 1.0, judge compliance 0.5/1 = 0.5 -> quality 2/3 + 1/6 = 0.833.
  const results = [
    makeResult("a", "m", "e", "snake", [{ criterionId: "compliance", value: 1 }], {
      harnessId: "claude-code",
      judgeScores: [judged("claude-code", 0.5)],
    }),
  ];

  it("scales quality by the row's own stability groups only", () => {
    // Fully stable group for this harness; the refio group must not leak in.
    const stability = [stabilityEntry("claude-code", 0, 1), stabilityEntry("refio", 3, 0)];
    const row = leaderboard(results, file(stability), tasksFile)[0];
    expect(row.stabilityScore).toBeCloseTo(1);
    expect(row.refioScore).toBeCloseTo(0.833);
  });

  it("counts every judge blind, even where a page hides the producer's own judge", () => {
    const row = leaderboard(results, file([stabilityEntry("claude-code", 0, 1)]), tasksFile, {
      excludeSelfJudge: true,
    })[0];
    expect(row.judgeAvgScore).toBeNull();
    expect(row.refioScore).toBeCloseTo(0.833);
  });

  it("leaves the Refio Score empty for a row without stability groups", () => {
    const row = leaderboard(results, file([]), tasksFile)[0];
    expect(row.stabilityScore).toBeNull();
    expect(row.refioScore).toBeNull();
  });
});

// The leader-relative view answers "how far behind the best model is this one?". Each
// task is first scored against the best result on that task, so a model is not
// punished for having run a harder task than the others, and the leader shows 100%.
describe("leaderRelativeScores", () => {
  const row = (
    modelId: string,
    taskQuality: Record<string, number>,
    stabilityScore: number | null,
  ) => ({ modelId, environmentId: "e", harnessId: "refio", taskQuality, stabilityScore });

  it("puts the leader at 100% and the others as a share of it", () => {
    const scores = leaderRelativeScores([
      row("strong", { snake: 0.8 }, 1),
      row("weak", { snake: 0.4 }, 1),
    ]);
    expect(scores.get("strong::e::refio")).toBeCloseTo(1);
    expect(scores.get("weak::e::refio")).toBeCloseTo(0.5);
  });

  it("does not punish a model for a hard task where it was the best", () => {
    // "hard" tops out at 0.3 for everyone; "a" leads it, "b" never ran it.
    const scores = leaderRelativeScores([
      row("a", { easy: 0.9, hard: 0.3 }, 1),
      row("b", { easy: 0.9 }, 1),
    ]);
    expect(scores.get("a::e::refio")).toBeCloseTo(1);
    expect(scores.get("b::e::refio")).toBeCloseTo(1);
  });

  it("keeps the stability factor of the absolute Refio Score", () => {
    const scores = leaderRelativeScores([
      row("stable", { snake: 0.8 }, 1),
      row("unstable", { snake: 0.8 }, 0),
    ]);
    expect(scores.get("unstable::e::refio")).toBeCloseTo(0.8);
  });

  it("leaves out a row without a stability measurement", () => {
    const scores = leaderRelativeScores([row("m", { snake: 0.8 }, null)]);
    expect(scores.has("m::e::refio")).toBe(false);
  });
});

describe("withRefioMode", () => {
  const row = (modelId: string, snake: number, refioScore: number | null) => ({
    modelId,
    environmentId: "e",
    harnessId: "refio",
    taskQuality: { snake },
    stabilityScore: 1,
    refioScore,
  });

  it("leaves the absolute Refio Score untouched in absolute mode", () => {
    const rows = [row("a", 0.8, 0.6)];
    expect(withRefioMode(rows, "absolute")[0].refioScore).toBe(0.6);
  });

  it("swaps in the vs-leader score so every page shows the same view", () => {
    const rows = withRefioMode([row("a", 0.8, 0.6), row("b", 0.4, 0.3)], "relative");
    expect(rows[0].refioScore).toBeCloseTo(1);
    expect(rows[1].refioScore).toBeCloseTo(0.5);
  });
});
