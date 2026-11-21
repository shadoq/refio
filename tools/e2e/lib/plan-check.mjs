// Trusted post-run check of how an agent PLANNED its work, not only what it left behind.
//
// Runs from the harness directory and only READS: the run document, the pristine fixture and the
// project the agent worked in. Plain Node ESM with no dependencies, so e2e-run.sh, e2e-run.ps1 and
// validate-scenarios.sh share one implementation.
//
// Where the plan comes from
//   - The `tasks` tool: an agent records its plan with action "plan" (ordered steps) and marks steps
//     with action "update" (in_progress / completed / failed / skipped). Every call is in run.json
//     (conversation[].toolCallDetails[]), so the plan and each step's last status are rebuilt from it.
//   - The answer text: in PLAN mode a model usually writes the plan as a numbered list. Steps are the
//     numbered items (or `### Step N` headings); without them a checklist (`- [ ]` / `- [x]`), and
//     without that the top-level bullets. A step owns the lines under it until the next step.
//   - In validate-scenarios.sh there is no run: the golden or control plan is a text file instead.
//
// Scenario fields (all optional; a scenario uses the ones it needs):
//
//   assert.plan = {
//     "source": "any" | "tasks" | "output",   // default "any": the tasks plan when one was made, else the answer
//     "agent": "planner",                       // multi-agent run: only this agent's calls / answer section
//     "min_steps": 3, "max_steps": 10,
//     "covers": [{ "label": "migration", "regex": "migrat" }],   // each must match at least one step
//     "order": [{ "before": "migrat", "after": "endpoint|route",  // the first step matching `before`
//                 "before_label": "the migration", "after_label": "the endpoint" }], // labels: optional
//         // must not come after the first matching `after`; steps that only read (title starting with
//         // read / review / inspect / ...) are left out, and one step matching both is fine
//     "mentions": ["models/user\\.py"],          // regexes the plan text as a whole must match
//     "paths_exist": true,                       // every file path the plan names exists in the fixture...
//     "new_paths": ["^tests/"],                  // ...unless it matches one of these (files it may create)
//     "completed": { "min": 3 } | { "all": true }// steps marked completed (tasks updates or `[x]`)
//   }
//   Regexes are JavaScript, case-insensitive.
//
//   assert.plan_followed = { "allow_extra": ["^tests/"] }
//       Every file the agent added, changed or deleted is named in the plan (by path or file name),
//       or matches allow_extra. A plan that says one thing while the agent edits another fails.
//
//   assert.rounds = { "max": 4, "errors_non_increasing": true, "plan_steps_done_min": 3, "progress_file": true }
//       Long tasks only (run.json longTask.rounds[]): no more rounds than `max`; the check's error
//       count never grows from one round to the next; the last round has at least N plan steps
//       completed; .refio/progress.md exists in the project. Needs a run, so the validator skips it.
//
// CLI: node plan-check.mjs <scenario.json> <fixtureDir> <projectDir> [--run <run.json>] [--plan-text <file>]
//   exit 0: every rule holds (or the scenario has none)
//   exit 1: a rule failed; one reason per line on stdout
//   exit 2: the scenario itself is wrong (bad regex, unknown source); reason on stdout
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

export class ScenarioError extends Error {}

const PATH_EXTENSIONS =
  "kt|kts|java|py|js|mjs|cjs|ts|tsx|jsx|go|rs|rb|md|json|ya?ml|toml|sql|gradle|html|css|scss|sh|txt|cfg|ini|xml|properties|csv|env";
