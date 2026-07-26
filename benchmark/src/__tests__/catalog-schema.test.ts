// @vitest-environment node
import { describe, it, expect } from "vitest";
import { CatalogCaseSchema } from "@/schema/catalog";

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
  judge: { criteria: ["todo.html is one self-contained file with add/complete/delete/filter + localStorage."] },
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
    expect(CatalogCaseSchema.safeParse(validAgentCase).success).toBe(true);
  });

  it("parses a valid PLAN analysis case without a deliverable", () => {
    expect(CatalogCaseSchema.safeParse(validPlanCase).success).toBe(true);
  });

  it("rejects an AGENT case that has no deliverable file", () => {
    const { deliverable, ...noDeliverable } = validAgentCase;
    void deliverable;
    expect(CatalogCaseSchema.safeParse(noDeliverable).success).toBe(false);
  });

  it("rejects an unknown category", () => {
    expect(
      CatalogCaseSchema.safeParse({ ...validAgentCase, category: "spreadsheet" }).success,
    ).toBe(false);
  });

  it("rejects an id with uppercase letters", () => {
    expect(CatalogCaseSchema.safeParse({ ...validAgentCase, id: "Todo" }).success).toBe(false);
  });

  it("applies defaults for maxIterations, extraCriteria and noContextOverflow", () => {
    const parsed = CatalogCaseSchema.safeParse(validAgentCase);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.maxIterations).toBe(40);
      expect(parsed.data.review.extraCriteria).toEqual([]);
      expect(parsed.data.assert.noContextOverflow).toBe(true);
    }
  });
});
