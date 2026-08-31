// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  NO_ARTIFACT_JUDGE_ID,
  needsNoArtifactVerdict,
  buildNoArtifactVerdict,
} from "@/lib/judge/scoring";

const criteria = [
  { id: "compliance", scale: { values: [0, 0.5, 1] } },
  { id: "works_out_of_box", scale: { values: [0, 0.5, 1] } },
];

const run = (extra: Record<string, unknown> = {}) => ({
  attachments: [] as Array<{ type: string; src: string }>,
  judgeScores: [] as Array<{ judgeId: string; error?: string | null }>,
  ...extra,
});

describe("needsNoArtifactVerdict", () => {
  it("scores a run the agent performed but that produced no artifact", () => {
    // The business rule: work without a deliverable is a failure worth 0, not
    // missing data - otherwise the model is invisible in the judge ranking and
    // gets a free pass for never writing the file.
    expect(needsNoArtifactVerdict(run())).toBe(true);
  });

  it("leaves a run with an HTML artifact to the real judges", () => {
    expect(
      needsNoArtifactVerdict(
        run({ attachments: [{ type: "html", src: "artifact.html" }] }),
      ),
    ).toBe(false);
  });


  it("does not overwrite a zero verdict it already wrote", () => {
    expect(
      needsNoArtifactVerdict(
        run({ judgeScores: [{ judgeId: NO_ARTIFACT_JUDGE_ID, error: null }] }),
      ),
    ).toBe(false);
  });

  it("still scores a run whose only entry is a failed judge attempt", () => {
    // A timed-out judge left no verdict, so the missing deliverable is still
    // unscored and the zero must be written.
    expect(
      needsNoArtifactVerdict(
        run({ judgeScores: [{ judgeId: "claude-code", error: "timed out" }] }),
      ),
    ).toBe(true);
  });
});

describe("buildNoArtifactVerdict", () => {
  it("gives every criterion the lowest value on its own scale", () => {
    const verdict = buildNoArtifactVerdict(criteria, "2026-08-27T10:00:00.000Z");
    expect(verdict.judgeId).toBe(NO_ARTIFACT_JUDGE_ID);
    expect(verdict.error).toBeNull();
    expect(verdict.scores).toEqual([
      { criterionId: "compliance", value: 0, rationale: expect.any(String) },
      { criterionId: "works_out_of_box", value: 0, rationale: expect.any(String) },
    ]);
  });

  it("uses the scale minimum even when it is not zero", () => {
    const verdict = buildNoArtifactVerdict(
      [{ id: "code", scale: { values: [1, 2, 3] } }],
      "2026-08-27T10:00:00.000Z",
    );
    expect(verdict.scores[0].value).toBe(1);
  });
});
