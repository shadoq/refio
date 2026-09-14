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

## Harness Scripts (`tools/e2e/`, on `main`)

Shell/Python harness around the headless CLI. All of these run under `bash` (not zsh) and need the CLI dist built first: `sh gradlew :cli:installDist`.

### TUI smoke test

```bash
uv run --with pexpect --with pyte tools/e2e/tui-smoke.py
```

Drives the real TUI in a pty (pexpect + pyte screen emulation) through a fixed scene list: startup, F1-F9 navigation, Settings interaction, side panel plus message send, terminal resizes, Ctrl+Q exit. Fully isolated: it passes `JAVA_OPTS=-Duser.home=<tmp>` (env `HOME` does not isolate a JVM) and a throwaway `--project` dir. Scenes covering known open TUI bugs are marked EXPECTED-FAIL and do not fail the script; `--strict` turns them into real failures once the fixes land. No LLM call is made.

### Scenario validation (deterministic, no LLM call)

```bash
bash tools/e2e/validate-scenarios.sh --all
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
bash tools/e2e/validate-analysis-scenarios.sh --all
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
  bash tools/e2e/e2e-run.sh --model ollama/qwen3.5:4b increase-retry-count
```

With `JUDGE_MODEL` set, `e2e-run.sh` runs an external judge after each scenario: the headless CLI in CHAT mode gets the task text, the diff of the fixture project after the run, the build output, and the scenario's optional `judge_criteria` (falls back to `judge.criteria`). The judge answers `{verdict, confidence, reasons}`; unparseable output becomes a FAIL verdict with reason "judge output unparseable". The verdict is appended to the run's `results.jsonl` record as `judge:{...}` and is advisory only - it never fails the run. `JUDGE_MODEL` must differ from the tested `--model`, otherwise judging is skipped with a warning.

### Pass-rate gate

```bash
bash tools/e2e/e2e-gate.sh --model ollama/qwen3.5:4b --runs 5 --threshold 4/5 \
    increase-retry-count find-and-fix-null-check
```

Runs each scenario N times (default 5) through `e2e-run.sh`, aggregates `results.jsonl`, prints a scenario x pass-rate table, and exits 0 only when every scenario reaches the threshold (default 0.8; accepts a decimal or a fraction like `4/5`). A single pass = the HARD assertion tier; the judge verdict stays advisory. `--out <dir>` persists the runs, `--all` gates the whole scenario pool, and any other flags are forwarded to `e2e-run.sh`.

### Benchmark statistics (quality + speed)

```bash
bash tools/e2e/e2e-stats.sh /tmp/gate-qwen /tmp/gate-o9 /tmp/gate-o35
```

Read-only aggregation (no LLM) of one or more gate result dirs into a Markdown report:
per-model outcomes (runs, pass-rate, avg iterations / output tokens / **tokens per second** /
cost / duration, failure modes), a scenario x model **pass-rate** matrix, a scenario x model
**avg-seconds-per-run** matrix (processing time per case), a tool-use histogram, and API-error
counts. The speed data comes from the per-run `durationMs` / `tokensOut` fields the runner
already writes to each `results.jsonl` record. `--out <file>` also writes the report to disk;
`--self-test` checks the aggregation offline. This is the benchmark data prepared for
`test_data/RESULTS.md`.

## Relationship to `main`

This branch **extends** `main`; the dependency never points the other way. `main` owns the e2e
toolchain in `tools/e2e/` (the case schema, the case loader, the case -> scenario emitter, the
runner scripts) and the case catalog itself in `test_data/e2e_catalog/`. The benchmark adds the
viewer, the result data, the strong judges and the case -> review-task half of the generator,
importing the shared pieces through the `@e2e/*` alias (`../tools/e2e/src`).

Practical consequences:

- `npm ci` in `../tools/e2e` is required before `gen-tasks` / `import-runs` / `gen-all`.
- `benchmark/src/schema/tasks.ts` re-exports `CriterionSchema` from `@e2e/schema/criterion`;
  there is exactly one definition of it in the repo.
- `zod` is pinned to this app's copy in `vite.config.ts` / `vitest.config.ts` / `tsconfig.json`,
  because `tools/e2e` carries its own `node_modules` with its own zod.
- Keep merging `main` into this branch, never the reverse.

### Merging `main`

`git merge main` is all it takes; `benchmark/` survives untouched.

