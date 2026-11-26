The job service in this directory is the worker behind incident INC-2141 (see
`docs/incident/INC-2141.md` and `logs/app.log`): since the 3.2.0 deploy the
worker process dies every 2 to 5 minutes with exit code 1.

Your task:

1. Reproduce the failure locally and diagnose the defect chain in `src/`: the
   background refresh path, the retry helper, and the logging around them.
2. Fix the root causes in `src/`: the worker must survive a failing dependency,
   the failing item must be reported with its id and the original error
   message, retries must stop after the configured number of attempts, the
   other items must still refresh, and the HTTP handlers must keep answering.
3. Prove it: run `node check.js` and iterate until every check prints PASS.

Do not modify `check.js`, `logs/`, `docs/`, `README.md` or `package.json`:
the fix belongs in `src/`. Your final message must include a line of the form
`ROOT CAUSE: <one sentence per defect>` and a line `FIX: <what you changed>`.
