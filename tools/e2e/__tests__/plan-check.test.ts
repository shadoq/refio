// Intent tests for the trusted planning check. Each case is a way an agent can hand back a plan that
// looks fine at a glance but would mislead whoever executes it: steps in the wrong order, a file
// that does not exist, a plan that the agent then ignores, or long-task rounds that go backwards.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  changedFiles,
  checkFollowed,
  checkPlan,
  checkRounds,
  evaluate,
  outputFor,
  pathsIn,
  resolvePlan,
  ScenarioError,
  stepsFromTasksCalls,
  stepsFromText,
} from "../lib/plan-check.mjs";

function project(files: Record<string, string>): string {
  const dir = mkdtempSync(join(tmpdir(), "plan-check-"));
  for (const [rel, body] of Object.entries(files)) {
    mkdirSync(join(dir, rel, ".."), { recursive: true });
    writeFileSync(join(dir, rel), body);
  }
  return dir;
}

const fixture = project({
  "app/models.py": "class User: pass\n",
  "app/routes.py": "def list_users(): pass\n",
  "tests/test_routes.py": "def test_x(): pass\n",
});

const migrationPlan = `Here is the plan.

1. Add the \`email\` column to \`app/models.py\`.
   Keep the old constructor working.
2. Write a migration script \`migrations/002_email.py\`.
3. Expose the field in \`app/routes.py\`.
4. Run the tests in \`tests/test_routes.py\`.
`;

test("numbered steps are found, and the lines under a step belong to it", () => {
  const steps = stepsFromText(migrationPlan);
  assert.equal(steps.length, 4);
  assert.match(steps[0].text, /old constructor/);
  assert.doesNotMatch(steps[1].text, /old constructor/);
});

test("when the answer revises its plan, only the last numbered list counts", () => {
  const text = "Plan:\n1. Read a.js\n2. Delete b.js\n\nLet me refine:\n1. Create c.js\n2. Update a.js\n3. Delete b.js\n";
  assert.deepEqual(stepsFromText(text).map((s) => s.title), ["Create c.js", "Update a.js", "Delete b.js"]);
});

test("a checklist is a plan too, and ticked items count as completed", () => {
  const steps = stepsFromText("- [x] read the code\n- [ ] change it\n- [x] test it\n");
  assert.deepEqual(steps.map((s) => s.status), ["completed", "pending", "completed"]);
});

test("a plan with the migration after the endpoint that needs it is rejected", () => {
  const plan = { steps: stepsFromText("1. Expose email in the endpoint\n2. Add the migration\n"), text: "", from: "answer" };
  const reasons = checkPlan({ order: [{ before: "migration", after: "endpoint" }] }, plan, fixture);
  assert.equal(reasons.length, 1);
  assert.match(reasons[0], /comes before/);
});

test("a step that only reads the code does not count for order, and one step doing both is not out of order", () => {
  const plan = {
    steps: stepsFromText("1. Read app/routes.py and app/models.py\n2. Add the field to app/models.py\n3. Expose it in app/routes.py\n"),
    text: "",
    from: "answer",
  };
  assert.deepEqual(checkPlan({ order: [{ before: "models", after: "routes" }] }, plan, fixture), []);
  const both = { steps: stepsFromText("1. Change app/models.py and app/routes.py together\n"), text: "", from: "answer" };
  assert.deepEqual(checkPlan({ order: [{ before: "models", after: "routes" }] }, both, fixture), []);
});

test("a plan that skips a required step is rejected", () => {
  const plan = { steps: stepsFromText("1. Add the column\n2. Expose it\n"), text: "", from: "answer" };
  const reasons = checkPlan({ covers: [{ label: "tests", regex: "test" }] }, plan, fixture);
  assert.deepEqual(reasons, ["plan (answer): no step covers tests"]);
});

test("a plan naming a file that is not in the project is rejected, unless the file is one it will create", () => {
  const plan = { steps: stepsFromText(migrationPlan), text: migrationPlan, from: "answer" };
  assert.match(checkPlan({ paths_exist: true }, plan, fixture)[0], /migrations\/002_email\.py/);
  assert.deepEqual(checkPlan({ paths_exist: true, new_paths: ["^migrations/"] }, plan, fixture), []);
});

test("a bare file name counts as existing when the project has it in some folder", () => {
  assert.deepEqual(pathsIn("open routes.py and Node.js docs"), ["routes.py"]);
  const plan = { steps: stepsFromText("1. Edit routes.py\n"), text: "1. Edit routes.py\n", from: "answer" };
  assert.deepEqual(checkPlan({ paths_exist: true }, plan, fixture), []);
});

test("a new file named by its full path may be referred to later by its bare name, but an unknown bare name still fails", () => {
  const text = "1. Add `locales/pl.json` with Polish texts.\n2. Note that pl.json is not loaded yet.\n";
  const plan = { steps: stepsFromText(text), text, from: "answer" };
  assert.deepEqual(checkPlan({ paths_exist: true, new_paths: ["^locales/"] }, plan, fixture), []);
  const unknown = "1. Add `locales/pl.json`.\n2. Update de.json too.\n";
  const bad = { steps: stepsFromText(unknown), text: unknown, from: "answer" };
  assert.match(checkPlan({ paths_exist: true, new_paths: ["^locales/"] }, bad, fixture)[0], /de\.json/);
});

test("a plan with too few or too many steps is rejected", () => {
  const plan = { steps: stepsFromText(migrationPlan), text: migrationPlan, from: "answer" };
  assert.equal(checkPlan({ min_steps: 5 }, plan, fixture).length, 1);
  assert.equal(checkPlan({ max_steps: 3 }, plan, fixture).length, 1);
  assert.deepEqual(checkPlan({ min_steps: 3, max_steps: 6 }, plan, fixture), []);
});

