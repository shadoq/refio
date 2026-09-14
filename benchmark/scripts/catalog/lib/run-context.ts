// The IO behind "this run is reproducible": load the local model with the window the
// sweep asked for, and read the version of whatever CLI drove it. Both are cheap, both
// are recorded on the entry, and without them two runs that differ in setup look like
// two runs that differ in the agent.
import { execShell } from "../../judge/lib/exec";
import { ollamaBaseUrl, ollamaWarmUpRequest } from "../../../src/lib/catalog/harness-routing";
import { agentSearchPath } from "../../../src/lib/catalog/agent-isolation";

const VERSION_TIMEOUT_MS = 20_000;
const WARMUP_TIMEOUT_MS = 10 * 60 * 1000;

const VERSION_COMMAND: Record<string, string> = {
  "claude-code": "claude --version",
  codex: "codex --version",
  "gemini-cli": "gemini --version",
};

// What the agent calls itself today. A sweep run a month apart against a CLI that has
// since been upgraded is a different measurement, and the version is the only thing
// that says so.
export async function harnessVersion(harnessId: string): Promise<string | undefined> {
  const command = VERSION_COMMAND[harnessId];
  if (!command) return undefined;
  try {
    // Resolved the same way the run itself resolves the agent, or the version recorded
    // would belong to a different program than the one that was measured.
    const r = await execShell(command, {
      timeoutMs: VERSION_TIMEOUT_MS,
      env: { PATH: agentSearchPath(process.env.PATH) },
    });
    const line = `${r.stdout}\n${r.stderr}`.split("\n").find((l) => l.trim() !== "");
    return line?.trim().slice(0, 120);
  } catch {
    return undefined; // an unreadable version is not a reason to abandon the run
  }
}

// Load the model ahead of the sweep so the first real request does not pay for the
// load, and check the server is reachable at all. This does NOT hold the window open:
// Ollama reloads on the next request that asks for different options, so an agent that
// asks for nothing still gets the server default. A sweep whose harnesses must share a
// window needs that window baked into the model. Returns whether the server accepted
// the request, so an unreachable endpoint is reported rather than discovered one failed
// run at a time.
export async function warmUpOllama(
  host: string,
  model: string,
  contextWindow: number,
): Promise<boolean> {
  const { url, body } = ollamaWarmUpRequest(model, contextWindow);
  const target = `${ollamaBaseUrl(host)}${url}`;
  try {
    const response = await fetch(target, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(WARMUP_TIMEOUT_MS),
    });
    return response.ok;
  } catch {
    return false;
  }
}
