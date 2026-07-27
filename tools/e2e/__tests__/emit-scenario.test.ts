import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { CatalogCaseSchema } from "../src/schema/case";
import { caseToScenario, resolveModelTemplate } from "../src/emit-scenario";

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
    assert.equal(scn.id, "todo");
    assert.equal(scn.mode, "AGENT");
    assert.equal(scn.fixture, "fixtures/todo");
    assert.equal(scn.prompt_file, "prompts/todo.md");
    assert.equal(scn.max_iterations, 40);
  });

  it("maps needles onto the deliverable path with {{MODEL_ID}} resolved for e2e", () => {
    assert.deepEqual(scn.assert.needles_in_file, [
      { path: "todo_model_01.html", regex: "localStorage" },
      { path: "todo_model_01.html", text: "Todo" },
    ]);
    assert.deepEqual(scn.assert.tool_order, ["create_new_file"]);
    assert.deepEqual(scn.assert.smoke, { entry: "todo_model_01.html", dom_present: ["input"] });
  });

  it("omits keys that do not apply to an AGENT single-file case", () => {
    assert.equal("needle_in_output" in scn.assert, false);
    assert.equal("build_cmd" in scn.assert, false);
    assert.equal("file_unchanged" in scn.assert, false);
  });

  it("keeps the judge criteria", () => {
    assert.deepEqual(scn.judge.criteria, ["one self-contained todo.html"]);
  });
});

describe("caseToScenario (PLAN)", () => {
  const scn = caseToScenario(planCase);

  it("emits needle_in_output and file_unchanged, not needles_in_file", () => {
    assert.deepEqual(scn.assert.needle_in_output, { regex: "OrderParser" });
    assert.deepEqual(scn.assert.file_unchanged, ["src/pipeline/OrderParser.kt"]);
    assert.equal("needles_in_file" in scn.assert, false);
    assert.equal("smoke" in scn.assert, false);
  });
});

describe("resolveModelTemplate", () => {
  it("substitutes every {{MODEL_ID}} occurrence with the token", () => {
    assert.equal(
      resolveModelTemplate("snake_{{MODEL_ID}}_01.html", "ollama-qwen"),
      "snake_ollama-qwen_01.html",
    );
    assert.equal(resolveModelTemplate("no placeholder here", "x"), "no placeholder here");
  });
});
