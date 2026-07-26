# benchmark.refio

Static benchmark viewer for evaluating which local and cloud models are a good fit for Refio.

The benchmark is intentionally focused on simple, repeatable coding tasks. It is not trying to prove that small local models can solve complex software projects. Its purpose is to map practical model behavior for Refio's likely target use cases: lightweight coding tasks, first-shot usefulness, visible tool use, reliability, speed, API cost and local viability.

## What It Measures

- Quality scores per task and criterion
- First-shot usability
- Reliability across repeated attempts
- Local viability against cloud baselines
- Runtime and estimated token throughput
- API cost for cloud models
- Per-task behavior and model-to-model comparisons

## What It Is For

- Choosing sensible default models for Refio modes
- Understanding where local models are good enough
- Comparing small, medium and cloud models on the same task set
- Keeping benchmark artifacts, screenshots and notes linkable
- Avoiding model choices based only on intuition

## What It Is Not

- A fully automated benchmark runner
- A general LLM leaderboard
- A claim that local models should handle large, complex agent tasks
- A replacement for manual review of generated artifacts

## Development

```bash
npm install
npm run dev
npm run build
```

Data lives in `data/tasks.json` and `data/results.json`. In development mode, the admin pages can edit those files through the Vite dev server helpers.

## Harness Scripts (`scripts/`)

Shell/Python harness around the headless CLI. All of these run under `bash` (not zsh) and need the CLI dist built first: `sh gradlew :cli:installDist`.

### TUI smoke test

```bash
uv run --with pexpect --with pyte benchmark/scripts/tui-smoke.py
```

Drives the real TUI in a pty (pexpect + pyte screen emulation) through a fixed scene list: startup, F1-F9 navigation, Settings interaction, side panel plus message send, terminal resizes, Ctrl+Q exit. Fully isolated: it passes `JAVA_OPTS=-Duser.home=<tmp>` (env `HOME` does not isolate a JVM) and a throwaway `--project` dir. Scenes covering known open TUI bugs are marked EXPECTED-FAIL and do not fail the script; `--strict` turns them into real failures once the fixes land. No LLM call is made.

### Scenario validation (deterministic, no LLM call)

```bash
bash benchmark/scripts/validate-scenarios.sh --all
```

Quality gate for the e2e scenario pool. For every scenario that has a golden solution under
`test_data/e2e/golden/<id>/` it checks that (a) the fixture is coherent and byte-compiles,
(b) after overlaying the golden solution every file-level HARD assertion passes (needles,
`file_unchanged`, `build_cmd` exit 0, `needle_in_output` vs `golden/<id>/answer.txt`), and
(c) the untouched fixture FAILS at least one HARD assertion, so the scenario cannot pass
without real agent work. Golden dirs mirror the fixture's relative layout with the full
post-solution content of every changed/added file; output-only scenarios (PLAN/CHAT) keep a
sample correct answer in `answer.txt` instead. Run-time-only assertions (`tool_invoked`,
`tool_order`, session status, context overflow) stay with `e2e-run.sh`.

The validated pool includes scenarios modeled on real benchmarks and everyday programmer
work: issue-to-fix (SWE-bench style), spec-with-hidden-tests (Aider Polyglot style),
shell-heavy tasks (Terminal-Bench style), plus add-endpoint, failing-test fix, multi-file
rename, dependency bump with a breaking change, regression test for a reported bug,
edge-case handling, code questions, and the delegate-review-then-fix pattern. All fixtures
build offline (Python stdlib or plain shell/text; no dependency downloads).

### Analysis-scenario validation (deterministic, no LLM call)

```bash
bash benchmark/scripts/validate-analysis-scenarios.sh --all
```

Quality gate for the PLAN-mode analysis scenarios (those tagged `"category":"analysis"`).
These are read-only: the agent inspects a fixture and answers one precise question
(architecture map, dependency/call-graph, root-cause diagnosis, security review,
API-surface summary, schema/index analysis, where-to-add-a-feature, config/deployment,
test-coverage gap, cross-cutting scope trace) over a different project type each
(Python web service, Node project, Kotlin layered service, SQL schema, Docker/compose
config, shell CLI, data pipeline, monorepo, React component tree, mixed-language repo).
They have no golden diff, so `validate-scenarios.sh` does not apply. Instead each scenario
must survive three deterministic checks: (a) the fixture and prompt exist, the prompt opens
with "Do not modify any files.", and every `file_unchanged` path is present in the fixture;
(b) every fixture `*.py` byte-compiles and every `*.js` passes `node --check` (files with no
offline parser are reported as skipped); (c) the `needle_in_output.regex` is a valid ERE and
its expected answer term actually occurs somewhere in the fixture, so the question is
answerable from the provided code. A needle that is a value not literally present opts out
with `"needle_not_in_fixture": true`. No LLM turn is run.

