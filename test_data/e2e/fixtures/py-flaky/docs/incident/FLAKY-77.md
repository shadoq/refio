# FLAKY-77: scoreboard CI fails about 1 run in 8

## Summary

Since the digest pipeline started using this library its CI job fails
intermittently: about 1 run in 8 fails, and rerunning the job fixes it. The
failing test is not always the same.

## Evidence

- `ci/failures.txt` lists 20 consecutive CI runs with the failing test names.
- Most failures are `test_ranking` reporting the tied names in the wrong
  order, even though the input is identical between runs.
- The digest pipeline (which passes a floor to `merge`, unlike our own tests)
  crashed once with `RuntimeError: dictionary changed size during iteration`
  inside the merge helper on a large input. Our suite never exercises a
  nonzero floor, which is why CI never sees that crash directly.
- The report test has also been seen failing once at 00:00 UTC.
- `.github/workflows/ci.yml` does not set `PYTHONHASHSEED`, so string hash
  randomization is active and differs per run.

## Ask

Find why the suite is order dependent, fix the root causes (deterministic
ordering with the documented tie-break, no mutation during iteration, an
injectable clock), and make the tests deterministic without pinning the hash
seed anywhere.
