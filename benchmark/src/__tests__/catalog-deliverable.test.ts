// @vitest-environment node
import { describe, it, expect } from "vitest";
import { pickDeliverable, SCAFFOLDING_FILES } from "@/lib/catalog/deliverable";

// A tasks.json task states the deliverable filename inside its prompt text rather than
// in a field, so the runner finds the artifact by looking at what the run produced.
describe("pickDeliverable", () => {
  it("returns the single html file the run produced", () => {
    expect(pickDeliverable(["snake_ollama-qwen3.8-27b_01.html"])).toBe(
      "snake_ollama-qwen3.8-27b_01.html",
    );
  });

  it("ignores the files the harness itself writes into the work dir", () => {
    const produced = [...SCAFFOLDING_FILES, "snake_x_01.html"];
    expect(pickDeliverable(produced)).toBe("snake_x_01.html");
  });

  it("prefers an html artifact over other files the agent left behind", () => {
    expect(pickDeliverable(["notes.md", "game.html", "scratch.txt"])).toBe("game.html");
  });

  // Two html files means the agent did something the task did not ask for, and
  // guessing which one to score would silently pick a winner. Report it instead.
  it("returns null when the run produced more than one html artifact", () => {
    expect(pickDeliverable(["a.html", "b.html"])).toBeNull();
  });

  it("returns null when the run produced nothing scoreable", () => {
    expect(pickDeliverable([...SCAFFOLDING_FILES])).toBeNull();
    expect(pickDeliverable([])).toBeNull();
  });
});
