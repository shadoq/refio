#!/usr/bin/env bash
#
# e2e-gate.sh - pass-rate gate over N repeated e2e runs per scenario.
#
# A single run of a local model is a coin flip; this gate runs each scenario N times
# through e2e-run.sh, aggregates the per-run verdicts from results.jsonl (E2E_OUT_DIR
# mechanism), prints a scenario x pass-rate table and goes green only when EVERY
# scenario reaches the threshold. "pass" for a single run = the HARD assertion tier
# (the LLM judge stays SOFT/advisory and does not count here).
#
# Run under `bash` (not zsh): bash tools/e2e/e2e-gate.sh ...
#
# CONSENT: every run spends tokens / local GPU time and writes into throwaway temp
# projects (via e2e-run.sh). A human approves the concrete command before it runs.
#
# Usage:
#   e2e-gate.sh [--model <provider/model>] [--runs N] [--threshold R] [--out <dir>] \
#               [<e2e-run.sh opts>...] (--all | <id|scenario.json> [more...])
#
#   --runs N        runs per scenario (default 5)
#   --threshold R   per-scenario pass-rate floor; decimal (0.8) or fraction (4/5); default 0.8
#   --model M       tested model, forwarded to e2e-run.sh (also labels results.jsonl records)
#   --out <dir>     persist runs + results.jsonl here (default: fresh temp dir)
#   --all           run every scenario under test_data/e2e/
#   any other args are forwarded verbatim to e2e-run.sh (e.g. --max-cost, --ollama-host).
#
# Exit codes: 0 = every scenario >= threshold, 1 = at least one below, 2 = usage/setup error.
#
# Example:
#   bash tools/e2e/e2e-gate.sh --model ollama/qwen3.5:4b --runs 5 --threshold 4/5 \
#       increase-retry-count find-and-fix-null-check
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RUNS=5
THRESHOLD="0.8"
OUT=""
MODEL=""
ALL=0
SCENARIOS=()
EXTRA_ARGS=()

die() { echo "ERROR: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --runs)      RUNS="$2"; shift 2 ;;
        --threshold) THRESHOLD="$2"; shift 2 ;;
        --out)       OUT="$2"; shift 2 ;;
        --model)     MODEL="$2"; shift 2 ;;
        --all)       ALL=1; shift ;;
        -h|--help)   sed -n '2,32p' "$0"; exit 0 ;;
        --cli|--max-cost|--ollama-host|--ollama-ctx|--config|--auto-approve)
                     EXTRA_ARGS+=("$1" "$2"); shift 2 ;;
        --no-auto-approve|--keep)
                     EXTRA_ARGS+=("$1"); shift ;;
        -*)          die "unknown flag: $1" ;;
        *)           SCENARIOS+=("$1"); shift ;;
    esac
done

command -v jq >/dev/null 2>&1 || die "'jq' not found on PATH"
[[ "$RUNS" =~ ^[0-9]+$ && "$RUNS" -ge 1 ]] || die "--runs must be a positive integer"
if [[ $ALL -eq 0 && ${#SCENARIOS[@]} -eq 0 ]]; then
    die "no scenarios selected (pass ids/paths or --all)"
fi

# Threshold accepts a decimal ("0.8") or a fraction ("4/5").
case "$THRESHOLD" in
    */*) THRESHOLD="$(awk -F/ '{ if ($2+0 == 0) exit 1; printf "%.6f", $1/$2 }' <<<"$THRESHOLD")" \
             || die "--threshold fraction has zero denominator" ;;
esac
awk -v t="$THRESHOLD" 'BEGIN { exit !(t >= 0 && t <= 1) }' \
    || die "--threshold must be in [0..1] (decimal or a/b fraction)"

if [[ -z "$OUT" ]]; then
    OUT="$(mktemp -d "${TMPDIR:-/tmp}/refio-e2e-gate-XXXXXX")"
fi
mkdir -p "$OUT"
: > "$OUT/results.jsonl"   # each gate invocation starts from a clean ledger

RUN_ARGS=("${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}")
[[ -n "$MODEL" ]] && RUN_ARGS+=(--model "$MODEL")
if [[ $ALL -eq 1 ]]; then
    RUN_ARGS+=(--all)
else
    RUN_ARGS+=("${SCENARIOS[@]}")
fi

echo "gate: $RUNS run(s) per scenario, threshold=$THRESHOLD -> $OUT" >&2
for (( i=1; i<=RUNS; i++ )); do
    echo "-- run $i/$RUNS --" >&2
    # A failing run is an expected, recorded outcome; it must not abort the loop.
    # Invoke via `bash` so a checkout without the execute bit still works.
    E2E_OUT_DIR="$OUT" E2E_RUN_INDEX="$i" \
        bash "$SCRIPT_DIR/e2e-run.sh" "${RUN_ARGS[@]}" || true
done

RESULTS="$OUT/results.jsonl"
[[ -s "$RESULTS" ]] || die "no results recorded in $RESULTS (did every run crash before assertion?)"

# Aggregate: per scenario, pass = HARD verdict PASS (the SOFT judge object is ignored here).
SUMMARY="$(jq -s '
    group_by(.scenario)
    | map({scenario: .[0].scenario,
           total: length,
           pass: ([.[] | select(.verdict == "PASS")] | length)})
    | map(.rate = (.pass / .total))
    | sort_by(.scenario)' "$RESULTS")"

echo
echo "| scenario | pass | runs | pass-rate | gate |"
echo "|---|---|---|---|---|"
GATE_FAIL=0
while IFS=$'\t' read -r scen pass total rate; do
    verdict="OK"
    if ! awk -v r="$rate" -v t="$THRESHOLD" 'BEGIN { exit !(r >= t) }'; then
        verdict="BELOW"
        GATE_FAIL=1
    fi
    printf '| %s | %s | %s | %.2f | %s |\n' "$scen" "$pass" "$total" "$rate" "$verdict"
done < <(jq -r '.[] | [.scenario, .pass, .total, .rate] | @tsv' <<<"$SUMMARY")

echo
if [[ $GATE_FAIL -eq 0 ]]; then
    echo "GATE GREEN: every scenario >= $THRESHOLD (results: $RESULTS)"
    exit 0
fi
echo "GATE RED: at least one scenario below $THRESHOLD (results: $RESULTS)"
exit 1
