#!/usr/bin/env bash
#
# e2e-stats.sh - aggregate benchmark statistics from e2e results.jsonl (produced by e2e-run.sh when
# E2E_OUT_DIR is set). Reports, as Markdown on stdout:
#   - per-model outcomes (runs, pass-rate, avg iterations / output tokens / cost / duration, failure modes),
#   - a scenario x model pass-rate matrix,
#   - a tool-use histogram (which tools ran, how often, in how many runs),
#   - API-error counts (errorType -> count).
#
# It is READ-ONLY: no LLM, no network, no mutation - it only reads the verdict records the runner
# already wrote. The enriched fields (iterations/tools/apiErrors/...) are additive; older results.jsonl
# files without them still aggregate (missing fields default to 0 / {}).
#
# Usage:
#   e2e-stats.sh <results-dir | results.jsonl> [more ...]   # print report to stdout
#   e2e-stats.sh --out <file> <results ...>                 # also write the report to <file>
#   e2e-stats.sh --self-test                                # offline check of the aggregation (no data)
#   e2e-stats.sh --help
#
# Multiple inputs are concatenated, so a multi-model / multi-dir sweep can be aggregated at once:
#   e2e-stats.sh /tmp/sweep/model-a /tmp/sweep/model-b
#
set -euo pipefail

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not found on PATH" >&2; exit 2; }; }
require_cmd jq

OUT=""
SELF_TEST=0
INPUTS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --out)       OUT="$2"; shift 2 ;;
        --self-test) SELF_TEST=1; shift ;;
        -h|--help)   sed -n '2,26p' "$0"; exit 0 ;;
        -*)          echo "ERROR: unknown flag: $1" >&2; exit 2 ;;
        *)           INPUTS+=("$1"); shift ;;
    esac
done

# Resolve inputs (dir -> dir/results.jsonl; file -> file) into one concatenated JSONL blob on stdout.
gather_jsonl() {
    local in
    for in in "$@"; do
        if [[ -d "$in" && -f "$in/results.jsonl" ]]; then
            cat "$in/results.jsonl"
        elif [[ -f "$in" ]]; then
            cat "$in"
        else
            echo "ERROR: no results.jsonl at '$in'" >&2; exit 2
        fi
    done
}

