// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  pickDeliverable,
  attachmentForDeliverable,
  SCAFFOLDING_FILES,
} from "@/lib/catalog/deliverable";

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

// A multi-file case delivers a module, not a page: it cannot be rendered, so it is
// attached as a plain file and scored through its build command instead.
describe("attachmentForDeliverable", () => {
  it("treats a page as the renderable html artifact", () => {
    expect(attachmentForDeliverable("snake_x_01.html")).toEqual({
      kind: "html",
      fileName: "artifact.html",
    });
    expect(attachmentForDeliverable("page.HTM").kind).toBe("html");
  });

  it("keeps a source file under its own name", () => {
    expect(attachmentForDeliverable("src/server.js")).toEqual({
      kind: "file",
      fileName: "server.js",
    });
  });

  // The runner hands over an absolute path produced by node's join(), so on Windows the
  // separator is a backslash. Reading only forward slashes made the whole path the file
  // name, and copying the artifact then landed outside the queue entry and threw.
  it("keeps the file name when the path uses backslashes", () => {
    expect(
      attachmentForDeliverable("C:\\Users\\a\\AppData\\Local\\Temp\\work\\src\\lib\\text.js"),
    ).toEqual({
      kind: "file",
      fileName: "text.js",
    });
  });

  it("recognises a page reached by a backslash path", () => {
    expect(attachmentForDeliverable("C:\\tmp\\work\\index.html").kind).toBe("html");
  });
});
