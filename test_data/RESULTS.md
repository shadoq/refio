# Refio e2e benchmark results

Log of e2e benchmark runs (`test_data/e2e/*.json`) through the headless CLI. Each run appends
a verdict record to `results.jsonl` (`E2E_OUT_DIR`) carrying both quality (verdict, status,
failure_mode) and per-run performance (`durationMs`, `tokensOut`, `iterations`, `apiCalls`).
The tables below are produced by `benchmark/scripts/e2e-stats.sh` and cover speed as well as
pass-rate. Gate: 5 runs per scenario, green at pass-rate >= 0.8. The LLM judge is a separate
SOFT tier and is disabled here (HARD assertions only).

The same scenario on the same (or a similar) model can be run on different machines, so the
throughput numbers (`tok/s`, `avg ms`) are only comparable within one host. Hardware is therefore
recorded **per model** in the `hardware` column of the Per-model table, not as a global property
of the benchmark. The five Ollama models below were served on an **NVIDIA DGX Spark**;
`openai/gpt-5.4-nano` ran against the **cloud OpenAI API**, so its `tok/s` / `avg ms` reflect
network plus a hosted model and are not comparable to the local rows. Local models cost 0; the
avg-cost matrix carries a per-run dollar figure only for the paid cloud rows.

`openai/gpt-5.4-nano` is a **2-pass** sample (not the 5-run stability gate the Ollama models use):
pass 1 and pass 2 are one run each over the suite, HARD assertions only (the LLM judge stays a
disabled SOFT tier). Pass 1 first ran through the Windows `.ps1` harness, which executes each
`build_cmd` via `cmd /c`; several scenarios use bash-only `build_cmd` (a backgrounded server with a
`curl` loop, single-quoted `grep`, ...) that `cmd /c` cannot interpret, so those failed spuriously.
Every pass-1 failure was therefore re-run through the canonical bash `e2e-run.sh` harness (the one
that produced the Ollama rows) and its verdict substituted, so no verdict here is a known harness
artifact. `shell-log-report` has a single run (its `sh-logs` fixture was added mid-batch, after
pass 1), hence `(1/1)` in its nano cell.

Reproduce:

```bash
# gate a model over 5 runs, persisting per-run records
bash benchmark/scripts/e2e-gate.sh --all --model <provider/model> \
  --runs 5 --threshold 0.8 --ollama-host <ollama-host> --max-cost 0 --out <dir>

# aggregate one or more result dirs into the report below (quality + speed)
bash benchmark/scripts/e2e-stats.sh <dir-model-a> <dir-model-b> ...
```

Runs: the four gate models were run 2026-07-09/10, 5 runs each over 63 scenarios; two subagent
scenarios were backfilled for them on 2026-07-17 (see below), bringing each to 65 scenarios /
325 runs (1300 runs total for the four). The `hardware` column is added by hand per batch (the
stats script does not know which host served the model).

`qwen3.5:122b` was added 2026-07 as a fifth model (also on DGX Spark), run over 5 pass(es) per scenario across 65 scenarios (325 runs). 5 runs is the planned stability gate.

Two subagent scenarios (`subagent-locate-and-fix`, `subagent-two-file-fix`) were added after the July gate. They were backfilled for the four July models in a separate 2026-07-17 batch on the same DGX Spark host (5 runs each on the current CLI dist); `qwen3.5:122b` already covered them as part of its 65-scenario gate, so every table now carries all five models on both scenarios. This backfill **is** folded into the four gate models' Per-model rows, the header count, and the tool-usage table (each model is now 65 scenarios / 325 runs). `runs`, `pass`, `pass-rate`, cost, failure-mode counts, and tool totals fold in exactly. Because the original July per-run records were not retained, the four models' derived speed columns (`avg iters`, `avg tokOut`, `tok/s`, `avg ms`) are recombined from the July per-run averages (weighted by 315) plus the exact backfill sums, so they are approximate at the ~1-unit level. The 2026-07-17 backfill ran on a later CLI dist than the July gate, so those four rows now span two dists.

Across all five models the two scenarios split cleanly by difficulty:

