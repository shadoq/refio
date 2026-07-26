// @vitest-environment node
import { describe, it, expect } from "vitest";
import { resultAdminFacts } from "@/lib/adminFacts";
import type { Result } from "@/schema/results";

const baseResult: Result = {
  id: "res_123",
  taskId: "games-snake4cpu",
  modelId: "ollama/qwen3.5:9b",
  environmentId: "dgx-spark",
  attemptNumber: 2,
  scores: [{ criterionId: "compliance", value: 1 }],
  attachments: [],
  judgeScores: [],
  runAt: "2026-07-25T10:00:00.000Z",
  createdAt: "2026-07-25T10:05:00.000Z",
};

function value(facts: ReturnType<typeof resultAdminFacts>, label: string): string | undefined {
  return facts.find((fact) => fact.label === label)?.value;
}

describe("resultAdminFacts", () => {
  // The admin preview must expose the raw result id so a reviewer can copy it
  // to cross-reference logs and the on-disk artifact folder.
  it("exposes the raw result id as a copyable fact", () => {
    const facts = resultAdminFacts(baseResult);
    const idFact = facts.find((fact) => fact.label === "Result ID");
    expect(idFact?.value).toBe("res_123");
    expect(idFact?.copyable).toBe(true);
  });

  // Reviewers need the human name AND the raw id to tie a run to both the UI and
  // the underlying data files, so resolved facts carry both.
  it("shows resolved names next to their raw ids when context is provided", () => {
    const facts = resultAdminFacts(baseResult, {
      taskName: "Snake 4CPU",
      modelName: "Qwen3.5 9B",
      environmentName: "DGX Spark",
      environmentType: "local",
    });
    expect(value(facts, "Task")).toBe("Snake 4CPU (games-snake4cpu)");
    expect(value(facts, "Model")).toBe("Qwen3.5 9B (ollama/qwen3.5:9b)");
    expect(value(facts, "Environment")).toBe("DGX Spark (dgx-spark, local)");
  });

  // An orphaned result (its task/model/env was deleted) must still read clearly,
  // falling back to the raw ids rather than rendering blanks.
  it("falls back to raw ids when no names are known", () => {
    const facts = resultAdminFacts(baseResult);
    expect(value(facts, "Task")).toBe("games-snake4cpu");
    expect(value(facts, "Model")).toBe("ollama/qwen3.5:9b");
    expect(value(facts, "Environment")).toBe("dgx-spark");
  });

  // The raw ISO timestamps are administrative detail the public view hides.
  it("carries both raw timestamps", () => {
    const facts = resultAdminFacts(baseResult);
    expect(value(facts, "Run at")).toBe("2026-07-25T10:00:00.000Z");
    expect(value(facts, "Created at")).toBe("2026-07-25T10:05:00.000Z");
  });

  // Exact metrics (with the raw millisecond count) matter for administration,
  // and missing optional metrics must render as a dash, never as "undefined".
  it("formats duration with the exact millisecond count and dashes missing metrics", () => {
    const withMetrics = resultAdminFacts({
      ...baseResult,
      durationMs: 45_000,
      costUsd: 0.0241,
      tokensOut: 1234,
    });
    expect(value(withMetrics, "Duration")).toBe("45s (45000 ms)");
    expect(value(withMetrics, "Cost")).toBe("$0.0241");
    expect(value(withMetrics, "Tokens out")).toBe("1234");

    const withoutMetrics = resultAdminFacts(baseResult);
    expect(value(withoutMetrics, "Duration")).toBe("-");
    expect(value(withoutMetrics, "Cost")).toBe("-");
    expect(value(withoutMetrics, "Tokens in")).toBe("-");
  });

  // The attachment count is a quick administrative signal (did the run produce
  // an artifact at all?).
  it("reports the attachment count", () => {
    const facts = resultAdminFacts({
      ...baseResult,
      attachments: [
        { type: "html", src: "a.html" },
        { type: "image", src: "b.png" },
      ],
    });
    expect(value(facts, "Attachments")).toBe("2");
  });
});
