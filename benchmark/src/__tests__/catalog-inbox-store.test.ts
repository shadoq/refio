// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  ensureModel,
  ensureEnvironment,
  ensureHarness,
  upsertInbox,
} from "@/lib/catalog/inbox-store";
import type { InboxEntry } from "@/schema/results";

function entry(id: string): InboxEntry {
  return {
    id,
    taskId: "todo",
    modelId: "ollama/qwen3.6:35b",
    environmentId: "local",
    harnessId: "refio",
    attemptNumber: 1,
    attachments: [],
    judgeScores: [],
    runAt: "2026-07-25T12:00:00.000Z",
    createdAt: "2026-07-25T12:00:00.000Z",
  };
}

describe("inbox-store helpers", () => {
  it("ensureModel adds a missing model with a provider derived from the id, once", () => {
    const file: Record<string, unknown> = { results: [] };
    ensureModel(file as never, "ollama/qwen3.6:35b");
    ensureModel(file as never, "ollama/qwen3.6:35b");
    const models = file.models as Array<{ id: string; provider: string }>;
    expect(models).toHaveLength(1);
    expect(models[0].provider).toBe("ollama");
  });

  it("ensureEnvironment adds a missing local environment once", () => {
    const file: Record<string, unknown> = { results: [] };
    ensureEnvironment(file as never, "local");
    ensureEnvironment(file as never, "local");
    expect((file.environments as unknown[]).length).toBe(1);
  });

  // An import under a new harness must register it, otherwise the views have an id
  // with no name and no description of the run conditions.
  it("ensureHarness adds a missing external harness once", () => {
    const file: Record<string, unknown> = { results: [] };
    ensureHarness(file as never, "claude-code");
    ensureHarness(file as never, "claude-code");
    const harnesses = file.harnesses as Array<{ id: string; kind: string }>;
    expect(harnesses).toHaveLength(1);
    expect(harnesses[0].kind).toBe("external");
  });

  it("ensureHarness marks refio as our own harness, not an external one", () => {
    const file: Record<string, unknown> = { results: [] };
    ensureHarness(file as never, "refio");
    const harnesses = file.harnesses as Array<{ id: string; kind: string }>;
    expect(harnesses[0].kind).toBe("refio");
  });

  it("upsertInbox appends a new id and replaces an existing one", () => {
    const file: Record<string, unknown> = { results: [] };
    upsertInbox(file as never, entry("a"));
    upsertInbox(file as never, entry("b"));
    upsertInbox(file as never, { ...entry("a"), notes: "updated" });
    const inbox = file.inbox as InboxEntry[];
    expect(inbox.map((e) => e.id)).toEqual(["a", "b"]);
    expect(inbox.find((e) => e.id === "a")?.notes).toBe("updated");
  });
});