- **`subagent-locate-and-fix`** (locate through a read-only subagent, then apply one fix) is within reach of every model. `ornith:35b`, `qwen3.5:9b` and `qwen3.5:122b` each pass 5/5; `ornith:9b` and `qwen3.5:35b` land at 60% (3/5). No model drops below 60%. Wall-clock is 45-77 s per run, with `122b` slowest (76.8 s) and the smaller models around 45-70 s.
- **`subagent-two-file-fix`** (delegate a two-file edit through a subagent) is a capability cliff. Only `122b` clears the 0.8 gate, at 80% (4/5). Every July gate model falls short: `ornith:35b` 40% (2/5), `ornith:9b` 20% (1/5), and both `qwen3.5:35b` and `qwen3.5:9b` 0% (0/5). The two 0/5 models fail *fast* (`qwen3.5:35b` averages 24.1 s, `qwen3.5:9b` 47.8 s) - they mis-delegate or emit a wrong/partial edit and stop, rather than time out. That is a model-capability limit on coordinating a multi-file change through a subagent, not a Refio defect: `122b` succeeds 4/5 on the same harness, and `122b` takes 181.2 s where the failing models bail in under 90 s.

> Caveat on `ornith:35b`: it ran on a CLI dist built before the `run_terminal_command`
> process-group fix. A watchdog reaped orphaned test servers during the run and logged 2
> reaps, so at most 2 of its 325 runs (server-starting scenarios) may reflect that tool hang
> rather than the model. The other three models rarely started servers and are unaffected.

> Caveat on `qwen3.5:35b`: it ran with tightened streaming timeouts
> (`streaming_request_timeout_sec=600`, `streaming_read_timeout_sec=120`) so a stalled
> generation fails fast instead of hanging. Its 3 `crash` results (3 `Exception` / 3
> `LLMError` / 4 `StreamAbortedException` records) are mostly slow generations cut by that
> shorter cap; under the default 1800/240 s timeouts a few of them would likely have completed.

---

# e2e benchmark statistics

1754 run(s) · 6 model(s) · 65 scenario(s) · overall pass-rate 78% (Ollama 5-model gate on DGX Spark + a 129-run single-pass of openai/gpt-5.4-nano at 91%, cloud OpenAI)

## Per-model

| model | hardware | runs | pass | pass-rate | avg iters | avg tokOut | tok/s | total cost | avg ms | failure modes |
|---|---|---|---|---|---|---|---|---|---|---|
| ollama/ornith:35b | DGX Spark | 325 | 275 | 84% | 4 | 1390 | 28.6 | 0 | 48488 | agent-fail:22 loop:16 build-fail:6 wrong-output:3 loop-aborted:3 |
| ollama/ornith:9b | DGX Spark | 325 | 210 | 64% | 4 | 1270 | 25.2 | 0 | 50411 | agent-fail:37 build-fail:24 wrong-output:38 loop:14 loop-aborted:2 |
| ollama/qwen3.5:35b | DGX Spark | 325 | 242 | 74% | 3 | 1461 | 41.4 | 0 | 35280 | agent-fail:28 loop:33 wrong-output:13 crash:3 build-fail:6 |
| ollama/qwen3.5:9b | DGX Spark | 325 | 223 | 68% | 3 | 1624 | 27.6 | 0 | 58753 | agent-fail:24 loop:29 build-fail:23 loop-aborted:1 wrong-output:25 |
| ollama/qwen3.5:122b | DGX Spark | 325 | 311 | 95% | 3 | 1170 | 12.4 | 0 | 93901 | wrong-output:7 agent-fail:4 build-fail:2 loop-aborted:1 |
| openai/gpt-5.4-nano | cloud OpenAI | 129 | 118 | 91% | 9 | 1861 | 50.0 | $0.8509 | 37245 | build-fail:6 loop:2 wrong-output:2 agent-fail:1 |

## Scenario x model (pass-rate)

