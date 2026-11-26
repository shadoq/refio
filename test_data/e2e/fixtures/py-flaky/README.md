# scoreboard

Small pure Python 3 library used by the weekly digest: it merges score
dictionaries from two sources, ranks names by score (see `docs/tie-break.md`
for the documented order on ties) and renders a dated report line list.

## Layout

- `pyflaky/ranking.py` - `rank(scores)`, the ranked name list
- `pyflaky/merge.py` - `merge(base, extra, floor)`, score merging
- `pyflaky/report.py` - `daily_report(scores)`, dated text report
- `tests/` - the unittest suite run in CI
- `check.py` - offline verification used during incidents

## Running

    python3 -m unittest discover -s tests
    python3 check.py

Standard library only, no dependencies. CI runs on Python 3.12.
