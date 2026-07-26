// Thin wrapper that runs ONE headless Refio turn into a throwaway work dir and
// returns the produced run.json plus the deliverable path. It never asserts and
// never fails on a non-zero exit - the run status is read from run.json. Executing
// this spends tokens / local GPU time, so import-runs only calls it in --run mode.
import { spawn } from "node:child_process";
import { mkdtemp, cp, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

export interface HeadlessResult {
  runJson: unknown;
  deliverablePath: string | null;
  workDir: string;
}

// Override with REFIO_CLI when the wrapper is not on PATH as `refio.bat`.
const CLI = process.env.REFIO_CLI ?? "refio.bat";

export async function runHeadless(opts: {
  repoRoot: string;
  fixtureDir: string | null;
  promptText: string; // already resolved ({{MODEL_ID}} substituted)
  mode: string;
  model: string;
  deliverable: string | null; // resolved filename the run is expected to produce
  maxCost?: number;
}): Promise<HeadlessResult> {
  const workDir = await mkdtemp(join(tmpdir(), "refio-import-"));
  if (opts.fixtureDir && existsSync(opts.fixtureDir)) {
    await cp(opts.fixtureDir, workDir, { recursive: true });
  }

  const promptPath = join(workDir, "prompt.md");
  await writeFile(promptPath, opts.promptText);
  const runJsonPath = join(workDir, "run.json");
  const args = [
    "-p", workDir,
    "--headless",
    "--mode", opts.mode,
    "--model", opts.model,
    "--prompt-file", promptPath,
    "--output", "json",
    "--output-file", runJsonPath,
  ];
  if (opts.maxCost !== undefined) args.push("--max-cost", String(opts.maxCost));

  await new Promise<void>((resolve, reject) => {
    const child = spawn(CLI, args, { cwd: opts.repoRoot, shell: true, stdio: "inherit" });
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

  return { runJson, deliverablePath, workDir };
}
