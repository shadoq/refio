#!/usr/bin/env bash
#
# validate-scenarios.sh - deterministic quality gate for e2e scenarios (no LLM call).
#
# A scenario enters the pool only when it survives three checks:
#   (a) fixture sanity     - the fixture dir, prompt file and every referenced assertion
#                            path are coherent; every *.py in the fixture byte-compiles.
#   (b) golden pass        - after overlaying the golden solution from
#                            test_data/e2e/golden/<id>/ onto a fresh fixture copy, every
#                            file-level HARD assertion holds (needles, file_unchanged,
#                            build_cmd exit 0, needle_in_output vs golden/answer.txt).
#   (c) untouched fail     - on the untouched fixture at least one HARD assertion FAILS,
#                            proving the scenario cannot pass without real agent work.
#
# Golden-solution convention: test_data/e2e/golden/<id>/ mirrors the fixture's relative
# layout with the full post-solution content of every changed/added file. For scenarios
# asserted via needle_in_output (PLAN/CHAT answers), golden/<id>/answer.txt holds a sample
# correct answer instead of file overlays.
#
# Run-time-only assertions (tool_invoked, tool_order, agent_order, session status,
# no_context_overflow) need a run.json and are out of scope here - e2e-run.sh owns them.
#
# Usage:
#   validate-scenarios.sh --list             # scenarios that have a golden dir
#   validate-scenarios.sh --all              # validate every scenario with a golden dir
#   validate-scenarios.sh <id|file> ...      # validate selected scenarios
#     opts: [--keep]                         # keep temp dirs on failure analysis
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
E2E_DIR="$REPO_ROOT/test_data/e2e"
GOLDEN_DIR="$E2E_DIR/golden"

KEEP=0
LIST=0
ALL=0
SCENARIOS=()

die() { echo "ERROR: $*" >&2; exit 2; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keep) KEEP=1; shift ;;
        --list) LIST=1; shift ;;
        --all)  ALL=1; shift ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        -*) die "unknown flag: $1" ;;
        *)  SCENARIOS+=("$1"); shift ;;
    esac
done

require_cmd jq
require_cmd python3

