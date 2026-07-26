// Small filesystem helper shared by the catalog generator. Supports a check mode
// (report drift without writing, for CI) and a dry-run mode.
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname } from "node:path";

export interface WriteResult {
  path: string;
  changed: boolean;
  existed: boolean;
}

// Write `content` to `path`, or in check/dry-run mode only report whether it
// would change. `changed` is true when on-disk content differs (or is absent).
export async function writeOrCheck(
  path: string,
  content: string,
  opts: { check?: boolean; dryRun?: boolean } = {},
): Promise<WriteResult> {
  const existed = existsSync(path);
  const current = existed ? await readFile(path, "utf8") : null;
  const changed = current !== content;
  if (changed && !opts.check && !opts.dryRun) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, content);
  }
  return { path, changed, existed };
}

// Exactly one trailing newline, so generated prompt files are stable across runs.
export function ensureFinalNewline(s: string): string {
  return s.replace(/\n*$/, "\n");
}
