// Contract every strong-judge adapter implements. Keep adapters thin: launch the
// CLI read-only in the evidence folder and return its raw verdict text; parsing
// and scale validation happen in the shared scoring/parse helpers.

export interface JudgeVerdict {
  rawOutput: string;
  judgeModel: string;
}

export interface JudgeAdapter {
  id: string;
  // True when the CLI is on PATH and runnable.
  isAvailable(): Promise<boolean>;
  // Runs the judge in read-only mode with cwd = evidenceDir. Throws on timeout
  // or non-zero exit; the caller records that as an error entry.
  judge(evidenceDir: string, promptText: string, timeoutMs: number): Promise<JudgeVerdict>;
}
