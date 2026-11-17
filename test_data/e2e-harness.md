# Coding-agent e2e harness

A lightweight **behavioural regression harness** for Refio's coding agents. Run it **after larger
changes** (turn loop, tools, prompts) to check the agent still does the right things: picks sensible
tools, edits the right file, and leaves the project building.

It is **not** a Gradle module and **not** part of `./gradlew test` (it is slow and needs a model).
It reuses the existing **headless CLI** (`--headless … --output json`) and asserts on the produced
`run.json`. See `tools/e2e/e2e-run.sh` (primary, validated on macOS/Linux) and
`tools/e2e/e2e-run.ps1` (Windows parity).

## Consent

A real run **spends tokens / local GPU time** and **writes into a throwaway `--project`** (a temp
dir copied from the fixture; the harness deletes it unless `--keep`). Per `CLAUDE.md`'s headless
rule, a human approves the concrete command before it runs. The one exception is:

```bash
tools/e2e/e2e-run.sh --self-test    # asserts the engine only - no CLI, no LLM, no writes
```

## Run

```bash
./gradlew :cli:installDist                                   # build the headless CLI once

tools/e2e/e2e-run.sh --list                         # show selectable scenarios, then exit
tools/e2e/e2e-run.sh --list --category C --filter ERE --json   # narrow by category / id regex (also narrows --all), JSON output
tools/e2e/e2e-run.sh --catalog                      # Markdown table of the corpus, then exit
tools/e2e/e2e-run.sh --model ollama/qwen3.5:9b \
  increase-retry-count                                       # select by id (or by file path)
tools/e2e/e2e-run.sh --model ollama/qwen3.5:9b --all # run every scenario
```

To run the suite against a model on **another Ollama host** (or with a different context size) without
editing config, use the sugar flags (they map to validated `--config providers.ollama.*` overrides):

