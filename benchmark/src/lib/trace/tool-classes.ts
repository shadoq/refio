// What each agent calls its tools, mapped onto the five classes a cross-harness
// comparison can be read in. The names are what the agent itself reports, so this is
// the single place to fix when a CLI renames one of them.
import type { ToolClass } from "./types";
import { MAX_TEXT } from "./types";

type ClassTable = Partial<Record<ToolClass, string[]>>;

const TOOLS_BY_HARNESS: Record<string, ClassTable> = {
  refio: {
    read: ["read_file", "read_directory", "view_diff"],
    write: [
      "code_editing",
      "advance_code_editing",
      "create_new_file",
      "multi_line_editor",
      "multi_edit",
      "rename_symbol",
    ],
    shell: ["run_terminal_command", "run_code", "run_process_background", "monitor_process"],
    search: ["grep_search", "file_search", "rag_search", "find_usages", "code_intelligence", "web_search"],
  },
  "claude-code": {
    read: ["read", "glob", "ls", "notebookread"],
    write: ["write", "edit", "multiedit", "notebookedit"],
    shell: ["bash"],
    search: ["grep", "websearch", "webfetch"],
  },
  // Codex reports what it did, not which tool it used: a shell command and a file
  // change are the two kinds of item it emits.
  codex: {
    write: ["file_change"],
    shell: ["command_execution"],
  },
  // Names taken from the installed Gemini CLI's own tool registry; search_file_content
  // is the legacy alias of grep_search and still appears in older transcripts.
  "gemini-cli": {
    read: ["read_file", "read_many_files", "list_directory"],
    write: ["write_file", "replace"],
    shell: ["run_shell_command"],
    search: ["glob", "grep_search", "search_file_content", "google_web_search", "web_fetch"],
  },
};

const LOOKUP: Record<string, Map<string, ToolClass>> = Object.fromEntries(
  Object.entries(TOOLS_BY_HARNESS).map(([harness, table]) => [
    harness,
    new Map(
      Object.entries(table).flatMap(([cls, names]) =>
        (names ?? []).map((n): [string, ToolClass] => [n.toLowerCase(), cls as ToolClass]),
      ),
    ),
  ]),
);

// An unknown tool is counted, never dropped: an agent that grows a new tool must show
// up in the histogram rather than silently vanish from the comparison.
export function classifyTool(harnessId: string, tool: string): ToolClass {
  return LOOKUP[harnessId]?.get(tool.toLowerCase()) ?? "other";
}

// Argument keys worth keeping: they identify WHAT the call touched. Everything else,
// above all the file content an edit carries, is deliberately dropped - it is already
// in the artifact and would add hundreds of kilobytes per run to the repository.
const IDENTIFYING_KEYS = [
  "file_path",
  "path",
  "filePath",
  "command",
  "cmd",
  "pattern",
  "query",
  "old_string",
];

const MAX_VALUE = 80;

export function summarizeArgs(args: unknown): string {
  if (args === null || typeof args !== "object") return "";
  const obj = args as Record<string, unknown>;
  const parts: string[] = [];
  for (const key of IDENTIFYING_KEYS) {
    const value = obj[key];
    if (value === undefined || value === null) continue;
    const text = typeof value === "string" ? value : JSON.stringify(value);
    parts.push(`${key}=${text.slice(0, MAX_VALUE)}`);
  }
  return parts.join(", ").slice(0, MAX_TEXT);
}

// Codex reports "I ran a command", never "I used the read tool", so its reads and its
// searches are invisible unless the command itself is read. Without this a Codex run
// looks like an agent that never looks at anything, sitting next to harnesses whose
// reads carry a name - and the reads/searches columns stop meaning the same thing in
// every row.
const SEARCH_COMMAND_RE = /^(grep|rg|ag|ack|find|fd|tree)\b/;
const READ_COMMAND_RE = /^(cat|head|tail|less|more|bat|sed -n|nl)\b/;

// Prefixes that say how a command runs, not what it does; the classification is about
// the command underneath them.
const COMMAND_PREFIX_RE = /^(sudo|nohup|time|env(\s+[A-Za-z_][A-Za-z0-9_]*=\S*)*)\s+/;

// Codex hands every command to a login shell, so what it reports is the wrapper and
// not the work. Left wrapped, six `cat` calls in a row are recorded as six anonymous
// shell runs and the reads column of that harness reads zero.
const SHELL_WRAPPER_RE = /^(?:\S*\/)?(?:ba|z|k|da)?sh\s+(?:-[a-z]+\s+)*-[a-z]*c\s+(['"])([\s\S]*)\1\s*$/;

// The ways a command puts bytes into a file. An agent with no edit tool of its own does
// all its work this way, and counting none of it as a write makes a run that repaired
// the code look identical to one that only looked around.
const WRITE_REDIRECT_RE = /(^|[^0-9>&])>>?\s*(?!\/dev\/(null|stderr|stdout))[^\s|&;<>]+/;
const WRITE_COMMAND_RE =
  /(^|[|;&]\s*)(tee\b|cp\b|mv\b|install\b|patch\b|apply_patch\b|touch\b|sed\s+-i\b|perl\s+-i\b|ruby\s+-i\b)/;
const WRITE_API_RE = /\b(writeFileSync|writeFile|appendFileSync|open\([^)]*['"][wa])/;

// Peel the wrapper off until the real command is on top.
function unwrapCommand(command: string): string {
  let rest = command.trim();
  for (let i = 0; i < 4; i += 1) {
    const wrapped = SHELL_WRAPPER_RE.exec(rest);
    if (wrapped) {
      rest = wrapped[2].trim();
      continue;
    }
    const stripped = rest.replace(COMMAND_PREFIX_RE, "");
    if (stripped === rest) break;
    rest = stripped;
  }
  return rest;
}

export function classifyShellCommand(command: string): ToolClass {
  const rest = unwrapCommand(command);
  // A command that both reads and writes was issued for the change it made.
  if (WRITE_COMMAND_RE.test(rest) || WRITE_REDIRECT_RE.test(rest) || WRITE_API_RE.test(rest)) {
    return "write";
  }
  if (SEARCH_COMMAND_RE.test(rest)) return "search";
  if (READ_COMMAND_RE.test(rest)) return "read";
  return "shell";
}

// A shell command that builds, tests or probes the thing the agent just wrote. It is
// the evidence that the MODEL checked its own work instead of handing back untested
// output - a behaviour worth comparing between harnesses.
export const BUILD_OR_TEST_RE =
  /\b(npm (test|run build|run check)|node --test|node [^\s]+\.(m?js)|pytest|python3? (-m unittest|[^\s]+\.py)|gradlew|gradle |mvn |cargo (test|build|check)|make\b|tsc\b|go (test|build)|curl )/;
