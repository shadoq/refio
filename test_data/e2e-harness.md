# Coding-agent e2e harness

A lightweight **behavioural regression harness** for Refio's coding agents. Run it **after larger
changes** (turn loop, tools, prompts) to check the agent still does the right things: picks sensible
tools, edits the right file, and leaves the project building.

It is **not** a Gradle module and **not** part of `./gradlew test` (it is slow and needs a model).
It reuses the existing **headless CLI** (`--headless … --output json`) and asserts on the produced
`run.json`. See `benchmark/scripts/e2e-run.sh` (primary, validated on macOS/Linux) and
`benchmark/scripts/e2e-run.ps1` (Windows parity).

## Consent

A real run **spends tokens / local GPU time** and **writes into a throwaway `--project`** (a temp
dir copied from the fixture; the harness deletes it unless `--keep`). Per `CLAUDE.md`'s headless
rule, a human approves the concrete command before it runs. The one exception is:

```bash
benchmark/scripts/e2e-run.sh --self-test    # asserts the engine only - no CLI, no LLM, no writes
```

## Run

```bash
./gradlew :cli:installDist                                   # build the headless CLI once

benchmark/scripts/e2e-run.sh --list                         # show selectable scenarios, then exit
benchmark/scripts/e2e-run.sh --model ollama/qwen3.5:9b \
  increase-retry-count                                       # select by id (or by file path)
benchmark/scripts/e2e-run.sh --model ollama/qwen3.5:9b --all # run every scenario
```

To run the suite against a model on **another Ollama host** (or with a different context size) without
editing config, use the sugar flags (they map to validated `--config providers.ollama.*` overrides):

```bash
benchmark/scripts/e2e-run.sh --model ollama/qwen3.5:9b \
  --ollama-host 192.168.5.60 --ollama-ctx 32768 --all     # host -> http://192.168.5.60:11434
```

PowerShell parity: `e2e-run.ps1 -Model ollama/qwen3.5:9b -OllamaHost 192.168.5.60 -OllamaCtx 32768 -All`.
`--ollama-host` accepts a bare host, `host:port`, or a full `http://host:port` URL. An explicit
`--config providers.ollama.ollama_endpoint=...` still wins over the sugar for the same key.

> **Default to a 32k or 64k context window for local models.** Pass `--ollama-ctx 32768` (or
> `65536` for extra headroom) on every local run. A small window (e.g. 16k) is truncated on the
> multi-file scenarios - `needle-in-haystack-deep` (~40 files), `find-by-symbol`, and any run that
> accumulates several tool results - and the HARD `no_context_overflow` gate then fails the run.
> That is a real signal (silent truncation is never a success), but not the one you want when
> checking agent *behaviour*: at 16k `qwen3.5:9b` overflowed all four of these scenarios, while the
> model itself supports up to 256k context. 32k/64k is comfortable and cheap. Example:
> `benchmark/scripts/e2e-run.sh --model ollama/qwen3.5:9b --ollama-ctx 65536 --all`.

Output is a markdown table: one row per scenario with the verdict + metrics. Exit code is non-zero
if any HARD assertion failed.

### Selecting scenarios

A positional argument is resolved, in order, as: (1) an existing **file path**; else
(2) `test_data/e2e/<arg>.json`; else (3) the scenario whose **`.id`** equals `<arg>`. Pass several to
run a subset, or `--all` to run them all. `--list` prints `id · mode · file` for everything
discoverable (top-level `test_data/e2e/*.json`; `examples/` is samples, not scenarios).

### Scenarios