```bash
tools/e2e/e2e-run.sh --model ollama/qwen3.5:9b \
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
> `tools/e2e/e2e-run.sh --model ollama/qwen3.5:9b --ollama-ctx 65536 --all`.

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
| `add-build-script` | AGENT | edit `package.json`, keep it valid JSON | 2 needles + `text` (existing kept) + JSON parses + `preserved_except` (JSON identical outside `scripts.build`) |
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
| `large-file-edit` | AGENT | one ~430-line file with a bug (`totalBalance` subtracts instead of adds) buried among ~210 look-alike helpers - locate and fix it without truncating the file | needle on the `credits + debits` fix + `preserved_except` (every line outside `totalBalance` unchanged) + `no_context_overflow` |

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
| `multi-agent-handoff` | (per-agent) | `measurer` (profile without editing tools) runs `node tools/measure.js`; `editor` (profile without a terminal, `depends_on: measurer`) sets `TIMEOUT_MS` to twice the reported p95, so the number can only reach it through the dependency report | `agent_order [measurer, editor]` + `node check.js` + measurement and check files unchanged |
| `multi-agent-red-blue` | (per-agent) | `red` (profile with `http_request` but no edit tools) exploits the SQL-injectable `/user` lookup on the **live** fixture server and saves the leaked admin secret to `redteam/leaked-flag.txt`; `blue` (profile with edit tools, `depends_on: red`) patches `lookup_user` with a parameterized query | `agent_order [red, blue]` + needle on `redteam/leaked-flag.txt` + `python3 attack_check.py` (checks red's extraction AND that blue's patch kills both injection payloads while legitimate lookups survive) + placeholder needle + `file_unchanged` on gate/profiles |

Each agent's mode comes from the YAML (`mode: plan|agent`), so the scenario-level `mode` is ignored.
The handoff and red-blue scenarios' profiles live in the fixture's `.refio/agents/`, so they apply only
to that run. `multi-agent-red-blue` is also the first multi-agent scenario that combines the graph with
a live `fixture_server` (the `cmd` form, port 8727): the red agent attacks the running old code during
the turn, and `attack_check.py` starts its own instance on port 8728 afterwards to judge the patch.
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

**Requires a one-time install** of the browser: `npm run smoke:install`
(run in `tools/e2e/`). Without it, a scenario that declares `smoke` HARD-fails with that hint - a
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

The benchmark catalog adds a `multi-file` category for the same idea on a bigger scale: the deliverable is
a set of modules under `src/`, the fixture ships its own `node --test` suite as the `build_cmd`, and a
golden solution lets `validate-scenarios.sh` prove the untouched fixture fails while the golden passes.
`multi-file-notes-api-hidden-tests` builds an API against the acceptance suite shipped in the fixture. Despite the
historical id, that suite is **visible** to the agent (the prompt names it) and only protected from edits by
`file_unchanged`, so a PASS measures "made the visible tests pass", not generalisation to unseen cases. A variant
that delivers independent acceptance tests only after the turn has ended does not exist yet.
`multi-file-refactor-keep-tests-green` splits a tangled module while its suite stays green, and an `absent`
needle proves `src/legacy.js` became a facade (it may no longer define functions itself).

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

### Security-workflow scenarios (audit, white team, red team)

Beyond the CTF pair, three scenarios cover the three directions of security work on the same
local-and-throwaway footing: reading code for weaknesses, fixing what an attack would hit, and
running the attack itself. All three are deterministic without an external oracle: the audit
gates on the report's content, the white-team run on an attack-replay gate shipped in the
fixture, and the red-team run on the exact flag string.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `vuln-audit-report` | AGENT | white-box audit of a small notes service: find the SQL injection, the path traversal and the hardcoded credential, and write a structured report | needles on `report/findings.md` (function name + `CWE-89`/`CWE-22`/`CWE-798` + severity) + `file_unchanged` on the code (an audit does not modify) |
| `white-team-harden-server` | AGENT | defensive hardening: contain the download endpoint to `public/` and replace the hardcoded admin password with `ADMIN_PASSWORD` from the environment | `build_cmd` = `python3 attack_check.py` (replays traversal + credential attacks AND the legit traffic) + needles (`ADMIN_PASSWORD` present, old literal absent) + `file_unchanged` on the gate |
| `red-team-cmd-injection` | AGENT | exploit unsanitised shell interpolation in `GET /ping?host=...` (OS command injection) to read a flag outside the web root | `tool_invoked` `http_request` + `needle_in_output` = exact flag + `file_unchanged` on the server |

`white-team-harden-server` is the mirror of a CTF exploit: the same attacks that would prove a
weakness must now FAIL while the legitimate requests keep succeeding - a "block everything"
regression fails the gate the same way an unfixed traversal does (its `deny-all` negative
control proves it). `attack_check.py` runs the vulnerable functions directly, so it needs no
live server; the red-team scenario uses the `cmd` form of `fixture_server` (port 8726) like
`ctf-path-traversal`. Both `vuln-audit-report` and `white-team-harden-server` carry golden
solutions and negative controls for `validate-scenarios.sh`; `red-team-cmd-injection` ships a
golden `answer.txt` only (its flag gate is run-time).

### Reverse-engineering scenario (decompilation and code analysis)

| id | mode | what it exercises | key assertion |
|---|---|---|
| `decompile-vm-crackme` | AGENT | decompile a bytecode program for a documented stack VM (`vm.py` + `ISA.md`): recover the passphrase from the obfuscated per-character XOR comparisons and obtain the flag from the success path | `needle_in_output` = exact flag (which embeds the passphrase) + `file_unchanged` on `vm.py`/`program.bin`/`ISA.md` (patching the check is not solving it) |

The fixture is a deliberate crackme: `program.bin` holds eight unrolled `INPUT; PUSH k; XOR;
PUSH t; EQ; JNZ; JMP` blocks (passphrase char XOR key == target) followed by `PUSH e; PUSH k2;
XOR; PRINT` groups for the flag. Two legitimate solution paths exist - decode everything
statically, or recover the passphrase statically and run `python3 vm.py program.bin
<passphrase>` - and both require actually reading the bytecode; brute force at a 9b-model's
iteration budget is not viable. Ships a golden `answer.txt` for the offline validator.

### Incident-driven debugging scenario

The existing repair scenarios start from a failing test; this one starts from a production
incident artifact, like a real debugging session does.

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `debug-crash-report` | AGENT | diagnose from `crash-report.txt`: an `IndexError` in `take_recent` when fewer events exist than requested, plus a cross-call state leak from a mutable default argument in `record_event` | `build_cmd` = `python3 checks.py` (replays both failure modes + runs the visible suite) + needles (`history is None`-style sentinel present, `history=[]` absent) + `file_unchanged` on the report, the gate and the tests |

Both bugs must be fixed for the gate to pass: the visible unit tests pass on the untouched
fixture (they never trigger either failure mode), so the scenario separates "diagnose from
symptoms" from "make the visible tests green". The partial-fix negative controls
(`mutable-kept`, `crash-kept`) prove that fixing only one symptom fails.

### Linux operations scenario

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `linux-ops-audit` | AGENT | on-call sysadmin work: triage `var/log/auth.log` (count failed-password logins, find the top offending IP) into `report/intrusion-report.txt`, tighten a world-writable file to mode 640, restore a script's executable bit | `build_cmd` = `bash verify.sh` (checks both report lines, the 640 mode and the executable bit) + `tool_invoked` `run_terminal_command` + needles on the report + `file_unchanged` on the log and the gate |

Two run notes. (1) **`chmod` is not in the harness's default auto-approve set**, so the two
permission items need either an operator-passed `--auto-approve` that adds it (the default
regex plus `\bchmod\b`) or an approved equivalent the model can reach (e.g. `python3 -c
'import os; os.chmod(...)'`). Example:

```bash
tools/e2e/e2e-run.sh --model ollama/qwen3.5:9b --auto-approve '(\bchmod\b|\b(kotlinc?|gradlew|gradle|javac|java|python3?|pip3?|node|npm|npx|pnpm|yarn|pytest|mvn|cargo|go|make|cmake|ls|cat|pwd|echo|head|tail|sed|awk|grep|rg|find|wc|diff|test|true|cd|sh|bash|env|export|mkdir|touch|mv|cp|tr|sort|uniq|cut|sleep|which|lsof)\b)' linux-ops-audit
```

(2) File modes are not content: `file_unchanged` compares bytes, so `chmod` does not violate it
(the mode checks live in `verify.sh`), and the fixture's initial modes are prompt-stated rather
than git-tracked (git only remembers the executable bit). The scenario has no golden dir for
the same reason - a golden overlay cannot carry a mode change.

## Benchmark-style scenario batch (Terminal-Bench inspired)

Twenty-six further hand-written scenarios extend the suite into domains the plugin had not
been tested in, in the Terminal-Bench spirit: every task is a small but **real** job with a
**deterministic, self-contained gate** (a checker script, a digest, a re-derived answer) -
never a fuzzy "looks plausible" judgement. Where the gate is file-level, the scenario also
ships a golden solution and negative controls for `validate-scenarios.sh`; where the gate is
run-time (live servers, multi-agent order, `needle_in_output` flags), a golden `answer.txt`
documents the expected result instead.

### CTF / exploitation (live local targets)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `ctf-jwt-forgery` | AGENT | the API's HS256 secret is committed in `server/config/dev.env`; recover it and mint an admin token | `tool_invoked` `http_request` + `needle_in_output` = exact flag + `file_unchanged` on server/config |
| `ctf-ssrf-metadata` | AGENT | `/preview?url=` denylists loopback hostnames by literal string (bypass with `0.0.0.0`/integer IP) and attaches its internal token to every fetch; pivot to the metadata service one port up | `tool_invoked` `http_request` + exact flag |
| `red-team-deserialization` | AGENT | the template store unpickles partner uploads; craft a `__reduce__` payload that exfiltrates the secret **on unpickle** | `needle_in_output` flag + needle on `exfil/out.txt` + `build_cmd` = `python3 verify.py` (wipes exfil first, so hand-written files cannot pass) + `tool_invoked` `run_terminal_command` |
| `crypto-token-forgery` | AGENT | a homemade "MAC" is repeating-key XOR over the readable payload; recover the keystream from captures (the key length is not public - cross-consistency over captures pins it) and forge an admin token | `build_cmd` = `python3 check.py` (validates signature, admin, future expiry, prints the flag) |

`ctf-jwt-forgery` (port 8730) and `ctf-ssrf-metadata` (port 8731, metadata on 8732) use the
`cmd` form of `fixture_server`; the SSRF target binds its internal service one port above
the public one and only accepts requests carrying the proxy's internal token, so the
exercise genuinely has to go through the SSRF. `crypto-token-forgery`'s gate re-derives
the token's validity through the fixture's own verifier; `red-team-deserialization`'s gate
executes the crafted pickle exactly the way the loader does.

### Security analysis, audit and forensics

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `xss-dom-audit` | AGENT | find the real DOM-XSS sink (`innerHTML` fed from `location.hash`) next to a safe `textContent` look-alike | needles on `report/xss-findings.md` (sink, source, `CWE-79`, PoC payload) + `file_unchanged` on the app |
| `container-sec-audit` | AGENT | Dockerfile/compose misconfigurations: baked secret, root user, `latest` tags, `privileged` sidecar, `0.0.0.0` publish | needles with CWEs/severities on the report + untouched configs |
| `privesc-path-audit` | AGENT | separate viable privesc paths (NOPASSWD `sudo tar` GTFOBins technique; root-cron script writable by our group) from a patched SUID screen | needles (the `--checkpoint` payload, the writable script, the patched rejection) |
| `malware-triage-deobfuscate` | AGENT | statically peel a two-layer base64 shell loader; document C2, both persistence mechanisms, exfil target | needles on the IOC report incl. `classification=shell_loader` |
| `net-forensics-exfil` | AGENT | reassemble DNS-tunnelled base32 chunks (sequence-ordered, marker and noise ignored) from a capture log | `build_cmd` = `python3 checks.py` (sha256 gate - the plaintext is not in the fixture) |
| `log-incident-timeline` | AGENT | triage an ssh brute force + sudo escalation + useradd persistence across two logs into `key=value` facts | `build_cmd` gate re-derives all five facts from the logs and matches the report |
| `api-contract-diff` | AGENT | three breaking changes between two API specs (field removed, param now required, type changed) vs one additive | needles on the breaking-changes report + untouched specs/SDK |
| `dependency-cve-triage` | AGENT | advisory triage by **reachability**: bump the two imported vulnerable deps, leave the unreachable one | needles on `package.json` + **`preserved_except` `json_paths`** proving only those two dependencies changed |

### Reverse engineering and static decoding

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `re-keygen-algorithm` | AGENT | recover a licence-check algorithm (hex checksum half + per-position letters half) and compute a key | `build_cmd` = `python3 verify_key.py` (checks the exact user) + format needle |
| `deobfuscate-javascript` | AGENT | a rotated string-array, hex-escaped beacon: account for the IIFE rotation before mapping accessor indices | needles on the deobfuscated-config report (decoys fail if the rotation is ignored) |
| `hexblob-decode` | AGENT | follow a documented firmware image format (magic, section table, XOR-0x5A string entries) in a hex dump | `needle_in_output` = exact flag (static decode, nothing executed) |

### Concurrency, debugging and test quality

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `debug-race-condition` | AGENT | a lost-update race across a **compliance-mandated 1 ms window** - lock it, don't delete the window | `build_cmd` stress (exact total) + latency probe (window kept) + needles (`Lock`, `time.sleep`) |
| `debug-deadlock` | AGENT | opposite-direction transfers deadlock on lock order; fix with id-sorted acquisition | `build_cmd` runs the deadly pair with join timeouts + needles (`sorted(`, window kept) |
| `flaky-test-stabilize` | AGENT | an unseeded-random assertion and an order-dependent scratch file - fix the **tests**, not the code | `build_cmd` runs four deterministic orderings/seeds and rejects skips/deletions; `src/pricing.py` `file_unchanged` |
| `memory-leak-trace` | AGENT | diagnose from a tracemalloc-style profile: audit trail keeps payload copies, live map unbounded | `build_cmd` = functional + two tracemalloc bounds (churn, unbounded open) + `2048` cap needle |

### Terminal-Bench style operations

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `perf-regression-bisect` | AGENT | a deterministic operation-count benchmark is red on `main`; **git bisect** to the first bad commit (needs `git` on PATH) | `build_cmd` = `python3 check_answer.py` (re-derives the bad commit from history via `git show`) + `commit=`/`reason=` needles |
| `linux-cron-repair` | AGENT | repair a drifted crontab against the schedule policy and restore a missing shebang cron chokes on | `build_cmd` = own cron parser firing all jobs over a synthetic year vs the policy |
| `linux-logrotate-fix` | AGENT | bring logrotate.conf to the retention policy (weekly, `rotate 4`, compress, `create 0640 app app`, `su`, `app-reload`) | `build_cmd` = field-by-field conf validator (forbidden old values rejected) |
| `linux-service-recovery` | AGENT | fix a drifted systemd unit (interpreter path, restart policy, user, workdir) **and** the env file so the service really boots | `build_cmd` parses the unit against the spec, then **boots the service and probes `/health`** |
| `sql-migration-repair` | AGENT | production drift: 003 recorded but never executed - write an idempotent repair migration (converge, preserve rows, stay clean on fresh + re-runs) | `build_cmd` proves convergence/data/idempotence on temp copies; the drifted snapshot stays untouched |

### Multi-agent pipelines (second wave)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `multi-agent-pentest-pipeline` | (per-agent) | `recon` (PLAN, http) -> `exploiter` (numeric SQLi on `/api/users`, saves the flag) -> `reporter` (report citing endpoint + flag), against a live fixture server (port 8734) | `agent_order [recon, exploiter, reporter]` + flag needle + report needles + `build_cmd` = `verify_report.py` |
| `multi-agent-audit-patch-zipslip` | (per-agent) | `auditor` (read-only) documents the zip-slip in `extract_archive` (CWE-22); `patcher` (depends_on auditor) contains extraction to the destination | `agent_order [auditor, patcher]` + `CWE-22` needle on the audit + containment needle on the fix + `build_cmd` replays the malicious **and** clean archives |

Run notes for the batch. (1) New fixture-server ports: 8730 (jwt), 8731/8732 (ssrf public /
internal), 8734 (pentest pipeline). (2) `perf-regression-bisect` ships its six-commit
history as `history.git.bundle` (a git bundle, not an embedded `.git` - nested repositories
cannot be committed) plus an idempotent `restore-history.sh` that materialises `.git` from
it; it needs `git` on PATH. The gate restores the history itself if the agent has not, never
touches the working tree (`git show` per commit), and `file_unchanged` on `src/index.py`
catches a forgotten `git bisect reset`. (3) `red-team-deserialization` and
`crypto-token-forgery`
expect the model to run terminal commands (crafting a pickle / computing a forgery), so
their `--auto-approve` needs the default set (both ship `tool_invoked` only where the
attack genuinely requires it). (4) The two SQL/crypto gates and the incident-timeline gate
embed no plaintext answers - digests or re-derivation only - so reading the gate does not
solve the task. (5) Like the first wave, the multi-agent pair has no golden dir (runtime
gates only); every file-level scenario in this batch validates through
`validate-scenarios.sh` with golden + negative controls.

## Benchmark-style scenario batch 3 (AgentBench, Terminal-Bench and HumanEval inspired)

Twenty-eight further scenarios model the suite explicitly on three public benchmarks:
**AgentBench** (OS / DB / knowledge-graph / web-browsing / WebShop / coding / digital
games / lateral-thinking environments, via the `ab-` prefix), **HumanEval**
(function completion judged by an untouched unit-test file, via `he-`), and
**Terminal-Bench** (terminal work verified by an end-state gate, via `tb-`). Two
multi-agent scenarios compose the benchmarks with orchestration. As in wave 2, every
file-level scenario ships a golden solution and negative controls for
`validate-scenarios.sh` (26 of 28; the two multi-agent pairs are runtime-gated), gates
embed digests or re-derivation rather than plaintext answers, and scenario JSONs
changed relative to wave 2 only where the model differs.

### AgentBench-OS (host evidence and policy manifests)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `proc-autopsy` | AGENT | triage a host from cold evidence (ps snapshot + dmesg): CPU hog, zombie + its parent, the OOM victim that must NOT be in ps, worker-pool shape | `build_cmd` gate re-derives all six facts from the evidence |
| `disk-quota-cleanup` | AGENT | apply a dated retention policy (keep 10 newest logs, caches older than 7 days, reports never) as the exact operator manifest | gate recomputes the expired set from filenames + reference date |
| `symlink-farm` | AGENT | repair four drifted release slots against the authoritative matrix; stale rc pointers must vanish | needles + gate cross-checks slots and version dirs |
| `perms-alignment` | AGENT | align the permission manifest with the policy (modes, owners, setgid dir, global no-world-writable rule) | CSV gate cross-checks every policy row |

### AgentBench-DB (SQLite analytics and surgery)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `ab-db-sales-queries` | AGENT | eight business questions with revenue rules (cancelled never counts, returned does, deterministic ties) | gate recomputes all answers via SQL; money compared at 2 decimals |
| `ab-db-normalize-orders` | AGENT | normalize a denormalized one-table export in place (case-insensitive email merge, UNIQUE enforced by schema, quantities preserved) | gate re-derives everything from the immutable raw table |
| `ab-db-index-tuning` | AGENT | give three documented slow queries the right indexes - the aggregation needs a covering index | gate proves it through `EXPLAIN QUERY PLAN` against the real db |
| `ab-db-integrity-repair` | AGENT | orphan rows, duplicate/NULL emails, comma-decimal TEXT prices; merge rules + money conservation | gate asserts invariants plus revenue digest 877.0 |

### AgentBench-KG (knowledge-graph reasoning)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `ab-kg-family-kinship` | AGENT | ten kinship questions over a parent/spouse graph (siblings exclude self, one legitimate empty set) | gate recomputes the closure from the triples |
| `ab-kg-org-authority` | AGENT | rank-gated approval resolution (self-approval for routine requests, never for sensitive ones, nearest sufficient manager up the chain) | gate recomputes all ten resolutions |
| `ab-kg-consistency-audit` | AGENT | find exactly the seven violations of five documented rules (cycles, life spans, `same_as` symmetry, missing managers, parent age) | gate rejects subsets and false positives alike |

### AgentBench-Web / WebShop (live local targets)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `ab-webshop-budget-cart` | AGENT | WebShop: browse a live catalog shop and pick the unique optimal cart (one item per category, hard budget, maximal rating - the high-rated items bust the budget) | gate brute-forces the optimum; `tool_invoked http_request` |
| `ab-web-docs-extraction` | AGENT | extract facts from served HTML (unreleased builds never win "latest released", teams without leads left out) | gate re-parses the pages with the stdlib HTML parser |
| `ab-web-session-flow` | AGENT | complete a real login flow: fetch form, echo the CSRF field, POST credentials, carry the session cookie to the protected page | exact-flag needle + `tool_invoked http_request` + untouched server |

### AgentBench-Coding / digital games / lateral thinking

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `ab-code-coin-change` | AGENT | unbounded coin change with a 5s-per-case runner and a large case that rules out exponential search (greedy has no correct form - the negative controls prove it) | `build_cmd` runs all six cases |
| `ab-game-mastermind` | AGENT | a solver played against a real game driver; every guess must be consistent with all previous feedback | `needle_in_output SOLVED in [1-7]` + driver-enforced consistency |
| `ab-game-minimax-tictactoe` | AGENT | unbeatable move selection with a depth-aware rule (fastest win, slowest loss) so each position has exactly one answer | six unique-optimal positions via untouched tests |
| `ab-think-classic-puzzles` | AGENT | river crossing (7 optimal crossings) and bridge-and-torch (exactly 17 minutes), simulated step by step | gate simulates both plans |

### HumanEval-style completion (unit tests are the judge)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `he-textutils-stubs` | AGENT | three contract-bearing stubs (one-level flatten, run-collapsing slugify, email masking) | untouched `unittest` file; `NotImplementedError` gone |
| `he-rolling-max` | AGENT | rolling window maximum with the canonical edge cases (k=1 identity, empty when k>len, `ValueError` for k<=0) | untouched tests incl. the raise contract |
| `he-parse-parens` | AGENT | the canonical per-group nesting-depth parser (adjacent pairs are ONE group) | untouched tests |
| `he-pure-total-cost` | AGENT | correct AND pure - two tests assert the input is never mutated across calls | untouched tests incl. purity assertions |

### Terminal-Bench-style operations (wave 3)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `tb-config-drift-triage` | AGENT | staging leaked an incident hot-patch; invariant = prod + exactly the documented overrides | gate recomputes prod+overrides and diffs key-by-key |
| `tb-archive-rescue` | AGENT | peel a triple-nested rescue payload (base64 -> ZIP -> tar.gz) and restore byte-exactly | sha256 gate rejects wrong layers and drifted copies |
| `tb-log-reduction` | AGENT | reduce a 4k-line log to per-minute ERROR counts (only minutes with errors, sorted keys) | gate recomputes every count from the log |
| `tb-port-wiring` | AGENT | repair the drifted run.env against the documented topology and prove the chain end-to-end | gate boots all three services and probes `stored: 21` |

### Multi-agent x benchmarks (runtime gates, no golden - as in waves 1-2)

| id | mode | what it exercises | key assertion |
|---|---|---|---|
| `multi-agent-he-review-fix` | MULTI-AGENT | reviewer (PLAN) runs the red suite and documents each failing test; fixer implements the stubs to green | `agent_order` + suite green + tests untouched + report names the failing tests |
| `multi-agent-ab-webshop` | MULTI-AGENT | planner (PLAN) fetches the live catalog and computes the unique optimal cart; buyer materializes it | `agent_order` + gate confirms the optimum + plan cites the 59.50 total |

## Browser games and complex single-file web apps (HTML/CSS/JS generation, wave 4)

Twenty from-scratch generation scenarios (10 `browser-game`, 10 `browser-app`) that each produce ONE
self-contained HTML file (inline CSS/JS, vanilla, no CDN/backend) from an empty fixture. Same shape as
`games-chip8-emulator` / `single-file-app-circuitsmith`: `tool_order` gate on `advance_code_editing`,
5 HARD needles on the entry file (canvas/DOM APIs plus feature-specific identifiers - rotation,
localStorage, sim loops, real math), a runtime `smoke` check on the entry page, and a heavy SOFT judge
listing every feature that must actually work (no fake states). They are the heaviest single-file
generation workloads in the suite - run them on the strongest model under test and expect
`no_context_overflow` to fail weak models that truncate the file.

### Browser games (`webgame-*`)

| id | entry | what it exercises | key assertions |
|---|---|---|---|
| `webgame-tetris` | `tetris_model_01.html` | full Tetris: SRS rotation + wall kicks, hold, next queue, ghost, lock delay, scoring/levels, localStorage high score | canvas + rAF + keydown + tetromino defs + kick/persistence |
| `webgame-breakout` | `breakout_model_01.html` | Breakout: paddle angle control, multi-hit/unbreakable bricks, working power-ups (multi-ball/laser/slow), particles, 4+ levels | canvas + rAF + power-ups + input + lives/score |
| `webgame-minesweeper` | `minesweeper_model_01.html` | Minesweeper: safe first click, flood-fill reveal, flags, chording, timer, best times | reveal/flood + contextmenu/flags + localStorage + presets + timer |
| `webgame-2048` | `game_2048_model_01.html` | 2048: one-merge-per-tile rule, animated transitions, swipe input, undo, win + continue | localStorage + undo + merge + touch + 2048/transition |
| `webgame-sudoku` | `sudoku_model_01.html` | Sudoku: unique-solution generator with backtracking solver, pencil marks, hints, best times | solver + pencil + localStorage + hint + uniqueness |
| `webgame-wordle` | `wordle_model_01.html` | Wordle: duplicate-letter feedback rules, on-screen keyboard state, hard mode, streak stats, emoji share | localStorage + keyboard + color rules + hard mode + share |
| `webgame-platformer` | `platformer_model_01.html` | tile-map platformer: coyote time/jump buffering, enemies, coins, checkpoints, camera clamping, 3 levels | physics + tilemap + enemy + coin + camera/checkpoint |
| `webgame-space-shooter` | `space_shooter_model_01.html` | vertical shooter: 4+ enemy patterns, scripted waves, power-ups, multi-phase boss, particles/shake | rAF + waves + boss + power-ups + particles/persistence |
| `webgame-chess` | `chess_model_01.html` | chess: legal move gen (castling/en passant/promotion), mate/stalemate, SAN history, minimax AI | castling/EP + mate detection + search + SAN + persistence |
| `webgame-tower-defense` | `tower_defense_model_01.html` | tower defense: pathing enemies, 3+ tower types, upgrades, economy, range preview, speed control | towers + waves + upgrades + range/splash + lives/persistence |

### Complex web apps (`webapp-*`)

| id | entry | what it exercises | key assertions |
|---|---|---|---|
| `webapp-markdown-studio` | `markdown_studio_model_01.html` | hand-written Markdown parser (tables, fenced code, nesting), live preview, toolbar, scroll sync, export | tables + fenced code + localStorage + scroll sync + export |
| `webapp-flowdraw` | `flowdraw_model_01.html` | SVG flowchart editor: port-to-port bezier edges, drag/snap, pan/zoom, undo/redo, JSON + SVG export | svg + bezier/paths + undo/redo + zoom/pan + persistence |
| `webapp-code-playground` | `code_playground_model_01.html` | HTML/CSS/JS sandbox: srcdoc preview, real console capture via postMessage, error overlay, templates | iframe/srcdoc + postMessage + localStorage + gutter + templates |
| `webapp-vfs-file-manager` | `vfs_file_manager_model_01.html` | localStorage virtual filesystem: tree/breadcrumbs, move/copy, OS drag-drop upload, text editor, search, quota | localStorage + dataTransfer + tree + rename/move + search/quota |
| `webapp-calendar-planner` | `calendar_planner_model_01.html` | month/week/agenda views, drag-to-create events, recurrence expansion, conflict detection, search | recurrence + localStorage + views + overlap + navigation |
| `webapp-whiteboard` | `whiteboard_model_01.html` | drawing board: smoothed strokes, shape tools, layers with opacity, 50-op undo stack, PNG export | undo/redo + layers + shapes + export + zoom/pan |
| `webapp-image-editor` | `image_editor_model_01.html` | real ImageData math: adjustments, filters, crop/rotate/flip, histogram, undo history, PNG/JPEG export | ImageData + adjustments + transforms + histogram + undo/export |
| `webapp-regex-lab` | `regex_lab_model_01.html` | regex lab: live match highlighting, capture-group table, replace preview, pattern library, no UI freeze | match/exec + groups/$1 + flags + localStorage + library |
| `webapp-zettel-notes` | `zettel_notes_model_01.html` | Zettelkasten: [[wiki-links]], auto backlinks, broken-link handling, search, force-directed graph | [[ ]] + backlinks + localStorage + force graph + search/tags |
| `webapp-habit-tracker` | `habit_tracker_model_01.html` | habits: weekly schedule check-off, year heatmap, schedule-aware streaks across boundaries, stats | streaks + heatmap + localStorage + habits + goals |

## Code editing across languages, bug fixing and refactoring (wave 5)

Twenty EDIT-IN-PLACE scenarios in three families. Unlike wave 4's empty fixtures, every fixture here
ships real pre-existing code (60-140 lines per file, commented, plausible apart from the described
gap) and the model edits it: `pg-*` adds a language-idiomatic feature, `sw-*` fixes a reproduced bug
against a hidden-test gate, `rf-*` performs a behavior-preserving migration. Every scenario was
verified against a golden reference solution before shipping: normal needles match the reference (and
miss the original wherever the construct is new), `absent` needles pin the legacy construct that must
disappear, and the `build_cmd` gates were proven to flip red-to-green (`sw-*`, the SWE-bench
FAIL_TO_PASS property) or stay green across the refactor (`rf-*`).

### Polyglot feature edits (`pg-*`)

Aider-Polyglot-style: one focused, language-idiomatic change per language, no runtime gate (the
toolchains are not part of the harness image), structural needles only.

| id | entry | what it exercises | key assertions |
|---|---|---|---|
| `pg-go-error-wrapping` | `config/config.go` | sentinel errors + `%w` wrapping + `errors.Is` helper | `errors.New` + `: %w` + `errors.Is`; old `Errorf("%s"` gone |
| `pg-go-context-timeout` | `fetcher/fetcher.go` | `FetchCtx` with `context.WithTimeout`, request context, typed deadline error | `WithTimeout` + `NewRequestWithContext` + `defer cancel`; bare `http.Get(` gone |
| `pg-rust-result-errors` | `src/lib.rs` | panic-free parser: `ParseError` enum, `Result` returns, `map_err` | `enum ParseError` + `Result<` + `map_err`; `.unwrap()` gone |
| `pg-rust-trait-display` | `src/main.rs` | `impl Display` + `impl FromStr` + `?` in the loader | `impl .*Display` + `impl .*FromStr` + `write!`; `.expect(` gone |
| `pg-cpp-operator-overload` | `include/vector2.hpp` | operators `+ - * == <<`, const-correctness, `dot`/`length` | `operator+` + `operator==` + `operator<<` + const; `memcmp` gone |
| `pg-ts-generics` | `src/client.ts` | typed API client: interfaces, generic `request<T>`, no `any` | `interface [A-Z]` + `<T` + `Promise<`; `: any` gone |
| `pg-java-optional` | `src/com/example/inventory/InventoryService.java` | `Optional` returns + stream pipelines replacing null checks | `Optional.` + `stream()` + `ofNullable` + `orElse`; `== null` gone |
| `pg-ruby-kwarg-enum` | `lib/order.rb` | keyword args, frozen status tuple, `pending?/paid?/shipped?` predicates | kwargs `def initialize(...:)` + `to_sym` + `freeze` + predicates |

### Mini SWE-bench bug fixes (`sw-*`)

Each fixture ships a real bug plus a hidden gate (`checks.py`/`checks.js`) that fails on the shipped
code and passes on the golden fix - the FAIL_TO_PASS property. Prompts are written like GitHub
issues: symptom, reproduction, expected behaviour.

| id | entry | what it exercises | key assertions |
|---|---|---|---|
| `sw-parser-off-by-one` | `csvlite.py` | trailing empty field dropped by the guarded flush | docstring examples + unconditional flush; `if field:` guard gone |
| `sw-race-cache` | `src/cache.js` | concurrent-miss coalescing via a pending map | `pending.get/set` + `return await load` + `.finally(`; bare `cache.has(` gone |
| `sw-canvas-leak` | `game.js` | listener/rAF teardown across start/stop cycles | `removeEventListener` + `cancelAnimationFrame` + bound handler; inline arrow registration gone |
| `sw-regex-validation` | `validators.js` | anchored email + calendar-aware date regexes | anchors + day/month classes; unanchored pattern gone |
| `sw-sort-comparator` | `sorttable.js` | numeric comparators + stable multi-key ordering | numeric diff shape + direction control; boolean comparator gone |
| `sw-dst-arithmetic` | `scheduler.py` | wall-clock-preserving shifts across CET/CEST | `timedelta(days=days)` + zone re-attach; `timezone(offset)` stamp gone |
| `sw-money-rounding` | `cart.js` | integer-cent money maths end to end | `Cents(` accessors + `formatCents` + single conversion; `parseFloat(` and float line sums gone |

### Refactors and migrations (`rf-*`)

Behavior-preserving structural migrations. Gated ones must stay green on the legacy code AND on the
golden refactor (no FAIL_TO_PASS here - the point is that nothing breaks).

| id | entry | what it exercises | key assertions |
|---|---|---|---|
| `rf-callbacks-to-async` | `src/downloader.js` | callback pyramid -> async/await with try/catch | `async function` + `await ` + `try {`; `, callback)` and `callback(err` gone |
| `rf-js-to-ts` | `src/store.ts` | typed twin of `src/store.js` (left in place as reference) | `interface [A-Z]` + `: string` + `: number` + `export`; `: any` gone |
| `rf-react-class-to-hooks` | `app.jsx` | class component -> function + hooks, dependency arrays, cleanup | `useState` + `useEffect`; `extends Component` gone |
| `rf-css-design-tokens` | `styles.css` | repeated literals -> `:root` custom properties + `var(--...)` | `:root` + `--token:` + `var(--`; the most-repeated hex literal gone |
| `rf-python-procedural-oop` | `pipeline.py` | module-level globals -> `Pipeline` class with typed API | `class Pipeline` + `__init__` + `self.` + `@property`; `^UPPER =` globals gone |

## Where scenarios come from

Two kinds live side by side under `test_data/e2e/`:

- **Hand-written** scenarios (most of the regression suite) - edit the JSON directly.
- **Catalog-generated** scenarios - authored once as a case in
  `test_data/e2e_catalog/<category>/<name>/<id>.case.json` (+ `<id>.prompt.md`) and emitted by
  `tools/e2e/gen-catalog.ts` into `<id>.json`, `prompts/<id>.md` and a fixture stub.

For a generated scenario, **edit the case, not the emitted file**: a hand edit is reverted the next
time anyone regenerates, and `npm run gen-catalog:check` (a CI job) fails on the drift meanwhile.

```bash
cd tools/e2e && npm ci
npm run gen-catalog -- --all      # or: npm run gen-catalog -- website-web-aurora
```

The same case also feeds the benchmark review task on the `benchmark` branch, which is why the
prompt keeps a `{{MODEL_ID}}` placeholder; the e2e copy resolves it to the fixed token `model`
because the harness cannot substitute it.

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
    "preserved_except": [                                                // HARD: only the allowed part may change
      { "path": "src/Main.kt", "region": { "start": "^\\s*fun f\\(" } },  //   lines outside the region unchanged
      { "path": "package.json", "json_paths": ["scripts.build"] }        //   JSON equal once scripts.build is removed
    ],
    "build_cmd": "kotlinc src -include-runtime -d build/out.jar && java -jar build/out.jar", // HARD: exit 0 (OPTIONAL)
    "no_context_overflow": true,                                         // HARD
    "enforce_max_iterations": true,                                      // HARD: max_iterations is a real cap
    "self_verified": true,                                               // HARD: Refio's loop verifier ran and PASSED
    "forbidden_markers": ["LOOP_ABORTED", "NOOP_WRITE_STALL"],           // HARD: even on a run that delivered
    "tool_budget": { "read_file": 8 },                                   // HARD: ceiling per tool
    "no_immediate_repeat": true                                          // HARD: no identical call twice in a row
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
touch it (guards "no change needed" restraint and "don't edit the test" scenarios). List every protected file
individually (tests, the project manifest such as `package.json`, runner scripts): there are no globs.

**`preserved_except`** (array) asserts that a file the agent had to edit changed **only** where it was allowed to.
A needle on the fix alone passes a file that was cut short after the fix or had a neighbouring function
rewritten; this does not. Each entry is one of:

- `{ path, region: { start, end? } }` - `start`/`end` are **JavaScript** regexes (not grep ERE) matched per line
  against the **original fixture**: the region is the single line matching `start` (it must match exactly one
  line, otherwise the scenario itself is reported as misconfigured) through the first following line matching
  `end` (inclusive; without `end`, just that line). Every original line before and after the region must still
  be there, in order, around whatever now stands in its place.
- `{ path, json_paths: ["scripts.build"] }` - both files must parse as JSON and be structurally equal once the
  listed dotted paths are removed from both. Key order and formatting are ignored; array order is not.

CRLF and LF compare equal and trailing newlines at end of file are ignored: a Windows checkout turns the fixture
into CRLF while an agent's write tool may emit LF, which is the environment, not a content change. The check is
`tools/e2e/lib/preserve-check.mjs`, run from the harness directory with `node`. `e2e-run.sh`, `e2e-run.ps1` and
`validate-scenarios.sh` all call the same script directly rather than through `build_cmd`, so it behaves the same
under bash and under `cmd /c`. It only reads the pristine fixture and the project; nothing the agent writes can
change the check. No `node` on PATH is a HARD fail with that reason, never a silent pass.

**Loop-quality gates.** Everything above measures the final state of the project; these five measure
how the run got there, which is the one thing the rest is blind to. A run that took forty wasted turns,
never checked its own work, kept retrying a call that could not work, or fired a guardrail and then
recovered leaves exactly the same files behind as a run that did the job well.

- **`enforce_max_iterations`** turns the scenario's own `max_iterations` into a HARD cap. Every scenario
  has carried that budget since the harness was written and nothing read it, so it was documentation.
  Iterations are counted as assistant messages in the conversation: `metrics.toolCallCount` counts
  SUBTASK rows, which is a different quantity under a name that reads like this one.
- **`self_verified`** requires `metrics.verification.ran` with result `PASSED`: **Refio's loop verifier**
  (the post-write verification step of the turn loop) ran the build or the tests during the turn and they
  passed. It describes the Refio loop, not a choice the model made: it does not show that the model decided to
  run the tests itself, and it is not a portable measure for agents that have no such verifier. The harness
  runs `build_cmd` afterwards either way, so without this gate a loop that writes code blind and one that
  checks and repairs what broke score identically. Results recorded earlier carry the same semantics; only the
  wording of the failure reason changed ("the Refio loop verifier never ran"). Newer run documents may add
  optional fields to `metrics.verification` (`exitCode`, `timedOut`, `baseline`, `attributionUncertain`,
  `notRunReason`; absent when null). They do not change the pass/fail rule; the harness appends them to the
  failure reason, so a run whose check was already red before the change (`baseline FAILED`,
  `attributionUncertain`) still fails `self_verified` but is reported as "attribution uncertain" rather
  than as a regression the agent caused. Whether the **model** chose to
  run the tests is reported separately, as `modelRanBuildCmd` in `results.jsonl` (see Benchmark statistics);
  it is not an assertion.
- **`forbidden_markers`** fails a run whose `metrics.failureMarker` is one of the listed values, **even
  when the run delivered**. The marker used to be read only for an already-failing verdict, so a
  guardrail could fire, the agent could recover, and the incident vanished behind a green PASS.
- **`tool_budget`** (`{tool: max}`) caps how often a tool may be called. `tool_order` is a subsequence
  check and therefore cannot see an agent that called the same tool twelve times.
- **`no_immediate_repeat`** fails a run that made the same call with the same arguments twice in a row,
  read from `conversation[].toolCallDetails`.

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

**`until`** (shell command, optional) runs the scenario as a long task (CLI `--until <cmd>`): rounds of AGENT turns until the command exits 0 in the project after a round. **`max_rounds`** (integer, optional) passes `--max-rounds`. Cap the time with the scenario's own `config`, e.g. `"long_task.max_minutes=30"`; an operator `--config` still overrides it, e.g. `long_task.max_minutes=180` for the three-hour run. `until` cannot be combined with `multi_agent`. Example: `long-12-stubs` (twelve helper stubs, `node run-tests.js` after every round).

**`score`** (`{ cmd, regex }`, optional) says how far a run got when it can end part-way: after the run, also a failed one, the harness runs `cmd` in the project and takes the first capture group of `regex` (POSIX ERE), e.g. `9/12` from `SUMMARY passed 9/12`. It is printed as `score=...` in the result line and written to `results.jsonl` as `score`, next to `rounds` (the long task's rounds, null for a single turn). It never changes the verdict.

**`smoke`** (`{ entry, no_js_errors?, dom_present[], interactions[] }`, optional) runs a headless-Chromium
runtime check on a generated browser artifact after the turn (HARD, exit-gated like `build_cmd`).
`entry` is the HTML file (relative to the project); `no_js_errors` fails on any page/console error;
`dom_present[]` are selectors that must exist; each `interactions[]` step is `{ press | click,
expect_text_change? , expect_contains? }` - it performs the action then asserts a selector's text
changed / contains text (proves the page is actually interactive, not dead markup). Implemented by
`tools/e2e/browser-smoke.mjs`; needs `playwright` installed (see above).

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
| **HARD** | `preserved_except[]` (`region` or `json_paths`) | fail - proves an edited file changed only where allowed (not truncated, no neighbour rewritten, no JSON field lost) |
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
| `modelRanBuildCmd` | how many terminal commands (`run_terminal_command` / `run_process_background`) the **model** issued whose `command` contains the scenario's `build_cmd` verbatim, read from `conversation[].toolCallDetails[]`: the model's own choice to run the tests, as opposed to `self_verified` (Refio's loop verifier). `null` when the scenario has no `build_cmd` or the run document has no tool arguments. Another spelling of the same test run (`npm test` for `node --test`) is not counted. Absent in records written before it existed: read that as unknown, not as 0 |
| `batch` | the invocation's batch id, the key into `manifest.jsonl` (absent in older records) |

**Batch manifest.** Each harness invocation with `E2E_OUT_DIR` set also appends one line to
`<E2E_OUT_DIR>/manifest.jsonl` (written by `tools/e2e/lib/batch-manifest.mjs`, needs `node`): `batch_id`, time,
git commit and dirty flag, the model, a content hash of every scenario used (scenario JSON + prompt + fixture,
line endings normalized) with its scenario-level `config` and `build_cmd`, the requested Ollama context size,
endpoint, cost cap, auto-approve regex and every `--config` override passed to the runs, what the Ollama server
reports about the model (server version, digest, quantization, template and its hash, parameters, and the
`/api/ps` size, `size_vram` and `context_length` when it is loaded), host OS/CPU/RAM/GPU (`nvidia-smi`), and the
CLI's `--print-config` output for the same overrides. Secrets (keys, tokens, passwords, `user:pass@` in URLs)
are masked. Every probe is best effort: an unreachable server or a missing CLI or tool is recorded as `null`
plus a reason and never fails the run. The host section describes the machine running the harness, which is
not the inference machine when `--ollama-host` points elsewhere. `run.json` is unchanged. The manifest is a
separate append-only file because `gate.sh` and model comparisons reuse one `E2E_OUT_DIR` for several
invocations.

These fields are **additive**: the Kotlin gate (`cli --gate`, `GateRunRecord` via Gson) ignores unknown
fields, so enrichment never breaks it. `tools/e2e/e2e-stats.sh <results-dir | results.jsonl> …`
aggregates one or more result sets into a Markdown report - a per-model leaderboard (pass-rate, avg
iterations/tokens/**tokens per second**/cost/duration, failure modes), a scenario x model pass-rate
matrix, a scenario x model **avg-seconds-per-run** matrix (processing time per case), a tool-use
histogram, and API-error counts. It is read-only (no LLM); `e2e-stats.sh --self-test` checks the
aggregation offline. The rendered report for the tracked model runs is committed to
[`RESULTS.md`](RESULTS.md).
