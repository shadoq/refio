#!/usr/bin/env bash
#
# e2e-run.sh — behavioural regression harness for Refio's coding agents (docs/0061).
#
# Runs each scenario through the EXISTING headless CLI (no Gradle module, not part of
# `./gradlew test`) into a throwaway --project, then asserts on the produced run.json.
#
# Assertion tiers (docs/0061 review note — keep hard signals hard, soft signals soft):
#   HARD (fail the run):  needle_in_file present · build_cmd exit==0 · no_context_overflow
#   SOFT (warn only):     tool_order (subsequence) · judge   — these drift on weak/local models
#                         and would otherwise produce flaky "fix the test" pressure (Rule 9).
#
# Scenarios are JSON (not the doc's YAML): parsed by `jq` here and by native ConvertFrom-Json in
# the .ps1 sibling — one format, zero extra YAML dependency (yq/PowerShell-Yaml not assumed).
#
# CONSENT: a real run spends tokens / local GPU time and WRITES into a temp --project. Per
# CLAUDE.md's headless rule, a human approves the concrete command before it runs. `--self-test`
# is the exception — it exercises only the assertion logic against bundled sample run.json files
# and makes no LLM call.
#
# Usage:
#   e2e-run.sh --self-test
#   e2e-run.sh --list                         # show selectable scenarios (id + mode + file), then exit
#   e2e-run.sh [opts] --all                   # run every scenario under test_data/e2e/
#   e2e-run.sh [opts] <id|scenario.json> ...  # run selected scenarios (by id OR by file path)
#     opts: [--cli <path>] [--max-cost <usd>] [--model <provider/model>] [--ollama-host <h>]
#           [--ollama-ctx <n>] [--config k=v]... [--auto-approve <regex>] [--no-auto-approve] [--keep]
#
# --auto-approve <regex> overrides which headless command-tool calls are approved (default: common
#   build/test/inspect commands); --no-auto-approve restores raw "reject every ASK command" (a model
#   that tries to compile/test then fails the turn). The write/edit tools are not ASK in AGENT mode,
#   so they are unaffected either way.
#
# --ollama-host points every scenario at a different Ollama endpoint for this run (sugar for
#   --config providers.ollama.ollama_endpoint=...). Accepts a host ("box"), host:port
#   ("box:11434"), or a full URL ("http://box:11434"); bare host/port becomes http://host:11434.
# --ollama-ctx overrides the configured Ollama context size for this run (sugar for
#   --config providers.ollama.ollama_context_size=<n>).
#
# Scenario selection: a positional arg is resolved as (1) an existing file path, else (2)
# test_data/e2e/<arg>.json, else (3) any scenario whose `.id` equals <arg>. `--all` runs them all.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLI_DEFAULT="$REPO_ROOT/cli/build/install/cli/bin/cli"
# Scenarios live as top-level *.json here; examples/ (sample run.json + the self-test scenario) is a
# subdir and is therefore excluded from `*.json` discovery.
E2E_DIR="$REPO_ROOT/test_data/e2e"

CLI="$CLI_DEFAULT"
MAX_COST="0.50"
MODEL=""
OLLAMA_HOST=""
OLLAMA_CTX=""
KEEP=0
SELF_TEST=0
LIST=0
ALL=0
SCENARIOS=()
CONFIG_OVERRIDES=()
# Headless auto-approval for verification commands. Without it, run_terminal_command (a
# PermissionLevel.ASK tool) is rejected in headless (no human, no approver) → the turn ends FAILED
# even though the edit already landed — a spurious failure for any model that tries to compile/test
# its own work. This default approves common build/test/inspect commands (the cooperative-user
# simulation a real IDE provides); it gates ONLY ASK command-tools (write/edit tools are not ASK in
# AGENT mode, so they are unaffected), the project is a throwaway temp dir, and CommandDenylist still
# guards destructive commands AFTER approval. Override with --auto-approve <regex> or disable with
# --no-auto-approve.
AUTO_APPROVE='\b(kotlinc|gradlew|gradle|javac|java|python3?|pip3?|node|npm|npx|pnpm|yarn|pytest|mvn|cargo|go|make|cmake|ls|cat|pwd|echo|head|tail|sed|awk|grep|rg|find|wc|diff|test|true|cd|sh|bash|env|export)\b'

