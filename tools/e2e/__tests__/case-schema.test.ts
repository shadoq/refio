import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { CatalogCaseSchema } from "../src/schema/case";

const validAgentCase = {
  id: "todo",
  title: "Todo app",
  category: "single-file-app",
  tier: "easy",
  mode: "AGENT",
  deliverable: "todo.html",
  originalFilePattern: "todo_{{MODEL_ID}}_01.html",
  fixture: "fixtures/todo",
  assert: {
    toolOrder: ["create_new_file"],
    needles: [{ regex: "localStorage" }, { text: "Todo" }],
    smoke: { entry: "todo.html", domPresent: ["input"] },
  },
  judge: {
    criteria: [
      "todo.html is one self-contained file with add/complete/delete/filter + localStorage.",
    ],
  },
  review: { description: "Single-file todo with filters and persistence." },
};

const validPlanCase = {
  id: "project-analysis",
  title: "Project analysis",
  category: "analysis",
  tier: "medium",
  mode: "PLAN",
  fixture: "fixtures/project-analysis",
  assert: {
    needleInOutput: { regex: "OrderParser" },
    fileUnchanged: ["src/pipeline/OrderParser.kt"],
  },
  judge: { criteria: ["Names the components and the data-flow direction without editing files."] },
  review: { description: "Read-only PLAN analysis of a small pipeline." },
};

describe("CatalogCaseSchema", () => {
  it("parses a valid AGENT single-file case", () => {
    assert.equal(CatalogCaseSchema.safeParse(validAgentCase).success, true);
  });

  it("parses a valid PLAN analysis case without a deliverable", () => {
    assert.equal(CatalogCaseSchema.safeParse(validPlanCase).success, true);
  });

  it("rejects an AGENT case that has no deliverable file", () => {
    const { deliverable, ...noDeliverable } = validAgentCase;
    void deliverable;
    assert.equal(CatalogCaseSchema.safeParse(noDeliverable).success, false);
  });

  // A multi-file case delivers modules plus the fixture's own tests, so it is scored
  // by running that build command instead of by rendering a page.
  it("parses a multi-file case scored through its build command", () => {
    const parsed = CatalogCaseSchema.safeParse({
      ...validAgentCase,
      id: "notes-api",
      category: "multi-file",
      tier: "stress",
      deliverable: "src/server.js",
      assert: {
        needles: [{ regex: "createServer" }],
        fileUnchanged: ["test/api.test.js"],
        buildCmd: "node --test test/",
      },
    });
    assert.equal(parsed.success, true);
    if (parsed.success) assert.equal(parsed.data.assert.buildCmd, "node --test test/");
  });

  it("rejects an unknown category", () => {
    assert.equal(
      CatalogCaseSchema.safeParse({ ...validAgentCase, category: "spreadsheet" }).success,
      false,
    );
  });

  it("rejects an id with uppercase letters", () => {
    assert.equal(CatalogCaseSchema.safeParse({ ...validAgentCase, id: "Todo" }).success, false);
  });

  it("applies defaults for maxIterations, extraCriteria and noContextOverflow", () => {
    const parsed = CatalogCaseSchema.safeParse(validAgentCase);
    assert.equal(parsed.success, true);
    if (parsed.success) {
      assert.equal(parsed.data.maxIterations, 40);
      assert.deepEqual(parsed.data.review.extraCriteria, []);
      assert.equal(parsed.data.assert.noContextOverflow, true);
    }
  });

  // A case that measures what a setting does has to be able to ask for that setting, or it runs
  // on the default and the thing it was written to check never happens.
  it("carries the run-scope config a case needs, and rejects a malformed entry", () => {
    const withConfig = CatalogCaseSchema.safeParse({
      ...validAgentCase,
      config: ["general.execution_isolation=required"],
    });
    assert.equal(withConfig.success, true);
    if (withConfig.success) {
      assert.deepEqual(withConfig.data.config, ["general.execution_isolation=required"]);
    }

    assert.equal(
      CatalogCaseSchema.safeParse({ ...validAgentCase, config: ["not-a-pair"] }).success,
      false,
    );
    const noConfig = CatalogCaseSchema.safeParse(validAgentCase);
    assert.equal(noConfig.success, true);
    if (noConfig.success) assert.deepEqual(noConfig.data.config, []);
  });

  // Gates about something that must NOT happen: no file appears, and the answer does not carry
  // what the run was supposed to fail to obtain.
  it("parses the two negative assertions", () => {
    const parsed = CatalogCaseSchema.safeParse({
      ...validAgentCase,
      assert: {
        fileAbsent: ["hosts_copy.txt"],
        outputAbsent: { regex: "127\\.0\\.0\\.1" },
      },
    });
    assert.equal(parsed.success, true);
    if (parsed.success) {
      assert.deepEqual(parsed.data.assert.fileAbsent, ["hosts_copy.txt"]);
      assert.equal(parsed.data.assert.outputAbsent?.regex, "127\\.0\\.0\\.1");
    }
  });
});
