#!/usr/bin/env bash
#
# validate-analysis-scenarios.sh - deterministic quality gate for PLAN-mode analysis
# scenarios (no LLM call, no golden diff).
#
# Analysis scenarios are read-only: the agent inspects a fixture and answers a question,
# it does not edit files. So they have no golden solution to overlay (the golden-based
# validate-scenarios.sh does not apply). Instead a scenario is trusted only when it
# survives three deterministic checks:
#   (a) fixture sanity    - the fixture dir exists, the prompt file exists and opens with
#                           "Do not modify any files.", and every path listed in
#                           assert.file_unchanged actually exists inside the fixture.
#   (b) parse step        - every *.py in the fixture byte-compiles (py_compile) and every
#                           *.js passes `node --check`. Files with no offline parser
#                           (.kt, .tsx, .sql, .sh, .yaml, Dockerfile, ...) are listed as
#                           skipped, not failed.
#   (c) answerable needle - assert.needle_in_output.regex is a valid regex AND its expected
#                           answer term genuinely occurs somewhere in the fixture (grep -rE,
#                           >= 1 hit), so the question can be answered from the provided code.
#                           A scenario whose needle is a value NOT literally present in any
#                           file must opt out with "needle_not_in_fixture": true and is then
#                           reported as an explicit exception rather than a failure.
#
# Scenarios are selected by the "category":"analysis" field. Run-time-only assertions
# (session status, no_context_overflow, tool_order) still belong to e2e-run.sh.
#
# Usage:
#   validate-analysis-scenarios.sh --list          # analysis scenarios discovered
#   validate-analysis-scenarios.sh --all           # validate every analysis scenario
#   validate-analysis-scenarios.sh <id|file> ...   # validate selected scenarios
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
E2E_DIR="$REPO_ROOT/test_data/e2e"

LIST=0
ALL=0
SCENARIOS=()

die() { echo "ERROR: $*" >&2; exit 2; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --list) LIST=1; shift ;;
        --all)  ALL=1; shift ;;
        -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
        -*) die "unknown flag: $1" ;;
        *)  SCENARIOS+=("$1"); shift ;;
    esac
done

require_cmd jq
require_cmd python3

HAVE_NODE=0
command -v node >/dev/null 2>&1 && HAVE_NODE=1

is_analysis() {
    [[ "$(jq -r '.category // empty' "$1" 2>/dev/null)" == "analysis" ]]
}