die() { echo "ERROR: $*" >&2; exit 2; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH (needed by this harness)"; }

# --- scenario discovery & selection -----------------------------------------
# Top-level *.json under E2E_DIR are scenarios; examples/ is a subdir (samples), so it is skipped.
discover_scenarios() {
    local f
    for f in "$E2E_DIR"/*.json; do [[ -e "$f" ]] && echo "$f"; done
}

list_scenarios() {
    echo "Selectable e2e scenarios in ${E2E_DIR#"$REPO_ROOT"/}:" >&2
    local f id mode
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        id="$(jq -r '.id // "?"' "$f" 2>/dev/null)"
        mode="$(jq -r '.mode // "AGENT"' "$f" 2>/dev/null)"
        printf '  %-24s mode=%-6s %s\n' "$id" "$mode" "${f#"$REPO_ROOT"/}" >&2
    done < <(discover_scenarios)
}

# Resolve a positional arg to a scenario path: existing file → that file; else <E2E_DIR>/<arg>.json;
# else the scenario whose `.id` equals <arg>. Dies if nothing matches.
resolve_scenario() {
    local arg="$1" f
    [[ -f "$arg" ]] && { echo "$arg"; return; }
    [[ -f "$E2E_DIR/$arg.json" ]] && { echo "$E2E_DIR/$arg.json"; return; }
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        [[ "$(jq -r '.id // empty' "$f" 2>/dev/null)" == "$arg" ]] && { echo "$f"; return; }
    done < <(discover_scenarios)
    die "scenario not found by path or id: '$arg' (try --list)"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --cli)       CLI="$2"; shift 2 ;;
        --max-cost)  MAX_COST="$2"; shift 2 ;;
        --model)     MODEL="$2"; shift 2 ;;
        --ollama-host) OLLAMA_HOST="$2"; shift 2 ;;
        --ollama-ctx)  OLLAMA_CTX="$2"; shift 2 ;;
        --config)    CONFIG_OVERRIDES+=("$2"); shift 2 ;;
        --auto-approve)    AUTO_APPROVE="$2"; shift 2 ;;
        --no-auto-approve) AUTO_APPROVE=""; shift ;;
        --keep)      KEEP=1; shift ;;
        --self-test) SELF_TEST=1; shift ;;
        --list)      LIST=1; shift ;;
        --all)       ALL=1; shift ;;
        -h|--help)   sed -n '2,34p' "$0"; exit 0 ;;
        -*)          die "unknown flag: $1" ;;
        *)           SCENARIOS+=("$1"); shift ;;
    esac
done

# --ollama-host / --ollama-ctx are sugar over the validated config overrides. They are emitted BEFORE
# any explicit --config (see cli_args below), so a raw --config providers.ollama.* stays the ultimate
# escape hatch and wins for the same key.
OLLAMA_SUGAR=()
if [[ -n "$OLLAMA_HOST" ]]; then
    case "$OLLAMA_HOST" in
        http://*|https://*) endpoint="$OLLAMA_HOST" ;;
        *:*)                endpoint="http://$OLLAMA_HOST" ;;        # host:port given
        *)                  endpoint="http://$OLLAMA_HOST:11434" ;;  # bare host → default port
    esac
    OLLAMA_SUGAR+=("providers.ollama.ollama_endpoint=$endpoint")
fi
if [[ -n "$OLLAMA_CTX" ]]; then
    OLLAMA_SUGAR+=("providers.ollama.ollama_context_size=$OLLAMA_CTX")
fi

require_cmd jq

# ---------------------------------------------------------------------------
# Assertion engine — operates purely on a produced run.json + project dir.
# Echoes a one-line verdict; returns 0 (all HARD passed) or 1 (a HARD failed).
# SOFT failures only print a WARN and never change the return code.
# ---------------------------------------------------------------------------
assert_run() {
    local scenario="$1" run_json="$2" project_dir="$3" build_exit="$4" smoke_exit="${5:-0}"
    local hard_fail=0 reasons=()

    # HARD 0 — the agent run itself must have SUCCEEDED. The headless run.json carries the final
    # TaskStatus name (SessionDebugExporter): SUCCESS = delivered; FAILED/INCOMPLETE/CANCELED = the
    # agent errored, gave up, or was aborted. Without this gate a run that failed but still wrote a
    # run.json passes whenever the FIXTURE already satisfies the deterministic checks below (e.g. a
    # no-change scenario where file_unchanged holds regardless) — hiding an agent failure as PASS and
    # making the harness unsafe as a regression gate (docs/0061). So a non-SUCCESS status hard-fails.
    local run_status
    run_status="$(jq -r '.session.status // "UNKNOWN"' "$run_json")"
    if [[ "$run_status" != "SUCCESS" ]]; then
        hard_fail=1; reasons+=("session.status=${run_status} (want SUCCESS)")
    fi

    # HARD 1 — needle(s) in the edited file(s). Single `needle_in_file` and/or `needles_in_file[]`,
    # ALL enforced. Each needle is {path, regex?|text?, absent?, min_count?, max_count?}:
    #   regex → grep -E (shape-agnostic, e.g. matches both `x == null` and `x != null`); text → grep -F.
    #   default: must appear (>=1 matching line). `absent:true` → must NOT appear (0). `min_count`/
    #   `max_count` bound the number of matching LINES (max_count:1 proves a duplicate was removed;
    #   min_count:2 proves a helper is called in >=2 places). Anchor on real code, never a fixture comment.
    local needles_json count n np nt nr nabs nmin nmax file c lo hi patt
    needles_json="$(jq -c '[.assert.needle_in_file // empty] + (.assert.needles_in_file // [])' "$scenario")"
    count="$(jq 'length' <<<"$needles_json")"
    for (( n=0; n<count; n++ )); do
        np="$(jq -r ".[$n].path // empty" <<<"$needles_json")"
        nt="$(jq -r ".[$n].text // empty" <<<"$needles_json")"
        nr="$(jq -r ".[$n].regex // empty" <<<"$needles_json")"
        nabs="$(jq -r ".[$n].absent // false" <<<"$needles_json")"
        nmin="$(jq -r ".[$n].min_count // empty" <<<"$needles_json")"
        nmax="$(jq -r ".[$n].max_count // empty" <<<"$needles_json")"
        [[ -n "$np" ]] || continue
        file="$project_dir/$np"
        if [[ ! -f "$file" ]]; then
            hard_fail=1; reasons+=("file ${np} not found"); continue
        fi
        if [[ -n "$nr" ]]; then
            c="$(grep -cE -- "$nr" "$file" || true)"; patt="/${nr}/"
        elif [[ -n "$nt" ]]; then
            c="$(grep -cF -- "$nt" "$file" || true)"; patt="'${nt}'"
        else
            continue
        fi
        if [[ "$nabs" == "true" ]]; then lo=0; hi=0; else lo="${nmin:-1}"; hi="${nmax:-1000000}"; fi
        if (( c < lo || c > hi )); then
            hard_fail=1; reasons+=("${patt} matched ${c} line(s) in ${np}, want [${lo}..${hi}]")
        fi
    done

    # HARD 1b — needle_in_output: regex/text against run.json `.finalOutput` (for PLAN/CHAT scenarios
    # that produce a plan/answer instead of a file edit).
    local out_regex out_text out
    out_regex="$(jq -r '.assert.needle_in_output.regex // empty' "$scenario")"
    out_text="$(jq -r '.assert.needle_in_output.text // empty' "$scenario")"
    if [[ -n "$out_regex" || -n "$out_text" ]]; then
        out="$(jq -r '.finalOutput // ""' "$run_json")"
        if [[ -n "$out_regex" ]]; then
            grep -qE -- "$out_regex" <<<"$out" || { hard_fail=1; reasons+=("output regex /${out_regex}/ not matched"); }
        else
            grep -qF -- "$out_text" <<<"$out" || { hard_fail=1; reasons+=("output needle '${out_text}' missing"); }
        fi
    fi

    # HARD 1c — file_unchanged: listed paths must be byte-identical to the original fixture (proves the
    # agent did NOT touch them — guards "no change needed" and "don't edit the test" scenarios).
    local fx_dir fu_count fidx fu
    fx_dir="$(jq -r '.fixture // empty' "$scenario")"
    if [[ -n "$fx_dir" ]]; then
        fx_dir="$(cd "$(dirname "$scenario")" && pwd)/$fx_dir"
        fu_count="$(jq -r '(.assert.file_unchanged // []) | length' "$scenario")"
        for (( fidx=0; fidx<fu_count; fidx++ )); do
            fu="$(jq -r ".assert.file_unchanged[$fidx]" "$scenario")"
            if ! cmp -s "$fx_dir/$fu" "$project_dir/$fu"; then
                hard_fail=1; reasons+=("file ${fu} changed (expected unchanged)")
            fi
        done
    fi

    # HARD 1d — tool_invoked: a named tool MUST (or, with absent:true, must NOT) have been called.
    # Each entry is {name, args_regex?, absent?}. Name presence is read from the always-present
    # conversation[].toolCalls[] (bare names). With args_regex it matches the raw arguments JSON in
    # conversation[].toolCallDetails[] (additive run.json field) — so a scenario can assert not just
    # "a subagent was used" but "the code-reviewer subagent was used". This turns the soft, ordering
    # tool_order hint into a hard "this tool had to run" gate (e.g. delegation scenarios).
    local ti_json ti_count tname trx tabs tc
    ti_json="$(jq -c '.assert.tool_invoked // []' "$scenario")"
    ti_count="$(jq 'length' <<<"$ti_json")"
    for (( n=0; n<ti_count; n++ )); do
        tname="$(jq -r ".[$n].name // empty" <<<"$ti_json")"
        trx="$(jq -r ".[$n].args_regex // empty" <<<"$ti_json")"
        tabs="$(jq -r ".[$n].absent // false" <<<"$ti_json")"
        [[ -n "$tname" ]] || continue
        if [[ -n "$trx" ]]; then
            tc="$(jq --arg n "$tname" --arg rx "$trx" \
                '[.conversation[].toolCallDetails[]? | select(.name==$n and (.arguments|test($rx)))] | length' \
                "$run_json" 2>/dev/null || echo 0)"
        else
            tc="$(jq --arg n "$tname" \
                '[.conversation[].toolCalls[]? | select(.==$n)] | length' \
                "$run_json" 2>/dev/null || echo 0)"
        fi
        if [[ "$tabs" == "true" ]]; then
            (( tc == 0 )) || { hard_fail=1; reasons+=("tool ${tname} was called ${tc}×, want absent"); }
        else
            (( tc >= 1 )) || { hard_fail=1; reasons+=("tool ${tname} not invoked${trx:+ with args /${trx}/}"); }
        fi
    done

    # HARD 1e — agent_order: for a multi-agent run, the listed agent names must appear in this relative
    # order in the real execution sequence (run.json .multiAgent.agents[], already sorted by start
    # time). A subsequence check (others may interleave) that proves depends_on was respected. HARD.
    local ao_count
    ao_count="$(jq '(.assert.agent_order // []) | length' "$scenario")"
    if (( ao_count > 0 )); then
        local -a aexp=() aact=()
        while IFS= read -r line; do [[ -n "$line" ]] && aexp+=("$line"); done \
            < <(jq -r '.assert.agent_order[]? // empty' "$scenario")
        while IFS= read -r line; do [[ -n "$line" ]] && aact+=("$line"); done \
            < <(jq -r '.multiAgent.agents[]?.agentName // empty' "$run_json" 2>/dev/null || true)
        local ai=0 at
        for at in "${aact[@]}"; do
            [[ $ai -lt ${#aexp[@]} && "$at" == "${aexp[$ai]}" ]] && ai=$((ai+1))
        done
        if (( ai < ${#aexp[@]} )); then
            hard_fail=1; reasons+=("agent_order not satisfied: expected subsequence [${aexp[*]}], saw [${aact[*]:-none}]")
        fi
    fi

    # HARD 2 — build/compile of the mutated project succeeded (proves it actually works).
    local want_build
    want_build="$(jq -r '.assert.build_cmd // empty' "$scenario")"
    if [[ -n "$want_build" ]]; then
        if [[ "$build_exit" != "0" ]]; then
            hard_fail=1; reasons+=("build_cmd exit=${build_exit}")
        fi
    fi

    # HARD 2b — browser-smoke (docs/0071 §8.5 layer 2): when `smoke` is declared, the headless-Chromium
    # check must pass (exit 0). exit 2 = could not run (no node/playwright/browser) — still a HARD fail
    # with an install hint, never a silent pass (a verifier you cannot run cannot certify success).
    if [[ "$(jq -r 'has("smoke")' "$scenario")" == "true" ]]; then
        if [[ "$smoke_exit" == "2" ]]; then
            hard_fail=1; reasons+=("browser-smoke unavailable (install: npm i -D playwright && npx playwright install chromium)")
        elif [[ "$smoke_exit" != "0" ]]; then
            hard_fail=1; reasons+=("browser-smoke failed (see smoke.log)")
        fi
    fi

    # HARD 3 — no silent context overflow (spins with docs/0057; truncation != success).
    local want_no_overflow actual_overflow
    want_no_overflow="$(jq -r '.assert.no_context_overflow // false' "$scenario")"
    actual_overflow="$(jq -r '.metrics.contextOverflow // false' "$run_json")"
    if [[ "$want_no_overflow" == "true" && "$actual_overflow" == "true" ]]; then
        hard_fail=1; reasons+=("context overflow (silent truncation)")
    fi

    # SOFT — tool_order as a SUBSEQUENCE of the actual call order (warn, never fail).
    # Portable array fill (no `mapfile` — absent on the bash 3.2 that ships with macOS).
    local -a expected=() actual=()
    while IFS= read -r line; do [[ -n "$line" ]] && expected+=("$line"); done \
        < <(jq -r '.assert.tool_order[]? // empty' "$scenario")
    while IFS= read -r line; do [[ -n "$line" ]] && actual+=("$line"); done \
        < <(jq -r '[.conversation[].toolCalls[]?] | .[]' "$run_json" 2>/dev/null || true)
    if [[ ${#expected[@]} -gt 0 ]]; then
        local i=0 t
        for t in "${actual[@]}"; do
            [[ $i -lt ${#expected[@]} && "$t" == "${expected[$i]}" ]] && i=$((i+1))
        done
        if [[ $i -lt ${#expected[@]} ]]; then
            echo "  WARN [soft] tool_order not satisfied: expected subsequence [${expected[*]}], saw [${actual[*]:-none}]" >&2
        fi
    fi

    # SOFT — judge is advisory only; this harness leaves the call to the model-judge layer.
    if [[ "$(jq -r 'has("judge")' "$scenario")" == "true" ]]; then
        echo "  NOTE [soft] judge.criteria present — run the LlmTaskVerifier judge separately; not a regression gate." >&2
    fi

    if [[ $hard_fail -eq 0 ]]; then
        echo "PASS"
        return 0
    fi
    local IFS='; '
    echo "FAIL (${reasons[*]})"
    return 1
}

run_scenario() {
    local scenario="$1"
    [[ -f "$scenario" ]] || die "scenario not found: $scenario"
    local sdir id mode prompt_file fixture build_cmd max_iter multi_agent_rel multi_agent_file=""
    sdir="$(cd "$(dirname "$scenario")" && pwd)"
    id="$(jq -r '.id' "$scenario")"
    mode="$(jq -r '.mode // "AGENT"' "$scenario")"
    max_iter="$(jq -r '.max_iterations // 20' "$scenario")"
    fixture="$sdir/$(jq -r '.fixture' "$scenario")"
    build_cmd="$(jq -r '.assert.build_cmd // empty' "$scenario")"
    # A scenario drives the turn with either a prompt_file (single agent) or a multi_agent YAML.
    multi_agent_rel="$(jq -r '.multi_agent // empty' "$scenario")"
    prompt_file="$(jq -r '.prompt_file // empty' "$scenario")"
    [[ -n "$prompt_file" ]] && prompt_file="$sdir/$prompt_file"
    if [[ -n "$multi_agent_rel" ]]; then
        multi_agent_file="$sdir/$multi_agent_rel"
        [[ -f "$multi_agent_file" ]] || die "multi_agent file not found: $multi_agent_file"
    else
        [[ -f "$prompt_file" ]] || die "prompt_file not found: $prompt_file"
    fi
    [[ -d "$fixture" ]] || die "fixture dir not found: $fixture"
    [[ -x "$CLI" ]] || die "CLI not found/executable: $CLI (build it: ./gradlew :cli:installDist)"

    local work; work="$(mktemp -d "${TMPDIR:-/tmp}/refio-e2e-${id}-XXXXXX")"
    cp -R "$fixture/." "$work/"
    local run_json="$work/run.json"

    # Optional fixture server: serve a directory from inside the temp project over loopback so the
    # agent can drive http_request/fetch_webpage against a deterministic local endpoint instead of the
    # flaky public internet. The prompt's {{FIXTURE_SERVER}} placeholder is replaced with the base URL,
    # and the loopback opt-in (UrlPolicy / security.allow_loopback) is enabled for this run only.
    local fs_dir fs_port server_pid="" effective_prompt="$prompt_file"
    fs_dir="$(jq -r '.fixture_server.dir // empty' "$scenario")"
    fs_port="$(jq -r '.fixture_server.port // empty' "$scenario")"
    if [[ -n "$fs_dir" && -n "$fs_port" ]]; then
        local py; py="$(command -v python3 || command -v python || true)"
        [[ -n "$py" ]] || die "fixture_server needs python3/python on PATH"
        # Run python directly (not in a subshell) so $! is the server's own PID and the kill below is
        # reliable; --directory takes the absolute served path so no `cd` is needed.
        "$py" -m http.server "$fs_port" --bind 127.0.0.1 --directory "$work/$fs_dir" \
            >"$work/server.log" 2>&1 &
        server_pid=$!
        local ready=0 i
        for (( i=0; i<50; i++ )); do
            (exec 3<>"/dev/tcp/127.0.0.1/$fs_port") 2>/dev/null && { exec 3>&- 3<&-; ready=1; break; }
            sleep 0.1
        done
        [[ $ready -eq 1 ]] || echo "  WARN fixture_server not ready on 127.0.0.1:$fs_port after 5s" >&2
        effective_prompt="$work/.e2e-prompt.md"
        sed "s|{{FIXTURE_SERVER}}|http://127.0.0.1:$fs_port|g" "$prompt_file" > "$effective_prompt"
    fi

    local -a cli_args=(
        --headless -p "$work" --mode "$mode"
        --output json --output-file "$run_json"
        --debug-level standard            # docs/0061: tool names live in run.json.conversation[]
        --config "agent.max_iterations=$max_iter"
        --max-cost "$MAX_COST"
    )
    # Single-agent scenarios pass --prompt-file; multi-agent ones pass --multi-agent <yaml> instead.
    if [[ -n "$multi_agent_file" ]]; then
        cli_args+=(--multi-agent "$multi_agent_file")
    else
        cli_args+=(--prompt-file "$effective_prompt")
    fi
    [[ -n "$fs_dir" && -n "$fs_port" ]] && cli_args+=(--config "security.allow_loopback=true")
    [[ -n "$MODEL" ]] && cli_args+=(--model "$MODEL")
    # Approve verification commands so a model that compiles/tests its own edit is not failed by a
    # headless rejection (see AUTO_APPROVE above). Empty (via --no-auto-approve) restores the raw
    # "reject every ASK command" behaviour.
    [[ -n "$AUTO_APPROVE" ]] && cli_args+=(--auto-approve "$AUTO_APPROVE")
    # --ollama-host/--ollama-ctx sugar first, then explicit --config (so a raw --config wins).
    local c
    if [[ ${#OLLAMA_SUGAR[@]} -gt 0 ]]; then
        for c in "${OLLAMA_SUGAR[@]}"; do cli_args+=(--config "$c"); done
    fi
    if [[ ${#CONFIG_OVERRIDES[@]} -gt 0 ]]; then
        for c in "${CONFIG_OVERRIDES[@]}"; do cli_args+=(--config "$c"); done
    fi

    echo "▶ $id (mode=$mode, max_cost=$MAX_COST) → $work" >&2
    local cli_exit=0
    "$CLI" "${cli_args[@]}" >&2 || cli_exit=$?
    # The server is only needed during the turn; stop it before build/assert (and before any early
    # return below) so no python process is left running.
    [[ -n "$server_pid" ]] && { kill "$server_pid" 2>/dev/null || true; }

    [[ -f "$run_json" ]] || { echo "| $id | FAIL (no run.json produced) | - |"; return 1; }

    # A non-zero CLI exit is a HARD failure: the headless turn aborted (e.g. cost ceiling, crash). It
    # must never be papered over by deterministic assertions that the starting fixture happens to
    # satisfy (docs/0061). The status gate in assert_run is the in-run.json equivalent; this catches
    # the process-level failures that never reach a finalized run.json status.
    if [[ "$cli_exit" != "0" ]]; then
        local st co
        st="$(jq -r '.session.status // "?"' "$run_json")"
        co="$(jq -r '.metrics.costUsd // 0' "$run_json")"
        echo "| $id | FAIL (headless CLI exit=$cli_exit) | status=$st cli_exit=$cli_exit cost=\$$co |"
        [[ $KEEP -eq 1 ]] || rm -rf "$work"
        return 1
    fi

    local build_exit=0
    if [[ -n "$build_cmd" ]]; then
        ( cd "$work" && eval "$build_cmd" ) >"$work/build.log" 2>&1 && build_exit=0 || build_exit=$?
    fi

    # Optional browser-smoke (docs/0071 §8.5): render the produced artifact in headless Chromium and
    # check it actually runs. Needs node + playwright; exit 2 (unavailable) is surfaced as a HARD fail
    # by assert_run, not papered over.
    local smoke_exit=0
    if [[ "$(jq -r 'has("smoke")' "$scenario")" == "true" ]]; then
        if command -v node >/dev/null 2>&1; then
            node "$SCRIPT_DIR/browser-smoke.mjs" "$scenario" "$work" >"$work/smoke.log" 2>&1 && smoke_exit=0 || smoke_exit=$?
        else
            echo "node not found on PATH" >"$work/smoke.log"; smoke_exit=2
        fi
    fi

    local verdict status tokens cost
    verdict="$(assert_run "$scenario" "$run_json" "$work" "$build_exit" "$smoke_exit" || true)"
    status="$(jq -r '.session.status // "?"' "$run_json")"
    cost="$(jq -r '.metrics.costUsd // 0' "$run_json")"
    echo "| $id | $verdict | status=$status build_exit=$build_exit cost=\$$cost |"

    [[ $KEEP -eq 1 ]] || rm -rf "$work"
    [[ "$verdict" == PASS* ]]
}

self_test() {
    echo "# e2e-run.sh self-test (assertion engine only — no LLM call)" >&2
    local sample="$SCRIPT_DIR/../../test_data/e2e/examples"
    local scen="$sample/self-test.scenario.json"
    [[ -f "$scen" ]] || die "missing self-test scenario: $scen"

    # A satisfied project: the needle file exists with the expected text.
    local proj; proj="$(mktemp -d "${TMPDIR:-/tmp}/refio-e2e-selftest-XXXXXX")"
    mkdir -p "$proj/src"
    printf 'fun f(x: String?) {\n    if (x != null) {\n        println(x)\n    }\n}\n' > "$proj/src/Main.kt"

    local fails=0 v
    # Case 1: passing run.json + satisfied project + build_exit 0  -> PASS
    v="$(assert_run "$scen" "$sample/sample-run.pass.json" "$proj" 0 || true)"
    echo "  case good-run      -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! expected PASS" >&2; fails=1; }

    # Case 1b: agent run FAILED -> FAIL (hard) even though the project + build satisfy every other
    # check. Proves a failed/incomplete agent run can never hide as PASS (docs/0061 status gate).
    v="$(assert_run "$scen" "$sample/sample-run.failed.json" "$proj" 0 || true)"
    echo "  case failed-status -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! expected FAIL on session.status=FAILED" >&2; fails=1; }

    # Case 2: context overflow -> FAIL (hard)
    v="$(assert_run "$scen" "$sample/sample-run.overflow.json" "$proj" 0 || true)"
    echo "  case overflow      -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! expected FAIL on overflow" >&2; fails=1; }

    # Case 3: build failed -> FAIL (hard)
    v="$(assert_run "$scen" "$sample/sample-run.pass.json" "$proj" 1 || true)"
    echo "  case build-failed  -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! expected FAIL on build_exit=1" >&2; fails=1; }

    # Case 4: needle missing -> FAIL (hard). Point at an empty project.
    local empty; empty="$(mktemp -d "${TMPDIR:-/tmp}/refio-e2e-empty-XXXXXX")"
    mkdir -p "$empty/src"; : > "$empty/src/Main.kt"
    v="$(assert_run "$scen" "$sample/sample-run.pass.json" "$empty" 0 || true)"
    echo "  case needle-missing-> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! expected FAIL on missing needle" >&2; fails=1; }

    # Case 5: SOFT — a tool_order that is NOT a subsequence emits a WARN but still PASSes
    # the HARD tiers (verdict stays PASS; the order drift is advisory, per docs/0061 review).
    local warn
    warn="$(assert_run "$scen" "$sample/sample-run.badorder.json" "$proj" 0 2>&1 1>/dev/null || true)"
    echo "  case bad-tool-order-> $(echo "$warn" | grep -c 'tool_order not satisfied') warn(s)" >&2
    echo "$warn" | grep -q 'tool_order not satisfied' || { echo "  !! expected a soft tool_order WARN" >&2; fails=1; }
    v="$(assert_run "$scen" "$sample/sample-run.badorder.json" "$proj" 0 2>/dev/null || true)"
    [[ "$v" == PASS* ]] || { echo "  !! bad tool_order must NOT fail the run (soft only)" >&2; fails=1; }

    # Case 6: regex needle is shape-agnostic — one regex accepts both `!=` and `==` null guards.
    local rscen="$proj/regex-needle.scenario.json"
    cat > "$rscen" <<'JSON'
{ "id": "regex-needle", "assert": { "needle_in_file": { "path": "src/Main.kt", "regex": "x[[:space:]]*[!=]=[[:space:]]*null" } } }
JSON
    # $proj/src/Main.kt currently holds `if (x != null)` from the setup above.
    v="$(assert_run "$rscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case regex-needle!= -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! regex needle must match 'x != null'" >&2; fails=1; }
    printf 'fun f(x: String?) {\n    if (x == null) return\n    println(x)\n}\n' > "$proj/src/Main.kt"
    v="$(assert_run "$rscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case regex-needle== -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! regex needle must ALSO match 'x == null'" >&2; fails=1; }

    # Case 7: needles_in_file[] — ALL must be present (helper defined AND used with the right arg).
    local ascen="$proj/array-needles.scenario.json"
    cat > "$ascen" <<'JSON'
{ "id": "array-needles", "assert": { "needles_in_file": [
    { "path": "src/Main.kt", "regex": "fun[[:space:]]+clamp[[:space:]]*\\(" },
    { "path": "src/Main.kt", "regex": "clamp[[:space:]]*\\([[:space:]]*score" } ] } }
JSON
    printf 'fun clamp(value: Int, lo: Int, hi: Int) = maxOf(lo, minOf(hi, value))\nfun normalize(score: Int) = clamp(score, 0, 100)\n' > "$proj/src/Main.kt"
    v="$(assert_run "$ascen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case array-needles  -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! both array needles present must PASS" >&2; fails=1; }
    printf 'fun clamp(value: Int, lo: Int, hi: Int) = maxOf(lo, minOf(hi, value))\nfun normalize(score: Int) = score\n' > "$proj/src/Main.kt"
    v="$(assert_run "$ascen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case array-missing  -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! missing one array needle must FAIL" >&2; fails=1; }

    # Case 8: scenario selection resolves a committed scenario by its id (not just by file path).
    local byid; byid="$(resolve_scenario increase-retry-count 2>/dev/null || true)"
    echo "  case resolve-by-id  -> ${byid#"$REPO_ROOT"/}" >&2
    [[ "$byid" == *"/increase-retry-count.json" ]] || { echo "  !! resolve_scenario must find a scenario by id" >&2; fails=1; }

    # Case 9: absent needle — pattern must NOT appear (negative needle, for rename/dedup).
    local nscen="$proj/absent.scenario.json"
    cat > "$nscen" <<'JSON'
{ "id":"absent", "assert": { "needle_in_file": { "path":"src/Main.kt", "regex":"legacyName", "absent":true } } }
JSON
    printf 'fun currentName() = 1\nval y = currentName()\n' > "$proj/src/Main.kt"
    v="$(assert_run "$nscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case absent-ok      -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! absent needle must PASS when pattern is gone" >&2; fails=1; }
    printf 'fun legacyName() = 1\n' > "$proj/src/Main.kt"
    v="$(assert_run "$nscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case absent-viol    -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! absent needle must FAIL when pattern still present" >&2; fails=1; }

    # Case 10: max_count — duplicate removed (pattern on <=1 line).
    local mscen="$proj/maxcount.scenario.json"
    cat > "$mscen" <<'JSON'
{ "id":"maxcount", "assert": { "needle_in_file": { "path":"src/Main.kt", "regex":"qty <= 0", "max_count":1 } } }
JSON
    printf 'fun validate(qty: Int) { if (qty <= 0) error("bad") }\n' > "$proj/src/Main.kt"
    v="$(assert_run "$mscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case maxcount-ok    -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! max_count:1 must PASS with a single occurrence" >&2; fails=1; }
    printf 'if (qty <= 0) a\nif (qty <= 0) b\n' > "$proj/src/Main.kt"
    v="$(assert_run "$mscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case maxcount-viol  -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! max_count:1 must FAIL with two occurrences (dup not removed)" >&2; fails=1; }

    # Case 11: min_count — helper called in >=2 places.
    local cscen="$proj/mincount.scenario.json"
    cat > "$cscen" <<'JSON'
{ "id":"mincount", "assert": { "needle_in_file": { "path":"src/Main.kt", "regex":"validate\\(", "min_count":2 } } }
JSON
    printf 'fun a() = validate(1)\nfun b() = validate(2)\n' > "$proj/src/Main.kt"
    v="$(assert_run "$cscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case mincount-ok    -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! min_count:2 must PASS with two call sites" >&2; fails=1; }
    printf 'fun a() = validate(1)\n' > "$proj/src/Main.kt"
    v="$(assert_run "$cscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case mincount-viol  -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! min_count:2 must FAIL with one call site" >&2; fails=1; }

    # Case 12: needle_in_output — regex against run.json .finalOutput (PLAN/CHAT scenarios).
    local oscen="$proj/output.scenario.json"
    cat > "$oscen" <<'JSON'
{ "id":"output", "assert": { "needle_in_output": { "regex":"null check" } } }
JSON
    v="$(assert_run "$oscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case output-ok      -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! needle_in_output must match finalOutput" >&2; fails=1; }
    cat > "$oscen" <<'JSON'
{ "id":"output", "assert": { "needle_in_output": { "regex":"nonexistent-phrase-xyz" } } }
JSON
    v="$(assert_run "$oscen" "$sample/sample-run.pass.json" "$proj" 0 2>/dev/null || true)"
    echo "  case output-viol    -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! needle_in_output must FAIL on a missing phrase" >&2; fails=1; }

    # Case 13: file_unchanged — file must equal the original fixture byte-for-byte.
    local fxroot="$proj/fx"; mkdir -p "$fxroot/src"
    printf 'object Frozen { const val V = 1 }\n' > "$fxroot/src/Frozen.kt"
    local uscen="$proj/unchanged.scenario.json"
    cat > "$uscen" <<'JSON'
{ "id":"unchanged", "fixture":"fx", "assert": { "file_unchanged": ["src/Frozen.kt"] } }
JSON
    local uproj="$proj/up"; mkdir -p "$uproj/src"; cp "$fxroot/src/Frozen.kt" "$uproj/src/Frozen.kt"
    v="$(assert_run "$uscen" "$sample/sample-run.pass.json" "$uproj" 0 2>/dev/null || true)"
    echo "  case unchanged-ok   -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! file_unchanged must PASS when identical" >&2; fails=1; }
    printf 'object Frozen { const val V = 2 }\n' > "$uproj/src/Frozen.kt"
    v="$(assert_run "$uscen" "$sample/sample-run.pass.json" "$uproj" 0 2>/dev/null || true)"
    echo "  case unchanged-viol -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! file_unchanged must FAIL when modified" >&2; fails=1; }

    # Case 14: tool_invoked — a named tool must (name) / must with given args (args_regex) / must not
    # (absent) have been called. Uses the subagent sample whose conversation carries toolCallDetails.
    local subrun="$sample/sample-run.subagent.json"
    local tscen="$proj/tool-invoked.scenario.json"
    cat > "$tscen" <<'JSON'
{ "id":"tool-invoked", "assert": { "tool_invoked": [ { "name":"invoke_subagent" } ] } }
JSON
    v="$(assert_run "$tscen" "$subrun" "$proj" 0 2>/dev/null || true)"
    echo "  case tool-by-name   -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! tool_invoked by name must PASS when the tool ran" >&2; fails=1; }

    cat > "$tscen" <<'JSON'
{ "id":"tool-invoked", "assert": { "tool_invoked": [ { "name":"invoke_subagent", "args_regex":"code-reviewer" } ] } }
JSON
    v="$(assert_run "$tscen" "$subrun" "$proj" 0 2>/dev/null || true)"
    echo "  case tool-args-ok   -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! tool_invoked args_regex must PASS when the arguments match" >&2; fails=1; }

    cat > "$tscen" <<'JSON'
{ "id":"tool-invoked", "assert": { "tool_invoked": [ { "name":"invoke_subagent", "args_regex":"security-engineer" } ] } }
JSON
    v="$(assert_run "$tscen" "$subrun" "$proj" 0 2>/dev/null || true)"
    echo "  case tool-args-miss -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! tool_invoked args_regex must FAIL when the wrong subagent ran" >&2; fails=1; }

    cat > "$tscen" <<'JSON'
{ "id":"tool-invoked", "assert": { "tool_invoked": [ { "name":"delegate_to_strong_model" } ] } }
JSON
    v="$(assert_run "$tscen" "$subrun" "$proj" 0 2>/dev/null || true)"
    echo "  case tool-missing   -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! tool_invoked must FAIL when a required tool never ran" >&2; fails=1; }

    cat > "$tscen" <<'JSON'
{ "id":"tool-invoked", "assert": { "tool_invoked": [ { "name":"invoke_subagent", "absent":true } ] } }
JSON
    v="$(assert_run "$tscen" "$subrun" "$proj" 0 2>/dev/null || true)"
    echo "  case tool-absent-vio-> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! tool_invoked absent must FAIL when the tool was called" >&2; fails=1; }

    # Case 15: agent_order — listed agents must appear in execution order in .multiAgent.agents[].
    local marun="$sample/sample-run.multiagent.json"
    local aoscen="$proj/agent-order.scenario.json"
    cat > "$aoscen" <<'JSON'
{ "id":"agent-order", "assert": { "agent_order": ["analyst","coder"] } }
JSON
    v="$(assert_run "$aoscen" "$marun" "$proj" 0 2>/dev/null || true)"
    echo "  case agent-order-ok -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! agent_order must PASS when the execution order matches" >&2; fails=1; }

    cat > "$aoscen" <<'JSON'
{ "id":"agent-order", "assert": { "agent_order": ["coder","analyst"] } }
JSON
    v="$(assert_run "$aoscen" "$marun" "$proj" 0 2>/dev/null || true)"
    echo "  case agent-order-rev -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! agent_order must FAIL when the order is reversed" >&2; fails=1; }

    cat > "$aoscen" <<'JSON'
{ "id":"agent-order", "assert": { "agent_order": ["analyst","tester"] } }
JSON
    v="$(assert_run "$aoscen" "$marun" "$proj" 0 2>/dev/null || true)"
    echo "  case agent-order-miss-> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! agent_order must FAIL when a listed agent never ran" >&2; fails=1; }

    # Case 16: browser-smoke HARD tier — gated on the runner's exit (5th arg), like build_cmd. 0=pass,
    # 1=smoke failed, 2=runner unavailable (still a loud HARD fail).
    local smscen="$proj/smoke.scenario.json"
    cat > "$smscen" <<'JSON'
{ "id":"smoke", "smoke": { "entry":"index.html", "dom_present":["#x"] } }
JSON
    v="$(assert_run "$smscen" "$sample/sample-run.pass.json" "$proj" 0 0 2>/dev/null || true)"
    echo "  case smoke-ok       -> $v" >&2
    [[ "$v" == PASS* ]] || { echo "  !! smoke must PASS when the runner exits 0" >&2; fails=1; }

    v="$(assert_run "$smscen" "$sample/sample-run.pass.json" "$proj" 0 1 2>/dev/null || true)"
    echo "  case smoke-fail     -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! smoke must FAIL when the runner exits 1" >&2; fails=1; }

    v="$(assert_run "$smscen" "$sample/sample-run.pass.json" "$proj" 0 2 2>/dev/null || true)"
    echo "  case smoke-unavail  -> $v" >&2
    [[ "$v" == FAIL* ]] || { echo "  !! smoke must FAIL (loud) when the runner is unavailable (exit 2)" >&2; fails=1; }

    rm -rf "$proj" "$empty"
    if [[ $fails -eq 0 ]]; then echo "self-test OK" >&2; else die "self-test FAILED"; fi
}

if [[ $SELF_TEST -eq 1 ]]; then
    self_test
    exit 0
fi

if [[ $LIST -eq 1 ]]; then
    list_scenarios
    exit 0
fi

# Build the run list: --all discovers every scenario; otherwise resolve each positional by id or path.
RESOLVED=()
if [[ $ALL -eq 1 ]]; then
    while IFS= read -r s; do [[ -n "$s" ]] && RESOLVED+=("$s"); done < <(discover_scenarios)
    [[ ${#RESOLVED[@]} -gt 0 ]] || die "no scenarios found in ${E2E_DIR#"$REPO_ROOT"/}"
else
    [[ ${#SCENARIOS[@]} -gt 0 ]] || die "no scenarios selected (try --list, --all, or pass <id|scenario.json>)"
    for s in "${SCENARIOS[@]}"; do
        sp="$(resolve_scenario "$s")" || exit $?   # resolve_scenario dies (exit 2) with a clear message on a miss
        RESOLVED+=("$sp")
    done
fi

echo "| scenario | verdict | metrics |"
echo "|---|---|---|"
overall=0
for s in "${RESOLVED[@]}"; do
    run_scenario "$s" || overall=1
done
exit $overall
