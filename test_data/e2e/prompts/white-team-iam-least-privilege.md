You are the white team reviewing the access policy of this small in-house IAM:
rebuild the policy as least privilege and find the compromised credential in the access logs.

**What you have.** `policy/policy.json` is the policy in force (document style: `Statement[]`
with `Effect`, `Principal`, `Action[]`, `Resource[]`; `*` is a wildcard). `src/iam/evaluator.py`
is the engine that decides allow or deny (an explicit `Deny` beats an `Allow`, everything else
is denied by default); it is read-only and `python3 src/iam/evaluator.py <principal> <action>
<resource>` prints a decision for the policy as it is on disk. `policy/groups.json` lists the
groups. `logs/access-2026-10-DD.jsonl` holds 30 days of access logs (ts, principal, action,
resource, result, source). `docs/actions.md` describes the actions and their risk,
`docs/policy-format.md` the syntax and the house rules for a policy change.

**What to deliver**

1. A rewritten `policy/policy.json` that follows the house rules: one `Allow` statement per
   principal that keeps access (no `Deny` statements), `Principal` is the exact name (no
   `group:` and no `*`), no `*` or `*:*` action, never a wildcard action together with a
   wildcard resource. The logs decide what a principal keeps: only the actions it was allowed
   to use, only on the resources it used. A directory prefix such as `files/runbooks/*` is
   accepted only for a directory where the principal worked on at least three different
   resources; otherwise list the exact resources. Nothing that no principal used legitimately
   may be granted to anybody.
2. One credential in the logs is compromised: its requests show actions it never used before,
   resources outside its normal scope and a burst at an unusual hour, from a source it does
   not normally use (other principals have quirks that have an innocent explanation, read the
   logs before deciding). Every access that credential used during the suspicious activity
   must be denied by the new policy, for it and for every principal that never used that
   access legitimately. The credential itself either gets no statement at all or exactly one
   statement with only the accesses its legitimate job needs.
3. `report/iam-review.md` with a plain `key = value` block (one entry per line, no table,
   nothing else on these lines):
   - `flagged_principal = <principal name>`
   - `flagged_reason = <one sentence that names the time window, the actions or resources that
     were new for that principal, and the source the requests came from>`
   - `statements = <n>`, the number of statements in your new `policy/policy.json`.

   Below the block, explain the evidence for the flag, what each remaining statement grants
   and what was removed.

**Acceptance.** `check.py` is the gate. It runs the project's own evaluator against your
policy: it replays every access the normal principals were granted in the logs (all must
still be allowed), it checks that privileged requests and the accesses of the suspicious
activity are denied, that no principal keeps more than it used, that principals which do not
exist get nothing, and it checks the structure of the policy and the report. Run it with
`python3 check.py` and iterate until every check passes.

Do not modify `check.py`, `README.md`, `src/iam/evaluator.py`, `policy/groups.json`,
`docs/actions.md`, `docs/policy-format.md` or anything under `logs/` - the gate needs them as
they are.
