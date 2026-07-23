// Codex judge adapter: `codex exec --sandbox read-only`, verdict read from the
// --output-last-message file. Model overridable via JUDGE_CODEX_MODEL.
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { execShell, quoteArg } from "../exec";
import type { JudgeAdapter, JudgeVerdict } from "./types";

export const codexAdapter: JudgeAdapter = {
  id: "codex",

  async isAvailable(): Promise<boolean> {
    const r = await execShell("codex --version", { timeoutMs: 15000 });
    return r.code === 0;
  },

  async judge(evidenceDir, promptText, timeoutMs): Promise<JudgeVerdict> {
    const model = process.env.JUDGE_CODEX_MODEL;
    const outFile = join(evidenceDir, "codex-out.txt");
    const parts = [
      "codex",
      "exec",
      "--sandbox",
      "read-only",
      // The evidence folder is a throwaway temp dir, not a git repo.
      "--skip-git-repo-check",
      "--cd",
      quoteArg(evidenceDir),
      "--output-last-message",
      quoteArg(outFile),
    ];
    if (model) parts.push("-m", quoteArg(model));
    parts.push(quoteArg(promptText));

    const r = await execShell(parts.join(" "), { cwd: evidenceDir, timeoutMs });
    if (r.timedOut) throw new Error(`codex timed out after ${timeoutMs}ms`);
    if (r.code !== 0) {
      throw new Error(`codex exited ${r.code}: ${r.stderr.slice(0, 300)}`);
    }

    const rawOutput = await readFile(outFile, "utf8");
    return { rawOutput, judgeModel: model ?? "codex" };
  },
};