test("an answer without any steps is no plan at all", () => {
  const plan = { steps: stepsFromText("I would change some files."), text: "", from: "answer" };
  assert.deepEqual(checkPlan({ min_steps: 1 }, plan, fixture), ["plan (answer): no plan steps found"]);
});

const tasksRun = {
  conversation: [
    {
      role: "ASSISTANT",
      toolCallDetails: [
        { name: "tasks", ok: true, arguments: JSON.stringify({ action: "plan", steps: ["Old plan"] }) },
        {
          name: "tasks",
          ok: true,
          arguments: JSON.stringify({ action: "plan", steps: [{ title: "Edit app/models.py" }, "Edit app/routes.py", "Run tests"] }),
        },
        { name: "tasks", ok: true, arguments: JSON.stringify({ action: "update", step_index: 0, status: "completed" }) },
        { name: "tasks", ok: true, arguments: JSON.stringify({ action: "update", step_index: 1, status: "completed" }) },
        { name: "tasks", ok: false, arguments: JSON.stringify({ action: "update", step_index: 2, status: "completed" }) },
      ],
    },
  ],
};

test("the last tasks plan wins, and only successful updates mark steps", () => {
  const steps = stepsFromTasksCalls(tasksRun, undefined)!;
  assert.deepEqual(steps.map((s) => s.title), ["Edit app/models.py", "Edit app/routes.py", "Run tests"]);
  assert.deepEqual(steps.map((s) => s.status), ["completed", "completed", "pending"]);
  const plan = resolvePlan({}, { run: tasksRun, planText: null });
  assert.equal(plan.from, "tasks tool");
  assert.equal(checkPlan({ completed: { all: true } }, plan, fixture).length, 1);
  assert.deepEqual(checkPlan({ completed: { min: 2 } }, plan, fixture), []);
});

test("a subagent's own tasks plan does not replace the main agent's plan", () => {
  const run = {
    conversation: [
      { role: "ASSISTANT", toolCallDetails: [{ name: "tasks", ok: true, arguments: JSON.stringify({ action: "plan", steps: ["Main step A", "Main step B"] }) }] },
      { role: "ASSISTANT", agentName: "code-reviewer", toolCallDetails: [{ name: "tasks", ok: true, arguments: JSON.stringify({ action: "plan", steps: ["Review"] }) }] },
    ],
  };
  assert.deepEqual(resolvePlan({}, { run, planText: null }).steps.map((s) => s.title), ["Main step A", "Main step B"]);
});

test("a scenario that asks for a tasks plan is not satisfied by a plan written in the answer", () => {
  const run = { conversation: [], finalOutput: migrationPlan };
  const plan = resolvePlan({ source: "tasks" }, { run, planText: null });
  assert.deepEqual(checkPlan({ min_steps: 1 }, plan, fixture), ["plan (tasks tool): no plan steps found"]);
});

test("in a multi-agent run only the named agent's plan is judged", () => {
  const run = { finalOutput: "--- planner ---\n1. Edit app/models.py\n2. Test\n\n--- coder ---\n1. Something else\n" };
  assert.match(outputFor(run, "planner"), /app\/models\.py/);
  assert.doesNotMatch(outputFor(run, "planner"), /Something else/);
  assert.equal(resolvePlan({ agent: "planner" }, { run, planText: null }).steps.length, 2);
});

test("an agent that edits a file its plan never names fails plan_followed", () => {
  const work = project({
    "app/models.py": "class User:\n    email = ''\n",
    "app/routes.py": "def list_users(): pass\n",
    "app/admin.py": "# new\n",
    "tests/test_routes.py": "def test_x(): pass\n",
    ".refio/progress.md": "harness file",
    "build.log": "output of the harness build command",
  });
  assert.deepEqual(changedFiles(fixture, work), ["app/admin.py", "app/models.py"]);
  const plan = { steps: stepsFromText("1. Edit app/models.py\n"), text: "1. Edit app/models.py\n", from: "answer" };
  assert.deepEqual(checkFollowed({}, plan, fixture, work), ["plan_followed: changed file(s) the plan never names: app/admin.py"]);
  assert.deepEqual(checkFollowed({ allow_extra: ["^app/admin"] }, plan, fixture, work), []);
});

test("a line-ending change alone is not counted as an edit", () => {
  const work = project({
    "app/models.py": "class User: pass\r\n",
    "app/routes.py": "def list_users(): pass\n",
    "tests/test_routes.py": "def test_x(): pass\n",
  });
  assert.deepEqual(changedFiles(fixture, work), []);
});

test("long-task rounds that make the check worse, run over the cap or leave no progress file are rejected", () => {
  const work = project({ "a.txt": "x" });
  const run = {
    longTask: {
      rounds: [
        { round: 1, errorLines: 4, planStepsDone: 1 },
        { round: 2, errorLines: 6, planStepsDone: 2 },
        { round: 3, errorLines: 0, planStepsDone: 2 },
      ],
    },
  };
  const reasons = checkRounds({ max: 2, errors_non_increasing: true, plan_steps_done_min: 3, progress_file: true }, run, work);
  assert.equal(reasons.length, 4);
  assert.match(reasons[1], /grew from 4 \(round 1\) to 6 \(round 2\)/);
  assert.deepEqual(checkRounds({ max: 3 }, run, work), []);
  assert.deepEqual(checkRounds({ max: 3 }, {}, work), ["rounds: the run has no long-task rounds"]);
});

test("a broken regex in a scenario is reported as a scenario error, not a model failure", () => {
  const scenario = { assert: { plan: { covers: [{ regex: "(" }] } } };
  assert.throws(() => evaluate(scenario, { fixtureDir: fixture, projectDir: fixture, planText: "1. a\n" }), ScenarioError);
});
