// @vitest-environment node
import { describe, it, expect } from "vitest";
import { execFileSync } from "node:child_process";
import { headlessArgs, quoteForShell } from "../run-cli";
import { HEADLESS_AUTO_APPROVE } from "../../../../src/lib/catalog/approval";

const base = {
  workDir: "/tmp/w",
  promptPath: "/tmp/w/prompt.md",
  runJsonPath: "/tmp/w/run.json",
  mode: "AGENT",
  model: "ollama/qwen3.5:9b",
};

describe("headlessArgs", () => {
  it("hands the approval expression through as one argument, unaltered", () => {
    const args = headlessArgs(base);
    const at = args.indexOf("--auto-approve");
    expect(at).toBeGreaterThan(-1);
    expect(args[at + 1]).toBe(HEADLESS_AUTO_APPROVE);
  });

  it("carries the run-scope config overrides in order", () => {
    const args = headlessArgs({ ...base, configOverrides: ["a=1", "b=2"] });
    expect(args.join(" ")).toContain("--config a=1 --config b=2");
  });
});

// The expression is full of characters a shell acts on. Unquoted it does not reach the
// CLI at all - the command line dies with a syntax error and the attempt is recorded as
// an agent that produced nothing, which is how six runs were lost.
describe("quoteForShell", () => {
  it("survives a round trip through a real shell", () => {
    const echoed = execFileSync("/bin/sh", ["-c", `printf %s ${quoteForShell(HEADLESS_AUTO_APPROVE)}`])
      .toString();
    expect(echoed).toBe(HEADLESS_AUTO_APPROVE);
  });

  it("survives a value carrying quotes, backslashes and a dollar", () => {
    const nasty = 'a"b\\c$d`e';
    const echoed = execFileSync("/bin/sh", ["-c", `printf %s ${quoteForShell(nasty)}`]).toString();
    expect(echoed).toBe(nasty);
  });
});
