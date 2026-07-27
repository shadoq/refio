// Discover and load catalog cases from test_data/e2e_catalog/**. Each case is a
// <id>.case.json (validated) plus a sibling <id>.prompt.md holding the prompt.
import { readFile, readdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { CatalogCaseSchema, type CatalogCase } from "../src/schema/case";

export interface LoadedCase {
  case: CatalogCase;
  promptText: string;
  caseFile: string;
}

async function walkCaseFiles(dir: string): Promise<string[]> {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walkCaseFiles(full)));
    else if (entry.isFile() && entry.name.endsWith(".case.json")) out.push(full);
  }
  return out;
}

export async function loadCaseFile(caseFile: string): Promise<LoadedCase> {
  const raw = JSON.parse(await readFile(caseFile, "utf8"));
  const parsed = CatalogCaseSchema.parse(raw);
  const promptFile = join(dirname(caseFile), `${parsed.id}.prompt.md`);
  if (!existsSync(promptFile)) {
    throw new Error(`case ${parsed.id}: missing prompt file ${promptFile}`);
  }
  return { case: parsed, promptText: await readFile(promptFile, "utf8"), caseFile };
}

// Load all cases (ids omitted) or just the requested ids, sorted by id. Throws on
// an unknown id so a typo fails loud instead of silently generating nothing.
export async function loadCases(catalogDir: string, ids?: string[]): Promise<LoadedCase[]> {
  const files = await walkCaseFiles(catalogDir);
  const loaded: LoadedCase[] = [];
  for (const f of files) loaded.push(await loadCaseFile(f));
  loaded.sort((a, b) => a.case.id.localeCompare(b.case.id));

  if (!ids || ids.length === 0) return loaded;
  const wanted = new Set(ids);
  const picked = loaded.filter((l) => wanted.has(l.case.id));
  const found = new Set(picked.map((l) => l.case.id));
  const missing = ids.filter((id) => !found.has(id));
  if (missing.length > 0) throw new Error(`unknown case id(s): ${missing.join(", ")}`);
  return picked;
}