All are designed to be solvable by **`qwen3.5:9b` or stronger** on AGENT mode (except `plan-validation`,
which is PLAN). Most gate on a shape-agnostic **regex needle** plus a **compile/run** of the mutated
project. No `kotlinc`/`python3` on PATH? Drop the `build_cmd` (it is optional) - the needle still proves
the right change landed.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `find-and-fix-null-check` | AGENT | single-file null-safety fix | regex needle + compile |
| `increase-retry-count` | AGENT | grep across 2 files, change **only** a named constant | regex needle + compile |
| `add-clamp-helper` | AGENT | add a helper **and** wire it in | 2 needles + compile |
| `fix-area-formula` | AGENT | read the contract in file B, fix the impl in file A | needle + `file_unchanged` B |
| `make-test-pass` | AGENT | fix production code so an executable test passes | **compile + run** (`java`), `file_unchanged` test |
| `py-sum-offbyone` | AGENT | cross-language (Python) off-by-one | **run** (`python3`), asserts kept |
| `haystack-https` | AGENT | find the needle among 8 files; flip http->https | needle + `absent` + overflow gate |
| `rename-function` | AGENT | rename across 3 files, no old refs left | `absent` needles + compile |
| `extract-validation` | AGENT | extract duplicated checks into one helper | `min_count`/`max_count` + compile |
| `no-bug-here` | AGENT | code is already correct - make **no** change | `file_unchanged` (anti-overeagerness) |
| `plan-validation` | PLAN | produce a plan, edit nothing | `needle_in_output` + `file_unchanged` |
| `js-fix-off-by-one` | AGENT | cross-language (JavaScript) off-by-one | **run** (`node`), asserts kept |
| `add-edge-case-test` | AGENT | add a missing test, leave the impl alone | needle in test + `file_unchanged` impl + **run** |
| `add-build-script` | AGENT | edit `package.json`, keep it valid JSON | 2 needles + `text` (existing kept) + JSON parses |
| `wire-format-helper` | AGENT | new file + wire it into a caller | 2 needles + build runs and prints `$10.00` |
| `snake-game` | AGENT | **generate** a single-file game from scratch | needles on `snake.html` (canvas/keydown/score/game over) |
| `stellar-sound-page` | AGENT | **generate** a single-file landing page | needles on `index.html` (brand/nav/form/table) |
| `pixel-plumber` | AGENT | **generate** a full single-file C64 platformer (the multi-model log workload) | needles on `plumber.html` (title/canvas/keydown/rAF/game over) + heavy SOFT judge |
| `pixel-plumber-levels` | AGENT | **generate** four named tile-map level arrays + canvas - exercises legitimate repetitive data (mostly-empty rows) so the stream guardrail must NOT abort it | 4 level-name needles + `<canvas>`/keydown + a long `.` run landed |
| `pixel-plumber-add-pause` | AGENT | **edit** an existing single-file game to add an Escape pause without breaking it | new pause/escape needle + existing canvas/rAF/title kept |

The **from-scratch generation** scenarios (`snake-game`, `stellar-sound-page`, `pixel-plumber`,
`pixel-plumber-levels`) start from an empty fixture and the agent writes the whole file. They are
heavier than the surgical edits above - expect a stronger model than `qwen3.5:9b` and watch
`no_context_overflow`, which legitimately FAILs a weak model that truncates a large file. Their needles
only check that a *real* artifact landed; the full feature list is left to the SOFT `judge`.
(`pixel-plumber-add-pause` is an **edit** scenario, not generation: it ships a working game in its
fixture and asks for one surgical addition.) The `pixel-plumber*` trio mirrors the multi-model
game-generation logs that motivated the turn-loop hardening - a complete file must land (the harness
already HARD-gates `session.status==SUCCESS`, so an intent-only stop fails), the repetitive level data
must survive the stream guardrail, and an existing file must be edited, not nuked.

### Multi-step scenarios

These deliberately need **several tool calls in sequence** (discover across files -> coordinate edits
in more than one file -> run). A partial change does not pass: the `build_cmd` runs a test that only
goes green once every required edit is in place, so they exercise the agent's ability to follow a
task through to the end rather than stop after the first edit.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `add-param-update-callers` | AGENT | add a param, find & update **all** callers across 3 files | 3 needles + **run** (`node`) + `file_unchanged` test |
| `implement-stubs` | AGENT | implement 3 stubbed functions to satisfy one suite | `absent` "not implemented" + **run** + `file_unchanged` test |
| `fix-two-failures` | AGENT | two independent bugs, one per file, make the suite green | 2 needles + **run** (`python3`) + `file_unchanged` main |

### Navigation, delegation & read-only analysis scenarios

