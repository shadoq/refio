#!/usr/bin/env bash
#
# gate.sh - N-runs stabilization gate over e2e scenario(s).
#
# Loops e2e-run.sh N times into a shared E2E_OUT_DIR (each run appends a verdict record to
# results.jsonl), then aggregates that into a pass-rate verdict via `cli --gate` - the canonical,
# unit-tested StabilizationGate in :core. Exit 0 = green, 1 = red.
#
# This is the measuring instrument for stabilization: it turns a single flaky run into a pass-rate
# you can compare against a floor and a baseline, so cuts to brittle scaffolding can be made safely.
#
# CONSENT: each run spends tokens / local GPU time and writes into a throwaway temp project (via
# e2e-run.sh). A human approves the concrete command before it runs.
#
# Usage:
#   gate.sh [--runs N] [--out <dir>] [--baseline R] [--min-pass-rate R] [--tolerance R] \
#           [<e2e-run.sh opts>...] <id|scenario.json> [<id|scenario.json>...]
#
#   --runs N           how many times to run each scenario (default 5)
#   --out <dir>        persist runs here (default: a fresh temp dir); contains results.jsonl + run.json copies
#   --baseline R       pass-rate [0..1] the gate must not regress below (optional)
#   --min-pass-rate R  absolute pass-rate floor [0..1] (default 1.0)
#   --tolerance R      allowed drop vs --baseline before red (default 0.0)
#   any other args are forwarded verbatim to e2e-run.sh (e.g. --model, --max-cost, --ollama-host).
#
# Example:
#   gate.sh --runs 10 --min-pass-rate 0.8 --model ollama/qwen3.5:4b find-and-fix-null-check
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLI="$REPO_ROOT/cli/build/install/cli/bin/cli"

RUNS=5
OUT=""
BASELINE=""
MIN_PASS_RATE="1.0"
TOLERANCE="0.0"
E2E_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --runs)          RUNS="$2"; shift 2 ;;
        --out)           OUT="$2"; shift 2 ;;
        --baseline)      BASELINE="$2"; shift 2 ;;
        --min-pass-rate) MIN_PASS_RATE="$2"; shift 2 ;;
        --tolerance)     TOLERANCE="$2"; shift 2 ;;
        *)               E2E_ARGS+=("$1"); shift ;;
    esac
done

[[ "$RUNS" =~ ^[0-9]+$ && "$RUNS" -ge 1 ]] || { echo "ERROR: --runs must be a positive integer" >&2; exit 2; }
[[ ${#E2E_ARGS[@]} -gt 0 ]] || { echo "ERROR: pass at least one scenario (id or path), plus optional e2e-run.sh opts" >&2; exit 2; }
[[ -x "$CLI" ]] || { echo "ERROR: CLI not built: $CLI (build it: ./gradlew :cli:installDist)" >&2; exit 2; }

if [[ -z "$OUT" ]]; then
    OUT="$(mktemp -d "${TMPDIR:-/tmp}/refio-gate-XXXXXX")"
fi
mkdir -p "$OUT"
: > "$OUT/results.jsonl"   # start each gate run from a clean ledger

echo "gate: $RUNS run(s) per scenario -> $OUT" >&2
for (( i=1; i<=RUNS; i++ )); do
    echo "── run $i/$RUNS ──" >&2
    # A failing run is an expected outcome, recorded in results.jsonl - it must not abort the loop.
    # Invoke via `bash` (not a direct exec) so the loop works regardless of e2e-run.sh's +x bit -
    # a checkout that lost the execute permission would otherwise fail every run with "Permission denied".
    E2E_OUT_DIR="$OUT" E2E_RUN_INDEX="$i" bash "$SCRIPT_DIR/e2e-run.sh" "${E2E_ARGS[@]}" || true
done

gate_args=(--gate "$OUT" --gate-min-pass-rate "$MIN_PASS_RATE" --gate-tolerance "$TOLERANCE")
[[ -n "$BASELINE" ]] && gate_args+=(--gate-baseline "$BASELINE")

# The CLI prints the human summary to stderr and the JSON report to stdout, and exits 0/1. Its exit
# code becomes this script's exit code (set -e on the last command), so `gate.sh ... && deploy` works.
"$CLI" "${gate_args[@]}"
