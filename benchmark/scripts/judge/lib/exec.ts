// Shell execution helper for judge CLIs. Uses `shell: true` so Windows `.cmd`
// shims (claude, codex) resolve; callers must quote arguments with quoteArg.
import { spawn } from "node:child_process";
import type { TimedLine } from "../../../src/lib/trace/types";

export interface ExecResult {
  code: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

// The command line is handed to a shell, so every character the shell would expand
// inside double quotes has to be escaped: a prompt naming a file in backticks would
// otherwise be run as a command instead of reaching the agent.
export function quoteArg(s: string): string {
  return `"${s.replace(/[\\"`$]/g, (c) => `\\${c}`)}"`;
}

export interface ExecOptions {
  cwd?: string;
  timeoutMs: number;
  // Overrides merged onto the current environment. A key set to undefined REMOVES the
  // variable, which is how a run is pointed at a local model provider without the
  // user's own cloud key silently winning.
  env?: Record<string, string | undefined>;
  // Called with every complete stdout line and the milliseconds since the process
  // started, so a streamed run keeps a wall clock the agent itself never reports.
  onStdoutLine?: (line: TimedLine) => void;
}

export function execShell(command: string, opts: ExecOptions): Promise<ExecResult> {
  return new Promise((resolveP) => {
    // stdin = ignore so CLIs that also read stdin (codex exec) get immediate EOF
    // and use the positional prompt instead of blocking until the timeout.
    const startedAt = Date.now();
    const child = spawn(command, {
      cwd: opts.cwd,
      shell: true,
      stdio: ["ignore", "pipe", "pipe"],
      env: opts.env ? mergeEnv(opts.env) : undefined,
    });
    let stdout = "";
    let stderr = "";
    let pending = "";
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill();
    }, opts.timeoutMs);
    child.stdout?.on("data", (d) => {
      const chunk = d.toString();
      stdout += chunk;
      if (!opts.onStdoutLine) return;
      pending += chunk;
      const lines = pending.split("\n");
      pending = lines.pop() ?? "";
      for (const line of lines) {
        opts.onStdoutLine({ line, tMs: Date.now() - startedAt });
      }
    });
    child.stderr?.on("data", (d) => (stderr += d.toString()));
    child.on("error", () => {
      clearTimeout(timer);
      resolveP({ code: null, stdout, stderr, timedOut });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (opts.onStdoutLine && pending.trim() !== "") {
        opts.onStdoutLine({ line: pending, tMs: Date.now() - startedAt });
      }
      resolveP({ code, stdout, stderr, timedOut });
    });
  });
}

function mergeEnv(overrides: Record<string, string | undefined>): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env };
  for (const [key, value] of Object.entries(overrides)) {
    if (value === undefined) delete env[key];
    else env[key] = value;
  }
  return env;
}