discover_analysis() {
    local f
    for f in "$E2E_DIR"/*.json; do
        [[ -e "$f" ]] || continue
        is_analysis "$f" && echo "$f"
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
    echo "Analysis scenarios (category==analysis) in ${E2E_DIR#"$REPO_ROOT"/}:" >&2
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        printf '  %-28s %s\n' "$(jq -r '.id' "$f")" "${f#"$REPO_ROOT"/}" >&2
    done < <(discover_analysis)
    exit 0
fi

if [[ $ALL -eq 1 ]]; then
    while IFS= read -r f; do [[ -n "$f" ]] && SCENARIOS+=("$f"); done < <(discover_analysis)
else
    [[ ${#SCENARIOS[@]} -gt 0 ]] || die "no scenarios given (use --all, --list, or ids)"
    resolved=()
    for s in "${SCENARIOS[@]}"; do resolved+=("$(resolve_scenario "$s")"); done
    SCENARIOS=("${resolved[@]}")
fi
[[ ${#SCENARIOS[@]} -gt 0 ]] || die "no analysis scenarios found"

validate_scenario() {
    local scenario="$1"
    local sdir id fixture prompt_file
    sdir="$(cd "$(dirname "$scenario")" && pwd)"
    id="$(jq -r '.id' "$scenario")"
    fixture="$sdir/$(jq -r '.fixture' "$scenario")"
    prompt_file="$sdir/$(jq -r '.prompt_file // empty' "$scenario")"

    # --- (a) fixture sanity ---
    [[ -d "$fixture" ]] || { echo "| $id | FAIL (a) | fixture dir missing: $fixture |"; return 1; }
    if [[ -z "$(jq -r '.prompt_file // empty' "$scenario")" || ! -f "$prompt_file" ]]; then
        echo "| $id | FAIL (a) | prompt_file missing |"; return 1
    fi
    if ! head -n1 "$prompt_file" | grep -q "Do not modify any files."; then
        echo "| $id | FAIL (a) | prompt must open with 'Do not modify any files.' |"; return 1
    fi
    local fu_count fidx fu missing=0
    fu_count="$(jq -r '(.assert.file_unchanged // []) | length' "$scenario")"
    [[ "$fu_count" -gt 0 ]] || { echo "| $id | FAIL (a) | assert.file_unchanged is empty |"; return 1; }
    for (( fidx=0; fidx<fu_count; fidx++ )); do
        fu="$(jq -r ".assert.file_unchanged[$fidx]" "$scenario")"
        [[ -f "$fixture/$fu" ]] || { echo "  (a) file_unchanged path missing in fixture: $fu" >&2; missing=1; }
    done
    [[ $missing -eq 0 ]] || { echo "| $id | FAIL (a) | file_unchanged lists a path not in the fixture |"; return 1; }

    # --- (b) parse step: py_compile every .py, node --check every .js ---
    local f bad=0 skipped=0
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        python3 -m py_compile "$f" 2>/dev/null || { echo "  (b) python file does not compile: ${f#"$fixture"/}" >&2; bad=1; }
    done < <(find "$fixture" -name '*.py' -type f)
    find "$fixture" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
    if [[ $HAVE_NODE -eq 1 ]]; then
        while IFS= read -r f; do
            [[ -n "$f" ]] || continue
            node --check "$f" 2>/dev/null || { echo "  (b) js file does not parse: ${f#"$fixture"/}" >&2; bad=1; }
        done < <(find "$fixture" -name '*.js' -type f)
    else
        skipped=1
    fi
    [[ $bad -eq 0 ]] || { echo "| $id | FAIL (b) | a fixture source file does not parse |"; return 1; }

    # --- (c) answerable needle: valid regex AND its term occurs in the fixture ---
    local needle exempt
    needle="$(jq -r '.assert.needle_in_output.regex // empty' "$scenario")"
    exempt="$(jq -r '.assert.needle_not_in_fixture // false' "$scenario")"
    if [[ -z "$needle" ]]; then
        echo "| $id | FAIL (c) | assert.needle_in_output.regex is required for analysis scenarios |"; return 1
    fi
    # Validity: a syntactically invalid ERE makes grep exit >=2; a valid one exits 0 (match)
    # or 1 (no match) against a probe line.
    local grep_rc=0
    printf 'probe\n' | grep -E -- "$needle" >/dev/null 2>&1 || grep_rc=$?
    if [[ $grep_rc -gt 1 ]]; then
        echo "| $id | FAIL (c) | needle regex is not a valid ERE: /$needle/ |"; return 1
    fi
    if [[ "$exempt" == "true" ]]; then
        echo "| $id | PASS (needle exempt) | needle /$needle/ is a value not in the fixture (declared exception) |"
        return 0
    fi
    local hits
    hits="$(grep -rElE -- "$needle" "$fixture" 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "${hits:-0}" -lt 1 ]]; then
        echo "| $id | FAIL (c) | needle /$needle/ not found anywhere in the fixture (unanswerable) |"; return 1
    fi

    local note=""
    [[ $skipped -eq 1 ]] && note=" (node absent: .js parse skipped)"
    echo "| $id | PASS | needle found in $hits file(s)${note} |"
    return 0
}

fails=0
echo "| scenario | verdict | detail |"
echo "|---|---|---|"
for s in "${SCENARIOS[@]}"; do
    validate_scenario "$s" || fails=$((fails+1))
done

if [[ $fails -gt 0 ]]; then
    echo "validate-analysis-scenarios: $fails scenario(s) FAILED validation" >&2
    exit 1
fi
echo "validate-analysis-scenarios: all ${#SCENARIOS[@]} scenario(s) validated OK" >&2