These stress finding the right place in a larger tree, delegating to a subagent, and analysing a
module without editing it. The two PLAN scenarios gate on `needle_in_output` + `file_unchanged` (no
edit at all); the delegation one gates on the hard `tool_invoked`.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `needle-in-haystack-deep` | AGENT | a bug buried deep in a ~40-file tree - narrow with grep, don't read everything | needle on the deep fix + `file_unchanged` on 3 similarly-named decoys + overflow gate |
| `find-by-symbol` | AGENT | one function defined once, called in 5 files - edit the **definition**, not the call sites | needle on the definition + `file_unchanged` on all 5 callers |
| `create-file-in-right-dir` | AGENT | create a new file in the right package, matching the directory convention | 2 needles on the **new** path (class + package) + `file_unchanged` sibling |
| `delegate-review-then-fix` | AGENT | use the `code-reviewer` subagent to find the bug, then fix it | **`tool_invoked` `invoke_subagent` with `args_regex` `code-reviewer`** + needle on the fix |
| `subagent-depth-guard` | AGENT | tempt unbounded delegation - the max-depth-3 guard must finish, not hang | `needle_in_output` (a summary landed) + status SUCCESS |
| `analyze-architecture` | PLAN | name the components and data-flow direction of a pipeline module, edit nothing | `needle_in_output` + `file_unchanged` on **all** files |
| `locate-root-cause` | PLAN | a wrong timeout spans 3 files - point at the cause, don't fix | `needle_in_output` (root file/symbol) + `file_unchanged` on all 3 |

The delegation/subagent scenarios need a model that actually drives `invoke_subagent`; like the
generation scenarios they expect a capable model and may legitimately fail on a weak one.

### Long-context scenarios

These deliberately force the agent to hold a lot of context to succeed - the `no_context_overflow`
gate is the point, so run them with a sufficient window (see the 32k/64k note under **Run**).

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `large-file-edit` | AGENT | one ~430-line file with a bug (`totalBalance` subtracts instead of adds) buried among ~210 look-alike helpers - locate and fix it without truncating the file | needle on the `credits + debits` fix + `no_context_overflow` |

### Local network scenario

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `fetch-local-json` | AGENT | fetch a JSON file from a **local fixture server** over HTTP and extract a field | `tool_invoked` `http_request` + needle on the saved `result.txt` + `file_unchanged` on the served file |

`fetch-local-json` declares a **`fixture_server`** (see the format section): the harness serves a
directory from the temp project on `127.0.0.1` for the duration of the turn, so the I/O loop is
deterministic instead of hitting the public internet. It needs `python3`/`python` on PATH and runs the
turn with `security.allow_loopback=true` (the loopback opt-in is **off** everywhere else).

### Multi-agent scenarios

A scenario can drive the turn with a **`multi_agent`** YAML (CLI `--multi-agent`) instead of a
`prompt_file`: the harness runs the whole agent graph and asserts on the produced `run.json`
(which the multi-agent path now emits) - status, file effect, build, and **`agent_order`**.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `pipeline-2-agents` | (per-agent) | `analyst` (PLAN) -> `coder` (AGENT, `depends_on: analyst`) implement a stub | `agent_order [analyst, coder]` + needle on the impl + **compile & run** |
| `dependency-ordering` | (per-agent) | 3-agent chain `analyze -> implement -> document` | `agent_order [analyze, implement, document]` + needle on the impl |

Each agent's mode comes from the YAML (`mode: plan|agent`), so the scenario-level `mode` is ignored.
Cycle detection (a `depends_on` loop) is rejected up front by `MultiAgentRunner.validateDependencies`
(covered by its unit test), so it is not a separate e2e scenario. These need a model that drives the
multi-agent loop and may legitimately fail on a weak one.

### Browser-smoke scenarios

For generated browser artifacts, a structural needle proves `<canvas>`/`<button>` *exists* but not that
it *runs*. A **`smoke`** block adds a deterministic HARD layer: the harness renders the artifact in
headless Chromium (Playwright) and checks it mechanically - no JS errors, required DOM present, and a
scripted interaction actually changes state.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `build-counter-page` | AGENT | generate a one-file counter page that really increments | needles on `#count`/`#inc` + **browser-smoke**: loads clean, `#inc` click changes `#count` |

**Requires a one-time install** of the browser: `npm i -D playwright && npx playwright install chromium`
(run in `benchmark/`). Without it, a scenario that declares `smoke` HARD-fails with that hint - a
verifier you cannot run must never pass silently. The same `smoke` block can be added to the existing
generation scenarios (`snake-game`, `pixel-plumber`, …) to upgrade "a file landed" into "it runs".

### Application-build scenarios (multi-file, build-and-run)

