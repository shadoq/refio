# Coding-agent e2e harness (docs/0061)

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
benchmark/scripts/e2e-run.sh --self-test    # asserts the engine only — no CLI, no LLM, no writes
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
  --ollama-host 192.168.5.60 --ollama-ctx 32768 --all     # host → http://192.168.5.60:11434
```

PowerShell parity: `e2e-run.ps1 -Model ollama/qwen3.5:9b -OllamaHost 192.168.5.60 -OllamaCtx 32768 -All`.
`--ollama-host` accepts a bare host, `host:port`, or a full `http://host:port` URL. An explicit
`--config providers.ollama.ollama_endpoint=...` still wins over the sugar for the same key.

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
project. No `kotlinc`/`python3` on PATH? Drop the `build_cmd` (it is optional) — the needle still proves
the right change landed.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `find-and-fix-null-check` | AGENT | single-file null-safety fix | regex needle + compile |
| `increase-retry-count` | AGENT | grep across 2 files, change **only** a named constant | regex needle + compile |
| `add-clamp-helper` | AGENT | add a helper **and** wire it in | 2 needles + compile |
| `fix-area-formula` | AGENT | read the contract in file B, fix the impl in file A | needle + `file_unchanged` B |
| `make-test-pass` | AGENT | fix production code so an executable test passes | **compile + run** (`java`), `file_unchanged` test |
| `py-sum-offbyone` | AGENT | cross-language (Python) off-by-one | **run** (`python3`), asserts kept |
| `haystack-https` | AGENT | find the needle among 8 files; flip http→https | needle + `absent` + overflow gate |
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

The two above are **from-scratch generation** scenarios (an empty fixture, the agent writes the
whole file). They are heavier than the surgical edits above - expect a stronger model than
`qwen3.5:9b` and watch `no_context_overflow`, which legitimately FAILs a weak model that truncates
a large file. Their needles only check that a *real* artifact landed; the full feature list is left
to the SOFT `judge`.

### Multi-step scenarios

These deliberately need **several tool calls in sequence** (discover across files → coordinate edits
in more than one file → run). A partial change does not pass: the `build_cmd` runs a test that only
goes green once every required edit is in place, so they exercise the agent's ability to follow a
task through to the end rather than stop after the first edit.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `add-param-update-callers` | AGENT | add a param, find & update **all** callers across 3 files | 3 needles + **run** (`node`) + `file_unchanged` test |
| `implement-stubs` | AGENT | implement 3 stubbed functions to satisfy one suite | `absent` "not implemented" + **run** + `file_unchanged` test |
| `fix-two-failures` | AGENT | two independent bugs, one per file, make the suite green | 2 needles + **run** (`python3`) + `file_unchanged` main |

## Scenario format (JSON)

The doc's example used YAML; we use **JSON** so a single parser (`jq` in bash, native
`ConvertFrom-Json` in PowerShell) covers both the scenario and the `run.json` — no `yq` /
PowerShell-Yaml dependency. Layout under `test_data/e2e/`:

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
`grep -E` and is the preferred form — make it **shape-agnostic** (`x[[:space:]]*[!=]=[[:space:]]*null`
accepts both `x == null` and `x != null`) so a correct-but-differently-shaped fix still passes
(Rule 9). Use POSIX classes (`[[:space:]]`), **not** `\s`/`\b` — those are not portable across BSD/GNU
grep. `text` is a literal `grep -F` fallback. By default a needle must appear (≥1 matching line);
`absent:true` requires it **not** to appear (rename "old name gone"); `min_count`/`max_count` bound the
matching-line count (`max_count:1` proves a duplicate was removed, `min_count:2` proves a helper is
called in ≥2 places). Use the singular `needle_in_file` and/or the `needles_in_file` array — **all** of
them must match. **Keep the needle string out of fixture comments** — a needle that already lives in a
comment makes the assertion a tautology (the bug this rewrite fixed).

**`needle_in_output`** (`{ regex? | text? }`) matches against `run.json.finalOutput` — for PLAN/CHAT
scenarios that produce a plan or answer instead of editing a file. **`file_unchanged`** (array of paths)
asserts each listed file is **byte-identical to the original fixture** — proves the agent did *not*
touch it (guards "no change needed" restraint and "don't edit the test" scenarios).

`build_cmd` is **optional and fixture-specific** — it runs inside the temp project and whatever
toolchain it names must be on PATH (`kotlinc`/`java` for the Kotlin fixtures, `python3` for
`py-sum-offbyone`; a missing tool yields a non-zero exit → HARD fail). Make it `… && java -jar …` /
`python3 src/main.py` to **run** an executable test, not just compile. Omit it to keep a scenario
toolchain-independent (the needle still gates). Swap it for `./gradlew compileKotlin`, `npm run build`,
etc. for your own fixtures.

## Assertion tiers (docs/0061 review note)

Weak/local models legitimately vary tool order and judge scores between runs. To avoid flaky
"fix the test" pressure (Rule 9 — encode *why*, not *what*), the harness splits assertions:

| Tier | Assertion | Effect |
|---|---|---|
| **HARD** | `needle_in_file` / `needles_in_file[]` (regex/text, with `absent`/`min_count`/`max_count`) | fail — proves the *right* change landed in the *right* file(s) |
| **HARD** | `needle_in_output` (regex/text vs `run.json.finalOutput`) | fail — for PLAN/CHAT scenarios that answer instead of editing |
| **HARD** | `file_unchanged[]` byte-identical to fixture | fail — proves a file the agent should *not* touch is intact |
| **HARD** | `build_cmd` exit `== 0` (when present; may compile **and run** a test) | fail — proves the code actually works, not just "something was written" |
| **HARD** | `no_context_overflow` (`run.json.metrics.contextOverflow == false`) | fail — silent truncation (docs/0057) is never a success |
| **SOFT** | `tool_order` is a **subsequence** of the real call order | warn only — order drifts on weak models |
| **SOFT** | `judge.criteria` | advisory — run the `LlmTaskVerifier` judge separately; score drift can't gate regression |

Tool order is read from `run.json.conversation[].toolCalls[]`, which requires
`--debug-level standard` (the runner sets it).
