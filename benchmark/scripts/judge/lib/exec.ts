// Shell execution helper for judge CLIs. Uses `shell: true` so Windows `.cmd`
// shims (claude, codex) resolve; callers must quote arguments with quoteArg.
import { spawn, execFile } from "node:child_process";
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

// The command is handed to a shell, so the spawned process is the shell and the agent
// runs underneath it. Signalling the shell alone leaves the agent running, still
// holding the output pipe, so "close" never arrives. Windows has no process groups to
// signal, so the tree is taken down by pid.
function killTree(pid: number | undefined, force: boolean): void {
  if (pid === undefined) return;
  if (process.platform === "win32") {
    const args = ["/pid", String(pid), "/T", ...(force ? ["/F"] : [])];
    execFile("taskkill", args, () => {});
    return;
  }
  const signal = force ? "SIGKILL" : "SIGTERM";
  try {
    process.kill(-pid, signal);
  } catch {
    try {
      process.kill(pid, signal);
    } catch {
      // Already gone.
    }
  }
}

// An agent buffers its event stream, so killing it outright throws away the record of
// everything it did before the deadline - and a timed-out attempt is worth reading
// precisely because it shows where the agent got stuck. So it is asked to stop first
// and only forced once it has had this long to write what it had.
const KILL_GRACE_MS = 5000;

// After the forced kill the pipes are given a moment to drain; a grandchild that
// survives even that must not hold the sweep, so the result is returned regardless.
const DRAIN_AFTER_FORCE_MS = 2000;

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
      // Its own process group, so a POSIX kill reaches the agent and not just the shell.
      detached: process.platform !== "win32",
    });
    let stdout = "";
    let stderr = "";
    let pending = "";
    let timedOut = false;
    let settled = false;
    let graceTimer: NodeJS.Timeout | undefined;
    const settle = (code: number | null): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (graceTimer) clearTimeout(graceTimer);
      if (opts.onStdoutLine && pending.trim() !== "") {
        opts.onStdoutLine({ line: pending, tMs: Date.now() - startedAt });
      }
      resolveP({ code, stdout, stderr, timedOut });
    };
    const timer = setTimeout(() => {
      timedOut = true;
      killTree(child.pid, false);
      graceTimer = setTimeout(() => {
        killTree(child.pid, true);
        graceTimer = setTimeout(() => settle(null), DRAIN_AFTER_FORCE_MS);
      }, KILL_GRACE_MS);
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
    child.on("error", () => settle(null));
    child.on("close", (code) => settle(code));
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
