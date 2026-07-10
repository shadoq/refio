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
