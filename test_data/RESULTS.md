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
of the benchmark. The four runs below were all served by Ollama on an **NVIDIA DGX Spark**.

Reproduce:

```bash
# gate a model over 5 runs, persisting per-run records
bash benchmark/scripts/e2e-gate.sh --all --model <provider/model> \
  --runs 5 --threshold 0.8 --ollama-host <ollama-host> --max-cost 0 --out <dir>

# aggregate one or more result dirs into the report below (quality + speed)
bash benchmark/scripts/e2e-stats.sh <dir-model-a> <dir-model-b> ...
```

Runs: 2026-07-09/10, four models, 5 runs each over 63 scenarios (1260 runs total). The `hardware`
column is added by hand per batch (the stats script does not know which host served the model).

> Caveat on `ornith:35b`: it ran on a CLI dist built before the `run_terminal_command`
> process-group fix. A watchdog reaped orphaned test servers during the run and logged 2
> reaps, so at most 2 of its 315 runs (server-starting scenarios) may reflect that tool hang
> rather than the model. The other three models rarely started servers and are unaffected.

> Caveat on `qwen3.5:35b`: it ran with tightened streaming timeouts
> (`streaming_request_timeout_sec=600`, `streaming_read_timeout_sec=120`) so a stalled
> generation fails fast instead of hanging. Its 3 `crash` results (3 `Exception` / 3
> `LLMError` / 4 `StreamAbortedException` records) are mostly slow generations cut by that
> shorter cap; under the default 1800/240 s timeouts a few of them would likely have completed.

---

# e2e benchmark statistics

1260 run(s) · 4 model(s) · 63 scenario(s) · overall pass-rate 73%

## Per-model

| model | hardware | runs | pass | pass-rate | avg iters | avg tokOut | tok/s | total cost | avg ms | failure modes |
|---|---|---|---|---|---|---|---|---|---|---|
| ollama/ornith:35b | DGX Spark | 315 | 268 | 85% | 4 | 1358 | 28.5 | 0 | 47553 | agent-fail:22 loop:13 build-fail:6 wrong-output:3 loop-aborted:3 |
| ollama/ornith:9b | DGX Spark | 315 | 206 | 65% | 4 | 1274 | 25.4 | 0 | 50158 | agent-fail:35 build-fail:20 wrong-output:38 loop:14 loop-aborted:2 |
| ollama/qwen3.5:35b | DGX Spark | 315 | 239 | 75% | 3 | 1472 | 41.7 | 0 | 35298 | agent-fail:23 loop:31 wrong-output:13 crash:3 build-fail:6 |
| ollama/qwen3.5:9b | DGX Spark | 315 | 218 | 69% | 3 | 1643 | 27.8 | 0 | 59046 | agent-fail:24 loop:27 build-fail:20 loop-aborted:1 wrong-output:25 |

## Scenario x model (pass-rate)