### LLM judge for e2e runs (SOFT tier)

```bash
JUDGE_MODEL=ollama/gpt-oss:20b E2E_OUT_DIR=/tmp/e2e-out \
  bash benchmark/scripts/e2e-run.sh --model ollama/qwen3.5:4b increase-retry-count
```

With `JUDGE_MODEL` set, `e2e-run.sh` runs an external judge after each scenario: the headless CLI in CHAT mode gets the task text, the diff of the fixture project after the run, the build output, and the scenario's optional `judge_criteria` (falls back to `judge.criteria`). The judge answers `{verdict, confidence, reasons}`; unparseable output becomes a FAIL verdict with reason "judge output unparseable". The verdict is appended to the run's `results.jsonl` record as `judge:{...}` and is advisory only - it never fails the run. `JUDGE_MODEL` must differ from the tested `--model`, otherwise judging is skipped with a warning.

### Pass-rate gate

```bash
bash benchmark/scripts/e2e-gate.sh --model ollama/qwen3.5:4b --runs 5 --threshold 4/5 \
    increase-retry-count find-and-fix-null-check
```

Runs each scenario N times (default 5) through `e2e-run.sh`, aggregates `results.jsonl`, prints a scenario x pass-rate table, and exits 0 only when every scenario reaches the threshold (default 0.8; accepts a decimal or a fraction like `4/5`). A single pass = the HARD assertion tier; the judge verdict stays advisory. `--out <dir>` persists the runs, `--all` gates the whole scenario pool, and any other flags are forwarded to `e2e-run.sh`.

### Benchmark statistics (quality + speed)

```bash
bash benchmark/scripts/e2e-stats.sh /tmp/gate-qwen /tmp/gate-o9 /tmp/gate-o35
```

Read-only aggregation (no LLM) of one or more gate result dirs into a Markdown report:
per-model outcomes (runs, pass-rate, avg iterations / output tokens / **tokens per second** /
cost / duration, failure modes), a scenario x model **pass-rate** matrix, a scenario x model
**avg-seconds-per-run** matrix (processing time per case), a tool-use histogram, and API-error
counts. The speed data comes from the per-run `durationMs` / `tokensOut` fields the runner
already writes to each `results.jsonl` record. `--out <file>` also writes the report to disk;
`--self-test` checks the aggregation offline. This is the benchmark data prepared for
`test_data/RESULTS.md`.

## Generating result data (catalog -> queue -> results)

Result rows come from the case catalog, not from hand-edited JSON. A case lives under
`catalog/<category>/<id>/` as a `<id>.case.json` + `<id>.prompt.md` pair and is the single
source for both an e2e scenario and an admin review task, so the prompt never drifts between
them. The two `npm` tools below turn a case into a reviewable result; both need the CLI dist
built first (`sh gradlew :cli:installDist`).

### 1. Emit the scenario and task from a case (no LLM call)

```bash
npm run gen-catalog -- --all          # or: npm run gen-catalog -- <id> ...
```

For each case it writes the e2e scenario (`test_data/e2e/<id>.json` + prompt + fixture stub)
and upserts the admin task in `data/tasks.json`, substituting the deliverable's `{{MODEL_ID}}`
token. Idempotent - an unchanged case produces no diff. `--check` reports drift (for CI),
`--dry-run` writes nothing.

### 2. Run a case on the model(s) and fill the review inbox (spends tokens / GPU)

`import-runs` invokes the headless CLI for a case, copies the produced artifact, renders a
screenshot, computes the deterministic judge (compliance / works_out_of_box / agent_logic)
and appends a schema-valid entry to `inbox[]` in `data/results.json`. It never writes
`results[]` or manual scores. It needs `refio.bat` reachable, or `REFIO_CLI` pointing at the
CLI launcher, and the target provider up (e.g. Ollama).

