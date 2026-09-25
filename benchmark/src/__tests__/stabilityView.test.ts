// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  scoreConsistency,
  entryStability,
  modelStability,
  filterStabilityEntries,
} from "@/lib/stabilityView";
import type { StabilityEntry } from "@/schema/results";

const entry = (
  over: Partial<StabilityEntry> & { judgeValues?: Record<string, number> },
): StabilityEntry => ({
  taskId: over.taskId ?? "snake",
  modelId: over.modelId ?? "ollama/a",
  environmentId: over.environmentId ?? "dgx-local",
  harnessId: over.harnessId ?? "refio",
  resultIds: ["r1", "r2"],
  deterministic: over.deterministic ?? { scoreVariance: 0.1, codeSimilarity: 0.4 },
  judges: Object.entries(over.judgeValues ?? {}).map(([judgeId, value]) => ({
    judgeId,
    judgeModel: `${judgeId}-model`,
    value,
    judgedAt: "2026-09-25T10:00:00.000Z",
  })),
  computedAt: "2026-09-25T10:00:00.000Z",
});

describe("scoreConsistency", () => {
  it("maps zero deviation between attempts to full consistency", () => {
    expect(scoreConsistency(0)).toBe(1);
  });

  it("uses the same 0.5 ceiling as the leaderboard reliability score", () => {
    expect(scoreConsistency(0.1)).toBeCloseTo(0.8);
    expect(scoreConsistency(0.25)).toBeCloseTo(0.5);
  });

  it("never goes negative for attempts that swing across the whole scale", () => {
    expect(scoreConsistency(0.7)).toBe(0);
  });
});

describe("entryStability", () => {
  it("blends score consistency, code similarity and the median judge verdict", () => {
    const s = entryStability(
      entry({
        deterministic: { scoreVariance: 0.1, codeSimilarity: 0.4 },
        judgeValues: { "claude-code": 0, codex: 1, other: 0.5 },
      }),
    );
    expect(s.judge).toBe(0.5);
    expect(s.overall).toBeCloseTo((0.8 + 0.4 + 0.5) / 3);
  });

  it("does not punish a group the judges have not scored yet", () => {
    // A missing verdict must not count as 0 (divergent); only the deterministic parts are known.
    const s = entryStability(entry({ deterministic: { scoreVariance: 0, codeSimilarity: 0.5 } }));
    expect(s.judge).toBeNull();
    expect(s.overall).toBeCloseTo(0.75);
  });
});

describe("modelStability", () => {
  it("averages each dimension over the model's tasks and keeps a per-task score", () => {
    const entries = [
      entry({ taskId: "snake", deterministic: { scoreVariance: 0, codeSimilarity: 0.2 }, judgeValues: { codex: 1 } }),
      entry({ taskId: "todo", deterministic: { scoreVariance: 0.25, codeSimilarity: 0.6 }, judgeValues: { codex: 0 } }),
      entry({ modelId: "ollama/b", taskId: "snake", judgeValues: { codex: 1 } }),
    ];
    const m = modelStability(entries, "ollama/a")!;
    expect(m.groups).toBe(2);
    expect(m.consistency).toBeCloseTo(0.75);
    expect(m.similarity).toBeCloseTo(0.4);
    expect(m.byJudge.codex).toBeCloseTo(0.5);
    expect(m.byTask.snake).toBeCloseTo((1 + 0.2 + 1) / 3);
    expect(m.byTask.todo).toBeCloseTo((0.5 + 0.6 + 0) / 3);
    expect(m.overall).toBeCloseTo((m.byTask.snake + m.byTask.todo) / 2);
  });

  it("averages a judge only over the groups that judge actually scored", () => {
    const entries = [
      entry({ taskId: "snake", judgeValues: { codex: 1, "claude-code": 0 } }),
      entry({ taskId: "todo", judgeValues: { codex: 1 } }),
    ];
    const m = modelStability(entries, "ollama/a")!;
    expect(m.byJudge["claude-code"]).toBe(0);
    expect(m.byJudge.codex).toBe(1);
  });

  it("returns null for a model with no stability groups", () => {
    expect(modelStability([entry({})], "ollama/missing")).toBeNull();
  });
});

describe("filterStabilityEntries", () => {
  it("applies the global model, task, environment and harness filters", () => {
    const entries = [
      entry({ harnessId: "refio" }),
      entry({ harnessId: "claude-code" }),
      entry({ environmentId: "cloud" }),
      entry({ taskId: "hidden-task" }),
    ];
    const out = filterStabilityEntries(
      entries,
      { modelIds: [], taskIds: [], environmentIds: ["dgx-local"], harnessIds: ["refio"] },
      new Set(["hidden-task"]),
    );
    expect(out).toHaveLength(1);
    expect(out[0].harnessId).toBe("refio");
    expect(out[0].environmentId).toBe("dgx-local");
  });
});
