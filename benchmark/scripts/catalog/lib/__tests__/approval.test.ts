// @vitest-environment node
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { HEADLESS_AUTO_APPROVE } from "../../../../src/lib/catalog/approval";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "../../../../..");

// Two harnesses drive the same headless Refio. If they approve different commands they
// measure different agents, and the difference is invisible in the data: a rejected
// command looks exactly like a model that chose not to run one.
describe("the headless approval gate", () => {
  it("is the same expression the e2e harness uses", () => {
    const shell = readFileSync(join(repoRoot, "tools/e2e/e2e-run.sh"), "utf8");
    const declared = /^AUTO_APPROVE='(.*)'$/m.exec(shell);
    expect(declared, "tools/e2e/e2e-run.sh no longer declares AUTO_APPROVE").not.toBeNull();
    expect(HEADLESS_AUTO_APPROVE).toBe(declared?.[1]);
  });

  // The commands a run needs to check its own work, and the one it must never be
  // handed. Spot checks, so a careless edit to the expression fails here and not in a
  // sweep three hours long.
  it("approves the commands a run needs to verify itself", () => {
    const re = new RegExp(HEADLESS_AUTO_APPROVE);
    for (const cmd of ["node --test", "npm test", "pytest -q", "./gradlew build", "ls -la", "cat src/a.js"]) {
      expect(re.test(cmd), cmd).toBe(true);
    }
  });

  it("does not approve a deletion or a call off this machine", () => {
    const re = new RegExp(HEADLESS_AUTO_APPROVE);
    expect(re.test("rm -rf src")).toBe(false);
    expect(re.test("curl https://example.com/x")).toBe(false);
  });
});
