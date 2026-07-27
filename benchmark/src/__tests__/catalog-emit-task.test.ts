// @vitest-environment node
import { describe, it, expect } from "vitest";
import { CatalogCaseSchema } from "@e2e/schema/case";
import { caseToTask, upsertTaskDated } from "@/lib/catalog/emit-task";
import type { Task } from "@/schema/tasks";

// The case -> e2e scenario half of the transform is tested in tools/e2e; this file
// covers only the benchmark half, the review task written into data/tasks.json.

const agentCase = CatalogCaseSchema.parse({
  id: "todo",
  title: "Todo app",
  category: "single-file-app",
  tier: "easy",
  mode: "AGENT",
  deliverable: "todo_{{MODEL_ID}}_01.html",
  fixture: "fixtures/todo",
  assert: {
    toolOrder: ["create_new_file"],
    needles: [{ regex: "localStorage" }, { text: "Todo" }],
    smoke: { entry: "todo_{{MODEL_ID}}_01.html", domPresent: ["input"] },
  },
  judge: { criteria: ["one self-contained todo.html"] },
  review: { description: "Single-file todo with filters and persistence." },
});

describe("caseToTask", () => {
  it("uses the prompt body as the systemPrompt and carries review fields", () => {
    const task = caseToTask(
      agentCase,
      "FULL PROMPT BODY",
      "2026-07-25T10:00:00.000Z",
      "2026-07-25T11:00:00.000Z",
    );
    expect(task.id).toBe("todo");
    expect(task.name).toBe("Todo app");
    expect(task.description).toBe("Single-file todo with filters and persistence.");
    expect(task.systemPrompt).toBe("FULL PROMPT BODY");
    expect(task.extraCriteria).toEqual([]);
    expect(task.createdAt).toBe("2026-07-25T10:00:00.000Z");
    expect(task.updatedAt).toBe("2026-07-25T11:00:00.000Z");
  });
});

const existing: Task = {
  id: "todo",
  name: "Old name",
  description: "old",
  systemPrompt: "old prompt",
  extraCriteria: [],
  createdAt: "2026-01-01T00:00:00.000Z",
  updatedAt: "2026-01-01T00:00:00.000Z",
};

const now = "2026-07-25T12:00:00.000Z";
// Content core (no timestamps) as caseToTaskCore would produce for a changed task.
const changedCore = {
  id: "todo",
  name: "Todo app",
  description: "new",
  systemPrompt: "new prompt",
  extraCriteria: [],
};

describe("upsertTaskDated", () => {
  it("appends a new id with now for both timestamps", () => {
    const out = upsertTaskDated([existing], { ...changedCore, id: "snake" }, now);
    expect(out.map((t) => t.id)).toEqual(["todo", "snake"]);
    expect(out[1].createdAt).toBe(now);
    expect(out[1].updatedAt).toBe(now);
  });

  it("updates changed content: keeps createdAt, bumps updatedAt to now", () => {
    const out = upsertTaskDated([existing], changedCore, now);
    expect(out).toHaveLength(1);
    expect(out[0].systemPrompt).toBe("new prompt");
    expect(out[0].createdAt).toBe("2026-01-01T00:00:00.000Z");
    expect(out[0].updatedAt).toBe(now);
  });

  it("leaves an unchanged task exactly as-is so a re-run produces no diff", () => {
    const unchanged = {
      id: "todo",
      name: "Old name",
      description: "old",
      systemPrompt: "old prompt",
      extraCriteria: [],
    };
    const input = [existing];
    const out = upsertTaskDated(input, unchanged, now);
    expect(out).toBe(input); // same array reference: nothing changed
    expect(out[0]).toBe(existing); // same object reference
  });

  it("does not mutate the input array on update", () => {
    const input = [existing];
    upsertTaskDated(input, changedCore, now);
    expect(input[0].name).toBe("Old name");
  });
});
