// Thin wrapper that runs ONE headless Refio turn into a throwaway work dir and
// returns the produced run.json plus the deliverable path. It never asserts and
// never fails on a non-zero exit - the run status is read from run.json. Executing
// this spends tokens / local GPU time, so import-runs only calls it in --run mode.
import { spawn } from "node:child_process";
import { mkdtemp, cp, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { HEADLESS_AUTO_APPROVE } from "../../../src/lib/catalog/approval";

// Only the Windows path goes through a shell (the .bat shim cannot be exec'd directly),
// and a shell re-parses every argument. The auto-approve expression is full of
// characters a shell acts on - parentheses, pipes, dollars - so an unquoted argument
// does not reach the CLI at all: it kills the command line with a syntax error and the
// run is recorded as an agent that produced nothing.
export function quoteForShell(arg: string): string {
  return `"${arg.replace(/(["\\$`])/g, "\\$1")}"`;
}

export interface HeadlessResult {
  runJson: unknown;
  deliverablePath: string | null;
  workDir: string;
  // Where the CLI wrote its run document, so the importer can keep it as the raw
  // evidence behind the trace; null when the run produced none.
  runJsonPath: string | null;
}

// The wrapper lives at the repo root and is not on PATH, so it is resolved against
// repoRoot. There is one wrapper per platform and the wrong one fails with a shell
// error rather than a run, which reads in the data as an agent that produced nothing.
// Override with REFIO_CLI to point at a different build.
function cliCommand(repoRoot: string): string {
  const wrapper = process.platform === "win32" ? "refio.bat" : "refio";
  return process.env.REFIO_CLI ?? join(repoRoot, wrapper);
}

// The headless command line, as a list. Pure so the argument list can be asserted in a
// test: a mangled argument here does not fail loudly, it produces a run that looks like
// an agent which did nothing.
export function headlessArgs(opts: {
  workDir: string;
  promptPath: string;
  runJsonPath: string;
  mode: string;
  model: string;
  maxCost?: number;
  configOverrides?: string[];
}): string[] {
  const args = [
    "-p", opts.workDir,
    "--headless",
    "--mode", opts.mode,
    "--model", opts.model,
    "--prompt-file", opts.promptPath,
    "--output", "json",
    "--output-file", opts.runJsonPath,
  ];
  if (opts.maxCost !== undefined) args.push("--max-cost", String(opts.maxCost));
  // Headless has no human to approve anything, so an ASK-level command tool waits out
  // its timeout and is recorded as a rejection - and a run that cannot start a build
  // cannot check its own work. Without this the benchmark measured a Refio with its
  // shell taken away, next to agents that had theirs.
  args.push("--auto-approve", HEADLESS_AUTO_APPROVE);
  for (const override of opts.configOverrides ?? []) args.push("--config", override);
  return args;
}

export async function runHeadless(opts: {
  repoRoot: string;
  fixtureDir: string | null;
  promptText: string; // already resolved ({{MODEL_ID}} substituted)
  mode: string;
  model: string;
  deliverable: string | null; // resolved filename the run is expected to produce
  maxCost?: number;
  // Run-scope config overrides ("key=value"), used to point the CLI at the same model
  // endpoint an external agent was given.
  configOverrides?: string[];
}): Promise<HeadlessResult> {
  const workDir = await mkdtemp(join(tmpdir(), "refio-import-"));
  if (opts.fixtureDir && existsSync(opts.fixtureDir)) {
    await cp(opts.fixtureDir, workDir, { recursive: true });
  }

  const promptPath = join(workDir, "prompt.md");
  await writeFile(promptPath, opts.promptText);
  const runJsonPath = join(workDir, "run.json");
  const args = headlessArgs({
    workDir,
    promptPath,
    runJsonPath,
    mode: opts.mode,
    model: opts.model,
    ...(opts.maxCost !== undefined ? { maxCost: opts.maxCost } : {}),
    ...(opts.configOverrides ? { configOverrides: opts.configOverrides } : {}),
  });

  await new Promise<void>((resolve, reject) => {
    // On POSIX the wrapper is a script with a shebang and is exec'd directly, so no
    // shell touches the arguments. Windows needs a shell for the .bat shim, and there
    // every argument is quoted by hand.
    const useShell = process.platform === "win32";
    const command = cliCommand(opts.repoRoot);
    const child = spawn(
      useShell ? quoteForShell(command) : command,
      useShell ? args.map(quoteForShell) : args,
      { cwd: opts.repoRoot, shell: useShell, stdio: "inherit" },
    );
    child.on("error", reject);
    child.on("exit", () => resolve());
  });

  const runJson = existsSync(runJsonPath)
    ? JSON.parse(await readFile(runJsonPath, "utf8"))
    : null;
  const deliverablePath =
    opts.deliverable && existsSync(join(workDir, opts.deliverable))
      ? join(workDir, opts.deliverable)
      : null;

  return {
    runJson,
    deliverablePath,
    workDir,
    runJsonPath: existsSync(runJsonPath) ? runJsonPath : null,
  };
}