The one merge that needed care was the first one after `main` dropped the directory: git saw
~3k files removed on one side and untouched on the other, and propagated the removal. That was
resolved once by restoring the tree from the pre-merge tip, and because the resolution is now part
of this branch's history, later merges start from a merge base that already has it. If a future
merge from `main` ever proposes deleting `benchmark/` again, that is the recipe:

```bash
TIP=$(git rev-parse HEAD)            # remember this branch's tip FIRST
git merge main --no-commit
git checkout "$TIP" -- benchmark/    # put the directory back verbatim
git diff --cached "$TIP" -- benchmark   # must print nothing
git commit
```

## Generating result data (catalog -> queue -> results)

Result rows come from the case catalog, not from hand-edited JSON. A case lives under
`../test_data/e2e_catalog/<category>/<name>/` as a `<id>.case.json` + `<id>.prompt.md` pair and is
the single source for both an e2e scenario and an admin review task, so the prompt never drifts
between them. The tools below turn a case into a reviewable result; they need the CLI dist built
first (`sh gradlew :cli:installDist`).

### 1. Emit the scenario and task from a case (no LLM call)

```bash
npm run gen-all -- --all      # both halves: e2e artifacts (tools/e2e) + data/tasks.json
npm run gen-tasks -- <id>     # this branch's half only
npm run gen-all:check         # CI-style drift check over both halves
```

`gen-tasks` upserts the admin task in `data/tasks.json`; the e2e scenario, prompt copy and
fixture stub come from `tools/e2e/gen-catalog.ts`, substituting the deliverable's `{{MODEL_ID}}`
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

### External agents (the Agents page)

The same catalog cases can be driven by an external coding agent instead of the Refio CLI.
Those runs land in the same queue and the same `results.json`, tell themselves apart by
`harnessId`, and stay out of the leaderboard, Results, Compare and Pareto - the `/agents`
page is where they are shown.

```bash
# Claude Code on its own cloud model
npm run import-runs -- <case-id> --model anthropic/claude-opus-5 --harness claude-code \
  --harness-model opus --attempts 1

# the same local model Refio was measured on, through Claude Code
npm run import-runs -- <case-id> --model ollama/qwen3.8:27b --harness claude-code \
  --ollama-host 192.168.5.60 --attempts 1
```

- `--harness` is `refio` (default), `claude-code`, `codex` or `gemini-cli`.
- `--model` is always the id RECORDED in the data; `--harness-model` is what the external CLI
  is told to run. A `--model` starting with `ollama/` points Claude Code (through
  `ANTHROPIC_BASE_URL`) or Codex (through `--oss --local-provider ollama`) at the local
  endpoint from `--ollama-host` (default `$OLLAMA_HOST` or `127.0.0.1`), so the same model can
  be compared under Refio and under that agent. Gemini CLI has no local provider and refuses
  such a model id.
- `--ollama-ctx N` (default 65536) is the context window the local model is LOADED with, for both
  harnesses alike. Refio sends its own window to Ollama explicitly; an agent talking to the same
  endpoint through a compatibility layer sends none and gets the server's default, so without this
  "the same local model under two harnesses" was two different measurements and nothing in the data
  said so. The model is pre-loaded with a warm-up request before the sweep starts, because Ollama
  allocates the key-value cache at load time; a warm-up that fails prints a warning and the window is
  left unrecorded rather than assumed. A warm-up does NOT pin the window: Ollama reloads the model on
  the next request that asks for different options, so a sweep that must compare harnesses needs the
  window baked into the model itself (a Modelfile with `PARAMETER num_ctx`).
- `--native-tools auto|always|never` states which tool channel the Refio harness uses; left out,
  Refio decides. It decides from a registry of model NAMES, and a model built locally to carry its own
  context window is not in that registry, so `auto` reads it as a model with no function calling and
  drops Refio onto a path where the model has to spell out a JSON action envelope in prose. The other
  agents consult no registry, so a sweep that says nothing here measures Refio on a different
  mechanism than its rivals - and says nothing about it either. The choice is recorded on the entry as
  `runContext.nativeTools`; an entry without that field predates this flag and its Refio numbers are
  understated.
- A Refio run is given the same auto-approval expression the e2e harness uses
  (`src/lib/catalog/approval.ts`, asserted identical to the one in `tools/e2e/e2e-run.sh` by a test).
  Headless has no human to approve anything, so without it every ASK-level command tool waits out its
  five-minute timeout and is recorded as "User rejected": shell calls zero on every task,
  self-verification zero on every task, and the numbers read as agent behaviour rather than as a tool
  that was taken away.
