// @vitest-environment node
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderPrompt } from "../evidence";

const template = readFileSync(
  join(import.meta.dirname, "..", "..", "prompts", "judge-artifact.md"),
  "utf8",
);

const criteria = [
  {
    id: "compliance",
    name: "Compliance",
    description: "Are the requirements covered?",
    scale: { values: [0, 1, 2, 3, 4, 5, 6] },
    weight: 1,
  },
];

// The task's judge instructions are how a reviewer tells the judge what matters on
// THIS task, so they must reach the judge in the real template, and a task without
// them must not leave an empty heading or a raw placeholder behind.
describe("renderPrompt", () => {
  it("passes the task's judge instructions to the judge under their own heading", () => {
    const text = renderPrompt(template, "Build snake", criteria, "The CPU snake must chase the food.");
    expect(text).toContain("## How to judge this task\n\nThe CPU snake must chase the food.");
    expect(text.indexOf("How to judge this task")).toBeLessThan(text.indexOf("## How to score"));
  });

  it("leaves no heading and no placeholder when the task has no instructions", () => {
    for (const none of [undefined, "", "   "]) {
      const text = renderPrompt(template, "Build snake", criteria, none);
      expect(text).not.toContain("How to judge this task");
      expect(text).not.toContain("{{");
    }
  });
});
