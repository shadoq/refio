// @vitest-environment node
import { describe, it, expect } from "vitest";
import { buildCalibration, proposeScores, MIN_CALIBRATION_SAMPLES } from "@/lib/judge/proposal";

const AT = "2026-11-01T00:00:00.000Z";
const SCALE = { values: [0, 1, 2, 3, 4, 5, 6] };
const look = { id: "look", scale: SCALE };
const agentLogic = { id: "agent_logic", scale: SCALE };

function judge(judgeId: string, values: Record<string, number>, error: string | null = null) {
  return {
    judgeId,
    judgeModel: "m",
    judgedAt: AT,
    scores: Object.entries(values).map(([criterionId, value]) => ({ criterionId, value })),
    error,
  };
}

// A promoted result where both judges said `judgeValue` and the human gave `humanValue`.
function history(judgeValue: number, humanValue: number, count: number) {
  return Array.from({ length: count }, () => ({
    scores: [{ criterionId: "look", value: humanValue }],
    judgeScores: [judge("claude-code", { look: judgeValue }), judge("codex", { look: judgeValue })],
  }));
}

describe("proposeScores", () => {
  it("translates the judges' score into what the reviewer usually gives for it", () => {
    // Judges consistently rate look one point above the reviewer.
    const calibration = buildCalibration(history(4, 3, MIN_CALIBRATION_SAMPLES));
    const entry = {
      judgeScores: [judge("claude-code", { look: 4 }), judge("codex", { look: 4 })],
    };

    const [p] = proposeScores(entry, [look], calibration);

    expect(p).toMatchObject({ criterionId: "look", value: 3, judgeMean: 4, calibrated: true, disputed: false });
  });

  it("uses the plain judge mean when too few past reviews back that judge score", () => {
    const calibration = buildCalibration(history(4, 3, MIN_CALIBRATION_SAMPLES - 1));
    const entry = {
      judgeScores: [judge("claude-code", { look: 4 }), judge("codex", { look: 4 })],
    };

    const [p] = proposeScores(entry, [look], calibration);

    expect(p).toMatchObject({ value: 4, calibrated: false });
  });

  it("still proposes a score but flags it when the judges are two or more points apart", () => {
    const entry = {
      judgeScores: [judge("claude-code", { look: 1 }), judge("codex", { look: 4 })],
    };

    const [p] = proposeScores(entry, [look], buildCalibration([]));

    // Mean 2.5 snaps to the lower neighbour, so a split never inflates the proposal.
    expect(p).toMatchObject({ value: 2, disputed: true });
  });

  it("ignores a judge whose run failed", () => {
    const entry = {
      judgeScores: [judge("claude-code", { look: 5 }), judge("codex", {}, "usage limit")],
    };

    const [p] = proposeScores(entry, [look], buildCalibration([]));

    expect(p).toMatchObject({ value: 5, disputed: false });
  });

  it("falls back to the deterministic checks for a criterion the judges do not score", () => {
    const entry = {
      judgeScores: [judge("claude-code", { look: 3 }), judge("e2e-deterministic", { agent_logic: 4 })],
    };

    const [, p] = proposeScores(entry, [look, agentLogic], buildCalibration([]));

    expect(p).toMatchObject({ criterionId: "agent_logic", value: 4, source: "deterministic" });
  });

  it("proposes nothing when no judge and no check scored the criterion", () => {
    const [p] = proposeScores({ judgeScores: [] }, [look], buildCalibration([]));

    expect(p.value).toBeUndefined();
  });
});

describe("buildCalibration", () => {
  it("does not learn from the deterministic checks, only from the strong judges", () => {
    const results = Array.from({ length: MIN_CALIBRATION_SAMPLES }, () => ({
      scores: [{ criterionId: "look", value: 1 }],
      judgeScores: [judge("e2e-deterministic", { look: 4 })],
    }));

    expect(buildCalibration(results).get("look")).toBeUndefined();
  });
});
