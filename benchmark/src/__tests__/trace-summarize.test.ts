// @vitest-environment node
import { describe, it, expect } from "vitest";
import { summarize, toJsonl, endReasonOf, pathsInArgs } from "@/lib/trace/summarize";
import type { TraceEvent } from "@/lib/trace/types";

let i = 0;
function call(tool: string, cls: TraceEvent["cls"], tMs: number, args = ""): TraceEvent {
  return { i: i++, tMs, turn: 1, kind: "tool_call", tool, cls, args, ok: null, text: null };
}

const events: TraceEvent[] = [
  { i: i++, tMs: 0, turn: 1, kind: "assistant_text", tool: null, cls: null, args: null, ok: null, text: "a" },
  call("Read", "read", 100),
  call("Read", "read", 200),
  { i: i++, tMs: 250, turn: 2, kind: "assistant_text", tool: null, cls: null, args: null, ok: null, text: "b" },
  call("Write", "write", 300, "file_path=a.html"),
  call("Bash", "shell", 400, "command=node --test test/"),
  { i: i++, tMs: 450, turn: 3, kind: "assistant_text", tool: null, cls: null, args: null, ok: null, text: "c" },
  call("Write", "write", 500, "file_path=a.html"),
  { i: i++, tMs: 600, turn: 3, kind: "error", tool: null, cls: null, args: null, ok: null, text: "boom" },
];

const paths = { path: "attachments/x/_trace/trace.jsonl", rawPath: "attachments/x/_trace/raw.log" };

describe("summarize", () => {
  it("counts what the agent did, by class", () => {
    const s = summarize(events, "claude-stream-json", paths);
    expect(s).toMatchObject({
      turns: 3,
      toolCalls: 5,
      reads: 2,
      writes: 2,
      shellRuns: 1,
      searches: 0,
      otherCalls: 0,
      toolErrors: 0,
    });
    expect(s.toolHistogram).toEqual({ Read: 2, Write: 2, Bash: 1 });
  });

  it("records when the first write happened and how much came after it", () => {
    const s = summarize(events, "claude-stream-json", paths);
    expect(s.firstWriteAtCall).toBe(3);
    expect(s.timeToFirstWriteMs).toBe(300);
    expect(s.editsAfterFirstWrite).toBe(1);
  });

  it("sees that the model ran the tests itself", () => {
    expect(summarize(events, "claude-stream-json", paths).selfVerified).toBe(true);
  });

  it("leaves the write metrics empty for a run that wrote nothing", () => {
    const s = summarize([call("Read", "read", 10)], "codex-jsonl", paths);
    expect(s.firstWriteAtCall).toBeNull();
    expect(s.timeToFirstWriteMs).toBeNull();
    expect(s.writes).toBe(0);
    expect(s.selfVerified).toBe(false);
  });

  it("counts a failed tool result as a tool error", () => {
    const failed: TraceEvent = {
      i: 0, tMs: 1, turn: 1, kind: "tool_result", tool: "Bash", cls: "shell", args: null, ok: false, text: null,
    };
    expect(summarize([failed], "codex-jsonl", paths).toolErrors).toBe(1);
  });
});

describe("toJsonl", () => {
  it("writes one parsable line per event and no trailing blank line", () => {
    const text = toJsonl(events.slice(0, 3));
    const lines = text.split("\n");
    expect(lines).toHaveLength(3);
    expect(() => lines.map((l) => JSON.parse(l))).not.toThrow();
  });
});

// An assistant message that carries only a tool call and no text is still a turn. A
// local model that reasons silently and then acts would otherwise be recorded as
// having taken zero turns while making a dozen tool calls.
describe("summarize counting turns", () => {
  it("counts an assistant message that said nothing out loud", () => {
    const silent: TraceEvent[] = [
      { i: 0, tMs: 0, turn: 1, kind: "tool_call", tool: "Bash", cls: "shell", args: "command=ls", ok: null, text: null },
      { i: 1, tMs: 5, turn: 1, kind: "tool_result", tool: "Bash", cls: "shell", args: null, ok: true, text: null },
      { i: 2, tMs: 9, turn: 2, kind: "tool_call", tool: "Read", cls: "read", args: "file_path=a", ok: null, text: null },
      { i: 3, tMs: 10, turn: 3, kind: "run_end", tool: null, cls: null, args: null, ok: null, text: "INCOMPLETE" },
    ];
    expect(summarize(silent, "claude-stream-json", paths).turns).toBe(3);
  });

  it("still counts turns that did speak", () => {
    expect(summarize(events, "claude-stream-json", paths).turns).toBe(3);
  });
});