| scenario \ model | ollama/ornith:35b | ollama/ornith:9b | ollama/qwen3.5:35b | ollama/qwen3.5:9b | ollama/qwen3.5:122b | openai/gpt-5.4-nano |
|---|---|---|---|---|---|---|
| add-build-script | 0% (0/5) | 0% (0/5) | 0% (0/5) | 0% (0/5) | 100% (5/5) | 100% (2/2) |
| add-clamp-helper | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| add-edge-case-test | 100% (5/5) | 60% (3/5) | 40% (2/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| add-endpoint-status | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| add-param-update-callers | 100% (5/5) | 0% (0/5) | 20% (1/5) | 0% (0/5) | 100% (5/5) | 100% (2/2) |
| analyze-architecture | 0% (0/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| build-cli-todo-app | 20% (1/5) | 0% (0/5) | 20% (1/5) | 0% (0/5) | 80% (4/5) | 100% (2/2) |
| build-counter-page | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| build-landing-multifile | 80% (4/5) | 0% (0/5) | 80% (4/5) | 60% (3/5) | 20% (1/5) | 100% (2/2) |
| build-rest-api | 80% (4/5) | 0% (0/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| build-spa-2-routes | 100% (5/5) | 80% (4/5) | 60% (3/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| code-question-backoff | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| create-file-in-right-dir | 100% (5/5) | 60% (3/5) | 60% (3/5) | 20% (1/5) | 100% (5/5) | 100% (2/2) |
| ctf-exposed-endpoint | 80% (4/5) | 0% (0/5) | 40% (2/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| ctf-path-traversal | 80% (4/5) | 0% (0/5) | 60% (3/5) | 40% (2/5) | 100% (5/5) | 50% (1/2) |
| delegate-review-fix-discount | 60% (3/5) | 20% (1/5) | 80% (4/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| delegate-review-then-fix | 60% (3/5) | 40% (2/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 50% (1/2) |
| dep-bump-fix-callers | 100% (5/5) | 100% (5/5) | 60% (3/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| dependency-ordering | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| edge-case-empty-stats | 100% (5/5) | 100% (5/5) | 60% (3/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| extract-validation | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| fetch-local-json | 100% (5/5) | 60% (3/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| find-and-fix-null-check | 100% (5/5) | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| find-by-symbol | 100% (5/5) | 100% (5/5) | 80% (4/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| fix-area-formula | 100% (5/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| fix-failing-slugify | 100% (5/5) | 100% (5/5) | 80% (4/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| fix-two-failures | 100% (5/5) | 60% (3/5) | 20% (1/5) | 60% (3/5) | 60% (3/5) | 100% (2/2) |
| haystack-https | 100% (5/5) | 80% (4/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| implement-roman-numerals | 100% (5/5) | 100% (5/5) | 60% (3/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| implement-stubs | 100% (5/5) | 100% (5/5) | 80% (4/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| increase-retry-count | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| infra-compose-config | 100% (5/5) | 80% (4/5) | 80% (4/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| js-fix-off-by-one | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| kotlin-scope-trace | 80% (4/5) | 40% (2/5) | 80% (4/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| large-file-edit | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| locate-root-cause | 100% (5/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| make-test-pass | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 0% (0/2) |
| mixed-security-review | 20% (1/5) | 20% (1/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) | 50% (1/2) |
| monorepo-deps | 60% (3/5) | 80% (4/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| needle-in-haystack-deep | 60% (3/5) | 80% (4/5) | 20% (1/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| no-bug-here | 100% (5/5) | 100% (5/5) | 80% (4/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| node-callgraph | 40% (2/5) | 100% (5/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| pipeline-2-agents | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 0% (0/2) |
| pipeline-root-cause | 100% (5/5) | 100% (5/5) | 80% (4/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| pixel-plumber | 80% (4/5) | 40% (2/5) | 60% (3/5) | 0% (0/5) | 60% (3/5) | 100% (2/2) |
| pixel-plumber-add-pause | 100% (5/5) | 0% (0/5) | 80% (4/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| pixel-plumber-levels | 60% (3/5) | 20% (1/5) | 60% (3/5) | 60% (3/5) | 100% (5/5) | 100% (2/2) |
| plan-validation | 100% (5/5) | 60% (3/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| py-sum-offbyone | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| py-test-gap | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| py-webservice-map | 20% (1/5) | 40% (2/5) | 80% (4/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| react-feature-locate | 80% (4/5) | 60% (3/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) | 100% (2/2) |
| regression-test-duration | 100% (5/5) | 100% (5/5) | 80% (4/5) | 20% (1/5) | 100% (5/5) | 100% (2/2) |
| rename-across-files | 100% (5/5) | 20% (1/5) | 60% (3/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| rename-function | 100% (5/5) | 0% (0/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| shell-cli-surface | 80% (4/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| shell-log-report | 80% (4/5) | 80% (4/5) | 20% (1/5) | 60% (3/5) | 40% (2/5) | 100% (1/1) |
| snake-game | 100% (5/5) | 40% (2/5) | 60% (3/5) | 0% (0/5) | 100% (5/5) | 100% (2/2) |
| sql-schema-index | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| stellar-sound-page | 100% (5/5) | 60% (3/5) | 100% (5/5) | 40% (2/5) | 100% (5/5) | 100% (2/2) |
| subagent-depth-guard | 60% (3/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| subagent-locate-and-fix | 100% (5/5) | 60% (3/5) | 60% (3/5) | 100% (5/5) | 100% (5/5) | 100% (2/2) |
| subagent-two-file-fix | 40% (2/5) | 20% (1/5) | 0% (0/5) | 0% (0/5) | 80% (4/5) | 0% (0/2) |
| swe-fix-cache-eviction | 100% (5/5) | 20% (1/5) | 80% (4/5) | 80% (4/5) | 80% (4/5) | 100% (2/2) |
| wire-format-helper | 80% (4/5) | 40% (2/5) | 80% (4/5) | 0% (0/5) | 100% (5/5) | 0% (0/2) |

## Scenario x model (avg seconds per run)

| scenario \ model | ollama/ornith:35b | ollama/ornith:9b | ollama/qwen3.5:35b | ollama/qwen3.5:9b | ollama/qwen3.5:122b | openai/gpt-5.4-nano |
|---|---|---|---|---|---|---|
| add-build-script | 20.7 | 24.8 | 18.7 | 21.1 | 60.6 | 22.2 |
| add-clamp-helper | 15.2 | 32.6 | 20.7 | 20.8 | 64.3 | 20.2 |
| add-edge-case-test | 14.7 | 22.4 | 16.5 | 16.3 | 40 | 22.0 |
| add-endpoint-status | 18.6 | 18.1 | 17.3 | 21.9 | 53.1 | 23.8 |
| add-param-update-callers | 35.3 | 43.5 | 25.1 | 35.9 | 198.6 | 32.3 |
| analyze-architecture | 15.1 | 39.9 | 29.1 | 30.5 | 61.9 | 13.3 |
| build-cli-todo-app | 230.8 | 53.2 | 54.8 | 79.6 | 633.7 | 45.5 |
| build-counter-page | 15.7 | 28.1 | 14.6 | 20.1 | 51.1 | 11.9 |
| build-landing-multifile | 70.5 | 46.9 | 44.3 | 52.3 | 188.5 | 51.5 |
| build-rest-api | 767 | 50.7 | 26.3 | 44.3 | 112.1 | 31.4 |
| build-spa-2-routes | 21 | 50.4 | 17.8 | 31.1 | 71.3 | 18.4 |
| code-question-backoff | 13.9 | 17.2 | 13.1 | 13.6 | 29.8 | 6.6 |
| create-file-in-right-dir | 18.9 | 33.7 | 26.4 | 28 | 70.3 | 18.4 |
| ctf-exposed-endpoint | 46.6 | 88.8 | 15.8 | 29.7 | 127.1 | 21.3 |
| ctf-path-traversal | 38.7 | 73.3 | 14.9 | 52 | 44.5 | 16.1 |
| delegate-review-fix-discount | 41.9 | 36 | 34.4 | 39.8 | 89 | 26.9 |
| delegate-review-then-fix | 25.3 | 33.1 | 49.9 | 30.1 | 78.3 | 31.0 |
| dep-bump-fix-callers | 15.6 | 23.8 | 18.9 | 22.9 | 48.3 | 18.5 |
| dependency-ordering | 29.4 | 44.1 | 28.1 | 34.1 | 83.5 | 54.5 |
| edge-case-empty-stats | 17.8 | 25.1 | 21 | 23 | 50.2 | 19.9 |
| extract-validation | 19.5 | 28.2 | 17.2 | 19.7 | 46.9 | 22.1 |
| fetch-local-json | 8.9 | 17 | 9.4 | 13.5 | 28.3 | 18.0 |
| find-and-fix-null-check | 18.5 | 19.2 | 13.1 | 15.6 | 30.2 | 24.0 |
| find-by-symbol | 16.5 | 31.7 | 17.8 | 32.4 | 40.4 | 32.0 |
| fix-area-formula | 12.7 | 16.5 | 19 | 17.5 | 32.2 | 26.0 |
| fix-failing-slugify | 18.3 | 20.2 | 21.2 | 27.6 | 45 | 27.8 |
| fix-two-failures | 21.6 | 29.2 | 19.8 | 28.1 | 67.3 | 35.4 |
| haystack-https | 12.1 | 20.8 | 13.8 | 19 | 30.1 | 27.0 |
| implement-roman-numerals | 26.1 | 49.1 | 24.5 | 34.2 | 62.9 | 22.5 |
| implement-stubs | 24 | 41.3 | 21.2 | 49.5 | 89.8 | 39.5 |
| increase-retry-count | 11.3 | 22.1 | 20.9 | 21.9 | 38.3 | 20.9 |
| infra-compose-config | 22.7 | 25.7 | 20.1 | 23 | 39.7 | 9.4 |
| js-fix-off-by-one | 11.6 | 17.3 | 14.5 | 17.6 | 30.9 | 25.5 |
| kotlin-scope-trace | 26.7 | 36.1 | 23.8 | 27.8 | 53.4 | 13.7 |
| large-file-edit | 15.6 | 25.6 | 20.3 | 20.3 | 58.3 | 18.7 |
| locate-root-cause | 18.8 | 28.7 | 17.9 | 24.6 | 31.8 | 10.0 |
| make-test-pass | 17.2 | 30 | 20.9 | 29.8 | 32.8 | 35.3 |
| mixed-security-review | 24.8 | 32.9 | 22.1 | 33.7 | 55.6 | 546.7 |
| monorepo-deps | 29.9 | 40.3 | 25.5 | 37.8 | 76.7 | 13.0 |
| needle-in-haystack-deep | 15.9 | 30.8 | 23.1 | 39.8 | 77.1 | 24.9 |
| no-bug-here | 59.6 | 53.4 | 20.4 | 52.6 | 30.5 | 19.6 |
| node-callgraph | 23.9 | 37.1 | 21.2 | 33.5 | 69.3 | 13.8 |
| pipeline-2-agents | 26.2 | 32.6 | 20.7 | 27.4 | 49.9 | 43.4 |
| pipeline-root-cause | 22.2 | 29.7 | 17.5 | 22.8 | 47.9 | 7.4 |
| pixel-plumber | 286.2 | 498.7 | 285.9 | 341.1 | 805 | 204.7 |
| pixel-plumber-add-pause | 37.5 | 20.4 | 91 | 54.5 | 184.1 | 34.9 |
| pixel-plumber-levels | 37.2 | 92.3 | 58 | 260.5 | 130 | 33.9 |
| plan-validation | 38.1 | 35.5 | 21.9 | 28.1 | 32.7 | 7.8 |
| py-sum-offbyone | 12.5 | 19.7 | 13.2 | 18.6 | 37.9 | 19.2 |
| py-test-gap | 13.5 | 13.9 | 10.6 | 14.5 | 25.9 | 7.5 |
| py-webservice-map | 29.6 | 52.4 | 41.2 | 33.3 | 80.5 | 11.9 |
| react-feature-locate | 36.7 | 30.6 | 23.7 | 29.5 | 89.3 | 13.4 |
| regression-test-duration | 28.5 | 55.6 | 25.8 | 50.9 | 71.1 | 25.4 |
| rename-across-files | 33.2 | 28.4 | 21.3 | 69.5 | 57.2 | 25.9 |
| rename-function | 11.8 | 24.5 | 26.8 | 17 | 24.9 | 24.7 |
| shell-cli-surface | 24.2 | 30.6 | 18.3 | 23.1 | 41.2 | 12.7 |
| shell-log-report | 14.9 | 22.7 | 20 | 12.6 | 62.4 | 7.5 |
| snake-game | 77.8 | 178.2 | 297.2 | 338.9 | 210.6 | 75.5 |
| sql-schema-index | 14 | 20 | 12.9 | 14.5 | 29.3 | 7.8 |
| stellar-sound-page | 239 | 418.3 | 232.9 | 1011.1 | 463.1 | 163.2 |
| subagent-depth-guard | 25.4 | 10.4 | 6.5 | 9.4 | 19.8 | 9.3 |
| subagent-locate-and-fix | 69.5 | 49.8 | 45.3 | 51.1 | 76.8 | 27.9 |
| subagent-two-file-fix | 86.3 | 66.9 | 24.1 | 47.8 | 181.2 | 41.2 |
| swe-fix-cache-eviction | 25.6 | 68.5 | 19.3 | 46.5 | 101.6 | 30.9 |
| wire-format-helper | 29.6 | 34.7 | 20.5 | 26.3 | 54.3 | 38.4 |

## Scenario x model (avg cost per run)

| scenario \ model | ollama/ornith:35b | ollama/ornith:9b | ollama/qwen3.5:35b | ollama/qwen3.5:9b | ollama/qwen3.5:122b | openai/gpt-5.4-nano |
|---|---|---|---|---|---|---|
| add-build-script | 0 | 0 | 0 | 0 | 0 | $0.0048 |
| add-clamp-helper | 0 | 0 | 0 | 0 | 0 | $0.0052 |
| add-edge-case-test | 0 | 0 | 0 | 0 | 0 | $0.0064 |
| add-endpoint-status | 0 | 0 | 0 | 0 | 0 | $0.0073 |
| add-param-update-callers | 0 | 0 | 0 | 0 | 0 | $0.0083 |
| analyze-architecture | 0 | 0 | 0 | 0 | 0 | $0.0018 |
| build-cli-todo-app | 0 | 0 | 0 | 0 | 0 | $0.0143 |
| build-counter-page | 0 | 0 | 0 | 0 | 0 | $0.0024 |
| build-landing-multifile | 0 | 0 | 0 | 0 | 0 | $0.0116 |
| build-rest-api | 0 | 0 | 0 | 0 | 0 | $0.0085 |
| build-spa-2-routes | 0 | 0 | 0 | 0 | 0 | $0.0041 |
| code-question-backoff | 0 | 0 | 0 | 0 | 0 | $0.0010 |
| create-file-in-right-dir | 0 | 0 | 0 | 0 | 0 | $0.0057 |
| ctf-exposed-endpoint | 0 | 0 | 0 | 0 | 0 | $0.0072 |
| ctf-path-traversal | 0 | 0 | 0 | 0 | 0 | $0.0049 |
| delegate-review-fix-discount | 0 | 0 | 0 | 0 | 0 | $0.0064 |
| delegate-review-then-fix | 0 | 0 | 0 | 0 | 0 | $0.0083 |
| dep-bump-fix-callers | 0 | 0 | 0 | 0 | 0 | $0.0051 |
| dependency-ordering | 0 | 0 | 0 | 0 | 0 | $0.0145 |
| edge-case-empty-stats | 0 | 0 | 0 | 0 | 0 | $0.0059 |
| extract-validation | 0 | 0 | 0 | 0 | 0 | $0.0076 |
| fetch-local-json | 0 | 0 | 0 | 0 | 0 | $0.0058 |
| find-and-fix-null-check | 0 | 0 | 0 | 0 | 0 | $0.0060 |
| find-by-symbol | 0 | 0 | 0 | 0 | 0 | $0.0100 |
| fix-area-formula | 0 | 0 | 0 | 0 | 0 | $0.0087 |
| fix-failing-slugify | 0 | 0 | 0 | 0 | 0 | $0.0083 |
| fix-two-failures | 0 | 0 | 0 | 0 | 0 | $0.0106 |
| haystack-https | 0 | 0 | 0 | 0 | 0 | $0.0090 |
| implement-roman-numerals | 0 | 0 | 0 | 0 | 0 | $0.0058 |
| implement-stubs | 0 | 0 | 0 | 0 | 0 | $0.0134 |
| increase-retry-count | 0 | 0 | 0 | 0 | 0 | $0.0061 |
| infra-compose-config | 0 | 0 | 0 | 0 | 0 | $0.0011 |
| js-fix-off-by-one | 0 | 0 | 0 | 0 | 0 | $0.0072 |
| kotlin-scope-trace | 0 | 0 | 0 | 0 | 0 | $0.0020 |
| large-file-edit | 0 | 0 | 0 | 0 | 0 | $0.0070 |
| locate-root-cause | 0 | 0 | 0 | 0 | 0 | $0.0010 |
| make-test-pass | 0 | 0 | 0 | 0 | 0 | $0.0079 |
| mixed-security-review | 0 | 0 | 0 | 0 | 0 | $0.0010 |
| monorepo-deps | 0 | 0 | 0 | 0 | 0 | $0.0015 |
| needle-in-haystack-deep | 0 | 0 | 0 | 0 | 0 | $0.0079 |
| no-bug-here | 0 | 0 | 0 | 0 | 0 | $0.0055 |
| node-callgraph | 0 | 0 | 0 | 0 | 0 | $0.0021 |
| pipeline-2-agents | 0 | 0 | 0 | 0 | 0 | $0.0095 |
| pipeline-root-cause | 0 | 0 | 0 | 0 | 0 | $0.0009 |
| pixel-plumber | 0 | 0 | 0 | 0 | 0 | $0.0258 |
| pixel-plumber-add-pause | 0 | 0 | 0 | 0 | 0 | $0.0101 |
| pixel-plumber-levels | 0 | 0 | 0 | 0 | 0 | $0.0071 |
| plan-validation | 0 | 0 | 0 | 0 | 0 | $0.0010 |
| py-sum-offbyone | 0 | 0 | 0 | 0 | 0 | $0.0062 |
| py-test-gap | 0 | 0 | 0 | 0 | 0 | $0.0011 |
| py-webservice-map | 0 | 0 | 0 | 0 | 0 | $0.0019 |
| react-feature-locate | 0 | 0 | 0 | 0 | 0 | $0.0020 |
| regression-test-duration | 0 | 0 | 0 | 0 | 0 | $0.0076 |
| rename-across-files | 0 | 0 | 0 | 0 | 0 | $0.0083 |
| rename-function | 0 | 0 | 0 | 0 | 0 | $0.0076 |
| shell-cli-surface | 0 | 0 | 0 | 0 | 0 | $0.0016 |
| shell-log-report | 0 | 0 | 0 | 0 | 0 | $0.0016 |
| snake-game | 0 | 0 | 0 | 0 | 0 | $0.0122 |
| sql-schema-index | 0 | 0 | 0 | 0 | 0 | $0.0011 |
| stellar-sound-page | 0 | 0 | 0 | 0 | 0 | $0.0158 |
| subagent-depth-guard | 0 | 0 | 0 | 0 | 0 | $0.0022 |
| subagent-locate-and-fix | 0 | 0 | 0 | 0 | 0 | $0.0058 |
| subagent-two-file-fix | 0 | 0 | 0 | 0 | 0 | $0.0088 |
| swe-fix-cache-eviction | 0 | 0 | 0 | 0 | 0 | $0.0092 |
| wire-format-helper | 0 | 0 | 0 | 0 | 0 | $0.0097 |

## Tool usage (all runs)

<!-- TOOLUSE-BASELINE {"read_file":[2918,1388],"code_editing":[778,679],"run_terminal_command":[572,396],"read_directory":[423,253],"create_new_file":[360,273],"grep_search":[172,149],"think":[169,100],"http_request":[156,65],"invoke_subagent":[126,103],"file_search":[111,107],"advance_code_editing":[90,84],"tasks":[90,38],"memory":[62,36],"multi_edit":[61,47],"run_code":[52,30],"fetch_webpage":[39,10],"rename_symbol":[27,27],"code_intelligence":[17,16],"rag_search":[15,14],"find_usages":[6,6],"multi_line_editor":[6,5],"run_process_background":[6,6],"monitor_process":[5,5],"llm_call":[4,4],"edit_file":[3,3],"manage_subagent":[3,2],"newTask":[3,1],"write_file":[3,2],"answer_message":[1,1],"delegate_to_strong_model":[1,1],"directory_read":[1,1],"list_files_recursive":[1,1],"mark_step_complete":[1,1],"send_task_to_agent":[1,1],"sleep":[1,1],"task":[1,1],"task_plans":[1,1],"task_use":[1,1],"todo":[1,1],"update_memory":[1,1]} -->
| tool | total calls | runs used | avg/run |
|---|---|---|---|
| read_file | 3159 | 1499 | 1.8 |
| code_editing | 855 | 736 | 0.49 |
| tasks | 641 | 127 | 0.37 |
| run_terminal_command | 583 | 407 | 0.33 |
| read_directory | 475 | 298 | 0.27 |
| create_new_file | 389 | 297 | 0.22 |
| grep_search | 219 | 170 | 0.12 |
| http_request | 172 | 70 | 0.1 |
| think | 169 | 100 | 0.1 |
| file_search | 161 | 136 | 0.09 |
| invoke_subagent | 136 | 111 | 0.08 |
| run_code | 113 | 64 | 0.06 |
| advance_code_editing | 105 | 95 | 0.06 |
| memory | 68 | 42 | 0.04 |
| multi_edit | 62 | 48 | 0.04 |
| fetch_webpage | 39 | 10 | 0.02 |
| code_intelligence | 31 | 27 | 0.02 |
| rename_symbol | 27 | 27 | 0.02 |
| rag_search | 15 | 14 | 0.01 |
| find_usages | 7 | 7 | 0 |
| multi_line_editor | 7 | 6 | 0 |
| run_process_background | 6 | 6 | 0 |
| monitor_process | 5 | 5 | 0 |
| llm_call | 4 | 4 | 0 |
| edit_file | 3 | 3 | 0 |
| manage_subagent | 3 | 2 | 0 |
| newTask | 3 | 1 | 0 |
| write_file | 3 | 2 | 0 |
| delegate_to_strong_model | 2 | 2 | 0 |
| answer_message | 1 | 1 | 0 |
| directory_read | 1 | 1 | 0 |
| list_files_recursive | 1 | 1 | 0 |
| mark_step_complete | 1 | 1 | 0 |
| send_task_to_agent | 1 | 1 | 0 |
| sleep | 1 | 1 | 0 |
| task | 1 | 1 | 0 |
| task_plans | 1 | 1 | 0 |
| task_use | 1 | 1 | 0 |
| todo | 1 | 1 | 0 |
| update_memory | 1 | 1 | 0 |

## API errors

<!-- APIERR-BASELINE {"Exception":9,"LLMError":9,"StreamAbortedException":8,"SocketTimeoutException":1} -->
| error type | count |
|---|---|
| Exception | 9 |
| LLMError | 9 |
| StreamAbortedException | 8 |
| SocketTimeoutException | 1 |
| EOFException | 1 |

---

## Interpretation

- **Ranking (single gate, HARD assertions):** `ornith:35b` 84% > `qwen3.5:35b` 74% >
  `qwen3.5:9b` 68% > `ornith:9b` 64%. Model size dominates: both 35B models clear the 9B pair,
  and they fail *softer* - their `build-fail` / `wrong-output` counts stay low (6/3 for
  `ornith:35b`, 6/13 for `qwen3.5:35b`) versus the 9B models (build-fail ~23-24, wrong-output
  25-38), i.e. when a 35B writes code it compiles and hits the target far more often.
- **`qwen3.5:35b` is the speed standout** (on the hardware these ran on, per the `hardware`
  column): ~**41.4 output tokens/s** and the lowest average wall-clock per scenario (**35.3 s**
  vs 48-59 s for the others). It is second in
  quality but clearly first in throughput, which makes it an attractive default when latency
  matters. Its main weakness is `loop` (33, the highest mid-turn-stall count) - it more often
  churns without converging than `ornith:35b` does.
- **Speed profile:** the 9B models run around 25-28 tok/s; `ornith:35b` 28.6 tok/s. The
  per-scenario avg-seconds matrix shows the shape: greenfield games/pages with large multi-file
  output are the slow tail (`snake-game`, `pixel-plumber`, `stellar-sound-page` run into the
  minutes, and `qwen3.5:9b` hits 1011 s on `stellar-sound-page`), while small bugfix and
  analysis cases finish in 10-25 s across all models.
- **Where the 9B models fall down:** `agent-fail` (empty structured output), `loop` (mid-turn
  stall), `build-fail` (non-compiling code), `wrong-output` (missing required element). All are
  model-capability limits, not Refio defects.
- **Refio health:** zero Refio-side crashes and zero context overflow across all 1625 runs. The one
  robustness defect surfaced by the capable 35B models (a `run_terminal_command` hang when the
  model backgrounds a server) is fixed separately; it touched at most 2 runs here. The 3
  `crash` results on `qwen3.5:35b` are its tightened streaming timeout cutting slow generations
  (see caveat), not a Refio fault. The 122b batch added no Refio-side defect.
- **`qwen3.5:122b` (full 5-run gate, 2026-07):** highest pass-rate here at **95%** (311/325), well
  clear of the 0.8 gate threshold. It is the slowest and lowest-throughput of the five (12.4 tok/s,
  ~94 s avg), skewed by the game builds it times out on. No scenario is a clean 0/5; the flaky ones
  are all external, none a Refio regression: `build-landing-multifile` 1/5 (a real browser bug - a
  `submit` button reloads the form and clears `#msg`, caught by the Playwright smoke gate),
  `pixel-plumber` 3/5 (`LLMTimeout` on the heaviest game build; the timeout fires correctly),
  `fix-two-failures` 3/5 (the needle `reversed(|[::-1]` misses a valid alternative reversal form -
  an assertion-strictness gap, same class as `find-and-fix-null-check`), and
  `build-cli-todo-app` / `swe-fix-cache-eviction` / `shell-log-report` (model-capability
  `build-fail` / `wrong-output`). The turn-finalization scenarios from this batch's changes hold up:
  `subagent-locate-and-fix` and `subagent-depth-guard` are 5/5, `subagent-two-file-fix` 4/5 (its one
  miss is a transient `headless CLI exit=1`, not a deliverable-logic failure).