The hardest generation class: build a whole small app from an **empty** fixture, gated on a
deterministic runtime rather than just a needle. These are the sharpest stress on the turn loop (long
chains, many files, deep context).

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `build-cli-todo-app` | AGENT | a multi-module Python CLI (`app.py` + `store.py` + own tests) with file-persistent state | needles on the modules/tests + **`build_cmd` runs the CLI end-to-end AND `python3 -m unittest`** |
| `build-rest-api` | AGENT | a stdlib-only HTTP API on `127.0.0.1:8792` (`/health`, `GET`/`POST /todos`) | **`build_cmd` starts the server, probes the endpoints, asserts POST persists, then stops it** |
| `build-landing-multifile` | AGENT | a landing page as three linked files (`index.html` + external `styles.css` + classic `app.js`) with form validation | structural needles on the external links + **browser-smoke**: loads clean, submit writes into `#msg` |
| `build-spa-2-routes` | AGENT | a hash-routed SPA with `#/home` and `#/about` switching a `#view` container | **browser-smoke** visits both routes, each nav changes `#view` |

The `build_cmd` for the two backend builds is the deterministic gate: it compiles/runs the app and
exercises real behaviour (the CLI persists across runs; the API serves and mutates), so "SUCCESS" also
means "it actually works". The two frontend builds gate on `smoke` (needs Playwright). All four ship an
empty fixture and carry a `judge.criteria` for the subjective completeness layer.

### CTF scenarios (defensive/educational, deterministic flag)

Local, throwaway, intentionally-vulnerable target services with a constant `FLAG{...}` baked into the
fixture. The task is open (multi-step recon + exploitation) but verification is fully deterministic:
`needle_in_output` on the exact flag string. No real targets, no egress; the flag is a masked test
secret like any other fixture value.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `ctf-exposed-endpoint` | AGENT | recon a service (`robots.txt` -> `/internal/` listing) to an undocumented status endpoint that leaks a flag | `tool_invoked` `http_request` + `needle_in_output` = exact flag + `file_unchanged` on the served files |
| `ctf-path-traversal` | AGENT | exploit an unsanitised `GET /file?name=...` with `../` to read a secret outside the web root | `tool_invoked` `http_request` + `needle_in_output` = exact flag |

`ctf-path-traversal` uses the vulnerable-server form of `fixture_server` (the `cmd` field, below) to run
an intentionally-vulnerable stdlib server (`fixtures/ctf-path-traversal/server/vuln_server.py`) instead
of the safe static file server; `ctf-exposed-endpoint` uses the plain static server. Both need a model
capable enough to chain recon requests and may legitimately fail on a weak one.

## Scenario format (JSON)

Scenarios are **JSON** (not YAML) so a single parser (`jq` in bash, native `ConvertFrom-Json` in
PowerShell) covers both the scenario and the `run.json` - no `yq` / PowerShell-Yaml dependency. Layout
under `test_data/e2e/`:

```
find-and-fix-null-check.json   # scenario
fixtures/null-bug/             # copied verbatim into the temp --project
prompts/null-bug.md            # the instruction (--prompt-file)
examples/                      # sample run.json + scenario used by --self-test (offline)
```

```jsonc
{
  "id": "find-and-fix-null-check",
  "mode": "AGENT",
  "max_iterations": 20,
  "fixture": "fixtures/null-bug",          // relative to the scenario file
  "prompt_file": "prompts/null-bug.md",
  "assert": {
    "tool_order": ["grep_search", "read_file", "advance_code_editing"],  // SOFT: subsequence
    "needle_in_file": { "path": "src/Main.kt", "regex": "x[[:space:]]*[!=]=[[:space:]]*null" }, // HARD
    "needles_in_file": [                                                                        // HARD: all required
      { "path": "src/Api.kt", "regex": "fetchRecord" },                  // must appear (>=1)
      { "path": "src/Api.kt", "regex": "legacyFetch", "absent": true },  // must NOT appear (0)
      { "path": "src/Orders.kt", "text": "qty <= 0", "max_count": 1 },   // dedup: at most 1 line
      { "path": "src/Orders.kt", "regex": "validate\\(", "min_count": 2 }// used in >= 2 places
    ],
    "needle_in_output": { "regex": "[Vv]alidat" },                       // HARD: regex vs run.json .finalOutput (PLAN/CHAT)
    "file_unchanged": ["src/Main.kt"],                                   // HARD: byte-identical to the fixture
    "build_cmd": "kotlinc src -include-runtime -d build/out.jar && java -jar build/out.jar", // HARD: exit 0 (OPTIONAL)
    "no_context_overflow": true                                          // HARD
  },
  "judge": { "criteria": ["Is the fix minimal and correct?"] }           // SOFT, advisory
}
```

