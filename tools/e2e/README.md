# tools/e2e - the e2e harness toolchain

Everything needed to define, generate and run Refio's end-to-end scenarios. Self-contained:
nothing here depends on the benchmark viewer, so `main` alone can define and run the full suite.

```
tools/e2e/
  gen-catalog.ts          generator: case -> e2e scenario + prompt + fixture stub
  lib/                    case loader, idempotent writer, e2e emitter
  src/schema/case.ts      the case schema (single source of truth for a scenario)
  src/schema/criterion.ts scored review criterion (re-used by the benchmark toolchain)
  src/emit-scenario.ts    pure case -> scenario transform
  __tests__/              unit tests (node:test, no bundler)
  e2e-run.sh / .ps1       the runner: one scenario through the headless CLI, then assertions
  gate.sh / e2e-gate.sh   N-runs pass-rate stabilization gates
  e2e-stats.sh            aggregate stats over results.jsonl
  browser-smoke.mjs       headless-Chromium DOM smoke for produced artifacts
  validate-*.sh           golden-solution / analysis-scenario validators
  tui-smoke.py            interactive TUI smoke
```

## Data lives outside this directory

| Path | What |
|---|---|
| `test_data/e2e_catalog/<category>/<name>/` | `*.case.json` + `*.prompt.md` - the authored source |
| `test_data/e2e/<id>.json`, `prompts/<id>.md` | generated artifacts, committed |
| `test_data/e2e/fixtures/<name>/` | starting project state for a scenario |

Hand-written scenarios that predate the catalog also live in `test_data/e2e/` and are left alone
by the generator; it only ever touches files derived from a case.

## Usage

```bash
npm ci                                # once, in tools/e2e
npm run gen-catalog -- --all          # regenerate every case
npm run gen-catalog -- website-web-aurora
npm run gen-catalog:check             # CI gate: fails on drift between case and generated files
npm test                              # unit tests for the schema and the emitter
```

After editing a `*.case.json` or `*.prompt.md`, regenerate and commit both the case and the
generated files. The drift check in `.github/workflows/test.yml` enforces this.

Running scenarios (spends tokens / GPU time, writes into a throwaway project - a human approves
the concrete command first):

```bash
bash tools/e2e/e2e-run.sh --self-test          # assertion engine only: no CLI, no LLM, no writes
bash tools/e2e/e2e-run.sh --list
bash tools/e2e/e2e-run.sh --model ollama/qwen3.5:9b website-web-aurora
```

Full harness reference: [`test_data/e2e-harness.md`](../../test_data/e2e-harness.md).

The `smoke` assertion needs Chromium: `npm run smoke:install` in this directory.

## Relationship to the benchmark

The `benchmark` branch extends this: it reads the same cases through `lib/case.ts` and the same
schemas, and emits an admin review task plus scored runs on top. The dependency only ever points
that way - nothing here imports from `benchmark/`.
