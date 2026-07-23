// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  scenarioIdFor,
  describeInteractions,
  INTERACTION_STEPS,
} from "@/lib/judge/interactions";
import type { InteractionRecord } from "@/lib/judge/interactions";

describe("scenarioIdFor", () => {
  // Clicking buttons proves nothing for these two: snake only reacts to the
  // keyboard, and a todo app shows an empty list until something is typed.
  it("gives snake and todo their own scenario instead of generic clicking", () => {
    expect(scenarioIdFor("snake")).toBe("snake");
    expect(scenarioIdFor("todo-app")).toBe("todo");
  });

  // A task added to the benchmark later must still produce interaction evidence
  // without anyone writing a scenario for it first.
  it("falls back to the generic scenario for any other task", () => {
    expect(scenarioIdFor("website-museum-night")).toBe("generic");
    expect(scenarioIdFor("some-future-task")).toBe("generic");
  });
});

describe("describeInteractions", () => {
  const record = (over: Partial<InteractionRecord> = {}): InteractionRecord => ({
    shot: "interact-1.png",
    action: 'clicked button "Get tickets"',
    ok: true,
    ...over,
  });

  // Without this the judge cannot tell an unchanged screenshot from a step that
  // never ran, and scores works_out_of_box on a guess.
  it("names the step behind every screenshot", () => {
    const text = describeInteractions([record()]);
    expect(text).toContain("`interact-1.png`");
    expect(text).toContain('clicked button "Get tickets"');
  });

  it("marks a failed step so its screenshot is not read as a working control", () => {
    const text = describeInteractions([record({ ok: false, note: "element not found" })]);
    expect(text).toContain("step failed");
    expect(text).toContain("element not found");
  });

  // A click that leaves the artifact is a defect in the artifact, not a render glitch.
  it("surfaces a click that navigated away", () => {
    const text = describeInteractions([
      record({ note: "the page navigated away to file:///gone.html" }),
    ]);
    expect(text).toContain("navigated away");
  });

  it("stays empty when no interaction ran, so the prompt gains no dead section", () => {
    expect(describeInteractions([])).toBe("");
  });

  it("describes one line per collected interaction shot", () => {
    const records = Array.from({ length: INTERACTION_STEPS }, (_, i) =>
      record({ shot: `interact-${i + 1}.png` }),
    );
    const lines = describeInteractions(records)
      .split("\n")
      .filter((l) => l.startsWith("- `interact-"));
    expect(lines).toHaveLength(INTERACTION_STEPS);
  });
});