**Needles.** A needle is `{ path, regex? | text?, absent?, min_count?, max_count? }`. `regex` uses
`grep -E` and is the preferred form - make it **shape-agnostic** (`x[[:space:]]*[!=]=[[:space:]]*null`
accepts both `x == null` and `x != null`) so a correct-but-differently-shaped fix still passes. Use
POSIX classes (`[[:space:]]`), **not** `\s`/`\b` - those are not portable across BSD/GNU grep. `text` is
a literal `grep -F` fallback. By default a needle must appear (>=1 matching line); `absent:true`
requires it **not** to appear (rename "old name gone"); `min_count`/`max_count` bound the matching-line
count (`max_count:1` proves a duplicate was removed, `min_count:2` proves a helper is called in >=2
places). Use the singular `needle_in_file` and/or the `needles_in_file` array - **all** of them must
match. **Keep the needle string out of fixture comments** - a needle that already lives in a comment
makes the assertion a tautology (the bug this rewrite fixed).

**`needle_in_output`** (`{ regex? | text? }`) matches against `run.json.finalOutput` - for PLAN/CHAT
scenarios that produce a plan or answer instead of editing a file. **`file_unchanged`** (array of paths)
asserts each listed file is **byte-identical to the original fixture** - proves the agent did *not*
touch it (guards "no change needed" restraint and "don't edit the test" scenarios).

**`tool_invoked`** (array of `{ name, args_regex?, absent? }`) asserts a named tool was (or, with
`absent:true`, was **not**) called during the run. Name presence reads the always-present
`run.json.conversation[].toolCalls[]`; `args_regex` additionally matches the **raw arguments JSON** in
`run.json.conversation[].toolCallDetails[]` (e.g. `{ "name": "invoke_subagent", "args_regex":
"code-reviewer" }` proves the *code-reviewer* subagent ran, not just *some* subagent). This is the
**hard** counterpart to the soft `tool_order` hint - use it when a specific tool/delegation *must* happen.

`build_cmd` is **optional and fixture-specific** - it runs inside the temp project and whatever
toolchain it names must be on PATH (`kotlinc`/`java` for the Kotlin fixtures, `python3` for
`py-sum-offbyone`; a missing tool yields a non-zero exit -> HARD fail). Make it `… && java -jar …` /
`python3 src/main.py` to **run** an executable test, not just compile. Omit it to keep a scenario
toolchain-independent (the needle still gates). Swap it for `./gradlew compileKotlin`, `npm run build`,
etc. for your own fixtures.

**`fixture_server`** (`{ dir, port }`, optional) makes the harness serve `<temp-project>/<dir>` on
`http://127.0.0.1:<port>` for the duration of the turn (via `python3 -m http.server --bind 127.0.0.1`),
so a scenario can exercise `http_request` / `fetch_webpage` against a **deterministic local endpoint**
instead of the public internet. The harness replaces a **`{{FIXTURE_SERVER}}`** placeholder in the
prompt with the base URL, runs the turn with `--config security.allow_loopback=true` (the loopback SSRF
opt-in is **off** by default for every other run), and stops the server afterwards. Needs
`python3`/`python` on PATH. Keep the served files in the fixture so `file_unchanged` can assert the
agent did not mutate the source. An optional **`cmd`** turns the static server into a **custom** server
(for an intentionally-vulnerable CTF target): the harness runs `cmd` instead of `http.server`,
substituting `{{PORT}}` and `{{DIR}}` (the absolute served dir) - e.g.
`"cmd": "exec python3 {{DIR}}/vuln_server.py {{PORT}}"`. Use `exec` so the killed PID is the server itself.

**`multi_agent`** (path to a YAML, optional) replaces `prompt_file`: the harness invokes the CLI with
`--multi-agent <yaml>` instead, running the whole agent graph against the fixture. The YAML is the
standard multi-agent definition (`name`, `agents[]` with `name`/`task`/`mode`/`depends_on`). The
multi-agent run now emits a `run.json` carrying `session.status` (SUCCESS iff every agent succeeded)
and `multiAgent.agents[]` in execution order, so the usual gates (status, needles, `build_cmd`) plus
`agent_order` all apply. A scenario sets **either** `prompt_file` **or** `multi_agent`, not both.

