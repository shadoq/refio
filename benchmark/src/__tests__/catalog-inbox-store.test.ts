// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  ensureModel,
  ensureEnvironment,
  upsertInbox,
} from "@/lib/catalog/inbox-store";
import type { InboxEntry } from "@/schema/results";

function entry(id: string): InboxEntry {
  return {
    id,
    taskId: "todo",
    modelId: "ollama/qwen3.6:35b",
    environmentId: "local",
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
