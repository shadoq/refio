// Shell execution helper for judge CLIs. Uses `shell: true` so Windows `.cmd`
// shims (claude, codex) resolve; callers must quote arguments with quoteArg.
import { spawn } from "node:child_process";

export interface ExecResult {
  code: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export function quoteArg(s: string): string {
  return `"${s.replace(/"/g, '\\"')}"`;
}

export function execShell(
  command: string,
  opts: { cwd?: string; timeoutMs: number },
): Promise<ExecResult> {
  return new Promise((resolveP) => {
    // stdin = ignore so CLIs that also read stdin (codex exec) get immediate EOF
    // and use the positional prompt instead of blocking until the timeout.
    const child = spawn(command, {
      cwd: opts.cwd,
      shell: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill();
    }, opts.timeoutMs);
    child.stdout?.on("data", (d) => (stdout += d.toString()));
    child.stderr?.on("data", (d) => (stderr += d.toString()));
    child.on("error", () => {
      clearTimeout(timer);
      resolveP({ code: null, stdout, stderr, timedOut });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolveP({ code, stdout, stderr, timedOut });
    });
  });
}
