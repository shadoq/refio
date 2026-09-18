// Thin wrapper that runs ONE task through an external coding agent (Claude Code,
// Codex or Gemini CLI) in a throwaway work dir and returns the produced run.json, the
// deliverable path and the agent's own event stream - the same contract as
// runHeadless() in ./run-cli.ts, so import-runs can treat both the same way.
// Executing this spends tokens, so only the --run path calls it.
//
// Every agent runs with write access confined to the work dir. Network access is left
// on: the agents track measures these tools as people actually use them, and the
// condition is recorded on the harness record rather than hidden.
import { mkdtemp, cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir, homedir } from "node:os";
import { execShell, quoteArg } from "../../judge/lib/exec";
import {
  parseClaudeCodeRun,
  parseCodexRun,
  parseGeminiRun,
  toRunJson,
} from "../../../src/lib/catalog/agent-run";
import type { HarnessRouting } from "../../../src/lib/catalog/harness-routing";
import type { AgentLimits } from "../../../src/lib/catalog/agent-limits";
import {
  agentWorkspaceSettings,
  agentSearchPath,
  hostSessionOverrides,
} from "../../../src/lib/catalog/agent-isolation";
import type { TimedLine, TraceSource } from "../../../src/lib/trace/types";

export interface AgentRunResult {
  runJson: unknown;
  deliverablePath: string | null;
  workDir: string;
  stderr: string;
  // The agent's own event stream, line by line, with the moment each line arrived.
  timedLines: TimedLine[];
  rawLog: string;
  traceSource: TraceSource;
}

export interface AgentRunOptions {
  harnessId: string;
  // Echo each step to stderr as it happens. A local model can take an hour on one task,
  // and without this the run is a black box until it ends.
  progress?: boolean;
  promptText: string; // already resolved ({{MODEL_ID}} substituted)
  fixtureDir: string | null;
  deliverable: string | null;
  routing: HarnessRouting;
  limits: AgentLimits;
}

export const AGENT_HARNESS_IDS = ["claude-code", "codex", "gemini-cli"];

// The event stream is what makes the run traceable; acceptEdits lets the agent write
// its deliverable without a prompt it cannot answer in -p mode. Anything needing a
// broader approval stalls until the timeout, which is recorded as a failed attempt
// rather than silently retried.
function claudeCommand(promptText: string, routing: HarnessRouting, limits: AgentLimits): string {
  const parts = [
    "claude",
    "-p",
    quoteArg(promptText),
    "--output-format",
    "stream-json",
    // stream-json refuses to run without it.
    "--verbose",
    "--permission-mode",
    "acceptEdits",
    // The host's own MCP servers are not part of the agent as it ships.
    "--strict-mcp-config",
    "--max-turns",
    String(limits.maxTurns),
    ...routing.extraArgs,
  ];
  if (routing.harnessModel) parts.push("--model", quoteArg(routing.harnessModel));
  return parts.join(" ");
}

function codexCommand(
  workDir: string,
  promptText: string,
  outFile: string,
  routing: HarnessRouting,
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
    ...routing.extraArgs,
  ];
  if (routing.harnessModel) parts.push("-m", quoteArg(routing.harnessModel));
  parts.push(quoteArg(promptText));
  return parts.join(" ");
}

// Gemini has no turn cap of its own, so the timeout is the only limit that applies.
function geminiCommand(promptText: string, routing: HarnessRouting): string {
  const parts = [
    "gemini",
    "-p",
    quoteArg(promptText),
    "-o",
    "stream-json",
    "--approval-mode",
    "auto_edit",
    ...routing.extraArgs,
  ];
  if (routing.harnessModel) parts.push("-m", quoteArg(routing.harnessModel));
  return parts.join(" ");
}

const TRACE_SOURCE_BY_HARNESS: Record<string, TraceSource> = {
  "claude-code": "claude-stream-json",
  codex: "codex-jsonl",
  "gemini-cli": "gemini-stream-json",
};