// The metrics that tell a loop that made progress apart from one that thrashed. They
// are the difference between "how much did this agent do" and "how well did it work".
describe("summarize: wasted work and recovery", () => {
  function result(ok: boolean, exit: number | null = null): TraceEvent {
    return { i: i++, tMs: 0, turn: 1, kind: "tool_result", tool: "Bash", cls: "shell", args: null, ok, exit, text: null };
  }

  it("counts a call the agent had already made as wasted", () => {
    const s = summarize(
      [call("Read", "read", 1, "file_path=a.js"), call("Read", "read", 2, "file_path=a.js")],
      "claude-stream-json",
      paths,
    );
    expect(s.duplicateCalls).toBe(1);
    expect(s.repeatedCallStreak).toBe(2);
  });

  it("does not call two reads of different files a repeat", () => {
    const s = summarize(
      [call("Read", "read", 1, "file_path=a.js"), call("Read", "read", 2, "file_path=b.js")],
      "claude-stream-json",
      paths,
    );
    expect(s.duplicateCalls).toBe(0);
    expect(s.repeatedCallStreak).toBe(0);
  });

  it("measures how long the agent kept retrying the same failing call", () => {
    const stuck: TraceEvent[] = [
      call("Bash", "shell", 1, "command=npm test"), result(false),
      call("Bash", "shell", 2, "command=npm test"), result(false),
      call("Bash", "shell", 3, "command=npm test"), result(false),
    ];
    expect(summarize(stuck, "claude-stream-json", paths).repeatedFailedCallStreak).toBe(3);
  });

  it("says whether the agent did anything after its last failure", () => {
    const gaveUp: TraceEvent[] = [call("Bash", "shell", 1, "command=npm test"), result(false)];
    expect(summarize(gaveUp, "claude-stream-json", paths).recoveredFromError).toBe(false);

    const fixed: TraceEvent[] = [...gaveUp, call("Write", "write", 2, "file_path=a.js")];
    expect(summarize(fixed, "claude-stream-json", paths).recoveredFromError).toBe(true);
  });

  it("leaves recovery unmeasured when nothing ever failed", () => {
    expect(summarize([call("Read", "read", 1)], "claude-stream-json", paths).recoveredFromError).toBeNull();
  });

  it("separates a failed tool call from a command that merely returned non-zero", () => {
    const s = summarize(
      [call("command_execution", "search", 1, "command=grep x a.txt"), result(true, 1)],
      "codex-jsonl",
      paths,
    );
    expect(s.toolErrors).toBe(0);
    expect(s.nonZeroExits).toBe(1);
  });

  it("counts distinct files written, not writing calls", () => {
    const s = summarize(
      [
        call("Write", "write", 1, "file_path=a.js"),
        call("Edit", "write", 2, "file_path=a.js"),
        call("Write", "write", 3, "file_path=b.js"),
      ],
      "claude-stream-json",
      paths,
    );
    expect(s.writes).toBe(3);
    expect(s.filesWritten).toBe(2);
  });

  it("counts the looking the agent did before it committed to anything", () => {
    const s = summarize(
      [
        call("Grep", "search", 1, "pattern=handler"),
        call("Read", "read", 2, "file_path=a.js"),
        call("Write", "write", 3, "file_path=a.js"),
        call("Read", "read", 4, "file_path=b.js"),
      ],
      "claude-stream-json",
      paths,
    );
    expect(s.readsBeforeFirstWrite).toBe(1);
    expect(s.searchesBeforeFirstWrite).toBe(1);
  });

  // A build run before any code was written checks the fixture, not the agent's work.
  it("does not treat a build run before the first write as self-verification", () => {
    const early: TraceEvent[] = [
      call("Bash", "shell", 1, "command=npm test"),
      call("Write", "write", 2, "file_path=a.js"),
    ];
    expect(summarize(early, "claude-stream-json", paths).selfVerified).toBe(false);
  });
});

describe("endReasonOf", () => {
  it("tells a run that hit its cap apart from one that simply failed", () => {
    expect(endReasonOf("INCOMPLETE")).toBe("incomplete");
    expect(endReasonOf("error_max_turns")).toBe("limit");
    expect(endReasonOf("FAILED")).toBe("failed");
    expect(endReasonOf("SUCCESS")).toBe("completed");
    expect(endReasonOf("CANCELLED")).toBe("cancelled");
    expect(endReasonOf(null)).toBe("unknown");
  });
});

// A harness with no edit tool of its own names the file it wrote inside the command,
// so a summary that only reads a path= argument reports it as having written to no
// files at all - next to harnesses whose writes carry a path argument.
describe("pathsInArgs for a shell command", () => {
  it("reads the target of a redirect", () => {
    expect(pathsInArgs(`command=/bin/zsh -lc "cat > ./src/format.js << 'EOF'"`)).toEqual([
      "./src/format.js",
    ]);
    expect(pathsInArgs(`command=echo hi >> notes.txt`)).toEqual(["notes.txt"]);
  });

  it("reads the file an in-place edit names", () => {
    expect(pathsInArgs(`command=sed -i '' 's/a/b/' src/x.js`)).toEqual(["src/x.js"]);
  });

  it("still reads a plain path argument", () => {
    expect(pathsInArgs("path=src/a.js")).toEqual(["src/a.js"]);
  });

  it("adds nothing for a command whose target it cannot read", () => {
    expect(pathsInArgs(`command=node build.js`)).toEqual([]);
    expect(pathsInArgs(`command=node --test > /dev/null`)).toEqual([]);
  });
});

// What the run did, in order, in the vocabulary every harness shares. A catalog case
// that says "create the file, do not just describe it" has to be checkable against an
// agent whose only tool is a shell, or the expectation can only ever be scored for the
// one harness that names its tools - and only ever against it.
describe("summarize classOrder", () => {
  it("records the class of every call in the order they were made", () => {
    const s = summarize(
      [
        { i: 0, tMs: 0, turn: 0, kind: "tool_call", tool: "grep_search", cls: "search", args: "", ok: null, text: null },
        { i: 1, tMs: 1, turn: 0, kind: "tool_call", tool: "read_file", cls: "read", args: "path=a.js", ok: null, text: null },
        { i: 2, tMs: 2, turn: 1, kind: "tool_call", tool: "create_new_file", cls: "write", args: "path=b.js", ok: null, text: null },
      ] as TraceEvent[],
      "refio-run-json",
      { path: "p" },
    );
    expect(s.classOrder).toEqual(["search", "read", "write"]);
  });

  it("is an empty list for a run that called nothing", () => {
    expect(summarize([], "refio-run-json", { path: "p" }).classOrder).toEqual([]);
  });
});
