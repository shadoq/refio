// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  InboxEntrySchema,
  ResultsFileSchema,
} from "@/schema/results";

// An inbox entry is a run awaiting human scoring: it carries the artifact,
// metrics and deterministic judgeScores, but no manual `scores` yet (a human
// adds those on promotion). This is why it is a distinct schema from Result,
// which requires at least one manual score.
const validInboxEntry = {
  id: "todo-qwen36-35b-dgx-01",
  taskId: "todo",
  modelId: "ollama/qwen3.6:35b",
  environmentId: "dgx-local",
  attemptNumber: 1,
  durationMs: 84210,
  attachments: [{ type: "html", src: "attachments/todo-qwen36-35b-dgx-01/artifact.html" }],
  judgeScores: [
    {
      judgeId: "e2e-deterministic",
      judgeModel: "refio-cli",
      judgedAt: "2026-07-25T12:00:00.000Z",
      scores: [{ criterionId: "compliance", value: 1 }],
    },
  ],
  autoVerdict: { verdict: "PASS", reasons: ["all needles matched"] },
  notes: "auto-import; confirm look/code",
  runAt: "2026-07-25T12:00:00.000Z",
  createdAt: "2026-07-25T12:00:00.000Z",
};

const validModel = { id: "ollama/qwen3.6:35b", name: "Qwen", provider: "ollama" };
const validEnv = { id: "dgx-local", name: "DGX", type: "local" as const };

describe("InboxEntrySchema", () => {
  it("parses an entry that has no manual scores yet", () => {
    const result = InboxEntrySchema.safeParse(validInboxEntry);
    expect(result.success).toBe(true);
  });

  it("does not accept a manual scores field (scores are added on promotion)", () => {
    // A stray `scores` key must not silently pass - manual scoring happens in
    // results[], never in the inbox.
    const result = InboxEntrySchema.safeParse({
      ...validInboxEntry,
      scores: [{ criterionId: "look", value: 2 }],
    });
    expect(result.success).toBe(false);
  });

  it("defaults judgeScores and attachments to empty arrays", () => {
    const { attachments, judgeScores, ...bare } = validInboxEntry;
    void attachments;
    void judgeScores;
    const result = InboxEntrySchema.safeParse(bare);
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.judgeScores).toEqual([]);
      expect(result.data.attachments).toEqual([]);
    }
  });

  it("requires the identity fields", () => {
    const { taskId, ...missing } = validInboxEntry;
    void taskId;
    const result = InboxEntrySchema.safeParse(missing);
    expect(result.success).toBe(false);
  });
});

describe("ResultsFileSchema with inbox", () => {
  const base = {
    version: 1 as const,
    models: [validModel],
    environments: [validEnv],
    results: [],
  };

  it("accepts an inbox array", () => {
    const result = ResultsFileSchema.safeParse({ ...base, inbox: [validInboxEntry] });
    expect(result.success).toBe(true);
  });

  it("defaults inbox to an empty array when omitted (backward compatible)", () => {
    const result = ResultsFileSchema.safeParse(base);
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.inbox).toEqual([]);
  });
});