export async function runAgent(opts: AgentRunOptions): Promise<AgentRunResult> {
  const workDir = await mkdtemp(join(tmpdir(), "refio-agent-"));
  if (opts.fixtureDir && existsSync(opts.fixtureDir)) {
    await cp(opts.fixtureDir, workDir, { recursive: true });
  }

  const promptPath = join(workDir, "prompt.md");
  await writeFile(promptPath, opts.promptText);
  const lastMessagePath = join(workDir, "agent-last-message.txt");
  await isolateFromHostPlugins(opts.harnessId, workDir, opts.routing.harnessModel);

  const timedLines: TimedLine[] = [];
  const startedAtLines = Date.now();
  const onStdoutLine = (line: TimedLine): void => {
    timedLines.push(line);
    if (opts.progress) echoProgress(opts.harnessId, line, startedAtLines);
  };
  const command = buildCommand(opts, workDir, lastMessagePath);
  // The command and the names of the overridden variables are printed so a run can be
  // reproduced by hand; the values are not, because they carry tokens.
  console.error(`  ${opts.harnessId}: ${command}`);
  const envNames = Object.keys(opts.routing.env);
  if (envNames.length > 0) console.error(`  env overrides: ${envNames.join(", ")}`);

  const startedAt = Date.now();
  const r = await execShell(command, {
    cwd: workDir,
    timeoutMs: opts.limits.timeoutMs,
    // The host session is cleared first so the run's own routing, applied after it,
    // always wins.
    env: {
      ...hostSessionOverrides(process.env),
      ...opts.routing.env,
      PATH: agentSearchPath(process.env.PATH),
    },
    onStdoutLine,
  });
  // A timeout is a failed run, not a crash: record it like any other failure so the
  // attempt still lands in the queue with its metrics.
  const exitCode = r.timedOut ? 1 : (r.code ?? 1);
  const durationMs = Date.now() - startedAt;

  let runJson: unknown;
  if (opts.harnessId === "claude-code") {
    runJson = toRunJson(parseClaudeCodeRun(r.stdout, exitCode));
  } else if (opts.harnessId === "codex") {
    const lastMessage = existsSync(lastMessagePath) ? await readFile(lastMessagePath, "utf8") : "";
    runJson = toRunJson(parseCodexRun(r.stdout, lastMessage, exitCode, durationMs));
  } else {
    runJson = toRunJson(parseGeminiRun(r.stdout, exitCode, durationMs));
  }

  const deliverablePath =
    opts.deliverable && existsSync(join(workDir, opts.deliverable))
      ? join(workDir, opts.deliverable)
      : null;

  return {
    runJson,
    deliverablePath,
    workDir,
    stderr: r.stderr,
    timedLines,
    rawLog: r.stdout,
    traceSource: TRACE_SOURCE_BY_HARNESS[opts.harnessId],
  };
}

export function buildCommand(
  opts: AgentRunOptions,
  workDir: string,
  lastMessagePath: string,
): string {
  if (opts.harnessId === "claude-code") {
    return claudeCommand(opts.promptText, opts.routing, opts.limits);
  }
  if (opts.harnessId === "codex") {
    return codexCommand(workDir, opts.promptText, lastMessagePath, opts.routing);
  }
  if (opts.harnessId === "gemini-cli") {
    return geminiCommand(opts.promptText, opts.routing);
  }
  throw new Error(
    `unknown external harness: ${opts.harnessId} (known: ${AGENT_HARNESS_IDS.join(", ")})`,
  );
}

// Read the plugin names the host has switched on, so they can be switched off again
// for this run. Only the names are read; nothing is written outside the work dir and
// no credential is touched - the agent still authenticates the way it normally does.
async function hostEnabledPlugins(): Promise<string[]> {
  try {
    const settingsPath = join(homedir(), ".claude", "settings.json");
    if (!existsSync(settingsPath)) return [];
    const parsed: unknown = JSON.parse(await readFile(settingsPath, "utf8"));
    const enabled = (parsed as { enabledPlugins?: Record<string, boolean> }).enabledPlugins ?? {};
    return Object.entries(enabled)
      .filter(([, on]) => on)
      .map(([name]) => name);
  } catch {
    return []; // an unreadable host config is not a reason to abandon the run
  }
}

// Claude Code reads a settings file from the directory it works in, so dropping one
// into the throwaway work dir turns the host's plugins off for this run only.
async function isolateFromHostPlugins(
  harnessId: string,
  workDir: string,
  model: string | undefined,
): Promise<void> {
  if (harnessId !== "claude-code") return;
  const plugins = await hostEnabledPlugins();
  const settingsDir = join(workDir, ".claude");
  await mkdir(settingsDir, { recursive: true });
  await writeFile(
    join(settingsDir, "settings.json"),
    JSON.stringify(agentWorkspaceSettings(plugins, model), null, 2),
  );
  if (plugins.length > 0) {
    console.error(`  disabled ${plugins.length} host plugin(s) for this run`);
  }
}

// One line per step the agent takes, as it takes it. Parsing is best-effort: a line
// that is not an event it knows about is simply not echoed.
function echoProgress(harnessId: string, line: TimedLine, startedAt: number): void {
  let event: Record<string, unknown>;
  try {
    event = JSON.parse(line.line.trim()) as Record<string, unknown>;
  } catch {
    return;
  }
  const at = `${String(Math.round((Date.now() - startedAt) / 1000)).padStart(4)}s`;

  if (harnessId === "claude-code" || harnessId === "gemini-cli") {
    const message = (event.message ?? {}) as Record<string, unknown>;
    const content = Array.isArray(message.content) ? message.content : [];
    for (const raw of content) {
      const block = (raw ?? {}) as Record<string, unknown>;
      if (block.type === "tool_use") console.error(`  ${at} -> ${String(block.name)}`);
      else if (block.type === "text" && typeof block.text === "string") {
        console.error(`  ${at} .. ${block.text.replace(/\s+/g, " ").slice(0, 100)}`);
      }
    }
    if (event.type === "tool_use") console.error(`  ${at} -> ${String(event.tool_name)}`);
    if (event.type === "result" || event.type === "error") console.error(`  ${at} == ${String(event.subtype ?? event.type)}`);
    return;
  }
  if (harnessId === "codex" && event.type === "item.completed") {
    const item = (event.item ?? {}) as Record<string, unknown>;
    console.error(`  ${at} -> ${String(item.type)}`);
  }
}