# The whole report as Markdown. Reads a JSONL blob on stdin; jq slurps it into one array.
render_report() {
    jq -rs '
      def pctf(x): ((x*100)|floor|tostring) + "%";
      . as $data
      | ($data|length) as $N
      | if $N == 0 then "No records to aggregate." else
      (
        "# e2e benchmark statistics",
        "",
        "\($N) run(s) · \(($data|map(.model)|unique|length)) model(s) · \(($data|map(.scenario)|unique|length)) scenario(s) · overall pass-rate \(pctf(([$data[]|select(.verdict=="PASS")]|length)/$N))",
        "",
        "## Per-model",
        "",
        "| model | runs | pass | pass-rate | avg iters | avg tokOut | tok/s | total cost | avg ms | failure modes |",
        "|---|---|---|---|---|---|---|---|---|---|",
        ( $data
          | group_by(.model)[]
          | (map(select(.verdict=="PASS"))|length) as $p
          | length as $n
          | ((map(.durationMs//0)|add)/1000) as $secs
          | ( [ .[] | select(.verdict!="PASS") | .failure_mode ]
              | reduce .[] as $m ({}; .[$m] = ((.[$m]//0)+1))
              | to_entries | map("\(.key):\(.value)") | join(" ") ) as $fmodes
          | "| \(.[0].model) | \($n) | \($p) | \(pctf($p/$n)) | " +
            "\(((map(.iterations//0)|add)/$n)|floor) | " +
            "\(((map(.tokensOut//0)|add)/$n)|floor) | " +
            "\(if $secs>0 then (((map(.tokensOut//0)|add)/$secs)*10|floor)/10 else 0 end) | " +
            "\(((map(.costUsd//0)|add)*100000|floor)/100000) | " +
            "\(((map(.durationMs//0)|add)/$n)|floor) | \(if $fmodes=="" then "-" else $fmodes end) |"
        ),
        "",
        "## Scenario x model (pass-rate)",
        "",
        ( ($data|map(.model)|unique) as $models
          | ($data|map(.scenario)|unique) as $scen
          | ( "| scenario \\ model | " + ($models|join(" | ")) + " |" ),
            ( "|" + ((["---"] + ($models|map("---")))|join("|")) + "|" ),
            ( $scen[] as $s
              | "| \($s) | " + ( $models | map( . as $m
                  | [ $data[] | select(.scenario==$s and .model==$m) ] as $cell
                  | if ($cell|length)==0 then "-"
                    else "\((([$cell[]|select(.verdict=="PASS")]|length)/($cell|length)*100)|floor)% (\([$cell[]|select(.verdict=="PASS")]|length)/\($cell|length))"
                    end
                ) | join(" | ") ) + " |"
            )
        ),
        "",
        "## Scenario x model (avg seconds per run)",
        "",
        ( ($data|map(.model)|unique) as $models
          | ($data|map(.scenario)|unique) as $scen
          | ( "| scenario \\ model | " + ($models|join(" | ")) + " |" ),
            ( "|" + ((["---"] + ($models|map("---")))|join("|")) + "|" ),
            ( $scen[] as $s
              | "| \($s) | " + ( $models | map( . as $m
                  | [ $data[] | select(.scenario==$s and .model==$m) ] as $cell
                  | if ($cell|length)==0 then "-"
                    else "\((([$cell[]|.durationMs//0]|add)/($cell|length)/100|floor)/10)"
                    end
                ) | join(" | ") ) + " |"
            )
        ),
        "",
        "## Tool usage (all runs)",
        "",
        ( ( [ $data[] | (.tools // {}) | keys[] ] | unique ) as $tools
          | if ($tools|length)==0 then "No tool calls recorded." else
            ( "| tool | total calls | runs used | avg/run |", "|---|---|---|---|",
              ( $tools | map( . as $t | {
                  tool: $t,
                  total: ( [ $data[] | ((.tools//{})[$t] // 0) ] | add ),
                  runs:  ( [ $data[] | select((.tools//{})|has($t)) ] | length )
                })
                | sort_by(-.total)[]
                | "| \(.tool) | \(.total) | \(.runs) | \(((.total/$N)*100|floor)/100) |" )
            )
            end
        ),
        "",
        "## API errors",
        "",
        ( [ $data[] | (.apiErrors // {}) | to_entries[] ] as $errs
          | if ($errs|length)==0 then "None recorded." else
            ( "| error type | count |", "|---|---|",
              ( $errs | group_by(.key) | map({type:.[0].key, count:(map(.value)|add)}) | sort_by(-.count)[]
                | "| \(.type) | \(.count) |" ) )
            end
        )
      )
      end
    '
}

self_test() {
    echo "# e2e-stats.sh self-test (aggregation only - no data files, no LLM)" >&2
    local tmp; tmp="$(mktemp "${TMPDIR:-/tmp}/e2e-stats-selftest-XXXXXX")"
    cat > "$tmp" <<'JSONL'
{"scenario":"s1","model":"m-a","run":1,"verdict":"PASS","failure_mode":"none","status":"SUCCESS","costUsd":0,"tokensOut":100,"iterations":3,"durationMs":1000,"tools":{"read_file":2,"grep_search":1},"apiErrors":{}}
{"scenario":"s1","model":"m-a","run":2,"verdict":"FAIL","failure_mode":"loop","status":"INCOMPLETE","costUsd":0,"tokensOut":200,"iterations":20,"durationMs":5000,"tools":{"read_file":5},"apiErrors":{"TIMEOUT":1}}
{"scenario":"s2","model":"m-b","run":1,"verdict":"PASS","failure_mode":"none","status":"SUCCESS","costUsd":0.01,"tokensOut":50,"iterations":2,"durationMs":800,"tools":{"advance_code_editing":1},"apiErrors":{}}
JSONL
    local report fails=0
    report="$(render_report <"$tmp")"
    rm -f "$tmp"
    check() { grep -qF -- "$1" <<<"$report" || { echo "  !! expected report to contain: $1" >&2; fails=1; }; }
    # m-a: 2 runs, 1 pass -> 50%
    check "| m-a | 2 | 1 | 50% |"
    # read_file total across runs = 2 + 5 = 7, used in 2 runs
    check "| read_file | 7 | 2 |"
    # tool ranking: read_file (7) is the busiest tool
    check "## Tool usage (all runs)"
    # API error surfaced
    check "| TIMEOUT | 1 |"
    # scenario x model matrix header present
    check "| scenario \\ model | m-a | m-b |"
    if [[ $fails -eq 0 ]]; then echo "self-test OK" >&2; else echo "self-test FAILED" >&2; exit 1; fi
}

if [[ $SELF_TEST -eq 1 ]]; then
    self_test
    exit 0
fi

[[ ${#INPUTS[@]} -gt 0 ]] || { echo "ERROR: no input (pass a results dir/jsonl, or --self-test)" >&2; exit 2; }

REPORT="$(gather_jsonl "${INPUTS[@]}" | render_report)"
printf '%s\n' "$REPORT"
if [[ -n "$OUT" ]]; then
    printf '%s\n' "$REPORT" > "$OUT"
    echo "wrote $OUT" >&2
fi
