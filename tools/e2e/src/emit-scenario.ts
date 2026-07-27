// Pure transform from a catalog case to a headless e2e scenario object. No
// filesystem access, so both the test suite and the tsx generator can use it.
// The mirror-image transform (case -> benchmark review task) lives with the
// benchmark toolchain and imports the case schema from here.
import type { CatalogCase } from "./schema/case";

// The deliverable filename in prompts uses the corpus template `name_{{MODEL_ID}}_01.html`.
// {{MODEL_ID}} is a placeholder resolved per model. The e2e harness cannot substitute
// it (only {{FIXTURE_SERVER}}/{{PORT}}), so generated e2e artifacts resolve it to a
// fixed concrete token; import-runs resolves it to the real model id at run time.
export const E2E_MODEL_TOKEN = "model";

export function resolveModelTemplate(text: string, token: string): string {
  return text.split("{{MODEL_ID}}").join(token);
}

export interface ScenarioNeedle {
  path: string;
  regex?: string;
  text?: string;
}

export interface ScenarioAssert {
  tool_order?: string[];
  needles_in_file?: ScenarioNeedle[];
  needle_in_output?: { regex: string };
  file_unchanged?: string[];
  smoke?: { entry: string; dom_present: string[] };
  build_cmd?: string;
  no_context_overflow: boolean;
}

export interface E2eScenario {
  id: string;
  mode: string;
  description: string;
  category: string;
  max_iterations: number;
  fixture: string;
  prompt_file: string;
  assert: ScenarioAssert;
  judge: { criteria: string[] };
}

// Build the e2e scenario object. Optional assert keys are omitted (not set to
// null/empty) so generated JSON reads like the hand-written scenarios.
export function caseToScenario(c: CatalogCase): E2eScenario {
  const assert: ScenarioAssert = { no_context_overflow: c.assert.noContextOverflow };

  if (c.assert.toolOrder.length > 0) {
    assert.tool_order = [...c.assert.toolOrder];
  }
  if (c.deliverable && c.assert.needles.length > 0) {
    const path = resolveModelTemplate(c.deliverable, E2E_MODEL_TOKEN);
    assert.needles_in_file = c.assert.needles.map((n) =>
      n.regex !== undefined ? { path, regex: n.regex } : { path, text: n.text as string },
    );
  }
  if (c.assert.needleInOutput) {
    assert.needle_in_output = { regex: c.assert.needleInOutput.regex };
  }
  if (c.assert.fileUnchanged.length > 0) {
    assert.file_unchanged = [...c.assert.fileUnchanged];
  }
  if (c.assert.smoke) {
    assert.smoke = {
      entry: resolveModelTemplate(c.assert.smoke.entry, E2E_MODEL_TOKEN),
      dom_present: [...c.assert.smoke.domPresent],
    };
  }
  if (c.assert.buildCmd) {
    assert.build_cmd = c.assert.buildCmd;
  }

  return {
    id: c.id,
    mode: c.mode,
    description: c.review.description,
    category: c.category,
    max_iterations: c.maxIterations,
    fixture: c.fixture,
    prompt_file: `prompts/${c.id}.md`,
    assert,
    judge: { criteria: [...c.judge.criteria] },
  };
}
