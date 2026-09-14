// @vitest-environment node
import { describe, it, expect } from "vitest";
import { execShell, quoteArg } from "../exec";

// Arguments go through `sh -c`, so anything the shell expands inside double quotes has
// to be escaped. A prompt naming a file in backticks used to be executed as a command
// and never reached the agent.
describe("quoteArg", () => {
  it("keeps backticks literal", async () => {
    const r = await execShell(`echo ${quoteArg("deliver `snake.html` please")}`, {
      timeoutMs: 5000,
    });
    expect(r.stdout.trim()).toBe("deliver `snake.html` please");
  });

  it("keeps a dollar sign literal", async () => {
    const r = await execShell(`echo ${quoteArg("costs $HOME and $(pwd)")}`, { timeoutMs: 5000 });
    expect(r.stdout.trim()).toBe("costs $HOME and $(pwd)");
  });

  // printf rather than echo: the shell's echo eats backslash escapes itself, which
  // would test echo instead of the quoting.
  it("keeps quotes and backslashes literal", async () => {
    const r = await execShell(`printf '%s' ${quoteArg('say "hi" a\\b')}`, { timeoutMs: 5000 });
    expect(r.stdout).toBe('say "hi" a\\b');
  });

  it("keeps a multi-line prompt in one argument", async () => {
    const r = await execShell(`echo ${quoteArg("line one\nline two")}`, { timeoutMs: 5000 });
    expect(r.stdout.trim()).toBe("line one\nline two");
  });
});
