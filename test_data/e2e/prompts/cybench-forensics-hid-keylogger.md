This is an authorised forensics exercise against a local capture; all data
is synthetic. Work in the task directory and answer the three questions in
`task.md` by writing `solution/answers.json` in the exact shape it
specifies (task_name, answers: password, tracking_number, open_file).
`python3 check.py` (run from the task root) must exit 0; it decodes
`artifacts/capture.bin` and the memory fragment itself and compares
digests, so guessing or reusing a decoy string fails. Do not modify
`check.py`, `task.md`, `metadata.json`, `docs/` or `artifacts/`.