const PATH_TOKEN = new RegExp(
  "(?:^|[\\s`'\"(\\[<*])((?:\\.{0,2}/)?(?:[\\w.@-]+/)*[\\w@-][\\w.@-]*\\.(?:" + PATH_EXTENSIONS + "))(?=$|[\\s`'\",):;\\]>*!?]|\\.(?:\\s|$))",
  "gim",
);
/** A step whose title starts with a reading verb only looks at the code. */
const READING_STEP =
  /^(?:#{1,4}\s*)?(?:\*\*)?(?:step\s+\d+\s*[:.)-]?\s*)?(?:\*\*)?\s*(?:read|review|inspect|look|understand|explore|analy[sz]e|examine|study|locate|find|identify|investigate|open|survey|map|search|grep|scan)\b/i;
// Product names that end in a path-like suffix ("Node.js") are not files.
const NOT_A_PATH = /^[A-Z][A-Za-z]*\.js$/;

/** Directories and files that are never the agent's work: tool caches, build output, harness logs. */
const IGNORED = [/^\.refio\//, /^\.git\//, /(^|\/)node_modules\//, /(^|\/)__pycache__\//, /\.pyc$/, /(^|\/)\.pytest_cache\//, /^build\//, /^dist\//, /^\.validate-build\.log$/, /^run\.json$/,
  // Logs the harness itself writes into the project root (build_cmd, browser smoke, servers).
  /^(build|smoke|server|mcp-server)\.log$/];

function rx(source, what) {
  try {
    return new RegExp(source, "i");
  } catch (e) {
    throw new ScenarioError(`${what}: invalid regex /${source}/: ${e.message}`);
  }
}

// ---------------------------------------------------------------------------------------------
// Plan extraction

/** Steps from free text: numbered items, else a checklist, else top-level bullets. */
export function stepsFromText(text) {
  const lines = String(text ?? "").replace(/\r\n?/g, "\n").split("\n");
  const numbered = /^\s{0,3}(?:#{1,4}\s*)?(?:\*\*)?(?:step\s+)?(\d{1,2})[.):](?:\*\*)?\s+(.*\S)/i;
  const headingStep = /^\s{0,3}#{1,4}\s*(?:\*\*)?step\s+(\d{1,2})\b[^\n]*/i;
  const checklist = /^\s{0,3}[-*+]\s+\[([ xX])\]\s+(.*\S)/;
  const bullet = /^\s{0,1}[-*+]\s+(.*\S)/;

  const collect = (match) => {
    const steps = [];
    for (const line of lines) {
      const m = match(line);
      if (m) {
        steps.push({ title: m.title, num: m.num, text: line.trim(), status: m.status ?? "pending" });
      } else if (steps.length > 0 && line.trim() !== "") {
        steps[steps.length - 1].text += "\n" + line.trim();
      } else if (steps.length > 0 && line.trim() === "") {
        steps[steps.length - 1].text += "\n";
      }
    }
    return steps;
  };

  let steps = collect((l) => {
    const h = headingStep.exec(l);
    if (h) return { title: l.replace(/^[\s#*]+/, "").trim(), num: Number(h[1]) };
    const n = numbered.exec(l);
    if (!n) return null;
    const c = /^\[([ xX])\]\s+(.*)/.exec(n[2]);
    const num = Number(n[1]);
    return c ? { title: c[2], num, status: c[1].trim() ? "completed" : "pending" } : { title: n[2], num };
  });
  // An answer that revises its plan ("Let me refine the plan", "Final plan") restarts the numbering
  // at 1; the last list is the plan it settled on, the earlier ones are drafts.
  const restart = steps.findLastIndex((s, i) => i > 0 && s.num === 1 && steps[i - 1].num >= 1);
  if (restart > 0) steps = steps.slice(restart);
  if (steps.length === 0) {
    steps = collect((l) => {
      const c = checklist.exec(l);
      return c ? { title: c[2], status: c[1].trim() ? "completed" : "pending" } : null;
    });
  }
  if (steps.length === 0) {
    steps = collect((l) => {
      const b = bullet.exec(l);
      return b ? { title: b[1] } : null;
    });
  }
  return steps.map((s, i) => ({ index: i, title: s.title, text: s.text.trim(), status: s.status }));
}

function parseArgs(raw) {
  if (raw && typeof raw === "object") return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/** Selects the main agent's messages (those with no agentName) in [stepsFromTasksCalls]. */
export const MAIN_AGENT = Symbol("main agent");

/** The last plan made with the `tasks` tool, with every later status update applied. Null when none. */
export function stepsFromTasksCalls(run, agent) {
  let steps = null;
  for (const msg of run?.conversation ?? []) {
    if (agent === MAIN_AGENT ? Boolean(msg.agentName) : agent && msg.agentName !== agent) continue;
    for (const call of msg.toolCallDetails ?? []) {
      if (call.name !== "tasks" || call.ok === false) continue;
      const args = parseArgs(call.arguments);
      if (!args) continue;
      if (args.action === "plan" && Array.isArray(args.steps)) {
        steps = args.steps.map((s, i) => {
          const title = typeof s === "string" ? s : String(s?.title ?? "");
          const description = typeof s === "object" && s?.description ? String(s.description) : "";
          return { index: i, title, text: description ? `${title}\n${description}` : title, status: "pending" };
        });
      } else if (args.action === "update" && steps) {
        const i = Number(args.step_index);
        if (Number.isInteger(i) && steps[i] && typeof args.status === "string") {
          steps[i].status = args.status.toLowerCase();
        }
      }
    }
  }
  return steps;
}

/** One agent's section of a multi-agent answer ("--- name ---" headers), or the whole answer. */
export function outputFor(run, agent) {
  const out = String(run?.finalOutput ?? "");
  if (!agent) return out;
  const parts = out.split(/^--- (.+?) ---$/m);
  for (let i = 1; i < parts.length; i += 2) {
    if (parts[i].trim() === agent) return parts[i + 1] ?? "";
  }
  return "";
}

/** The plan this check judges, and where it came from. */
export function resolvePlan(spec, { run, planText }) {
  const source = spec?.source ?? "any";
  if (!["any", "tasks", "output"].includes(source)) throw new ScenarioError(`plan.source: unknown value '${source}'`);
  if (planText != null) return { steps: stepsFromText(planText), text: planText, from: "plan text" };
  // Without a named agent the main agent's plan counts (messages with no agentName); a subagent's
  // own `tasks` plan is used only when the main agent made none.
  const fromTasks =
    source === "output"
      ? null
      : spec?.agent
        ? stepsFromTasksCalls(run, spec.agent)
        : (stepsFromTasksCalls(run, MAIN_AGENT) ?? stepsFromTasksCalls(run, undefined));
  if (fromTasks && fromTasks.length > 0) {
    return { steps: fromTasks, text: fromTasks.map((s) => s.text).join("\n"), from: "tasks tool" };
  }
  if (source === "tasks") return { steps: [], text: "", from: "tasks tool" };
  const text = outputFor(run, spec?.agent);
  return { steps: stepsFromText(text), text, from: "answer" };
}

// ---------------------------------------------------------------------------------------------
// Files

function listFiles(root) {
  const out = new Map();
  if (!root || !existsSync(root)) return out;
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const rel = relative(root, full).split(sep).join("/");
      let st;
      try {
        st = statSync(full);
      } catch {
        continue;
      }
      if (st.isDirectory()) {
        if (IGNORED.some((r) => r.test(rel + "/"))) continue;
        walk(full);
      } else if (!IGNORED.some((r) => r.test(rel))) {
        out.set(rel, full);
      }
    }
  };
  walk(root);
  return out;
}

function sameContent(a, b) {
  const norm = (p) => readFileSync(p).toString("latin1").replace(/\r\n?/g, "\n").replace(/\n+$/, "");
  return norm(a) === norm(b);
}

/** Paths added, changed or deleted in the project relative to the fixture. */
export function changedFiles(fixtureDir, projectDir) {
  const before = listFiles(fixtureDir);
  const after = listFiles(projectDir);
  const changed = [];
  for (const [rel, full] of after) {
    const orig = before.get(rel);
    if (!orig || !sameContent(orig, full)) changed.push(rel);
  }
  for (const rel of before.keys()) if (!after.has(rel)) changed.push(rel);
  return changed.sort();
}

export function pathsIn(text) {
  const found = new Set();
  for (const m of String(text).matchAll(PATH_TOKEN)) {
    const p = m[1].replace(/^\.\//, "");
    if (!NOT_A_PATH.test(p) && !/^https?:/i.test(p)) found.add(p);
  }
  return [...found];
}

function mentioned(planText, rel) {
  const text = planText.replace(/\\/g, "/");
  if (text.includes(rel)) return true;
  const base = rel.split("/").pop();
  return new RegExp(`(^|[^\\w.-])${base.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}($|[^\\w-])`, "m").test(text);
}

// ---------------------------------------------------------------------------------------------
// Rules

export function checkPlan(spec, plan, fixtureDir) {
  const reasons = [];
  const { steps } = plan;
  const label = `plan (${plan.from})`;
  if (steps.length === 0) return [`${label}: no plan steps found`];

  if (spec.min_steps != null && steps.length < spec.min_steps) reasons.push(`${label}: ${steps.length} step(s), want at least ${spec.min_steps}`);
  if (spec.max_steps != null && steps.length > spec.max_steps) reasons.push(`${label}: ${steps.length} step(s), want at most ${spec.max_steps}`);

  for (const c of spec.covers ?? []) {
    const r = rx(c.regex, `plan.covers ${c.label ?? c.regex}`);
    if (!steps.some((s) => r.test(s.text))) reasons.push(`${label}: no step covers ${c.label ?? `/${c.regex}/`}`);
  }

  // Order is about the steps that change something: a plan that opens with "Read app/routes.py"
  // has not touched the routes yet, so reading steps are left out of the order rules.
  const acting = steps.filter((s) => !READING_STEP.test(s.title));
  for (const o of spec.order ?? []) {
    const b = rx(o.before, "plan.order.before");
    const a = rx(o.after, "plan.order.after");
    const bs = acting.find((s) => b.test(s.text));
    const as = acting.find((s) => a.test(s.text));
    const beforeName = o.before_label ?? `/${o.before}/`;
    const afterName = o.after_label ?? `/${o.after}/`;
    if (!bs) reasons.push(`${label}: order rule needs a step for ${beforeName}, none has it`);
    else if (!as) reasons.push(`${label}: order rule needs a step for ${afterName}, none has it`);
    // One step doing both says nothing about order, so only a strictly later `before` fails.
    else if (bs.index > as.index) reasons.push(`${label}: step ${as.index + 1} (${afterName}) comes before step ${bs.index + 1} (${beforeName})`);
  }

  for (const m of spec.mentions ?? []) {
    if (!rx(m, "plan.mentions").test(plan.text)) reasons.push(`${label}: does not mention /${m}/`);
  }

  if (spec.paths_exist) {
    const allowed = (spec.new_paths ?? []).map((p) => rx(p, "plan.new_paths"));
    const fixtureFiles = [...listFiles(fixtureDir).keys()];
    const named = pathsIn(plan.text).map((p) => p.replace(/^\/+/, ""));
    const missing = named.filter((clean) => {
      if (allowed.some((r) => r.test(clean))) return false;
      if (fixtureFiles.includes(clean)) return false;
      if (!clean.includes("/") && fixtureFiles.some((f) => f === clean || f.endsWith("/" + clean))) return false;
      if (clean.includes("/") && fixtureFiles.some((f) => f.endsWith("/" + clean))) return false;
      // A bare name used again after the plan gave its full, allowed path ("add locales/pl.json ...
      // pl.json is not loaded yet") is that same new file.
      if (!clean.includes("/") && named.some((n) => n.endsWith("/" + clean) && allowed.some((r) => r.test(n)))) return false;
      return true;
    });
    if (missing.length > 0) reasons.push(`${label}: names file(s) that do not exist: ${missing.join(", ")}`);
  }

  if (spec.completed) {
    const done = steps.filter((s) => s.status === "completed").length;
    if (spec.completed.all && done < steps.length) reasons.push(`${label}: ${done}/${steps.length} steps marked completed, want all`);
    if (spec.completed.min != null && done < spec.completed.min) reasons.push(`${label}: ${done} step(s) marked completed, want at least ${spec.completed.min}`);
  }
  return reasons;
}

export function checkFollowed(spec, plan, fixtureDir, projectDir) {
  if (plan.steps.length === 0) return ["plan_followed: no plan to compare the changes with"];
  const allowed = (spec.allow_extra ?? []).map((p) => rx(p, "plan_followed.allow_extra"));
  const off = changedFiles(fixtureDir, projectDir).filter((rel) => !allowed.some((r) => r.test(rel)) && !mentioned(plan.text, rel));
  return off.length > 0 ? [`plan_followed: changed file(s) the plan never names: ${off.join(", ")}`] : [];
}

export function checkRounds(spec, run, projectDir) {
  const reasons = [];
  const rounds = run?.longTask?.rounds ?? [];
  if (rounds.length === 0) return ["rounds: the run has no long-task rounds"];
  if (spec.max != null && rounds.length > spec.max) reasons.push(`rounds: ${rounds.length} round(s), want at most ${spec.max}`);
  if (spec.errors_non_increasing) {
    let prev = null;
    for (const r of rounds) {
      if (r.errorLines == null) continue;
      if (prev != null && r.errorLines > prev.errorLines) {
        reasons.push(`rounds: check errors grew from ${prev.errorLines} (round ${prev.round}) to ${r.errorLines} (round ${r.round})`);
      }
      prev = r;
    }
  }
  if (spec.plan_steps_done_min != null) {
    const last = rounds[rounds.length - 1];
    if ((last.planStepsDone ?? 0) < spec.plan_steps_done_min) {
      reasons.push(`rounds: ${last.planStepsDone ?? 0} plan step(s) completed by the last round, want at least ${spec.plan_steps_done_min}`);
    }
  }
  if (spec.progress_file && !existsSync(join(projectDir, ".refio", "progress.md"))) {
    reasons.push("rounds: .refio/progress.md was not written");
  }
  return reasons;
}

/** Every planning rule of the scenario; [] when all hold. Throws ScenarioError on a broken scenario. */
export function evaluate(scenario, { fixtureDir, projectDir, run = null, planText = null }) {
  const a = scenario?.assert ?? {};
  const reasons = [];
  let plan = null;
  const needPlan = a.plan || a.plan_followed;
  if (needPlan) plan = resolvePlan(a.plan ?? {}, { run, planText });
  if (a.plan) reasons.push(...checkPlan(a.plan, plan, fixtureDir));
  if (a.plan_followed) reasons.push(...checkFollowed(a.plan_followed, plan, fixtureDir, projectDir));
  if (a.rounds && run) reasons.push(...checkRounds(a.rounds, run, projectDir));
  return reasons;
}

function main(argv) {
  const [scenarioPath, fixtureDir, projectDir, ...rest] = argv;
  if (!scenarioPath || !fixtureDir || !projectDir) {
    console.log("usage: plan-check.mjs <scenario.json> <fixtureDir> <projectDir> [--run <run.json>] [--plan-text <file>]");
    return 2;
  }
  let run = null;
  let planText = null;
  for (let i = 0; i < rest.length; i += 2) {
    if (rest[i] === "--run") run = JSON.parse(readFileSync(rest[i + 1], "utf8"));
    else if (rest[i] === "--plan-text") planText = existsSync(rest[i + 1]) ? readFileSync(rest[i + 1], "utf8") : "";
  }
  try {
    const scenario = JSON.parse(readFileSync(scenarioPath, "utf8"));
    const reasons = evaluate(scenario, { fixtureDir: resolve(fixtureDir), projectDir: resolve(projectDir), run, planText });
    for (const r of reasons) console.log(r);
    return reasons.length === 0 ? 0 : 1;
  } catch (e) {
    console.log(e instanceof ScenarioError ? e.message : `plan check could not run: ${e.message}`);
    return 2;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
