// Emit the e2e artifacts for one case: the scenario JSON, a copy of the prompt
// under test_data/e2e/prompts/, and a fixture stub for empty single-file cases.
import { existsSync } from "node:fs";
import { join } from "node:path";
import {
  caseToScenario,
  resolveModelTemplate,
  E2E_MODEL_TOKEN,
} from "../../../src/lib/catalog/emit";
import type { CatalogCase } from "../../../src/schema/catalog";
import { writeOrCheck, ensureFinalNewline, type WriteResult } from "./io";

export function scenarioJson(c: CatalogCase): string {
  return JSON.stringify(caseToScenario(c), null, 2) + "\n";
}

export async function emitE2e(opts: {
  e2eDir: string;
  loaded: { case: CatalogCase; promptText: string };
  check?: boolean;
  dryRun?: boolean;
}): Promise<WriteResult[]> {
  const { e2eDir, loaded } = opts;
  const c = loaded.case;
  const out: WriteResult[] = [];

  out.push(await writeOrCheck(join(e2eDir, `${c.id}.json`), scenarioJson(c), opts));
  // The e2e harness cannot substitute {{MODEL_ID}}, so bake it to a fixed concrete
  // token here; the canonical prompt (with {{MODEL_ID}}) lives in the admin task.
  out.push(
    await writeOrCheck(
      join(e2eDir, "prompts", `${c.id}.md`),
      ensureFinalNewline(resolveModelTemplate(loaded.promptText, E2E_MODEL_TOKEN)),
      opts,
    ),
  );

  // Single-file cases (no build, no read-only guard) start from an empty fixture:
  // create a .gitkeep if the dir is absent. Cases with a real fixture (PLAN
  // analysis, build tasks) must ship it; warn loudly on anything missing.
  const fixtureDir = join(e2eDir, c.fixture);
  const isSingleFile = c.assert.fileUnchanged.length === 0 && c.assert.buildCmd === null;
  if (!existsSync(fixtureDir)) {
    if (isSingleFile) {
      out.push(await writeOrCheck(join(fixtureDir, ".gitkeep"), "", opts));
    } else {
      console.warn(`WARN case ${c.id}: fixture ${c.fixture} is missing`);
    }
  }
  for (const rel of c.assert.fileUnchanged) {
    if (!existsSync(join(fixtureDir, rel))) {
      console.warn(`WARN case ${c.id}: file_unchanged path missing in fixture: ${rel}`);
    }
  }
  return out;
}
