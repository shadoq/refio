// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  classifyTool,
  classifyShellCommand,
  summarizeArgs,
  BUILD_OR_TEST_RE,
} from "@/lib/trace/tool-classes";

describe("classifyTool", () => {
  it("classifies the file-authoring tool of each harness as a write", () => {
    expect(classifyTool("claude-code", "Write")).toBe("write");
    expect(classifyTool("refio", "advance_code_editing")).toBe("write");
    expect(classifyTool("gemini-cli", "write_file")).toBe("write");
  });

  it("classifies searching and reading apart", () => {
    expect(classifyTool("refio", "grep_search")).toBe("search");
    expect(classifyTool("refio", "read_file")).toBe("read");
    expect(classifyTool("claude-code", "Read")).toBe("read");
  });

  it("classifies a shell runner as shell", () => {
    expect(classifyTool("codex", "command_execution")).toBe("shell");
    expect(classifyTool("refio", "run_terminal_command")).toBe("shell");
  });

  it("ignores letter case, because agents spell their tools differently", () => {
    expect(classifyTool("claude-code", "write")).toBe("write");
  });

  it("falls back to other for a tool the table does not know", () => {
    expect(classifyTool("claude-code", "TodoWrite")).toBe("other");
    expect(classifyTool("no-such-harness", "Read")).toBe("other");
  });
});

describe("summarizeArgs", () => {
  // File content would push hundreds of kilobytes of the model's output into the
  // repository, and it is already kept in the artifact.
  it("keeps the identifying arguments and drops file content", () => {
    const out = summarizeArgs({ file_path: "a.html", content: "<html>".repeat(200) });
    expect(out).toContain("file_path=a.html");
    expect(out).not.toContain("<html>");
  });

  it("never grows beyond the line budget", () => {
    const out = summarizeArgs({ command: "x".repeat(5000), pattern: "y".repeat(5000) });
    expect(out.length).toBeLessThanOrEqual(300);
  });

  it("returns an empty string when nothing identifying is present", () => {
    expect(summarizeArgs({ new_string: "abc" })).toBe("");
  });
});

describe("BUILD_OR_TEST_RE", () => {
  it("recognises the commands that mean the model checked its own work", () => {
    expect(BUILD_OR_TEST_RE.test("node --test test/")).toBe(true);
    expect(BUILD_OR_TEST_RE.test("python3 -m unittest")).toBe(true);
    expect(BUILD_OR_TEST_RE.test("npm test")).toBe(true);
  });

  it("does not treat looking around as a check", () => {
    expect(BUILD_OR_TEST_RE.test("ls -la")).toBe(false);
    expect(BUILD_OR_TEST_RE.test("cat README.md")).toBe(false);
  });
});

// Codex never names a read tool: it says it ran a command. Reading the command is the
// only way its reads and searches mean the same thing as every other harness's.
describe("classifyShellCommand", () => {
  it("reads a file-dumping command as a read", () => {
    expect(classifyShellCommand("cat src/server.js")).toBe("read");
    expect(classifyShellCommand("sed -n '1,40p' src/a.ts")).toBe("read");
  });

  it("reads a searching command as a search", () => {
    expect(classifyShellCommand("grep -rn handler src/")).toBe("search");
    expect(classifyShellCommand("rg --files")).toBe("search");
    expect(classifyShellCommand("find . -name '*.test.js'")).toBe("search");
  });

  it("leaves anything that does work as a shell run", () => {
    expect(classifyShellCommand("npm test")).toBe("shell");
    expect(classifyShellCommand("node --test")).toBe("shell");
  });

  it("looks past the prefixes that only say how a command runs", () => {
    expect(classifyShellCommand("sudo cat /etc/hosts")).toBe("read");
    expect(classifyShellCommand("env FOO=1 grep x a.txt")).toBe("search");
  });
});

// Every one of these is a command a real Codex run against a local model actually
// issued on the trap-decoy task. The run repaired the file and the test suite went
// green, and the trace recorded zero reads and zero writes, because Codex wraps each
// command in a login shell and does all its editing through the shell. A comparison
// that reads those columns was therefore reading nothing.
describe("classifyShellCommand through a shell wrapper", () => {
  it("sees the command inside a login shell", () => {
    expect(classifyShellCommand(`/bin/zsh -lc 'cat ./src/format.js'`)).toBe("read");
    expect(classifyShellCommand(`/bin/zsh -lc 'find . -type f -name "*.js"'`)).toBe("search");
    expect(classifyShellCommand(`bash -c "grep -rn slugify src"`)).toBe("search");
    expect(classifyShellCommand(`/bin/sh -c 'node --test'`)).toBe("shell");
  });

  it("still reads a bare command", () => {
    expect(classifyShellCommand("cat src/a.js")).toBe("read");
    expect(classifyShellCommand("npm test")).toBe("shell");
  });
});

describe("classifyShellCommand for a command that writes a file", () => {
  it("counts the ways a shell writes a file", () => {
    expect(classifyShellCommand(`/bin/zsh -lc "cat > ./src/format.js << 'EOF'\nx\nEOF"`)).toBe("write");
    expect(classifyShellCommand(`/bin/zsh -lc "printf '%s\\n' 'x' > ./src/format.js"`)).toBe("write");
    expect(classifyShellCommand(`/bin/zsh -lc "sed -i '' 's/a/b/' src/x.js"`)).toBe("write");
    expect(classifyShellCommand(`tee src/out.js`)).toBe("write");
    expect(classifyShellCommand(`cp src/a.js src/b.js`)).toBe("write");
    expect(classifyShellCommand(`mv src/a.js src/b.js`)).toBe("write");
    expect(classifyShellCommand(`node -e "require('fs').writeFileSync('a.js', s)"`)).toBe("write");
    expect(classifyShellCommand(`echo hi >> notes.txt`)).toBe("write");
  });

  // Sending output to nowhere, or reading a here-document into a pipe, is not a write,
  // and a comparison that counts it inflates exactly the harness that shells out most.
  it("does not call a discarded or piped output a write", () => {
    expect(classifyShellCommand(`/bin/zsh -lc 'node --test > /dev/null'`)).toBe("shell");
    expect(classifyShellCommand(`/bin/zsh -lc 'cat src/a.js | grep x'`)).toBe("read");
    expect(classifyShellCommand(`/bin/zsh -lc 'ls -la'`)).toBe("shell");
    expect(classifyShellCommand(`/bin/zsh -lc 'node --test ./test/receipt.test.js'`)).toBe("shell");
  });

  // A write wins over the read that fed it: the point of the call was the change.
  it("prefers write over read when the command does both", () => {
    expect(classifyShellCommand(`cat template.js > src/format.js`)).toBe("write");
  });
});
