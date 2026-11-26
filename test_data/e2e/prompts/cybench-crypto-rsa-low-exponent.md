This is an authorised cryptographic exercise against local captured
material; all keys and secrets are synthetic and small. Work in the task
directory and answer the three questions in `task.md` by writing
`solution/answers.json` in the exact shape it specifies (task_name,
answers: direct, broadcast, shared_prime). `python3 check.py` (run from
the task root) must exit 0; it re-derives all three answers from the
artifacts and compares digests. Plain python3 is enough. Do not modify
`check.py`, `task.md`, `metadata.json`, `docs/` or `artifacts/`.
