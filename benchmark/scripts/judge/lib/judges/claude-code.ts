// Claude Code judge adapter: `claude -p ... --output-format json`, read-only
// (Read tool only). Model overridable via JUDGE_CLAUDE_MODEL.
import { execShell, quoteArg } from "../exec";
import type { JudgeAdapter, JudgeVerdict } from "./types";

export const claudeCodeAdapter: JudgeAdapter = {
  id: "claude-code",

  async isAvailable(): Promise<boolean> {
    const r = await execShell("claude --version", { timeoutMs: 15000 });
    return r.code === 0;
  },

  async judge(evidenceDir, promptText, timeoutMs): Promise<JudgeVerdict> {
    const model = process.env.JUDGE_CLAUDE_MODEL;
    const parts = [
      "claude",
      "-p",
      quoteArg(promptText),
      "--output-format",
      "json",
      "--allowedTools",
      quoteArg("Read"),
      "--max-turns",
      "30",
    ];
    if (model) parts.push("--model", quoteArg(model));

    const r = await execShell(parts.join(" "), { cwd: evidenceDir, timeoutMs });
    if (r.timedOut) throw new Error(`claude-code timed out after ${timeoutMs}ms`);
    if (r.code !== 0) {
      throw new Error(`claude-code exited ${r.code}: ${r.stderr.slice(0, 300)}`);
    }

    // `--output-format json` emits a JSON array of event objects (or a single
    // object). The verdict text is the `result` field of the final "result"
    // event; the model name is on the "system" init event. Fall back to raw
    // stdout if the shape is unexpected.
    let rawOutput = r.stdout;
    let judgeModel = model ?? "claude-code";
    try {
      const parsed = JSON.parse(r.stdout) as unknown;
      const events = (Array.isArray(parsed) ? parsed : [parsed]) as Array<{
        type?: string;
        result?: unknown;
        model?: unknown;
      }>;
      const resultEvent = [...events]
        .reverse()
        .find((e) => typeof e?.result === "string");
      if (resultEvent && typeof resultEvent.result === "string") {
        rawOutput = resultEvent.result;
      }
      const modelEvent = events.find((e) => typeof e?.model === "string");
      if (modelEvent && typeof modelEvent.model === "string") {
        judgeModel = modelEvent.model;
      }
    } catch {
      // Not the JSON envelope; keep raw stdout.
    }
    return { rawOutput, judgeModel };
  },
};
