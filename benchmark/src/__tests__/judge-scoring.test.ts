// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  snapToScale,
  validateVerdict,
  aggregateJudgeScores,
  scoreVariance,
  weightedNormalized,
  maxSharedDivergence,
  mayRecordJudgeError,
} from "@/lib/judge/scoring";

describe("snapToScale", () => {
  it("returns the nearest allowed value", () => {
    expect(snapToScale(0.7, [0, 0.5, 1])).toBe(0.5);
    expect(snapToScale(0.9, [0, 0.5, 1])).toBe(1);
  });

  it("keeps the lower value on an exact tie", () => {
    // 0.75 is equidistant from 0.5 and 1 -> the business rule prefers the lower.
    expect(snapToScale(0.75, [0, 0.5, 1])).toBe(0.5);
  });

  it("passes through a value that is already on the scale", () => {
    expect(snapToScale(1.5, [0, 0.5, 1, 1.5, 2])).toBe(1.5);
  });
});

describe("validateVerdict", () => {
  const criteria = [
    { id: "compliance", scale: { values: [0, 0.5, 1] } },
    { id: "look", scale: { values: [0, 0.5, 1, 1.5, 2] } },
  ];

  it("snaps out-of-scale values and reports nothing missing when complete", () => {
    const res = validateVerdict(
      [
        { criterionId: "compliance", value: 0.8 },
        { criterionId: "look", value: 1.7 },
      ],
      criteria,
    );
    expect(res.missing).toEqual([]);
    expect(res.unknown).toEqual([]);
    expect(res.scores).toEqual([
      { criterionId: "compliance", value: 1 },
      { criterionId: "look", value: 1.5 },
    ]);
  });

  it("reports criteria that were not scored", () => {
    const res = validateVerdict([{ criterionId: "compliance", value: 1 }], criteria);
    expect(res.missing).toEqual(["look"]);
  });

  it("drops and reports scores for unknown criteria", () => {
    const res = validateVerdict(
      [
        { criterionId: "compliance", value: 1 },
        { criterionId: "look", value: 2 },
        { criterionId: "bogus", value: 1 },
      ],
      criteria,
    );
    expect(res.unknown).toEqual(["bogus"]);
    expect(res.scores.map((s) => s.criterionId)).toEqual(["compliance", "look"]);
  });
});

describe("aggregateJudgeScores", () => {
  it("takes the median per criterion across judges", () => {
    const agg = aggregateJudgeScores([
      { scores: [{ criterionId: "code", value: 0 }] },
      { scores: [{ criterionId: "code", value: 0.5 }] },
      { scores: [{ criterionId: "code", value: 1 }] },
    ]);
    expect(agg.code).toBe(0.5);
  });

  it("averages the two middle values on an even count", () => {
    const agg = aggregateJudgeScores([
      { scores: [{ criterionId: "code", value: 0 }] },
      { scores: [{ criterionId: "code", value: 1 }] },
    ]);
    expect(agg.code).toBe(0.5);
  });

  it("ignores judge sets carrying an error", () => {
    const agg = aggregateJudgeScores([
      { scores: [{ criterionId: "code", value: 1 }] },
      { error: "timeout", scores: [] },
    ]);
    expect(agg.code).toBe(1);
  });
});

describe("scoreVariance", () => {
  it("is 0 for identical attempts", () => {
    expect(scoreVariance([{ code: 1 }, { code: 1 }])).toBe(0);
  });

  it("is 0 with fewer than two attempts", () => {
    expect(scoreVariance([{ code: 1 }])).toBe(0);
  });

  it("computes the mean absolute deviation across attempts", () => {
    // code: [0, 1] -> mean 0.5, MAD 0.5; single criterion -> variance 0.5
    expect(scoreVariance([{ code: 0 }, { code: 1 }])).toBeCloseTo(0.5);
  });

  it("averages the deviation over criteria", () => {
    // code MAD 0.5, look MAD 0 -> average 0.25
    expect(
      scoreVariance([
        { code: 0, look: 1 },
        { code: 1, look: 1 },
      ]),
    ).toBeCloseTo(0.25);
  });
});

describe("weightedNormalized", () => {
  const criteria = [
    { id: "a", weight: 1, scale: { values: [0, 0.5, 1] } },
    { id: "look", weight: 1, scale: { values: [0, 0.5, 1, 1.5, 2] } },
  ];

  it("normalizes each value against its scale max before weighting", () => {
    // a: 1/1 = 1 (w1); look: 1/2 = 0.5 (w1) -> (1 + 0.5) / 2 = 0.75
    expect(weightedNormalized({ a: 1, look: 1 }, criteria)).toBeCloseTo(0.75);
  });

  it("respects weights", () => {
    const weighted = [
      { id: "a", weight: 3, scale: { values: [0, 1] } },
      { id: "b", weight: 1, scale: { values: [0, 1] } },
    ];
    // a=1 (w3), b=0 (w1) -> 3/4 = 0.75
    expect(weightedNormalized({ a: 1, b: 0 }, weighted)).toBeCloseTo(0.75);
  });

  it("returns null when no criterion has a value", () => {
    expect(weightedNormalized({}, criteria)).toBeNull();
  });
});

describe("maxSharedDivergence", () => {
  it("returns the largest gap over shared criteria", () => {
    const human = [
      { criterionId: "compliance", value: 1 },
      { criterionId: "look", value: 2 },
    ];
    expect(maxSharedDivergence(human, { compliance: 1, look: 1 })).toBe(1);
  });

  it("ignores criteria only one side scored", () => {
    const human = [{ criterionId: "compliance", value: 1 }];
    // code_structure is judge-only -> not shared -> no divergence
    expect(maxSharedDivergence(human, { code_structure: 0 })).toBe(0);
  });
});

describe("mayRecordJudgeError", () => {
  // The bug this guards against: a re-judge whose CLI hit a usage limit failed on
  // every call, and each failure overwrote a good verdict with an error stub,
  // destroying scores that had cost real judge calls to produce.
  it("refuses to write a failure over an existing successful verdict", () => {
    const existing = [{ judgeId: "codex", error: null }];
    expect(mayRecordJudgeError(existing, "codex")).toBe(false);
  });

  // A result that never scored must still surface the failure, so the operator
  // sees it needs a retry rather than a silent gap.
  it("records a failure when the judge has no verdict yet", () => {
    expect(mayRecordJudgeError([], "codex")).toBe(true);
    const otherJudgeOnly = [{ judgeId: "claude-code", error: null }];
    expect(mayRecordJudgeError(otherJudgeOnly, "codex")).toBe(true);
  });

  // A prior attempt that also errored is not a verdict worth keeping, so a fresh
  // failure may replace it (with an updated reason).
  it("replaces a prior error stub for the same judge", () => {
    const existing = [{ judgeId: "codex", error: "timed out" }];
    expect(mayRecordJudgeError(existing, "codex")).toBe(true);
  });
});
