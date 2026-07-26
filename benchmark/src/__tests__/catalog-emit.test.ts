// @vitest-environment node
import { describe, it, expect } from "vitest";
import { CatalogCaseSchema } from "@/schema/catalog";
import {
  caseToScenario,
  caseToTask,
  upsertTaskDated,
  resolveModelTemplate,
} from "@/lib/catalog/emit";
import type { Task } from "@/schema/tasks";

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

const planCase = CatalogCaseSchema.parse({
  id: "project-analysis",
  title: "Project analysis",
  category: "analysis",
  tier: "medium",
  mode: "PLAN",
  maxIterations: 25,
  fixture: "fixtures/project-analysis",
  assert: {
    needleInOutput: { regex: "OrderParser" },
    fileUnchanged: ["src/pipeline/OrderParser.kt"],
  },
  judge: { criteria: ["names the components and the data flow"] },
  review: { description: "Read-only PLAN analysis." },
});

describe("caseToScenario (AGENT)", () => {
  const scn = caseToScenario(agentCase);

  it("carries the identity and run fields", () => {
    expect(scn.id).toBe("todo");
    expect(scn.mode).toBe("AGENT");
    expect(scn.fixture).toBe("fixtures/todo");
    expect(scn.prompt_file).toBe("prompts/todo.md");
    expect(scn.max_iterations).toBe(40);
  });

  it("maps needles onto the deliverable path with {{MODEL_ID}} resolved for e2e", () => {
    expect(scn.assert.needles_in_file).toEqual([
      { path: "todo_model_01.html", regex: "localStorage" },
      { path: "todo_model_01.html", text: "Todo" },
    ]);
    expect(scn.assert.tool_order).toEqual(["create_new_file"]);
    expect(scn.assert.smoke).toEqual({ entry: "todo_model_01.html", dom_present: ["input"] });
  });

  it("omits keys that do not apply to an AGENT single-file case", () => {
    expect("needle_in_output" in scn.assert).toBe(false);
    expect("build_cmd" in scn.assert).toBe(false);
    expect("file_unchanged" in scn.assert).toBe(false);
  });

  it("keeps the judge criteria", () => {
    expect(scn.judge.criteria).toEqual(["one self-contained todo.html"]);
  });
});

describe("caseToScenario (PLAN)", () => {
  const scn = caseToScenario(planCase);

  it("emits needle_in_output and file_unchanged, not needles_in_file", () => {
    expect(scn.assert.needle_in_output).toEqual({ regex: "OrderParser" });
    expect(scn.assert.file_unchanged).toEqual(["src/pipeline/OrderParser.kt"]);
    expect("needles_in_file" in scn.assert).toBe(false);
    expect("smoke" in scn.assert).toBe(false);
  });
});

describe("resolveModelTemplate", () => {
  it("substitutes every {{MODEL_ID}} occurrence with the token", () => {
    expect(resolveModelTemplate("snake_{{MODEL_ID}}_01.html", "ollama-qwen")).toBe(
      "snake_ollama-qwen_01.html",
    );
    expect(resolveModelTemplate("no placeholder here", "x")).toBe("no placeholder here");
  });
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