```bash
# one model, one attempt
npm run import-runs -- demoscene-effect-gouraud-shaded-cube \
  --model ollama/qwen3.5:9b --attempts 1 --max-cost 0.5
```

`import-runs` takes one `--model` per call, so run several selected models in a loop
(PowerShell on Windows):

```powershell
$env:REFIO_CLI = "D:\_work\Saas\refio\refio.bat"
foreach ($m in @("ollama/qwen3.5:9b", "ollama/qwen3.6:27b")) {
  npm run import-runs -- demoscene-effect-gouraud-shaded-cube --model $m --attempts 1 --max-cost 0.5
}
```

Flags: `--all | <id>...`, `--attempts N` (repeat the same model for stability), `--env <id>`
(default `local`), `--max-cost <usd>`, `--no-render` (skip the screenshot). To score an
existing run without calling a model, use `--from-run <run.json> --artifact <html>`;
`--dry-run` builds and validates the entries and prints them without writing.

`import-runs` has no `--config` flag - the headless CLI it spawns reads `~/.refio/config.yaml`,
so target a remote Ollama box (e.g. a DGX Spark) by setting `providers.ollama.ollama_endpoint`
there.

### 3. Promote an inbox entry to a visible result

Open `/admin/queue` in the dev server (`npm run dev`), add the human look/code scores and
promote the entry into `results[]` (or discard it). Only a promoted entry becomes a visible
result. Optional strong-judge scores can be added afterwards (see below).

## Strong-judge scoring (`npm run judge`)

Optional, additional quality scores produced by strong-judge agents (Claude Code,
Codex), independent of the e2e harness and of the manual `scores`. The pipeline
lives in `scripts/judge/` (deterministic orchestration; pure logic in
`src/lib/judge/`, covered by vitest).

```bash
npm run judge -- --dry-run --limit 2                 # build evidence + prompts, no CLI, no write
npm run judge -- --task snake --limit 20             # score a scope with all available judges
npm run judge -- --result-id <id> --judges codex     # one result, one judge
npm run judge -- --stability --task snake            # cross-attempt stability for a group
```

For each result with an HTML artifact the runner renders it headless (Playwright),
hands a read-only evidence folder to each judge CLI (`claude -p ... --allowedTools
Read`, `codex exec --sandbox read-only`), validates the returned JSON (values
snapped to each criterion's `scale.values`), and writes one `judgeScores` entry per
judge into `data/results.json`. A `results.json.bak` holds the pre-write state.

The evidence folder holds `artifact.html`, `console-errors.json`,
`interactions.json` and seven screenshots:

- `shot-1/2/3.png` - the 1280x800 viewport at 1s, 6s and 12s after load. The spread
  is what separates a live animation from a frozen first frame.
- `shot-full.png` - the whole scrollable page, captured after scrolling through it
  so reveal-on-scroll sections are in their revealed state.
- `interact-1/2/3.png` - one interaction each, every one on a freshly loaded page so
  the shots stay independent. The default scenario clicks the first three controls
  that pass Playwright's actionability check (real controls before in-page anchors,
  DOM order, so the choice is reproducible); `snake` and `todo-app` have their own
  scenarios in `scripts/judge/lib/interactions.ts` because clicking alone proves
  nothing for a keyboard game or an empty todo list. `interactions.json` records
  what each step did, including steps that found nothing to click.

- Judges score the same criteria as the human (`coreCriteria` + task `extraCriteria`)
  plus judge-only `judgeCriteria` (code structure, logic correctness). Blind:
  a judge never sees the human scores or another judge's scores.
- The per-criterion aggregate (median across judges) is computed in the viewer,
  never stored. Toggle "Judges" on the Results page for the aggregate column and a
  divergence badge when human and judges differ by >= 0.5 on a shared criterion;
  open a result for the per-judge breakdown.
- Stability (`--stability`) records deterministic metrics (score variance across
  attempts + token-Jaccard code similarity) plus a judge verdict over all attempts,
  keyed by (task, model, environment); it needs >= 2 attempts that already have
  `judgeScores`.
- **Cost:** every non-`--dry-run` run calls paid/agentic CLIs. `--limit` (default 20)
  caps scope; `--re-judge` re-scores. Model overrides: `JUDGE_CLAUDE_MODEL`,
  `JUDGE_CODEX_MODEL`.
