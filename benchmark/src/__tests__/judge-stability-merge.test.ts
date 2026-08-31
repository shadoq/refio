// @vitest-environment node
import { describe, it, expect } from "vitest";
import { mergeStabilityJudges, stabilityNeedsJudging } from "@/lib/judge/stability-merge";

const judge = (judgeId: string, value: number, judgedAt: string) => ({
  judgeId,
  judgeModel: `${judgeId}-model`,
  value,
  judgedAt,
});

const entry = (judges: ReturnType<typeof judge>[], computedAt: string) => ({
  taskId: "website-museum-night",
  modelId: "ollama/qwen3.5:9b",
  environmentId: "dgx-local",
  resultIds: ["a", "b"],
  deterministic: { scoreVariance: 0.1, codeSimilarity: 0.3 },
  judges,
  computedAt,
});

describe("mergeStabilityJudges", () => {
  it("keeps a verdict from a judge the new run did not use", () => {
    // Topping up a group that only got codex must not cost the codex verdict:
    // re-running with --judges claude-code recomputes the entry from scratch.
    const existing = entry([judge("codex", 0.5, "2026-08-27T08:31:00.000Z")], "2026-08-27T08:31:00.000Z");
    const incoming = entry([judge("claude-code", 0, "2026-08-31T10:00:00.000Z")], "2026-08-31T10:00:00.000Z");

    const merged = mergeStabilityJudges(existing, incoming);

    expect(merged.judges.map((j) => j.judgeId).sort()).toEqual(["claude-code", "codex"]);
    expect(merged.judges.find((j) => j.judgeId === "codex")?.value).toBe(0.5);
  });

  it("lets the new run replace the same judge's older verdict", () => {
    const existing = entry([judge("codex", 0.5, "2026-08-27T08:31:00.000Z")], "2026-08-27T08:31:00.000Z");
    const incoming = entry([judge("codex", 1, "2026-08-31T10:00:00.000Z")], "2026-08-31T10:00:00.000Z");

    const merged = mergeStabilityJudges(existing, incoming);

    expect(merged.judges).toHaveLength(1);
    expect(merged.judges[0].value).toBe(1);
  });

  it("takes deterministic metrics and timestamp from the new run", () => {
    const existing = entry([judge("codex", 0.5, "2026-08-27T08:31:00.000Z")], "2026-08-27T08:31:00.000Z");
    const incoming = { ...entry([], "2026-08-31T10:00:00.000Z"), deterministic: { scoreVariance: 0.9, codeSimilarity: 0.2 } };

    const merged = mergeStabilityJudges(existing, incoming);

    expect(merged.deterministic.scoreVariance).toBe(0.9);
    expect(merged.computedAt).toBe("2026-08-31T10:00:00.000Z");
  });

  it("returns the new entry unchanged when the group had none", () => {
    const incoming = entry([judge("claude-code", 1, "2026-08-31T10:00:00.000Z")], "2026-08-31T10:00:00.000Z");
    expect(mergeStabilityJudges(undefined, incoming)).toEqual(incoming);
  });
});

describe("stabilityNeedsJudging", () => {
  const wanted = ["claude-code", "codex"];

  it("treats a group with no entry at all as pending", () => {
    expect(stabilityNeedsJudging(undefined, wanted)).toBe(true);
  });

  it("treats a group missing one of the requested judges as pending", () => {
    // The trap this rule closes: an entry that only codex answered used to count
    // as done, so a plain --stability run silently skipped the missing verdict.
    const partial = entry([judge("codex", 0.5, "2026-08-27T08:31:00.000Z")], "2026-08-27T08:31:00.000Z");
    expect(stabilityNeedsJudging(partial, wanted)).toBe(true);
  });

  it("skips a group every requested judge already scored", () => {
    const full = entry(
      [judge("codex", 0.5, "2026-08-27T08:31:00.000Z"), judge("claude-code", 1, "2026-08-31T10:00:00.000Z")],
      "2026-08-31T10:00:00.000Z",
    );
    expect(stabilityNeedsJudging(full, wanted)).toBe(false);
  });

  it("ignores judges outside the requested set", () => {
    // Running with --judges codex must not re-run codex just because some other
    // judge never scored this group.
    const codexOnly = entry([judge("codex", 0.5, "2026-08-27T08:31:00.000Z")], "2026-08-27T08:31:00.000Z");
    expect(stabilityNeedsJudging(codexOnly, ["codex"])).toBe(false);
  });
});
