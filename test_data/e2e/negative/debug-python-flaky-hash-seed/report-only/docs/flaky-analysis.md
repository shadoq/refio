# FLAKY-77 analysis

## Findings

- `rank()` builds its entry list from `set(scores)` and sorts only by score,
  so tied names come out in hash randomization order; the tie-break required
  by docs/tie-break.md is not implemented.
- `merge()` deletes from `combined` while iterating over it, which raises
  RuntimeError: dictionary changed size during iteration whenever a dropped
  key is not the last one visited.
- `daily_report()` calls datetime.now() directly, so the report test can fail
  when midnight passes between the test's own now() call and the report's.

## Recommendation

Fix all three in pyflaky/ as a follow-up; pinning PYTHONHASHSEED in CI would
only mask the ordering defect.