| scenario \ model | ollama/ornith:35b | ollama/ornith:9b | ollama/qwen3.5:35b | ollama/qwen3.5:9b |
|---|---|---|---|---|
| add-build-script | 0% (0/5) | 0% (0/5) | 0% (0/5) | 0% (0/5) |
| add-clamp-helper | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| add-edge-case-test | 100% (5/5) | 60% (3/5) | 40% (2/5) | 100% (5/5) |
| add-endpoint-status | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) |
| add-param-update-callers | 100% (5/5) | 0% (0/5) | 20% (1/5) | 0% (0/5) |
| analyze-architecture | 0% (0/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) |
| build-cli-todo-app | 20% (1/5) | 0% (0/5) | 20% (1/5) | 0% (0/5) |
| build-counter-page | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| build-landing-multifile | 80% (4/5) | 0% (0/5) | 80% (4/5) | 60% (3/5) |
| build-rest-api | 80% (4/5) | 0% (0/5) | 100% (5/5) | 80% (4/5) |
| build-spa-2-routes | 100% (5/5) | 80% (4/5) | 60% (3/5) | 80% (4/5) |
| code-question-backoff | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| create-file-in-right-dir | 100% (5/5) | 60% (3/5) | 60% (3/5) | 20% (1/5) |
| ctf-exposed-endpoint | 80% (4/5) | 0% (0/5) | 40% (2/5) | 80% (4/5) |
| ctf-path-traversal | 80% (4/5) | 0% (0/5) | 60% (3/5) | 40% (2/5) |
| delegate-review-fix-discount | 60% (3/5) | 20% (1/5) | 80% (4/5) | 40% (2/5) |
| delegate-review-then-fix | 60% (3/5) | 40% (2/5) | 100% (5/5) | 100% (5/5) |
| dep-bump-fix-callers | 100% (5/5) | 100% (5/5) | 60% (3/5) | 80% (4/5) |
| dependency-ordering | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) |
| edge-case-empty-stats | 100% (5/5) | 100% (5/5) | 60% (3/5) | 60% (3/5) |
| extract-validation | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) |
| fetch-local-json | 100% (5/5) | 60% (3/5) | 80% (4/5) | 100% (5/5) |
| find-and-fix-null-check | 100% (5/5) | 100% (5/5) | 100% (5/5) | 80% (4/5) |
| find-by-symbol | 100% (5/5) | 100% (5/5) | 80% (4/5) | 60% (3/5) |
| fix-area-formula | 100% (5/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) |
| fix-failing-slugify | 100% (5/5) | 100% (5/5) | 80% (4/5) | 40% (2/5) |
| fix-two-failures | 100% (5/5) | 60% (3/5) | 20% (1/5) | 60% (3/5) |
| haystack-https | 100% (5/5) | 80% (4/5) | 100% (5/5) | 80% (4/5) |
| implement-roman-numerals | 100% (5/5) | 100% (5/5) | 60% (3/5) | 100% (5/5) |
| implement-stubs | 100% (5/5) | 100% (5/5) | 80% (4/5) | 60% (3/5) |
| increase-retry-count | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| infra-compose-config | 100% (5/5) | 80% (4/5) | 80% (4/5) | 60% (3/5) |
| js-fix-off-by-one | 100% (5/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) |
| kotlin-scope-trace | 80% (4/5) | 40% (2/5) | 80% (4/5) | 40% (2/5) |
| large-file-edit | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| locate-root-cause | 100% (5/5) | 80% (4/5) | 80% (4/5) | 100% (5/5) |
| make-test-pass | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) |
| mixed-security-review | 20% (1/5) | 20% (1/5) | 80% (4/5) | 80% (4/5) |
| monorepo-deps | 60% (3/5) | 80% (4/5) | 80% (4/5) | 80% (4/5) |
| needle-in-haystack-deep | 60% (3/5) | 80% (4/5) | 20% (1/5) | 40% (2/5) |
| no-bug-here | 100% (5/5) | 100% (5/5) | 80% (4/5) | 40% (2/5) |
| node-callgraph | 40% (2/5) | 100% (5/5) | 80% (4/5) | 80% (4/5) |
| pipeline-2-agents | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| pipeline-root-cause | 100% (5/5) | 100% (5/5) | 80% (4/5) | 60% (3/5) |
| pixel-plumber | 80% (4/5) | 40% (2/5) | 60% (3/5) | 0% (0/5) |
| pixel-plumber-add-pause | 100% (5/5) | 0% (0/5) | 80% (4/5) | 60% (3/5) |
| pixel-plumber-levels | 60% (3/5) | 20% (1/5) | 60% (3/5) | 60% (3/5) |
| plan-validation | 100% (5/5) | 60% (3/5) | 100% (5/5) | 80% (4/5) |
| py-sum-offbyone | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| py-test-gap | 100% (5/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| py-webservice-map | 20% (1/5) | 40% (2/5) | 80% (4/5) | 40% (2/5) |
| react-feature-locate | 80% (4/5) | 60% (3/5) | 80% (4/5) | 80% (4/5) |
| regression-test-duration | 100% (5/5) | 100% (5/5) | 80% (4/5) | 20% (1/5) |
| rename-across-files | 100% (5/5) | 20% (1/5) | 60% (3/5) | 40% (2/5) |
| rename-function | 100% (5/5) | 0% (0/5) | 100% (5/5) | 100% (5/5) |
| shell-cli-surface | 80% (4/5) | 100% (5/5) | 80% (4/5) | 100% (5/5) |
| shell-log-report | 80% (4/5) | 80% (4/5) | 20% (1/5) | 60% (3/5) |
| snake-game | 100% (5/5) | 40% (2/5) | 60% (3/5) | 0% (0/5) |
| sql-schema-index | 100% (5/5) | 80% (4/5) | 100% (5/5) | 100% (5/5) |
| stellar-sound-page | 100% (5/5) | 60% (3/5) | 100% (5/5) | 40% (2/5) |
| subagent-depth-guard | 60% (3/5) | 100% (5/5) | 100% (5/5) | 100% (5/5) |
| swe-fix-cache-eviction | 100% (5/5) | 20% (1/5) | 80% (4/5) | 80% (4/5) |
| wire-format-helper | 80% (4/5) | 40% (2/5) | 80% (4/5) | 0% (0/5) |

## Scenario x model (avg seconds per run)

| scenario \ model | ollama/ornith:35b | ollama/ornith:9b | ollama/qwen3.5:35b | ollama/qwen3.5:9b |
|---|---|---|---|---|
| add-build-script | 20.7 | 24.8 | 18.7 | 21.1 |
| add-clamp-helper | 15.2 | 32.6 | 20.7 | 20.8 |
| add-edge-case-test | 14.7 | 22.4 | 16.5 | 16.3 |
| add-endpoint-status | 18.6 | 18.1 | 17.3 | 21.9 |
| add-param-update-callers | 35.3 | 43.5 | 25.1 | 35.9 |
| analyze-architecture | 15.1 | 39.9 | 29.1 | 30.5 |
| build-cli-todo-app | 230.8 | 53.2 | 54.8 | 79.6 |
| build-counter-page | 15.7 | 28.1 | 14.6 | 20.1 |
| build-landing-multifile | 70.5 | 46.9 | 44.3 | 52.3 |
| build-rest-api | 767 | 50.7 | 26.3 | 44.3 |
| build-spa-2-routes | 21 | 50.4 | 17.8 | 31.1 |
| code-question-backoff | 13.9 | 17.2 | 13.1 | 13.6 |
| create-file-in-right-dir | 18.9 | 33.7 | 26.4 | 28 |
| ctf-exposed-endpoint | 46.6 | 88.8 | 15.8 | 29.7 |
| ctf-path-traversal | 38.7 | 73.3 | 14.9 | 52 |
| delegate-review-fix-discount | 41.9 | 36 | 34.4 | 39.8 |
| delegate-review-then-fix | 25.3 | 33.1 | 49.9 | 30.1 |
| dep-bump-fix-callers | 15.6 | 23.8 | 18.9 | 22.9 |
| dependency-ordering | 29.4 | 44.1 | 28.1 | 34.1 |
| edge-case-empty-stats | 17.8 | 25.1 | 21 | 23 |
| extract-validation | 19.5 | 28.2 | 17.2 | 19.7 |
| fetch-local-json | 8.9 | 17 | 9.4 | 13.5 |
| find-and-fix-null-check | 18.5 | 19.2 | 13.1 | 15.6 |
| find-by-symbol | 16.5 | 31.7 | 17.8 | 32.4 |
| fix-area-formula | 12.7 | 16.5 | 19 | 17.5 |
| fix-failing-slugify | 18.3 | 20.2 | 21.2 | 27.6 |
| fix-two-failures | 21.6 | 29.2 | 19.8 | 28.1 |
| haystack-https | 12.1 | 20.8 | 13.8 | 19 |
| implement-roman-numerals | 26.1 | 49.1 | 24.5 | 34.2 |
| implement-stubs | 24 | 41.3 | 21.2 | 49.5 |
| increase-retry-count | 11.3 | 22.1 | 20.9 | 21.9 |
| infra-compose-config | 22.7 | 25.7 | 20.1 | 23 |
| js-fix-off-by-one | 11.6 | 17.3 | 14.5 | 17.6 |
| kotlin-scope-trace | 26.7 | 36.1 | 23.8 | 27.8 |
| large-file-edit | 15.6 | 25.6 | 20.3 | 20.3 |
| locate-root-cause | 18.8 | 28.7 | 17.9 | 24.6 |
| make-test-pass | 17.2 | 30 | 20.9 | 29.8 |
| mixed-security-review | 24.8 | 32.9 | 22.1 | 33.7 |
| monorepo-deps | 29.9 | 40.3 | 25.5 | 37.8 |
| needle-in-haystack-deep | 15.9 | 30.8 | 23.1 | 39.8 |
| no-bug-here | 59.6 | 53.4 | 20.4 | 52.6 |
| node-callgraph | 23.9 | 37.1 | 21.2 | 33.5 |
| pipeline-2-agents | 26.2 | 32.6 | 20.7 | 27.4 |
| pipeline-root-cause | 22.2 | 29.7 | 17.5 | 22.8 |
| pixel-plumber | 286.2 | 498.7 | 285.9 | 341.1 |
| pixel-plumber-add-pause | 37.5 | 20.4 | 91 | 54.5 |
| pixel-plumber-levels | 37.2 | 92.3 | 58 | 260.5 |
| plan-validation | 38.1 | 35.5 | 21.9 | 28.1 |
| py-sum-offbyone | 12.5 | 19.7 | 13.2 | 18.6 |
| py-test-gap | 13.5 | 13.9 | 10.6 | 14.5 |
| py-webservice-map | 29.6 | 52.4 | 41.2 | 33.3 |
| react-feature-locate | 36.7 | 30.6 | 23.7 | 29.5 |
| regression-test-duration | 28.5 | 55.6 | 25.8 | 50.9 |
| rename-across-files | 33.2 | 28.4 | 21.3 | 69.5 |
| rename-function | 11.8 | 24.5 | 26.8 | 17 |
| shell-cli-surface | 24.2 | 30.6 | 18.3 | 23.1 |
| shell-log-report | 14.9 | 22.7 | 20 | 12.6 |
| snake-game | 77.8 | 178.2 | 297.2 | 338.9 |
| sql-schema-index | 14 | 20 | 12.9 | 14.5 |
| stellar-sound-page | 239 | 418.3 | 232.9 | 1011.1 |
| subagent-depth-guard | 25.4 | 10.4 | 6.5 | 9.4 |
| swe-fix-cache-eviction | 25.6 | 68.5 | 19.3 | 46.5 |
| wire-format-helper | 29.6 | 34.7 | 20.5 | 26.3 |

## Tool usage (all runs)

| tool | total calls | runs used | avg/run |
|---|---|---|---|
| read_file | 2231 | 1073 | 1.77 |
| code_editing | 560 | 497 | 0.44 |
| run_terminal_command | 451 | 310 | 0.35 |
| read_directory | 359 | 212 | 0.28 |
| create_new_file | 284 | 218 | 0.22 |
| think | 151 | 85 | 0.11 |
| grep_search | 137 | 119 | 0.1 |
| http_request | 126 | 49 | 0.1 |
| advance_code_editing | 77 | 71 | 0.06 |
| file_search | 75 | 71 | 0.05 |
| tasks | 68 | 24 | 0.05 |
| invoke_subagent | 53 | 47 | 0.04 |
| run_code | 50 | 28 | 0.03 |
| multi_edit | 49 | 37 | 0.03 |
| memory | 43 | 26 | 0.03 |
| fetch_webpage | 39 | 10 | 0.03 |
| rename_symbol | 17 | 17 | 0.01 |
| code_intelligence | 15 | 14 | 0.01 |
| rag_search | 15 | 14 | 0.01 |
| find_usages | 5 | 5 | 0 |
| multi_line_editor | 5 | 4 | 0 |
| run_process_background | 5 | 5 | 0 |
| llm_call | 4 | 4 | 0 |
| monitor_process | 4 | 4 | 0 |
| manage_subagent | 3 | 2 | 0 |
| newTask | 3 | 1 | 0 |
| answer_message | 1 | 1 | 0 |
| directory_read | 1 | 1 | 0 |
| edit_file | 1 | 1 | 0 |
| list_files_recursive | 1 | 1 | 0 |
| mark_step_complete | 1 | 1 | 0 |
| send_task_to_agent | 1 | 1 | 0 |
| sleep | 1 | 1 | 0 |
| task_use | 1 | 1 | 0 |
| todo | 1 | 1 | 0 |
| write_file | 1 | 1 | 0 |

## API errors

| error type | count |
|---|---|
| Exception | 9 |
| LLMError | 9 |
| StreamAbortedException | 8 |

---

## Interpretation

- **Ranking (single gate, HARD assertions):** `ornith:35b` 85% > `qwen3.5:35b` 75% >
  `qwen3.5:9b` 69% > `ornith:9b` 65%. Model size dominates: both 35B models clear the 9B pair,
  and they fail *softer* - their `build-fail` / `wrong-output` counts stay low (6/3 for
  `ornith:35b`, 6/13 for `qwen3.5:35b`) versus the 9B models (build-fail ~20, wrong-output
  25-38), i.e. when a 35B writes code it compiles and hits the target far more often.
- **`qwen3.5:35b` is the speed standout** (on the hardware these ran on, per the `hardware`
  column): ~**41.7 output tokens/s** and the lowest average wall-clock per scenario (**35.3 s**
  vs 47-59 s for the others). It is second in
  quality but clearly first in throughput, which makes it an attractive default when latency
  matters. Its main weakness is `loop` (31, the highest mid-turn-stall count) - it more often
  churns without converging than `ornith:35b` does.
- **Speed profile:** the 9B models run around 25-28 tok/s; `ornith:35b` 28.5 tok/s. The
  per-scenario avg-seconds matrix shows the shape: greenfield games/pages with large multi-file
  output are the slow tail (`snake-game`, `pixel-plumber`, `stellar-sound-page` run into the
  minutes, and `qwen3.5:9b` hits 1011 s on `stellar-sound-page`), while small bugfix and
  analysis cases finish in 10-25 s across all models.
- **Where the 9B models fall down:** `agent-fail` (empty structured output), `loop` (mid-turn
  stall), `build-fail` (non-compiling code), `wrong-output` (missing required element). All are
  model-capability limits, not Refio defects.
- **Refio health:** zero Refio-side crashes and zero context overflow across 1260 runs. The one
  robustness defect surfaced by the capable 35B models (a `run_terminal_command` hang when the
  model backgrounds a server) is fixed separately; it touched at most 2 runs here. The 3
  `crash` results on `qwen3.5:35b` are its tightened streaming timeout cutting slow generations
  (see caveat), not a Refio fault.
