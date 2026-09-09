// Thin wrapper that runs ONE task through an external coding agent (Claude Code or
// Codex) in a throwaway work dir and returns the produced run.json plus the deliverable
// path - the same contract as runHeadless() in ./run-cli.ts, so import-runs can treat
// both the same way. Executing this spends tokens, so only the --run path calls it.
//
// Both agents run with write access confined to the work dir. Network access is left
// on: the reference track measures these tools as people actually use them, and the
// condition is recorded on the harness record rather than hidden.
import { mkdtemp, cp, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { execShell, quoteArg } from "../../judge/lib/exec";
import { parseClaudeCodeRun, parseCodexRun, toRunJson } from "../../../src/lib/catalog/agent-run";

export interface AgentRunResult {
  runJson: unknown;
  deliverablePath: string | null;
  workDir: string;
  stderr: string;
}

export interface AgentRunOptions {
  harnessId: string;
  promptText: string; // already resolved ({{MODEL_ID}} substituted)
  fixtureDir: string | null;
  deliverable: string | null;
  model?: string; // omit to let the agent use its own default
  timeoutMs: number;
}

// A task is one artifact, but an agent may take many turns to get there. The cap is
// generous on purpose: a turn limit that bites would measure the limit, not the agent.
const CLAUDE_MAX_TURNS = 60;

export const AGENT_HARNESS_IDS = ["claude-code", "codex"];

// The prompt goes on the command line, the same way the judge adapters pass theirs.
// acceptEdits lets the agent write its deliverable without a prompt it cannot answer
// in -p mode; anything needing a broader approval stalls until the timeout, which is
// recorded as a failed attempt rather than silently retried.
function claudeCommand(promptText: string, model?: string): string {
  const parts = [
    "claude",
    "-p",
    quoteArg(promptText),
    "--output-format",
    "json",
    "--permission-mode",
    "acceptEdits",
    "--max-turns",
    String(CLAUDE_MAX_TURNS),
  ];
  if (model) parts.push("--model", quoteArg(model));
  return parts.join(" ");
}

function codexCommand(
  workDir: string,
  promptText: string,
  outFile: string,
  model?: string,
): string {
  const parts = [
    "codex",
    "exec",
    "--json",
    "--sandbox",
    "workspace-write",
    // The work dir is a throwaway temp dir, not a git repo.
    "--skip-git-repo-check",
    "--cd",
    quoteArg(workDir),
    "--output-last-message",
    quoteArg(outFile),
  ];
  if (model) parts.push("-m", quoteArg(model));
  parts.push(quoteArg(promptText));
  return parts.join(" ");
}

export async function runAgent(opts: AgentRunOptions): Promise<AgentRunResult> {
  const workDir = await mkdtemp(join(tmpdir(), "refio-agent-"));
  if (opts.fixtureDir && existsSync(opts.fixtureDir)) {
    await cp(opts.fixtureDir, workDir, { recursive: true });
  }

  const promptPath = join(workDir, "prompt.md");
  await writeFile(promptPath, opts.promptText);
  const lastMessagePath = join(workDir, "agent-last-message.txt");

  const startedAt = Date.now();
  let runJson: unknown;
  let stderr = "";

  if (opts.harnessId === "claude-code") {
    const r = await execShell(claudeCommand(opts.promptText, opts.model), {
      cwd: workDir,
      timeoutMs: opts.timeoutMs,
    });
    stderr = r.stderr;
    // A timeout is a failed run, not a crash: record it like any other failure so the
    // attempt still lands in the queue with its metrics.
    runJson = toRunJson(parseClaudeCodeRun(r.stdout, r.timedOut ? 1 : (r.code ?? 1)));
  } else if (opts.harnessId === "codex") {
    const r = await execShell(codexCommand(workDir, opts.promptText, lastMessagePath, opts.model), {
      cwd: workDir,
      timeoutMs: opts.timeoutMs,
    });
    stderr = r.stderr;
    const lastMessage = existsSync(lastMessagePath)
      ? await readFile(lastMessagePath, "utf8")
      : "";
    runJson = toRunJson(
      parseCodexRun(r.stdout, lastMessage, r.timedOut ? 1 : (r.code ?? 1), Date.now() - startedAt),
    );
  } else {
    throw new Error(
      `unknown external harness: ${opts.harnessId} (known: ${AGENT_HARNESS_IDS.join(", ")})`,
    );
  }

  const deliverablePath =
    opts.deliverable && existsSync(join(workDir, opts.deliverable))
      ? join(workDir, opts.deliverable)
      : null;

  return { runJson, deliverablePath, workDir, stderr };
}