- `--dry-run` prints the command it would run and the NAMES of the environment variables it
  would set, and executes no agent.
- How long an agent may work scales with the case tier: easy 15 min / 40 turns, medium 30/60,
  hard 60/120, stress 120/200. Every run spends tokens or GPU time - decide before you start it.

Each run leaves its action log in `data/attachments/<entryId>/_trace/`:

| file | what it is |
|---|---|
| `trace.jsonl` | one JSON event per line: assistant turns, tool calls with the file or command they touched, tool results, the end of the run. Never file contents. |
| `raw.log` | the agent's own stdout, verbatim, capped at 8 MB with a truncation marker. Absent for Refio. |
| `run.json` | the headless CLI's run document. Refio only. |
| `build.log` | the tail of the case's `buildCmd` output, for cases scored by running their tests. |

The queue entry and the promoted result carry a `trace` summary computed from that log:
turns, tool calls split into reads/writes/shell/searches, tool errors, when the first write
happened and whether the model itself ran a build or a test. All of it is plain arithmetic
over the log, never a model's opinion.

Beyond the volume of work, the summary also measures its quality, which is what tells a loop that
made progress apart from one that thrashed:

| field | what it answers |
|---|---|
| `endReason` | how the run ended. A run killed by its cap and one that failed in ten seconds read the same without it. |
| `duplicateCalls`, `repeatedCallStreak` | how much of the work repeated a call the agent had already made. |
| `repeatedFailedCallStreak` | how long it kept retrying a call that kept failing. |
| `recoveredFromError` | whether anything useful happened after the last failing call. Null when nothing failed, which is not the same as not recovering. |
| `readsBeforeFirstWrite`, `searchesBeforeFirstWrite` | how much looking it did before committing to anything. |
| `filesWritten` | distinct files touched, as opposed to the number of writing calls. |
| `nonZeroExits` | shell commands that returned non-zero, kept apart from `toolErrors` because for some agents a grep with no match looks exactly like a failed tool call. |
| `selfVerified` | the model ran a build or test itself, and AFTER it had written something: a build run before any code exists checks the fixture, not the agent's work. |
| `loop` | what the run's own loop reported about itself (context overflow, its failure marker, the verification it ran). Only Refio fills this in today. |

`runContext` on the same row records what it would take to run the attempt again and get a
comparable one: the agent's CLI version, a digest of the resolved prompt, the context window, the
model server, the permission mode, the time and turn limits and the command line.

Six criterion changes came with those metrics, and all of them are deliberate:

- `compliance` is left UNMEASURED for a case that declares no needles, instead of scoring full marks.
  The free point was enough on its own to turn a verdict green for the five highest-volume tasks.
- `agent_logic` is computed from the action log for EVERY harness, instead of being an unconditional
  1.0 for anything that is not Refio. A run that read nothing, wrote nothing and exited zero used to
  score full marks on the one criterion meant to judge the loop.
- `works_out_of_box` fails a page that renders cleanly and still shows nothing. A blank canvas over a
  silent console passed every mechanical check while the deliverable was, to a person looking at it,
  not there.

- The expected tool order is checked against the CLASS of each call (read / write / search /
  shell), not against Refio's tool names. A case still states it as `toolOrder: ["create_new_file"]`
  because that is the vocabulary a case author works in, but scoring it only where those names
  exist made it a part of the score Refio alone could lose: fifteen of the twenty-one cases declare
  one, and on each of them Refio carried a criterion its competitors did not.
- `works_out_of_box` from a build command is only credited to a run that wrote something. A
  refactoring case ships a suite that is green before the agent starts - keeping it green is the
  task - so a passing build said nothing about a run that read three files and stopped, and handed
  it full marks for the fixture's own health.
- `agent_logic` drops to 0.5 when the case declares `assert.selfVerified` and the run never built
  or tested what it wrote. Asked only where the case asked for it: no harness has ever verified
  itself on a page-generation task, because there is nothing there to run, so scoring it everywhere
  would take the same half point off everyone and measure nothing.

A case whose `deliverable` is not an HTML page (the `multi-file` category) is attached as a
plain file and scored by running its `assert.buildCmd` in the work dir: exit 0 AND at least one
write means `works_out_of_box`, anything else records the output tail as the reason.

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