**`smoke`** (`{ entry, no_js_errors?, dom_present[], interactions[] }`, optional) runs a headless-Chromium
runtime check on a generated browser artifact after the turn (HARD, exit-gated like `build_cmd`).
`entry` is the HTML file (relative to the project); `no_js_errors` fails on any page/console error;
`dom_present[]` are selectors that must exist; each `interactions[]` step is `{ press | click,
expect_text_change? , expect_contains? }` - it performs the action then asserts a selector's text
changed / contains text (proves the page is actually interactive, not dead markup). Implemented by
`benchmark/scripts/browser-smoke.mjs`; needs `playwright` installed (see above).

The SOFT **`judge.criteria`** stays advisory today (every generation/build scenario carries one). A
**layer-3 oracle** would let an external strong model (distinct from the model under test) score the
artifact against the criteria and write a structured `judge-<id>.json` verdict -
`{ works, score, per_criterion: [{text, pass, why}] }` (see `examples/judge.example.json` for the exact
shape). That oracle **runner** is deliberately not wired yet: the judge stays SOFT/scored until promoted
behind a `--judge` flag on a small representative subset, with a score threshold and multi-run consensus.

## Assertion tiers

Weak/local models legitimately vary tool order and judge scores between runs. To avoid flaky "fix the
test" pressure (each assertion encodes *why* a behaviour matters, not *what* string to emit), the
harness splits assertions:

| Tier | Assertion | Effect |
|---|---|---|
| **HARD** | `needle_in_file` / `needles_in_file[]` (regex/text, with `absent`/`min_count`/`max_count`) | fail - proves the *right* change landed in the *right* file(s) |
| **HARD** | `needle_in_output` (regex/text vs `run.json.finalOutput`) | fail - for PLAN/CHAT scenarios that answer instead of editing |
| **HARD** | `file_unchanged[]` byte-identical to fixture | fail - proves a file the agent should *not* touch is intact |
| **HARD** | `build_cmd` exit `== 0` (when present; may compile **and run** a test) | fail - proves the code actually works, not just "something was written" |
| **HARD** | `no_context_overflow` (`run.json.metrics.contextOverflow == false`) | fail - silent truncation is never a success |
| **HARD** | `tool_invoked[]` (`{name, args_regex?, absent?}`) | fail - a named tool **had** to run (or, with `absent:true`, must **not** have). `args_regex` matches the raw arguments JSON in `run.json.conversation[].toolCallDetails[]`, so a scenario can assert the *right* subagent/tool ran, not just *a* tool |
| **HARD** | `agent_order[]` (multi-agent only) | fail - the listed agent names must appear in this relative order in the real execution sequence (`run.json.multiAgent.agents[]`, sorted by start time) - proves `depends_on` was respected |
| **SOFT** | `tool_order` is a **subsequence** of the real call order | warn only - order drifts on weak models |
| **SOFT** | `judge.criteria` | advisory - run the `LlmTaskVerifier` judge separately; score drift can't gate regression |

Tool order is read from `run.json.conversation[].toolCalls[]`, which requires
`--debug-level standard` (the runner sets it).

## Benchmark statistics

With `E2E_OUT_DIR` set, every run appends an enriched verdict record to `results.jsonl`. Beyond the gate
fields (`scenario`, `model`, `run`, `verdict`, `failure_mode`, `status`, `costUsd`, `tokensOut`,
`reasons[]`) each record now carries per-run behaviour derived from `run.json`:

| field | meaning |
|---|---|
| `mode` / `provider` | execution mode and LLM provider for the run |
| `iterations` | turn/tool steps taken (`metrics.toolCallCount`) |
| `apiCalls` | number of LLM API calls (`metrics.apiCallCount`) |
| `tokensIn` / `durationMs` | input tokens and wall-clock duration |
| `tools` | per-tool call histogram `{toolName: count}` (from `conversation[].toolCalls[]`) |
| `apiErrors` | provider/tool error histogram `{errorType: count}` (from `apiLogs[].errorType`) |

These fields are **additive**: the Kotlin gate (`cli --gate`, `GateRunRecord` via Gson) ignores unknown
fields, so enrichment never breaks it. `benchmark/scripts/e2e-stats.sh <results-dir | results.jsonl> …`
aggregates one or more result sets into a Markdown report - a per-model leaderboard (pass-rate, avg
iterations/tokens/**tokens per second**/cost/duration, failure modes), a scenario x model pass-rate
matrix, a scenario x model **avg-seconds-per-run** matrix (processing time per case), a tool-use
histogram, and API-error counts. It is read-only (no LLM); `e2e-stats.sh --self-test` checks the
aggregation offline. The rendered report for the tracked model runs is committed to
[`RESULTS.md`](RESULTS.md).