# Scenarios that have a golden dir (the validatable pool).
discover_validatable() {
    local f id
    for f in "$E2E_DIR"/*.json; do
        [[ -e "$f" ]] || continue
        id="$(jq -r '.id // empty' "$f" 2>/dev/null)"
        [[ -n "$id" && -d "$GOLDEN_DIR/$id" ]] && echo "$f"
    done
}

resolve_scenario() {
    local arg="$1" f
    [[ -f "$arg" ]] && { echo "$arg"; return; }
    [[ -f "$E2E_DIR/$arg.json" ]] && { echo "$E2E_DIR/$arg.json"; return; }
    for f in "$E2E_DIR"/*.json; do
        [[ -e "$f" ]] || continue
        [[ "$(jq -r '.id // empty' "$f" 2>/dev/null)" == "$arg" ]] && { echo "$f"; return; }
    done
    die "scenario not found by path or id: '$arg'"
}

if [[ $LIST -eq 1 ]]; then
    echo "Validatable scenarios (golden dir present under ${GOLDEN_DIR#"$REPO_ROOT"/}/):" >&2
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        printf '  %-32s %s\n' "$(jq -r '.id' "$f")" "${f#"$REPO_ROOT"/}" >&2
    done < <(discover_validatable)
    exit 0
fi

if [[ $ALL -eq 1 ]]; then
    while IFS= read -r f; do [[ -n "$f" ]] && SCENARIOS+=("$f"); done < <(discover_validatable)
else
    [[ ${#SCENARIOS[@]} -gt 0 ]] || die "no scenarios given (use --all, --list, or ids)"
    resolved=()
    for s in "${SCENARIOS[@]}"; do resolved+=("$(resolve_scenario "$s")"); done
    SCENARIOS=("${resolved[@]}")
fi
[[ ${#SCENARIOS[@]} -gt 0 ]] || die "no validatable scenarios found (golden/<id>/ missing?)"

# ---------------------------------------------------------------------------
# File-level HARD assertion check against a project dir (no run.json involved).
# $1 scenario json, $2 project dir, $3 fixture dir, $4 simulated output text.
# Echoes PASS or FAIL (reasons); returns 0/1 accordingly.
# ---------------------------------------------------------------------------
check_hard() {
    local scenario="$1" project_dir="$2" fixture="$3" out_text="$4"
    local hard_fail=0 reasons=()

    # Needles (needle_in_file + needles_in_file[]) - same semantics as e2e-run.sh.
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
            if [[ "$nabs" == "true" ]]; then continue; fi
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

    # needle_in_output against the simulated output text.
    local out_regex out_text_needle
    out_regex="$(jq -r '.assert.needle_in_output.regex // empty' "$scenario")"
    out_text_needle="$(jq -r '.assert.needle_in_output.text // empty' "$scenario")"
    if [[ -n "$out_regex" ]]; then
        grep -qE -- "$out_regex" <<<"$out_text" || { hard_fail=1; reasons+=("output regex /${out_regex}/ not matched"); }
    elif [[ -n "$out_text_needle" ]]; then
        grep -qF -- "$out_text_needle" <<<"$out_text" || { hard_fail=1; reasons+=("output needle '${out_text_needle}' missing"); }
    fi

    # file_unchanged vs the pristine fixture.
    local fu_count fidx fu
    fu_count="$(jq -r '(.assert.file_unchanged // []) | length' "$scenario")"
    for (( fidx=0; fidx<fu_count; fidx++ )); do
        fu="$(jq -r ".assert.file_unchanged[$fidx]" "$scenario")"
        if ! cmp -s "$fixture/$fu" "$project_dir/$fu"; then
            hard_fail=1; reasons+=("file ${fu} changed (expected unchanged)")
        fi
    done

    # build_cmd in the project dir.
    local build_cmd build_exit=0
    build_cmd="$(jq -r '.assert.build_cmd // empty' "$scenario")"
    if [[ -n "$build_cmd" ]]; then
        ( cd "$project_dir" && eval "$build_cmd" ) >"$project_dir/.validate-build.log" 2>&1 || build_exit=$?
        if [[ "$build_exit" != "0" ]]; then
            hard_fail=1; reasons+=("build_cmd exit=${build_exit}")
        fi
    fi

    if [[ $hard_fail -eq 0 ]]; then echo "PASS"; return 0; fi
    local IFS='; '
    echo "FAIL (${reasons[*]})"
    return 1
}

validate_scenario() {
    local scenario="$1"
    local sdir id fixture prompt_file golden
    sdir="$(cd "$(dirname "$scenario")" && pwd)"
    id="$(jq -r '.id' "$scenario")"
    fixture="$sdir/$(jq -r '.fixture' "$scenario")"
    prompt_file="$(jq -r '.prompt_file // empty' "$scenario")"
    golden="$GOLDEN_DIR/$id"

    # --- (a) fixture sanity ---
    [[ -d "$fixture" ]] || { echo "| $id | FAIL (a) | fixture dir missing: $fixture |"; return 1; }
    [[ -d "$golden" ]]  || { echo "| $id | FAIL (a) | golden dir missing: golden/$id |"; return 1; }
    if [[ -n "$prompt_file" && ! -f "$sdir/$prompt_file" ]]; then
        echo "| $id | FAIL (a) | prompt_file missing: $prompt_file |"; return 1
    fi
    local py bad=0
    while IFS= read -r py; do
        [[ -n "$py" ]] || continue
        python3 -m py_compile "$py" 2>/dev/null || { echo "  (a) fixture file does not compile: ${py#"$fixture"/}" >&2; bad=1; }
    done < <(find "$fixture" -name '*.py' -type f)
    find "$fixture" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
    [[ $bad -eq 0 ]] || { echo "| $id | FAIL (a) | fixture does not byte-compile |"; return 1; }

    # Golden overlay files must exist relative to the fixture layout; answer.txt is special.
    local answer=""
    [[ -f "$golden/answer.txt" ]] && answer="$(cat "$golden/answer.txt")"

    # --- (b) golden pass ---
    local work_b; work_b="$(mktemp -d "${TMPDIR:-/tmp}/refio-val-${id}-b-XXXXXX")"
    cp -R "$fixture/." "$work_b/"
    ( cd "$golden" && find . -type f ! -name 'answer.txt' | while IFS= read -r f; do
        mkdir -p "$work_b/$(dirname "$f")"
        cp "$f" "$work_b/$f"
      done )
    local v_b
    v_b="$(check_hard "$scenario" "$work_b" "$fixture" "$answer" || true)"
    if [[ "$v_b" != PASS* ]]; then
        echo "| $id | FAIL (b) | golden solution does not satisfy HARD assertions: $v_b |"
        [[ $KEEP -eq 1 ]] || rm -rf "$work_b"
        return 1
    fi
    [[ $KEEP -eq 1 ]] || rm -rf "$work_b"

    # --- (c) untouched fixture must FAIL ---
    local work_c; work_c="$(mktemp -d "${TMPDIR:-/tmp}/refio-val-${id}-c-XXXXXX")"
    cp -R "$fixture/." "$work_c/"
    local v_c
    v_c="$(check_hard "$scenario" "$work_c" "$fixture" "" || true)"
    if [[ "$v_c" != FAIL* ]]; then
        echo "| $id | FAIL (c) | untouched fixture already PASSES the HARD assertions (scenario proves nothing) |"
        [[ $KEEP -eq 1 ]] || rm -rf "$work_c"
        return 1
    fi
    [[ $KEEP -eq 1 ]] || rm -rf "$work_c"

    echo "| $id | PASS | untouched: ${v_c} |"
    return 0
}

fails=0
echo "| scenario | verdict | detail |"
echo "|---|---|---|"
for s in "${SCENARIOS[@]}"; do
    validate_scenario "$s" || fails=$((fails+1))
done

if [[ $fails -gt 0 ]]; then
    echo "validate-scenarios: $fails scenario(s) FAILED validation" >&2
    exit 1
fi
echo "validate-scenarios: all ${#SCENARIOS[@]} scenario(s) validated OK" >&2
